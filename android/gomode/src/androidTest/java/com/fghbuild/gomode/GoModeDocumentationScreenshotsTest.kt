// Captures real native service, voice settings, Halo, and a host-neutral demonstration frontend.
package com.fghbuild.gomode

import android.os.Environment
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GoModeDocumentationScreenshotsTest : GoModeE2eTestBase() {
    override fun validateDeviceBeforeSetup() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1") {
            "Documentation captures require an emulator"
        }
    }

    @Test
    fun captureNativeShellAndHostedService() {
        assumeTrue(
            "documentation captures require the explicit screenshot runner",
            InstrumentationRegistry.getArguments().getString("gomodeScreenshots") == "true",
        )
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val directory =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "gomode-screenshots",
            )
        check(directory.mkdirs() || directory.isDirectory) { "Could not create screenshot directory" }
        directory.listFiles()?.forEach { check(it.delete()) { "Could not remove old screenshot ${it.name}" } }

        waitForTag("gomode-settings")
        composeRule.onNodeWithTag("gomode-service-label").performTextReplacement("Development")
        composeRule.onNodeWithTag("gomode-service-url").performTextReplacement(baseUrl)
        device.pressBack()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { !isImeVisible() }
        waitForSettingsButton()
        capture(device, directory, "service-editor")

        composeRule.onNodeWithTag("gomode-save-service").performScrollTo().performClick()
        try {
            waitForHostedFrontend()
        } catch (error: IllegalStateException) {
            device.takeScreenshot(File(directory, "failure.png"))
            throw IllegalStateException(composeRule.onRoot().printToString(), error)
        }
        waitForDom("demonstration service loaded") { "isVisible('#demo-service')" }
        waitForDom("demonstration service fonts") { "document.fonts.status === 'loaded'" }
        capture(device, directory, "hosted-service")

        waitForSettingsButton()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.onNodeWithTag("gomode-add-service").performClick()
        composeRule.onNodeWithTag("gomode-service-label").performTextReplacement("Staging")
        composeRule.onNodeWithTag("gomode-service-url").performTextReplacement(baseUrl)
        composeRule.onNodeWithTag("gomode-save-service").performScrollTo().performClick()
        waitForHostedFrontend()
        waitForSettingsButton()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        waitForTag("gomode-settings")
        capture(device, directory, "connections")

        composeRule.onNodeWithTag("gomode-voice-mode-device").performScrollTo().performClick()
        composeRule.onNodeWithTag("gomode-voice-language").performScrollTo()
        capture(device, directory, "voice-settings")

        composeRule.onNodeWithTag("gomode-manage-halo").performScrollTo().performClick()
        waitForTag("gomode-halo-screen")
        capture(device, directory, "halo")
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(
        device: UiDevice,
        directory: File,
        name: String,
    ) {
        composeRule.waitForIdle()
        // Cold device/test initialization can reset the managed SystemUI profile.
        // Reapply it after shell readiness; the owning runner restores each value.
        val hiddenIcons =
            checkNotNull(InstrumentationRegistry.getArguments().getString("gomodeIconBlacklist")) {
                "The managed screenshot runner must supply its saved-and-augmented icon profile"
            }
        check(hiddenIcons.matches(Regex("[A-Za-z0-9_,]+"))) { "Invalid SystemUI icon slot profile" }
        // UiAutomation tokenizes the command directly, without shell quoting.
        device.executeShellCommand("settings put secure icon_blacklist $hiddenIcons")
        check(device.executeShellCommand("settings get secure icon_blacklist").trim() == hiddenIcons) {
            "SystemUI icon profile was not applied"
        }
        device.executeShellCommand("settings put global sysui_demo_allowed 1")
        // Wait for actual SystemUI command receivers after visible shell readiness.
        val controllerCommand =
            "dumpsys activity service com.android.systemui/.SystemUIService DemoModeController"
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            val state = device.executeShellCommand(controllerCommand)
            state.contains("isDemoModeAllowed=true") &&
                Regex("clock : \\[[^\\]]+\\]").containsMatchIn(state) &&
                Regex("battery : \\[[^\\]]+\\]").containsMatchIn(state)
        }
        // Demo Wi-Fi events have no replay; active connectivity avoids fallback
        // satellite icons. The runner hides only the three network icon slots.
        for (
        command in
        listOf(
            "enter",
            "clock -e hhmm 0930",
            "battery -e level 100 -e plugged false",
            "notifications -e visible false",
            "network -e wifi show -e level 4 -e fully true -e mobile hide",
        )
        ) {
            device.executeShellCommand("am broadcast -a com.android.systemui.demo -e command $command")
        }
        device.waitForIdle()
        check(device.executeShellCommand(controllerCommand).contains("isInDemoMode=true")) {
            "SystemUI demo mode was reset before capturing $name"
        }
        check(device.takeScreenshot(File(directory, "$name.png"))) { "Could not capture $name" }
    }
}
