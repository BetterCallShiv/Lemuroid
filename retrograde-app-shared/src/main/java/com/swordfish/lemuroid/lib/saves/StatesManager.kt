package com.swordfish.lemuroid.lib.saves

import com.swordfish.lemuroid.common.kotlin.readBytesUncompressedAtomic
import com.swordfish.lemuroid.common.kotlin.readTextAtomic
import com.swordfish.lemuroid.common.kotlin.runCatchingWithRetry
import com.swordfish.lemuroid.common.kotlin.writeBytesCompressedAtomic
import com.swordfish.lemuroid.common.kotlin.writeTextAtomic
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class StatesManager(private val directoriesManager: DirectoriesManager) {
    suspend fun getSlotSave(
        game: Game,
        coreID: CoreID,
        index: Int,
    ): SaveState? =
        withContext(Dispatchers.IO) {
            assert(index >= 0)
            getSaveState(getSlotSaveFileName(game, index), coreID.coreName)
        }

    suspend fun setSlotSave(
        game: Game,
        saveState: SaveState,
        coreID: CoreID,
        index: Int,
    ) = withContext(Dispatchers.IO) {
        assert(index >= 0)
        val fileName = getSlotSaveFileName(game, index)
        val existingTitle = runCatching {
            val metaFile = getMetadataStateFile(fileName, coreID.coreName)
            if (metaFile.exists()) {
                Json.Default.decodeFromString(
                    SaveState.Metadata.serializer(),
                    metaFile.readTextAtomic(),
                ).customTitle
            } else null
        }.getOrNull()

        val metadataToSave = if (saveState.metadata.customTitle == null && existingTitle != null) {
            saveState.metadata.copy(customTitle = existingTitle)
        } else {
            saveState.metadata
        }

        setSaveState(fileName, coreID.coreName, SaveState(saveState.state, metadataToSave))
    }

    suspend fun renameSlot(
        game: Game,
        coreID: CoreID,
        index: Int,
        newTitle: String,
    ) = withContext(Dispatchers.IO) {
        assert(index >= 0)
        val fileName = getSlotSaveFileName(game, index)
        val metadataFile = getMetadataStateFile(fileName, coreID.coreName)
        val existingMetadata = runCatching {
            if (metadataFile.exists()) {
                Json.Default.decodeFromString(
                    SaveState.Metadata.serializer(),
                    metadataFile.readTextAtomic(),
                )
            } else null
        }.getOrNull() ?: SaveState.Metadata()

        val updatedMetadata = existingMetadata.copy(customTitle = newTitle.trim().ifEmpty { null })
        writeMetadataToDisk(fileName, coreID.coreName, updatedMetadata)
    }

    suspend fun deleteSlot(
        game: Game,
        coreID: CoreID,
        index: Int,
    ) = withContext(Dispatchers.IO) {
        assert(index >= 0)
        val stateFile = getStateFile(getSlotSaveFileName(game, index), coreID.coreName)
        val metadataFile = getMetadataStateFile(getSlotSaveFileName(game, index), coreID.coreName)
        val previewDir = File(directoriesManager.getStatesPreviewDirectory(), coreID.coreName)
        val previewFile = File(previewDir, "${game.fileName}.slot${index + 1}.jpg")

        if (stateFile.exists()) stateFile.delete()
        if (metadataFile.exists()) metadataFile.delete()
        if (previewFile.exists()) previewFile.delete()
    }

    suspend fun getAutoSaveInfo(
        game: Game,
        coreID: CoreID,
    ): SaveInfo =
        withContext(Dispatchers.IO) {
            val autoSaveFile = getStateFile(getAutoSaveFileName(game), coreID.coreName)
            val autoSaveHasData = autoSaveFile.length() > 0
            SaveInfo(autoSaveFile.exists() && autoSaveHasData, autoSaveFile.lastModified())
        }

    suspend fun getAutoSave(
        game: Game,
        coreID: CoreID,
    ) = withContext(Dispatchers.IO) {
        getSaveState(getAutoSaveFileName(game), coreID.coreName)
    }

    suspend fun setAutoSave(
        game: Game,
        coreID: CoreID,
        saveState: SaveState,
    ) = withContext(Dispatchers.IO) {
        setSaveState(getAutoSaveFileName(game), coreID.coreName, saveState)
    }

    suspend fun getSavedSlotsInfo(
        game: Game,
        coreID: CoreID,
    ): List<SaveInfo> =
        withContext(Dispatchers.IO) {
            val statesDir = File(directoriesManager.getStatesDirectory(), coreID.coreName)
            val slotPattern = Regex("""^${Regex.escape(game.fileName)}\.slot(\d+)$""")
            val existingIndices = mutableSetOf<Int>()

            if (statesDir.exists()) {
                statesDir.listFiles()?.forEach { file ->
                    val match = slotPattern.find(file.name)
                    if (match != null) {
                        val slotNum = match.groupValues[1].toIntOrNull()
                        if (slotNum != null && slotNum > 0) {
                            existingIndices.add(slotNum - 1)
                        }
                    }
                }
            }

            val result = mutableListOf<SaveInfo>()
            existingIndices.sorted().forEach { idx ->
                val stateFile = getStateFile(getSlotSaveFileName(game, idx), coreID.coreName)
                val metadataFile = getMetadataStateFile(getSlotSaveFileName(game, idx), coreID.coreName)
                val customTitle = runCatching {
                    if (metadataFile.exists()) {
                        Json.Default.decodeFromString(
                            SaveState.Metadata.serializer(),
                            metadataFile.readTextAtomic(),
                        ).customTitle
                    } else null
                }.getOrNull()

                result.add(
                    SaveInfo(
                        exists = stateFile.exists(),
                        date = stateFile.lastModified(),
                        slotIndex = idx,
                        customTitle = customTitle,
                    ),
                )
            }

            val nextSlotIndex = if (existingIndices.isEmpty()) 0 else (existingIndices.maxOrNull()!! + 1)
            result.add(
                SaveInfo(
                    exists = false,
                    date = 0L,
                    slotIndex = nextSlotIndex,
                    customTitle = null,
                ),
            )

            result
        }

    private suspend fun getSaveState(
        fileName: String,
        coreName: String,
    ): SaveState? {
        return runCatchingWithRetry(FILE_ACCESS_RETRIES) {
            val saveFile = getStateFile(fileName, coreName)
            val metadataFile = getMetadataStateFile(fileName, coreName)
            if (saveFile.exists()) {
                val byteArray = saveFile.readBytesUncompressedAtomic()
                val stateMetadata =
                    runCatching {
                        Json.Default.decodeFromString(
                            SaveState.Metadata.serializer(),
                            metadataFile.readTextAtomic(),
                        )
                    }
                SaveState(byteArray, stateMetadata.getOrNull() ?: SaveState.Metadata())
            } else {
                null
            }
        }.getOrNull()
    }

    private suspend fun setSaveState(
        fileName: String,
        coreName: String,
        saveState: SaveState,
    ) {
        runCatchingWithRetry(FILE_ACCESS_RETRIES) {
            writeStateToDisk(fileName, coreName, saveState.state)
            writeMetadataToDisk(fileName, coreName, saveState.metadata)
        }
    }

    private fun writeMetadataToDisk(
        fileName: String,
        coreName: String,
        metadata: SaveState.Metadata,
    ) {
        val metadataFile = getMetadataStateFile(fileName, coreName)
        metadataFile.writeTextAtomic(Json.encodeToString(SaveState.Metadata.serializer(), metadata))
    }

    private fun writeStateToDisk(
        fileName: String,
        coreName: String,
        stateArray: ByteArray,
    ) {
        val saveFile = getStateFile(fileName, coreName)
        saveFile.writeBytesCompressedAtomic(stateArray)
    }

    private fun getStateFile(
        fileName: String,
        coreName: String,
    ): File {
        val statesDirectories = File(directoriesManager.getStatesDirectory(), coreName)
        statesDirectories.mkdirs()
        return File(statesDirectories, fileName)
    }

    private fun getMetadataStateFile(
        stateFileName: String,
        coreName: String,
    ): File {
        val statesDirectories = File(directoriesManager.getStatesDirectory(), coreName)
        statesDirectories.mkdirs()
        return File(statesDirectories, "$stateFileName.metadata")
    }

    private fun getAutoSaveFileName(game: Game) = "${game.fileName}.state"

    private fun getSlotSaveFileName(
        game: Game,
        index: Int,
    ) = "${game.fileName}.slot${index + 1}"

    companion object {
        const val MAX_STATES = 4
        private const val FILE_ACCESS_RETRIES = 3
    }
}
