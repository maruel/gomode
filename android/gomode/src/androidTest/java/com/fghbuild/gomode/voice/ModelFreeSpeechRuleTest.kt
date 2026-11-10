// Verifies temporary speech setup restores permission even when the test fails.
package com.fghbuild.gomode.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ModelFreeSpeechRuleTest {
    @get:Rule(order = 0)
    val emulator =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    requireModelFreeSpeechEmulator(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
                    base.evaluate()
                }
            }
        }

    @get:Rule(order = 1)
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    @ModelFreeSpeech
    fun platformRecognizerBecomesReadyWithoutAModel() {
        val description =
            Description.createTestDescription(
                javaClass,
                "platformRecognizerBecomesReadyWithoutAModel",
                *javaClass.getMethod("platformRecognizerBecomesReadyWithoutAModel").annotations,
            )
        val scenario =
            object : Statement() {
                override fun evaluate() {
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    val ready = CountDownLatch(1)
                    val error = AtomicInteger(0)
                    lateinit var recognizer: SpeechRecognizer
                    instrumentation.runOnMainSync {
                        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(instrumentation.targetContext)
                        recognizer.setRecognitionListener(
                            object : RecognitionListener {
                                override fun onReadyForSpeech(params: Bundle?) {
                                    ready.countDown()
                                }

                                override fun onError(value: Int) {
                                    error.set(value)
                                    ready.countDown()
                                }

                                override fun onBeginningOfSpeech() = Unit

                                override fun onRmsChanged(rms: Float) = Unit

                                override fun onBufferReceived(buffer: ByteArray?) = Unit

                                override fun onEndOfSpeech() = Unit

                                override fun onResults(results: Bundle?) = Unit

                                override fun onPartialResults(results: Bundle?) = Unit

                                override fun onEvent(
                                    event: Int,
                                    params: Bundle?,
                                ) = Unit
                            },
                        )
                        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
                    }
                    try {
                        org.junit.Assert.assertTrue(
                            "Platform recognizer did not become ready",
                            ready.await(5, TimeUnit.SECONDS),
                        )
                        assertEquals("Platform recognizer returned an error", 0, error.get())
                    } finally {
                        instrumentation.runOnMainSync { recognizer.destroy() }
                    }
                }
            }
        ModelFreeSpeechRule().apply(scenario, description).evaluate()
    }

    @Test
    @ModelFreeSpeech
    fun failingScenarioRestoresMicrophonePermission() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val permission = Manifest.permission.RECORD_AUDIO
        val before = context.packageManager.checkPermission(permission, context.packageName)
        val failure = AssertionError("Deliberate scenario failure")
        val description =
            Description.createTestDescription(
                javaClass,
                "failingScenarioRestoresMicrophonePermission",
                *javaClass.getMethod("failingScenarioRestoresMicrophonePermission").annotations,
            )
        val scenario =
            object : Statement() {
                override fun evaluate() {
                    assertEquals(
                        PackageManager.PERMISSION_GRANTED,
                        context.packageManager.checkPermission(permission, context.packageName),
                    )
                    throw failure
                }
            }
        val actual =
            assertThrows(AssertionError::class.java) { ModelFreeSpeechRule().apply(scenario, description).evaluate() }
        assertSame(failure, actual)
        assertEquals(before, context.packageManager.checkPermission(permission, context.packageName))
    }
}
