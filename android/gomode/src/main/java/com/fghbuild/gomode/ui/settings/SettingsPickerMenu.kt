// Outlined dropdown setting with its label above, a checked current item, and the error it owns.
package com.fghbuild.gomode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

/** Items of an open [SettingsPicker] menu; selecting one closes it. */
internal class SettingsPickerMenu(
    private val close: () -> Unit,
) {
    /** A choice; the current one carries a trailing check and reselecting it saves nothing. */
    @Composable
    fun Item(
        text: String,
        selected: Boolean,
        tag: String,
        enabled: Boolean = true,
        onSelect: () -> Unit,
    ) {
        DropdownMenuItem(
            text = { Text(text) },
            onClick = {
                close()
                if (!selected) onSelect()
            },
            enabled = enabled,
            // A trailing check marks the current item without shifting item labels.
            trailingIcon = { if (selected) Icon(Icons.Default.Check, contentDescription = null) },
            modifier = Modifier.semantics { this.selected = selected }.testTag(tag),
        )
    }

    /** A label that names the items after it. */
    @Composable
    fun Header(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPicker(
    label: String,
    value: String,
    enabled: Boolean,
    tag: String,
    menuTag: String,
    error: String?,
    errorTag: String,
    items: @Composable SettingsPickerMenu.() -> Unit,
) {
    // Disabling closes the menu; the menu's items close the same state object the field opens.
    val open = remember(enabled) { mutableStateOf(false) }
    var expanded by open
    val menu = remember(open) { SettingsPickerMenu { open.value = false } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = enabled && it }) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                isError = error != null,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier =
                    Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                        .fillMaxWidth()
                        // The visible label sits above the field, outside its semantics.
                        .semantics { contentDescription = label }
                        .testTag(tag),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // Menus open a popup window, which does not inherit the screen's flag.
                modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(menuTag),
            ) {
                menu.items()
            }
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(errorTag),
            )
        }
    }
}
