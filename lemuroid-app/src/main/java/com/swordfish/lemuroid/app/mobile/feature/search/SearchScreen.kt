package com.swordfish.lemuroid.app.mobile.feature.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidEmptyView
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameListRow
import com.swordfish.lemuroid.lib.library.db.entity.Game

@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel,
    searchQuery: String,
    recentSearches: List<String> = emptyList(),
    showQuickPlayButton: Boolean = false,
    onRecentSearchClick: (String) -> Unit = {},
    onRemoveRecentSearch: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    onSaveRecentSearch: (String) -> Unit = {},
    onGameClick: (Game) -> Unit,
    onGameQuickPlay: ((Game) -> Unit)? = null,
    onGameLongClick: (Game) -> Unit,
    onGameFavoriteToggle: (Game, Boolean) -> Unit,
) {
    val searchState = viewModel.searchState.collectAsState()
    val searchGames = viewModel.searchResults.collectAsLazyPagingItems()
    val ratingsByGameId = viewModel.ratingsByGameId.collectAsState().value
    val focusManager = LocalFocusManager.current

    LaunchedEffect(key1 = searchQuery) {
        viewModel.queryString.value = searchQuery
    }

    AnimatedContent(
        targetState = searchState.value,
        label = "SearchContent",
        transitionSpec = { fadeIn() togetherWith fadeOut() },
    ) { state ->
        when {
            state == SearchViewModel.UIState.Idle -> {
                if (recentSearches.isEmpty()) {
                    SearchEmptyView(modifier, stringResource(R.string.game_page_search_suggestion))
                } else {
                    RecentSearchesView(
                        modifier = modifier,
                        recentSearches = recentSearches,
                        onRecentSearchClick = { query ->
                            focusManager.clearFocus(true)
                            onRecentSearchClick(query)
                        },
                        onRemoveRecentSearch = onRemoveRecentSearch,
                        onClearRecentSearches = onClearRecentSearches,
                    )
                }
            }

            state == SearchViewModel.UIState.Loading -> {
                SearchLoadingView(modifier)
            }

            state == SearchViewModel.UIState.Ready && searchGames.itemCount == 0 -> {
                SearchEmptyView(modifier, stringResource(id = R.string.empty_view_default))
            }

            else -> {
                SearchResultsView(
                    modifier,
                    searchGames,
                    ratingsByGameId,
                    showQuickPlayButton,
                    onGameClick = { game ->
                        if (searchQuery.isNotBlank()) onSaveRecentSearch(searchQuery)
                        onGameClick(game)
                    },
                    onGameQuickPlay = onGameQuickPlay?.let { handler ->
                        { game ->
                            if (searchQuery.isNotBlank()) onSaveRecentSearch(searchQuery)
                            handler(game)
                        }
                    },
                    onGameLongClick = { game ->
                        if (searchQuery.isNotBlank()) onSaveRecentSearch(searchQuery)
                        onGameLongClick(game)
                    },
                    onGameFavoriteToggle,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentSearchesView(
    modifier: Modifier,
    recentSearches: List<String>,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        item(key = "recent_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.search_recent_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onClearRecentSearches,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.search_recent_clear),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        items(
            items = recentSearches,
            key = { "recent_$it" },
        ) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .clickable { onRecentSearchClick(entry) }
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = entry,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onRemoveRecentSearch(entry) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 46.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultsView(
    modifier: Modifier,
    games: LazyPagingItems<Game>,
    ratingsByGameId: Map<Int, Float>,
    showQuickPlayButton: Boolean,
    onGameClick: (Game) -> Unit,
    onGameQuickPlay: ((Game) -> Unit)?,
    onGameLongClick: (Game) -> Unit,
    onGameFavoriteToggle: (Game, Boolean) -> Unit,
) {
    LazyColumn(modifier = modifier) {
        items(games.itemCount, key = { index -> "${games[index]?.id}_$index" }) { index ->
            val game = games[index] ?: return@items

            LemuroidGameListRow(
                modifier = Modifier.animateItem(),
                game = game,
                rating = ratingsByGameId[game.id],
                onClick = { onGameClick(game) },
                onLongClick = { onGameLongClick(game) },
                onFavoriteToggle = { isFavorite ->
                    onGameFavoriteToggle(game, isFavorite)
                },
                onQuickPlayClick = if (showQuickPlayButton && onGameQuickPlay != null) {
                    { onGameQuickPlay(game) }
                } else null,
            )
        }
    }
}

@Composable
private fun SearchEmptyView(
    modifier: Modifier,
    text: String,
) {
    LemuroidEmptyView(
        modifier = modifier,
        text = text,
    )
}

@Composable
private fun SearchLoadingView(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
