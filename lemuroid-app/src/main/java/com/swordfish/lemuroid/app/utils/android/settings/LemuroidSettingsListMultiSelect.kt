package com.swordfish.lemuroid.app.utils.android.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.alorma.compose.settings.storage.base.SettingValueState

@Composable
fun LemuroidSettingsListMultiSelect(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: SettingValueState<Set<String>>,
    title: @Composable () -> Unit,
    entryValues: List<String>,
    entries: List<String>,
    icon: @Composable (() -> Unit)? = null,
    confirmButton: String,
    subtitle: @Composable (() -> Unit)? = null,
    showSubtitleInDialog: Boolean = false,
    onItemsSelected: ((List<String>) -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    if (entryValues.size != entries.size) {
        throw IllegalArgumentException("entries and entryValues need to have the same size")
    }

    var showDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    LemuroidSettingsMenuLink(
        enabled = enabled,
        icon = icon,
        title = title,
        subtitle = subtitle,
        action = action,
        onClick = { showDialog = true },
    )

    if (!showDialog) return

    val onAdd: (Int) -> Unit = { selectedIndex ->
        val mutable = state.value.toMutableSet()
        mutable.add(entryValues[selectedIndex])
        state.value = mutable
    }
    val onRemove: (Int) -> Unit = { selectedIndex ->
        val mutable = state.value.toMutableSet()
        mutable.remove(entryValues[selectedIndex])
        state.value = mutable
    }

    Dialog(onDismissRequest = { showDialog = false }) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 24.dp,
                    top = 20.dp,
                    end = 20.dp,
                    bottom = 10.dp,
                ),
            ) {
                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                        title()
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState),
                ) {
                    if (showSubtitleInDialog && subtitle != null) {
                        subtitle()
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    entryValues.forEachIndexed { index, item ->
                        val isSelected = state.value.contains(item)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 40.dp)
                                    .toggleable(
                                        role = Role.Checkbox,
                                        value = isSelected,
                                        onValueChange = { checked ->
                                            if (checked) {
                                                onAdd(index)
                                            } else {
                                                onRemove(index)
                                            }
                                        },
                                    )
                                    .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null,
                            )
                            Spacer(modifier = Modifier.size(12.dp))
                            Text(
                                text = entries[index],
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    TextButton(
                        onClick = {
                            showDialog = false
                            onItemsSelected?.invoke(entryValues.filter { state.value.contains(it) })
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(text = confirmButton)
                    }
                }
            }
        }
    }
}
