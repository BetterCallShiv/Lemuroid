package com.swordfish.lemuroid.app.mobile.feature.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.settings.StorageFrameworkPickerLauncher
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.SystemRomSizeCache
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.dao.LibraryStatsRaw
import com.swordfish.lemuroid.lib.library.db.dao.SystemCount
import com.swordfish.lemuroid.lib.library.db.entity.CollectionWithGames
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.db.entity.GameCollection
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.scraper.MediaStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

@OptIn(FlowPreview::class)
class HomeViewModel(
    appContext: Context,
    private val retrogradeDb: RetrogradeDatabase,
    private val coresSelection: CoresSelection,
) : ViewModel() {
    companion object {
        const val CAROUSEL_MAX_ITEMS = 10
        const val TOP_RATED_POOL_SIZE = 50
        const val DEBOUNCE_TIME = 100L
        private const val PREF_MICROPHONE_BANNER_DISMISSED = "home_microphone_banner_dismissed"
    }

    class Factory(
        val appContext: Context,
        val retrogradeDb: RetrogradeDatabase,
        val coresSelection: CoresSelection,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(appContext, retrogradeDb, coresSelection) as T
        }
    }

    data class LibraryStats(
        val totalGames: Int = 0,
        val totalSystems: Int = 0,
        val playedGames: Int = 0,
        val favoriteGames: Int = 0,
        val scrapedGames: Int = 0,
        val totalPlayTimeMs: Long = 0L,
        val collectionsCount: Int = 0,
        val mediaStorageBytes: Long = 0L,
        val mediaFileCount: Int = 0,
        val systemBreakdown: List<SystemCount> = emptyList(),
        val systemRomBytes: Map<String, Long> = emptyMap(),
    )

    data class UIState(
        val continuePlayingGame: Game? = null,
        val favoritesGames: List<Game> = emptyList(),
        val recentGames: List<Game> = emptyList(),
        val topRatedGames: List<Game> = emptyList(),
        val discoveryGames: List<Game> = emptyList(),
        val collections: List<CollectionWithGames> = emptyList(),
        val ratingsByGameId: Map<Int, Float> = emptyMap(),
        val libraryStats: LibraryStats = LibraryStats(),
        val indexInProgress: Boolean = true,
        val showNoNotificationPermissionCard: Boolean = false,
        val showNoMicrophonePermissionCard: Boolean = false,
        val showNoGamesCard: Boolean = false,
        val showDesmumeDeprecatedCard: Boolean = false,
    )

    private data class NotificationFlags(
        val indexInProgress: Boolean,
        val notificationsPermissionEnabled: Boolean,
        val showMicrophoneCard: Boolean,
        val showDesmumeWarning: Boolean,
    )

    private data class StatsBundle(
        val raw: LibraryStatsRaw,
        val systems: List<SystemCount>,
        val mediaStats: MediaStorageManager.MediaStorageStats,
        val systemRomBytes: Map<String, Long>,
        val topRatedGames: List<Game>,
        val ratingsByGameId: Map<Int, Float>,
    )

    private val applicationContext = appContext.applicationContext
    private val sharedPreferences = SharedPreferencesHelper.getSharedPreferences(appContext)
    private val mediaStorageManager = MediaStorageManager(appContext)
    private val mediaStatsState = MutableStateFlow(MediaStorageManager.MediaStorageStats())
    private val systemRomBytesState = MutableStateFlow<Map<String, Long>>(
        SystemRomSizeCache.getSizes(applicationContext) ?: emptyMap(),
    )
    private var romSizeJob: Job? = null
    private val discoveryShuffleSeed = MutableStateFlow(0)
    private val topRatedShuffleSeed = MutableStateFlow(0)
    private val microphonePermissionEnabledState = MutableStateFlow(true)
    private val microphoneBannerDismissedState =
        MutableStateFlow(sharedPreferences.getBoolean(PREF_MICROPHONE_BANNER_DISMISSED, false))
    private val notificationsPermissionEnabledState = MutableStateFlow(true)
    private val uiStates = MutableStateFlow(UIState())

    fun dismissMicrophoneBanner() {
        microphoneBannerDismissedState.value = true
        sharedPreferences.edit().putBoolean(PREF_MICROPHONE_BANNER_DISMISSED, true).apply()
    }

    fun getViewStates(): Flow<UIState> {
        return uiStates
    }

    fun changeLocalStorageFolder(context: Context) {
        StorageFrameworkPickerLauncher.pickFolder(context)
    }

    fun updatePermissions(context: Context) {
        notificationsPermissionEnabledState.value = isNotificationsPermissionGranted(context)
        microphonePermissionEnabledState.value = isMicrophonePermissionGranted(context)
        refreshMediaStats()
        refreshSystemRomSizes()
    }

    fun refreshMediaStats() {
        viewModelScope.launch(Dispatchers.IO) {
            mediaStatsState.value = runCatching {
                mediaStorageManager.calculateStorageStats()
            }.getOrDefault(MediaStorageManager.MediaStorageStats())
        }
    }

    fun refreshSystemRomSizes() {
        val cached = SystemRomSizeCache.getSizes(applicationContext)
        if (cached != null) {
            systemRomBytesState.value = cached
            return
        }
        if (romSizeJob?.isActive == true) return
        romSizeJob = viewModelScope.launch(Dispatchers.IO) {
            val games = runCatching { retrogradeDb.gameDao().selectAllGames() }.getOrDefault(emptyList())
            val semaphore = Semaphore(12)
            val resolved = coroutineScope {
                games.map { game ->
                    async {
                        semaphore.withPermit {
                            game.systemId to resolveRomFileSizeBytes(game.fileUri)
                        }
                    }
                }.awaitAll()
            }
            val bySystem = HashMap<String, Long>()
            for ((sysId, size) in resolved) {
                if (size > 0L) {
                    bySystem[sysId] = (bySystem[sysId] ?: 0L) + size
                }
            }
            SystemRomSizeCache.saveSizes(applicationContext, bySystem)
            systemRomBytesState.value = bySystem
        }
    }

    private fun resolveRomFileSizeBytes(uriString: String): Long {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return 0L
        return when (uri.scheme) {
            "file", null -> {
                val path = uri.path ?: uriString.takeIf { it.startsWith("/") }
                path?.let { File(it).takeIf(File::exists)?.length() } ?: 0L
            }
            "content" -> {
                val queried = runCatching {
                    applicationContext.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        val colIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (colIdx >= 0 && cursor.moveToFirst() && !cursor.isNull(colIdx)) {
                            cursor.getLong(colIdx)
                        } else null
                    }
                }.getOrNull()?.takeIf { it > 0L }
                queried ?: runCatching {
                    applicationContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        pfd.statSize.takeIf { it > 0L }
                    }
                }.getOrNull() ?: 0L
            }
            else -> 0L
        }
    }

    fun shuffleDiscovery() {
        discoveryShuffleSeed.value = discoveryShuffleSeed.value + 1
    }

    fun shuffleTopRated() {
        topRatedShuffleSeed.value = topRatedShuffleSeed.value + 1
    }

    fun createCollection(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            retrogradeDb.collectionDao().insertCollection(GameCollection(name = trimmed))
        }
    }

    fun renameCollection(collection: GameCollection, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == collection.name) return
        viewModelScope.launch(Dispatchers.IO) {
            retrogradeDb.collectionDao().updateCollection(collection.copy(name = trimmed))
        }
    }

    fun deleteCollection(collection: GameCollection) {
        viewModelScope.launch(Dispatchers.IO) {
            retrogradeDb.collectionDao().deleteCollection(collection)
        }
    }

    private fun isNotificationsPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        val permissionResult =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            )

        return permissionResult == PackageManager.PERMISSION_GRANTED
    }

    private fun isMicrophonePermissionGranted(context: Context): Boolean {
        val permissionResult =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            )

        return permissionResult == PackageManager.PERMISSION_GRANTED
    }

    private fun buildViewState(
        favoritesGames: List<Game>,
        recentGames: List<Game>,
        discoveryGames: List<Game>,
        collections: List<CollectionWithGames>,
        flagsAndStats: Pair<NotificationFlags, StatsBundle>,
    ): UIState {
        val (flags, statsBundle) = flagsAndStats
        val noGames = recentGames.isEmpty() && favoritesGames.isEmpty() && discoveryGames.isEmpty()
        val continuePlaying = (recentGames + favoritesGames)
            .filter { it.lastPlayedAt != null }
            .maxByOrNull { it.lastPlayedAt ?: 0L }
        val remainingRecents = if (continuePlaying != null) {
            recentGames.filter { it.id != continuePlaying.id }
        } else {
            recentGames
        }

        val libraryStats = LibraryStats(
            totalGames = statsBundle.raw.totalGames,
            totalSystems = statsBundle.raw.totalSystems,
            playedGames = statsBundle.raw.playedGames,
            favoriteGames = statsBundle.raw.favoriteGames,
            scrapedGames = statsBundle.raw.scrapedGames,
            totalPlayTimeMs = statsBundle.raw.totalPlayTimeMs,
            collectionsCount = collections.size,
            mediaStorageBytes = statsBundle.mediaStats.totalBytes,
            mediaFileCount = statsBundle.mediaStats.fileCount,
            systemBreakdown = statsBundle.systems.sortedByDescending { it.count },
            systemRomBytes = statsBundle.systemRomBytes,
        )

        return UIState(
            continuePlayingGame = continuePlaying,
            favoritesGames = favoritesGames,
            recentGames = remainingRecents,
            topRatedGames = statsBundle.topRatedGames,
            discoveryGames = discoveryGames,
            collections = collections,
            ratingsByGameId = statsBundle.ratingsByGameId,
            libraryStats = libraryStats,
            indexInProgress = flags.indexInProgress,
            showNoNotificationPermissionCard = !flags.notificationsPermissionEnabled,
            showNoMicrophonePermissionCard = flags.showMicrophoneCard,
            showNoGamesCard = noGames,
            showDesmumeDeprecatedCard = flags.showDesmumeWarning,
        )
    }

    init {
        refreshMediaStats()
        refreshSystemRomSizes()
        viewModelScope.launch {
            val notificationsFlow = combine(
                indexingInProgress(appContext),
                notificationsPermissionEnabledState,
                microphoneNotification(retrogradeDb),
                desmumeWarningNotification(),
            ) { indexInProgress, notificationsEnabled, showMic, showDesmume ->
                NotificationFlags(
                    indexInProgress = indexInProgress,
                    notificationsPermissionEnabled = notificationsEnabled,
                    showMicrophoneCard = showMic,
                    showDesmumeWarning = showDesmume,
                )
            }

            val ratingsMapFlow = retrogradeDb.gameDetailsDao().getAllRatings()
                .map { list -> list.associate { it.gameId to it.rating } }

            val topRatedFlow = combine(
                retrogradeDb.gameDao().selectTopRated(TOP_RATED_POOL_SIZE),
                topRatedShuffleSeed,
            ) { pool, seed ->
                if (seed == 0) {
                    pool.take(CAROUSEL_MAX_ITEMS)
                } else {
                    pool.shuffled(kotlin.random.Random(System.nanoTime() + seed))
                        .take(CAROUSEL_MAX_ITEMS)
                }
            }

            val storageStatsFlow = combine(
                mediaStatsState,
                systemRomBytesState,
                SystemRomSizeCache.sizesFlow,
            ) { mediaStats, romBytes, liveCachedSizes ->
                mediaStats to (liveCachedSizes ?: romBytes)
            }

            val statsFlow = combine(
                retrogradeDb.gameDao().selectLibraryStats(),
                retrogradeDb.gameDao().selectSystemsWithCount(),
                storageStatsFlow,
                topRatedFlow,
                ratingsMapFlow,
            ) { raw, systems, (mediaStats, romBytes), topRated, ratingsMap ->
                StatsBundle(raw, systems, mediaStats, romBytes, topRated, ratingsMap)
            }

            val flagsAndStatsFlow = combine(notificationsFlow, statsFlow) { flags, stats ->
                flags to stats
            }

            val uiStatesFlow = combine(
                favoritesGames(retrogradeDb),
                recentGames(retrogradeDb),
                discoveryGames(retrogradeDb),
                retrogradeDb.collectionDao().getCollectionsWithGames(),
                flagsAndStatsFlow,
                ::buildViewState,
            )

            uiStatesFlow
                .debounce(DEBOUNCE_TIME)
                .flowOn(Dispatchers.IO)
                .collect { uiStates.value = it }
        }
    }

    private fun indexingInProgress(appContext: Context) =
        PendingOperationsMonitor(appContext).anyLibraryOperationInProgress()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun discoveryGames(retrogradeDb: RetrogradeDatabase): Flow<List<Game>> =
        combine(
            retrogradeDb.gameDao().selectFirstNotPlayed(1),
            discoveryShuffleSeed,
        ) { _, seed -> seed }
            .flatMapLatest {
                kotlinx.coroutines.flow.flow {
                    emit(retrogradeDb.gameDao().selectRandomNotPlayed(CAROUSEL_MAX_ITEMS))
                }
            }

    private fun recentGames(retrogradeDb: RetrogradeDatabase) =
        retrogradeDb.gameDao().selectFirstUnfavoriteRecents(CAROUSEL_MAX_ITEMS)

    private fun favoritesGames(retrogradeDb: RetrogradeDatabase) =
        retrogradeDb.gameDao().selectFirstFavorites(CAROUSEL_MAX_ITEMS)

    private fun dsGamesCount(retrogradeDb: RetrogradeDatabase): Flow<Int> {
        return retrogradeDb.gameDao().selectSystemsWithCount()
            .map { systems ->
                systems
                    .firstOrNull { it.systemId == SystemID.NDS.dbname }
                    ?.count
                    ?: 0
            }
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun microphoneNotification(db: RetrogradeDatabase): Flow<Boolean> {
        return combine(
            microphonePermissionEnabledState,
            microphoneBannerDismissedState,
        ) { isMicrophoneEnabled, isDismissed -> isMicrophoneEnabled || isDismissed }
            .flatMapLatest { shouldHide ->
                if (shouldHide) {
                    flowOf(false)
                } else {
                    combine(
                        coresSelection.getSelectedCores(),
                        dsGamesCount(db),
                    ) { cores, dsCount ->
                        cores.any { it.coreConfig.supportsMicrophone } &&
                            dsCount > 0
                    }
                }
                    .distinctUntilChanged()
            }
    }

    private fun desmumeWarningNotification(): Flow<Boolean> {
        return coresSelection.getSelectedCores()
            .map { cores -> cores.any { it.coreConfig.coreID == CoreID.DESMUME } }
            .distinctUntilChanged()
    }
}
