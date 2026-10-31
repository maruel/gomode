// Exercises native download recovery, progress, completion, cancellation, and the voice-session guard.
package com.fghbuild.gomode

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.ui.settings.ModelDownloadControls
import com.fghbuild.gomode.voice.ModelDownloadState
import com.fghbuild.gomode.voice.ModelTransferProgress
import com.fghbuild.gomode.voice.ModelTransferStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ModelDownloadSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun downloadStatesOfferRecoveryAndRespectActiveVoice() {
        var state by mutableStateOf<ModelDownloadState>(ModelDownloadState.Missing(0))
        var enabled by mutableStateOf(true)
        var starts = 0
        var cancels = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.statusBarsPadding().padding(24.dp)) {
                    ModelDownloadControls(
                        LocalLlmModel.GEMMA_4_E2B,
                        state,
                        enabled,
                        onDownload = {
                            starts++
                            state =
                                ModelDownloadState.Downloading(
                                    ModelTransferProgress(10_000_000, ModelTransferStage.DOWNLOADING),
                                )
                        },
                        onCancel = {
                            cancels++
                            state = ModelDownloadState.Missing(0)
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("gomode-model-download-status").assertTextContains("Not downloaded")
        capture("missing")
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("gomode-model-download").assertIsNotEnabled()
        compose.runOnIdle { enabled = true }
        compose.onNodeWithTag("gomode-model-download").performClick()
        assertEquals(1, starts)
        compose.onNodeWithTag("gomode-model-download-progress").assertExists()
        compose.onNodeWithTag("gomode-model-download-status").assertTextContains("Downloading · 0.4%", substring = true)
        compose.onNodeWithTag("gomode-model-download-cancel").assertContentDescriptionEquals("Cancel download")
        capture("downloading")
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("gomode-model-download-cancel").assertIsNotEnabled()
        compose.runOnIdle { enabled = true }
        compose.onNodeWithTag("gomode-model-download-cancel").performClick()
        assertEquals(1, cancels)
        compose.onNodeWithTag("gomode-model-download-status").assertTextContains("Not downloaded")
        compose.runOnIdle {
            state =
                ModelDownloadState.Failed("Connection interrupted. Resume the download.", 10_000_000)
        }
        compose.onNodeWithTag("gomode-model-download").assertTextContains("Resume download")
        compose
            .onNodeWithTag(
                "gomode-model-download-status",
            ).assertTextContains("Connection interrupted. Resume the download.")
        capture("error")
        compose.onNodeWithTag("gomode-model-download").performClick()
        assertEquals(2, starts)
        compose.runOnIdle { state = ModelDownloadState.Ready }
        compose.onNodeWithTag("gomode-model-download-status").assertTextContains("Installed on this device")
        compose.onNodeWithTag("gomode-model-download").assertDoesNotExist()
        compose.onNodeWithTag("gomode-model-download-cancel").assertDoesNotExist()
        compose.onNodeWithTag("gomode-model-download-progress").assertDoesNotExist()
        capture("ready")
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        compose.waitForIdle()
        device.waitForIdle()
        assertTrue(
            device.takeScreenshot(
                File(instrumentation.targetContext.getExternalFilesDir(null), "model-download-$name.png"),
            ),
        )
    }
}
