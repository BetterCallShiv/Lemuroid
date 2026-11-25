package com.swordfish.lemuroid.app.shared.retroachievements

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AchievementPopupOverlay(modifier: Modifier = Modifier) {
    val bannerEvent by RetroAchievementsManager.bannerFlow.collectAsState()
    var currentBanner by remember { mutableStateOf<AchievementBannerNotification?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    var bannerSize by remember { mutableStateOf(IntSize.Zero) }

    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    LaunchedEffect(bannerEvent) {
        bannerEvent?.let { banner ->
            offsetX.snapTo(0f)
            offsetY.snapTo(0f)
            isDragging = false
            isDismissing = false
            currentBanner = banner
            isVisible = true

            var elapsedMs = 0L
            while (elapsedMs < 4500L && isVisible && !isDismissing) {
                delay(100L)
                if (!isDragging && !isDismissing) {
                    elapsedMs += 100L
                }
            }
            if (!isDismissing && isVisible) {
                isVisible = false
                RetroAchievementsManager.dismissBanner(banner)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars))
            .padding(top = 12.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = isVisible && currentBanner != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        ) {
            currentBanner?.let { banner ->
                val dismissThresholdX = with(density) { 72.dp.toPx() }
                val dismissThresholdY = with(density) { 28.dp.toPx() }
                val maxDownRubberBandPx = with(density) { 12.dp.toPx() }

                val progressX = (abs(offsetX.value) / dismissThresholdX.coerceAtLeast(1f)).coerceIn(0f, 1f)
                val progressY = ((-offsetY.value) / dismissThresholdY.coerceAtLeast(1f)).coerceIn(0f, 1f)
                val dragAlpha = (1f - maxOf(progressX, progressY) * 0.7f).coerceIn(0.05f, 1f)

                Box(
                    modifier = Modifier
                        .onSizeChanged { bannerSize = it }
                        .pointerInput(banner) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val velocityTracker = VelocityTracker().apply {
                                    addPosition(down.uptimeMillis, down.position)
                                }
                                var lockHorizontal: Boolean? = null
                                var didDrag = false

                                val dragStart =
                                    awaitTouchSlopOrCancellation(down.id) { change, over ->
                                        change.consume()
                                        didDrag = true
                                        isDragging = true
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        lockHorizontal = abs(over.x) > abs(over.y)
                                        scope.launch {
                                            if (lockHorizontal == true) {
                                                offsetX.snapTo(offsetX.value + over.x)
                                            } else {
                                                val targetY = (offsetY.value + over.y).coerceAtMost(maxDownRubberBandPx)
                                                offsetY.snapTo(if (targetY > 0f) targetY * 0.35f else targetY)
                                            }
                                        }
                                    }

                                if (dragStart != null || didDrag) {
                                    val trackId = (dragStart ?: down).id
                                    try {
                                        drag(trackId) { change ->
                                            val delta = change.positionChange()
                                            if (delta != androidx.compose.ui.geometry.Offset.Zero) {
                                                change.consume()
                                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                                scope.launch {
                                                    if (lockHorizontal == true) {
                                                        offsetX.snapTo(offsetX.value + delta.x)
                                                    } else {
                                                        val rawY = offsetY.value + delta.y
                                                        val clampedY =
                                                            if (rawY > 0f) {
                                                                (rawY * 0.35f).coerceAtMost(maxDownRubberBandPx)
                                                            } else {
                                                                rawY
                                                            }
                                                        offsetY.snapTo(clampedY)
                                                    }
                                                }
                                            }
                                        }
                                    } finally {
                                        isDragging = false
                                    }

                                    val velocity = velocityTracker.calculateVelocity()
                                    val curX = offsetX.value
                                    val curY = offsetY.value
                                    val widthPx = bannerSize.width.toFloat().coerceAtLeast(dismissThresholdX * 3f)
                                    val heightPx = bannerSize.height.toFloat().coerceAtLeast(dismissThresholdY * 3f)

                                    val flingVelocityThreshold = 550f
                                    val shouldDismissHorizontal =
                                        lockHorizontal == true &&
                                            (abs(curX) >= dismissThresholdX || abs(velocity.x) >= flingVelocityThreshold)
                                    val shouldDismissUp =
                                        lockHorizontal != true &&
                                            (curY <= -dismissThresholdY || velocity.y <= -flingVelocityThreshold)

                                    if (shouldDismissHorizontal) {
                                        isDismissing = true
                                        val dir = if (curX != 0f) (if (curX > 0f) 1f else -1f) else (if (velocity.x >= 0f) 1f else -1f)
                                        scope.launch {
                                            offsetX.animateTo(dir * widthPx * 1.25f, tween(160))
                                            isVisible = false
                                            RetroAchievementsManager.dismissBanner(banner)
                                        }
                                    } else if (shouldDismissUp) {
                                        isDismissing = true
                                        scope.launch {
                                            offsetY.animateTo(-heightPx * 1.5f, tween(160))
                                            isVisible = false
                                            RetroAchievementsManager.dismissBanner(banner)
                                        }
                                    } else {
                                        scope.launch {
                                            launch { offsetX.animateTo(0f, spring()) }
                                            launch { offsetY.animateTo(0f, spring()) }
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .offset {
                                IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt())
                            }
                            .graphicsLayer { alpha = dragAlpha }
                            .shadow(8.dp, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xB8121216))
                            .border(1.2.dp, Color(0xFFFFD700).copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    if (banner.badgeUrl.isNotEmpty()) {
                        AsyncImage(
                            model = banner.badgeUrl,
                            contentDescription = banner.title,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFFFFD700), RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val categoryText = if (banner.categoryResId != 0) stringResource(id = banner.categoryResId) else banner.category
                        val hasCategory = categoryText.isNotEmpty()
                        if (hasCategory) {
                            Text(
                                text = categoryText,
                                color = Color(0xFFFFD700),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp,
                            )
                        }
                        Text(
                            text = banner.title,
                            color = if (hasCategory) Color.White else Color(0xFFFFD700),
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (banner.subtitle.isNotEmpty()) {
                            Text(
                                text = banner.subtitle,
                                color = if (hasCategory) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (banner.badgeLabel.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFFD700).copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = banner.badgeLabel,
                                color = Color(0xFFFFD700),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                }
            }
        }
    }
}
