package com.swordfish.lemuroid.lib.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val version: Int = CURRENT_VERSION,
    val appVersion: String,
    val createdAt: Long,
    val includesSaves: Boolean,
    val includesStates: Boolean,
    val includesUserData: Boolean,
    val includesCheats: Boolean,
    val includesSettings: Boolean,
    val includesCredentials: Boolean,
    val includesMedia: Boolean,
    val stats: BackupStats = BackupStats(),
) {
    companion object {
        const val CURRENT_VERSION = 1
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val SETTINGS_FILE_NAME = "settings.json"
        const val USERDATA_FILE_NAME = "userdata.json"
        const val CHEATS_FILE_NAME = "cheats.json"
        const val GAMEDETAILS_FILE_NAME = "gamedetails.json"
        const val COLLECTIONS_FILE_NAME = "collections.json"
    }
}

@Serializable
data class BackupStats(
    val saveCount: Int = 0,
    val stateCount: Int = 0,
    val cheatCount: Int = 0,
    val userDataCount: Int = 0,
    val gameDetailsCount: Int = 0,
    val collectionCount: Int = 0,
    val customCoverCount: Int = 0,
    val mediaCount: Int = 0,
    val totalUncompressedBytes: Long = 0L,
)

@Serializable
data class ExportedGameUserData(
    val fileName: String,
    val baseName: String,
    val systemId: String,
    val title: String,
    val developer: String? = null,
    val coverFrontUrl: String? = null,
    val isFavorite: Boolean = false,
    val totalPlayTimeMs: Long = 0L,
    val lastPlayedAt: Long? = null,
    val customName: String? = null,
    val customCoverZipEntry: String? = null,
)

@Serializable
data class ExportedGameDetails(
    val fileName: String,
    val systemId: String,
    val synopsis: String? = null,
    val genres: String? = null,
    val publisher: String? = null,
    val releaseDate: String? = null,
    val players: String? = null,
    val rating: Float? = null,
    val ratingCount: Int? = null,
    val isTopStaff: Boolean = false,
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
data class ExportedCheat(
    val fileName: String,
    val systemId: String,
    val title: String,
    val description: String? = null,
    val code: String,
    val cheatType: String,
    val enabled: Boolean = true,
    val isMasterCode: Boolean = false,
    val orderIndex: Int = 0,
    val exportId: Long? = null,
    val masterCodeExportId: Long? = null,
)

@Serializable
data class ExportedCollectionGameRef(
    val fileName: String,
    val systemId: String,
    val baseName: String = fileName.substringBeforeLast('.'),
    val addedAt: Long = 0L,
)

@Serializable
data class ExportedCollection(
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val games: List<ExportedCollectionGameRef> = emptyList(),
)

@Serializable
data class BackupOptions(
    val includeSaves: Boolean = true,
    val includeStates: Boolean = true,
    val includeUserData: Boolean = true,
    val includeCheats: Boolean = true,
    val includeSettings: Boolean = true,
    val includeCredentials: Boolean = false,
    val includeMedia: Boolean = false,
)

data class BackupEstimate(
    val uncompressedBytes: Long,
    val estimatedCompressedBytes: Long,
    val saveCount: Int,
    val stateCount: Int,
    val cheatCount: Int,
    val userDataCount: Int,
    val gameDetailsCount: Int = 0,
    val collectionCount: Int = 0,
    val customCoverCount: Int = 0,
    val mediaFileCount: Int,
    val availableBytes: Long,
    val hasEnoughSpace: Boolean,
)

data class RestoreResult(
    val savesRestored: Int = 0,
    val statesRestored: Int = 0,
    val userDataRestored: Int = 0,
    val gameDetailsRestored: Int = 0,
    val collectionsRestored: Int = 0,
    val customCoversRestored: Int = 0,
    val cheatsRestored: Int = 0,
    val mediaRestored: Int = 0,
    val settingsRestored: Boolean = false,
    val error: String? = null,
)

data class InspectedBackupInfo(
    val manifest: BackupManifest,
    val fileSizeBytes: Long,
)
