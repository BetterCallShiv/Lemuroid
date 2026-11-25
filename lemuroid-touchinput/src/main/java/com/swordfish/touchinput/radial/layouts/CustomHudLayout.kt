package com.swordfish.touchinput.radial.layouts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.swordfish.touchinput.radial.settings.HudControlSpec
import com.swordfish.touchinput.radial.settings.TouchControllerID
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.settings.getHudControlSpecs
import gg.padkit.PadKitScope
import kotlin.math.roundToInt

data class HudControlPixelBounds(
    val leftPx: Float,
    val topPx: Float,
    val sizePx: Float,
    val sizeDp: Dp,
    val centerXFraction: Float,
    val centerYFraction: Float,
    val scale: Float,
    val visible: Boolean,
)

fun resolveHudControlBounds(
    spec: HudControlSpec,
    override: TouchControllerSettingsManager.HudControlOverride?,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    densityScale: Float,
): HudControlPixelBounds {
    val safeW = canvasWidthPx.coerceAtLeast(1f)
    val safeH = canvasHeightPx.coerceAtLeast(1f)
    val scale =
        (override?.scale ?: 1.0f).coerceIn(
            TouchControllerSettingsManager.MIN_CUSTOM_CONTROL_SCALE,
            TouchControllerSettingsManager.MAX_CUSTOM_CONTROL_SCALE,
        )
    val sizeDpVal = spec.baseSizeDp * scale
    val sizePx = sizeDpVal * densityScale
    val halfW = (sizePx / 2f).coerceAtMost(safeW / 2f)
    val halfH = (sizePx / 2f).coerceAtMost(safeH / 2f)

    val rawCenterX = (override?.xFraction ?: spec.defaultXFraction) * safeW
    val rawCenterY = (override?.yFraction ?: spec.defaultYFraction) * safeH
    val centerX = rawCenterX.coerceIn(halfW, (safeW - halfW).coerceAtLeast(halfW))
    val centerY = rawCenterY.coerceIn(halfH, (safeH - halfH).coerceAtLeast(halfH))

    return HudControlPixelBounds(
        leftPx = centerX - sizePx / 2f,
        topPx = centerY - sizePx / 2f,
        sizePx = sizePx,
        sizeDp = sizeDpVal.dp,
        centerXFraction = centerX / safeW,
        centerYFraction = centerY / safeH,
        scale = scale,
        visible = if (spec.canHide) (override?.visible ?: true) else true,
    )
}

@Composable
fun PadKitScope.CustomHudOverlay(
    modifier: Modifier = Modifier,
    controllerId: TouchControllerID,
    settings: TouchControllerSettingsManager.Settings,
    isEditorMode: Boolean = false,
    showHiddenInEditor: Boolean = true,
) {
    val specs =
        remember(controllerId, settings.rotation) {
            getHudControlSpecs(controllerId, settings)
        }
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        specs.forEach { spec ->
            key(spec.id) {
                val resolved =
                    resolveHudControlBounds(
                        spec = spec,
                        override = settings.controlOverrides[spec.id],
                        canvasWidthPx = widthPx,
                        canvasHeightPx = heightPx,
                        densityScale = density.density,
                    )

                if (resolved.visible || (isEditorMode && showHiddenInEditor)) {
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
                                .alpha(if (resolved.visible) 1f else 0.28f),
                        contentAlignment = Alignment.Center,
                    ) {
                        spec.content(this@CustomHudOverlay)
                    }
                }
            }
        }
    }
}
