package com.swordfish.lemuroid.lib.library

import android.content.Context
import com.swordfish.lemuroid.lib.library.covers.CustomCoverManager
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Cheat
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.db.entity.GameCollectionCrossRef
import com.swordfish.lemuroid.lib.library.db.entity.GameDetails
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.Locale
import java.util.UUID


class LibraryGameArchiveManager(
    private val appContext: Context,
    private val db: RetrogradeDatabase,
    private val customCoverManager: CustomCoverManager,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()

    private fun archiveFile(): File = File(appContext.filesDir, ARCHIVE_FILE_NAME)

    data class GameDataSnapshot(
        val game: Game,
        val details: GameDetails?,
        val cheats: List<ArchivedCheat>,
        val collectionIds: List<Int>,
    ) {
        fun isWorthPreserving(): Boolean {
            return game.isFavorite ||
                game.totalPlayTimeMs > 0L ||
                game.lastPlayedAt != null ||
                !game.customName.isNullOrBlank() ||
                game.customCoverUri != null ||
                details != null ||
                cheats.isNotEmpty() ||
                collectionIds.isNotEmpty()
        }
    }

    @Serializable
    data class ArchivedGameEntry(
        val archiveId: String,
        val systemId: String,
        val fileName: String,
        val title: String,
        val normalizedTitle: String,
        val normalizedFileBase: String,
        val developer: String? = null,
        val coverFrontUrl: String? = null,
        val lastPlayedAt: Long? = null,
        val totalPlayTimeMs: Long = 0L,
        val isFavorite: Boolean = false,
        val customName: String? = null,
        val hasCustomCover: Boolean = false,
        val details: ArchivedGameDetails? = null,
        val cheats: List<ArchivedCheat> = emptyList(),
        val collectionIds: List<Int> = emptyList(),
        val archivedAtMs: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class ArchivedGameDetails(
        val synopsis: String? = null,
        val genres: String? = null,
        val publisher: String? = null,
        val releaseDate: String? = null,
        val players: String? = null,
        val rating: Float? = null,
        val ratingCount: Int? = null,
        val bannerUrl: String? = null,
        val logoUrl: String? = null,
        val boxArtFrontUrl: String? = null,
        val boxArtBackUrl: String? = null,
        val screenshotUrlsJson: String? = null,
        val videoUrl: String? = null,
        val families: String? = null,
        val manualUrlsJson: String? = null,
        val crc32: String? = null,
        val scraperSource: String? = null,
        val lastScrapedAt: Long? = null,
    )

    @Serializable
    data class ArchivedCheat(
        val title: String,
        val description: String? = null,
        val systemId: String,
        val cheatType: String,
        val code: String,
        val enabled: Boolean = true,
        val isMasterCode: Boolean = false,
        val masterCodeIndex: Int? = null,
        val orderIndex: Int = 0,
    )

    suspend fun captureSnapshot(game: Game): GameDataSnapshot {
        val details = db.gameDetailsDao().getGameDetailsDirect(game.id)
        val rawCheats = db.cheatDao().getCheatsForGameSync(game.id)
        val idToIndex = rawCheats.mapIndexed { index, cheat -> cheat.id to index }.toMap()
        val cheats = rawCheats.map { cheat ->
            ArchivedCheat(
                title = cheat.title,
                description = cheat.description,
                systemId = cheat.systemId,
                cheatType = cheat.cheatType,
                code = cheat.code,
                enabled = cheat.enabled,
                isMasterCode = cheat.isMasterCode,
                masterCodeIndex = cheat.masterCodeId?.let { idToIndex[it] },
                orderIndex = cheat.orderIndex,
            )
        }
        val collectionIds = db.collectionDao().getCollectionIdsForGameSync(game.id)
        return GameDataSnapshot(
            game = game,
            details = details,
            cheats = cheats,
            collectionIds = collectionIds,
        )
    }

    suspend fun migrateSnapshotToGame(
        snapshot: GameDataSnapshot,
        targetGame: Game,
    ) {
        val oldGame = snapshot.game
        val sameFileName = oldGame.fileName.equals(targetGame.fileName, ignoreCase = true)
        val migratedCustomCoverUri = if (oldGame.customCoverUri != null) {
            customCoverManager.migrateCover(oldGame.id, targetGame.id) ?: targetGame.customCoverUri
        } else {
            targetGame.customCoverUri
        }

        val wasScraped = isScraped(snapshot.details?.lastScrapedAt, snapshot.details?.scraperSource)
        val updatedGame = targetGame.copy(
            title = chooseRestoredTitle(targetGame.title, oldGame.title, wasScraped, sameFileName),
            developer = targetGame.developer ?: oldGame.developer,
            coverFrontUrl = chooseRestoredCoverUrl(targetGame.coverFrontUrl, oldGame.coverFrontUrl, wasScraped),
            lastPlayedAt = maxNullable(targetGame.lastPlayedAt, oldGame.lastPlayedAt),
            totalPlayTimeMs = targetGame.totalPlayTimeMs + oldGame.totalPlayTimeMs,
            isFavorite = targetGame.isFavorite || oldGame.isFavorite,
            customName = targetGame.customName ?: oldGame.customName,
            customCoverUri = migratedCustomCoverUri,
        )
        db.gameDao().update(updatedGame)

        restoreAssociatedEntities(
            targetGameId = targetGame.id,
            sameFileName = sameFileName,
            details = snapshot.details?.let { toArchivedDetails(it) },
            cheats = snapshot.cheats,
            collectionIds = snapshot.collectionIds,
        )

        Timber.i(
            "Migrated library data from '${oldGame.fileName}' (id=${oldGame.id}) " +
                "to '${targetGame.fileName}' (id=${targetGame.id})",
        )
    }

    fun archiveSnapshots(snapshots: List<GameDataSnapshot>) {
        val worthArchiving = snapshots.filter { it.isWorthPreserving() }
        if (worthArchiving.isEmpty()) return

        synchronized(lock) {
            val current = readArchiveLocked().toMutableList()
            val now = System.currentTimeMillis()

            for (snapshot in worthArchiving) {
                val g = snapshot.game
                val replaced = current.filter {
                    it.systemId == g.systemId && it.fileName.equals(g.fileName, ignoreCase = true)
                }
                replaced.forEach { customCoverManager.deleteArchivedCoversForKey(it.archiveId) }
                current.removeAll(replaced)

                val archiveId = UUID.randomUUID().toString()
                val coverArchived = if (g.customCoverUri != null) {
                    customCoverManager.archiveCover(g.id, archiveId)
                } else {
                    false
                }

                current.add(
                    ArchivedGameEntry(
                        archiveId = archiveId,
                        systemId = g.systemId,
                        fileName = g.fileName,
                        title = g.title,
                        normalizedTitle = normalizeComparableKey(g.title),
                        normalizedFileBase = normalizeComparableKey(g.fileName.substringBeforeLast('.')),
                        developer = g.developer,
                        coverFrontUrl = g.coverFrontUrl,
                        lastPlayedAt = g.lastPlayedAt,
                        totalPlayTimeMs = g.totalPlayTimeMs,
                        isFavorite = g.isFavorite,
                        customName = g.customName,
                        hasCustomCover = coverArchived,
                        details = snapshot.details?.let { toArchivedDetails(it) },
                        cheats = snapshot.cheats,
                        collectionIds = snapshot.collectionIds,
                        archivedAtMs = now,
                    ),
                )
            }

            if (current.size > MAX_ARCHIVE_ENTRIES) {
                val sorted = current.sortedByDescending { it.archivedAtMs }
                sorted.drop(MAX_ARCHIVE_ENTRIES).forEach {
                    customCoverManager.deleteArchivedCoversForKey(it.archiveId)
                }
                writeArchiveLocked(sorted.take(MAX_ARCHIVE_ENTRIES))
            } else {
                writeArchiveLocked(current)
            }
        }
    }

    suspend fun restoreFromArchiveIfMatched(game: Game): Boolean {
        val matched = synchronized(lock) {
            val entries = readArchiveLocked()
            if (entries.isEmpty()) return@synchronized null

            val found = findBestArchivedMatch(game, entries) ?: return@synchronized null
            val remaining = entries.filterNot { it.archiveId == found.archiveId }
            writeArchiveLocked(remaining)
            found
        } ?: return false

        val sameFileName = matched.fileName.equals(game.fileName, ignoreCase = true)
        val restoredCoverUri = if (matched.hasCustomCover) {
            customCoverManager.restoreArchivedCover(matched.archiveId, game.id) ?: game.customCoverUri
        } else {
            game.customCoverUri
        }

        val wasScraped = isScraped(matched.details?.lastScrapedAt, matched.details?.scraperSource)
        val updatedGame = game.copy(
            title = chooseRestoredTitle(game.title, matched.title, wasScraped, sameFileName),
            developer = game.developer ?: matched.developer,
            coverFrontUrl = chooseRestoredCoverUrl(game.coverFrontUrl, matched.coverFrontUrl, wasScraped),
            lastPlayedAt = maxNullable(game.lastPlayedAt, matched.lastPlayedAt),
            totalPlayTimeMs = game.totalPlayTimeMs + matched.totalPlayTimeMs,
            isFavorite = game.isFavorite || matched.isFavorite,
            customName = game.customName ?: matched.customName,
            customCoverUri = restoredCoverUri,
        )
        db.gameDao().update(updatedGame)

        restoreAssociatedEntities(
            targetGameId = game.id,
            sameFileName = sameFileName,
            details = matched.details,
            cheats = matched.cheats,
            collectionIds = matched.collectionIds,
        )

        Timber.i(
            "Restored archived library data from '${matched.fileName}' " +
                "to newly indexed '${game.fileName}' (id=${game.id})",
        )
        return true
    }

    private suspend fun restoreAssociatedEntities(
        targetGameId: Int,
        sameFileName: Boolean,
        details: ArchivedGameDetails?,
        cheats: List<ArchivedCheat>,
        collectionIds: List<Int>,
    ) {
        if (details != null && db.gameDetailsDao().getGameDetailsDirect(targetGameId) == null) {
            db.gameDetailsDao().insertOrUpdate(
                GameDetails(
                    gameId = targetGameId,
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
                    families = details.families,
                    manualUrlsJson = details.manualUrlsJson,
                    crc32 = if (sameFileName) details.crc32 else null,
                    scraperSource = details.scraperSource,
                    lastScrapedAt = details.lastScrapedAt,
                ),
            )
        }

        if (cheats.isNotEmpty() && db.cheatDao().getCheatsForGameSync(targetGameId).isEmpty()) {
            val baseEntities = cheats.map { c ->
                Cheat(
                    gameId = targetGameId,
                    title = c.title,
                    description = c.description,
                    systemId = c.systemId,
                    cheatType = c.cheatType,
                    code = c.code,
                    enabled = c.enabled,
                    isMasterCode = c.isMasterCode,
                    masterCodeId = null,
                    orderIndex = c.orderIndex,
                )
            }
            val insertedIds = db.cheatDao().insertCheats(baseEntities)
            cheats.forEachIndexed { index, archivedCheat ->
                val masterIdx = archivedCheat.masterCodeIndex
                if (masterIdx != null && masterIdx in insertedIds.indices && index in insertedIds.indices) {
                    db.cheatDao().updateCheat(
                        baseEntities[index].copy(
                            id = insertedIds[index],
                            masterCodeId = insertedIds[masterIdx],
                        ),
                    )
                }
            }
        }

        if (collectionIds.isNotEmpty()) {
            val validCollectionIds = db.collectionDao().getAllCollectionIdsSync().toHashSet()
            collectionIds.filter { it in validCollectionIds }.forEach { collectionId ->
                db.collectionDao().addGameToCollection(
                    GameCollectionCrossRef(collectionId = collectionId, gameId = targetGameId),
                )
            }
        }
    }

    private fun findBestArchivedMatch(
        game: Game,
        entries: List<ArchivedGameEntry>,
    ): ArchivedGameEntry? {
        val sameSystem = entries.filter { it.systemId == game.systemId }
        if (sameSystem.isEmpty()) return null

        sameSystem.firstOrNull { it.fileName.equals(game.fileName, ignoreCase = true) }
            ?.let { return it }

        val keys = candidateKeysForGame(game)
        if (keys.isEmpty()) return null

        return sameSystem
            .filter { entry ->
                entry.normalizedTitle in keys || entry.normalizedFileBase in keys
            }
            .maxByOrNull { it.archivedAtMs }
    }

    private fun isScraped(
        lastScrapedAt: Long?,
        scraperSource: String?,
    ): Boolean {
        return lastScrapedAt != null && lastScrapedAt > 0L && scraperSource != "None"
    }

    private fun chooseRestoredTitle(
        currentTitle: String,
        previousTitle: String,
        wasScraped: Boolean,
        sameFileName: Boolean,
    ): String {
        if ((sameFileName || wasScraped) && previousTitle.isNotBlank()) return previousTitle
        return currentTitle
    }

    private fun chooseRestoredCoverUrl(
        currentCoverUrl: String?,
        previousCoverUrl: String?,
        wasScraped: Boolean,
    ): String? {
        if (wasScraped && !previousCoverUrl.isNullOrBlank()) return previousCoverUrl
        return currentCoverUrl ?: previousCoverUrl
    }

    private fun toArchivedDetails(details: GameDetails) =
        ArchivedGameDetails(
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
            families = details.families,
            manualUrlsJson = details.manualUrlsJson,
            crc32 = details.crc32,
            scraperSource = details.scraperSource,
            lastScrapedAt = details.lastScrapedAt,
        )

    private fun readArchiveLocked(): List<ArchivedGameEntry> {
        val file = archiveFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ArchivedGameEntry>>(file.readText())
        }.onFailure {
            Timber.e(it, "Failed to read deleted games archive")
        }.getOrDefault(emptyList())
    }

    private fun writeArchiveLocked(entries: List<ArchivedGameEntry>) {
        val file = archiveFile()
        runCatching {
            if (entries.isEmpty()) {
                if (file.exists()) file.delete()
            } else {
                file.writeText(json.encodeToString(entries))
            }
        }.onFailure {
            Timber.e(it, "Failed to write deleted games archive")
        }
    }

    private fun maxNullable(a: Long?, b: Long?): Long? =
        when {
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }

    companion object {
        private const val ARCHIVE_FILE_NAME = "deleted_games_archive.json"
        private const val MAX_ARCHIVE_ENTRIES = 1000

        private val DISC_TOKEN_REGEX = Regex("\\((?:disc|disk)\\s*([a-z0-9]+)\\)", RegexOption.IGNORE_CASE)
        private val TAGS_REGEX = Regex("\\([^)]*\\)|\\[[^\\]]*]")
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")

        fun findReplacementCandidate(
            deletedGame: Game,
            candidates: List<Game>,
        ): Game? {
            val sameSystem = candidates.filter { it.systemId == deletedGame.systemId }
            if (sameSystem.isEmpty()) return null

            sameSystem.firstOrNull { it.fileName.equals(deletedGame.fileName, ignoreCase = true) }
                ?.let { return it }

            val deletedKeys = candidateKeysForGame(deletedGame)
            if (deletedKeys.isEmpty()) return null

            return sameSystem.firstOrNull { candidate ->
                candidateKeysForGame(candidate).any { it in deletedKeys }
            }
        }

        fun candidateKeysForGame(game: Game): Set<String> {
            val keys = mutableSetOf<String>()
            val titleKey = normalizeComparableKey(game.title)
            if (titleKey.isNotEmpty()) keys.add(titleKey)
            val fileKey = normalizeComparableKey(game.fileName.substringBeforeLast('.'))
            if (fileKey.isNotEmpty()) keys.add(fileKey)
            return keys
        }

        fun normalizeComparableKey(raw: String): String {
            if (raw.isBlank()) return ""
            val discSuffix = DISC_TOKEN_REGEX.find(raw)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
            val stripped = TAGS_REGEX.replace(raw, " ")
            val normalized = NON_ALNUM_REGEX.replace(stripped.lowercase(Locale.ROOT), " ").trim()
            return if (discSuffix != null && normalized.isNotEmpty()) {
                "$normalized disc $discSuffix"
            } else {
                normalized
            }
        }
    }
}
