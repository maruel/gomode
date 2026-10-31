// Exercises model download recovery, storage, deletion, diagnostic disclosure and active-voice guards.
package com.fghbuild.gomode

import android.content.ContextWrapper
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
import com.fghbuild.gomode.ui.settings.ModelLoadDiagnosticsControls
import com.fghbuild.gomode.voice.LocalModelLoadDiagnostics
import com.fghbuild.gomode.voice.ModelDownloadState
import com.fghbuild.gomode.voice.ModelLoadStage
import com.fghbuild.gomode.voice.ModelTransferProgress
import com.fghbuild.gomode.voice.ModelTransferStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

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
                        availableBytes = 8_000_000_000,
                        onRemove = { state = ModelDownloadState.Removing },
                        onDownload = {
                            starts++
                            state =
                                ModelDownloadState.Downloading(
                                    ModelTransferProgress(10_000_000, ModelTransferStage.DOWNLOADING),
                                    totalBytes = 2_588_147_712,
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
        compose.onNodeWithTag("gomode-model-download").assertContentDescriptionEquals("Download model")
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
        compose.onNodeWithTag("gomode-model-download").assertContentDescriptionEquals("Resume download")
        compose
            .onNodeWithTag(
                "gomode-model-download-status",
            ).assertTextContains("Connection interrupted. Resume the download.")
        capture("error")
        compose.onNodeWithTag("gomode-model-download").performClick()
        assertEquals(2, starts)
        compose.runOnIdle { state = ModelDownloadState.Ready(2_588_147_712, true) }
        compose.onNodeWithTag("gomode-model-download-status").assertTextContains("Installed · 2.59 GB")
        compose.onNodeWithTag("gomode-model-storage").assertTextContains("8.00 GB available")
        compose.onNodeWithTag("gomode-model-download").assertDoesNotExist()
        compose.onNodeWithTag("gomode-model-download-cancel").assertDoesNotExist()
        compose.onNodeWithTag("gomode-model-download-progress").assertDoesNotExist()
        capture("ready")
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("gomode-model-delete").assertIsNotEnabled()
        compose.runOnIdle { enabled = true }
        compose.onNodeWithTag("gomode-model-delete").performClick()
        capture("delete-confirmation")
        compose.onNodeWithTag("gomode-model-delete-confirm").performClick()
        compose.onNodeWithTag("gomode-model-download-status").assertTextContains("Deleting model…")
        compose.onNodeWithTag("gomode-model-delete").assertDoesNotExist()
        capture("deleting")
        compose.runOnIdle { state = ModelDownloadState.Missing(0) }
        compose.onNodeWithTag("gomode-model-download-status").assertTextContains("Not downloaded")
        compose.onNodeWithTag("gomode-model-download").assertExists()
        compose.runOnIdle { state = ModelDownloadState.Ready(2_588_147_712, false) }
        compose.onNodeWithTag("gomode-model-download-status").assertTextContains("developer-managed", substring = true)
        compose.onNodeWithTag("gomode-model-delete").assertDoesNotExist()
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

    @Test
    fun modelsResolveBeforeShowingMeasuredDownloadProgress() {
        var model by mutableStateOf(LocalLlmModel.LFM_3_1_7B_32K)
        var state by mutableStateOf<ModelDownloadState>(ModelDownloadState.Missing(0))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.statusBarsPadding().padding(24.dp)) {
                    ModelDownloadControls(
                        model,
                        state,
                        enabled = true,
                        availableBytes = 8_000_000_000,
                        onRemove = { state = ModelDownloadState.Missing(0) },
                        onDownload = {
                            state =
                                ModelDownloadState.Downloading(ModelTransferProgress(0, ModelTransferStage.CHECKING))
                        },
                        onCancel = { state = ModelDownloadState.Missing(0) },
                    )
                }
            }
        }
        for (variant in LocalLlmModel.entries) {
            compose.runOnIdle { model = variant }
            compose.onNodeWithTag("gomode-model-download").performClick()
            compose.onNodeWithTag("gomode-model-download-status").assertTextContains("Resolving model release…")
            compose.onNodeWithTag("gomode-model-download-progress").assertExists()
            capture("${variant.name}-resolving")
            compose.runOnIdle {
                state =
                    ModelDownloadState.Downloading(
                        ModelTransferProgress(18_000_000, ModelTransferStage.DOWNLOADING),
                        totalBytes = 1_800_000_000,
                    )
            }
            compose
                .onNodeWithTag("gomode-model-download-status")
                .assertTextContains("Downloading · 1.0% · 18.0 MB of 1.80 GB")
            capture("${variant.name}-downloading")
            compose.onNodeWithTag("gomode-model-download-cancel").performClick()
            compose.onNodeWithTag("gomode-model-download-status").assertTextContains("Not downloaded")
        }
    }

    @Test
    fun loadDiagnosticsDisclosureShowsRecordedAttempt() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "model-diagnostics-fixture-${UUID.randomUUID()}")
        val isolated =
            object : ContextWrapper(app) {
                override fun getNoBackupFilesDir(): File = directory
            }
        try {
            val history =
                runBlocking(Dispatchers.IO) {
                    LocalModelLoadDiagnostics(isolated).use { diagnostics ->
                        val id = diagnostics.begin(LocalLlmModel.LFM_3_1_7B_32K, "GPU")
                        diagnostics.stage(id, ModelLoadStage.CONVERSATION_CREATION)
                        diagnostics.finish(id)
                        diagnostics.history.value
                    }
                }
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.statusBarsPadding().padding(24.dp)) {
                        ModelLoadDiagnosticsControls(history)
                    }
                }
            }
            compose.onNodeWithTag("gomode-model-diagnostics-details").assertDoesNotExist()
            compose.onNodeWithTag("gomode-model-diagnostics").performClick()
            compose
                .onNodeWithTag("gomode-model-diagnostics-details")
                .assertTextContains("LFM3 1.7B · 32K · 32768 tokens · GPU", substring = true)
                .assertTextContains("Peak sampled RSS:", substring = true)
            capture("load-diagnostics")
            compose.onNodeWithTag("gomode-model-diagnostics").performClick()
            compose.onNodeWithTag("gomode-model-diagnostics-details").assertDoesNotExist()
        } finally {
            directory.deleteRecursively()
        }
    }
}
