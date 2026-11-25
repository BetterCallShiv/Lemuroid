package com.swordfish.lemuroid.app.mobile.feature.game

import android.graphics.RectF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.swordfish.lemuroid.app.shared.game.BaseGameScreenViewModel
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelTouchControls.Companion.MENU_LOADING_ANIMATION_MILLIS
import com.swordfish.lemuroid.app.shared.settings.HapticFeedbackMode
import com.swordfish.lemuroid.lib.controller.ControllerConfig
import com.swordfish.touchinput.controller.R
import com.swordfish.touchinput.radial.LemuroidPadTheme
import com.swordfish.touchinput.radial.LocalFastForwardEnabled
import com.swordfish.touchinput.radial.LocalLemuroidPadTheme
import com.swordfish.touchinput.radial.layouts.CustomHudOverlay
import com.swordfish.touchinput.radial.sensors.TiltConfiguration
import com.swordfish.touchinput.radial.settings.LocalHudControlBoundsRecorder
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.ui.GlassSurface
import com.swordfish.touchinput.radial.ui.LemuroidButtonPressFeedback
import gg.padkit.PadKit
import gg.padkit.config.HapticFeedbackType
import gg.padkit.inputstate.InputState

@Composable
fun MobileGameScreen(viewModel: BaseGameScreenViewModel) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = constraints.maxWidth > constraints.maxHeight

        LaunchedEffect(isLandscape) {
            val orientation =
                if (isLandscape) {
                    TouchControllerSettingsManager.Orientation.LANDSCAPE
                } else {
                    TouchControllerSettingsManager.Orientation.PORTRAIT
                }
            viewModel.onScreenOrientationChanged(orientation)
        }

        val controllerConfigState = viewModel.getTouchControllerConfig().collectAsState(null)
        val touchControlsVisibleState = viewModel.isTouchControllerVisible().collectAsState(false)
        val touchControllerSettingsState =
            viewModel
                .getTouchControlsSettings(LocalDensity.current, WindowInsets.displayCutout)
                .collectAsState(null)

        val touchControllerSettings = touchControllerSettingsState.value
        val currentControllerConfig = controllerConfigState.value

        val tiltConfiguration = viewModel.getTiltConfiguration().collectAsState(TiltConfiguration.Disabled)
        val tiltSimulatedStates = viewModel.getSimulatedTiltEvents().collectAsState(InputState())
        val tiltSimulatedControls = remember { derivedStateOf { tiltConfiguration.value.controlIds() } }

        val touchGamePads = currentControllerConfig?.getTouchControllerConfig()
        val leftGamePad = touchGamePads?.leftComposable
        val rightGamePad = touchGamePads?.rightComposable
        val centerGamePad = touchGamePads?.centerComposable

        val hapticFeedbackMode =
            viewModel
                .getTouchHapticFeedbackMode()
                .collectAsState(HapticFeedbackMode.NONE)

        val padHapticFeedback =
            when (hapticFeedbackMode.value) {
                HapticFeedbackMode.NONE -> HapticFeedbackType.NONE
                HapticFeedbackMode.PRESS -> HapticFeedbackType.PRESS
                HapticFeedbackMode.PRESS_RELEASE -> HapticFeedbackType.PRESS_RELEASE
            }

        val showAdvancedHudEditor = viewModel.isAdvancedHudEditorShown().collectAsState(false).value
        val density = LocalDensity.current
        val measuredControlBounds =
            remember(currentControllerConfig?.touchControllerID, isLandscape) {
                mutableStateMapOf<String, Rect>()
            }
        var editorDraft by remember { mutableStateOf<TouchControllerSettingsManager.Settings?>(null) }
        var editorBaseline by remember { mutableStateOf<TouchControllerSettingsManager.Settings?>(null) }
        var showHiddenInEditor by remember { mutableStateOf(true) }

        LaunchedEffect(isLandscape, currentControllerConfig?.touchControllerID) {
            editorDraft = null
            editorBaseline = null
            viewModel.showAdvancedHudEditor(false)
        }

        val effectiveSettings =
            if (showAdvancedHudEditor) {
                editorDraft ?: touchControllerSettings
            } else {
                touchControllerSettings
            }
        val touchControlsVisible = touchControlsVisibleState.value
        val isCustomHudActive = effectiveSettings?.customHudEnabled == true
        val applyCustomViewport = isCustomHudActive && touchControlsVisible

        LaunchedEffect(touchControlsVisible) {
            if (!touchControlsVisible && showAdvancedHudEditor) {
                editorDraft = null
                editorBaseline = null
                viewModel.showAdvancedHudEditor(false)
            }
        }

        val fullScreenPosition = remember { mutableStateOf<Rect?>(null) }
        val viewportPosition = remember { mutableStateOf<Rect?>(null) }
        val defaultViewportPosition = remember { mutableStateOf<Rect?>(null) }

        PadKit(
            modifier = Modifier.fillMaxSize(),
            onInputEvents = {
                if (!showAdvancedHudEditor) {
                    viewModel.handleVirtualInputEvent(it)
                }
            },
            hapticFeedbackType = padHapticFeedback,
            simulatedState = tiltSimulatedStates,
            simulatedControlIds = tiltSimulatedControls,
        ) {
            val localContext = LocalContext.current
            val lifecycle = LocalLifecycleOwner.current

            AndroidView(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { fullScreenPosition.value = it.boundsInRoot() },
                factory = {
                    viewModel.createRetroView(localContext, lifecycle)
                },
            )

            val fullPos = fullScreenPosition.value
            val viewPos = viewportPosition.value

            LaunchedEffect(fullPos, viewPos) {
                val gameView = viewModel.retroGameView.retroGameViewFlow()
                if (fullPos == null || viewPos == null || fullPos.width <= 1f || fullPos.height <= 1f) {
                    return@LaunchedEffect
                }
                val viewport =
                    RectF(
                        (viewPos.left - fullPos.left) / fullPos.width,
                        (viewPos.top - fullPos.top) / fullPos.height,
                        (viewPos.right - fullPos.left) / fullPos.width,
                        (viewPos.bottom - fullPos.top) / fullPos.height,
                    )
                gameView.viewport = viewport
            }

            ConstraintLayout(
                modifier = Modifier.fillMaxSize(),
                constraintSet =
                    GameScreenLayout.buildConstraintSet(
                        isLandscape = isLandscape,
                        allowTouchOverlay = currentControllerConfig?.allowTouchOverlay ?: true,
                        customViewport = if (applyCustomViewport) effectiveSettings?.customViewport else null,
                    ),
            ) {
                Box(
                    modifier =
                        Modifier
                            .layoutId(GameScreenLayout.CONSTRAINTS_GAME_VIEW)
                            .let { mod ->
                                if (!applyCustomViewport) {
                                    mod.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
                                } else {
                                    mod
                                }
                            }
                            .onGloballyPositioned {
                                val bounds = it.boundsInRoot()
                                viewportPosition.value = bounds
                                if (!applyCustomViewport) {
                                    defaultViewportPosition.value = bounds
                                }
                            },
                )

                val isVisible =
                    effectiveSettings != null &&
                        currentControllerConfig != null &&
                        touchControlsVisible

                if (isVisible) {
                    val isFfEnabled = viewModel.isFastForwardEnabled.collectAsState().value
                    CompositionLocalProvider(
                        LocalLemuroidPadTheme provides LemuroidPadTheme(),
                        LocalFastForwardEnabled provides isFfEnabled,
                    ) {
                        if (isCustomHudActive) {
                            CustomHudOverlay(
                                modifier = Modifier.fillMaxSize(),
                                controllerId = currentControllerConfig.touchControllerID,
                                settings = effectiveSettings,
                                isEditorMode = showAdvancedHudEditor,
                                showHiddenInEditor = showHiddenInEditor,
                            )
                        } else {
                            if (!isLandscape) {
                                PadContainer(
                                    modifier = Modifier.layoutId(GameScreenLayout.CONSTRAINTS_BOTTOM_CONTAINER),
                                )
                            } else if (!currentControllerConfig.allowTouchOverlay) {
                                PadContainer(
                                    modifier = Modifier.layoutId(GameScreenLayout.CONSTRAINTS_LEFT_CONTAINER),
                                )
                                PadContainer(
                                    modifier = Modifier.layoutId(GameScreenLayout.CONSTRAINTS_RIGHT_CONTAINER),
                                )
                            }

                            CompositionLocalProvider(
                                LocalHudControlBoundsRecorder provides { id, rect ->
                                    measuredControlBounds[id] = rect
                                },
                            ) {
                                leftGamePad?.invoke(
                                    this,
                                    Modifier.layoutId(GameScreenLayout.CONSTRAINTS_LEFT_PAD),
                                    effectiveSettings,
                                )
                                rightGamePad?.invoke(
                                    this,
                                    Modifier.layoutId(GameScreenLayout.CONSTRAINTS_RIGHT_PAD),
                                    effectiveSettings,
                                )
                                centerGamePad?.invoke(
                                    this,
                                    Modifier.layoutId(GameScreenLayout.CONSTRAINTS_CENTER_PAD),
                                    effectiveSettings,
                                )
                            }
                        }

                        GameScreenRunningCentralMenu(
                            modifier = Modifier.layoutId(GameScreenLayout.CONSTRAINTS_GAME_CONTAINER),
                            controllerConfig = currentControllerConfig,
                            touchControllerSettings = effectiveSettings,
                            viewModel = viewModel,
                            onOpenAdvancedHud = {
                                val baseline =
                                    buildBaselineCustomSettings(
                                        baseSettings = effectiveSettings,
                                        controllerId = currentControllerConfig.touchControllerID,
                                        measuredControlBounds = measuredControlBounds.toMap(),
                                        fullScreenRect = fullScreenPosition.value,
                                        viewportRect =
                                            if (effectiveSettings.customHudEnabled) {
                                                defaultViewportPosition.value
                                            } else {
                                                viewportPosition.value
                                            },
                                        density = density,
                                        isLandscape = isLandscape,
                                        allowTouchOverlay = currentControllerConfig.allowTouchOverlay,
                                    )
                                editorBaseline = baseline
                                editorDraft =
                                    if (effectiveSettings.customHudEnabled) {
                                        mergeMissingHudOverrides(effectiveSettings, baseline)
                                    } else {
                                        baseline
                                    }
                                showHiddenInEditor = true
                                viewModel.showAdvancedHudEditor(true)
                            },
                        )
                    }
                }
            }
        }

        val activeControllerConfig = currentControllerConfig
        val activeDraft = editorDraft
        val activeBaseline = editorBaseline
        if (showAdvancedHudEditor && activeControllerConfig != null && activeDraft != null && activeBaseline != null) {
            AdvancedHudEditorOverlay(
                controllerId = activeControllerConfig.touchControllerID,
                draft = activeDraft,
                baseline = activeBaseline,
                showHiddenInEditor = showHiddenInEditor,
                onToggleShowHidden = { showHiddenInEditor = !showHiddenInEditor },
                onDraftChange = { editorDraft = it },
                onResetAll = {
                    viewModel.resetTouchControls()
                    editorDraft = null
                    editorBaseline = null
                    viewModel.showAdvancedHudEditor(false)
                },
                onCancel = {
                    editorDraft = null
                    editorBaseline = null
                    viewModel.showAdvancedHudEditor(false)
                },
                onSave = {
                    viewModel.updateTouchControllerSettings(activeDraft)
                    editorDraft = null
                    editorBaseline = null
                    viewModel.showAdvancedHudEditor(false)
                },
            )
        }

        val isLoading =
            viewModel.loadingState
                .collectAsState(true)
                .value

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        val showSpeedDialog = viewModel.showFastForwardSpeedDialog.collectAsState().value
        if (showSpeedDialog) {
            val currentSpeed = viewModel.getFastForwardSpeed()
            val speeds = listOf(2, 3, 4, 5)
            AlertDialog(
                onDismissRequest = { viewModel.showFastForwardSpeedDialog.value = false },
                title = { Text(stringResource(com.swordfish.lemuroid.R.string.settings_title_fast_forward_speed)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                    ) {
                        speeds.forEach { speed ->
                            val isSelected = (currentSpeed == speed)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .selectable(
                                        role = Role.RadioButton,
                                        selected = isSelected,
                                        onClick = {
                                            viewModel.updateFastForwardSpeed(speed)
                                            viewModel.showFastForwardSpeedDialog.value = false
                                        },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null,
                                )
                                Text(
                                    text = "${speed}x",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {},
            )
        }

        com.swordfish.lemuroid.app.shared.retroachievements.AchievementPopupOverlay()
    }
}

@Composable
private fun PadContainer(modifier: Modifier = Modifier) {
    val theme = LocalLemuroidPadTheme.current
    GlassSurface(
        modifier = modifier,
        cornerRadius = theme.level0CornerRadius,
        fillColor = theme.level0Fill,
        shadowColor = theme.level0Shadow,
        shadowWidth = theme.level0ShadowWidth,
    )
}

@Composable
private fun GameScreenRunningCentralMenu(
    modifier: Modifier = Modifier,
    viewModel: BaseGameScreenViewModel,
    touchControllerSettings: TouchControllerSettingsManager.Settings,
    controllerConfig: ControllerConfig,
    onOpenAdvancedHud: () -> Unit,
) {
    val menuPressed = viewModel.isMenuPressed().collectAsState(false)
    Box(
        modifier = modifier.wrapContentSize(),
        contentAlignment = Alignment.Center,
    ) {
        LemuroidButtonPressFeedback(
            pressed = menuPressed.value,
            animationDurationMillis = MENU_LOADING_ANIMATION_MILLIS,
            icon = R.drawable.button_menu,
        )
        MenuEditTouchControls(viewModel, controllerConfig, touchControllerSettings, onOpenAdvancedHud)
    }
}

@Composable
private fun MenuEditTouchControls(
    viewModel: BaseGameScreenViewModel,
    controllerConfig: ControllerConfig,
    touchControllerSettings: TouchControllerSettingsManager.Settings,
    onOpenAdvancedHud: () -> Unit,
) {
    val showEditControls = viewModel.isEditControlShown().collectAsState(false)
    if (!showEditControls.value) return

    Dialog(onDismissRequest = { viewModel.showEditControls(false) }) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
        ) {
            val isCustomActive = touchControllerSettings.customHudEnabled
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MenuEditTouchControlRow(Icons.Default.OpenInFull, "Scale", 0f) {
                    Slider(
                        value = touchControllerSettings.scale,
                        enabled = !isCustomActive,
                        onValueChange = {
                            viewModel.updateTouchControllerSettings(
                                touchControllerSettings.copy(scale = it),
                            )
                        },
                    )
                }
                MenuEditTouchControlRow(Icons.Default.Height, "Horizontal Margin", 90f) {
                    Slider(
                        value = touchControllerSettings.marginX,
                        enabled = !isCustomActive,
                        onValueChange = {
                            viewModel.updateTouchControllerSettings(
                                touchControllerSettings.copy(marginX = it),
                            )
                        },
                    )
                }
                MenuEditTouchControlRow(Icons.Default.Height, "Vertical Margin", 0f) {
                    Slider(
                        value = touchControllerSettings.marginY,
                        enabled = !isCustomActive,
                        onValueChange = {
                            viewModel.updateTouchControllerSettings(
                                touchControllerSettings.copy(marginY = it),
                            )
                        },
                    )
                }
                if (controllerConfig.allowTouchRotation) {
                    MenuEditTouchControlRow(Icons.Default.RotateLeft, "Rotate", 0f) {
                        Slider(
                            value = touchControllerSettings.rotation,
                            onValueChange = {
                                viewModel.updateTouchControllerSettings(
                                    touchControllerSettings.copy(rotation = it),
                                )
                            },
                        )
                    }
                }
                if (isCustomActive) {
                    Text(
                        text = stringResource(R.string.touch_hud_editor_custom_active_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                OutlinedButton(
                    onClick = onOpenAdvancedHud,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.touch_customize_button_advanced_hud))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(
                        onClick = { viewModel.resetTouchControls() },
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(text = stringResource(R.string.touch_customize_button_reset))
                    }
                    TextButton(
                        onClick = { viewModel.showEditControls(false) },
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(text = stringResource(R.string.touch_customize_button_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuEditTouchControlRow(
    icon: ImageVector,
    label: String,
    rotation: Float,
    slider: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            modifier = Modifier.rotate(rotation),
            imageVector = icon,
            contentDescription = label,
        )
        slider()
    }
}
