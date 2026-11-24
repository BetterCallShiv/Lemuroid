package com.swordfish.lemuroid.lib.backup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Cheat
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.db.entity.GameDetails
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.scraper.MediaStorageManager
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CountingInputStream(wrapped: InputStream) : FilterInputStream(wrapped) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b != -1) bytesRead++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        if (count > 0) bytesRead += count
        return count
    }
}

class BackupManager(
    private val appContext: Context,
    private val directoriesManager: DirectoriesManager,
    private val retrogradeDatabase: RetrogradeDatabase,
    private val mediaStorageManager: MediaStorageManager = MediaStorageManager(appContext),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun estimateBackup(options: BackupOptions): BackupEstimate = withContext(Dispatchers.IO) {
        var uncompressedBytes = 0L
        var estimatedCompressedBytes = 0L

        var saveCount = 0
        var stateCount = 0
        var mediaCount = 0

        if (options.includeSaves) {
            val savesDir = directoriesManager.getSavesDirectory()
            if (savesDir.exists()) {
                savesDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    saveCount++
                    uncompressedBytes += file.length()
                    estimatedCompressedBytes += (file.length() * 0.4).toLong()
                }
            }
        }

        if (options.includeStates) {
            val statesDir = directoriesManager.getStatesDirectory()
            if (statesDir.exists()) {
                statesDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    stateCount++
                    uncompressedBytes += file.length()
                    estimatedCompressedBytes += (file.length() * 0.95).toLong()
                }
            }
            val previewsDir = directoriesManager.getStatesPreviewDirectory()
            if (previewsDir.exists()) {
                previewsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    uncompressedBytes += file.length()
                    estimatedCompressedBytes += (file.length() * 0.98).toLong()
                }
            }
        }

        if (options.includeMedia) {
            val mediaStats = mediaStorageManager.calculateStorageStats()
            mediaCount = mediaStats.fileCount
            uncompressedBytes += mediaStats.totalBytes
            estimatedCompressedBytes += (mediaStats.totalBytes * 0.98).toLong()
        }

        val allGames = if (options.includeUserData || options.includeCheats) {
            runCatching { retrogradeDatabase.gameDao().selectAllGames() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val userDataCount = if (options.includeUserData) allGames.size else 0
        val gameDetailsCount = if (options.includeUserData) {
            runCatching { retrogradeDatabase.gameDetailsDao().getAllDetails().size }.getOrDefault(0)
        } else {
            0
        }
        val cheatCount = if (options.includeCheats) {
            runCatching { retrogradeDatabase.cheatDao().selectAllCheats().size }.getOrDefault(0)
        } else {
            0
        }

        val jsonBytes = (userDataCount * 300L) + (gameDetailsCount * 600L) + (cheatCount * 150L) + 10_000L
        uncompressedBytes += jsonBytes
        estimatedCompressedBytes += (jsonBytes * 0.3).toLong()

        val statPath = appContext.getExternalFilesDir(null)?.path ?: appContext.filesDir.path
        val statFs = StatFs(statPath)
        val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        val requiredBytes = (estimatedCompressedBytes * 1.15).toLong().coerceAtLeast(1024L * 1024L)
        val hasEnoughSpace = availableBytes >= requiredBytes

        BackupEstimate(
            uncompressedBytes = uncompressedBytes,
            estimatedCompressedBytes = estimatedCompressedBytes,
            saveCount = saveCount,
            stateCount = stateCount,
            cheatCount = cheatCount,
            userDataCount = userDataCount,
            gameDetailsCount = gameDetailsCount,
            mediaFileCount = mediaCount,
            availableBytes = availableBytes,
            hasEnoughSpace = hasEnoughSpace,
        )
    }

    suspend fun createBackup(
        outputUri: Uri,
        options: BackupOptions,
        appVersion: String = "alpha-1.0.0",
        onProgress: (step: String, progress: Float, bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _, _, _ -> },
    ): Result<BackupManifest> = withContext(Dispatchers.IO) {
        runCatching {
            var saveCount = 0
            var stateCount = 0
            var cheatCount = 0
            var userDataCount = 0
            var gameDetailsCount = 0
            var mediaCount = 0
            var totalUncompressedBytes = 0L

            val estimate = estimateBackup(options)
            val totalBytesTarget = estimate.uncompressedBytes.coerceAtLeast(1024L * 1024L)
            var currentProcessedBytes = 0L

            val outputStream = appContext.contentResolver.openOutputStream(outputUri)
                ?: throw IllegalStateException(appContext.getString(com.swordfish.lemuroid.lib.R.string.backup_error_open_dest))

            ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                zipOut.setLevel(Deflater.DEFAULT_COMPRESSION)

                val stepSavesAndData = appContext.getString(com.swordfish.lemuroid.lib.R.string.backup_step_saves_data)
                val stepMedia = appContext.getString(com.swordfish.lemuroid.lib.R.string.backup_step_media)
                val stepFinalizing = appContext.getString(com.swordfish.lemuroid.lib.R.string.backup_step_finalizing)

                fun reportProgress(step: String, bytesAdded: Long = 0L) {
                    currentProcessedBytes += bytesAdded
                    totalUncompressedBytes += bytesAdded
                    val p = (currentProcessedBytes.toFloat() / totalBytesTarget).coerceIn(0f, 0.98f)
                    onProgress(step, p, currentProcessedBytes, totalBytesTarget)
                }

                if (options.includeSaves) {
                    reportProgress(stepSavesAndData)
                    val savesDir = directoriesManager.getSavesDirectory()
                    if (savesDir.exists()) {
                        savesDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val relPath = "saves/${file.name}"
                            val bytesWritten = addFileToZip(file, relPath, zipOut)
                            saveCount++
                            reportProgress(stepSavesAndData, bytesWritten)
                        }
                    }
                }

                if (options.includeStates) {
                    reportProgress(stepSavesAndData)
                    val statesDir = directoriesManager.getStatesDirectory()
                    if (statesDir.exists()) {
                        statesDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val rel = file.relativeTo(statesDir).path.replace('\\', '/')
                            val bytesWritten = addFileToZip(file, "states/$rel", zipOut)
                            stateCount++
                            reportProgress(stepSavesAndData, bytesWritten)
                        }
                    }

                    val previewsDir = directoriesManager.getStatesPreviewDirectory()
                    if (previewsDir.exists()) {
                        previewsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val rel = file.relativeTo(previewsDir).path.replace('\\', '/')
                            val bytesWritten = addFileToZip(file, "state-previews/$rel", zipOut)
                            reportProgress(stepSavesAndData, bytesWritten)
                        }
                    }
                }

                if (options.includeMedia) {
                    reportProgress(stepMedia)
                    val mediaDir = mediaStorageManager.rootMediaDir
                    if (mediaDir.exists()) {
                        mediaDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val rel = file.relativeTo(mediaDir).path.replace('\\', '/')
                            val bytesWritten = addFileToZip(file, "media/$rel", zipOut)
                            mediaCount++
                            reportProgress(stepMedia, bytesWritten)
                        }
                    }
                }

                val allGames = if (options.includeUserData || options.includeCheats) {
                    retrogradeDatabase.gameDao().selectAllGames()
                } else {
                    emptyList()
                }
                val gameIdToGame = allGames.associateBy { it.id }

                if (options.includeUserData) {
                    reportProgress(stepSavesAndData)
                    val exportedUserData = allGames.map { game ->
                        ExportedGameUserData(
                            fileName = game.fileName,
                            baseName = game.fileName.substringBeforeLast('.'),
                            systemId = game.systemId,
                            title = game.title,
                            developer = game.developer,
                            coverFrontUrl = game.coverFrontUrl,
                            isFavorite = game.isFavorite,
                            totalPlayTimeMs = game.totalPlayTimeMs,
                            lastPlayedAt = game.lastPlayedAt,
                            customName = game.customName,
                        )
                    }
                    userDataCount = exportedUserData.size
                    val userDataJson =
                        json.encodeToString(
                            ListSerializer(ExportedGameUserData.serializer()),
                            exportedUserData,
                        )
                    val udBytes = addTextToZip(userDataJson, BackupManifest.USERDATA_FILE_NAME, zipOut)
                    reportProgress(stepSavesAndData, udBytes)

                    val allDetails = retrogradeDatabase.gameDetailsDao().getAllDetails()
                    val exportedDetails = allDetails.mapNotNull { details ->
                        val game = gameIdToGame[details.gameId] ?: return@mapNotNull null
                        ExportedGameDetails(
                            fileName = game.fileName,
                            systemId = game.systemId,
                            synopsis = details.synopsis,
                            genres = details.genres,
                            publisher = details.publisher,
                            releaseDate = details.releaseDate,
                            players = details.players,
                            rating = details.rating,
                            ratingCount = details.ratingCount,
                            bannerUrl = details.bannerUrl,
                            logoUrl = details.logoUrl,
                            boxArtFrontUrl = details.boxArtFrontUrl,
                            boxArtBackUrl = details.boxArtBackUrl,
                            screenshotUrlsJson = details.screenshotUrlsJson,
                            videoUrl = details.videoUrl,
                            crc32 = details.crc32,
                            scraperSource = details.scraperSource,
                            lastScrapedAt = details.lastScrapedAt,
                        )
                    }
                    gameDetailsCount = exportedDetails.size
                    val detailsJson =
                        json.encodeToString(
                            ListSerializer(ExportedGameDetails.serializer()),
                            exportedDetails,
                        )
                    val gdBytes = addTextToZip(detailsJson, BackupManifest.GAMEDETAILS_FILE_NAME, zipOut)
                    reportProgress(stepSavesAndData, gdBytes)
                }

                if (options.includeCheats) {
                    reportProgress(stepSavesAndData)
                    val allCheats = retrogradeDatabase.cheatDao().selectAllCheats()
                    val exportedCheats = allCheats.mapNotNull { cheat ->
                        val game = gameIdToGame[cheat.gameId] ?: return@mapNotNull null
                        ExportedCheat(
                            fileName = game.fileName,
                            systemId = cheat.systemId,
                            title = cheat.title,
                            description = cheat.description,
                            code = cheat.code,
                            cheatType = cheat.cheatType,
                            enabled = cheat.enabled,
                            isMasterCode = cheat.isMasterCode,
                            orderIndex = cheat.orderIndex,
                        )
                    }
                    cheatCount = exportedCheats.size
                    val cheatsJson =
                        json.encodeToString(
                            ListSerializer(ExportedCheat.serializer()),
                            exportedCheats,
                        )
                    val chBytes = addTextToZip(cheatsJson, BackupManifest.CHEATS_FILE_NAME, zipOut)
                    reportProgress(stepSavesAndData, chBytes)
                }

                if (options.includeSettings) {
                    reportProgress(stepSavesAndData)
                    val settingsJson = exportSettingsToJson(options.includeCredentials)
                    val stBytes = addTextToZip(settingsJson, BackupManifest.SETTINGS_FILE_NAME, zipOut)
                    reportProgress(stepSavesAndData, stBytes)
                }

                val manifest = BackupManifest(
                    appVersion = appVersion,
                    createdAt = System.currentTimeMillis(),
                    includesSaves = options.includeSaves,
                    includesStates = options.includeStates,
                    includesUserData = options.includeUserData,
                    includesCheats = options.includeCheats,
                    includesSettings = options.includeSettings,
                    includesCredentials = options.includeCredentials,
                    includesMedia = options.includeMedia,
                    stats = BackupStats(
                        saveCount = saveCount,
                        stateCount = stateCount,
                        cheatCount = cheatCount,
                        userDataCount = userDataCount,
                        gameDetailsCount = gameDetailsCount,
                        mediaCount = mediaCount,
                        totalUncompressedBytes = totalUncompressedBytes,
                    ),
                )
                val manifestJson = json.encodeToString(BackupManifest.serializer(), manifest)
                addTextToZip(manifestJson, BackupManifest.MANIFEST_FILE_NAME, zipOut)

                onProgress(stepFinalizing, 1.0f, totalBytesTarget, totalBytesTarget)
                manifest
            }
        }
    }

    suspend fun inspectBackup(inputUri: Uri): InspectedBackupInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val fileSizeBytes = runCatching {
                appContext.contentResolver.openFileDescriptor(inputUri, "r")?.use { it.statSize }
            }.getOrNull() ?: 0L

            val inputStream = appContext.contentResolver.openInputStream(inputUri) ?: return@runCatching null
            ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (entry.name == BackupManifest.MANIFEST_FILE_NAME) {
                        val text = zipIn.bufferedReader().readText()
                        val manifest = json.decodeFromString(BackupManifest.serializer(), text)
                        return@runCatching InspectedBackupInfo(manifest, fileSizeBytes)
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
                null
            }
        }.getOrNull()
    }

    suspend fun restoreBackup(
        inputUri: Uri,
        overwriteSaves: Boolean = true,
        onProgress: (step: String, progress: Float, bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _, _, _ -> },
    ): RestoreResult = withContext(Dispatchers.IO) {
        var savesRestored = 0
        var statesRestored = 0
        var userDataRestored = 0
        var gameDetailsRestored = 0
        var cheatsRestored = 0
        var mediaRestored = 0
        var settingsRestored = false

        try {
            val fileSizeBytes = runCatching {
                appContext.contentResolver.openFileDescriptor(inputUri, "r")?.use { it.statSize }
            }.getOrNull() ?: 0L

            val savesDir = directoriesManager.getSavesDirectory()
            val statesDir = directoriesManager.getStatesDirectory()
            val previewsDir = directoriesManager.getStatesPreviewDirectory()
            val mediaDir = mediaStorageManager.rootMediaDir

            var userDataJson: String? = null
            var gameDetailsJson: String? = null
            var cheatsJson: String? = null
            var settingsJson: String? = null

            val stepExtracting = appContext.getString(com.swordfish.lemuroid.lib.R.string.backup_step_extracting)
            val stepRestoringMedia = appContext.getString(com.swordfish.lemuroid.lib.R.string.backup_step_restoring_media)
            val stepRestoringData = appContext.getString(com.swordfish.lemuroid.lib.R.string.backup_step_restoring_data)

            onProgress(stepExtracting, 0.05f, 0L, fileSizeBytes)
            val inputStream = appContext.contentResolver.openInputStream(inputUri)
                ?: throw IllegalStateException(appContext.getString(com.swordfish.lemuroid.lib.R.string.backup_error_open_dest))

            val countingIn = CountingInputStream(BufferedInputStream(inputStream))

            ZipInputStream(countingIn).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')

                    if (name.contains("..")) {
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }

                    val progress = if (fileSizeBytes > 0) {
                        (countingIn.bytesRead.toFloat() / fileSizeBytes).coerceIn(0.05f, 0.75f)
                    } else 0.3f
                    val currentStep = if (name.startsWith("media/")) stepRestoringMedia else stepExtracting
                    onProgress(currentStep, progress, countingIn.bytesRead, fileSizeBytes)

                    when {
                        name == BackupManifest.SETTINGS_FILE_NAME -> {
                            settingsJson = zipIn.bufferedReader().readText()
                        }
                        name == BackupManifest.USERDATA_FILE_NAME -> {
                            userDataJson = zipIn.bufferedReader().readText()
                        }
                        name == BackupManifest.GAMEDETAILS_FILE_NAME -> {
                            gameDetailsJson = zipIn.bufferedReader().readText()
                        }
                        name == BackupManifest.CHEATS_FILE_NAME -> {
                            cheatsJson = zipIn.bufferedReader().readText()
                        }
                        name.startsWith("saves/") -> {
                            val rel = name.removePrefix("saves/")
                            if (rel.isNotEmpty()) {
                                val dest = File(savesDir, rel)
                                if (overwriteSaves || !dest.exists()) {
                                    extractEntryToFile(zipIn, dest)
                                    savesRestored++
                                }
                            }
                        }
                        name.startsWith("states/") -> {
                            val rel = name.removePrefix("states/")
                            if (rel.isNotEmpty()) {
                                val dest = File(statesDir, rel)
                                if (overwriteSaves || !dest.exists()) {
                                    extractEntryToFile(zipIn, dest)
                                    statesRestored++
                                }
                            }
                        }
                        name.startsWith("state-previews/") -> {
                            val rel = name.removePrefix("state-previews/")
                            if (rel.isNotEmpty()) {
                                val dest = File(previewsDir, rel)
                                extractEntryToFile(zipIn, dest)
                            }
                        }
                        name.startsWith("media/") -> {
                            val rel = name.removePrefix("media/")
                            if (rel.isNotEmpty()) {
                                val dest = File(mediaDir, rel)
                                extractEntryToFile(zipIn, dest)
                                mediaRestored++
                            }
                        }
                    }

                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            onProgress(stepRestoringData, 0.85f, fileSizeBytes, fileSizeBytes)

            if (!userDataJson.isNullOrEmpty()) {
                val exportedData = runCatching {
                    json.decodeFromString(
                        ListSerializer(ExportedGameUserData.serializer()),
                        userDataJson!!,
                    )
                }.getOrDefault(emptyList())

                if (exportedData.isNotEmpty()) {
                    val allGames = retrogradeDatabase.gameDao().selectAllGames()
                    val gamesByFileName = allGames.associateBy { it.fileName }
                    val gamesBySystemAndBase = allGames.associateBy { "${it.systemId}::${it.fileName.substringBeforeLast('.')}" }
                    val gamesBySystemAndTitle = allGames.associateBy { "${it.systemId}::${it.title.lowercase().trim()}" }

                    val updatedGames = mutableListOf<Game>()
                    for (item in exportedData) {
                        val match = gamesByFileName[item.fileName]
                            ?: gamesBySystemAndBase["${item.systemId}::${item.baseName}"]
                            ?: gamesBySystemAndTitle["${item.systemId}::${item.title.lowercase().trim()}"]

                        if (match != null) {
                            val updated = match.copy(
                                title = item.title.takeIf { it.isNotBlank() } ?: match.title,
                                coverFrontUrl = item.coverFrontUrl ?: match.coverFrontUrl,
                                developer = item.developer ?: match.developer,
                                isFavorite = item.isFavorite,
                                totalPlayTimeMs = item.totalPlayTimeMs.coerceAtLeast(match.totalPlayTimeMs),
                                lastPlayedAt = item.lastPlayedAt ?: match.lastPlayedAt,
                                customName = item.customName ?: match.customName,
                            )
                            updatedGames.add(updated)
                            userDataRestored++
                        }
                    }
                    if (updatedGames.isNotEmpty()) {
                        retrogradeDatabase.gameDao().update(updatedGames)
                    }
                }
            }
            if (!gameDetailsJson.isNullOrEmpty()) {
                val exportedDetails = runCatching {
                    json.decodeFromString(
                        ListSerializer(ExportedGameDetails.serializer()),
                        gameDetailsJson!!,
                    )
                }.getOrDefault(emptyList())

                if (exportedDetails.isNotEmpty()) {
                    val allGames = retrogradeDatabase.gameDao().selectAllGames()
                    val gamesByFileName = allGames.associateBy { it.fileName }
                    val gamesBySystemAndBase = allGames.associateBy { "${it.systemId}::${it.fileName.substringBeforeLast('.')}" }

                    val detailsToInsert = mutableListOf<GameDetails>()
                    for (item in exportedDetails) {
                        val match = gamesByFileName[item.fileName]
                            ?: gamesBySystemAndBase["${item.systemId}::${item.fileName.substringBeforeLast('.')}"]
                        if (match != null) {
                            val details = GameDetails(
                                gameId = match.id,
                                synopsis = item.synopsis,
                                genres = item.genres,
                                publisher = item.publisher,
                                releaseDate = item.releaseDate,
                                players = item.players,
                                rating = item.rating,
                                ratingCount = item.ratingCount,
                                bannerUrl = item.bannerUrl,
                                logoUrl = item.logoUrl,
                                boxArtFrontUrl = item.boxArtFrontUrl,
                                boxArtBackUrl = item.boxArtBackUrl,
                                screenshotUrlsJson = item.screenshotUrlsJson,
                                videoUrl = item.videoUrl,
                                crc32 = item.crc32,
                                scraperSource = item.scraperSource,
                                lastScrapedAt = item.lastScrapedAt ?: System.currentTimeMillis(),
                            )
                            detailsToInsert.add(details)
                            gameDetailsRestored++
                        }
                    }
                    if (detailsToInsert.isNotEmpty()) {
                        retrogradeDatabase.gameDetailsDao().insertOrUpdateAll(detailsToInsert)
                    }
                }
            }
            if (!cheatsJson.isNullOrEmpty()) {
                val exportedCheats = runCatching {
                    json.decodeFromString(
                        ListSerializer(ExportedCheat.serializer()),
                        cheatsJson!!,
                    )
                }.getOrDefault(emptyList())

                if (exportedCheats.isNotEmpty()) {
                    val allGames = retrogradeDatabase.gameDao().selectAllGames()
                    val gamesByFileName = allGames.associateBy { it.fileName }
                    val gamesBySystemAndBase = allGames.associateBy { "${it.systemId}::${it.fileName.substringBeforeLast('.')}" }

                    for (item in exportedCheats) {
                        val match = gamesByFileName[item.fileName]
                            ?: gamesBySystemAndBase["${item.systemId}::${item.fileName.substringBeforeLast('.')}"]
                        if (match != null) {
                            val cheat = Cheat(
                                gameId = match.id,
                                title = item.title,
                                description = item.description,
                                systemId = item.systemId,
                                code = item.code,
                                cheatType = item.cheatType,
                                enabled = item.enabled,
                                isMasterCode = item.isMasterCode,
                                orderIndex = item.orderIndex,
                            )
                            retrogradeDatabase.cheatDao().insertCheat(cheat)
                            cheatsRestored++
                        }
                    }
                }
            }
            if (!settingsJson.isNullOrEmpty()) {
                settingsRestored = restoreSettingsFromJson(settingsJson!!)
            }

            onProgress(appContext.getString(com.swordfish.lemuroid.lib.R.string.backup_step_restore_complete), 1.0f, fileSizeBytes, fileSizeBytes)

            RestoreResult(
                savesRestored = savesRestored,
                statesRestored = statesRestored,
                userDataRestored = userDataRestored,
                gameDetailsRestored = gameDetailsRestored,
                cheatsRestored = cheatsRestored,
                mediaRestored = mediaRestored,
                settingsRestored = settingsRestored,
            )
        } catch (e: Exception) {
            Timber.e(e, "Error during restoreBackup")
            RestoreResult(
                savesRestored = savesRestored,
                statesRestored = statesRestored,
                userDataRestored = userDataRestored,
                gameDetailsRestored = gameDetailsRestored,
                cheatsRestored = cheatsRestored,
                mediaRestored = mediaRestored,
                settingsRestored = settingsRestored,
                error = e.localizedMessage ?: "Unknown restore error",
            )
        }
    }

    private fun addFileToZip(file: File, zipPath: String, zipOut: ZipOutputStream): Long {
        if (!file.exists() || !file.isFile) return 0L
        val entry = ZipEntry(zipPath.replace('\\', '/')).apply {
            time = file.lastModified()
        }
        zipOut.putNextEntry(entry)
        val bytes = file.inputStream().buffered().use { it.copyTo(zipOut) }
        zipOut.closeEntry()
        return bytes
    }

    private fun addTextToZip(text: String, zipPath: String, zipOut: ZipOutputStream): Long {
        val entry = ZipEntry(zipPath.replace('\\', '/'))
        zipOut.putNextEntry(entry)
        val bytes = text.toByteArray(Charsets.UTF_8)
        zipOut.write(bytes)
        zipOut.closeEntry()
        return bytes.size.toLong()
    }

    private fun extractEntryToFile(zipIn: ZipInputStream, destFile: File) {
        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).buffered().use { out ->
            zipIn.copyTo(out)
        }
    }

    private fun exportSettingsToJson(includeCredentials: Boolean): String {
        val harmonyPrefs = SharedPreferencesHelper.getSharedPreferences(appContext)
        val legacyPrefs = SharedPreferencesHelper.getLegacySharedPreferences(appContext)

        val merged = mutableMapOf<String, Any>()
        legacyPrefs.all.forEach { (k, v) -> if (v != null) merged[k] = v }
        harmonyPrefs.all.forEach { (k, v) -> if (v != null) merged[k] = v }

        val jsonObject = buildJsonObject {
            merged.forEach { (key, value) ->
                if (!includeCredentials && isCredentialKey(key)) {
                    return@forEach
                }
                when (value) {
                    is Boolean -> put(key, value)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Float -> put(key, value.toDouble())
                    is String -> put(key, value)
                    is Set<*> -> {
                        put(key, JsonArray(value.map { JsonPrimitive(it.toString()) }))
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), jsonObject)
    }

    private fun restoreSettingsFromJson(jsonStr: String): Boolean {
        return runCatching {
            val jsonObject = json.decodeFromString(JsonObject.serializer(), jsonStr)
            val harmonyPrefs = SharedPreferencesHelper.getSharedPreferences(appContext)
            val editor = harmonyPrefs.edit()

            jsonObject.forEach { (key, element) ->
                if (element is JsonPrimitive) {
                    element.booleanOrNull?.let { editor.putBoolean(key, it) }
                        ?: element.intOrNull?.let { editor.putInt(key, it) }
                        ?: element.longOrNull?.let { editor.putLong(key, it) }
                        ?: editor.putString(key, element.content)
                } else if (element is JsonArray) {
                    val set = element.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
                    editor.putStringSet(key, set)
                }
            }
            editor.apply()
            true
        }.getOrDefault(false)
    }

    private fun isCredentialKey(key: String): Boolean {
        return key.contains("password", ignoreCase = true) ||
            key == "pref_retroachievements_token" ||
            key == "screenscraper_password" ||
            key == "screenscraper_devpassword"
    }
}
