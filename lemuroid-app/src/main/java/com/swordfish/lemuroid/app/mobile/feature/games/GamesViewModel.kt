package com.swordfish.lemuroid.app.mobile.feature.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.dao.GameListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

class GamesViewModel(
    private val retrogradeDb: RetrogradeDatabase,
    initialMetaSystem: MetaSystemID?,
) : ViewModel() {
    class Factory(
        private val retrogradeDb: RetrogradeDatabase,
        private val initialMetaSystem: MetaSystemID?,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GamesViewModel(retrogradeDb, initialMetaSystem) as T
        }
    }

    private val metaSystemId = MutableStateFlow(initialMetaSystem)
    private val searchQuery = MutableStateFlow("")
    private val selectedGenres = MutableStateFlow<Set<String>>(emptySet())
    private val selectedFamily = MutableStateFlow<String?>(null)
    private val selectedYear = MutableStateFlow<String?>(null)
    private val selectedRating = MutableStateFlow<Float?>(null)
    private val selectedTopStaff = MutableStateFlow(false)
    private val sortOrder = MutableStateFlow(GameSortOrder.NAME_ASC)

    fun changeSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun changeSelectedGenres(genres: Set<String>) {
        selectedGenres.value = genres
    }

    fun changeSelectedFamily(family: String?) {
        selectedFamily.value = family
    }

    fun changeSelectedYear(year: String?) {
        selectedYear.value = year
    }

    fun changeSelectedRating(rating: Float?) {
        selectedRating.value = rating
    }

    fun changeSelectedTopStaff(enabled: Boolean) {
        selectedTopStaff.value = enabled
    }

    fun changeSortOrder(order: GameSortOrder) {
        sortOrder.value = order
    }

    private val systemDbNames =
        metaSystemId.map { metaSystem ->
            metaSystem?.systemIDs?.map { it.dbname } ?: SystemID.values().map { it.dbname }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val availableGenres: StateFlow<List<String>> =
        systemDbNames
            .flatMapLatest { systemIds ->
                if (systemIds.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    retrogradeDb.gameDao().selectGenresForSystems(systemIds)
                        .map { rawGenres ->
                            rawGenres
                                .asSequence()
                                .flatMap { it.split(GENRE_SPLIT_REGEX).asSequence() }
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .distinctBy { it.lowercase() }
                                .sortedBy { it.lowercase() }
                                .toList()
                        }
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    val hasTopStaffGames: StateFlow<Boolean> =
        systemDbNames
            .flatMapLatest { systemIds ->
                if (systemIds.isEmpty()) {
                    flowOf(false)
                } else {
                    retrogradeDb.gameDao().selectHasTopStaffForSystems(systemIds)
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = false,
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    val games: StateFlow<List<GameListItem>> =
        combine(
            combine(systemDbNames, searchQuery.map { it.trim() }, selectedGenres, selectedFamily) { systemIds, query, genres, family ->
                Quad(systemIds, query, genres, family)
            },
            combine(sortOrder, selectedYear, selectedRating, selectedTopStaff) { order, year, rating, topStaff ->
                Quad(order, year, rating, topStaff)
            },
        ) { (systemIds, query, genres, family), (order, year, rating, topStaff) ->
            FilterParams(systemIds, query, genres, family, order, year, rating, topStaff)
        }
            .flatMapLatest { (systemIds, query, genres, family, order, year, rating, topStaff) ->
                if (systemIds.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val selectedLower = genres.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
                    val targetFamily = family?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                    val targetYear = year?.trim()?.takeIf { it.isNotBlank() }
                    val targetRatingText = rating?.let { String.format(Locale.US, "%.1f", it) }

                    retrogradeDb.gameDao().selectBySystemsSorted(
                        systemIds = systemIds,
                        query = query,
                        sortOrder = order.name,
                    ).map { list ->
                        if (selectedLower.isEmpty() && targetFamily == null && targetYear == null && targetRatingText == null && !topStaff) {
                            list
                        } else {
                            list.filter { item ->
                                val matchesGenres = if (selectedLower.isEmpty()) {
                                    true
                                } else {
                                    val itemGenres = item.genres
                                        ?.split(GENRE_SPLIT_REGEX)
                                        ?.map { it.trim().lowercase() }
                                        ?.toSet()
                                        ?: emptySet()
                                    selectedLower.all { it in itemGenres }
                                }
                                val matchesFamily = targetFamily == null ||
                                    item.families
                                        ?.split(GENRE_SPLIT_REGEX)
                                        ?.any { it.trim().equals(targetFamily, ignoreCase = true) } == true
                                val matchesYear = targetYear == null ||
                                    item.releaseDate?.trim()?.startsWith(targetYear) == true
                                val itemRating = item.rating
                                val matchesRating = targetRatingText == null ||
                                    (itemRating != null && itemRating > 0f &&
                                        String.format(Locale.US, "%.1f", itemRating) == targetRatingText)
                                val matchesTopStaff = !topStaff || item.isTopStaff

                                matchesGenres && matchesFamily && matchesYear && matchesRating && matchesTopStaff
                            }
                        }
                    }
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private data class FilterParams(
        val systemIds: List<String>,
        val query: String,
        val genres: Set<String>,
        val family: String?,
        val sortOrder: GameSortOrder,
        val year: String?,
        val rating: Float?,
        val topStaff: Boolean,
    )

    companion object {
        const val ALL_SYSTEMS_ID = "ALL"
        private val GENRE_SPLIT_REGEX = Regex("[,|/•]")
    }
}
