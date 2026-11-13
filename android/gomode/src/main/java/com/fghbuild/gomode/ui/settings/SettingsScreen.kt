// Autosaved native service and voice settings with explicit service creation and system Back navigation.
package com.fghbuild.gomode.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fghbuild.gomode.data.ServiceInstance
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.voice.AndroidThinkingEarcon
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
    serviceStatus: Map<String, ServiceStatus> = emptyMap(),
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
                    // The last setting scrolls clear of the navigation bar; the root shell applies the IME inset.
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(20.dp)
                    .semantics { testTagsAsResourceId = true }
                    .testTag("gomode-settings"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServicesSetting(
                settings = settings,
                edits = edits,
                voiceSessionActive = voiceSessionActive,
                status = serviceStatus,
                finish = finish,
                commitURL = { scope.launch { edits.save(!active, commitURL = true) } },
                onRetry = onRetryService,
                onAdded = onDone,
            )
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("Voice")
                if (voiceSessionActive) Text("End the voice session to change voice settings.")
                VoiceSetupSetting(
                    settings,
                    settingsRepository,
                    !voiceSessionActive,
                    bearerTokenFor,
                    bearerToken,
                    authEpoch,
                )
                VoiceLanguageSetting(
                    edits,
                    enabled = !voiceSessionActive && settings.loadState == SettingsLoadState.Ready,
                )
                VoiceErrorDiagnosticsSetting()
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("Display")
                // Display preferences stay available during calls.
                SwitchRow(
                    "Show assistant reasoning",
                    checked = settings.showReasoning,
                    enabled = settings.loadState == SettingsLoadState.Ready,
                    tag = "gomode-show-reasoning",
                ) { value -> scope.launch { edits.setShowReasoning(value) } }
                SwitchRow(
                    "Show usage stats",
                    checked = settings.showUsageStats,
                    enabled = settings.loadState == SettingsLoadState.Ready,
                    tag = "gomode-show-usage-stats",
                ) { value -> scope.launch { edits.setShowUsageStats(value) } }
            }
            HorizontalDivider()
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
        OutlinedIconButton(
            onClick = { player.setThinking(!playing) },
            enabled = enabled,
            modifier = Modifier.testTag("gomode-thinking-music-preview"),
        ) {
            Icon(
                if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Stop thinking music" else "Play thinking music",
            )
        }
    }
    if (failed) Text("Could not play thinking music.", color = MaterialTheme.colorScheme.error)
}

/** A switch row whose label starts at the field-label inset; the whole row toggles. */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(tag)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

private const val VOICE_LANGUAGE_HINT = "Applies to recognition, speech, and replies."

@Composable
private fun VoiceLanguageSetting(
    edits: SettingsEdits,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Voice language", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = edits.language,
            onValueChange = { edits.language = it },
            modifier =
                Modifier
                    .fillMaxWidth()
                    // The label and hint render outside the field; its semantics carry them.
                    .semantics { contentDescription = "Voice language. $VOICE_LANGUAGE_HINT" }
                    .testTag("gomode-voice-language"),
            singleLine = true,
            placeholder = { Text("en-US") },
            isError = edits.normalizedLanguage == null,
            enabled = enabled,
        )
        // Aligned with the field like the picker hints and the web's hint, not inset as supporting text.
        Text(
            VOICE_LANGUAGE_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { hideFromAccessibility() },
        )
    }
}
