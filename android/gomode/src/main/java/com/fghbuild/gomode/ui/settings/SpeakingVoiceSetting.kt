// Bounded gateway speaking-voice dropdown with a checked current voice and persistence errors.
package com.fghbuild.gomode.ui.settings

import androidx.compose.runtime.Composable
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.voice.GatewayVoiceChoices
import com.fghbuild.voicegateway.sdk.v2.VoiceChoice

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
    val saver = rememberPickerSave("speaking voice", choices.gatewayURL, choice.id)
    val catalogError = catalog.exceptionOrNull()?.message
    if (voices.isEmpty() && catalogError == null) return
    val selection = runCatching { choices.speakingVoice(settings, choice) }
    val selected = selection.getOrNull()
    SettingsPicker(
        label = "Speaking voice",
        value = selected?.ifEmpty { "Provider default" } ?: UNAVAILABLE_SPEAKING_VOICE,
        enabled = enabled && voices.isNotEmpty() && !saver.saving && settings.loadState == SettingsLoadState.Ready,
        tag = "gomode-speaking-voice",
        menuTag = "gomode-speaking-voices",
        error = saver.error ?: catalogError ?: selection.exceptionOrNull()?.message,
        errorTag = if (saver.error != null) "gomode-speaking-voice-save-error" else "gomode-speaking-voice-error",
    ) {
        (listOf("") + voices).forEach { name ->
            Item(
                text = name.ifEmpty { "Provider default" },
                selected = name == selected,
                tag = if (name.isEmpty()) "gomode-speaking-voice-default" else "gomode-speaking-voice-$name",
            ) {
                saver.launch { save(name) }
            }
        }
    }
}
