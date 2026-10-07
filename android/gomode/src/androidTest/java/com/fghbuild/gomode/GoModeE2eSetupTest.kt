// Verifies device refusal precedes keyboard, persisted settings, and Activity setup.
package com.fghbuild.gomode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class GoModeE2eSetupTest {
    @Test
    @StandaloneHostedFixture
    fun refusedDeviceDoesNotChangeSettingsOrLaunchActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        assumeTrue(
            "regression owns emulator settings",
            device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1",
        )
        val preferences = instrumentation.targetContext.filesDir.resolve("datastore/gomode_settings.preferences_pb")
        val previousPreferences = if (preferences.exists()) preferences.readBytes() else null
        val previousKeyboard = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        val refusal = IllegalStateException("Documentation captures require an emulator")
        var launched = false
        val suite =
            object : GoModeE2eTestBase() {
                override fun validateDeviceBeforeSetup(): Unit = throw refusal
            }
        val launch =
            object : Statement() {
                override fun evaluate() {
                    launched = true
                }
            }
        try {
            preferences.parentFile?.mkdirs()
            preferences.writeText("persisted settings must survive refusal")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 0")
            try {
                suite.clearSettingsRule
                    .apply(
                        launch,
                        Description.createTestDescription(javaClass, "refusal"),
                    ).evaluate()
                fail("The device guard must refuse before setup")
            } catch (error: IllegalStateException) {
                assertSame(refusal, error)
            }
            assertFalse("inner permission and Activity rules must not run", launched)
            assertEquals("0", device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim())
            assertEquals("persisted settings must survive refusal", preferences.readText())
        } finally {
            if (previousPreferences == null) {
                preferences.delete()
            } else {
                preferences.writeBytes(previousPreferences)
            }
            if (previousKeyboard == "null") {
                device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
            } else {
                device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $previousKeyboard")
            }
        }
    }
}
