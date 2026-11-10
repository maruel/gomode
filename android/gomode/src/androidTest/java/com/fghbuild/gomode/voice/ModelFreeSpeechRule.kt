// Owns a fresh, temporary platform recognizer and restores the test APK's microphone permission.
package com.fghbuild.gomode.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModelFreeSpeech

internal class ModelFreeSpeechRule : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                if (description.getAnnotation(ModelFreeSpeech::class.java) == null) {
                    base.evaluate()
                    return
                }
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val device = UiDevice.getInstance(instrumentation)
                requireModelFreeSpeechEmulator(device)
                val context = instrumentation.context
                val permission = Manifest.permission.RECORD_AUDIO
                val granted =
                    context.packageManager.checkPermission(permission, context.packageName) ==
                        PackageManager.PERMISSION_GRANTED
                val failures = mutableListOf<Throwable>()
                try {
                    check(device.executeShellCommand("pm grant ${context.packageName} $permission").isBlank())
                    val provider = TestSpeechRecognitionService::class.java.name
                    // API35 IRecognitionServiceManager.setTemporaryComponent(ComponentName), transaction 2.
                    // Android expires this override after 60 seconds if instrumentation dies.
                    overrideRecognizer(device, "i32 1 s16 ${context.packageName} s16 $provider")
                    base.evaluate()
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Throwable, // Preserve JUnit assertion failures.
                ) {
                    failures.add(failure)
                }
                try {
                    overrideRecognizer(device, "i32 0")
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Throwable, // Cleanup must not mask test failures.
                ) {
                    failures.add(failure)
                }
                try {
                    if (!granted) {
                        val result = device.executeShellCommand("pm revoke ${context.packageName} $permission")
                        check(result.isBlank()) { result }
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Throwable, // Retain permission failures.
                ) {
                    failures.add(failure)
                }
                MultipleFailureException.assertEmpty(failures)
            }
        }

    private fun overrideRecognizer(
        device: UiDevice,
        arguments: String,
    ) {
        val result = device.executeShellCommand("service call speech_recognition 2 $arguments")
        check(result.contains("Parcel(NULL)")) { result }
    }
}

internal fun requireModelFreeSpeechEmulator(device: UiDevice) {
    // Only the owned API35 emulator has this known framework TestApi transaction layout.
    check(Build.VERSION.SDK_INT == 35 && device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1") {
        "Model-free speech requires the API35 emulator"
    }
}
