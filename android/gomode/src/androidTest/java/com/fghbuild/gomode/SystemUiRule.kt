// Restores the notification and quick-settings shade before and after tests, including assertion failures.
package com.fghbuild.gomode

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement

internal class SystemUiRule : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                collapse(device)
                val failures = mutableListOf<Throwable>()
                try {
                    base.evaluate()
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Throwable, // JUnit failures include AssertionError.
                ) {
                    failures.add(failure)
                }
                try {
                    collapse(device)
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Throwable, // Retain cleanup failures too.
                ) {
                    failures.add(failure)
                }
                MultipleFailureException.assertEmpty(failures)
            }
        }

    private fun collapse(device: UiDevice) {
        // Let an in-flight expansion settle before asking System UI to collapse it.
        device.waitForIdle()
        device.executeShellCommand("cmd statusbar collapse")
        check(device.wait(Until.gone(By.res("com.android.systemui", "notification_stack_scroller")), 5_000)) {
            "Notification shade did not collapse"
        }
        device.waitForIdle()
    }
}
