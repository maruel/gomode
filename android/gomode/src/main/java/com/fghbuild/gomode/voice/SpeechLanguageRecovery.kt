// Explains selected speech-language readiness and offers explicit installation and settings actions.
package com.fghbuild.gomode.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun SpeechLanguageRecovery(
    state: SpeechLanguageState,
    languageTag: String,
    active: Boolean,
    onDownload: () -> Unit,
    onCheck: () -> Unit,
    onSettings: () -> Unit,
) {
    if (active && state !is SpeechLanguageState.Unverified) return
    val message =
        when (state) {
            SpeechLanguageState.Idle, SpeechLanguageState.Ready -> {
                return
            }

            SpeechLanguageState.Checking -> {
                "Checking speech language $languageTag…"
            }

            SpeechLanguageState.Missing -> {
                "Speech language $languageTag is not installed. Download it to use on-device recognition."
            }

            SpeechLanguageState.Pending -> {
                "The $languageTag speech download is pending. Check again after it finishes."
            }

            is SpeechLanguageState.Downloading -> {
                state.percent?.let { "Downloading speech language $languageTag: $it%" }
                    ?: "Requesting speech language $languageTag…"
            }

            SpeechLanguageState.Unsupported -> {
                "This recognizer does not support $languageTag. Choose another language or Cloud voice in Settings."
            }

            is SpeechLanguageState.Unverified -> {
                if (active) {
                    "${state.message} Speech may still work."
                } else {
                    "${state.message} You can try voice, request a language download, or choose Cloud voice in Settings."
                }
            }

            is SpeechLanguageState.Failed -> {
                state.message
            }
        }
    val busy = state == SpeechLanguageState.Checking || state is SpeechLanguageState.Downloading
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("gomode-speech-language-recovery")) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        if (!active) {
            FlowRow {
                if (!busy) {
                    if (state != SpeechLanguageState.Unsupported && state != SpeechLanguageState.Pending) {
                        TextButton(onClick = onDownload) { Text("Download language") }
                    }
                    TextButton(onClick = onCheck) { Text("Check language") }
                }
                TextButton(onClick = onSettings) { Text("Voice settings") }
            }
        }
    }
}
