package com.swordfish.lemuroid.lib.scraper

import android.content.Context
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ScreenScraperClient(
    private val context: Context? = null,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
) {
    data class DevCredentials(
        val devId: String,
        val devPassword: String,
        val softName: String = DEFAULT_SOFTNAME,
    )

    companion object {
        private const val SS_INFO_URL = "https://neoclone.screenscraper.fr/api2/jeuInfos.php"
        private const val SS_SEARCH_URL = "https://neoclone.screenscraper.fr/api2/jeuRecherche.php"
        private const val DEFAULT_SOFTNAME = "AlphaLemuroid"
        private const val CONFIG_PREFS_NAME = "screenscraper_config_cache"
        private const val KEY_DEV_ID = "ss_cfg_devid"
        private const val KEY_DEV_PASS = "ss_cfg_devpassword"
        private const val KEY_SOFT_NAME = "ss_cfg_softname"
        private const val KEY_FETCHED_AT = "ss_cfg_stamp"
        private const val KEY_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_RETRY_ATTEMPTS = 3
        private val RETRYABLE_HTTP_CODES = setOf(429, 430, 503)
        private val CONFIG_URLS = listOf(
            "https://api.alphalemuroid.workers.dev/ss/config",
            "https://api.alphalemuroid.indevs.in/ss/config",
        )
        private val CREDENTIAL_QUERY_KEYS = setOf("devid", "devpassword", "ssid", "sspassword", "softname")

        private val ROM_EXT_REGEX = Regex(
            "\\.(zip|7z|rar|nes|fds|sfc|smc|fig|swc|gb|gbc|gba|md|smd|gen|bin|cue|iso|chd|pbp|cso|n64|z64|v64|nds|3ds|cia|sms|gg|pce|ccd|ngp|ngc|ws|wsc|a26|a78|lnx|dos)$",
            RegexOption.IGNORE_CASE,
        )
        private val CATALOG_PREFIX_REGEX = Regex("^\\d{3,5}\\s*[-._]\\s*")
        private val PAREN_TAG_REGEX = Regex("\\s*\\([^)]*\\)")
        private val BRACKET_TAG_REGEX = Regex("\\s*\\[[^]]*]")
        private val SEPARATOR_REGEX = Regex("[_\\-]+")
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val DIACRITIC_REGEX = Regex("[\\u0300-\\u036f]")
        private val PUNCTUATION_STRIP_REGEX = Regex("['’:.!,&]")
        private val WORD_THE_REGEX = Regex("\\bthe\\b")
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
        private val DIGITS_ONLY_REGEX = Regex("^\\d+$")
        private val ROMAN_MAP = mapOf(
            "ii" to "2",
            "iii" to "3",
            "iv" to "4",
            "v" to "5",
            "vi" to "6",
            "vii" to "7",
            "viii" to "8",
            "ix" to "9",
            "x" to "10",
        )

        private val anonApiSemaphore = Semaphore(3)
        private val userApiSemaphore = Semaphore(5)
        private val credsMutex = Mutex()
        private val blockingCredsLock = Any()

        @Volatile
        private var cachedDevCreds: DevCredentials? = null

        @Volatile
        private var cachedDevCredsStamp: Long = 0L

        private val sharedHttpClient: OkHttpClient by lazy { defaultOkHttpClient() }

        private fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", DEFAULT_SOFTNAME)
                        .build()
                    chain.proceed(request)
                }
                .dispatcher(
                    Dispatcher().apply {
                        maxRequests = 32
                        maxRequestsPerHost = 12
                    },
                )
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        fun cleanSearchTitle(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw
                .trim()
                .replace(ROM_EXT_REGEX, "")
                .replace(CATALOG_PREFIX_REGEX, "")
                .replace(PAREN_TAG_REGEX, "")
                .replace(BRACKET_TAG_REGEX, "")
                .replace(SEPARATOR_REGEX, " ")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
        }

        internal fun normalizeComparable(raw: String?): String {
            val cleaned = cleanSearchTitle(raw)
            if (cleaned.isEmpty()) return ""
            val decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
            return DIACRITIC_REGEX.replace(decomposed, "")
                .lowercase(Locale.ROOT)
                .replace(PUNCTUATION_STRIP_REGEX, "")
                .replace(WORD_THE_REGEX, " ")
                .replace(NON_ALNUM_REGEX, " ")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
        }

        internal fun extractNumberTokens(normalizedTitle: String): String {
            return normalizedTitle
                .split(' ')
                .filter { it.isNotEmpty() }
                .map { ROMAN_MAP[it] ?: it }
                .filter { DIGITS_ONLY_REGEX.matches(it) }
                .joinToString(",")
        }

        internal fun scoreTitleMatch(queryTitle: String, candidateTitle: String): Int {
            val target = normalizeComparable(queryTitle)
            val cand = normalizeComparable(candidateTitle)
            if (target.isEmpty() || cand.isEmpty() || target.length < 2) return 0

            if (target == cand) return 100

            if (extractNumberTokens(target) != extractNumberTokens(cand)) {
                return 0
            }

            val targetSet = target.split(' ').filter { it.isNotEmpty() }.toSet()
            val candSet = cand.split(' ').filter { it.isNotEmpty() }.toSet()
            if (targetSet.isEmpty() || candSet.isEmpty()) return 0

            val common = targetSet.count { it in candSet }
            val targetCoverage = common.toDouble() / targetSet.size.toDouble()
            val candCoverage = common.toDouble() / candSet.size.toDouble()

            return if (targetCoverage == 1.0 && candCoverage >= 0.75) 85 else 0
        }

        internal fun pickBestScreenScraperMatch(jeuxRaw: Any?, searchTitle: String): JSONObject? {
            val list: List<JSONObject> = when (jeuxRaw) {
                is JSONArray -> (0 until jeuxRaw.length()).mapNotNull { jeuxRaw.optJSONObject(it) }
                is JSONObject -> listOf(jeuxRaw)
                else -> return null
            }
            if (list.isEmpty()) return null

            var best: JSONObject? = null
            var bestScore = 0

            for (jeu in list) {
                val candidateTitles = mutableListOf<String>()
                val noms = jeu.optJSONArray("noms")
                if (noms != null) {
                    for (i in 0 until noms.length()) {
                        val text = noms.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                        if (text != null) candidateTitles.add(text)
                    }
                }
                jeu.optString("nom").takeIf { it.isNotBlank() }?.let { candidateTitles.add(it) }

                var jeuScore = 0
                for (cand in candidateTitles) {
                    jeuScore = maxOf(jeuScore, scoreTitleMatch(searchTitle, cand))
                }

                if (jeuScore > bestScore) {
                    bestScore = jeuScore
                    best = jeu
                }
            }

            return if (bestScore >= 85) best else null
        }

        fun sanitizeMediaUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            val parsed = trimmed.toHttpUrlOrNull() ?: return trimmed
            if (!parsed.host.contains("screenscraper.fr", ignoreCase = true)) {
                return trimmed
            }
            val builder = parsed.newBuilder()
            CREDENTIAL_QUERY_KEYS.forEach { builder.removeAllQueryParameters(it) }
            return builder.build().toString()
        }

        fun invalidateCachedDevCreds(context: Context? = null) {
            cachedDevCreds = null
            cachedDevCredsStamp = 0L
            context?.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.clear()
                ?.apply()
        }

        fun getUserCredentials(context: Context?): Pair<String, String>? {
            val ctx = context ?: return null
            val appPrefs = runCatching { SharedPreferencesHelper.getSharedPreferences(ctx) }.getOrNull() ?: return null
            val user = appPrefs.getString("screenscraper_user", "").orEmpty().trim()
            val pass = appPrefs.getString("screenscraper_password", "").orEmpty().trim()
            return if (user.isNotEmpty() && pass.isNotEmpty()) user to pass else null
        }

        private fun readDevCredsFromPrefs(context: Context): DevCredentials? {
            val appPrefs = runCatching { SharedPreferencesHelper.getSharedPreferences(context) }.getOrNull()
            val customId = appPrefs?.getString("screenscraper_devid", "").orEmpty().trim()
            val customPass = appPrefs?.getString("screenscraper_devpassword", "").orEmpty().trim()
            if (customId.isNotEmpty() && customPass.isNotEmpty()) {
                return DevCredentials(customId, customPass, DEFAULT_SOFTNAME)
            }

            val prefs = context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE)
            val stamp = prefs.getLong(KEY_FETCHED_AT, 0L)
            val now = System.currentTimeMillis()
            if (now - stamp >= KEY_CACHE_TTL_MS) return null
            val devId = prefs.getString(KEY_DEV_ID, "").orEmpty().trim()
            val devPass = prefs.getString(KEY_DEV_PASS, "").orEmpty().trim()
            val softName = prefs.getString(KEY_SOFT_NAME, DEFAULT_SOFTNAME).orEmpty().trim().ifBlank { DEFAULT_SOFTNAME }
            return if (devId.isNotEmpty() && devPass.isNotEmpty()) {
                DevCredentials(devId, devPass, softName)
            } else null
        }

        private fun persistDevCreds(context: Context?, creds: DevCredentials) {
            val now = System.currentTimeMillis()
            cachedDevCreds = creds
            cachedDevCredsStamp = now
            context?.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(KEY_DEV_ID, creds.devId)
                ?.putString(KEY_DEV_PASS, creds.devPassword)
                ?.putString(KEY_SOFT_NAME, creds.softName)
                ?.putLong(KEY_FETCHED_AT, now)
                ?.apply()
        }

        private fun fetchDevCredsBlockingFromNetwork(client: OkHttpClient, context: Context?): DevCredentials? {
            for (configUrl in CONFIG_URLS) {
                val parsed = runCatching {
                    val req = Request.Builder()
                        .url(configUrl)
                        .header("User-Agent", DEFAULT_SOFTNAME)
                        .get()
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body?.string() ?: return@use null
                        val obj = JSONObject(body)
                        val devId = obj.optString("devid").trim()
                        val devPass = obj.optString("devpassword").trim()
                        val softName = obj.optString("softname").trim().ifBlank { DEFAULT_SOFTNAME }
                        if (devId.isNotEmpty() && devPass.isNotEmpty()) {
                            DevCredentials(devId, devPass, softName)
                        } else null
                    }
                }.getOrNull()
                if (parsed != null) {
                    persistDevCreds(context, parsed)
                    return parsed
                }
            }
            return null
        }

        fun getDevCredsBlocking(
            context: Context? = null,
            forceRefresh: Boolean = false,
            client: OkHttpClient = sharedHttpClient,
        ): DevCredentials? {
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                val mem = cachedDevCreds
                if (mem != null && now - cachedDevCredsStamp < KEY_CACHE_TTL_MS) {
                    return mem
                }
                if (context != null) {
                    readDevCredsFromPrefs(context)?.let {
                        cachedDevCreds = it
                        cachedDevCredsStamp = now
                        return it
                    }
                }
            }
            synchronized(blockingCredsLock) {
                if (!forceRefresh) {
                    val mem = cachedDevCreds
                    if (mem != null && System.currentTimeMillis() - cachedDevCredsStamp < KEY_CACHE_TTL_MS) {
                        return mem
                    }
                }
                return fetchDevCredsBlockingFromNetwork(client, context)
            }
        }

        private fun resolveDirectMediaEndpoint(mediaParam: String?): String {
            val lower = mediaParam.orEmpty().lowercase(Locale.ROOT)
            return when {
                lower.startsWith("manuel") -> "mediaManuelJeu.php"
                lower.startsWith("video") -> "mediaVideoJeu.php"
                else -> "mediaJeu.php"
            }
        }

        fun resolvePlayableVideoUrl(rawUrl: String, context: Context? = null): String {
            val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return rawUrl
            return attachCredentialsIfNeeded(parsed, context).toString()
        }

        fun attachCredentialsIfNeeded(
            url: HttpUrl,
            context: Context? = null,
            userId: String? = null,
            userPass: String? = null,
        ): HttpUrl {
            val isLegacyProxyMedia = url.host.contains("alphalemuroid.", ignoreCase = true) &&
                url.encodedPath.startsWith("/screenscraper/media", ignoreCase = true)

            val targetUrl = if (isLegacyProxyMedia) {
                val systemEid = url.queryParameter("systemeid")
                val jeuId = url.queryParameter("jeuid")
                val media = url.queryParameter("media")
                if (!systemEid.isNullOrBlank() && !jeuId.isNullOrBlank() && !media.isNullOrBlank()) {
                    val endpoint = resolveDirectMediaEndpoint(media)
                    "https://neoclone.screenscraper.fr/api2/$endpoint".toHttpUrlOrNull()!!
                        .newBuilder()
                        .addQueryParameter("systemeid", systemEid)
                        .addQueryParameter("jeuid", jeuId)
                        .addQueryParameter("media", media)
                        .build()
                } else {
                    return url
                }
            } else {
                url
            }

            if (!targetUrl.host.contains("screenscraper.fr", ignoreCase = true) ||
                !targetUrl.encodedPath.contains("/api2/", ignoreCase = true) ||
                !targetUrl.encodedPath.endsWith(".php", ignoreCase = true)
            ) {
                return targetUrl
            }

            val hasDevId = !targetUrl.queryParameter("devid").isNullOrBlank() &&
                !targetUrl.queryParameter("devpassword").isNullOrBlank()
            val creds = if (hasDevId) null else getDevCredsBlocking(context)
            val storedUserCreds = getUserCredentials(context)
            val effectiveUser = userId?.trim()?.takeIf { it.isNotEmpty() } ?: storedUserCreds?.first
            val effectivePass = userPass?.trim()?.takeIf { it.isNotEmpty() } ?: storedUserCreds?.second

            val builder = targetUrl.newBuilder()
            if (creds != null) {
                builder.setQueryParameter("devid", creds.devId)
                builder.setQueryParameter("devpassword", creds.devPassword)
                if (targetUrl.queryParameter("softname").isNullOrBlank()) {
                    builder.setQueryParameter("softname", creds.softName)
                }
            }
            if (!effectiveUser.isNullOrBlank() && !effectivePass.isNullOrBlank()) {
                builder.setQueryParameter("ssid", effectiveUser)
                builder.setQueryParameter("sspassword", effectivePass)
            }
            return builder.build()
        }
    }

    suspend fun resolveDevCreds(
        forceRefresh: Boolean = false,
        customDevId: String? = null,
        customDevPassword: String? = null,
    ): DevCredentials? = withContext(Dispatchers.IO) {
        val customId = customDevId?.trim().orEmpty()
        val customPass = customDevPassword?.trim().orEmpty()
        if (customId.isNotEmpty() && customPass.isNotEmpty()) {
            return@withContext DevCredentials(customId, customPass, DEFAULT_SOFTNAME)
        }
        credsMutex.withLock {
            getDevCredsBlocking(context, forceRefresh = forceRefresh, client = okHttpClient)
        }
    }

    suspend fun ensureDevCredsLoaded(): Boolean {
        return resolveDevCreds(forceRefresh = false) != null
    }

    private fun buildBaseUrlBuilder(
        baseUrl: String,
        creds: DevCredentials,
        systemEid: Int,
        userId: String?,
        userPass: String?,
    ): HttpUrl.Builder {
        val builder = baseUrl.toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("devid", creds.devId)
            .addQueryParameter("devpassword", creds.devPassword)
            .addQueryParameter("softname", creds.softName)
            .addQueryParameter("output", "json")
            .addQueryParameter("systemeid", systemEid.toString())
        if (!userId.isNullOrBlank() && !userPass.isNullOrBlank()) {
            builder.addQueryParameter("ssid", userId.trim())
            builder.addQueryParameter("sspassword", userPass.trim())
        }
        return builder
    }

    private fun parseJsonObjectSafe(rawBody: String?): JSONObject? {
        if (rawBody.isNullOrBlank()) return null
        val start = rawBody.indexOf('{')
        if (start < 0) return null
        val trimmed = if (start > 0) rawBody.substring(start) else rawBody
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    private suspend fun executeSsGet(url: HttpUrl): Pair<Int, String?> {
        for (attempt in 0..MAX_RETRY_ATTEMPTS) {
            var retryDelayMs: Long? = null
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", DEFAULT_SOFTNAME)
                    .get()
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return resp.code to resp.body?.string()
                    }
                    if (resp.code !in RETRYABLE_HTTP_CODES || attempt == MAX_RETRY_ATTEMPTS) {
                        android.util.Log.w(
                            "ScreenScraperClient",
                            "ScreenScraper HTTP ${resp.code} on ${url.encodedPath} (attempt=${attempt + 1})",
                        )
                        return resp.code to null
                    }
                    val retryAfterSec = resp.header("Retry-After")?.toLongOrNull()
                    retryDelayMs = retryAfterSec?.times(1000L)?.coerceIn(400L, 5000L)
                        ?: (600L * (attempt + 1) + Random.nextLong(80L, 320L))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (attempt == MAX_RETRY_ATTEMPTS) return 0 to null
                retryDelayMs = 350L * (attempt + 1)
            }
            if (retryDelayMs != null) {
                delay(retryDelayMs)
            }
        }
        return 0 to null
    }

    private suspend fun executeWithCredsRefresh(
        hasPersonalUser: Boolean,
        customDevId: String?,
        customDevPassword: String?,
        buildUrl: (DevCredentials) -> HttpUrl,
    ): Pair<Int, String?> {
        val semaphore = if (hasPersonalUser) userApiSemaphore else anonApiSemaphore
        return semaphore.withPermit {
            val initialCreds = resolveDevCreds(
                forceRefresh = false,
                customDevId = customDevId,
                customDevPassword = customDevPassword,
            ) ?: return@withPermit 0 to null

            val result = executeSsGet(buildUrl(initialCreds))
            if (result.first == 403 && customDevId.isNullOrBlank() && customDevPassword.isNullOrBlank()) {
                val refreshedCreds = resolveDevCreds(forceRefresh = true) ?: return@withPermit result
                if (refreshedCreds != initialCreds) {
                    return@withPermit executeSsGet(buildUrl(refreshedCreds))
                }
            }
            result
        }
    }

    suspend fun scrapeGame(
        systemId: String,
        fileName: String,
        fallbackTitle: String? = null,
        crc32: String? = null,
        fileSizeBytes: Long? = null,
        userId: String? = null,
        userPassword: String? = null,
        customDevId: String? = null,
        customDevPassword: String? = null,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val systemEid = ScreenScraperSystemMapper.getSystemEid(systemId) ?: return@withContext null
        val storedCreds = getUserCredentials(context)
        val cleanUser = userId?.trim()?.takeIf { it.isNotEmpty() } ?: storedCreds?.first
        val cleanPass = userPassword?.trim()?.takeIf { it.isNotEmpty() } ?: storedCreds?.second
        val hasPersonalUser = cleanUser != null && cleanPass != null
        val cleanCrc = crc32?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "0" }
        val romName = fileName.trim()
        val cleanFallback = cleanSearchTitle(fallbackTitle ?: romName)

        if (romName.isNotEmpty()) {
            val (_, infoBody) = executeWithCredsRefresh(hasPersonalUser, customDevId, customDevPassword) { creds ->
                buildBaseUrlBuilder(SS_INFO_URL, creds, systemEid, cleanUser, cleanPass).apply {
                    addQueryParameter("romnom", romName)
                    addQueryParameter("romtype", "rom")
                    if (cleanCrc != null) addQueryParameter("crc", cleanCrc)
                    if (fileSizeBytes != null && fileSizeBytes > 0L) {
                        addQueryParameter("romtaille", fileSizeBytes.toString())
                    }
                }.build()
            }
            val hashJeu = parseJsonObjectSafe(infoBody)?.optJSONObject("response")?.optJSONObject("jeu")
            if (hashJeu != null) return@withContext hashJeu
        }

        val searchCandidates = listOf(cleanFallback, cleanSearchTitle(romName))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase(Locale.ROOT) }

        var bestMatch: JSONObject? = null
        for (queryTitle in searchCandidates) {
            val (_, searchBody) = executeWithCredsRefresh(hasPersonalUser, customDevId, customDevPassword) { creds ->
                buildBaseUrlBuilder(SS_SEARCH_URL, creds, systemEid, cleanUser, cleanPass)
                    .addQueryParameter("recherche", queryTitle)
                    .build()
            }
            val jeuxRaw = parseJsonObjectSafe(searchBody)?.optJSONObject("response")?.opt("jeux")
            bestMatch = pickBestScreenScraperMatch(jeuxRaw, queryTitle)
            if (bestMatch != null) break
        }
        if (bestMatch == null) return@withContext null

        val gameId = bestMatch.optString("id").trim()
        if (gameId.isEmpty()) return@withContext null

        val (_, enrichedBody) = executeWithCredsRefresh(hasPersonalUser, customDevId, customDevPassword) { creds ->
            buildBaseUrlBuilder(SS_INFO_URL, creds, systemEid, cleanUser, cleanPass)
                .addQueryParameter("gameid", gameId)
                .build()
        }

        parseJsonObjectSafe(enrichedBody)?.optJSONObject("response")?.optJSONObject("jeu") ?: bestMatch
    }

    @Suppress("UNUSED_PARAMETER")
    fun parseJeuJsonObject(
        jeu: JSONObject,
        systemEid: Int,
        boxArtFormat: String = "2D",
        mediaSources: Set<String> = emptySet(),
    ): ScrapedGameData {
        val preferredRegions = listOf("us", "wor", "ss", "eu", "jp")

    
        val nomsArray = jeu.optJSONArray("noms")
        var title: String? = null
        if (nomsArray != null) {
            for (region in preferredRegions) {
                for (i in 0 until nomsArray.length()) {
                    val obj = nomsArray.optJSONObject(i) ?: continue
                    val r = obj.optString("region").lowercase(Locale.ROOT)
                    val t = obj.optString("text")
                    if (r == region && t.isNotBlank()) {
                        title = t
                        break
                    }
                }
                if (title != null) break
            }
            if (title == null && nomsArray.length() > 0) {
                title = nomsArray.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
            }
        }
        if (title.isNullOrBlank()) {
            title = jeu.optString("nom").takeIf { it.isNotBlank() }
        }

  
        val synArray = jeu.optJSONArray("synopsis")
        var synopsis: String? = null
        if (synArray != null) {
            for (i in 0 until synArray.length()) {
                val obj = synArray.optJSONObject(i) ?: continue
                val l = obj.optString("langue").lowercase(Locale.ROOT)
                val t = obj.optString("text")
                if (l == "en" && t.isNotBlank()) {
                    synopsis = t
                    break
                }
            }
            if (synopsis == null && synArray.length() > 0) {
                synopsis = synArray.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
            }
        }
        if (synopsis.isNullOrBlank()) {
            synopsis = jeu.optString("synopsis").takeIf { it.isNotBlank() }
        }

        val developer = jeu.optJSONObject("developpeur")?.optString("text")?.takeIf { it.isNotBlank() }
            ?: jeu.optString("developpeur").takeIf { it.isNotBlank() }
        val publisher = jeu.optJSONObject("editeur")?.optString("text")?.takeIf { it.isNotBlank() }
            ?: jeu.optString("editeur").takeIf { it.isNotBlank() }

        val datesArray = jeu.optJSONArray("dates")
        var releaseDate: String? = null
        if (datesArray != null) {
            for (region in preferredRegions) {
                for (i in 0 until datesArray.length()) {
                    val obj = datesArray.optJSONObject(i) ?: continue
                    val r = obj.optString("region").lowercase(Locale.ROOT)
                    val t = obj.optString("text")
                    if (r == region && t.isNotBlank()) {
                        releaseDate = t
                        break
                    }
                }
                if (releaseDate != null) break
            }
            if (releaseDate == null && datesArray.length() > 0) {
                releaseDate = datesArray.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
            }
        }
        if (releaseDate.isNullOrBlank()) {
            releaseDate = jeu.optString("date").takeIf { it.isNotBlank() }
        }

        val players = jeu.optJSONObject("joueurs")?.optString("text")?.takeIf { it.isNotBlank() }
            ?: jeu.optString("joueurs").takeIf { it.isNotBlank() }

        val noteObj = jeu.optJSONObject("note")
        val noteStr = noteObj?.optString("text")?.takeIf { it.isNotBlank() }
            ?: noteObj?.optString("id")?.takeIf { it.isNotBlank() }
            ?: jeu.optString("note").takeIf { it.isNotBlank() && it != "null" }
            ?: jeu.optString("notefouineur").takeIf { it.isNotBlank() && it != "null" }
        val rawNote = noteStr?.toFloatOrNull() ?: if (jeu.has("note")) {
            jeu.optDouble("note", -1.0).takeIf { it >= 0 }?.toFloat()
        } else null
        val rating = rawNote?.let {
            if (it > 5.0f) it / 4.0f else it
        } ?: -1.0f

        val genres = extractLocalizedNames(jeu.optJSONArray("genres"), listOf("en"))
        val families = extractLocalizedNames(jeu.optJSONArray("familles"), listOf("en", "fr"))

        val mediasArray = jeu.optJSONArray("medias")
        val medias = mutableListOf<ScreenScraperMedia>()
        if (mediasArray != null) {
            for (i in 0 until mediasArray.length()) {
                val m = mediasArray.optJSONObject(i) ?: continue
                val url = sanitizeMediaUrl(m.optString("url"))
                if (url.isNotBlank()) {
                    medias.add(
                        ScreenScraperMedia(
                            type = m.optString("type").takeIf { it.isNotBlank() },
                            parent = m.optString("parent").takeIf { it.isNotBlank() },
                            url = url,
                            region = m.optString("region").takeIf { it.isNotBlank() },
                            format = m.optString("format").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }

        val bannerUrl = if (mediaSources.contains("banner")) {
            medias.firstOrNull { it.type.equals("fanart", ignoreCase = true) }?.url
                ?: medias.firstOrNull { it.type.equals("steamgrid", ignoreCase = true) }?.url
                ?: medias.firstOrNull { it.type.equals("background", ignoreCase = true) }?.url
        } else null

        val logoUrl = preferredRegions.firstNotNullOfOrNull { region ->
            medias.firstOrNull {
                it.type.equals("wheel", ignoreCase = true) &&
                    it.region.equals(region, ignoreCase = true)
            }?.url
        } ?: medias.firstOrNull { it.type.equals("wheel", ignoreCase = true) }?.url
            ?: medias.firstOrNull { it.type?.contains("wheel", ignoreCase = true) == true }?.url

        val primaryBoxType = if (boxArtFormat.equals("3D", ignoreCase = true)) "box-3D" else "box-2D"
        val secondaryBoxType = if (primaryBoxType == "box-3D") "box-2D" else "box-3D"

        val boxArtFrontUrl = preferredRegions.firstNotNullOfOrNull { region ->
            medias.firstOrNull {
                it.type.equals(primaryBoxType, ignoreCase = true) &&
                    it.region.equals(region, ignoreCase = true)
            }?.url
        } ?: medias.firstOrNull { it.type.equals(primaryBoxType, ignoreCase = true) }?.url
            ?: preferredRegions.firstNotNullOfOrNull { region ->
                medias.firstOrNull {
                    it.type.equals(secondaryBoxType, ignoreCase = true) &&
                        it.region.equals(region, ignoreCase = true)
                }?.url
            } ?: medias.firstOrNull { it.type.equals(secondaryBoxType, ignoreCase = true) }?.url
            ?: medias.firstOrNull { it.type?.contains("box", ignoreCase = true) == true }?.url

        val boxArtBackUrl = preferredRegions.firstNotNullOfOrNull { region ->
            medias.firstOrNull {
                it.type?.contains("back", ignoreCase = true) == true &&
                    it.region.equals(region, ignoreCase = true)
            }?.url
        } ?: medias.firstOrNull { it.type?.contains("back", ignoreCase = true) == true }?.url

        val videoUrl = if (mediaSources.contains("video")) {
            medias.firstOrNull { it.type.equals("video-normalized", ignoreCase = true) }?.url
                ?: medias.firstOrNull { it.type.equals("video", ignoreCase = true) }?.url
        } else null

        val fromMedias = medias
            .filter {
                val t = it.type?.lowercase(Locale.ROOT) ?: return@filter false
                mediaSources.any { source ->
                    when (source) {
                        "ss" -> t == "ss" || t.startsWith("ss(")
                        "sstitle" -> t == "sstitle" || t.startsWith("sstitle(")
                        "fanart" -> t == "fanart" || t.startsWith("fanart(")
                        else -> false
                    }
                }
            }
            .mapNotNull { it.url }

        val screenshotUrls = fromMedias.distinct().take(25)

        val manualEntries = extractManualEntries(medias)

        return ScrapedGameData(
            title = title,
            synopsis = synopsis,
            genres = genres,
            families = families,
            publisher = publisher,
            developer = developer,
            releaseDate = releaseDate,
            players = players,
            rating = rating,
            bannerUrl = bannerUrl,
            logoUrl = logoUrl,
            boxArtFrontUrl = boxArtFrontUrl,
            boxArtBackUrl = boxArtBackUrl,
            screenshotUrls = screenshotUrls,
            videoUrl = videoUrl,
            manualEntries = manualEntries,
            scraperSource = "ScreenScraper",
        )
    }

    private fun extractLocalizedNames(
        array: JSONArray?,
        preferredLangs: List<String>,
    ): String? {
        if (array == null || array.length() == 0) return null
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val noms = obj.optJSONArray("noms")
            var resolved: String? = null
            if (noms != null) {
                for (lang in preferredLangs) {
                    for (j in 0 until noms.length()) {
                        val nObj = noms.optJSONObject(j) ?: continue
                        if (nObj.optString("langue").equals(lang, ignoreCase = true)) {
                            resolved = nObj.optString("text").takeIf { it.isNotBlank() }
                            if (resolved != null) break
                        }
                    }
                    if (resolved != null) break
                }
                if (resolved == null) {
                    for (j in 0 until noms.length()) {
                        resolved = noms.optJSONObject(j)?.optString("text")?.takeIf { it.isNotBlank() }
                        if (resolved != null) break
                    }
                }
            }
            if (resolved == null) {
                resolved = obj.optString("nom").takeIf { it.isNotBlank() }
                    ?: obj.optString("nomcourt").takeIf { it.isNotBlank() }
            }
            val cleaned = resolved?.trim()?.takeIf { it.isNotBlank() } ?: continue
            if (seen.add(cleaned.lowercase(Locale.ROOT))) {
                results.add(cleaned)
            }
        }
        return results.joinToString(", ").takeIf { it.isNotBlank() }
    }

    private fun extractManualEntries(medias: List<ScreenScraperMedia>): List<ScreenScraperManualEntry> {
        val groupedByUrl = linkedMapOf<String, MutableList<ScreenScraperMedia>>()
        medias.filter { it.type.equals("manuel", ignoreCase = true) && !it.url.isNullOrBlank() }
            .forEach { media ->
                val url = media.url!!.trim()
                groupedByUrl.getOrPut(url) { mutableListOf() }.add(media)
            }

        val regionOrder = listOf("us", "eu", "uk", "gb", "wor", "au", "jp", "fr", "de", "sp", "es", "it", "pt", "br", "nl")
        val seenRegions = mutableSetOf<String>()
        val deduped = mutableListOf<ScreenScraperManualEntry>()

        for ((url, group) in groupedByUrl) {
            val matchingUrlRegion = group.firstOrNull { media ->
                val r = media.region?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
                r != null && url.contains("($r)", ignoreCase = true)
            }
            val chosen = matchingUrlRegion ?: group.first()
            val region = chosen.region?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "wor"
            if (seenRegions.add(region)) {
                deduped.add(ScreenScraperManualEntry(region = region, url = url))
            }
        }

        return deduped.sortedWith(
            compareBy(
                { entry -> regionOrder.indexOf(entry.region).let { if (it >= 0) it else regionOrder.size } },
                { entry -> entry.region },
            ),
        )
    }

    fun serializeManuals(manuals: List<ScreenScraperManualEntry>): String {
        val array = JSONArray()
        manuals.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("region", entry.region)
                    put("url", entry.url)
                },
            )
        }
        return array.toString()
    }

    fun deserializeManuals(jsonString: String?): List<ScreenScraperManualEntry> {
        if (jsonString.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(jsonString)
            val list = mutableListOf<ScreenScraperManualEntry>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                val region = obj.optString("region").trim().lowercase(Locale.ROOT).ifBlank { "wor" }
                if (url.isNotBlank()) {
                    list.add(ScreenScraperManualEntry(region = region, url = url))
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    fun selectScreenshotUrls(
        igdbScreenshots: List<String>?,
        screenScraperScreenshots: List<String>?,
        includeGameplay: Boolean,
        includeTitles: Boolean,
        maxCount: Int = 10,
    ): List<String> {
        if (!includeGameplay && !includeTitles) return emptyList()
        val candidates = mutableListOf<String>()
        if (includeGameplay) {
            val igdbShots = igdbScreenshots.orEmpty().filter { it.isNotBlank() }
            if (igdbShots.isNotEmpty()) {
                candidates.addAll(igdbShots)
            } else {
                val ssOnly = screenScraperScreenshots.orEmpty().filter { !it.contains("title", ignoreCase = true) }
                candidates.addAll(ssOnly)
            }
        }
        if (includeTitles) {
            val titlesOnly = screenScraperScreenshots.orEmpty().filter { it.contains("title", ignoreCase = true) }
            candidates.addAll(titlesOnly)
        }
        return candidates.distinct().take(maxCount)
    }

    fun serializeScreenshots(screenshotUrls: List<String>): String {
        val array = JSONArray()
        screenshotUrls.forEach { array.put(it) }
        return array.toString()
    }

    fun deserializeScreenshots(jsonString: String?): List<String> {
        if (jsonString.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(jsonString)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val str = array.optString(i)
                if (str.isNotBlank()) {
                    list.add(str)
                }
            }
            list
        }.getOrDefault(emptyList())
    }
}
