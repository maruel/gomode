// Tests Android recognition partial-result requests, revisions, and terminal callback isolation.
package com.fghbuild.gomode.voice

import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class DeviceSpeechTest {
    private fun result(text: String) =
        Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }

    @Test
    fun `language failures describe recovery and update language readiness`() {
        for ((code, expected) in listOf(
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to SpeechLanguageState.Missing,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to SpeechLanguageState.Unsupported,
        )) {
            val speech = AndroidDeviceSpeech(RuntimeEnvironment.getApplication(), "fr-CA")
            val errors = mutableListOf<String>()
            try {
                speech.startListening({}, {}, {}, errors::add)
                shadowOf(Looper.getMainLooper()).idle()
                shadowOf(checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())).triggerOnError(code)
                assertEquals(expected, speech.languageState.value)
                assertTrue(errors.single().contains("fr-CA"))
                assertTrue(errors.single().contains("Settings"))
            } finally {
                speech.close()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Test
    fun `a disconnected recognition service is recreated once without ending the voice session`() {
        val speech = AndroidDeviceSpeech(RuntimeEnvironment.getApplication(), "en-US")
        val finals = mutableListOf<String>()
        val errors = mutableListOf<String>()
        speech.startListening(finals::add, {}, {}, errors::add)
        shadowOf(Looper.getMainLooper()).idle()
        try {
            val original = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            shadowOf(original).triggerOnError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
            assertTrue(errors.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            val replacement = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            assertNotSame(original, replacement)
            assertTrue(shadowOf(original).isDestroyed)
            shadowOf(original).triggerOnError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
            shadowOf(replacement).triggerOnResults(result("Recovered input"))
            assertEquals(listOf("Recovered input"), finals)
            assertTrue(errors.isEmpty())
        } finally {
            speech.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `replacing input cancels recovery owned by the previous listener`() {
        val speech = AndroidDeviceSpeech(RuntimeEnvironment.getApplication(), "en-US")
        val retired = mutableListOf<String>()
        val current = mutableListOf<String>()
        speech.startListening(retired::add, {}, {}, { error(it) })
        shadowOf(Looper.getMainLooper()).idle()
        try {
            val original = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            shadowOf(original).triggerOnError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
            speech.startListening(current::add, {}, {}, { error(it) })
            shadowOf(Looper.getMainLooper()).idle()
            val replacement = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertSame(replacement, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            shadowOf(original).triggerOnResults(result("Old input"))
            shadowOf(replacement).triggerOnResults(result("New input"))
            assertTrue(retired.isEmpty())
            assertEquals(listOf("New input"), current)
        } finally {
            speech.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `recognition service recovery is bounded and repeated failure is reported`() {
        val speech = AndroidDeviceSpeech(RuntimeEnvironment.getApplication(), "en-US")
        val errors = mutableListOf<String>()
        speech.startListening({}, {}, {}, errors::add)
        shadowOf(Looper.getMainLooper()).idle()
        try {
            shadowOf(checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
                .triggerOnError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            val replacement = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            shadowOf(replacement).triggerOnError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertSame(replacement, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            assertEquals(listOf("Speech recognition service disconnected"), errors)
        } finally {
            speech.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `mute and close cancel pending recognition service recovery`() {
        for (close in listOf(false, true)) {
            val speech = AndroidDeviceSpeech(RuntimeEnvironment.getApplication(), "en-US")
            val errors = mutableListOf<String>()
            speech.startListening({}, {}, {}, errors::add)
            shadowOf(Looper.getMainLooper()).idle()
            try {
                val original = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
                shadowOf(original).triggerOnError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                if (close) speech.close() else speech.stopListening()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                assertSame(original, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
                assertTrue(errors.isEmpty())
            } finally {
                speech.close()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Test
    fun `a missed final match submits the last nonempty transcript`() {
        val speech = AndroidDeviceSpeech(RuntimeEnvironment.getApplication(), "en-US")
        val finals = mutableListOf<String>()
        speech.startListening(finals::add, {}, { error("Recognized speech must not become idle") }, { error(it) })
        shadowOf(Looper.getMainLooper()).idle()
        try {
            val recognizer = shadowOf(checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
            recognizer.triggerOnPartialResults(result("Send this to the agent"))
            recognizer.triggerOnPartialResults(result(""))
            recognizer.triggerOnEndOfSpeech()
            recognizer.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
            assertEquals(listOf("Send this to the agent"), finals)
            recognizer.triggerOnResults(result("Late duplicate"))
            assertEquals(1, finals.size)
        } finally {
            speech.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `speech end submits recognized text even if no final callback arrives`() {
        val speech = AndroidDeviceSpeech(RuntimeEnvironment.getApplication(), "en-US")
        val finals = mutableListOf<String>()
        speech.startListening(finals::add, {}, {}, { error(it) })
        shadowOf(Looper.getMainLooper()).idle()
        try {
            val recognizer = shadowOf(checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
            recognizer.triggerOnPartialResults(result("Send after I finish"))
            recognizer.triggerOnEndOfSpeech()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(749))
            assertTrue(finals.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(listOf("Send after I finish"), finals)
            recognizer.triggerOnResults(result("Late duplicate"))
            assertEquals(1, finals.size)
            speech.startListening(finals::add, {}, {}, { error(it) })
            shadowOf(Looper.getMainLooper()).idle()
            val restarted = shadowOf(checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
            recognizer.triggerOnResults(result("Old recognizer callback after restart"))
            assertEquals(1, finals.size)
            restarted.triggerOnPartialResults(result("Do not send canceled speech"))
            restarted.triggerOnEndOfSpeech()
            speech.stopListening()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, finals.size)
        } finally {
            speech.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `end of speech keeps supplied audio open until the final result`() {
        val speech = AndroidDeviceSpeech(RuntimeEnvironment.getApplication(), "en-US")
        val finals = mutableListOf<String>()
        val partials = mutableListOf<String>()
        speech.startListening(finals::add, partials::add, {}, { error(it) })
        shadowOf(Looper.getMainLooper()).idle()
        try {
            val recognizer = shadowOf(checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
            val source =
                checkNotNull(
                    recognizer.lastRecognizerIntent.getParcelableExtra(
                        RecognizerIntent.EXTRA_AUDIO_SOURCE,
                        ParcelFileDescriptor::class.java,
                    ),
                )
            recognizer.triggerOnPartialResults(result("Keep this input"))
            recognizer.triggerOnEndOfSpeech()
            // Robolectric's socket descriptors have no backing file for getFd().
            assertFalse(ReflectionHelpers.getField(shadowOf(source), "closed"))
            assertTrue(finals.isEmpty())
            recognizer.triggerOnResults(result("Keep this input please"))
            assertEquals(listOf("Keep this input please"), finals)
            assertTrue(ReflectionHelpers.getField(shadowOf(source), "closed"))
        } finally {
            speech.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `Android requests live hypotheses and ignores partials after final or cancellation`() {
        val speech = AndroidDeviceSpeech(RuntimeEnvironment.getApplication(), "fr-CA")
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        speech.startListening(finals::add, partials::add, {}, { error(it) })
        shadowOf(Looper.getMainLooper()).idle()
        val recognizer = shadowOf(checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
        assertTrue(recognizer.lastRecognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertEquals("fr-CA", recognizer.lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(recognizer.lastRecognizerIntent.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE))
        assertEquals(
            16_000,
            recognizer.lastRecognizerIntent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 0),
        )
        assertEquals(
            1,
            recognizer.lastRecognizerIntent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 0),
        )
        recognizer.triggerOnPartialResults(result("Turn on"))
        recognizer.triggerOnPartialResults(result("Turn off"))
        assertEquals(listOf("Turn on", "Turn off"), partials)
        assertTrue(finals.isEmpty())
        recognizer.triggerOnResults(result("Turn off the light"))
        recognizer.triggerOnResults(result("Duplicate final"))
        recognizer.triggerOnPartialResults(result("Late partial"))
        recognizer.triggerOnError(SpeechRecognizer.ERROR_CLIENT)
        assertEquals(listOf("Turn off the light"), finals)
        assertEquals(2, partials.size)
        speech.startListening(finals::add, partials::add, {}, { error(it) })
        shadowOf(Looper.getMainLooper()).idle()
        speech.stopListening()
        shadowOf(Looper.getMainLooper()).idle()
        recognizer.triggerOnPartialResults(result("Cancelled partial"))
        assertEquals(2, partials.size)
        assertTrue(recognizer.isDestroyed)
        speech.startListening(finals::add, partials::add, {}, { error(it) })
        shadowOf(Looper.getMainLooper()).idle()
        val replacement = shadowOf(checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
        assertNotSame(recognizer, replacement)
        recognizer.triggerOnError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
        replacement.triggerOnResults(result("New input after switching"))
        assertEquals(listOf("Turn off the light", "New input after switching"), finals)
        speech.close()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
