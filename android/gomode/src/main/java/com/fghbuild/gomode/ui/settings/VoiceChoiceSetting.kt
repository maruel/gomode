// Native gateway choice picker with cancellable discovery, retry, and persisted selection errors.
package com.fghbuild.gomode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.voice.GatewayVoiceChoices
import com.fghbuild.gomode.voice.discoverVoiceChoices
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
            discovery = ChoiceDiscovery.Failed(e.message ?: "Unable to load LLMs")
        }
    }
    (settings.loadState as? SettingsLoadState.Failed)?.message?.let {
        Text(it, modifier = Modifier.testTag("gomode-voice-choice-error"))
    }
    when (val state = discovery) {
        ChoiceDiscovery.Loading -> {
            Text("Loading LLMs…", modifier = Modifier.testTag("gomode-voice-choices-loading"))
        }

        is ChoiceDiscovery.Failed -> {
            Text(state.message, modifier = Modifier.testTag("gomode-voice-choice-error"))
            IconButton(
                onClick = { retry += 1 },
                enabled = enabled,
                modifier = Modifier.testTag("gomode-voice-choices-retry"),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Retry")
            }
        }

        is ChoiceDiscovery.Ready -> {
            val selection = runCatching { state.choices.selection(settings) }
            selection.exceptionOrNull()?.message?.let {
                Text(it, modifier = Modifier.testTag("gomode-voice-choice-error"))
            }
            val compatible = state.choices.compatible(settings)
            // A missing saved model still needs an explicit replacement, even with only one candidate.
            if (compatible.size > 1 || (compatible.isNotEmpty() && selection.isFailure)) {
                Text("LLM", style = MaterialTheme.typography.titleMedium)
                ChoiceChips(state.choices, settings, selection.getOrNull()?.id, enabled && !saving) { choiceID ->
                    scope.launch {
                        saving = true
                        saveError = null
                        try {
                            repository.updateVoiceChoice(state.choices.gatewayURL, choiceID)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: Exception,
                        ) {
                            // Display failed persistence.
                            saveError = "Unable to save LLM: ${e.message}"
                        } finally {
                            saving = false
                        }
                    }
                }
            }
            if (settings.voiceMode == VoiceMode.CLOUD) {
                selection.getOrNull()?.let { choice ->
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
    choices: GatewayVoiceChoices,
    settings: SettingsState,
    selectedID: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.compatible(settings).forEach { choice ->
            FilterChip(
                selected = choice.id == selectedID,
                onClick = { onSelect(choice.id) },
                enabled = enabled && settings.loadState == SettingsLoadState.Ready,
                label = { Text(choice.label) },
                modifier = Modifier.testTag("gomode-voice-choice-${choice.id}"),
            )
        }
    }
}
