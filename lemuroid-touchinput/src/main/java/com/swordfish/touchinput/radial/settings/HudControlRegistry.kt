package com.swordfish.touchinput.radial.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.swordfish.touchinput.controller.R
import com.swordfish.touchinput.radial.LocalFastForwardEnabled
import com.swordfish.touchinput.radial.controls.LemuroidControlAnalog
import com.swordfish.touchinput.radial.controls.LemuroidControlButton
import com.swordfish.touchinput.radial.controls.LemuroidControlCross
import com.swordfish.touchinput.radial.controls.LemuroidControlFaceButtons
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.ui.LemuroidButtonForeground
import com.swordfish.touchinput.radial.ui.LemuroidCentralButton
import com.swordfish.touchinput.radial.ui.LemuroidCrossForegroundAlternate
import com.swordfish.touchinput.radial.utils.buildCentral6ButtonsAnchors
import gg.padkit.PadKitScope
import gg.padkit.ids.Id
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

object HudControlIds {
    const val LEFT_DPAD = "left_dpad"
    const val RIGHT_FACE = "right_face"
    const val LEFT_ANALOG = "left_analog"
    const val RIGHT_ANALOG = "right_analog"
    const val RIGHT_C_PAD = "right_c_pad"
    const val BTN_L = "btn_l"
    const val BTN_R = "btn_r"
    const val BTN_L1 = "btn_l1"
    const val BTN_L2 = "btn_l2"
    const val BTN_R1 = "btn_r1"
    const val BTN_R2 = "btn_r2"
    const val BTN_EXTRA_C = "btn_extra_c"
    const val BTN_START = "btn_start"
    const val BTN_SELECT = "btn_select"
    const val BTN_MENU = "btn_menu"
    const val BTN_FAST_FORWARD = "btn_fast_forward"
    const val BTN_MIC = "btn_mic"
    const val BTN_CLOSE_SCREEN = "btn_close_screen"
    const val BTN_KEYBOARD = "btn_keyboard"
}

data class HudControlSpec(
    val id: String,
    val label: String,
    val baseSizeDp: Float,
    val defaultXFraction: Float,
    val defaultYFraction: Float,
    val canHide: Boolean = true,
    val content: @Composable PadKitScope.() -> Unit,
)

val LocalHudControlBoundsRecorder = compositionLocalOf<((String, Rect) -> Unit)?> { null }

@Composable
fun Modifier.trackHudBounds(controlId: String): Modifier {
    val recorder = LocalHudControlBoundsRecorder.current ?: return this
    return this.onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) {
            recorder(controlId, coordinates.boundsInRoot())
        }
    }
}

fun getHudControlSpecs(
    controllerId: TouchControllerID,
    settings: TouchControllerSettingsManager.Settings,
): List<HudControlSpec> {
    val primaryDpad =
        dpadSpec(
            motionSource =
                when (controllerId) {
                    TouchControllerID.ARCADE_4,
                    TouchControllerID.ARCADE_6,
                    -> ComposeTouchLayouts.MOTION_SOURCE_DPAD_AND_LEFT_STICK
                    else -> ComposeTouchLayouts.MOTION_SOURCE_DPAD
                },
        )

    return when (controllerId) {
        TouchControllerID.GB ->
            listOf(
                primaryDpad,
                labeledFaceSpec(rotation = -30f, buttons = listOf(KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B")),
                selectButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.NES ->
            listOf(
                primaryDpad,
                labeledFaceSpec(buttons = listOf(KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B")),
                selectButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.SMS,
        TouchControllerID.ATARI7800,
        ->
            listOf(
                primaryDpad,
                labeledFaceSpec(buttons = listOf(KeyEvent.KEYCODE_BUTTON_A to "2", KeyEvent.KEYCODE_BUTTON_B to "1")),
                selectButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            ).let { base ->
                if (controllerId == TouchControllerID.SMS) base.filterNot { it.id == HudControlIds.BTN_SELECT } else base
            }

        TouchControllerID.GG ->
            listOf(
                primaryDpad,
                labeledFaceSpec(rotation = -30f, buttons = listOf(KeyEvent.KEYCODE_BUTTON_A to "2", KeyEvent.KEYCODE_BUTTON_B to "1")),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.NGP ->
            listOf(
                primaryDpad,
                labeledFaceSpec(rotation = -30f, buttons = listOf(KeyEvent.KEYCODE_BUTTON_A to "B", KeyEvent.KEYCODE_BUTTON_B to "A")),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.PCE ->
            listOf(
                primaryDpad,
                labeledFaceSpec(buttons = listOf(KeyEvent.KEYCODE_BUTTON_A to "II", KeyEvent.KEYCODE_BUTTON_B to "I")),
                selectButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.WS_LANDSCAPE ->
            listOf(
                primaryDpad,
                labeledFaceSpec(rotation = -30f, buttons = listOf(KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B")),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.WS_PORTRAIT ->
            listOf(
                primaryDpad,
                labeledFaceSpec(
                    buttons =
                        listOf(
                            KeyEvent.KEYCODE_BUTTON_A to "X3",
                            KeyEvent.KEYCODE_BUTTON_B to "X4",
                            KeyEvent.KEYCODE_BUTTON_Y to "X1",
                            KeyEvent.KEYCODE_BUTTON_X to "X2",
                        ),
                ),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.GENESIS_3 ->
            listOf(
                primaryDpad,
                labeledFaceSpec(
                    rotation = -30f,
                    buttons =
                        listOf(
                            KeyEvent.KEYCODE_BUTTON_A to "C",
                            KeyEvent.KEYCODE_BUTTON_Y to "A",
                            KeyEvent.KEYCODE_BUTTON_B to "B",
                        ),
                ),
                selectButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.GENESIS_6 ->
            listOf(
                primaryDpad,
                central6FaceSpec(
                    label = "A / B / X / Y",
                    rotation = settings.rotation,
                    buttons =
                        listOf(
                            KeyEvent.KEYCODE_BUTTON_X to "Y",
                            KeyEvent.KEYCODE_BUTTON_L1 to "X",
                            KeyEvent.KEYCODE_BUTTON_B to "B",
                            KeyEvent.KEYCODE_BUTTON_Y to "A",
                        ),
                ),
                textButtonSpec(HudControlIds.BTN_EXTRA_C, "C", KeyEvent.KEYCODE_BUTTON_A, "C", 0.72f, 0.56f),
                textButtonSpec(HudControlIds.BTN_R1, "Z", KeyEvent.KEYCODE_BUTTON_R1, "Z", 0.86f, 0.50f),
                selectButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.ATARI2600 ->
            listOf(
                primaryDpad,
                iconFaceSpec(
                    label = "Fire",
                    keyIcons = listOf(KeyEvent.KEYCODE_BUTTON_B to null),
                    includeComposite = false,
                ),
                textButtonSpec(HudControlIds.BTN_L1, "DIFF.A", KeyEvent.KEYCODE_BUTTON_L1, "DIFF.A", 0.14f, 0.52f),
                textButtonSpec(HudControlIds.BTN_L2, "DIFF.B", KeyEvent.KEYCODE_BUTTON_L2, "DIFF.B", 0.25f, 0.52f),
                textButtonSpec(HudControlIds.BTN_SELECT, "SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", 0.75f, 0.52f),
                textButtonSpec(HudControlIds.BTN_START, "RESET", KeyEvent.KEYCODE_BUTTON_START, "RESET", 0.86f, 0.52f),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.LYNX ->
            listOf(
                primaryDpad,
                labeledFaceSpec(rotation = -15f, buttons = listOf(KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B")),
                textButtonSpec(HudControlIds.BTN_L1, "OPTION 1", KeyEvent.KEYCODE_BUTTON_L1, "OPTION 1", 0.14f, 0.52f),
                textButtonSpec(HudControlIds.BTN_R1, "OPTION 2", KeyEvent.KEYCODE_BUTTON_R1, "OPTION 2", 0.14f, 0.86f),
                startButtonSpec(),
                fastForwardButtonSpec(defaultX = 0.50f, defaultY = 0.86f),
                menuButtonSpec(),
            )

        TouchControllerID.GBA ->
            listOf(
                primaryDpad,
                labeledFaceSpec(rotation = -30f, buttons = listOf(KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B")),
                lButtonSpec(),
                rButtonSpec(),
                selectButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.SNES ->
            listOf(
                primaryDpad,
                labeledFaceSpec(
                    buttons =
                        listOf(
                            KeyEvent.KEYCODE_BUTTON_A to "A",
                            KeyEvent.KEYCODE_BUTTON_B to "B",
                            KeyEvent.KEYCODE_BUTTON_Y to "Y",
                            KeyEvent.KEYCODE_BUTTON_X to "X",
                        ),
                ),
                lButtonSpec(),
                rButtonSpec(),
                selectButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.DESMUME ->
            listOf(
                primaryDpad,
                labeledFaceSpec(
                    buttons =
                        listOf(
                            KeyEvent.KEYCODE_BUTTON_A to "A",
                            KeyEvent.KEYCODE_BUTTON_B to "B",
                            KeyEvent.KEYCODE_BUTTON_Y to "Y",
                            KeyEvent.KEYCODE_BUTTON_X to "X",
                        ),
                ),
                lButtonSpec(),
                rButtonSpec(),
                selectButtonSpec(),
                startButtonSpec(),
                iconButtonSpec(HudControlIds.BTN_MIC, "Mic", KeyEvent.KEYCODE_BUTTON_THUMBL, R.drawable.button_mic, 0.12f, 0.86f),
                iconButtonSpec(HudControlIds.BTN_CLOSE_SCREEN, "Close Lid", KeyEvent.KEYCODE_BUTTON_L2, R.drawable.button_close_screen, 0.24f, 0.86f),
                fastForwardButtonSpec(defaultX = 0.76f, defaultY = 0.86f),
                menuButtonSpec(),
            )

        TouchControllerID.MELONDS ->
            listOf(
                primaryDpad,
                labeledFaceSpec(
                    buttons =
                        listOf(
                            KeyEvent.KEYCODE_BUTTON_A to "A",
                            KeyEvent.KEYCODE_BUTTON_B to "B",
                            KeyEvent.KEYCODE_BUTTON_Y to "Y",
                            KeyEvent.KEYCODE_BUTTON_X to "X",
                        ),
                ),
                lButtonSpec(),
                rButtonSpec(),
                selectButtonSpec(),
                startButtonSpec(),
                iconButtonSpec(HudControlIds.BTN_MIC, "Mic", KeyEvent.KEYCODE_BUTTON_L2, R.drawable.button_mic, 0.12f, 0.86f),
                iconButtonSpec(HudControlIds.BTN_CLOSE_SCREEN, "Close Lid", KeyEvent.KEYCODE_BUTTON_THUMBL, R.drawable.button_close_screen, 0.24f, 0.86f),
                fastForwardButtonSpec(defaultX = 0.76f, defaultY = 0.86f),
                menuButtonSpec(),
            )

        TouchControllerID.PSX ->
            listOf(
                primaryDpad,
                psxFaceSpec(),
                l1ButtonSpec(),
                l2ButtonSpec(),
                r1ButtonSpec(),
                r2ButtonSpec(),
                selectButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.PSX_DUALSHOCK ->
            listOf(
                primaryDpad,
                psxFaceSpec(),
                leftAnalogSpec(),
                rightAnalogSpec(),
                l1ButtonSpec(),
                l2ButtonSpec(),
                r1ButtonSpec(),
                r2ButtonSpec(),
                selectButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.N64 ->
            listOf(
                primaryDpad,
                labeledFaceSpec(
                    label = "A / B / Z",
                    rotation = 90f,
                    buttons =
                        listOf(
                            KeyEvent.KEYCODE_BUTTON_B to "A",
                            KeyEvent.KEYCODE_BUTTON_Y to "B",
                            KeyEvent.KEYCODE_BUTTON_L2 to "Z",
                        ),
                ),
                leftAnalogSpec(),
                n64CPadSpec(),
                lButtonSpec(),
                textButtonSpec(HudControlIds.BTN_L2, "Z (Left)", KeyEvent.KEYCODE_BUTTON_L2, "Z", 0.25f, 0.52f),
                rButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.PSP ->
            listOf(
                primaryDpad,
                psxFaceSpec(),
                leftAnalogSpec(),
                lButtonSpec(),
                rButtonSpec(),
                selectButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.NINTENDO_3DS ->
            listOf(
                primaryDpad,
                labeledFaceSpec(
                    buttons =
                        listOf(
                            KeyEvent.KEYCODE_BUTTON_A to "A",
                            KeyEvent.KEYCODE_BUTTON_B to "B",
                            KeyEvent.KEYCODE_BUTTON_Y to "Y",
                            KeyEvent.KEYCODE_BUTTON_X to "X",
                        ),
                ),
                leftAnalogSpec(),
                lButtonSpec(),
                rButtonSpec(),
                selectButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.ARCADE_4 ->
            listOf(
                primaryDpad,
                iconFaceSpec(
                    label = "Face Buttons",
                    keyIcons =
                        listOf(
                            KeyEvent.KEYCODE_BUTTON_A to null,
                            KeyEvent.KEYCODE_BUTTON_B to null,
                            KeyEvent.KEYCODE_BUTTON_Y to null,
                            KeyEvent.KEYCODE_BUTTON_X to null,
                        ),
                ),
                coinButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.ARCADE_6 ->
            listOf(
                primaryDpad,
                central6FaceSpec(
                    label = "Face Buttons",
                    rotation = settings.rotation,
                    buttons =
                        listOf(
                            KeyEvent.KEYCODE_BUTTON_X to null,
                            KeyEvent.KEYCODE_BUTTON_Y to null,
                            KeyEvent.KEYCODE_BUTTON_A to null,
                            KeyEvent.KEYCODE_BUTTON_B to null,
                        ),
                ),
                textButtonSpec(HudControlIds.BTN_L1, "Button 5", KeyEvent.KEYCODE_BUTTON_L1, null, 0.72f, 0.56f),
                textButtonSpec(HudControlIds.BTN_R1, "Button 6", KeyEvent.KEYCODE_BUTTON_R1, null, 0.86f, 0.50f),
                coinButtonSpec(),
                startButtonSpec(),
                fastForwardButtonSpec(),
                menuButtonSpec(),
            )

        TouchControllerID.DOS ->
            listOf(
                primaryDpad,
                labeledFaceSpec(
                    buttons =
                        listOf(
                            KeyEvent.KEYCODE_BUTTON_A to "A",
                            KeyEvent.KEYCODE_BUTTON_B to "B",
                            KeyEvent.KEYCODE_BUTTON_Y to "Y",
                            KeyEvent.KEYCODE_BUTTON_X to "X",
                        ),
                ),
                leftAnalogSpec(),
                rightAnalogSpec(),
                l1ButtonSpec(),
                l2ButtonSpec(),
                r1ButtonSpec(),
                r2ButtonSpec(),
                selectButtonSpec(),
                startButtonSpec(),
                iconButtonSpec(HudControlIds.BTN_KEYBOARD, "Keyboard", KeyEvent.KEYCODE_BUTTON_THUMBL, R.drawable.button_keyboard, 0.12f, 0.86f),
                fastForwardButtonSpec(defaultX = 0.76f, defaultY = 0.86f),
                menuButtonSpec(),
            )
    }
}

private fun dpadSpec(motionSource: Int) =
    HudControlSpec(
        id = HudControlIds.LEFT_DPAD,
        label = "D-Pad",
        baseSizeDp = 160f,
        defaultXFraction = 0.18f,
        defaultYFraction = 0.72f,
    ) {
        LemuroidControlCross(
            modifier = Modifier.fillMaxSize(),
            id = Id.DiscreteDirection(motionSource),
        )
    }

private fun labeledFaceSpec(
    label: String = "Face Buttons",
    rotation: Float = 0f,
    buttons: List<Pair<Int, String>>,
): HudControlSpec {
    val ids = buttons.map { Id.Key(it.first) }.toPersistentList()
    val foregrounds =
        buttons.associate { (keyCode, text) ->
            val fg: @Composable (State<Boolean>) -> Unit = { LemuroidButtonForeground(pressed = it, label = text) }
            Id.Key(keyCode) to fg
        }.toPersistentMap()

    return HudControlSpec(
        id = HudControlIds.RIGHT_FACE,
        label = label,
        baseSizeDp = 160f,
        defaultXFraction = 0.82f,
        defaultYFraction = 0.72f,
    ) {
        LemuroidControlFaceButtons(
            modifier = Modifier.fillMaxSize(),
            rotationInDegrees = rotation,
            ids = ids,
            idsForegrounds = foregrounds,
        )
    }
}

private fun iconFaceSpec(
    label: String = "Face Buttons",
    keyIcons: List<Pair<Int, Int?>>,
    includeComposite: Boolean = true,
): HudControlSpec {
    val ids = keyIcons.map { Id.Key(it.first) }.toPersistentList()
    val foregrounds =
        keyIcons.associate { (keyCode, iconRes) ->
            val fg: @Composable (State<Boolean>) -> Unit = { LemuroidButtonForeground(pressed = it, icon = iconRes) }
            Id.Key(keyCode) to fg
        }.toPersistentMap()

    return HudControlSpec(
        id = HudControlIds.RIGHT_FACE,
        label = label,
        baseSizeDp = 160f,
        defaultXFraction = 0.82f,
        defaultYFraction = 0.72f,
    ) {
        LemuroidControlFaceButtons(
            modifier = Modifier.fillMaxSize(),
            ids = ids,
            includeComposite = includeComposite,
            idsForegrounds = foregrounds,
        )
    }
}

private fun psxFaceSpec() =
    iconFaceSpec(
        label = "Face Buttons",
        keyIcons =
            listOf(
                KeyEvent.KEYCODE_BUTTON_A to R.drawable.psx_circle,
                KeyEvent.KEYCODE_BUTTON_B to R.drawable.psx_cross,
                KeyEvent.KEYCODE_BUTTON_Y to R.drawable.psx_square,
                KeyEvent.KEYCODE_BUTTON_X to R.drawable.psx_triangle,
            ),
    )

private fun central6FaceSpec(
    label: String,
    rotation: Float,
    buttons: List<Pair<Int, String?>>,
): HudControlSpec {
    val foregrounds =
        buttons.associate { (keyCode, text) ->
            val fg: @Composable (State<Boolean>) -> Unit = { LemuroidCentralButton(pressedState = it, label = text) }
            Id.Key(keyCode) to fg
        }.toPersistentMap()

    return HudControlSpec(
        id = HudControlIds.RIGHT_FACE,
        label = label,
        baseSizeDp = 160f,
        defaultXFraction = 0.82f,
        defaultYFraction = 0.72f,
    ) {
        val anchors =
            remember(rotation) {
                buildCentral6ButtonsAnchors(
                    rotation,
                    buttons[0].first,
                    buttons[1].first,
                    buttons[2].first,
                    buttons[3].first,
                )
            }
        LemuroidControlFaceButtons(
            modifier = Modifier.fillMaxSize(),
            primaryAnchors = anchors,
            background = { },
            applyPadding = false,
            trackPointers = false,
            idsForegrounds = foregrounds,
        )
    }
}

private fun leftAnalogSpec() =
    HudControlSpec(
        id = HudControlIds.LEFT_ANALOG,
        label = "Left Analog",
        baseSizeDp = 112f,
        defaultXFraction = 0.28f,
        defaultYFraction = 0.82f,
    ) {
        LemuroidControlAnalog(
            modifier = Modifier.fillMaxSize(),
            id = Id.ContinuousDirection(ComposeTouchLayouts.MOTION_SOURCE_LEFT_STICK),
            analogPressId = Id.Key(KeyEvent.KEYCODE_BUTTON_THUMBL),
        )
    }

private fun rightAnalogSpec() =
    HudControlSpec(
        id = HudControlIds.RIGHT_ANALOG,
        label = "Right Analog",
        baseSizeDp = 112f,
        defaultXFraction = 0.72f,
        defaultYFraction = 0.82f,
    ) {
        LemuroidControlAnalog(
            modifier = Modifier.fillMaxSize(),
            id = Id.ContinuousDirection(ComposeTouchLayouts.MOTION_SOURCE_RIGHT_STICK),
            analogPressId = Id.Key(KeyEvent.KEYCODE_BUTTON_THUMBR),
        )
    }

private fun n64CPadSpec() =
    HudControlSpec(
        id = HudControlIds.RIGHT_C_PAD,
        label = "C-Buttons",
        baseSizeDp = 112f,
        defaultXFraction = 0.72f,
        defaultYFraction = 0.82f,
    ) {
        LemuroidControlCross(
            modifier = Modifier.fillMaxSize(),
            id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_RIGHT_STICK),
            allowDiagonals = false,
            foreground = { LemuroidCrossForegroundAlternate(it) },
        )
    }

private fun textButtonSpec(
    id: String,
    label: String,
    keyCode: Int,
    buttonText: String?,
    defaultX: Float,
    defaultY: Float,
) = HudControlSpec(
    id = id,
    label = label,
    baseSizeDp = 56f,
    defaultXFraction = defaultX,
    defaultYFraction = defaultY,
) {
    LemuroidControlButton(
        modifier = Modifier.fillMaxSize(),
        id = Id.Key(keyCode),
        label = buttonText,
    )
}

private fun iconButtonSpec(
    id: String,
    label: String,
    keyCode: Int,
    iconRes: Int,
    defaultX: Float,
    defaultY: Float,
    canHide: Boolean = true,
) = HudControlSpec(
    id = id,
    label = label,
    baseSizeDp = 56f,
    defaultXFraction = defaultX,
    defaultYFraction = defaultY,
    canHide = canHide,
) {
    LemuroidControlButton(
        modifier = Modifier.fillMaxSize(),
        id = Id.Key(keyCode),
        icon = iconRes,
    )
}

private fun lButtonSpec() = textButtonSpec(HudControlIds.BTN_L, "L", KeyEvent.KEYCODE_BUTTON_L1, "L", 0.12f, 0.54f)

private fun rButtonSpec() = textButtonSpec(HudControlIds.BTN_R, "R", KeyEvent.KEYCODE_BUTTON_R1, "R", 0.88f, 0.54f)

private fun l1ButtonSpec() = textButtonSpec(HudControlIds.BTN_L1, "L1", KeyEvent.KEYCODE_BUTTON_L1, "L1", 0.20f, 0.52f)

private fun l2ButtonSpec() = textButtonSpec(HudControlIds.BTN_L2, "L2", KeyEvent.KEYCODE_BUTTON_L2, "L2", 0.11f, 0.54f)

private fun r1ButtonSpec() = textButtonSpec(HudControlIds.BTN_R1, "R1", KeyEvent.KEYCODE_BUTTON_R1, "R1", 0.80f, 0.52f)

private fun r2ButtonSpec() = textButtonSpec(HudControlIds.BTN_R2, "R2", KeyEvent.KEYCODE_BUTTON_R2, "R2", 0.89f, 0.54f)

private fun selectButtonSpec() = iconButtonSpec(HudControlIds.BTN_SELECT, "Select", KeyEvent.KEYCODE_BUTTON_SELECT, R.drawable.button_select, 0.34f, 0.86f)

private fun coinButtonSpec() = iconButtonSpec(HudControlIds.BTN_SELECT, "Coin", KeyEvent.KEYCODE_BUTTON_SELECT, R.drawable.button_coin, 0.14f, 0.54f)

private fun startButtonSpec() = iconButtonSpec(HudControlIds.BTN_START, "Start", KeyEvent.KEYCODE_BUTTON_START, R.drawable.button_start, 0.66f, 0.86f)

private fun menuButtonSpec() = iconButtonSpec(HudControlIds.BTN_MENU, "Menu", KeyEvent.KEYCODE_BUTTON_MODE, R.drawable.button_menu, 0.88f, 0.86f, canHide = false)

private fun fastForwardButtonSpec(
    defaultX: Float = 0.12f,
    defaultY: Float = 0.86f,
) = HudControlSpec(
    id = HudControlIds.BTN_FAST_FORWARD,
    label = "Fast Forward",
    baseSizeDp = 56f,
    defaultXFraction = defaultX,
    defaultYFraction = defaultY,
) {
    LemuroidControlButton(
        modifier = Modifier.fillMaxSize(),
        id = Id.Key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
        icon = R.drawable.button_fast_forward,
        active = LocalFastForwardEnabled.current,
    )
}
