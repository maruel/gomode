// Native settings screen for adding, selecting, and editing Go Mode services.
package com.fghbuild.gomode.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.voice.usesGatewayVoice
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsState,
    settingsRepository: SettingsRepository,
    voiceSessionActive: Boolean,
    onDone: () -> Unit,
    onOpenHalo: () -> Unit,
    bearerTokenFor: (String) -> String? = { null },
    bearerToken: String? = null,
    authEpoch: Long = 0,
    servicesNeedingAttention: Set<String> = emptySet(),
    serviceReadiness: Map<String, String> = emptyMap(),
    serviceErrors: Map<String, String> = emptyMap(),
    serviceRetryLabels: Map<String, String> = emptyMap(),
    onRetryService: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var editingServiceId by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(settings.services.isEmpty()) }
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = settings.activeServiceURL.isNotBlank(), onBack = onDone)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Go Mode") },
                actions = {
                    if (settings.activeServiceURL.isNotBlank()) {
                        TextButton(onClick = onDone, modifier = Modifier.testTag("gomode-done-settings")) {
                            Text("Done")
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
                    .testTag("gomode-settings"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.services.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Services", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            editingServiceId = null
                            label = ""
                            url = ""
                            error = null
                            showEditor = true
                        },
                        modifier = Modifier.testTag("gomode-add-service"),
                    ) {
                        Text("Add new service")
                    }
                }
                settings.services.forEach { service ->
                    ListItem(
                        headlineContent = { Text(service.label.ifBlank { service.url }) },
                        supportingContent = {
                            Column {
                                Text(service.url)
                                serviceReadiness[service.id]?.let { readiness ->
                                    Text(readiness, modifier = Modifier.testTag("gomode-readiness-${service.id}"))
                                }
                                serviceErrors[service.id]?.let { Text(it) }
                                serviceRetryLabels[service.id]?.let { label ->
                                    TextButton(
                                        onClick = { onRetryService(service.id) },
                                        modifier = Modifier.testTag("gomode-retry-${service.id}"),
                                    ) { Text(label) }
                                }
                                if (service.id in servicesNeedingAttention) Text("Needs attention")
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (service.id == settings.activeServiceId) {
                                    Text("Active", style = MaterialTheme.typography.labelMedium)
                                } else {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                settingsRepository.switchService(service.id)
                                                onDone()
                                            }
                                        },
                                        modifier = Modifier.testTag("gomode-service-${service.id}"),
                                    ) {
                                        Text("Use")
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        editingServiceId = service.id
                                        label = service.label
                                        url = service.url
                                        error = null
                                        showEditor = true
                                    },
                                    modifier = Modifier.testTag("gomode-edit-service-${service.id}"),
                                ) {
                                    Text("Edit")
                                }
                            }
                        },
                    )
                }
            }
            if (showEditor) {
                Text(
                    if (editingServiceId == null) "Add new service" else "Edit service",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth().testTag("gomode-service-label"),
                    singleLine = true,
                    label = { Text("Alias") },
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth().testTag("gomode-service-url"),
                    singleLine = true,
                    label = { Text("Server URL") },
                    supportingText = error?.let { { Text(it) } },
                    isError = error != null,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val normalized = SettingsRepository.normalizeURL(url)
                            if (!hasSupportedScheme(normalized)) {
                                error = "Use an http:// or https:// URL."
                                return@Button
                            }
                            scope.launch {
                                val id = editingServiceId
                                if (id == null) {
                                    settingsRepository.addService(label = label, url = normalized)
                                    onDone()
                                } else {
                                    settingsRepository.updateService(id = id, label = label, url = normalized)
                                    showEditor = false
                                }
                            }
                        },
                        modifier = Modifier.testTag("gomode-save-service"),
                    ) {
                        Text(if (editingServiceId == null) "Add service" else "Save changes")
                    }
                    if (settings.services.isNotEmpty()) {
                        TextButton(
                            onClick = { showEditor = false },
                            modifier = Modifier.testTag("gomode-cancel-edit"),
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
            HorizontalDivider()
            (settings.loadState as? SettingsLoadState.Failed)?.message?.let {
                Text(it, modifier = Modifier.testTag("gomode-settings-error"))
            }
            if (voiceSessionActive) Text("End the voice session before changing voice settings.")
            VoiceModeSetting(settings.voiceMode, settingsRepository, enabled = !voiceSessionActive)
            if (settings.voiceMode == VoiceMode.DEVICE) {
                OnDeviceEngineSetting(settings.onDeviceEngine, settingsRepository, enabled = !voiceSessionActive)
                if (settings.onDeviceEngine == OnDeviceEngine.LOCAL_LLM) {
                    LocalLlmModelSetting(settings.localLlmModel, settingsRepository, enabled = !voiceSessionActive)
                }
            }
            if (settings.activeServiceURL.isNotBlank() && usesGatewayVoice(settings)) {
                VoiceChoiceSetting(
                    settings,
                    settingsRepository,
                    !voiceSessionActive,
                    bearerTokenFor,
                    bearerToken,
                    authEpoch,
                )
            }
            VoiceLanguageSetting(settings, settingsRepository, enabled = !voiceSessionActive)
            HorizontalDivider()
            Text("Halo", style = MaterialTheme.typography.titleMedium)
            ListItem(
                headlineContent = { Text("Device") },
                supportingContent = { Text(settings.haloAddress ?: "No device selected") },
                leadingContent = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
                trailingContent = {
                    Button(
                        onClick = onOpenHalo,
                        modifier = Modifier.testTag("gomode-manage-halo"),
                    ) {
                        Text("Manage")
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceModeSetting(
    mode: VoiceMode,
    repository: SettingsRepository,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    Text("Voice mode", style = MaterialTheme.typography.titleMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilterChip(
            selected = mode == VoiceMode.CLOUD,
            onClick = { scope.launch { repository.updateVoiceMode(VoiceMode.CLOUD) } },
            enabled = enabled,
            label = { Text("Cloud voice") },
            modifier = Modifier.testTag("gomode-voice-mode-cloud"),
        )
        FilterChip(
            selected = mode == VoiceMode.DEVICE,
            onClick = { scope.launch { repository.updateVoiceMode(VoiceMode.DEVICE) } },
            enabled = enabled,
            label = { Text("On-device voice") },
            modifier = Modifier.testTag("gomode-voice-mode-device"),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalLlmModelSetting(
    model: LocalLlmModel,
    repository: SettingsRepository,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    Text("Model", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        LocalLlmModel.entries.forEach { choice ->
            FilterChip(
                selected = model == choice,
                onClick = { scope.launch { repository.updateLocalLlmModel(choice) } },
                enabled = enabled,
                label = { Text(choice.label) },
                modifier = Modifier.testTag("gomode-model-${choice.name}"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnDeviceEngineSetting(
    engine: OnDeviceEngine,
    repository: SettingsRepository,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    Text("On-device engine", style = MaterialTheme.typography.titleMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilterChip(
            selected = engine == OnDeviceEngine.REMOTE_GATEWAY,
            onClick = { scope.launch { repository.updateOnDeviceEngine(OnDeviceEngine.REMOTE_GATEWAY) } },
            enabled = enabled,
            label = { Text("Remote gateway") },
            modifier = Modifier.testTag("gomode-engine-remote-gateway"),
        )
        FilterChip(
            selected = engine == OnDeviceEngine.NEUDECIDE,
            onClick = { scope.launch { repository.updateOnDeviceEngine(OnDeviceEngine.NEUDECIDE) } },
            enabled = enabled,
            label = { Text("NeuDecide") },
            modifier = Modifier.testTag("gomode-engine-neudecide"),
        )
        FilterChip(
            selected = engine == OnDeviceEngine.LOCAL_LLM,
            onClick = { scope.launch { repository.updateOnDeviceEngine(OnDeviceEngine.LOCAL_LLM) } },
            enabled = enabled,
            label = { Text("Gemma 4") },
            modifier = Modifier.testTag("gomode-engine-local-llm"),
        )
        FilterChip(
            selected = engine == OnDeviceEngine.NEEDLE_WHISTLE,
            onClick = { scope.launch { repository.updateOnDeviceEngine(OnDeviceEngine.NEEDLE_WHISTLE) } },
            enabled = enabled,
            label = { Text("Whistle + Needle") },
            modifier = Modifier.testTag("gomode-engine-needle-whistle"),
        )
    }
}

@Composable
private fun VoiceLanguageSetting(
    settings: SettingsState,
    repository: SettingsRepository,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    var tag by remember(settings.voiceLanguageTag) { mutableStateOf(settings.voiceLanguageTag) }
    val normalized = runCatching { SettingsRepository.normalizeVoiceLanguageTag(tag) }.getOrNull()
    Text("Voice language", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = tag,
        onValueChange = { tag = it },
        modifier = Modifier.fillMaxWidth().testTag("gomode-voice-language"),
        singleLine = true,
        label = { Text("Language tag") },
        supportingText = { Text("English (US): en-US. Sets device speech and assistant reply language.") },
        isError = normalized == null,
        enabled = enabled,
    )
    Button(
        onClick = { scope.launch { repository.updateVoiceLanguageTag(tag) } },
        enabled = enabled && normalized != null && normalized != settings.voiceLanguageTag,
        modifier = Modifier.testTag("gomode-save-voice-language"),
    ) {
        Text("Save language")
    }
}

private fun hasSupportedScheme(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")
