// Keeps platform speech sessions available without downloaded ASR models during browser-tool tests.
package com.fghbuild.gomode.cardialfixture

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import java.util.concurrent.atomic.AtomicInteger

class FixtureSpeechRecognitionService : RecognitionService() {
    override fun onStartListening(
        recognizerIntent: Intent,
        listener: Callback,
    ) {
        starts.incrementAndGet()
        listener.readyForSpeech(Bundle())
    }

    override fun onStopListening(listener: Callback) {
        listener.results(
            Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf()) },
        )
    }

    override fun onCancel(listener: Callback) = Unit

    companion object {
        private val starts = AtomicInteger()
        val startCount: Int get() = starts.get()
    }
}
