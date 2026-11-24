package com.swordfish.touchinput.radial.layouts

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.swordfish.touchinput.controller.R
import com.swordfish.touchinput.radial.LocalFastForwardEnabled
import com.swordfish.touchinput.radial.LocalLemuroidPadTheme
import com.swordfish.touchinput.radial.controls.LemuroidControlButton
import kotlin.math.cos
import kotlin.math.sin
import com.swordfish.touchinput.radial.controls.LemuroidControlCross
import com.swordfish.touchinput.radial.controls.LemuroidControlFaceButtons
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenu
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonStart
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.ui.LemuroidButtonForeground
import gg.padkit.PadKitScope
import gg.padkit.ids.Id
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

@Composable
fun PadKitScope.LynxLeft(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            LemuroidControlButton(
                modifier = Modifier.radialPosition(120f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_L1),
                label = "OPTION 1",
            )
            LemuroidControlButton(
                modifier = Modifier.radialPosition(-120f),
                id = Id.Key(KeyEvent.KEYCODE_BUTTON_R1),
                label = "OPTION 2",
            )
        },
    )
}

@Composable
fun PadKitScope.LynxRight(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutRight(
        settings = settings,
        modifier = modifier,
        primaryDial = {
            LemuroidControlFaceButtons(
                rotationInDegrees = -15f,
                ids =
                    persistentListOf(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A),
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B),
                    ),
                idsForegrounds =
                    persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A) to { LemuroidButtonForeground(pressed = it, label = "A") },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to { LemuroidButtonForeground(pressed = it, label = "B") },
                    ),
            )
        },
        secondaryDials = {
            SecondaryButtonStart()
            SecondaryButtonMenu(settings)
        },
    )
}

@Composable
fun PadKitScope.LynxCenter(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    val theme = LocalLemuroidPadTheme.current
    val interpolatedDialSize =
        remember(settings.scale) {
            lerp(
                TouchControllerSettingsManager.MIN_SCALE,
                TouchControllerSettingsManager.MAX_SCALE,
                settings.scale,
            )
        }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
    ) {
        val isPortrait = maxWidth <= maxHeight
        val sidePadMaxWidth = if (isPortrait) maxWidth / 2f else maxWidth
        val radialAvailableWidth =
            (sidePadMaxWidth - TouchControllerSettingsManager.MAX_MARGINS.dp * settings.marginX - theme.padding * 2)
                .coerceAtLeast(0.dp)
        val radialAvailableHeight =
            (maxHeight - TouchControllerSettingsManager.MAX_MARGINS.dp * settings.marginY - theme.padding * 2)
                .coerceAtLeast(0.dp)

        val secondaryScale =
            remember {
                val sinVal = sin(Math.PI / 12.0).toFloat()
                sinVal / (1f - sinVal)
            }
        val (relativeWidth, relativeHeight) =
            remember(settings.rotation, secondaryScale) {
                val baseRotation = -settings.rotation * TouchControllerSettingsManager.MAX_ROTATION
                val angles =
                    listOf(
                        60f + baseRotation,
                        -60f + 2f * settings.rotation * TouchControllerSettingsManager.MAX_ROTATION + baseRotation,
                    )
                val distance = 0.5f * (1f + secondaryScale)
                val halfSecondary = secondaryScale / 2f
                var left = -0.5f
                var top = -0.5f
                var right = 0.5f
                var bottom = 0.5f
                angles.forEach { deg ->
                    val rad = Math.toRadians(-deg.toDouble())
                    val cx = cos(rad).toFloat() * distance
                    val cy = sin(rad).toFloat() * distance
                    left = minOf(left, cx - halfSecondary)
                    top = minOf(top, cy - halfSecondary)
                    right = maxOf(right, cx + halfSecondary)
                    bottom = maxOf(bottom, cy + halfSecondary)
                }
                (right - left) to (bottom - top)
            }

        val maxPrimaryDialSize = 160.dp * interpolatedDialSize
        val clampedPrimarySize =
            minOf(
                maxPrimaryDialSize,
                radialAvailableWidth / relativeWidth,
                radialAvailableHeight / relativeHeight,
            )
        val buttonSize = clampedPrimarySize * secondaryScale

        Box(
            modifier =
                Modifier
                    .absolutePadding(
                        bottom = TouchControllerSettingsManager.MAX_MARGINS.dp * settings.marginY,
                    )
                    .size(buttonSize),
            contentAlignment = Alignment.Center,
        ) {
            LemuroidControlButton(
                modifier = Modifier.fillMaxSize(),
                id = Id.Key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
                icon = R.drawable.button_fast_forward,
                active = LocalFastForwardEnabled.current,
            )
        }
    }
}
