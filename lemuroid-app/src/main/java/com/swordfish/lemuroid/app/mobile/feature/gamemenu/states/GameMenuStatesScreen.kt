package com.swordfish.lemuroid.app.mobile.feature.gamemenu.states

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsMenuLink

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameMenuStatesScreen(
    viewModel: GameMenuStatesViewModel,
    onStateClicked: (Int) -> Unit,
) {
    val state = viewModel.uiStates.collectAsState(initial = GameMenuStatesViewModel.State())

    var menuExpandedSlotIndex by remember { mutableStateOf<Int?>(null) }
    var renameTarget by remember { mutableStateOf<GameMenuStatesViewModel.StateEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<GameMenuStatesViewModel.StateEntry?>(null) }
    var overwriteTarget by remember { mutableStateOf<GameMenuStatesViewModel.StateEntry?>(null) }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (state.value.isLoadMode && state.value.entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.game_menu_state_empty_load),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        state.value.entries.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            if (!state.value.isLoadMode) {
                                overwriteTarget = entry
                            } else {
                                onStateClicked(entry.slotIndex)
                            }
                        },
                        onLongClick = {
                            menuExpandedSlotIndex = entry.slotIndex
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.preview != null) {
                    Image(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        bitmap = entry.preview.asImageBitmap(),
                        contentScale = ContentScale.Crop,
                        contentDescription = null,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box {
                    IconButton(onClick = { menuExpandedSlotIndex = entry.slotIndex }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpandedSlotIndex == entry.slotIndex,
                        onDismissRequest = { menuExpandedSlotIndex = null },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.game_menu_state_action_rename)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpandedSlotIndex = null
                                renameTarget = entry
                                renameText = entry.customTitle ?: ""
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.game_menu_state_action_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpandedSlotIndex = null
                                deleteTarget = entry
                            },
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (!state.value.isLoadMode && state.value.newSlotEntry != null) {
            val newSlot = state.value.newSlotEntry!!
            LemuroidSettingsMenuLink(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                },
                title = {
                    Text(
                        text = newSlot.title,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                subtitle = { Text(text = newSlot.description) },
                onClick = { onStateClicked(newSlot.slotIndex) },
            )
        }
    }

    if (renameTarget != null) {
        val entry = renameTarget!!
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.game_menu_state_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.game_menu_state_rename_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSlot(entry.slotIndex, renameText)
                        renameTarget = null
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (deleteTarget != null) {
        val entry = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.game_menu_state_delete_title)) },
            text = {
                Text(stringResource(R.string.game_menu_state_delete_msg, entry.title))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSlot(entry.slotIndex)
                        deleteTarget = null
                    },
                ) {
                    Text(
                        stringResource(R.string.game_menu_state_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (overwriteTarget != null) {
        val entry = overwriteTarget!!
        AlertDialog(
            onDismissRequest = { overwriteTarget = null },
            title = { Text(stringResource(R.string.game_menu_state_overwrite_title)) },
            text = {
                Text(stringResource(R.string.game_menu_state_overwrite_msg, entry.title))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val slot = entry.slotIndex
                        overwriteTarget = null
                        onStateClicked(slot)
                    },
                ) {
                    Text(stringResource(R.string.game_menu_state_action_overwrite))
                }
            },
            dismissButton = {
                TextButton(onClick = { overwriteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
