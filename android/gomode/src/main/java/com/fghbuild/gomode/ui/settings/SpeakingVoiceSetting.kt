// Bounded gateway speaking-voice dropdown with a checked current voice and persistence errors.
package com.fghbuild.gomode.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.voice.GatewayVoiceChoices
import com.fghbuild.voicegateway.sdk.v2.VoiceChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SpeakingVoiceSetting(
    choices: GatewayVoiceChoices,
    choice: VoiceChoice,
    settings: SettingsState,
    enabled: Boolean,
    save: suspend (voiceName: String) -> Unit,
) {
    val catalog = runCatching { choices.speakingVoices(choice) }
    val voices = catalog.getOrDefault(emptyList())
    val scope = rememberCoroutineScope()
    var saving by remember(choices.gatewayURL, choice.id) { mutableStateOf(false) }
    var saveError by remember(choices.gatewayURL, choice.id) { mutableStateOf<String?>(null) }
    val catalogError = catalog.exceptionOrNull()?.message
    if (voices.isEmpty() && catalogError == null) return
    val selection = runCatching { choices.speakingVoice(settings, choice) }
    val selected = selection.getOrNull()
    SettingsPicker(
        label = "Speaking voice",
        value = selected?.ifEmpty { "Provider default" } ?: "Choose an available speaking voice",
        enabled = enabled && voices.isNotEmpty() && !saving && settings.loadState == SettingsLoadState.Ready,
        tag = "gomode-speaking-voice",
        menuTag = "gomode-speaking-voices",
        error = saveError ?: catalogError ?: selection.exceptionOrNull()?.message,
        errorTag = if (saveError != null) "gomode-speaking-voice-save-error" else "gomode-speaking-voice-error",
    ) {
        (listOf("") + voices).forEach { name ->
            Item(
                text = name.ifEmpty { "Provider default" },
                selected = name == selected,
                tag = if (name.isEmpty()) "gomode-speaking-voice-default" else "gomode-speaking-voice-$name",
            ) {
                scope.launch {
                    saving = true
                    saveError = null
                    try {
                        save(name)
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
            }
        }
    }
}
