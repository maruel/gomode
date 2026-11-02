// Discloses the retained voice failures with their engine, phase, and time.
package com.fghbuild.gomode.ui.settings

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.voice.VoiceErrorRecord
import java.text.DateFormat
import java.util.Date

@Composable
internal fun VoiceErrorDiagnosticsSetting() {
    val errors = (LocalContext.current.applicationContext as GoModeApplication).voiceErrors
    val history by errors.history.collectAsStateWithLifecycle()
    VoiceErrorDiagnosticsControls(history)
}

@Composable
internal fun VoiceErrorDiagnosticsControls(history: List<VoiceErrorRecord>) {
    if (history.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("gomode-voice-errors")) {
        Text(if (expanded) "Hide previous voice errors" else "Previous voice errors (${history.size})")
    }
    if (expanded) {
        SelectionContainer {
            Text(
                history.joinToString("\n\n") { it.describe() },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("gomode-voice-error-details"),
            )
        }
    }
}

private fun VoiceErrorRecord.describe(): String =
    buildString {
        appendLine(DateFormat.getDateTimeInstance().format(Date(timestampMs)))
        appendLine("$engine · ${phase.label}")
        append(message)
    }
