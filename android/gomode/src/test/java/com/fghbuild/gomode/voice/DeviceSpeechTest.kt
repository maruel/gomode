// Tests Android recognition partial-result requests, revisions, and terminal callback isolation.
package com.fghbuild.gomode.voice

import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
class DeviceSpeechTest {
    private fun result(text: String) =
        Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
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
        speech.close()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
