package com.swordfish.lemuroid.app.mobile.feature.settings.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidCardSettingsGroup
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsMenuLink
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsPage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupRestoreSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: BackupRestoreViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            viewModel.executeBackup(uri)
        }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.onRestoreFileSelected(uri)
        }
    }

    LemuroidSettingsPage(modifier = modifier.fillMaxSize()) {
        LemuroidCardSettingsGroup(
            title = { Text(text = stringResource(id = R.string.backup_category_actions)) },
        ) {
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(id = R.string.backup_create_title)) },
                subtitle = { Text(text = stringResource(id = R.string.backup_create_description)) },
                enabled = !uiState.isOperating,
                onClick = { viewModel.openCreateDialog() },
            )

            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(id = R.string.backup_restore_title)) },
                subtitle = { Text(text = stringResource(id = R.string.backup_restore_description)) },
                enabled = !uiState.isOperating,
                onClick = {
                    restoreBackupLauncher.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"),
                    )
                },
            )
        }
    }

    if (uiState.showCreateDialog) {
        CreateBackupDialog(
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = { viewModel.dismissCreateDialog() },
            onConfirm = {
                val timeStamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                val defaultFileName = "alphalemuroid-backup-$timeStamp.zip"
                createBackupLauncher.launch(defaultFileName)
            },
        )
    }

    if (uiState.showConfirmRestoreDialog && uiState.inspectedBackup != null) {
        ConfirmRestoreDialog(
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = { viewModel.dismissConfirmRestoreDialog() },
            onConfirm = { viewModel.confirmRestore() },
        )
    }

    if (uiState.isOperating) {
        OperationProgressDialog(
            isRestore = uiState.isRestoringOperation,
            isAnalyzing = uiState.isAnalyzingOperation,
            step = uiState.operationStep,
            progress = uiState.operationProgress,
            bytesProcessed = uiState.bytesProcessed,
            totalBytes = uiState.totalBytes,
            estimatedRemainingSeconds = uiState.estimatedRemainingSeconds,
            formatSize = { viewModel.formatSize(it) },
        )
    }

    if (uiState.backupSuccess != null) {
        BackupSuccessDialog(
            info = uiState.backupSuccess!!,
            formatSize = { viewModel.formatSize(it) },
            onDismiss = { viewModel.clearMessages() },
        )
    }

    if (uiState.resultMessage != null || uiState.errorMessage != null) {
        ResultDialog(
            message = uiState.resultMessage ?: uiState.errorMessage ?: "",
            isError = uiState.errorMessage != null,
            onDismiss = { viewModel.clearMessages() },
        )
    }
}

@Composable
private fun CreateBackupDialog(
    uiState: BackupRestoreUiState,
    viewModel: BackupRestoreViewModel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val options = uiState.options
    val estimate = uiState.estimate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_create_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                DialogCheckboxRow(
                    title = stringResource(R.string.backup_options_saves),
                    subtitle = stringResource(R.string.backup_options_saves_desc),
                    checked = options.includeSaves,
                    onCheckedChange = { viewModel.updateOptions(options.copy(includeSaves = it, includeStates = it)) },
                )

                DialogCheckboxRow(
                    title = stringResource(R.string.backup_options_userdata),
                    subtitle = stringResource(R.string.backup_options_userdata_desc),
                    checked = options.includeUserData,
                    onCheckedChange = { viewModel.updateOptions(options.copy(includeUserData = it, includeCheats = it)) },
                )

                DialogCheckboxRow(
                    title = stringResource(R.string.backup_options_settings),
                    subtitle = stringResource(R.string.backup_options_settings_desc),
                    checked = options.includeSettings,
                    onCheckedChange = { viewModel.updateOptions(options.copy(includeSettings = it)) },
                )

                DialogCheckboxRow(
                    title = stringResource(R.string.backup_options_credentials),
                    subtitle = stringResource(R.string.backup_options_credentials_desc),
                    checked = options.includeCredentials,
                    onCheckedChange = { viewModel.updateOptions(options.copy(includeCredentials = it)) },
                )

                DialogCheckboxRow(
                    title = stringResource(R.string.backup_options_media),
                    subtitle = stringResource(R.string.backup_options_media_desc),
                    checked = options.includeMedia,
                    onCheckedChange = { viewModel.updateOptions(options.copy(includeMedia = it)) },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                if (estimate != null) {
                    val sizeFormatted = viewModel.formatSize(estimate.estimatedCompressedBytes)
                    val availableFormatted = viewModel.formatSize(estimate.availableBytes)

                    Text(
                        text = stringResource(R.string.backup_estimated_size, sizeFormatted),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.backup_available_space, availableFormatted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (!estimate.hasEnoughSpace) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.backup_insufficient_space, sizeFormatted, availableFormatted),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.backup_calculating_size), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = estimate?.hasEnoughSpace == true && !uiState.isEstimating,
            ) {
                Text(stringResource(R.string.backup_action_backup))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ConfirmRestoreDialog(
    uiState: BackupRestoreUiState,
    viewModel: BackupRestoreViewModel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val inspected = uiState.inspectedBackup ?: return
    val manifest = inspected.manifest
    val stats = manifest.stats
    val dateFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(manifest.createdAt))

    val archiveSizeFormatted = viewModel.formatSize(inspected.fileSizeBytes)
    val uncompressedEstimatedBytes = if (stats.totalUncompressedBytes > 0) {
        stats.totalUncompressedBytes
    } else {
        (inspected.fileSizeBytes * 1.05).toLong()
    }
    val uncompressedFormatted = viewModel.formatSize(uncompressedEstimatedBytes)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.FolderZip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.backup_restore_confirm_title))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.backup_archive_size_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = archiveSizeFormatted,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End,
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.backup_estimated_restore_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uncompressedFormatted,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val contentsText = buildString {
                    append(if (stats.saveCount == 1) stringResource(R.string.backup_item_save_singular) else stringResource(R.string.backup_item_save_plural, stats.saveCount))
                    append("\n")
                    append(if (stats.stateCount == 1) stringResource(R.string.backup_item_state_singular) else stringResource(R.string.backup_item_state_plural, stats.stateCount))
                    append("\n")
                    append(if (stats.userDataCount == 1) stringResource(R.string.backup_item_userdata_singular) else stringResource(R.string.backup_item_userdata_plural, stats.userDataCount))
                    if (stats.cheatCount > 0) {
                        append("\n")
                        append(if (stats.cheatCount == 1) stringResource(R.string.backup_item_cheat_singular) else stringResource(R.string.backup_item_cheat_plural, stats.cheatCount))
                    }
                    if (stats.gameDetailsCount > 0) {
                        append("\n")
                        append(if (stats.gameDetailsCount == 1) stringResource(R.string.backup_item_detail_singular) else stringResource(R.string.backup_item_detail_plural, stats.gameDetailsCount))
                    }
                    if (stats.collectionCount > 0) {
                        append("\n")
                        append(if (stats.collectionCount == 1) stringResource(R.string.backup_item_collection_singular) else stringResource(R.string.backup_item_collection_plural, stats.collectionCount))
                    }
                    if (stats.customCoverCount > 0) {
                        append("\n")
                        append(if (stats.customCoverCount == 1) stringResource(R.string.backup_item_custom_cover_singular) else stringResource(R.string.backup_item_custom_cover_plural, stats.customCoverCount))
                    }
                    if (stats.mediaCount > 0) {
                        append("\n")
                        append(if (stats.mediaCount == 1) stringResource(R.string.backup_item_media_singular) else stringResource(R.string.backup_item_media_plural, stats.mediaCount))
                    }
                    if (manifest.includesCredentials) {
                        append("\n")
                        append(stringResource(R.string.backup_item_credentials))
                    }
                }
                Text(
                    text = stringResource(
                        R.string.backup_restore_confirm_msg,
                        dateFormatted,
                        manifest.appVersion,
                        contentsText,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogCheckboxRow(
                    title = stringResource(R.string.backup_overwrite_saves),
                    subtitle = stringResource(R.string.backup_overwrite_saves_desc),
                    checked = uiState.overwriteSavesOnRestore,
                    onCheckedChange = { viewModel.setOverwriteSavesOnRestore(it) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.backup_action_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun OperationProgressDialog(
    isRestore: Boolean,
    isAnalyzing: Boolean = false,
    step: String,
    progress: Float,
    bytesProcessed: Long,
    totalBytes: Long,
    estimatedRemainingSeconds: Long,
    formatSize: (Long) -> String,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "progressAnimation",
    )

    Dialog(
        onDismissRequest = { /* Non-cancellable */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(72.dp),
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(72.dp),
                            strokeWidth = 4.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { if (progress > 0f) animatedProgress else 0.5f },
                            modifier = Modifier.size(72.dp),
                            strokeWidth = 4.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when {
                                isAnalyzing -> Icons.Outlined.FolderZip
                                isRestore -> Icons.Outlined.CloudDownload
                                else -> Icons.Outlined.CloudUpload
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val titleText = when {
                    isAnalyzing -> stringResource(R.string.backup_title_analyzing)
                    isRestore -> stringResource(R.string.backup_title_restoring)
                    else -> stringResource(R.string.backup_title_creating)
                }

                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

                if (!isAnalyzing && step.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!isAnalyzing) {
                    Spacer(modifier = Modifier.height(20.dp))

                    LinearProgressIndicator(
                        progress = { if (progress > 0f) animatedProgress else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        if (totalBytes > 0 && bytesProcessed > 0) {
                            Text(
                                text = "${formatSize(bytesProcessed)} / ${formatSize(totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (estimatedRemainingSeconds > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            val timeStr = if (estimatedRemainingSeconds >= 60) {
                                val mins = estimatedRemainingSeconds / 60
                                val secs = estimatedRemainingSeconds % 60
                                stringResource(R.string.backup_remaining_time_minutes, mins, secs)
                            } else {
                                stringResource(R.string.backup_remaining_time_seconds, estimatedRemainingSeconds)
                            }
                            Text(
                                text = timeStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = stringResource(R.string.backup_keep_open_notice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun BackupSuccessDialog(
    info: BackupSuccessInfo,
    formatSize: (Long) -> String,
    onDismiss: () -> Unit,
) {
    val stats = info.stats
    val sizeFormatted = formatSize(info.fileSizeBytes)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.backup_complete_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.backup_file_name_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = info.filePath,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.backup_archive_size_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = sizeFormatted,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.backup_archived_items_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = buildString {
                        if (stats.saveCount > 0) {
                            append(if (stats.saveCount == 1) stringResource(R.string.backup_item_save_singular) else stringResource(R.string.backup_item_save_plural, stats.saveCount))
                            append("\n")
                        }
                        append(if (stats.stateCount == 1) stringResource(R.string.backup_item_state_singular) else stringResource(R.string.backup_item_state_plural, stats.stateCount))
                        append("\n")
                        append(if (stats.userDataCount == 1) stringResource(R.string.backup_item_game_stats_singular) else stringResource(R.string.backup_item_game_stats_plural, stats.userDataCount))
                        if (stats.gameDetailsCount > 0) {
                            append("\n")
                            append(if (stats.gameDetailsCount == 1) stringResource(R.string.backup_item_detail_singular) else stringResource(R.string.backup_item_detail_plural, stats.gameDetailsCount))
                        }
                        if (stats.collectionCount > 0) {
                            append("\n")
                            append(if (stats.collectionCount == 1) stringResource(R.string.backup_item_collection_singular) else stringResource(R.string.backup_item_collection_plural, stats.collectionCount))
                        }
                        if (stats.customCoverCount > 0) {
                            append("\n")
                            append(if (stats.customCoverCount == 1) stringResource(R.string.backup_item_custom_cover_singular) else stringResource(R.string.backup_item_custom_cover_plural, stats.customCoverCount))
                        }
                        if (stats.cheatCount > 0) {
                            append("\n")
                            append(if (stats.cheatCount == 1) stringResource(R.string.backup_item_cheat_singular) else stringResource(R.string.backup_item_cheat_plural, stats.cheatCount))
                        }
                        if (stats.mediaCount > 0) {
                            append("\n")
                            append(if (stats.mediaCount == 1) stringResource(R.string.backup_item_media_singular) else stringResource(R.string.backup_item_media_plural, stats.mediaCount))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultDialog(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isError) stringResource(R.string.backup_dialog_error_title) else stringResource(R.string.backup_dialog_success_title)) },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}

@Composable
private fun DialogCheckboxRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
