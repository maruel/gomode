// Refuses physical devices and existing or unknown credentials before the car-dial fixture lifecycle runs.
package com.fghbuild.gomode.cardialfixture

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

internal class CarDialFixtureDeviceRule(
    private val shell: (String) -> String,
    private val isDeviceSecure: () -> Boolean,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                check(shell("getprop ro.kernel.qemu").trim() == "1") {
                    "Car-dial tests require an emulator; device settings were not changed"
                }
                check(!isDeviceSecure()) {
                    "Car-dial tests require an emulator without a secure credential; device settings were not changed"
                }
                base.evaluate()
            }
        }
}
