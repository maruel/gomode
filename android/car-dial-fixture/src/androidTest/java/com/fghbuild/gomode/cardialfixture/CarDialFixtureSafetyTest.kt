// Verifies refused devices never enter the car-dial fixture's setup or teardown statements.
package com.fghbuild.gomode.cardialfixture

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class CarDialFixtureSafetyTest {
    @Test
    fun physicalDeviceRefusalDoesNotRunFixtureSetupOrTeardown() {
        assertRefused(emulator = "0", deviceSecure = false, expectedReads = listOf("getprop ro.kernel.qemu"))
    }

    @Test
    fun unknownDeviceStateRefusalDoesNotRunFixtureSetupOrTeardown() {
        assertRefused(
            emulator = "error reading system property",
            deviceSecure = false,
            expectedReads = listOf("getprop ro.kernel.qemu"),
        )
    }

    @Test
    fun secureEmulatorRefusalDoesNotRunFixtureSetupOrTeardown() {
        assertRefused(
            emulator = "1",
            deviceSecure = true,
            expectedReads = listOf("getprop ro.kernel.qemu", "isDeviceSecure"),
        )
    }

    @Test
    fun unknownCredentialStateRefusalDoesNotRunFixtureSetupOrTeardown() {
        assertRefused(
            emulator = "1",
            deviceSecure = null,
            expectedReads = listOf("getprop ro.kernel.qemu", "isDeviceSecure"),
        )
    }

    private fun assertRefused(
        emulator: String,
        deviceSecure: Boolean?,
        expectedReads: List<String>,
    ) {
        val reads = mutableListOf<String>()
        var setupRan = false
        var teardownRan = false
        val rule =
            CarDialFixtureDeviceRule(
                shell = { command ->
                    reads += command
                    check(command == "getprop ro.kernel.qemu") { "Unexpected shell operation: $command" }
                    emulator
                },
                isDeviceSecure = {
                    reads += "isDeviceSecure"
                    deviceSecure ?: error("Credential state unavailable")
                },
            )
        val fixtureLifecycle =
            object : Statement() {
                override fun evaluate() {
                    try {
                        setupRan = true
                    } finally {
                        teardownRan = true
                    }
                }
            }
        assertThrows(IllegalStateException::class.java) {
            rule.apply(fixtureLifecycle, Description.createTestDescription(javaClass, "refusal")).evaluate()
        }
        assertFalse("Refused device entered fixture setup", setupRan)
        assertFalse("Refused device entered fixture teardown with no snapshots", teardownRan)
        assertEquals("Refusal must perform read-only preflight commands", expectedReads, reads)
    }
}
