// Opt-in acceptance downloads the public LFM3 artifact through native settings and Activity recreation.
package com.fghbuild.gomode

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.ui.settings.SettingsScreen
import com.fghbuild.gomode.ui.settings.rememberSettingsEdits
import com.fghbuild.gomode.ui.theme.GoModeTheme
import com.fghbuild.gomode.voice.ModelDownloadState
import com.fghbuild.gomode.voice.artifact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PublicModelDownloadTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun publicLfm3DownloadSurvivesRecreationAndInstallsVerifiedWeights() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gomodeDownloadModel") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val downloads = (context.applicationContext as GoModeApplication).modelDownloads
        val model = LocalLlmModel.LFM_3_1_7B
        val destination = File(context.noBackupFilesDir, "models/${model.fileName}")
        assertFalse(
            "Start this acceptance with LFM3 absent; existing models are never removed by the test",
            destination.exists(),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = File(context.cacheDir, "download-${UUID.randomUUID()}.preferences_pb")
        val repository = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { preferences })
        try {
            runBlocking {
                repository.updateVoiceMode(VoiceMode.DEVICE)
                repository.updateOnDeviceEngine(OnDeviceEngine.LOCAL_LLM)
                repository.updateLocalLlmModel(model)
            }

            fun mount(activity: ComponentActivity) {
                activity.setContent {
                    val settings by repository.settings.collectAsState()
                    GoModeTheme {
                        SettingsScreen(
                            settings,
                            repository,
                            false,
                            {},
                            {},
                            edits = rememberSettingsEdits(repository, settings),
                        )
                    }
                }
            }
            compose.activityRule.scenario.onActivity(::mount)
            compose.waitUntil { downloads.states.value[model] is ModelDownloadState.Missing }
            compose.onNodeWithTag("gomode-model-download").performScrollTo()
            capture("missing")
            compose.onNodeWithTag("gomode-model-download").performClick()
            compose.waitUntil(60_000) {
                (downloads.states.value[model] as? ModelDownloadState.Downloading)?.progress?.bytes?.let {
                    it >
                        1_000_000
                } ==
                    true
            }
            capture("progress")
            compose.onNodeWithTag("gomode-model-download-cancel").performScrollTo().performClick()
            compose.waitUntil(10_000) { downloads.states.value[model] is ModelDownloadState.Missing }
            assertFalse(File(destination.parentFile, "${model.fileName}.part").exists())
            compose.onNodeWithTag("gomode-model-download").performScrollTo().performClick()
            compose.waitUntil(60_000) {
                (downloads.states.value[model] as? ModelDownloadState.Downloading)?.progress?.bytes?.let {
                    it >
                        1_000_000
                } ==
                    true
            }
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity(::mount)
            compose.waitUntil(15 * 60_000) {
                downloads.states.value[model] == ModelDownloadState.Ready ||
                    downloads.states.value[model] is ModelDownloadState.Failed
            }
            assertEquals(ModelDownloadState.Ready, downloads.states.value[model])
            assertEquals(checkNotNull(model.artifact()).size, destination.length())
            assertFalse(File(destination.parentFile, "${model.fileName}.part").exists())
            compose
                .onNodeWithTag(
                    "gomode-model-download-status",
                ).performScrollTo()
                .assertTextContains("Installed on this device")
            capture("ready")
        } finally {
            scope.cancel()
            preferences.delete()
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle()
        assertTrue(
            device.takeScreenshot(
                File(instrumentation.targetContext.getExternalFilesDir(null), "model-public-$name.png"),
            ),
        )
    }
}
