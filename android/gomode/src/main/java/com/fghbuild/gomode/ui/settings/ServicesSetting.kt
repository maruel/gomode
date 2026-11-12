// Saved services as cards that edit in place, with unique aliases and URLs and confirmed deletion.
package com.fghbuild.gomode.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.fghbuild.gomode.data.ServiceInstance
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import kotlinx.coroutines.launch

/**
 * ServicesSetting lists saved services and edits each one inside its own card.
 *
 * A new service draft opens directly below the heading's add action. [finish] flushes the open
 * draft before running its action; [commitURL] saves a completed URL when its field loses focus.
 */
@Composable
internal fun ServicesSetting(
    settings: SettingsState,
    edits: SettingsEdits,
    voiceSessionActive: Boolean,
    status: Map<String, ServiceStatus>,
    finish: (suspend () -> Unit) -> Unit,
    commitURL: () -> Unit,
    onRetry: (String) -> Unit,
    onAdded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = edits.service
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Services", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (settings.services.isNotEmpty()) {
                IconButton(
                    onClick = { finish { edits.editService(null) } },
                    enabled = draft == null || draft.id != null,
                    modifier = Modifier.testTag("gomode-add-service"),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New service")
                }
            }
        }
        if (draft != null && draft.id == null) {
            ServiceEditor(
                draft = draft,
                edits = edits,
                aliasTaken = edits.aliasTaken,
                urlTaken = edits.urlTaken,
                commitURL = commitURL,
                actions = {
                    if (settings.services.isNotEmpty()) {
                        IconButton(
                            onClick = { edits.service = null },
                            modifier = Modifier.testTag("gomode-cancel-service"),
                        ) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                    }
                    IconButton(
                        onClick = { finish { if (edits.addService()) onAdded() } },
                        enabled =
                            draft.normalizedURL != null && !edits.aliasTaken && !edits.urlTaken &&
                                settings.loadState == SettingsLoadState.Ready,
                        modifier = Modifier.testTag("gomode-create-service"),
                    ) { Icon(Icons.Default.Check, contentDescription = "Add service") }
                },
            )
        }
        settings.services.forEach { service ->
            if (draft?.id == service.id) {
                ServiceEditor(
                    draft = draft,
                    edits = edits,
                    aliasTaken = edits.aliasTaken,
                    urlTaken = edits.urlTaken,
                    commitURL = commitURL,
                    actions = {
                        IconButton(
                            onClick = { finish { edits.service = null } },
                            enabled = !edits.aliasTaken && !edits.urlTaken,
                            modifier = Modifier.testTag("gomode-service-done"),
                        ) { Icon(Icons.Default.Check, contentDescription = "Done") }
                    },
                )
            } else {
                ServiceCard(
                    service = service,
                    selected = service.id == settings.activeServiceId,
                    status = status[service.id] ?: ServiceStatus(),
                    onRetry = { onRetry(service.id) },
                    onEdit = { finish { edits.editService(service) } },
                    onDelete = { pendingDelete = service.id },
                )
            }
        }
    }
    val deleting = settings.services.firstOrNull { it.id == pendingDelete }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            // Dialogs open their own window, which does not inherit the screen's flag.
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text("Delete ${deleting.label}?") },
            text = {
                Text(
                    if (voiceSessionActive) {
                        "End the voice session to delete this service."
                    } else {
                        "Go Mode forgets ${deleting.url} on this device."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch { edits.removeService(deleting.id) }
                    },
                    enabled = !voiceSessionActive,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("gomode-confirm-delete-service"),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServiceEditor(
    draft: ServiceEdit,
    edits: SettingsEdits,
    aliasTaken: Boolean,
    urlTaken: Boolean,
    commitURL: () -> Unit,
    actions: @Composable () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag("gomode-service-editor"),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft.id == null) Text("New service", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = draft.label,
                onValueChange = { edits.service = edits.service?.copy(label = it) },
                modifier = Modifier.fillMaxWidth().testTag("gomode-service-label"),
                singleLine = true,
                label = { Text("Alias") },
                isError = aliasTaken,
                supportingText = if (aliasTaken) ({ Text("Another service uses this alias.") }) else null,
            )
            val urlError =
                when {
                    draft.url.isNotBlank() && draft.normalizedURL == null -> "Use an http:// or https:// URL."
                    urlTaken -> "Another service uses this URL."
                    else -> null
                }
            OutlinedTextField(
                value = draft.url,
                onValueChange = { edits.service = edits.service?.copy(url = it) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) commitURL() }
                        .testTag("gomode-service-url"),
                singleLine = true,
                label = { Text("Server URL") },
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it) } },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) { actions() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceCard(
    service: ServiceInstance,
    selected: Boolean,
    status: ServiceStatus,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // The URL and error use the full card width; only the title and status rows hold actions.
    OutlinedCard(Modifier.fillMaxWidth().testTag("gomode-service-${service.id}")) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(service.label.ifBlank { service.url }, style = MaterialTheme.typography.titleSmall)
                    if (selected) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                "Active",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.testTag("gomode-edit-service-${service.id}")) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit service")
                }
                IconButton(onClick = onDelete, modifier = Modifier.testTag("gomode-delete-service-${service.id}")) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete service")
                }
            }
            Column(Modifier.padding(end = 8.dp)) {
                Text(
                    service.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status.readiness != null || status.retryLabel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        status.readiness?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f, fill = false).testTag("gomode-readiness-${service.id}"),
                            )
                        }
                        status.retryLabel?.let {
                            IconButton(onClick = onRetry, modifier = Modifier.testTag("gomode-retry-${service.id}")) {
                                Icon(Icons.Default.Refresh, contentDescription = it)
                            }
                        }
                    }
                }
                status.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (status.needsAttention) Text("Needs attention", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
