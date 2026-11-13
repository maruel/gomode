// Local speaking-voice dropdown: the engine default first, then installed voices of the voice language.
//
// Mirrors the web's browser speaking-voice picker in web/src/VoiceSettings.tsx: "Local default"
// leaves the choice to the TextToSpeech engine; other items list the installed voices whose
// language matches the voice language, in any region. A saved voice that is no longer installed,
// or that no longer matches the language, shows as unavailable until a pick replaces it;
// AndroidDeviceSpeech refuses to speak with it rather than substituting another voice.
package com.fghbuild.gomode.ui.settings

import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.fghbuild.gomode.voice.deviceVoiceMatchesLanguage
import com.fghbuild.gomode.voice.installedDeviceVoices

private const val LOCAL_DEFAULT = "Local default"

internal const val UNAVAILABLE_SPEAKING_VOICE = "Choose an available speaking voice"

@Composable
internal fun DeviceSpeakingVoiceSetting(
    voiceName: String,
    languageTag: String,
    enabled: Boolean,
    save: suspend (voiceName: String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    // Null until the engine lists its voices.
    val installed by produceState<List<Voice>?>(null, context) { value = installedDeviceVoices(context) }
    val voices = installed?.filter { deviceVoiceMatchesLanguage(it, languageTag) }
    val saver = rememberPickerSave("speaking voice")
    SettingsPicker(
        label = "Speaking voice",
        value =
            when {
                voiceName.isEmpty() -> LOCAL_DEFAULT
                voices == null -> voiceName
                else -> voices.firstOrNull { it.name == voiceName }?.let(::voiceLabel) ?: UNAVAILABLE_SPEAKING_VOICE
            },
        enabled = enabled && voices != null && !saver.saving,
        tag = "gomode-device-speaking-voice",
        menuTag = "gomode-device-speaking-voices",
        error = saver.error,
        errorTag = "gomode-device-speaking-voice-error",
    ) {
        Item(LOCAL_DEFAULT, selected = voiceName.isEmpty(), tag = "gomode-device-speaking-voice-default") {
            saver.launch { save("") }
        }
        voices.orEmpty().forEach { voice ->
            Item(
                voiceLabel(voice),
                selected = voice.name == voiceName,
                tag = "gomode-device-speaking-voice-${voice.name}",
            ) {
                saver.launch { save(voice.name) }
            }
        }
    }
}

private fun voiceLabel(voice: Voice): String = "${voice.name} (${voice.locale.toLanguageTag()})"
