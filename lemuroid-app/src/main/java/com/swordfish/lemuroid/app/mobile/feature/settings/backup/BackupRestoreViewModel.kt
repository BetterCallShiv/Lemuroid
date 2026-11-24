package com.swordfish.lemuroid.app.mobile.feature.settings.backup

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.lib.backup.BackupEstimate
import com.swordfish.lemuroid.lib.backup.BackupManager
import com.swordfish.lemuroid.lib.backup.BackupOptions
import com.swordfish.lemuroid.lib.backup.BackupStats
import com.swordfish.lemuroid.lib.backup.InspectedBackupInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BackupSuccessInfo(
    val filePath: String,
    val fileSizeBytes: Long,
    val stats: BackupStats,
)

data class BackupRestoreUiState(
    val options: BackupOptions = BackupOptions(),
    val estimate: BackupEstimate? = null,
    val isEstimating: Boolean = false,
    val isOperating: Boolean = false,
    val isRestoringOperation: Boolean = false,
    val isAnalyzingOperation: Boolean = false,
    val operationStep: String = "",
    val operationProgress: Float = 0f,
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = 0L,
    val estimatedRemainingSeconds: Long = -1L,
    val inspectedBackup: InspectedBackupInfo? = null,
    val pendingRestoreUri: Uri? = null,
    val backupSuccess: BackupSuccessInfo? = null,
    val overwriteSavesOnRestore: Boolean = true,
    val showCreateDialog: Boolean = false,
    val showConfirmRestoreDialog: Boolean = false,
    val resultMessage: String? = null,
    val errorMessage: String? = null,
)

class BackupRestoreViewModel(
    application: Application,
    private val backupManager: BackupManager,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

    private var operationStartTime = 0L

    init {
        refreshEstimate()
    }

    fun openCreateDialog() {
        _uiState.value = _uiState.value.copy(showCreateDialog = true)
        refreshEstimate()
    }

    fun dismissCreateDialog() {
        _uiState.value = _uiState.value.copy(showCreateDialog = false)
    }

    fun updateOptions(newOptions: BackupOptions) {
        _uiState.value = _uiState.value.copy(options = newOptions)
        refreshEstimate()
    }

    fun setOverwriteSavesOnRestore(overwrite: Boolean) {
        _uiState.value = _uiState.value.copy(overwriteSavesOnRestore = overwrite)
    }

    fun refreshEstimate() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isEstimating = true)
            val currentOptions = _uiState.value.options
            val est = backupManager.estimateBackup(currentOptions)
            _uiState.value = _uiState.value.copy(estimate = est, isEstimating = false)
        }
    }

    fun executeBackup(outputUri: Uri) {
        viewModelScope.launch {
            operationStartTime = System.currentTimeMillis()
            val app = getApplication<Application>()
            _uiState.value = _uiState.value.copy(
                isOperating = true,
                isRestoringOperation = false,
                isAnalyzingOperation = false,
                operationStep = app.getString(com.swordfish.lemuroid.R.string.backup_step_preparing),
                operationProgress = 0f,
                bytesProcessed = 0L,
                totalBytes = 0L,
                estimatedRemainingSeconds = -1L,
                showCreateDialog = false,
                errorMessage = null,
                resultMessage = null,
                backupSuccess = null,
            )

            val options = _uiState.value.options
            val result = backupManager.createBackup(
                outputUri = outputUri,
                options = options,
                onProgress = { step, progress, bytesProcessed, totalBytes ->
                    val elapsedSeconds = (System.currentTimeMillis() - operationStartTime) / 1000.0
                    val remainingSeconds = if (elapsedSeconds > 1.5 && bytesProcessed > 1024 * 100 && totalBytes > bytesProcessed) {
                        val speed = bytesProcessed / elapsedSeconds
                        ((totalBytes - bytesProcessed) / speed).toLong().coerceIn(1L, 3600L)
                    } else -1L

                    _uiState.value = _uiState.value.copy(
                        operationStep = step,
                        operationProgress = progress,
                        bytesProcessed = bytesProcessed,
                        totalBytes = totalBytes,
                        estimatedRemainingSeconds = remainingSeconds,
                    )
                },
            )

            result.fold(
                onSuccess = { manifest ->
                    val (fullPath, fileSizeBytes) = resolveUriDetails(outputUri)
                    val successInfo = BackupSuccessInfo(
                        filePath = fullPath,
                        fileSizeBytes = fileSizeBytes,
                        stats = manifest.stats,
                    )
                    _uiState.value = _uiState.value.copy(
                        isOperating = false,
                        backupSuccess = successInfo,
                    )
                },
                onFailure = { error ->
                    val errorMsg = error.localizedMessage ?: app.getString(com.swordfish.lemuroid.R.string.backup_unknown_error)
                    _uiState.value = _uiState.value.copy(
                        isOperating = false,
                        errorMessage = app.getString(com.swordfish.lemuroid.R.string.backup_error_failed_prefix, errorMsg),
                    )
                },
            )
        }
    }

    fun onRestoreFileSelected(inputUri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            _uiState.value = _uiState.value.copy(
                isOperating = true,
                isRestoringOperation = false,
                isAnalyzingOperation = true,
                operationStep = "",
                operationProgress = 0f,
                bytesProcessed = 0L,
                totalBytes = 0L,
                estimatedRemainingSeconds = -1L,
                errorMessage = null,
                resultMessage = null,
                backupSuccess = null,
            )

            val inspected = backupManager.inspectBackup(inputUri)
            if (inspected != null) {
                _uiState.value = _uiState.value.copy(
                    isOperating = false,
                    isAnalyzingOperation = false,
                    inspectedBackup = inspected,
                    pendingRestoreUri = inputUri,
                    showConfirmRestoreDialog = true,
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isOperating = false,
                    isAnalyzingOperation = false,
                    errorMessage = app.getString(com.swordfish.lemuroid.R.string.backup_error_invalid_archive),
                )
            }
        }
    }

    fun dismissConfirmRestoreDialog() {
        _uiState.value = _uiState.value.copy(
            showConfirmRestoreDialog = false,
            pendingRestoreUri = null,
            inspectedBackup = null,
        )
    }

    fun confirmRestore() {
        val uri = _uiState.value.pendingRestoreUri ?: return
        val overwrite = _uiState.value.overwriteSavesOnRestore
        val app = getApplication<Application>()

        viewModelScope.launch {
            operationStartTime = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(
                showConfirmRestoreDialog = false,
                isOperating = true,
                isRestoringOperation = true,
                isAnalyzingOperation = false,
                operationStep = app.getString(com.swordfish.lemuroid.R.string.backup_step_restoring),
                operationProgress = 0f,
                bytesProcessed = 0L,
                totalBytes = 0L,
                estimatedRemainingSeconds = -1L,
            )

            val result = backupManager.restoreBackup(
                inputUri = uri,
                overwriteSaves = overwrite,
                onProgress = { step, progress, bytesProcessed, totalBytes ->
                    val elapsedSeconds = (System.currentTimeMillis() - operationStartTime) / 1000.0
                    val remainingSeconds = if (elapsedSeconds > 1.5 && bytesProcessed > 1024 * 100 && totalBytes > bytesProcessed) {
                        val speed = bytesProcessed / elapsedSeconds
                        ((totalBytes - bytesProcessed) / speed).toLong().coerceIn(1L, 3600L)
                    } else -1L

                    _uiState.value = _uiState.value.copy(
                        operationStep = step,
                        operationProgress = progress,
                        bytesProcessed = bytesProcessed,
                        totalBytes = totalBytes,
                        estimatedRemainingSeconds = remainingSeconds,
                    )
                },
            )

            if (result.error != null) {
                _uiState.value = _uiState.value.copy(
                    isOperating = false,
                    errorMessage = app.getString(com.swordfish.lemuroid.R.string.backup_error_restore_prefix, result.error),
                )
            } else {
                val summary = buildString {
                    append(app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_header))
                    if (result.savesRestored > 0) {
                        append(if (result.savesRestored == 1) app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_saves_singular) else app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_saves_plural, result.savesRestored))
                    }
                    if (result.statesRestored > 0) {
                        append(if (result.statesRestored == 1) app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_states_singular) else app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_states_plural, result.statesRestored))
                    }
                    if (result.userDataRestored > 0) {
                        append(if (result.userDataRestored == 1) app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_games_singular) else app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_games_plural, result.userDataRestored))
                    }
                    if (result.gameDetailsRestored > 0) {
                        append(if (result.gameDetailsRestored == 1) app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_details_singular) else app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_details_plural, result.gameDetailsRestored))
                    }
                    if (result.cheatsRestored > 0) {
                        append(if (result.cheatsRestored == 1) app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_cheats_singular) else app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_cheats_plural, result.cheatsRestored))
                    }
                    if (result.mediaRestored > 0) {
                        append(if (result.mediaRestored == 1) app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_media_singular) else app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_media_plural, result.mediaRestored))
                    }
                    if (result.settingsRestored) {
                        append(app.getString(com.swordfish.lemuroid.R.string.backup_restore_summary_settings))
                    }
                }
                _uiState.value = _uiState.value.copy(
                    isOperating = false,
                    resultMessage = summary,
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(
            resultMessage = null,
            errorMessage = null,
            backupSuccess = null,
        )
    }

    fun formatSize(bytes: Long): String {
        return Formatter.formatFileSize(getApplication(), bytes)
    }

    private fun resolveUriDetails(uri: Uri): Pair<String, Long> {
        val app = getApplication<Application>()
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "alphalemuroid-backup.zip"
        var size = 0L

        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx != -1) cursor.getString(nameIdx)?.let { name = it }
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }
        }

        if (size <= 0L) {
            size = runCatching {
                app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull() ?: 0L
        }

        val fullPath = resolveFullPath(uri, name)
        return Pair(fullPath, size)
    }

    private fun resolveFullPath(uri: Uri, fileName: String): String {
        val decoded = runCatching { Uri.decode(uri.toString()) }.getOrDefault(uri.toString())
        val path = uri.path ?: ""
        val decodedPath = runCatching { Uri.decode(path) }.getOrDefault(path)

        return when {
            decoded.contains("primary:") -> {
                val rel = decoded.substringAfter("primary:").trimStart('/')
                "/storage/emulated/0/$rel"
            }
            decodedPath.contains("primary:") -> {
                val rel = decodedPath.substringAfter("primary:").trimStart('/')
                "/storage/emulated/0/$rel"
            }
            decoded.contains("raw:") -> {
                decoded.substringAfter("raw:")
            }
            decodedPath.contains("raw:") -> {
                decodedPath.substringAfter("raw:")
            }
            decoded.contains("/storage/") -> {
                "/storage/" + decoded.substringAfter("/storage/")
            }
            decodedPath.contains("/storage/") -> {
                "/storage/" + decodedPath.substringAfter("/storage/")
            }
            decoded.contains("download", ignoreCase = true) -> {
                "/storage/emulated/0/Download/$fileName"
            }
            decoded.contains("document", ignoreCase = true) -> {
                "/storage/emulated/0/Documents/$fileName"
            }
            else -> {
                "/storage/emulated/0/$fileName"
            }
        }
    }

    class Factory(
        private val application: Application,
        private val backupManager: BackupManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BackupRestoreViewModel(application, backupManager) as T
        }
    }
}
