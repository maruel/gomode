// Opt-in acceptance exercises actual background transfers, notification cancellation, and external process restart.
package com.fghbuild.gomode

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.voice.LocalModelDownloads
import com.fghbuild.gomode.voice.ModelDownloadState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.minutes

class BackgroundModelDownloadTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as GoModeApplication
    private val downloads get() = app.modelDownloads
    private val model = LocalLlmModel.GEMMA_4_E2B
    private val device get() = UiDevice.getInstance(instrumentation)
    private val marker get() = File(app.cacheDir, "background-model-restart.txt")

    @Test
    fun downloadContinuesAwayFromSettingsAndNotificationCancelsIt() =
        runTest(timeout = 3.minutes) {
            enabled("cancel")
            configure()
            launch().use {
                start()
                val before = bytes()
                compose.onNodeWithTag("gomode-local-model-error").assertDoesNotExist()
                capture("settings-progress")
                device.pressHome()
                compose.waitUntil(60_000) {
                    bytes() > before ||
                        downloads.states.value[model] == ModelDownloadState.Ready
                }
                val posted =
                    app
                        .getSystemService(NotificationManager::class.java)
                        .activeNotifications
                        .first { it.notification.channelId == "gomode-model-downloads" }
                assertTrue(posted.notification.actions.any { it.title == "Cancel download" })
                assertTrue(device.openNotification())
                assertTrue(device.wait(Until.hasObject(By.text("Cancel download")), 10_000))
                capture("notification-progress")
                posted.notification.actions
                    .first { it.title == "Cancel download" }
                    .actionIntent
                    .send()
                compose.waitUntil(30_000) { downloads.states.value[model] is ModelDownloadState.Missing }
                assertFalse(downloads.partial(model).exists())
            }
        }

    @Test
    fun startTransferForExternalProcessRestart() =
        runTest(timeout = 3.minutes) {
            enabled("start")
            configure()
            launch().use {
                start()
                val work =
                    WorkManager
                        .getInstance(
                            app,
                        ).getWorkInfosForUniqueWork(LocalModelDownloads.workName(model))
                        .get()
                        .single()
                marker.writeText("${work.id}\n${bytes()}\n")
                capture("before-process-restart")
                device.pressHome()
            }
        }

    @Test
    fun resumedTransferRetainsItsRequestAndCanBeCancelled() {
        enabled("resume")
        val saved = marker.readLines()
        val beforeLaunch = maxOf(saved[1].toLong(), downloads.partial(model).length())
        launch().use {
            compose.waitUntil(120_000) {
                bytes() > beforeLaunch || downloads.states.value[model] == ModelDownloadState.Ready
            }
            val work =
                WorkManager
                    .getInstance(
                        app,
                    ).getWorkInfosForUniqueWork(LocalModelDownloads.workName(model))
                    .get()
                    .single()
            assertEquals(saved[0], work.id.toString())
            compose.onNodeWithTag("gomode-local-model-error").assertDoesNotExist()
            compose.onNodeWithTag("gomode-model-download-status").performScrollTo()
            capture("after-process-restart")
            downloads.cancel(model)
            compose.waitUntil(30_000) {
                downloads.states.value[model] is ModelDownloadState.Missing ||
                    downloads.states.value[model] == ModelDownloadState.Ready
            }
            if (downloads.states.value[model] is ModelDownloadState.Missing) {
                assertFalse(
                    downloads.partial(model).exists(),
                )
            }
            marker.delete()
        }
    }

    private fun enabled(mode: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gomodeBackgroundTransfer") == mode)
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) {
            "Use an emulator for download acceptance"
        }
        device.wakeUp()
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun configure() {
        assumeTrue("An existing model is never removed by acceptance", !downloads.isInstalled(model))
        app.settingsRepository.updateVoiceMode(VoiceMode.DEVICE)
        app.settingsRepository.updateOnDeviceEngine(OnDeviceEngine.LOCAL_LLM)
        app.settingsRepository.updateLocalLlmModel(model)
        downloads.cancel(model)?.join()
        compose.waitUntil(30_000) { downloads.states.value[model] is ModelDownloadState.Missing }
    }

    private fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_MODEL_DOWNLOAD, true),
        )

    private fun start() {
        compose.waitUntil(30_000) { downloads.states.value[model] is ModelDownloadState.Missing }
        compose.onNodeWithTag("gomode-model-download").performScrollTo().performClick()
        compose.waitUntil(60_000) { bytes() >= 1_000_000 }
    }

    private fun bytes(): Long = (downloads.states.value[model] as? ModelDownloadState.Downloading)?.progress?.bytes ?: 0

    private fun capture(name: String) {
        device.waitForIdle()
        val file = File(app.getExternalFilesDir(null), "background-model-$name.png")
        assertTrue(device.takeScreenshot(file))
        assertTrue("Screenshot must contain image data", file.length() > 0)
    }
}
