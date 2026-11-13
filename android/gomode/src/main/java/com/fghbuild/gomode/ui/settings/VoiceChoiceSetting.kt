// Native gateway choice picker with cancellable discovery, retry, and persisted selection errors.
package com.fghbuild.gomode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.SpeechStageLocation
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.data.VoiceSetupPreference
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.voice.GatewayVoiceChoices
import com.fghbuild.gomode.voice.VoiceSetupKind
import com.fghbuild.gomode.voice.discoverVoiceChoices
import com.fghbuild.voicegateway.sdk.v2.VoiceChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

private sealed interface ChoiceDiscovery {
    data object Loading : ChoiceDiscovery

    data class Ready(
        val choices: GatewayVoiceChoices,
    ) : ChoiceDiscovery

    data class Failed(
        val message: String,
    ) : ChoiceDiscovery
}

@Composable
internal fun VoiceChoiceSetting(
    settings: SettingsState,
    repository: SettingsRepository,
    enabled: Boolean,
    bearerTokenFor: (String) -> String?,
    bearerToken: String?,
    authEpoch: Long,
) {
    val identity = listOf(settings.activeServiceId, settings.activeServiceURL, bearerToken, authEpoch)
    var discovery by remember(identity) { mutableStateOf<ChoiceDiscovery>(ChoiceDiscovery.Loading) }
    var retry by remember(identity) { mutableStateOf(0) }
    var saveError by remember(identity) { mutableStateOf<String?>(null) }
    var saving by remember(identity) { mutableStateOf(false) }
    val tokenProvider by rememberUpdatedState(bearerTokenFor)
    val scope = rememberCoroutineScope()
    LaunchedEffect(identity, retry) {
        discovery = ChoiceDiscovery.Loading
        try {
            val service = ServiceSettingsClient().fetch(settings.activeServiceURL)
            val choices = discoverVoiceChoices(settings.activeServiceURL, service, tokenProvider)
            coroutineContext.ensureActive()
            discovery = ChoiceDiscovery.Ready(choices)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // User-visible discovery boundary.
            discovery = ChoiceDiscovery.Failed(e.message ?: "Unable to load voice choices")
        }
    }
    (settings.loadState as? SettingsLoadState.Failed)?.message?.let {
        Text(it, modifier = Modifier.testTag("gomode-voice-choice-error"))
    }
    when (val state = discovery) {
        ChoiceDiscovery.Loading -> {
            Text("Loading voice choices…", modifier = Modifier.testTag("gomode-voice-choices-loading"))
        }

        is ChoiceDiscovery.Failed -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f, fill = false).testTag("gomode-voice-choice-error"),
                )
                IconButton(
                    onClick = { retry += 1 },
                    enabled = enabled,
                    modifier = Modifier.testTag("gomode-voice-choices-retry"),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retry voice choices")
                }
            }
        }

        is ChoiceDiscovery.Ready -> {
            val resolved =
                runCatching { state.choices.resolveSetup(settings).also(state.choices::requireAndroidSpeech) }
            resolved.exceptionOrNull()?.message?.let {
                Text(it, modifier = Modifier.testTag("gomode-voice-choice-error"))
            }
            val deviceSpeech = settings.voiceSetup.deviceSpeech
            val listed = listedChoices(state.choices, deviceSpeech)
            // Gateway speech selects a model; device speech selects an LLM.
            val familyLabel = if (deviceSpeech) "LLM" else "Model"
            // A missing saved choice still needs an explicit replacement, even with only one candidate.
            if (listed.size > 1 || (listed.isNotEmpty() && resolved.isFailure)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(familyLabel, style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(listed, settings, resolved.getOrNull()?.choice?.id, enabled && !saving) { choice ->
                        scope.launch {
                            saving = true
                            saveError = null
                            try {
                                repository.updateVoiceChoice(state.choices.gatewayURL, choice.id) {
                                    choiceSetup(it, deviceSpeech)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (
                                @Suppress("TooGenericExceptionCaught") e: Exception,
                            ) {
                                // Display failed persistence.
                                saveError = "Unable to save $familyLabel: ${e.message}"
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
            }
            if (!deviceSpeech) {
                resolved.getOrNull()?.choice?.let { choice ->
                    SpeakingVoiceSetting(state.choices, choice, settings, repository, enabled && !saving)
                }
            }
        }
    }
    saveError?.let { Text(it, modifier = Modifier.testTag("gomode-voice-choice-save-error")) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(
    choices: List<VoiceChoice>,
    settings: SettingsState,
    selectedID: String?,
    enabled: Boolean,
    onSelect: (VoiceChoice) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            FilterChip(
                selected = choice.id == selectedID,
                onClick = { onSelect(choice) },
                enabled = enabled && settings.loadState == SettingsLoadState.Ready,
                label = { Text(choice.label) },
                modifier = Modifier.testTag("gomode-voice-choice-${choice.id}"),
            )
        }
    }
}

// Gateway speech lists native models and language models with gateway recognition and speech.
private fun listedChoices(
    choices: GatewayVoiceChoices,
    deviceSpeech: Boolean,
): List<VoiceChoice> {
    val stage = if (deviceSpeech) SpeechStageLocation.DEVICE else SpeechStageLocation.GATEWAY
    val stages = choices.compatibleChoices(VoiceSetupKind.SEPARATE_STAGES, stage, stage)
    if (deviceSpeech) return stages
    val native = choices.setupChoices(VoiceSetupKind.ALL_IN_ONE)
    return choices.catalog.choices
        .orEmpty()
        .filter { it in native || it in stages }
}

// Device speech saves the separate-stages choice; gateway speech follows the picked gateway choice.
private fun choiceSetup(
    setup: VoiceSetupPreference,
    deviceSpeech: Boolean,
): VoiceSetupPreference =
    if (deviceSpeech) setup.withDeviceSpeech() else setup.copy(mode = VoiceSetupMode.FOLLOW_GATEWAY)
