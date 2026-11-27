package com.swordfish.lemuroid.lib.scraper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class AlphaLemuroidClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "AlphaLemuroid")
                .build()
            chain.proceed(request)
        }
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 15
            },
        )
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        private val API_URLS = listOf(
            "https://api.alphalemuroid.workers.dev/scrape",
            "https://api.alphalemuroid.indevs.in/scrape",
        )
        private const val MAX_RETRY_ATTEMPTS = 2
        private val RETRYABLE_HTTP_CODES = setOf(429, 503)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private suspend fun executePostWithRetry(requestBody: RequestBody): String? {
        for (url in API_URLS) {
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("User-Agent", "AlphaLemuroid")
                .post(requestBody)
                .build()

            for (attempt in 0..MAX_RETRY_ATTEMPTS) {
                var retryDelayMs: Long? = null
                var moveToNextHost = false
                try {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            return response.body?.string()
                        }
                        if (response.code !in RETRYABLE_HTTP_CODES || attempt == MAX_RETRY_ATTEMPTS) {
                            if (response.code in 400..499 && response.code != 429) {
                                return null
                            }
                            moveToNextHost = true
                            return@use
                        }
                        val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
                        retryDelayMs = retryAfterSec?.times(1000L)?.coerceIn(300L, 5000L)
                            ?: (500L * (attempt + 1) + Random.nextLong(50L, 250L))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    if (attempt == MAX_RETRY_ATTEMPTS) {
                        moveToNextHost = true
                    } else {
                        retryDelayMs = 400L * (attempt + 1)
                    }
                }
                if (moveToNextHost) break
                if (retryDelayMs != null) {
                    delay(retryDelayMs)
                }
            }
        }
        return null
    }

    suspend fun scrapeIgdb(
        systemId: String,
        canonicalTitle: String?,
        fileName: String,
        crc32: String?,
    ): AlphaLemuroidMetadata? = withContext(Dispatchers.IO) {
        val cleanCrc = crc32?.trim()?.uppercase()?.takeIf { it.isNotBlank() && it != "0" }
        val cleanTitle = cleanGameTitle(canonicalTitle)
        val cleanFallback = cleanGameTitle(fileName).ifBlank { cleanTitle }
        val platformId = IgdbPlatformMapper.getPlatformId(systemId)

        if (cleanCrc == null && cleanTitle.isBlank() && cleanFallback.isBlank()) {
            return@withContext null
        }

        val jsonBody = JSONObject().apply {
            put("source", "igdb")
            if (!cleanCrc.isNullOrBlank()) {
                put("crc32", cleanCrc)
            }
            if (cleanTitle.isNotBlank()) {
                put("title", cleanTitle)
            }
            if (cleanFallback.isNotBlank()) {
                put("fallbackTitle", cleanFallback)
            }
            if (platformId != null && platformId > 0) {
                put("platformId", platformId)
            }
        }

        val jsonString = executePostWithRetry(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            ?: return@withContext null

        parseResponse(jsonString)
    }

    suspend fun scrapeMetadata(
        crc32: String? = null,
        title: String? = null,
        platformId: Int? = null,
    ): AlphaLemuroidMetadata? = withContext(Dispatchers.IO) {
        val cleanCrc = crc32?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val cleanTitle = cleanGameTitle(title)

        if (cleanCrc == null && cleanTitle.isBlank()) return@withContext null

        val jsonBody = JSONObject().apply {
            put("source", "igdb")
            if (!cleanCrc.isNullOrBlank()) {
                put("crc32", cleanCrc)
            }
            if (cleanTitle.isNotBlank()) {
                put("title", cleanTitle)
            }
            if (platformId != null && platformId > 0) {
                put("platformId", platformId)
            }
        }

        val jsonString = executePostWithRetry(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            ?: return@withContext null

        parseResponse(jsonString)
    }

    private fun parseResponse(jsonString: String): AlphaLemuroidMetadata? {
        return runCatching {
            val root = JSONObject(jsonString)
            if (root.has("error")) return null

            val name = root.optString("name").takeIf { it.isNotBlank() }
            val summary = root.optString("summary").takeIf { it.isNotBlank() }
            val storyline = root.optString("storyline").takeIf { it.isNotBlank() }
            val rawRating = root.optDouble("rating", -1.0).takeIf { it >= 0 }
            val rating = rawRating?.let { (it / 20.0).toFloat() }
            val ratingCount = root.optInt("total_rating_count", -1).takeIf { it > 0 }
                ?: root.optInt("rating_count", -1).takeIf { it > 0 }

            val genres = parseStringList(root, "genres")
            val themes = parseStringList(root, "themes")
            val gameModes = parseStringList(root, "game_modes")
            val developers = parseStringList(root, "developers")
            val publishers = parseStringList(root, "publishers")

            val mediaObj = root.optJSONObject("media")
            val coverObj = mediaObj?.optJSONObject("cover")
            val coverUrl = coverObj?.optString("original")?.takeIf { it.isNotBlank() }
                ?: coverObj?.optString("hd")?.takeIf { it.isNotBlank() }

            val screenshots = mutableListOf<String>()
            val ssArray = mediaObj?.optJSONArray("screenshots")
            if (ssArray != null) {
                for (i in 0 until ssArray.length()) {
                    val sObj = ssArray.optJSONObject(i) ?: continue
                    val u = sObj.optString("full_hd").takeIf { it.isNotBlank() }
                        ?: sObj.optString("hd").takeIf { it.isNotBlank() }
                    if (u != null) {
                        screenshots.add(u.replace(".jpg", ".webp"))
                    }
                }
            }

            val artworks = mutableListOf<String>()
            val artArray = mediaObj?.optJSONArray("artworks")
            if (artArray != null) {
                for (i in 0 until artArray.length()) {
                    val aObj = artArray.optJSONObject(i) ?: continue
                    val u = aObj.optString("full_hd").takeIf { it.isNotBlank() }
                        ?: aObj.optString("hd").takeIf { it.isNotBlank() }
                    if (u != null) {
                        artworks.add(u.replace(".jpg", ".webp"))
                    }
                }
            }

            val videoYoutubeUrls = mutableListOf<String>()
            val vidArray = mediaObj?.optJSONArray("videos")
            if (vidArray != null) {
                for (i in 0 until vidArray.length()) {
                    val vObj = vidArray.optJSONObject(i) ?: continue
                    val u = vObj.optString("youtube_url").takeIf { it.isNotBlank() }
                    if (u != null) {
                        videoYoutubeUrls.add(u)
                    }
                }
            }

            val releaseYear = root.optLong("first_release_date", -1L).takeIf { it > 0 }?.let { epochSec ->
                val calendar = java.util.Calendar.getInstance()
                calendar.timeInMillis = epochSec * 1000L
                calendar.get(java.util.Calendar.YEAR).toString()
            }

            AlphaLemuroidMetadata(
                id = root.optLong("id", -1L).takeIf { it > 0 },
                name = name,
                summary = summary,
                storyline = storyline,
                releaseDate = releaseYear,
                rating = rating,
                ratingCount = ratingCount,
                genres = genres,
                themes = themes,
                gameModes = gameModes,
                developers = developers,
                publishers = publishers,
                coverUrl = coverUrl?.replace(".jpg", ".webp"),
                screenshots = screenshots,
                artworks = artworks,
                videoYoutubeUrls = videoYoutubeUrls,
            )
        }.getOrNull()
    }

    private fun parseStringList(obj: JSONObject, key: String): List<String> {
        val array = obj.optJSONArray(key) ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optString(i)
            if (item.isNotBlank()) {
                list.add(item)
            }
        }
        return list.distinct()
    }

    fun cleanGameTitle(rawTitle: String?): String = ScreenScraperClient.cleanSearchTitle(rawTitle)
}
