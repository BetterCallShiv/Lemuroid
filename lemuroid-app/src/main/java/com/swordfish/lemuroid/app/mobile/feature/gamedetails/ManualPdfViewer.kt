package com.swordfish.lemuroid.app.mobile.feature.gamedetails

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.scraper.ScreenScraperManualEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

fun formatManualRegionLabel(region: String): String {
    return when (region.trim().lowercase(Locale.ROOT)) {
        "us" -> "English (US)"
        "eu" -> "English / Multi (EU)"
        "uk", "gb" -> "English (UK)"
        "wor" -> "English / World"
        "au" -> "English (AU)"
        "jp" -> "Japanese (JP)"
        "fr" -> "French (FR)"
        "de" -> "German (DE)"
        "sp", "es" -> "Spanish (ES)"
        "br" -> "Portuguese (BR)"
        "pt" -> "Portuguese (PT)"
        "it" -> "Italian (IT)"
        "nl" -> "Dutch (NL)"
        else -> region.uppercase(Locale.ROOT)
    }
}

fun resolvePreferredManualEntry(
    entries: List<ScreenScraperManualEntry>,
    preferredLanguage: String,
): ScreenScraperManualEntry? {
    if (entries.isEmpty()) return null
    if (entries.size == 1) return entries.first()
    val candidates = when (preferredLanguage.lowercase(Locale.ROOT)) {
        "ask" -> return null
        "auto_en" -> listOf("us", "wor", "eu", "uk", "gb", "au")
        "sp", "es" -> listOf("sp", "es")
        "br", "pt" -> listOf("br", "pt")
        else -> listOf(preferredLanguage.lowercase(Locale.ROOT))
    }
    for (region in candidates) {
        entries.firstOrNull { it.region.equals(region, ignoreCase = true) }?.let { return it }
    }
    return null
}

@Composable
fun ManualLanguagePickerDialog(
    entries: List<ScreenScraperManualEntry>,
    onSelect: (ScreenScraperManualEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.game_details_manual_choose_language)) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries, key = { "${it.region}_${it.url}" }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(entry) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = formatManualRegionLabel(entry.region),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

private class PdfRenderSession(
    val pfd: ParcelFileDescriptor,
    val renderer: PdfRenderer,
    val mutex: Mutex = Mutex(),
) {
    fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}

@Composable
fun ManualPdfViewerDialog(
    file: File,
    currentEntry: ScreenScraperManualEntry,
    availableManuals: List<ScreenScraperManualEntry>,
    onSwitchManual: (ScreenScraperManualEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var session by remember(file.absolutePath) { mutableStateOf<PdfRenderSession?>(null) }
    var pageCount by remember(file.absolutePath) { mutableIntStateOf(0) }
    var openError by remember(file.absolutePath) { mutableStateOf(false) }
    val renderedPages = remember(file.absolutePath) { mutableStateMapOf<Int, ImageBitmap>() }
    var showLanguageMenu by remember { mutableStateOf(false) }

    DisposableEffect(file.absolutePath) {
        val opened = runCatching {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            PdfRenderSession(pfd = pfd, renderer = renderer)
        }.getOrNull()

        if (opened != null && opened.renderer.pageCount > 0) {
            session = opened
            pageCount = opened.renderer.pageCount
            openError = false
        } else {
            opened?.close()
            session = null
            pageCount = 0
            openError = true
        }

        onDispose {
            renderedPages.clear()
            opened?.close()
        }
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
            if (openError) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.game_details_manual_error),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.close))
                    }
                }
            } else if (session != null && pageCount > 0) {
                val activeSession = session!!
                val pagerState = rememberPagerState(pageCount = { pageCount })
                var currentPageZoomed by remember { mutableStateOf(false) }

                LaunchedEffect(pagerState.currentPage) {
                    currentPageZoomed = false
                    val keysToRemove = renderedPages.keys.filter { kotlin.math.abs(it - pagerState.currentPage) > 2 }
                    keysToRemove.forEach { renderedPages.remove(it) }
                }

                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !currentPageZoomed,
                    modifier = Modifier.fillMaxSize(),
                ) { pageIndex ->
                    var pageBitmap by remember(file.absolutePath, pageIndex) {
                        mutableStateOf(renderedPages[pageIndex])
                    }

                    LaunchedEffect(file.absolutePath, pageIndex) {
                        if (pageBitmap == null) {
                            val bmp = renderPdfPageBitmap(activeSession, pageIndex, targetWidthPx = 2560)
                            if (bmp != null) {
                                val img = bmp.asImageBitmap()
                                renderedPages[pageIndex] = img
                                pageBitmap = img
                            }
                        }
                    }

                    var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
                    var offset by remember(pageIndex) { mutableStateOf(Offset.Zero) }
                    var containerSize by remember(pageIndex) { mutableStateOf(IntSize.Zero) }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { containerSize = it }
                            .pointerInput(pageIndex) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (scale > 1.05f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                            currentPageZoomed = false
                                        } else {
                                            scale = 2.5f
                                            currentPageZoomed = true
                                        }
                                    },
                                )
                            }
                            .pointerInput(pageIndex) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressedCount = event.changes.count { it.pressed }
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()

                                        if (pressedCount >= 2 || scale > 1.02f) {
                                            val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
                                            scale = nextScale
                                            if (nextScale <= 1.02f) {
                                                scale = 1f
                                                offset = Offset.Zero
                                                currentPageZoomed = false
                                            } else {
                                                currentPageZoomed = true
                                                val maxX = ((containerSize.width * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                                                val maxY = ((containerSize.height * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                                                val rawOffset = offset + panChange
                                                offset = Offset(
                                                    x = rawOffset.x.coerceIn(-maxX, maxX),
                                                    y = rawOffset.y.coerceIn(-maxY, maxY),
                                                )
                                            }
                                            if (pressedCount >= 2 || zoomChange != 1f || scale > 1.02f) {
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val img = pageBitmap
                        if (img != null) {
                            Image(
                                bitmap = img,
                                contentDescription = stringResource(
                                    R.string.game_details_manual_page_counter,
                                    pageIndex + 1,
                                    pageCount,
                                ),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 4.dp, vertical = 56.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offset.x
                                        translationY = offset.y
                                    },
                            )
                        } else {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 18.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.game_details_manual_page_counter,
                            pagerState.currentPage + 1,
                            pageCount,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            } else {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = "${stringResource(R.string.game_details_manual)} • ${formatManualRegionLabel(currentEntry.region)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (availableManuals.size > 1) {
                    Box {
                        TextButton(onClick = { showLanguageMenu = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Language,
                                contentDescription = stringResource(R.string.settings_title_manual_language),
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = currentEntry.region.uppercase(Locale.ROOT),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                        DropdownMenu(
                            expanded = showLanguageMenu,
                            onDismissRequest = { showLanguageMenu = false },
                        ) {
                            availableManuals.forEach { entry ->
                                val selected = entry.region.equals(currentEntry.region, ignoreCase = true)
                                DropdownMenuItem(
                                    text = {
                                        Text(text = formatManualRegionLabel(entry.region))
                                    },
                                    onClick = {
                                        showLanguageMenu = false
                                        if (!selected) {
                                            onSwitchManual(entry)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
    }
}

private suspend fun renderPdfPageBitmap(
    session: PdfRenderSession,
    pageIndex: Int,
    targetWidthPx: Int,
): Bitmap? = withContext(Dispatchers.IO) {
    session.mutex.withLock {
        runCatching {
            session.renderer.openPage(pageIndex).use { page ->
                val aspectRatio = page.height.toFloat() / page.width.coerceAtLeast(1).toFloat()
                val desiredWidth = maxOf(targetWidthPx, page.width * 4).coerceAtMost(3840)
                var renderWidth = desiredWidth.coerceAtLeast(1440)
                var renderHeight = (renderWidth * aspectRatio).toInt()
                if (renderHeight > 4096) {
                    renderHeight = 4096
                    renderWidth = (renderHeight / aspectRatio).toInt().coerceAtLeast(1)
                }
                val bitmap = Bitmap.createBitmap(renderWidth, renderHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }.getOrNull()
    }
}
