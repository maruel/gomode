// Proves system shades are reset before tests and after a deliberately failing notification interaction.
package com.fghbuild.gomode

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

@LocalEmulatorOnly
class SystemUiRuleTest {
    @get:Rule
    internal val systemUi = SystemUiRule()

    @Test
    fun shadeIsRestoredWhenTestFails() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val shade = By.res("com.android.systemui", "notification_stack_scroller")
        val failure = AssertionError("simulated test failure")
        assertTrue(device.openNotification())
        assertTrue(device.wait(Until.hasObject(shade), 5_000))
        val test =
            object : Statement() {
                override fun evaluate() {
                    assertFalse(device.hasObject(shade))
                    assertTrue(device.openNotification())
                    assertTrue(device.wait(Until.hasObject(shade), 5_000))
                    throw failure
                }
            }
        try {
            SystemUiRule().apply(test, Description.createTestDescription(javaClass, "failure")).evaluate()
            fail("The original assertion must be propagated")
        } catch (actual: AssertionError) {
            assertSame(failure, actual)
        }
        assertFalse(device.hasObject(shade))
    }
}
