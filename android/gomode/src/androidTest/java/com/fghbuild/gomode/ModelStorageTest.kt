// Exercises installed-size display and real background deletion using bounded app-owned model fixtures.
package com.fghbuild.gomode

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.VoiceLanguageModel
import com.fghbuild.gomode.voice.ModelDownloadState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ModelStorageTest {
    @get:Rule(order = 0)
    internal val systemUi = SystemUiRule()

    @get:Rule(order = 1)
    @OptIn(ExperimentalTestApi::class)
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    @Test
    fun installedModelCanBeDeletedFromSettings() {
        check(
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"),
        ) { "Use an emulator for storage fixtures" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GoModeApplication
        val model = LocalLlmModel.LFM_3_1_7B_32K
        val downloads = app.modelDownloads
        assumeTrue("Existing models are never replaced by fixtures", !downloads.isInstalled(model))
        runBlocking {
            app.goModeSettingsDataStore.edit { it.clear() }
            app.settingsRepository.updateSavedVoiceSetup(null) {
                it.copy(
                    preference = it.preference.withLanguageModel(VoiceLanguageModel.LOCAL_LLM),
                    localLlmModel = model,
                )
            }
            downloads.cancel(model)?.join()
        }
        compose.waitUntil(30_000) { downloads.states.value[model] is ModelDownloadState.Missing }
        val file = File(downloads.directory, model.fileName)
        file.parentFile?.mkdirs()
        check(file.createNewFile())
        try {
            file.writeBytes(ByteArray(128))
            // An already-published file is discovered by the normal durable worker without fetching weights.
            downloads.download(model)
            compose.waitUntil(30_000) { downloads.states.value[model] is ModelDownloadState.Ready }
            assertEquals(128L, (downloads.states.value[model] as ModelDownloadState.Ready).bytes)
            ActivityScenario
                .launch<MainActivity>(
                    Intent(app, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_MODEL_DOWNLOAD, true),
                ).use {
                    compose
                        .onNodeWithTag("gomode-model-download-status")
                        .performScrollTo()
                        .assertTextContains("Installed · 128 byte")
                    capture("installed")
                    compose.onNodeWithTag("gomode-model-delete").performScrollTo().performClick()
                    compose.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                        compose.onAllNodesWithTag("gomode-model-delete-confirm").fetchSemanticsNodes().isNotEmpty()
                    }
                    capture("confirm")
                    compose.onNodeWithTag("gomode-model-delete-confirm").performClick()
                    compose.waitUntil(30_000) { downloads.states.value[model] is ModelDownloadState.Missing }
                    compose.onNodeWithTag("gomode-model-download-status").assertTextContains("Not downloaded")
                    assertFalse(file.exists())
                    capture("deleted")
                }
        } finally {
            // The test owns this bounded fixture, never a user's downloaded model.
            file.delete()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "model-storage-$name.png")
        assertTrue(device.takeScreenshot(file))
    }
}
