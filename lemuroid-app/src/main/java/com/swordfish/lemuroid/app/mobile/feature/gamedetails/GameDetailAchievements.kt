package com.swordfish.lemuroid.app.mobile.feature.gamedetails

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.retroachievements.AchievementDetail
import com.swordfish.lemuroid.app.shared.retroachievements.AchievementsUiState
import com.swordfish.lemuroid.app.shared.retroachievements.GameAchievementsData
import java.text.NumberFormat
import java.util.Locale

private enum class BarSegmentHint {
    HARDCORE,
    SOFTCORE,
    LOCKED,
}

private enum class AchievementSortOrder(
    val labelResId: Int,
    val icon: ImageVector,
) {
    UNLOCKED_FIRST(R.string.ra_sort_unlocked_first, Icons.Filled.LockOpen),
    DISPLAY_FIRST(R.string.ra_sort_display_first, Icons.Filled.ArrowDownward),
    DISPLAY_LAST(R.string.ra_sort_display_last, Icons.Filled.ArrowUpward),
    WON_BY_MOST(R.string.ra_sort_won_most, Icons.Filled.Group),
    WON_BY_LEAST(R.string.ra_sort_won_least, Icons.Filled.Group),
    POINTS_MOST(R.string.ra_sort_points_most, Icons.Filled.WorkspacePremium),
    POINTS_LEAST(R.string.ra_sort_points_least, Icons.Filled.WorkspacePremium),
    TITLE_ASC(R.string.ra_sort_title_asc, Icons.Filled.SortByAlpha),
    TITLE_DESC(R.string.ra_sort_title_desc, Icons.Filled.SortByAlpha),
    TYPE_ASC(R.string.ra_sort_type_asc, Icons.Filled.LocalOffer),
    TYPE_DESC(R.string.ra_sort_type_desc, Icons.Filled.LocalOffer),
}

private fun typeSortRank(type: String?): Int = when (type?.lowercase(Locale.ROOT)) {
    "progression" -> 0
    "win_condition" -> 1
    "missable" -> 2
    else -> 3
}

private fun sortAchievements(
    items: List<AchievementDetail>,
    order: AchievementSortOrder,
): List<AchievementDetail> = when (order) {
    AchievementSortOrder.UNLOCKED_FIRST ->
        items.sortedWith(compareByDescending<AchievementDetail> { it.isUnlocked }.thenBy { it.displayOrder })
    AchievementSortOrder.DISPLAY_FIRST ->
        items.sortedBy { it.displayOrder }
    AchievementSortOrder.DISPLAY_LAST ->
        items.sortedByDescending { it.displayOrder }
    AchievementSortOrder.WON_BY_MOST ->
        items.sortedWith(compareByDescending<AchievementDetail> { it.rarity ?: it.rarityHardcore ?: -1.0 }.thenBy { it.displayOrder })
    AchievementSortOrder.WON_BY_LEAST ->
        items.sortedWith(compareBy<AchievementDetail> { it.rarity ?: it.rarityHardcore ?: Double.MAX_VALUE }.thenBy { it.displayOrder })
    AchievementSortOrder.POINTS_MOST ->
        items.sortedWith(compareByDescending<AchievementDetail> { it.points }.thenBy { it.displayOrder })
    AchievementSortOrder.POINTS_LEAST ->
        items.sortedWith(compareBy<AchievementDetail> { it.points }.thenBy { it.displayOrder })
    AchievementSortOrder.TITLE_ASC ->
        items.sortedWith(compareBy<AchievementDetail> { it.title.lowercase(Locale.ROOT) }.thenBy { it.displayOrder })
    AchievementSortOrder.TITLE_DESC ->
        items.sortedWith(compareByDescending<AchievementDetail> { it.title.lowercase(Locale.ROOT) }.thenBy { it.displayOrder })
    AchievementSortOrder.TYPE_ASC ->
        items.sortedWith(compareBy<AchievementDetail> { typeSortRank(it.type) }.thenBy { it.displayOrder })
    AchievementSortOrder.TYPE_DESC ->
        items.sortedWith(compareByDescending<AchievementDetail> { typeSortRank(it.type) }.thenBy { it.displayOrder })
}

private val GoldAccent = Color(0xFFFFD700)
private val GrayscaleFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
fun GameDetailAchievementsSection(
    achievementsState: AchievementsUiState,
    isHardcore: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (achievementsState) {
        is AchievementsUiState.Disabled -> {
        }
        is AchievementsUiState.Loading -> {
            Card(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = GoldAccent,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.ra_game_details_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is AchievementsUiState.NoAchievements -> {
        
        }
        is AchievementsUiState.Error -> {
            Card(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.EmojiEvents,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = achievementsState.message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.retry),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        is AchievementsUiState.Success -> {
            GameDetailAchievementsCard(
                data = achievementsState.data,
                isHardcore = isHardcore,
                modifier = modifier,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailAchievementsCard(
    data: GameAchievementsData,
    isHardcore: Boolean,
    modifier: Modifier = Modifier,
) {
    var selectedAchievement by remember { mutableStateOf<AchievementDetail?>(null) }
    var showAllSheet by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 10.dp),
        ) {
        
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    tint = GoldAccent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ra_game_details_header),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isHardcore) GoldAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
                    border = if (isHardcore) androidx.compose.foundation.BorderStroke(1.dp, GoldAccent.copy(alpha = 0.6f)) else null,
                ) {
                    Text(
                        text = if (isHardcore) stringResource(R.string.ra_game_details_hardcore) else stringResource(R.string.ra_game_details_softcore),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isHardcore) GoldAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.ra_game_details_progress,
                        data.unlockedCount,
                        data.totalCount,
                        data.unlockedPoints,
                        data.totalPoints,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${data.completionPercentage.toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (data.unlockedCount > 0) GoldAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            val progressFraction = if (data.totalCount > 0) {
                data.unlockedCount.toFloat() / data.totalCount.toFloat()
            } else 0f

            LinearProgressIndicator(
                progress = { progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = GoldAccent,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            )

            Spacer(modifier = Modifier.height(14.dp))

            val previewItems = remember(data.achievements) { data.achievements.take(10) }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(previewItems, key = { it.id }) { item ->
                    AchievementBadgeThumbnail(
                        badgeUrl = item.badgeUrl,
                        title = item.title,
                        isUnlocked = item.isUnlocked,
                        size = 52.dp,
                        onClick = { selectedAchievement = item },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showAllSheet = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ra_game_details_view_all, data.totalCount),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = GoldAccent,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = GoldAccent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }


    selectedAchievement?.let { item ->
        AchievementDetailDialog(
            achievement = item,
            onDismiss = { selectedAchievement = null },
        )
    }

    if (showAllSheet) {
        AchievementsListBottomSheet(
            data = data,
            onSelectAchievement = { selectedAchievement = it },
            onDismiss = { showAllSheet = false },
        )
    }
}

@Composable
fun AchievementBadgeThumbnail(
    badgeUrl: String,
    title: String,
    isUnlocked: Boolean,
    size: Dp = 52.dp,
    onClick: () -> Unit,
) {
    val finalUrl = if (isUnlocked) {
        badgeUrl
    } else {
        if (badgeUrl.endsWith(".png")) badgeUrl.replace(".png", "_lock.png") else badgeUrl
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = if (isUnlocked) 1.5.dp else 1.dp,
                color = if (isUnlocked) GoldAccent else Color.White.copy(alpha = 0.22f),
                shape = RoundedCornerShape(10.dp),
            )
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (finalUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(finalUrl)
                    .addHeader("User-Agent", "RetroArch/1.19.0 (Android) rcheevos/11.7.0")
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp),
                contentScale = ContentScale.Fit,
            )
        }

        if (!isUnlocked) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AchievementDetailDialog(
    achievement: AchievementDetail,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val finalUrl = if (achievement.isUnlocked) {
        achievement.badgeUrl
    } else {
        if (achievement.badgeUrl.endsWith(".png")) achievement.badgeUrl.replace(".png", "_lock.png") else achievement.badgeUrl
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            width = if (achievement.isUnlocked) 2.dp else 1.dp,
                            color = if (achievement.isUnlocked) GoldAccent else Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (finalUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(finalUrl)
                                .addHeader("User-Agent", "RetroArch/1.19.0 (Android) rcheevos/11.7.0")
                                .crossfade(true)
                                .build(),
                            contentDescription = achievement.title,
                            modifier = Modifier.fillMaxSize().padding(4.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = achievement.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = if (achievement.isUnlocked) GoldAccent else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = GoldAccent.copy(alpha = 0.18f),
                    ) {
                        Text(
                            text = stringResource(R.string.ra_game_details_points_badge, achievement.points),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = GoldAccent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }

                    val typeLabel = when (achievement.type?.lowercase()) {
                        "progression" -> stringResource(R.string.ra_type_progression)
                        "missable" -> stringResource(R.string.ra_type_missable)
                        "win_condition" -> stringResource(R.string.ra_type_win_condition)
                        else -> null
                    }
                    if (typeLabel != null) {
                        val typeColor = if (achievement.type?.lowercase() == "missable") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = typeColor.copy(alpha = 0.18f),
                        ) {
                            Text(
                                text = typeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = typeColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }

                    if (achievement.isUnlocked) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = (if (achievement.isHardcore) GoldAccent else MaterialTheme.colorScheme.secondary).copy(alpha = 0.18f),
                        ) {
                            Text(
                                text = stringResource(
                                    if (achievement.isHardcore) R.string.ra_game_details_hardcore else R.string.ra_game_details_softcore,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (achievement.isHardcore) GoldAccent else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = stringResource(R.string.ra_status_locked),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = achievement.description,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )

                val rarityText = achievement.rarity?.let { r ->
                    val hc = achievement.rarityHardcore
                    if (hc != null && hc > 0.0) {
                        stringResource(R.string.ra_rarity_hardcore_format, r, hc)
                    } else {
                        stringResource(R.string.ra_rarity_format, r)
                    }
                }
                val hasFooterMeta = achievement.isUnlocked ||
                    rarityText != null ||
                    (achievement.numAwarded != null && (achievement.numPlayers ?: 0) > 0)
                if (hasFooterMeta) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (achievement.isUnlocked) {
                            val statusText = achievement.unlockDate?.let {
                                stringResource(R.string.ra_game_details_unlocked_on, it)
                            } ?: stringResource(R.string.ra_achievement_unlocked)
                            Text(
                                text = stringResource(R.string.ra_unlocked_trophy_format, statusText),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = GoldAccent,
                                textAlign = TextAlign.Center,
                            )
                        }
                        val hasRawCounts = achievement.numAwarded != null &&
                            achievement.numPlayers != null &&
                            achievement.numPlayers > 0
                        val effectiveRarity = achievement.rarity ?: if (hasRawCounts) {
                            (achievement.numAwarded!!.toDouble() * 100.0) / achievement.numPlayers!!.toDouble()
                        } else null
                        val effectiveRarityHardcore = achievement.rarityHardcore ?: if (hasRawCounts && achievement.numAwardedHardcore != null) {
                            (achievement.numAwardedHardcore.toDouble() * 100.0) / achievement.numPlayers!!.toDouble()
                        } else null

                        if (effectiveRarity != null || hasRawCounts) {
                            val totalFraction = ((effectiveRarity ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                            val hardcoreFraction = ((effectiveRarityHardcore ?: 0.0) / 100.0)
                                .toFloat()
                                .coerceIn(0f, totalFraction)
                            var selectedHint by remember(achievement.id) { mutableStateOf<BarSegmentHint?>(null) }
                            var barWidthPx by remember { mutableStateOf(0) }
                            val fmt = remember { NumberFormat.getIntegerInstance() }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.86f)
                                    .height(20.dp)
                                    .onSizeChanged { barWidthPx = it.width }
                                    .pointerInput(totalFraction, hardcoreFraction) {
                                        detectTapGestures { offset ->
                                            val width = barWidthPx.takeIf { it > 0 } ?: return@detectTapGestures
                                            val tapFraction = (offset.x / width).coerceIn(0f, 1f)
                                            val segment = when {
                                                hardcoreFraction > 0f && tapFraction <= hardcoreFraction -> BarSegmentHint.HARDCORE
                                                totalFraction > hardcoreFraction && tapFraction <= totalFraction -> BarSegmentHint.SOFTCORE
                                                else -> BarSegmentHint.LOCKED
                                            }
                                            selectedHint = if (selectedHint == segment) null else segment
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
                                ) {
                                    if (totalFraction > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(totalFraction)
                                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)),
                                        )
                                    }
                                    if (hardcoreFraction > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(hardcoreFraction)
                                                .background(GoldAccent),
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(visible = selectedHint != null) {
                                val activeHint = selectedHint
                                if (activeHint != null) {
                                    val hardcorePct = (effectiveRarityHardcore ?: 0.0).coerceIn(0.0, 100.0)
                                    val totalPct = (effectiveRarity ?: 0.0).coerceIn(0.0, 100.0)
                                    val softcorePct = (totalPct - hardcorePct).coerceAtLeast(0.0)
                                    val lockedPct = (100.0 - totalPct).coerceAtLeast(0.0)

                                    val (dotColor, hintText) = when (activeHint) {
                                        BarSegmentHint.HARDCORE -> GoldAccent to if (hasRawCounts && achievement.numAwardedHardcore != null) {
                                            stringResource(
                                                R.string.ra_bar_hint_hardcore_counts,
                                                fmt.format(achievement.numAwardedHardcore),
                                                hardcorePct,
                                            )
                                        } else {
                                            stringResource(R.string.ra_bar_hint_hardcore_pct, hardcorePct)
                                        }
                                        BarSegmentHint.SOFTCORE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f) to if (hasRawCounts) {
                                            val softcoreCount = (achievement.numAwarded!! - (achievement.numAwardedHardcore ?: 0)).coerceAtLeast(0)
                                            stringResource(
                                                R.string.ra_bar_hint_softcore_counts,
                                                fmt.format(softcoreCount),
                                                softcorePct,
                                            )
                                        } else {
                                            stringResource(R.string.ra_bar_hint_softcore_pct, softcorePct)
                                        }
                                        BarSegmentHint.LOCKED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f) to if (hasRawCounts) {
                                            val lockedCount = (achievement.numPlayers!! - achievement.numAwarded!!).coerceAtLeast(0)
                                            stringResource(
                                                R.string.ra_bar_hint_locked_counts,
                                                fmt.format(lockedCount),
                                                lockedPct,
                                            )
                                        } else {
                                            stringResource(R.string.ra_bar_hint_locked_pct, lockedPct)
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, dotColor.copy(alpha = 0.45f)),
                                        modifier = Modifier.padding(bottom = 2.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(dotColor),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = hintText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }
                            }

                            if (hasRawCounts) {
                                val totalAwardedStr = fmt.format(achievement.numAwarded!!)
                                val totalPlayersStr = fmt.format(achievement.numPlayers!!)
                                val playersLine = if (achievement.numAwardedHardcore != null) {
                                    stringResource(
                                        R.string.ra_stats_players_hardcore,
                                        totalAwardedStr,
                                        fmt.format(achievement.numAwardedHardcore),
                                        totalPlayersStr,
                                    )
                                } else {
                                    stringResource(
                                        R.string.ra_stats_players_total,
                                        totalAwardedStr,
                                        totalPlayersStr,
                                    )
                                }
                                Text(
                                    text = playersLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = stringResource(R.string.ra_stats_unlock_rate, effectiveRarity ?: 0.0),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            } else if (rarityText != null) {
                                Text(
                                    text = rarityText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                uriHandler.openUri("https://retroachievements.org/achievement/${achievement.id}")
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.ra_view_on_web))
                    }

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(text = stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsListBottomSheet(
    data: GameAchievementsData,
    onSelectAchievement: (AchievementDetail) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var filterMode by remember { mutableStateOf(0) }
    var sortOrder by remember { mutableStateOf(AchievementSortOrder.UNLOCKED_FIRST) }

    val filteredList = remember(data.achievements, filterMode, sortOrder) {
        val base = when (filterMode) {
            1 -> data.achievements.filter { it.isUnlocked }
            2 -> data.achievements.filter { !it.isUnlocked }
            else -> data.achievements
        }
        sortAchievements(base, sortOrder)
    }

    LaunchedEffect(filterMode, sortOrder) {
        listState.scrollToItem(0)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            if (sheetState.currentValue == SheetValue.Expanded) {
                                sheetState.partialExpand()
                            } else {
                                sheetState.expand()
                            }
                        }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .width(48.dp)
                        .height(5.dp),
                    shape = RoundedCornerShape(2.5.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                ) {}
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
    
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    tint = GoldAccent,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ra_game_details_header),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }


            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item(key = "filter_all") {
                    FilterChip(
                        selected = filterMode == 0,
                        onClick = { filterMode = 0 },
                        label = { Text(stringResource(R.string.ra_game_details_filter_all, data.totalCount)) },
                    )
                }
                item(key = "filter_unlocked") {
                    FilterChip(
                        selected = filterMode == 1,
                        onClick = { filterMode = 1 },
                        label = { Text(stringResource(R.string.ra_game_details_filter_unlocked, data.unlockedCount)) },
                    )
                }
                item(key = "filter_locked") {
                    FilterChip(
                        selected = filterMode == 2,
                        onClick = { filterMode = 2 },
                        label = { Text(stringResource(R.string.ra_game_details_filter_locked, data.totalCount - data.unlockedCount)) },
                    )
                }
                item(key = "sort_divider") {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                items(AchievementSortOrder.entries, key = { it.name }) { order ->
                    FilterChip(
                        selected = sortOrder == order,
                        onClick = { sortOrder = order },
                        leadingIcon = {
                            Icon(
                                imageVector = order.icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = { Text(stringResource(order.labelResId)) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(filteredList, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAchievement(item) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AchievementBadgeThumbnail(
                                badgeUrl = item.badgeUrl,
                                title = item.title,
                                isUnlocked = item.isUnlocked,
                                size = 46.dp,
                                onClick = { onSelectAchievement(item) },
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.isUnlocked) GoldAccent else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = GoldAccent.copy(alpha = 0.18f),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.ra_game_details_points_badge, item.points),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = GoldAccent,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(3.dp))

                                Text(
                                    text = item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                if (item.isUnlocked && !item.unlockDate.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.ra_game_details_unlocked_on, item.unlockDate),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GoldAccent.copy(alpha = 0.85f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
