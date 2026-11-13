// Outlined dropdown setting with its label above, a checked current item, a hint, and the error it owns.
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    /** Guidance below the field, styled like the web's hints and tagged "[tag]-hint". */
    hint: String? = null,
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
                        // The label, hint, and error render outside the field; its semantics carry them.
                        .semantics {
                            contentDescription = listOfNotNull(label, hint).joinToString(". ")
                            error?.let { this.error(it) }
                        }.testTag(tag),
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
        hint?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { hideFromAccessibility() }.testTag("$tag-hint"),
            )
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { hideFromAccessibility() }.testTag(errorTag),
            )
        }
    }
}

/** One picker's save in progress and its failure, which shows until the next successful save. */
internal class PickerSave(
    private val scope: CoroutineScope,
    private val noun: String,
) {
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun launch(write: suspend () -> Unit) {
        scope.launch {
            saving = true
            error = null
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Display failed persistence.
                error = "Unable to save $noun: ${e.message}"
            } finally {
                saving = false
            }
        }
    }
}

/** A [PickerSave] for [noun] that starts over when [keys] change. */
@Composable
internal fun rememberPickerSave(
    noun: String,
    vararg keys: Any?,
): PickerSave {
    val scope = rememberCoroutineScope()
    return remember(scope, noun, *keys) { PickerSave(scope, noun) }
}
