// Gateway speaking-voice dropdown with the effective voice checked and persistence errors.
//
// A saved voice the choice no longer offers shows as the provider default, which sessions use.
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
    val voices = choice.voices.orEmpty()
    val saver = rememberPickerSave("speaking voice", choices.gatewayURL, choice.id)
    if (voices.isEmpty()) return
    val selected = choices.speakingVoice(settings, choice)
    SettingsPicker(
        label = "Speaking voice",
        value = selected.ifEmpty { "Provider default" },
        enabled = enabled && !saver.saving && settings.loadState == SettingsLoadState.Ready,
        tag = "gomode-speaking-voice",
        menuTag = "gomode-speaking-voices",
        error = saver.error,
        errorTag = "gomode-speaking-voice-save-error",
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
