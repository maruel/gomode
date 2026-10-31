// Bounded gateway speaking-voice dropdown with explicit selection and persistence errors.
package com.fghbuild.gomode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.caic.voicegateway.sdk.v1.VoiceChoice
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.voice.GatewayVoiceChoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SpeakingVoiceSetting(
    choices: GatewayVoiceChoices,
    choice: VoiceChoice,
    settings: SettingsState,
    repository: SettingsRepository,
    enabled: Boolean,
) {
    val catalog = runCatching { choices.speakingVoices(choice) }
    val voices = catalog.getOrDefault(emptyList())
    catalog.exceptionOrNull()?.message?.let { Text(it, modifier = Modifier.testTag("gomode-speaking-voice-error")) }
    if (voices.isEmpty()) return
    val selection = runCatching { choices.speakingVoice(settings, choice) }
    val scope = rememberCoroutineScope()
    var expanded by remember(choices.gatewayURL, choice.id, enabled) { mutableStateOf(false) }
    var saving by remember(choices.gatewayURL, choice.id) { mutableStateOf(false) }
    var saveError by remember(choices.gatewayURL, choice.id) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Speaking voice", style = MaterialTheme.typography.labelLarge)
        selection.exceptionOrNull()?.message?.let {
            Text(
                it,
                modifier = Modifier.testTag("gomode-speaking-voice-error"),
            )
        }
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled && !saving && settings.loadState == SettingsLoadState.Ready,
                modifier = Modifier.testTag("gomode-speaking-voice"),
            ) {
                Text(selection.getOrNull() ?: "Select a speaking voice")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp).testTag("gomode-speaking-voices"),
            ) {
                voices.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        modifier = Modifier.testTag("gomode-speaking-voice-$name"),
                        onClick = {
                            expanded = false
                            scope.launch {
                                saving = true
                                saveError = null
                                try {
                                    repository.updateSpeakingVoice(choices.gatewayURL, choice.id, name)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (
                                    @Suppress("TooGenericExceptionCaught") e: Exception,
                                ) {
                                    // Display failed persistence.
                                    saveError = "Unable to save speaking voice: ${e.message}"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    )
                }
            }
        }
        saveError?.let { Text(it, modifier = Modifier.testTag("gomode-speaking-voice-save-error")) }
    }
}
