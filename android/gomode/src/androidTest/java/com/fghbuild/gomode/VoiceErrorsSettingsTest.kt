// Compose tests for the retained voice failures disclosed in native settings.
package com.fghbuild.gomode

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.ui.settings.VoiceErrorDiagnosticsControls
import com.fghbuild.gomode.voice.VoiceErrorLog
import com.fghbuild.gomode.voice.VoiceErrorPhase
import com.fghbuild.gomode.voice.VoiceErrorRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class VoiceErrorsSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun disclosedFailuresNameTheEngineAndPhase() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "voice-errors-fixture-${UUID.randomUUID()}")
        val isolated =
            object : ContextWrapper(app) {
                override fun getNoBackupFilesDir(): File = directory
            }
        try {
            lateinit var history: List<VoiceErrorRecord>
            runTest {
                VoiceErrorLog(isolated).use { errors ->
                    errors.record(
                        "Gemma 4 E4B",
                        VoiceErrorPhase.CALL,
                        "Voice turn failed (llm): out of memory",
                    )
                    errors.record("Remote LLM", VoiceErrorPhase.CONNECTING, "Voice gateway unreachable")
                    history = errors.history.first { it.size == 2 }
                }
            }
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.padding(24.dp)) {
                        VoiceErrorDiagnosticsControls(history)
                    }
                }
            }
            compose.onNodeWithTag("gomode-voice-error-details").assertDoesNotExist()
            compose.onNodeWithTag("gomode-voice-errors").assertTextContains("Previous voice errors (2)")
            compose.onNodeWithTag("gomode-voice-errors").performClick()
            compose
                .onNodeWithTag("gomode-voice-error-details")
                .assertTextContains("Remote LLM · While connecting", substring = true)
                .assertTextContains("Gemma 4 E4B · During a call", substring = true)
                .assertTextContains("Voice turn failed (llm): out of memory", substring = true)
            capture("voice-errors")
            compose.onNodeWithTag("gomode-voice-errors").performClick()
            compose.onNodeWithTag("gomode-voice-error-details").assertDoesNotExist()
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        compose.waitForIdle()
        device.waitForIdle()
        assertTrue(
            device.takeScreenshot(
                File(instrumentation.targetContext.getExternalFilesDir(null), "voice-errors-$name.png"),
            ),
        )
    }
}
