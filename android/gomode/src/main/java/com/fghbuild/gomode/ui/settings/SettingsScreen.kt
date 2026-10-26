// Autosaved native service and voice settings with explicit service creation and system Back navigation.
package com.fghbuild.gomode.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.voice.AndroidThinkingEarcon
import com.fghbuild.gomode.voice.usesGatewayVoice
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, FlowPreview::class)
@Composable
internal fun SettingsScreen(
    settings: SettingsState,
    settingsRepository: SettingsRepository,
    voiceSessionActive: Boolean,
    onDone: () -> Unit,
    onOpenHalo: () -> Unit,
    edits: SettingsEdits,
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
    val active by rememberUpdatedState(voiceSessionActive)
    val currentSettings by rememberUpdatedState(settings)
    val finish: (suspend () -> Unit) -> Unit = { action ->
        edits.finish(!active, action)
    }
    DisposableEffect(edits) {
        onDispose { edits.discardDrafts() }
    }
    LaunchedEffect(edits) {
        snapshotFlow { listOf(edits.service, edits.language, active, currentSettings.loadState) }
            .debounce(1000)
            .collect { edits.save(!active, commitURL = false) }
    }
    BackHandler(enabled = settings.activeServiceURL.isNotBlank()) { finish { onDone() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Go Mode") },
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
                    .semantics { testTagsAsResourceId = true }
                    .testTag("gomode-settings"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.services.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Services", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            finish { edits.editService(null) }
                        },
                        modifier = Modifier.testTag("gomode-add-service"),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add new service")
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
                                    IconButton(
                                        onClick = {
                                            finish {
                                                settingsRepository.switchService(service.id)
                                                onDone()
                                            }
                                        },
                                        modifier = Modifier.testTag("gomode-service-${service.id}"),
                                    ) {
                                        Icon(Icons.Default.SwapHoriz, contentDescription = "Use service")
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        finish { edits.editService(service) }
                                    },
                                    modifier = Modifier.testTag("gomode-edit-service-${service.id}"),
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit service")
                                }
                            }
                        },
                    )
                }
            }
            edits.service?.let { draft ->
                Text(
                    if (draft.id == null) "Add new service" else "Edit service",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = { edits.service = edits.service?.copy(label = it) },
                    modifier = Modifier.fillMaxWidth().testTag("gomode-service-label"),
                    singleLine = true,
                    label = { Text("Alias") },
                )
                OutlinedTextField(
                    value = draft.url,
                    onValueChange = { edits.service = edits.service?.copy(url = it) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                if (!it.isFocused) scope.launch { edits.save(!active, commitURL = true) }
                            }.testTag("gomode-service-url"),
                    singleLine = true,
                    label = { Text("Server URL") },
                    supportingText =
                        if (draft.url.isNotBlank() && draft.normalizedURL == null) {
                            { Text("Use an http:// or https:// URL.") }
                        } else {
                            null
                        },
                    isError = draft.url.isNotBlank() && draft.normalizedURL == null,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (draft.id == null) {
                        IconButton(
                            onClick = { finish { if (edits.addService()) onDone() } },
                            enabled = draft.normalizedURL != null && settings.loadState == SettingsLoadState.Ready,
                            modifier = Modifier.testTag("gomode-create-service"),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add service")
                        }
                    }
                    if (settings.services.isNotEmpty()) {
                        IconButton(
                            onClick = { finish { edits.service = null } },
                            modifier = Modifier.testTag("gomode-close-editor"),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close editor")
                        }
                    }
                }
            }
            edits.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("gomode-settings-save-error"),
                )
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
            VoiceLanguageSetting(edits, enabled = !voiceSessionActive && settings.loadState == SettingsLoadState.Ready)
            CarDialSetting(settings, enabled = !voiceSessionActive)
            ListItem(
                modifier =
                    Modifier.testTag("gomode-show-service-on-lock-screen").toggleable(
                        value = settings.showServiceOnLockScreen,
                        enabled = !voiceSessionActive && settings.loadState == SettingsLoadState.Ready,
                        role = Role.Switch,
                        onValueChange = { value -> scope.launch { edits.setShowServiceOnLockScreen(value) } },
                    ),
                headlineContent = { Text("Show service on lock screen") },
                supportingContent = {
                    Text("During car calls. Anyone can view and use your services without unlocking.")
                },
                trailingContent = {
                    Switch(
                        checked = settings.showServiceOnLockScreen,
                        onCheckedChange = null,
                        enabled = !voiceSessionActive && settings.loadState == SettingsLoadState.Ready,
                    )
                },
            )
            CarCallerIDSetting(settings, enabled = !voiceSessionActive)
            HorizontalDivider()
            Text("Halo", style = MaterialTheme.typography.titleMedium)
            ListItem(
                headlineContent = { Text("Device") },
                supportingContent = { Text(settings.haloAddress ?: "No device selected") },
                leadingContent = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
                trailingContent = {
                    IconButton(
                        onClick = { finish { onOpenHalo() } },
                        modifier = Modifier.testTag("gomode-manage-halo"),
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Manage Halo")
                    }
                },
            )
            HorizontalDivider()
            CreditsSetting(voiceSessionActive)
        }
    }
}

@Composable
private fun CreditsSetting(voiceSessionActive: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier =
                Modifier.fillMaxWidth().testTag("gomode-credits").semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
        ) {
            Text(
                "Credits",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Start,
            )
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        if (expanded) {
            ThinkingMusicPreview(enabled = !voiceSessionActive)
            Text("Composed by Hans Zimmer.", style = MaterialTheme.typography.bodySmall)
            Text("Arranged for Solo-Piano by Philipp A. G. Grzywaczyk.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ThinkingMusicPreview(enabled: Boolean) {
    val context = LocalContext.current.applicationContext
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val player =
        remember(context) {
            AndroidThinkingEarcon(context, preview = true) { active, error ->
                playing = active
                failed = error != null
            }
        }
    DisposableEffect(player) {
        onDispose { player.setThinking(false) }
    }
    LaunchedEffect(enabled) {
        if (!enabled) player.setThinking(false)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.setThinking(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Thinking music", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = { player.setThinking(!playing) },
            enabled = enabled,
            modifier = Modifier.testTag("gomode-thinking-music-preview"),
        ) {
            Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (playing) "Stop" else "Play")
        }
    }
    if (failed) Text("Could not play thinking music.", color = MaterialTheme.colorScheme.error)
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
    Text("Local model", style = MaterialTheme.typography.titleMedium)
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
    Text("Model", style = MaterialTheme.typography.titleMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilterChip(
            selected = engine == OnDeviceEngine.REMOTE_GATEWAY,
            onClick = { scope.launch { repository.updateOnDeviceEngine(OnDeviceEngine.REMOTE_GATEWAY) } },
            enabled = enabled,
            label = { Text("Remote LLM") },
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
    edits: SettingsEdits,
    enabled: Boolean,
) {
    Text("Voice language", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = edits.language,
        onValueChange = { edits.language = it },
        modifier = Modifier.fillMaxWidth().testTag("gomode-voice-language"),
        singleLine = true,
        label = { Text("Language tag") },
        supportingText = { Text("English (US): en-US. Sets device speech and assistant reply language.") },
        isError = edits.normalizedLanguage == null,
        enabled = enabled,
    )
}
