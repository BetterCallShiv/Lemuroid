package com.swordfish.lemuroid.app.mobile.feature.gamemenu.states

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.GameMenuActivity
import com.swordfish.lemuroid.app.shared.gamemenu.GameMenuHelper
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class GameMenuStatesViewModel(
    private val application: Application,
    private val gameMenuRequest: GameMenuActivity.GameMenuRequest,
    private val statesManager: StatesManager,
    private val disableMissingEntries: Boolean,
    private val statesPreviewManager: StatesPreviewManager,
) : ViewModel() {
    class Factory(
        private val application: Application,
        private val gameMenuRequest: GameMenuActivity.GameMenuRequest,
        private val statesManager: StatesManager,
        private val disableMissingEntries: Boolean,
        private val statesPreviewManager: StatesPreviewManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GameMenuStatesViewModel(
                application,
                gameMenuRequest,
                statesManager,
                disableMissingEntries,
                statesPreviewManager,
            ) as T
        }
    }

    data class StateEntry(
        val slotIndex: Int,
        val title: String,
        val description: String,
        val enabled: Boolean,
        val exists: Boolean,
        val customTitle: String?,
        val preview: Bitmap?,
    )

    data class State(
        val entries: List<StateEntry> = emptyList(),
        val newSlotEntry: StateEntry? = null,
        val isLoadMode: Boolean = false,
    )

    private val refreshTrigger = MutableStateFlow(0)

    val uiStates =
        refreshTrigger.flatMapLatest {
            flow {
                val slotsInfo = statesManager.getSavedSlotsInfo(gameMenuRequest.game, gameMenuRequest.coreConfig.coreID)

                val existingSlots = slotsInfo.filter { it.exists }
                val nextSlot = slotsInfo.firstOrNull { !it.exists }

                val entries =
                    existingSlots.map { slotInfo ->
                        val defaultTitle =
                            application.applicationContext.getString(
                                R.string.game_menu_state,
                                (slotInfo.slotIndex + 1).toString(),
                            )
                        val title = slotInfo.customTitle ?: defaultTitle
                        val dateDesc = GameMenuHelper.getSaveStateDescription(slotInfo)
                        val description = if (slotInfo.customTitle != null) {
                            application.applicationContext.getString(
                                R.string.game_menu_state_custom_subtitle,
                                (slotInfo.slotIndex + 1).toString(),
                                dateDesc,
                            )
                        } else {
                            dateDesc
                        }
                        val preview =
                            GameMenuHelper.getSaveStateBitmap(
                                application.applicationContext,
                                statesPreviewManager,
                                slotInfo,
                                gameMenuRequest.game,
                                gameMenuRequest.coreConfig.coreID,
                                slotInfo.slotIndex,
                            )

                        StateEntry(
                            slotIndex = slotInfo.slotIndex,
                            title = title,
                            description = description,
                            enabled = true,
                            exists = true,
                            customTitle = slotInfo.customTitle,
                            preview = preview,
                        )
                    }

                val newEntry = if (!disableMissingEntries && nextSlot != null) {
                    StateEntry(
                        slotIndex = nextSlot.slotIndex,
                        title = application.applicationContext.getString(R.string.game_menu_state_new),
                        description = application.applicationContext.getString(
                            R.string.game_menu_state_new_desc,
                            (nextSlot.slotIndex + 1).toString(),
                        ),
                        enabled = true,
                        exists = false,
                        customTitle = null,
                        preview = null,
                    )
                } else null

                emit(State(entries = entries, newSlotEntry = newEntry, isLoadMode = disableMissingEntries))
            }
        }

    fun renameSlot(slotIndex: Int, newTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            statesManager.renameSlot(
                gameMenuRequest.game,
                gameMenuRequest.coreConfig.coreID,
                slotIndex,
                newTitle,
            )
            refreshTrigger.value++
        }
    }

    fun deleteSlot(slotIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            statesManager.deleteSlot(
                gameMenuRequest.game,
                gameMenuRequest.coreConfig.coreID,
                slotIndex,
            )
            refreshTrigger.value++
        }
    }
}
