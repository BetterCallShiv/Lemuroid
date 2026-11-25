package com.swordfish.lemuroid.app.shared.retroachievements

import android.content.Context
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.PushbackInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class AchievementDetail(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val badgeUrl: String,
    val isUnlocked: Boolean,
    val unlockDate: String? = null,
    val isHardcore: Boolean = false,
    val type: String? = null,
    val author: String? = null,
    val rarity: Double? = null,
    val rarityHardcore: Double? = null,
)

data class GameAchievementsData(
    val gameId: Int,
    val title: String,
    val unlockedCount: Int,
    val totalCount: Int,
    val unlockedPoints: Int,
    val totalPoints: Int,
    val badgeIconUrl: String,
    val achievements: List<AchievementDetail>,
) {
    val completionPercentage: Float
        get() = if (totalCount > 0) (unlockedCount.toFloat() / totalCount.toFloat()) * 100f else 0f
}

sealed interface AchievementsUiState {
    object Disabled : AchievementsUiState
    object Loading : AchievementsUiState
    object NoAchievements : AchievementsUiState
    data class Success(val data: GameAchievementsData) : AchievementsUiState
    data class Error(val message: String) : AchievementsUiState
}

class RetroAchievementsRepository(private val context: Context) {

    companion object {
        private const val MAX_BUFFER_HASH_BYTES = 16L * 1024L * 1024L
    }

    private val prefs = RetroAchievementsPreferences(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "RetroArch/1.19.0 (Android) rcheevos/11.7.0")
                .build()
            chain.proceed(request)
        }
        .build()

    fun getAchievementsFlow(game: Game, crc32: String? = null): Flow<AchievementsUiState> = flow {
        if (!prefs.enabled) {
            emit(AchievementsUiState.Disabled)
            return@flow
        }

        emit(AchievementsUiState.Loading)

        val keys = listOfNotNull(
            game.fileName.takeIf { it.isNotBlank() },
            game.title.takeIf { it.isNotBlank() },
            game.fileUri.takeIf { it.isNotBlank() },
            crc32?.takeIf { it.isNotBlank() },
        )

        val username = prefs.username
        val token = prefs.token
        val hasCredentials = username.isNotBlank() && token.isNotBlank()

        if (!hasCredentials) {
            Timber.w("RetroAchievements: User is not logged in")
            emit(AchievementsUiState.NoAchievements)
            return@flow
        }

        val gameId = prefs.getCachedGameId(*keys.toTypedArray())

        if (gameId != null && gameId > 0) {
            val cachedData = loadFromDiskCache(gameId)
            if (cachedData != null && cachedData.totalCount > 0) {
                emit(AchievementsUiState.Success(cachedData))
            }
        }

        val cachedHash = prefs.getCachedGameHash(game.fileName)
            ?: prefs.getCachedGameHash(game.fileUri)
        if (!cachedHash.isNullOrEmpty()) {
            val resolved = tryResolveAchievementsWithHash(cachedHash, game, keys, username, token)
            if (resolved != null) {
                emit(AchievementsUiState.Success(resolved))
                return@flow
            }
        }

        val uri = android.net.Uri.parse(game.fileUri)
        val distinctHashes = computeRomMd5Hashes(game, uri)
            .filterNot { it == cachedHash }
            .distinct()
        Timber.d("RetroAchievements: Candidate hashes for '${game.title}': $distinctHashes")

        for (hash in distinctHashes) {
            val resolved = tryResolveAchievementsWithHash(hash, game, keys, username, token)
            if (resolved != null) {
                emit(AchievementsUiState.Success(resolved))
                return@flow
            }
        }

        Timber.w("RetroAchievements: No achievements resolved for ${game.title}")
        val fallback = if (gameId != null && gameId > 0) loadFromDiskCache(gameId) else null
        if (fallback != null && fallback.totalCount > 0) {
            emit(AchievementsUiState.Success(fallback))
        } else {
            emit(AchievementsUiState.NoAchievements)
        }
    }.flowOn(Dispatchers.IO)

    private fun tryResolveAchievementsWithHash(
        hash: String,
        game: Game,
        keys: List<String>,
        username: String,
        token: String,
    ): GameAchievementsData? {
        try {
            val form = FormBody.Builder()
                .add("r", "achievementsets")
                .add("m", hash)
                .add("v", "2")
                .add("u", username)
                .add("t", token)
                .build()

            val req = Request.Builder()
                .url("https://retroachievements.org/dorequest.php")
                .post(form)
                .build()

            val body = client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }

            if (!body.isNullOrEmpty()) {
                val root = JSONObject(body)
                if (root.optBoolean("Success", false)) {
                    val resolvedId = root.optInt(
                        "GameId",
                        root.optInt("GameID", root.optJSONObject("Game")?.optInt("ID", 0) ?: 0)
                    )
                    if (resolvedId > 0) {
                        Timber.i("RetroAchievements: Resolved game ID $resolvedId using achievementsets and hash $hash")
                        prefs.setCachedGameId(keys, resolvedId)
                        prefs.setCachedGameHash(game.fileName, hash)
                        prefs.setCachedGameHash(game.fileUri, hash)

                        val unlockedMap = mutableMapOf<Int, Long>()
                        val hardcoreSet = mutableSetOf<Int>()
                        fetchUserUnlocks(resolvedId, username, token, unlockedMap, hardcoreSet)

                        val parsed = parseAchievementsJson(resolvedId, body, unlockedMap, hardcoreSet)
                        if (parsed != null && parsed.totalCount > 0) {
                            saveToDiskCache(resolvedId, body, unlockedMap, hardcoreSet)
                            return parsed
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "RetroAchievements: achievementsets request failed for hash $hash")
        }
        return null
    }

    private fun fetchUserUnlocks(
        gameId: Int,
        username: String,
        token: String,
        unlockedMap: MutableMap<Int, Long>,
        hardcoreSet: MutableSet<Int>,
    ) {
        try {
            val sessionForm = FormBody.Builder()
                .add("r", "startsession")
                .add("g", gameId.toString())
                .add("u", username)
                .add("t", token)
                .build()

            val sessionRequest = Request.Builder()
                .url("https://retroachievements.org/dorequest.php")
                .post(sessionForm)
                .build()

            client.newCall(sessionRequest).execute().use { resp ->
                if (resp.isSuccessful) {
                    val unlockJson = JSONObject(resp.body?.string() ?: "{}")
                    val unlocksArr = unlockJson.optJSONArray("Unlocks") ?: unlockJson.optJSONArray("UserUnlocks")
                    if (unlocksArr != null) {
                        for (i in 0 until unlocksArr.length()) {
                            val item = unlocksArr.optJSONObject(i)
                            if (item != null) {
                                val id = item.optInt("ID", 0)
                                val whenTs = item.optLong("When", 0L)
                                if (id > 0) unlockedMap[id] = whenTs
                            } else {
                                val id = unlocksArr.optInt(i, 0)
                                if (id > 0) unlockedMap[id] = 0L
                            }
                        }
                    }
                    val hardcoreArr = unlockJson.optJSONArray("HardcoreUnlocks")
                    if (hardcoreArr != null) {
                        for (i in 0 until hardcoreArr.length()) {
                            val item = hardcoreArr.optJSONObject(i)
                            val id = item?.optInt("ID", 0) ?: hardcoreArr.optInt(i, 0)
                            if (id > 0) hardcoreSet.add(id)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "RetroAchievements: Could not fetch user unlocks for game $gameId")
        }
    }

    private fun parseAchievementsJson(
        gameId: Int,
        jsonString: String,
        unlockedMap: Map<Int, Long> = emptyMap(),
        hardcoreSet: Set<Int> = emptySet(),
    ): GameAchievementsData? {
        return try {
            val root = JSONObject(jsonString)
            val patchData = root.optJSONObject("PatchData") ?: root
            val gameObj = root.optJSONObject("Game")
            val title = gameObj?.optString("Title")
                ?: patchData.optString("Title", root.optString("Title", "Game #$gameId"))
            val iconPath = gameObj?.optString("ImageIcon")
                ?: patchData.optString("ImageIcon", root.optString("ImageIcon", ""))
            val badgeIconUrl = if (iconPath.startsWith("http")) {
                iconPath
            } else if (iconPath.isNotBlank()) {
                "https://media.retroachievements.org$iconPath"
            } else {
                ""
            }

            val achievementsList = mutableListOf<AchievementDetail>()
            val setsArr = root.optJSONArray("AchievementSets")
                ?: root.optJSONArray("Sets")
                ?: patchData.optJSONArray("AchievementSets")
                ?: patchData.optJSONArray("Sets")

            if (setsArr != null && setsArr.length() > 0) {
                for (s in 0 until setsArr.length()) {
                    val setObj = setsArr.optJSONObject(s) ?: continue
                    val setAchievementsObj = setObj.optJSONObject("Achievements")
                    val setAchievementsArr = setObj.optJSONArray("Achievements")
                    if (setAchievementsObj != null) {
                        val keys = setAchievementsObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val item = setAchievementsObj.optJSONObject(key) ?: continue
                            parseAchievementItem(item, unlockedMap, hardcoreSet)?.let { achievementsList.add(it) }
                        }
                    } else if (setAchievementsArr != null) {
                        for (i in 0 until setAchievementsArr.length()) {
                            val item = setAchievementsArr.optJSONObject(i) ?: continue
                            parseAchievementItem(item, unlockedMap, hardcoreSet)?.let { achievementsList.add(it) }
                        }
                    }
                }
            }

            if (achievementsList.isEmpty()) {
                val achievementsObj = patchData.optJSONObject("Achievements") ?: root.optJSONObject("Achievements")
                val achievementsArr = patchData.optJSONArray("Achievements") ?: root.optJSONArray("Achievements")

                if (achievementsObj != null) {
                    val keys = achievementsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val item = achievementsObj.optJSONObject(key) ?: continue
                        parseAchievementItem(item, unlockedMap, hardcoreSet)?.let { achievementsList.add(it) }
                    }
                } else if (achievementsArr != null) {
                    for (i in 0 until achievementsArr.length()) {
                        val item = achievementsArr.optJSONObject(i) ?: continue
                        parseAchievementItem(item, unlockedMap, hardcoreSet)?.let { achievementsList.add(it) }
                    }
                }
            }

            if (achievementsList.isEmpty()) {
                return null
            }

            val sorted = achievementsList.distinctBy { it.id }.sortedWith(
                compareByDescending<AchievementDetail> { it.isUnlocked }
                    .thenBy { it.id }
            )

            val unlockedCount = sorted.count { it.isUnlocked }
            val unlockedPoints = sorted.filter { it.isUnlocked }.sumOf { it.points }
            val totalPoints = sorted.sumOf { it.points }

            GameAchievementsData(
                gameId = gameId,
                title = title,
                unlockedCount = unlockedCount,
                totalCount = sorted.size,
                unlockedPoints = unlockedPoints,
                totalPoints = totalPoints,
                badgeIconUrl = badgeIconUrl,
                achievements = sorted,
            )
        } catch (e: Exception) {
            Timber.e(e, "RetroAchievements: JSON parsing error")
            null
        }
    }

    private fun parseAchievementItem(
        item: JSONObject,
        unlockedMap: Map<Int, Long>,
        hardcoreSet: Set<Int>,
    ): AchievementDetail? {
        val flags = item.optInt("Flags", 3)
        if (flags != 3 && flags != 0) {
            return null
        }

        val id = item.optInt("ID", item.optInt("Id", item.optString("ID", "0").toIntOrNull() ?: 0))
        if (id == 0) return null
        val title = item.optString("Title", "Achievement #$id")
        val desc = item.optString("Description", "")
        val points = item.optInt("Points", item.optString("Points", "0").toIntOrNull() ?: 0)
        val badgeName = item.optString("BadgeName", item.optString("Badge", ""))
        val badgeUrl = if (badgeName.isNotBlank()) {
            if (badgeName.startsWith("http")) badgeName else "https://media.retroachievements.org/Badge/$badgeName.png"
        } else {
            ""
        }

        val unlockTimestamp = unlockedMap[id] ?: run {
            val dateEarned = item.optLong("DateEarned", 0L)
            if (dateEarned > 0) dateEarned else null
        }
        val isUnlocked = unlockTimestamp != null || item.optInt("Achieved", 0) == 1
        val isHardcore = hardcoreSet.contains(id) || item.optInt("HardcoreAchieved", 0) == 1
        val formattedDate = unlockTimestamp?.takeIf { it > 0 }?.let { ts ->
            try {
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ts * 1000L))
            } catch (e: Exception) {
                null
            }
        }

        val type = item.optString("Type", "").takeIf { it.isNotBlank() && it != "null" }
        val author = item.optString("Author", "").takeIf { it.isNotBlank() && it != "null" }
        val rarity = if (item.has("Rarity") && !item.isNull("Rarity")) {
            item.optDouble("Rarity").takeIf { !it.isNaN() }
        } else null
        val rarityHardcore = if (item.has("RarityHardcore") && !item.isNull("RarityHardcore")) {
            item.optDouble("RarityHardcore").takeIf { !it.isNaN() }
        } else null

        return AchievementDetail(
            id = id,
            title = title,
            description = desc,
            points = points,
            badgeUrl = badgeUrl,
            isUnlocked = isUnlocked,
            unlockDate = formattedDate,
            isHardcore = isHardcore,
            type = type,
            author = author,
            rarity = rarity,
            rarityHardcore = rarityHardcore,
        )
    }

    private fun computeRomMd5Hashes(game: Game, uri: android.net.Uri): List<String> {
        val consoleId = RetroAchievementsManager.getConsoleId(game.systemId)
        if (consoleId <= 0) return emptyList()

        val isArcade = game.systemId.equals("fbneo", ignoreCase = true) ||
            game.systemId.equals("mame2003plus", ignoreCase = true) ||
            game.systemId.equals("arcade", ignoreCase = true)

        val possibleCacheFolders = listOf("storage-framework-games", "local-storage-games")
        for (folder in possibleCacheFolders) {
            val cachedFile = com.swordfish.lemuroid.lib.storage.local.GameCacheUtils.getCacheFileForGame(folder, context, game)
            if (cachedFile.exists() && cachedFile.length() > 0 &&
                (isArcade || !cachedFile.name.endsWith(".zip", ignoreCase = true))
            ) {
                try {
                    val hash = com.swordfish.libretrodroid.LibretroDroid.generateRetroAchievementsHash(
                        consoleId,
                        cachedFile.absolutePath,
                        null
                    )
                    if (!hash.isNullOrEmpty()) {
                        Timber.d("RetroAchievements: Native hash generated from cached file: $hash for console $consoleId")
                        return listOf(hash)
                    }
                } catch (t: Throwable) {
                    Timber.w(t, "RetroAchievements: Failed to hash cached file")
                }
            }
        }

        val directPath = if (uri.scheme == "file") uri.path ?: "" else if (uri.toString().startsWith("/")) uri.toString() else ""
        if (directPath.isNotEmpty() && File(directPath).exists() &&
            (isArcade || !directPath.endsWith(".zip", ignoreCase = true))
        ) {
            try {
                val hash = com.swordfish.libretrodroid.LibretroDroid.generateRetroAchievementsHash(
                    consoleId,
                    directPath,
                    null
                )
                if (!hash.isNullOrEmpty()) {
                    Timber.d("RetroAchievements: Native hash generated from direct file: $hash for console $consoleId")
                    return listOf(hash)
                }
            } catch (t: Throwable) {
                Timber.w(t, "RetroAchievements: Failed to hash direct file")
            }
        }

        val result = mutableListOf<String>()
        try {
            context.contentResolver.openInputStream(uri)?.use { rawStream ->
                val pushback = PushbackInputStream(rawStream, 4)
                val header = ByteArray(4)
                val headerLen = pushback.read(header)
                if (headerLen > 0) {
                    pushback.unread(header, 0, headerLen)
                }
                val hasZipMagic = headerLen == 4 &&
                    header[0] == 0x50.toByte() &&
                    header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() &&
                    header[3] == 0x04.toByte()

                val isZip = hasZipMagic ||
                    game.fileUri.endsWith(".zip", ignoreCase = true) ||
                    game.fileName.endsWith(".zip", ignoreCase = true)

                if (isArcade) {
                    val baseZipName = if (game.fileName.endsWith(".zip", ignoreCase = true)) {
                        game.fileName
                    } else {
                        "${game.fileName}.zip"
                    }
                    val tempDir = File(context.cacheDir, "ra_arcade_${System.nanoTime()}").apply { mkdirs() }
                    val tempArcadeFile = File(tempDir, File(baseZipName).name)
                    try {
                        FileOutputStream(tempArcadeFile).buffered().use { out ->
                            pushback.copyTo(out)
                        }
                        if (tempArcadeFile.length() > 0) {
                            val fileHash = com.swordfish.libretrodroid.LibretroDroid.generateRetroAchievementsHash(
                                consoleId,
                                tempArcadeFile.absolutePath,
                                null
                            )
                            if (!fileHash.isNullOrEmpty()) {
                                Timber.d("RetroAchievements: Native hash generated for arcade zip: $fileHash for console $consoleId")
                                result.add(fileHash)
                            }
                        }
                    } catch (t: Throwable) {
                        Timber.w(t, "RetroAchievements: Failed to hash arcade zip")
                    } finally {
                        runCatching { tempDir.deleteRecursively() }
                    }
                } else if (isZip) {
                    var exactTempFile: File? = null
                    var fallbackTempFile: File? = null
                    try {
                        val romExtensions = setOf(
                            "nes", "sfc", "smc", "gb", "gbc", "gba", "md", "smd",
                            "gen", "bin", "pce", "ngp", "ngc", "ws", "wsc", "z64", "n64", "nds"
                        )
                        ZipInputStream(pushback).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                val entryName = entry.name
                                val isMacOsMeta = entry.isDirectory ||
                                    entryName.startsWith("__MACOSX/", ignoreCase = true) ||
                                    entryName.contains("/__MACOSX/", ignoreCase = true) ||
                                    entryName.substringAfterLast('/').startsWith("._")
                                if (!isMacOsMeta) {
                                    val isExactMatch = entryName.equals(game.fileName, ignoreCase = true) ||
                                        entryName.substringAfterLast('/').equals(game.fileName, ignoreCase = true)
                                    val ext = entryName.substringAfterLast('.', "").lowercase()
                                    val suffix = if (ext.isNotEmpty()) ".$ext" else ".${game.systemId}"

                                    if (isExactMatch) {
                                        val tmp = File.createTempFile("ra_rom_", suffix, context.cacheDir)
                                        FileOutputStream(tmp).buffered().use { out -> zip.copyTo(out) }
                                        exactTempFile = tmp
                                        fallbackTempFile?.delete()
                                        fallbackTempFile = null
                                        break
                                    } else if (fallbackTempFile == null && ext in romExtensions) {
                                        val tmp = File.createTempFile("ra_rom_", suffix, context.cacheDir)
                                        FileOutputStream(tmp).buffered().use { out -> zip.copyTo(out) }
                                        fallbackTempFile = tmp
                                    }
                                }
                                entry = zip.nextEntry
                            }
                        }

                        val targetFile = exactTempFile ?: fallbackTempFile
                        if (targetFile != null && targetFile.length() > 0) {
                            hashTempRomFile(consoleId, targetFile, result)
                        }
                    } finally {
                        runCatching { exactTempFile?.delete() }
                        runCatching { fallbackTempFile?.delete() }
                    }
                } else {
                    val extension = if (game.fileName.contains('.')) {
                        "." + game.fileName.substringAfterLast('.')
                    } else {
                        ".${game.systemId}"
                    }
                    val tempRomFile = File.createTempFile("ra_rom_", extension, context.cacheDir)
                    try {
                        FileOutputStream(tempRomFile).buffered().use { out ->
                            pushback.copyTo(out)
                        }
                        if (tempRomFile.length() > 0) {
                            hashTempRomFile(consoleId, tempRomFile, result)
                        }
                    } finally {
                        runCatching { tempRomFile.delete() }
                    }
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "RetroAchievements: Failed to read ROM stream for hashing")
        }

        return result.distinct()
    }

    private fun hashTempRomFile(
        consoleId: Int,
        tempRomFile: File,
        result: MutableList<String>,
    ) {
        try {
            val fileHash = com.swordfish.libretrodroid.LibretroDroid.generateRetroAchievementsHash(
                consoleId,
                tempRomFile.absolutePath,
                null
            )
            if (!fileHash.isNullOrEmpty()) {
                Timber.d("RetroAchievements: Native hash generated via temp file: $fileHash for console $consoleId")
                result.add(fileHash)
                return
            }
        } catch (t: Throwable) {
            Timber.w(t, "RetroAchievements: Failed to hash via temp file")
        }

        if (tempRomFile.length() <= MAX_BUFFER_HASH_BYTES) {
            try {
                val romBytes = tempRomFile.readBytes()
                val bufferHash = com.swordfish.libretrodroid.LibretroDroid.generateRetroAchievementsHash(
                    consoleId,
                    "",
                    romBytes
                )
                if (!bufferHash.isNullOrEmpty()) {
                    result.add(bufferHash)
                }
            } catch (t: Throwable) {
                Timber.w(t, "RetroAchievements: Failed to hash via buffer")
            }
        }
    }

    private fun getCacheFile(gameId: Int): File {
        val dir = File(context.cacheDir, "retroachievements")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "game_$gameId.json")
    }

    private fun saveToDiskCache(
        gameId: Int,
        patchJson: String,
        unlockedMap: Map<Int, Long>,
        hardcoreSet: Set<Int>,
    ) {
        try {
            val cacheObj = JSONObject()
            cacheObj.put("patch", patchJson)
            val unlocksObj = JSONObject()
            unlockedMap.forEach { (id, ts) -> unlocksObj.put(id.toString(), ts) }
            cacheObj.put("unlocks", unlocksObj)
            val hardcoreArr = JSONArray()
            hardcoreSet.forEach { hardcoreArr.put(it) }
            cacheObj.put("hardcore", hardcoreArr)
            getCacheFile(gameId).writeText(cacheObj.toString())
        } catch (e: Exception) {
            Timber.w(e, "RetroAchievements: Failed to write disk cache for $gameId")
        }
    }

    private fun loadFromDiskCache(gameId: Int): GameAchievementsData? {
        return try {
            val file = getCacheFile(gameId)
            if (file.exists() && file.length() > 0) {
                val content = file.readText()
                if (content.startsWith("{") && content.contains("\"patch\"")) {
                    val root = JSONObject(content)
                    val patchJson = root.getString("patch")
                    val unlocksObj = root.optJSONObject("unlocks")
                    val unlockedMap = mutableMapOf<Int, Long>()
                    unlocksObj?.keys()?.forEach { k ->
                        k.toIntOrNull()?.let { id -> unlockedMap[id] = unlocksObj.getLong(k) }
                    }
                    val hardcoreArr = root.optJSONArray("hardcore")
                    val hardcoreSet = mutableSetOf<Int>()
                    if (hardcoreArr != null) {
                        for (i in 0 until hardcoreArr.length()) {
                            hardcoreSet.add(hardcoreArr.getInt(i))
                        }
                    }
                    parseAchievementsJson(gameId, patchJson, unlockedMap, hardcoreSet)
                } else {
                    parseAchievementsJson(gameId, content)
                }
            } else null
        } catch (e: Exception) {
            Timber.w(e, "RetroAchievements: Failed to read disk cache for $gameId")
            null
        }
    }
}
