// Discloses persisted model-load timing, sampled memory and correlated Android exit diagnostics.
package com.fghbuild.gomode.ui.settings

import android.app.ApplicationExitInfo
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.voice.ModelDownloadFormat
import com.fghbuild.gomode.voice.ModelLoadHistory
import com.fghbuild.gomode.voice.ModelLoadOutcome
import com.fghbuild.gomode.voice.ModelLoadRecord
import com.fghbuild.gomode.voice.ModelLoadStage
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ModelLoadDiagnosticsSetting() {
    val diagnostics = (LocalContext.current.applicationContext as GoModeApplication).modelLoadDiagnostics
    val history by diagnostics.history.collectAsStateWithLifecycle()
    ModelLoadDiagnosticsControls(history)
}

@Composable
internal fun ModelLoadDiagnosticsControls(history: ModelLoadHistory) {
    var expanded by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { ModelDownloadFormat(locale) }
    if (history.latest == null) return
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("gomode-model-diagnostics")) {
        Text(if (expanded) "Hide model load diagnostics" else "Model load diagnostics")
    }
    if (expanded) {
        SelectionContainer {
            Text(
                listOfNotNull(history.latest, history.previous).joinToString("\n\n") { record ->
                    record.describe(format)
                } + "\n\nMemory values are sampled process usage; GPU allocations may be excluded.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("gomode-model-diagnostics-details"),
            )
        }
    }
}

private fun ModelLoadRecord.describe(format: ModelDownloadFormat): String =
    buildString {
        val label = LocalLlmModel.entries.firstOrNull { it.name == model }?.label ?: model
        appendLine("$label · $contextTokens tokens · $backend")
        appendLine(DateFormat.getDateTimeInstance().format(Date(startedAtMs)))
        val outcomeLabel =
            when (outcome) {
                ModelLoadOutcome.LOADING -> "Loading"
                ModelLoadOutcome.INTERRUPTED -> "Interrupted"
                ModelLoadOutcome.READY -> "Ready"
                ModelLoadOutcome.FAILED -> "Failed"
            }
        val stageLabel =
            when (stage) {
                ModelLoadStage.ENGINE_CREATION -> "engine creation"
                ModelLoadStage.ENGINE_INITIALIZATION -> "engine initialization"
                ModelLoadStage.CONVERSATION_CREATION -> "conversation creation"
            }
        appendLine("$outcomeLabel · $stageLabel · $durationMs ms")
        appendLine("Peak sampled RSS: ${format.bytes(peakSampledRssBytes)}")
        appendLine("Peak sampled PSS: ${format.bytes(peakSampledPssBytes)}")
        appendLine("Peak sampled native heap: ${format.bytes(peakSampledNativeHeapBytes)}")
        appendLine("Minimum available RAM: ${format.bytes(minimumAvailableBytes)} of ${format.bytes(totalMemoryBytes)}")
        failureClass?.let { appendLine("Failure: $it") }
        exit?.let {
            val reason =
                when (it.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                    ApplicationExitInfo.REASON_CRASH -> "App crash"
                    ApplicationExitInfo.REASON_ANR -> "App not responding"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "User requested"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "User stopped"
                    ApplicationExitInfo.REASON_SIGNALED -> "Signal"
                    ApplicationExitInfo.REASON_EXIT_SELF -> "App exited"
                    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "Package updated"
                    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "Package state changed"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permissions changed"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failed"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource usage"
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency exited"
                    ApplicationExitInfo.REASON_FREEZER -> "Cached app frozen"
                    ApplicationExitInfo.REASON_UNKNOWN -> "Unknown"
                    ApplicationExitInfo.REASON_OTHER -> "Other"
                    else -> "Android reason ${it.reason}"
                }
            appendLine("Process $pid exit: $reason · status ${it.status}")
            appendLine("Exit RSS: ${format.bytes(it.rssBytes)} · PSS: ${format.bytes(it.pssBytes)}")
        }
        if (outcome == ModelLoadOutcome.INTERRUPTED && exit == null) {
            appendLine("No matching Android exit record available.")
        }
    }.trimEnd()
