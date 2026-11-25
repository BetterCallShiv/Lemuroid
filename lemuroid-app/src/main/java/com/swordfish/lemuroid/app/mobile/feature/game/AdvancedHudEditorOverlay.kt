package com.swordfish.lemuroid.app.mobile.feature.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.touchinput.controller.R
import com.swordfish.touchinput.radial.layouts.resolveHudControlBounds
import com.swordfish.touchinput.radial.settings.HudControlSpec
import com.swordfish.touchinput.radial.settings.TouchControllerID
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.settings.getHudControlSpecs
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt
import com.swordfish.lemuroid.R as AppR

private const val HUD_GRID_STEP_DP = 20f

private sealed interface HudSelection {
    data class Control(val spec: HudControlSpec) : HudSelection

    data object GameScreen : HudSelection
}

private sealed interface HudDragTarget {
    data class Control(val spec: HudControlSpec) : HudDragTarget

    data object GameScreenMove : HudDragTarget

    data object GameScreenResize : HudDragTarget

    fun toSelection(): HudSelection =
        when (this) {
            is Control -> HudSelection.Control(spec)
            GameScreenMove, GameScreenResize -> HudSelection.GameScreen
        }
}

fun buildBaselineCustomSettings(
    baseSettings: TouchControllerSettingsManager.Settings,
    controllerId: TouchControllerID,
    measuredControlBounds: Map<String, Rect>,
    fullScreenRect: Rect?,
    viewportRect: Rect?,
    density: Density,
    isLandscape: Boolean = false,
    allowTouchOverlay: Boolean = true,
): TouchControllerSettingsManager.Settings {
    val specs = getHudControlSpecs(controllerId, baseSettings)
    val safeFull = fullScreenRect?.takeIf { it.width > 1f && it.height > 1f }

    val fallbackViewport =
        when {
            !isLandscape -> TouchControllerSettingsManager.HudViewportRect(0f, 0f, 1f, 0.55f)
            allowTouchOverlay -> TouchControllerSettingsManager.HudViewportRect(0f, 0f, 1f, 1f)
            else -> TouchControllerSettingsManager.HudViewportRect(0.22f, 0f, 0.56f, 1f)
        }

    val initialViewport =
        if (safeFull != null && viewportRect != null && viewportRect.width > 1f && viewportRect.height > 1f) {
            val w = (viewportRect.width / safeFull.width).coerceIn(TouchControllerSettingsManager.MIN_VIEWPORT_WIDTH, 1f)
            val h = (viewportRect.height / safeFull.height).coerceIn(TouchControllerSettingsManager.MIN_VIEWPORT_HEIGHT, 1f)
            val x = ((viewportRect.left - safeFull.left) / safeFull.width).coerceIn(0f, 1f - w)
            val y = ((viewportRect.top - safeFull.top) / safeFull.height).coerceIn(0f, 1f - h)
            TouchControllerSettingsManager.HudViewportRect(x, y, w, h)
        } else {
            fallbackViewport
        }

    val initialOverrides =
        specs.associate { spec ->
            val measured = measuredControlBounds[spec.id]
            val override =
                if (safeFull != null && measured != null && measured.width > 1f && measured.height > 1f) {
                    val measuredWidthDp = measured.width / density.density
                    val scale =
                        (measuredWidthDp / spec.baseSizeDp).coerceIn(
                            TouchControllerSettingsManager.MIN_CUSTOM_CONTROL_SCALE,
                            TouchControllerSettingsManager.MAX_CUSTOM_CONTROL_SCALE,
                        )
                    TouchControllerSettingsManager.HudControlOverride(
                        xFraction = ((measured.center.x - safeFull.left) / safeFull.width).coerceIn(0.04f, 0.96f),
                        yFraction = ((measured.center.y - safeFull.top) / safeFull.height).coerceIn(0.04f, 0.96f),
                        scale = scale,
                        visible = true,
                    )
                } else {
                    TouchControllerSettingsManager.HudControlOverride(
                        xFraction = spec.defaultXFraction,
                        yFraction = spec.defaultYFraction,
                        scale = 1.0f,
                        visible = true,
                    )
                }
            spec.id to override
        }

    return baseSettings.copy(
        customHudEnabled = true,
        customViewport = initialViewport,
        controlOverrides = initialOverrides,
    )
}

fun mergeMissingHudOverrides(
    current: TouchControllerSettingsManager.Settings,
    baseline: TouchControllerSettingsManager.Settings,
): TouchControllerSettingsManager.Settings {
    val mergedControls =
        baseline.controlOverrides.toMutableMap().apply {
            current.controlOverrides.forEach { (id, ov) ->
                put(id, ov)
            }
        }
    return current.copy(
        customHudEnabled = true,
        customViewport = current.customViewport ?: baseline.customViewport,
        controlOverrides = mergedControls,
    )
}

@Composable
fun AdvancedHudEditorOverlay(
    controllerId: TouchControllerID,
    draft: TouchControllerSettingsManager.Settings,
    baseline: TouchControllerSettingsManager.Settings,
    showHiddenInEditor: Boolean,
    onToggleShowHidden: () -> Unit,
    onDraftChange: (TouchControllerSettingsManager.Settings) -> Unit,
    onResetAll: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    BackHandler { onCancel() }

    val specs =
        remember(controllerId, draft.rotation) {
            getHudControlSpecs(controllerId, draft)
        }
    val context = LocalContext.current
    val sharedPrefs = remember(context) { SharedPreferencesHelper.getSharedPreferences(context) }
    val showGridKey = stringResource(AppR.string.pref_key_hud_editor_show_grid)
    val snapToGridKey = stringResource(AppR.string.pref_key_hud_editor_snap_to_grid)

    var showGrid by remember { mutableStateOf(sharedPrefs.getBoolean(showGridKey, false)) }
    var snapToGrid by remember { mutableStateOf(sharedPrefs.getBoolean(snapToGridKey, false)) }

    var selection by remember { mutableStateOf<HudSelection?>(null) }
    var dockOverride by remember(selection) { mutableStateOf<Boolean?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var activeDragControlCenterPx by remember { mutableStateOf<Offset?>(null) }

    val latestDraft by rememberUpdatedState(draft)
    val latestBaseline by rememberUpdatedState(baseline)
    val latestSpecs by rememberUpdatedState(specs)
    val latestShowHidden by rememberUpdatedState(showHiddenInEditor)
    val latestSnapToGrid by rememberUpdatedState(snapToGrid)
    val latestOnDraftChange by rememberUpdatedState(onDraftChange)
    val density = LocalDensity.current
    val gridAccentColor = MaterialTheme.colorScheme.primary

    val activeSelection = selection
    val selectedControlSpec = (activeSelection as? HudSelection.Control)?.spec
    val selectedCenterY =
        when (activeSelection) {
            is HudSelection.Control -> {
                val ov = draft.controlOverrides[activeSelection.spec.id]
                ov?.yFraction ?: activeSelection.spec.defaultYFraction
            }
            is HudSelection.GameScreen -> {
                val vp = draft.customViewport ?: baseline.customViewport
                if (vp != null) vp.yFraction + vp.heightFraction / 2f else 0.3f
            }
            null -> 0.5f
        }
    val autoDockAtTop =
        when (activeSelection) {
            null -> {
                val vp = draft.customViewport ?: baseline.customViewport
                (vp?.heightFraction ?: 0.55f) > 0.72f
            }
            else -> selectedCenterY > 0.52f
        }
    val dockInspectorAtTop = dockOverride ?: autoDockAtTop

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f))
                    .pointerInput(widthPx, heightPx, density.density) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val target =
                                hitTestHudTarget(
                                    position = down.position,
                                    specs = latestSpecs,
                                    draft = latestDraft,
                                    baseline = latestBaseline,
                                    showHiddenInEditor = latestShowHidden,
                                    canvasWidthPx = widthPx,
                                    canvasHeightPx = heightPx,
                                    densityScale = density.density,
                                )
                            if (target == null) {
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    selection = null
                                }
                                return@awaitEachGesture
                            }

                            down.consume()
                            selection = target.toSelection()

                            var rawAnchor =
                                initRawDragAnchor(
                                    target = target,
                                    draft = latestDraft,
                                    baseline = latestBaseline,
                                    canvasWidthPx = widthPx,
                                    canvasHeightPx = heightPx,
                                    densityScale = density.density,
                                )

                            try {
                                var dragStarted = false
                                val dragChange =
                                    awaitTouchSlopOrCancellation(down.id) { change, over ->
                                        change.consume()
                                        dragStarted = true
                                        rawAnchor =
                                            clampRawDragAnchor(
                                                target = target,
                                                raw = rawAnchor + over,
                                                draft = latestDraft,
                                                baseline = latestBaseline,
                                                canvasWidthPx = widthPx,
                                                canvasHeightPx = heightPx,
                                                densityScale = density.density,
                                            )
                                        val controlCenter =
                                            applyHudDragAtRawPosition(
                                                target = target,
                                                rawAnchor = rawAnchor,
                                                snapToGrid = latestSnapToGrid,
                                                draft = latestDraft,
                                                baseline = latestBaseline,
                                                canvasWidthPx = widthPx,
                                                canvasHeightPx = heightPx,
                                                densityScale = density.density,
                                                onDraftChange = latestOnDraftChange,
                                            )
                                        activeDragControlCenterPx = controlCenter
                                    }
                                if (dragChange != null || dragStarted) {
                                    val trackId = (dragChange ?: down).id
                                    drag(trackId) { change ->
                                        val delta = change.positionChange()
                                        if (delta != Offset.Zero) {
                                            change.consume()
                                            rawAnchor =
                                                clampRawDragAnchor(
                                                    target = target,
                                                    raw = rawAnchor + delta,
                                                    draft = latestDraft,
                                                    baseline = latestBaseline,
                                                    canvasWidthPx = widthPx,
                                                    canvasHeightPx = heightPx,
                                                    densityScale = density.density,
                                                )
                                            val controlCenter =
                                                applyHudDragAtRawPosition(
                                                    target = target,
                                                    rawAnchor = rawAnchor,
                                                    snapToGrid = latestSnapToGrid,
                                                    draft = latestDraft,
                                                    baseline = latestBaseline,
                                                    canvasWidthPx = widthPx,
                                                    canvasHeightPx = heightPx,
                                                    densityScale = density.density,
                                                    onDraftChange = latestOnDraftChange,
                                                )
                                            activeDragControlCenterPx = controlCenter
                                        }
                                    }
                                }
                            } finally {
                                activeDragControlCenterPx = null
                            }
                        }
                    },
        )

        val draggingCenter = activeDragControlCenterPx
        if (showGrid || (snapToGrid && draggingCenter != null)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val stepPx = HUD_GRID_STEP_DP * density.density

                if (showGrid && stepPx > 1f) {
                    val minorColor = Color.White.copy(alpha = 0.14f)
                    val majorColor = Color.White.copy(alpha = 0.28f)
                    val axisColor = gridAccentColor.copy(alpha = 0.58f)
                    val minorStroke = 0.8.dp.toPx()
                    val majorStroke = 1.1.dp.toPx()
                    val axisStroke = 1.6.dp.toPx()

                    var k = 1
                    while (centerX - k * stepPx >= 0f || centerX + k * stepPx <= size.width) {
                        val isMajor = (k % 2 == 0)
                        val color = if (isMajor) majorColor else minorColor
                        val stroke = if (isMajor) majorStroke else minorStroke
                        val leftX = centerX - k * stepPx
                        val rightX = centerX + k * stepPx
                        if (leftX >= 0f) {
                            drawLine(color = color, start = Offset(leftX, 0f), end = Offset(leftX, size.height), strokeWidth = stroke)
                        }
                        if (rightX <= size.width) {
                            drawLine(color = color, start = Offset(rightX, 0f), end = Offset(rightX, size.height), strokeWidth = stroke)
                        }
                        k++
                    }

                    k = 1
                    while (centerY - k * stepPx >= 0f || centerY + k * stepPx <= size.height) {
                        val isMajor = (k % 2 == 0)
                        val color = if (isMajor) majorColor else minorColor
                        val stroke = if (isMajor) majorStroke else minorStroke
                        val topY = centerY - k * stepPx
                        val bottomY = centerY + k * stepPx
                        if (topY >= 0f) {
                            drawLine(color = color, start = Offset(0f, topY), end = Offset(size.width, topY), strokeWidth = stroke)
                        }
                        if (bottomY <= size.height) {
                            drawLine(color = color, start = Offset(0f, bottomY), end = Offset(size.width, bottomY), strokeWidth = stroke)
                        }
                        k++
                    }

                    drawLine(color = axisColor, start = Offset(centerX, 0f), end = Offset(centerX, size.height), strokeWidth = axisStroke)
                    drawLine(color = axisColor, start = Offset(0f, centerY), end = Offset(size.width, centerY), strokeWidth = axisStroke)
                }

                if (draggingCenter != null) {
                    val activeLineColor = gridAccentColor.copy(alpha = 0.65f)
                    val mirrorLineColor = Color(0xFFFFD54F).copy(alpha = 0.45f)
                    val guideStroke = 1.2.dp.toPx()

                    drawLine(
                        color = activeLineColor,
                        start = Offset(draggingCenter.x, 0f),
                        end = Offset(draggingCenter.x, size.height),
                        strokeWidth = guideStroke,
                    )
                    drawLine(
                        color = activeLineColor,
                        start = Offset(0f, draggingCenter.y),
                        end = Offset(size.width, draggingCenter.y),
                        strokeWidth = guideStroke,
                    )
                    val mirrorX = size.width - draggingCenter.x
                    if (abs(mirrorX - draggingCenter.x) > 4.dp.toPx()) {
                        drawLine(
                            color = mirrorLineColor,
                            start = Offset(mirrorX, 0f),
                            end = Offset(mirrorX, size.height),
                            strokeWidth = guideStroke,
                        )
                    }
                }
            }
        }

        val viewport =
            draft.customViewport ?: baseline.customViewport
                ?: TouchControllerSettingsManager.HudViewportRect(0f, 0f, 1f, 0.6f)
        val vpLeftPx = (viewport.xFraction * widthPx).roundToInt()
        val vpTopPx = (viewport.yFraction * heightPx).roundToInt()
        val vpWidthDp = with(density) { (viewport.widthFraction * widthPx).toDp() }
        val vpHeightDp = with(density) { (viewport.heightFraction * heightPx).toDp() }
        val isScreenSelected = activeSelection is HudSelection.GameScreen

        Box(
            modifier =
                Modifier
                    .offset { IntOffset(vpLeftPx, vpTopPx) }
                    .size(vpWidthDp, vpHeightDp)
                    .border(
                        width = if (isScreenSelected) 2.5.dp else 1.5.dp,
                        color = if (isScreenSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(4.dp),
                    ),
        ) {

            Surface(
                color = if (isScreenSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.72f),
                contentColor = if (isScreenSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
                shape = RoundedCornerShape(bottomEnd = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.touch_hud_editor_game_screen),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (isScreenSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Black.copy(alpha = 0.75f)
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = stringResource(R.string.touch_hud_editor_size),
                    tint = if (isScreenSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        specs.forEach { spec ->
            key(spec.id) {
                val currentOverride = draft.controlOverrides[spec.id]
                val resolved =
                    resolveHudControlBounds(
                        spec = spec,
                        override = currentOverride,
                        canvasWidthPx = widthPx,
                        canvasHeightPx = heightPx,
                        densityScale = density.density,
                    )

                if (resolved.visible || showHiddenInEditor) {
                    val isControlSelected = selectedControlSpec?.id == spec.id
                    val borderColor =
                        when {
                            isControlSelected -> MaterialTheme.colorScheme.primary
                            !resolved.visible -> Color(0xFFFFB74D).copy(alpha = 0.85f)
                            else -> Color.White.copy(alpha = 0.45f)
                        }

                    Box(
                        modifier =
                            Modifier
                                .offset {
                                    IntOffset(
                                        resolved.leftPx.roundToInt(),
                                        resolved.topPx.roundToInt(),
                                    )
                                }
                                .size(resolved.sizeDp)
                                .border(
                                    width = if (isControlSelected) 2.5.dp else 1.dp,
                                    color = borderColor,
                                    shape = RoundedCornerShape(12.dp),
                                ),
                    ) {
                        if (!resolved.visible) {
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.78f))
                                        .padding(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB74D),
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.displayCutout
                            .union(WindowInsets.navigationBars)
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = if (dockInspectorAtTop) Alignment.TopCenter else Alignment.BottomCenter,
        ) {
            HudInspectorBar(
                selection = activeSelection,
                draft = draft,
                baseline = baseline,
                showHiddenInEditor = showHiddenInEditor,
                showGrid = showGrid,
                snapToGrid = snapToGrid,
                onToggleDock = { dockOverride = !dockInspectorAtTop },
                onToggleGrid = {
                    val next = !showGrid
                    showGrid = next
                    sharedPrefs.edit().putBoolean(showGridKey, next).apply()
                },
                onToggleSnapToGrid = {
                    val next = !snapToGrid
                    snapToGrid = next
                    sharedPrefs.edit().putBoolean(snapToGridKey, next).apply()
                },
                onToggleShowHidden = onToggleShowHidden,
                onDraftChange = onDraftChange,
                onRequestResetAll = { showResetConfirm = true },
                onCancel = onCancel,
                onSave = onSave,
            )
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.touch_hud_editor_reset_confirm_title)) },
            text = { Text(stringResource(R.string.touch_hud_editor_reset_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        selection = null
                        onResetAll()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.touch_hud_editor_reset_all),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.touch_hud_editor_cancel))
                }
            },
        )
    }
}

@Composable
private fun HudInspectorBar(
    selection: HudSelection?,
    draft: TouchControllerSettingsManager.Settings,
    baseline: TouchControllerSettingsManager.Settings,
    showHiddenInEditor: Boolean,
    showGrid: Boolean,
    snapToGrid: Boolean,
    onToggleDock: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleSnapToGrid: () -> Unit,
    onToggleShowHidden: () -> Unit,
    onDraftChange: (TouchControllerSettingsManager.Settings) -> Unit,
    onRequestResetAll: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 8.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val titleText =
                    when (selection) {
                        is HudSelection.Control -> selection.spec.label
                        is HudSelection.GameScreen -> stringResource(R.string.touch_hud_editor_game_screen)
                        null -> stringResource(R.string.touch_hud_editor_hint)
                    }

                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                IconButton(
                    onClick = onToggleGrid,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = if (showGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                        contentDescription = stringResource(R.string.touch_hud_editor_grid_toggle),
                        tint = if (showGrid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color =
                        if (snapToGrid) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    contentColor =
                        if (snapToGrid) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    border =
                        BorderStroke(
                            width = 1.dp,
                            color =
                                if (snapToGrid) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                },
                        ),
                    modifier =
                        Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onToggleSnapToGrid),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (snapToGrid) Icons.Default.Grid4x4 else Icons.Default.OpenWith,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text =
                                stringResource(
                                    if (snapToGrid) {
                                        R.string.touch_hud_editor_snap_mode
                                    } else {
                                        R.string.touch_hud_editor_free_mode
                                    },
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                IconButton(
                    onClick = onToggleDock,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = stringResource(R.string.touch_hud_editor_dock_toggle),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }

                IconButton(
                    onClick = onToggleShowHidden,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = if (showHiddenInEditor) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = stringResource(R.string.touch_hud_editor_show_hidden),
                        tint = if (showHiddenInEditor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (selection is HudSelection.Control) {
                val spec = selection.spec
                val currentOverride =
                    draft.controlOverrides[spec.id]
                        ?: baseline.controlOverrides[spec.id]
                        ?: TouchControllerSettingsManager.HudControlOverride(
                            xFraction = spec.defaultXFraction,
                            yFraction = spec.defaultYFraction,
                            scale = 1.0f,
                            visible = true,
                        )
                val isVisible = if (spec.canHide) currentOverride.visible else true

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${stringResource(R.string.touch_hud_editor_size)} ${String.format(Locale.US, "%.1fx", currentOverride.scale)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = currentOverride.scale,
                        onValueChange = { newScale ->
                            val updated = draft.controlOverrides.toMutableMap()
                            updated[spec.id] = currentOverride.copy(scale = newScale)
                            onDraftChange(draft.copy(controlOverrides = updated))
                        },
                        valueRange =
                            TouchControllerSettingsManager.MIN_CUSTOM_CONTROL_SCALE..
                                TouchControllerSettingsManager.MAX_CUSTOM_CONTROL_SCALE,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = spec.canHide,
                        onClick = {
                            if (spec.canHide) {
                                val updated = draft.controlOverrides.toMutableMap()
                                updated[spec.id] = currentOverride.copy(visible = !isVisible)
                                onDraftChange(draft.copy(controlOverrides = updated))
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector =
                                when {
                                    !spec.canHide -> Icons.Default.Lock
                                    isVisible -> Icons.Default.Visibility
                                    else -> Icons.Default.VisibilityOff
                                },
                            contentDescription = null,
                            tint =
                                when {
                                    !spec.canHide -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    isVisible -> MaterialTheme.colorScheme.primary
                                    else -> Color(0xFFFFB74D)
                                },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            val baseOverride =
                                baseline.controlOverrides[spec.id]
                                    ?: TouchControllerSettingsManager.HudControlOverride(
                                        xFraction = spec.defaultXFraction,
                                        yFraction = spec.defaultYFraction,
                                        scale = 1.0f,
                                        visible = true,
                                    )
                            val updated = draft.controlOverrides.toMutableMap()
                            updated[spec.id] = baseOverride
                            onDraftChange(draft.copy(controlOverrides = updated))
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.RotateLeft,
                            contentDescription = stringResource(R.string.touch_hud_editor_reset_item),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (selection is HudSelection.GameScreen) {
                val viewport =
                    draft.customViewport ?: baseline.customViewport
                        ?: TouchControllerSettingsManager.HudViewportRect(0f, 0f, 1f, 0.55f)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.touch_hud_editor_width),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(46.dp),
                    )
                    Slider(
                        value = viewport.widthFraction,
                        onValueChange = { newW ->
                            val clampedW = newW.coerceIn(TouchControllerSettingsManager.MIN_VIEWPORT_WIDTH, 1f)
                            val clampedX = viewport.xFraction.coerceIn(0f, (1f - clampedW).coerceAtLeast(0f))
                            onDraftChange(
                                draft.copy(customViewport = viewport.copy(xFraction = clampedX, widthFraction = clampedW)),
                            )
                        },
                        valueRange = TouchControllerSettingsManager.MIN_VIEWPORT_WIDTH..1f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(viewport.widthFraction * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(34.dp),
                    )
                    IconButton(
                        onClick = {
                            val centeredX = ((1f - viewport.widthFraction) / 2f).coerceAtLeast(0f)
                            onDraftChange(draft.copy(customViewport = viewport.copy(xFraction = centeredX)))
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CenterFocusStrong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.touch_hud_editor_height),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(46.dp),
                    )
                    Slider(
                        value = viewport.heightFraction,
                        onValueChange = { newH ->
                            val clampedH = newH.coerceIn(TouchControllerSettingsManager.MIN_VIEWPORT_HEIGHT, 1f)
                            val clampedY = viewport.yFraction.coerceIn(0f, (1f - clampedH).coerceAtLeast(0f))
                            onDraftChange(
                                draft.copy(customViewport = viewport.copy(yFraction = clampedY, heightFraction = clampedH)),
                            )
                        },
                        valueRange = TouchControllerSettingsManager.MIN_VIEWPORT_HEIGHT..1f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(viewport.heightFraction * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(34.dp),
                    )
                    IconButton(
                        onClick = {
                            onDraftChange(draft.copy(customViewport = baseline.customViewport))
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.RotateLeft,
                            contentDescription = stringResource(R.string.touch_hud_editor_reset_item),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onRequestResetAll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text(
                        text = stringResource(R.string.touch_hud_editor_reset_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text(
                        text = stringResource(R.string.touch_hud_editor_cancel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Button(
                    onClick = onSave,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text(
                        text = stringResource(R.string.touch_hud_editor_save),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

private fun hitTestHudTarget(
    position: Offset,
    specs: List<HudControlSpec>,
    draft: TouchControllerSettingsManager.Settings,
    baseline: TouchControllerSettingsManager.Settings,
    showHiddenInEditor: Boolean,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    densityScale: Float,
): HudDragTarget? {
    val minControlHalfHitPx = 22f * densityScale
    for (i in specs.indices.reversed()) {
        val spec = specs[i]
        val resolved =
            resolveHudControlBounds(
                spec = spec,
                override = draft.controlOverrides[spec.id],
                canvasWidthPx = canvasWidthPx,
                canvasHeightPx = canvasHeightPx,
                densityScale = densityScale,
            )
        if (!resolved.visible && !showHiddenInEditor) continue
        val halfHit = max(resolved.sizePx / 2f, minControlHalfHitPx)
        val centerX = resolved.centerXFraction * canvasWidthPx
        val centerY = resolved.centerYFraction * canvasHeightPx
        if (abs(position.x - centerX) <= halfHit && abs(position.y - centerY) <= halfHit) {
            return HudDragTarget.Control(spec)
        }
    }

    val viewport =
        draft.customViewport ?: baseline.customViewport
            ?: TouchControllerSettingsManager.HudViewportRect(0f, 0f, 1f, 0.55f)
    val vpLeft = viewport.xFraction * canvasWidthPx
    val vpTop = viewport.yFraction * canvasHeightPx
    val vpRight = vpLeft + viewport.widthFraction * canvasWidthPx
    val vpBottom = vpTop + viewport.heightFraction * canvasHeightPx
    val resizeHalfHitPx = 24f * densityScale
    val handleCenterX = vpRight - 18f * densityScale
    val handleCenterY = vpBottom - 18f * densityScale
    if (abs(position.x - handleCenterX) <= resizeHalfHitPx && abs(position.y - handleCenterY) <= resizeHalfHitPx) {
        return HudDragTarget.GameScreenResize
    }

    if (position.x in vpLeft..vpRight && position.y in vpTop..vpBottom) {
        return HudDragTarget.GameScreenMove
    }

    return null
}

private fun initRawDragAnchor(
    target: HudDragTarget,
    draft: TouchControllerSettingsManager.Settings,
    baseline: TouchControllerSettingsManager.Settings,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    densityScale: Float,
): Offset {
    val safeW = canvasWidthPx.coerceAtLeast(1f)
    val safeH = canvasHeightPx.coerceAtLeast(1f)
    val defaultViewport =
        baseline.customViewport
            ?: TouchControllerSettingsManager.HudViewportRect(0f, 0f, 1f, 0.55f)

    return when (target) {
        is HudDragTarget.Control -> {
            val resolved =
                resolveHudControlBounds(
                    spec = target.spec,
                    override = draft.controlOverrides[target.spec.id] ?: baseline.controlOverrides[target.spec.id],
                    canvasWidthPx = safeW,
                    canvasHeightPx = safeH,
                    densityScale = densityScale,
                )
            Offset(resolved.centerXFraction * safeW, resolved.centerYFraction * safeH)
        }
        HudDragTarget.GameScreenMove -> {
            val cur = draft.customViewport ?: defaultViewport
            Offset(cur.xFraction * safeW, cur.yFraction * safeH)
        }
        HudDragTarget.GameScreenResize -> {
            val cur = draft.customViewport ?: defaultViewport
            Offset(cur.widthFraction * safeW, cur.heightFraction * safeH)
        }
    }
}

private fun clampRawDragAnchor(
    target: HudDragTarget,
    raw: Offset,
    draft: TouchControllerSettingsManager.Settings,
    baseline: TouchControllerSettingsManager.Settings,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    densityScale: Float,
): Offset {
    val safeW = canvasWidthPx.coerceAtLeast(1f)
    val safeH = canvasHeightPx.coerceAtLeast(1f)
    val defaultViewport =
        baseline.customViewport
            ?: TouchControllerSettingsManager.HudViewportRect(0f, 0f, 1f, 0.55f)

    return when (target) {
        is HudDragTarget.Control -> {
            val resolved =
                resolveHudControlBounds(
                    spec = target.spec,
                    override = draft.controlOverrides[target.spec.id] ?: baseline.controlOverrides[target.spec.id],
                    canvasWidthPx = safeW,
                    canvasHeightPx = safeH,
                    densityScale = densityScale,
                )
            val halfW = (resolved.sizePx / 2f).coerceAtMost(safeW / 2f)
            val halfH = (resolved.sizePx / 2f).coerceAtMost(safeH / 2f)
            Offset(
                raw.x.coerceIn(halfW, (safeW - halfW).coerceAtLeast(halfW)),
                raw.y.coerceIn(halfH, (safeH - halfH).coerceAtLeast(halfH)),
            )
        }
        HudDragTarget.GameScreenMove -> {
            val cur = draft.customViewport ?: defaultViewport
            val maxX = ((1f - cur.widthFraction) * safeW).coerceAtLeast(0f)
            val maxY = ((1f - cur.heightFraction) * safeH).coerceAtLeast(0f)
            Offset(raw.x.coerceIn(0f, maxX), raw.y.coerceIn(0f, maxY))
        }
        HudDragTarget.GameScreenResize -> {
            val cur = draft.customViewport ?: defaultViewport
            val minW = TouchControllerSettingsManager.MIN_VIEWPORT_WIDTH * safeW
            val minH = TouchControllerSettingsManager.MIN_VIEWPORT_HEIGHT * safeH
            val maxW = ((1f - cur.xFraction) * safeW).coerceAtLeast(minW)
            val maxH = ((1f - cur.yFraction) * safeH).coerceAtLeast(minH)
            Offset(raw.x.coerceIn(minW, maxW), raw.y.coerceIn(minH, maxH))
        }
    }
}

private fun snapCoordinateToGrid(
    valuePx: Float,
    axisCenterPx: Float,
    stepPx: Float,
): Float {
    if (stepPx <= 1f) return valuePx
    return axisCenterPx + round((valuePx - axisCenterPx) / stepPx) * stepPx
}

private fun applyHudDragAtRawPosition(
    target: HudDragTarget,
    rawAnchor: Offset,
    snapToGrid: Boolean,
    draft: TouchControllerSettingsManager.Settings,
    baseline: TouchControllerSettingsManager.Settings,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    densityScale: Float,
    onDraftChange: (TouchControllerSettingsManager.Settings) -> Unit,
): Offset? {
    val safeW = canvasWidthPx.coerceAtLeast(1f)
    val safeH = canvasHeightPx.coerceAtLeast(1f)
    val stepPx = HUD_GRID_STEP_DP * densityScale
    val axisCenterX = safeW / 2f
    val axisCenterY = safeH / 2f
    val defaultViewport =
        baseline.customViewport
            ?: TouchControllerSettingsManager.HudViewportRect(0f, 0f, 1f, 0.55f)

    when (target) {
        is HudDragTarget.Control -> {
            val spec = target.spec
            val existing =
                draft.controlOverrides[spec.id]
                    ?: baseline.controlOverrides[spec.id]
                    ?: TouchControllerSettingsManager.HudControlOverride(
                        xFraction = spec.defaultXFraction,
                        yFraction = spec.defaultYFraction,
                        scale = 1.0f,
                        visible = true,
                    )
            val curBounds =
                resolveHudControlBounds(
                    spec = spec,
                    override = existing,
                    canvasWidthPx = safeW,
                    canvasHeightPx = safeH,
                    densityScale = densityScale,
                )
            val halfW = (curBounds.sizePx / 2f).coerceAtMost(safeW / 2f)
            val halfH = (curBounds.sizePx / 2f).coerceAtMost(safeH / 2f)

            val candidateX = if (snapToGrid) snapCoordinateToGrid(rawAnchor.x, axisCenterX, stepPx) else rawAnchor.x
            val candidateY = if (snapToGrid) snapCoordinateToGrid(rawAnchor.y, axisCenterY, stepPx) else rawAnchor.y

            val finalX = candidateX.coerceIn(halfW, (safeW - halfW).coerceAtLeast(halfW))
            val finalY = candidateY.coerceIn(halfH, (safeH - halfH).coerceAtLeast(halfH))

            val updatedOverrides = draft.controlOverrides.toMutableMap()
            updatedOverrides[spec.id] = existing.copy(xFraction = finalX / safeW, yFraction = finalY / safeH)
            onDraftChange(draft.copy(controlOverrides = updatedOverrides))
            return Offset(finalX, finalY)
        }

        HudDragTarget.GameScreenMove -> {
            val cur = draft.customViewport ?: defaultViewport
            val vpWidthPx = cur.widthFraction * safeW
            val maxX = (safeW - vpWidthPx).coerceAtLeast(0f)
            val maxY = (safeH - cur.heightFraction * safeH).coerceAtLeast(0f)

            val finalX =
                if (snapToGrid && stepPx > 1f) {
                    val edgeThreshold = stepPx * 0.45f
                    when {
                        rawAnchor.x <= edgeThreshold -> 0f
                        maxX - rawAnchor.x <= edgeThreshold -> maxX
                        else -> {
                            val snappedCenter = snapCoordinateToGrid(rawAnchor.x + vpWidthPx / 2f, axisCenterX, stepPx)
                            (snappedCenter - vpWidthPx / 2f).coerceIn(0f, maxX)
                        }
                    }
                } else {
                    rawAnchor.x.coerceIn(0f, maxX)
                }

            val finalY =
                if (snapToGrid && stepPx > 1f) {
                    val edgeThreshold = stepPx * 0.45f
                    when {
                        rawAnchor.y <= edgeThreshold -> 0f
                        maxY - rawAnchor.y <= edgeThreshold -> maxY
                        else -> snapCoordinateToGrid(rawAnchor.y, axisCenterY, stepPx).coerceIn(0f, maxY)
                    }
                } else {
                    rawAnchor.y.coerceIn(0f, maxY)
                }

            onDraftChange(draft.copy(customViewport = cur.copy(xFraction = finalX / safeW, yFraction = finalY / safeH)))
            return null
        }

        HudDragTarget.GameScreenResize -> {
            val cur = draft.customViewport ?: defaultViewport
            val minW = TouchControllerSettingsManager.MIN_VIEWPORT_WIDTH * safeW
            val minH = TouchControllerSettingsManager.MIN_VIEWPORT_HEIGHT * safeH
            val maxW = ((1f - cur.xFraction) * safeW).coerceAtLeast(minW)
            val maxH = ((1f - cur.yFraction) * safeH).coerceAtLeast(minH)

            val finalW =
                if (snapToGrid && stepPx > 1f) {
                    if (maxW - rawAnchor.x <= stepPx * 0.45f) {
                        maxW
                    } else {
                        (round(rawAnchor.x / stepPx) * stepPx).coerceIn(minW, maxW)
                    }
                } else {
                    rawAnchor.x.coerceIn(minW, maxW)
                }

            val finalH =
                if (snapToGrid && stepPx > 1f) {
                    if (maxH - rawAnchor.y <= stepPx * 0.45f) {
                        maxH
                    } else {
                        (round(rawAnchor.y / stepPx) * stepPx).coerceIn(minH, maxH)
                    }
                } else {
                    rawAnchor.y.coerceIn(minH, maxH)
                }

            onDraftChange(draft.copy(customViewport = cur.copy(widthFraction = finalW / safeW, heightFraction = finalH / safeH)))
            return null
        }
    }
}
