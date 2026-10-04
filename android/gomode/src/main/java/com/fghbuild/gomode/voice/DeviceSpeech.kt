// On-device speech recognition and synthesis for the text voice mode.
package com.fghbuild.gomode.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

private const val TAG = "GoModeDeviceSpeech"

/** DeviceSpeech runs speech recognition and synthesis on the device. */
internal interface DeviceSpeech {
    /** isAvailable reports whether the device has an on-device speech recognizer. */
    fun isAvailable(): Boolean

    /**
     * startListening begins one utterance. onResult reports final text, onIdle
     * reports a normal end with no text, and onError reports a failure.
     */
    fun startListening(
        onResult: (String) -> Unit,
        onIdle: () -> Unit,
        onError: (String) -> Unit,
    )

    /** stopListening cancels the current utterance without a result. */
    fun stopListening()

    /** speak queues text, invoking onDone on completion or onError on failure. */
    fun speak(
        text: String,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    )

    /** stopSpeaking drops queued and in-progress speech without calling onDone. */
    fun stopSpeaking()

    /** close releases platform resources. */
    fun close()
}

/** AndroidDeviceSpeech uses the on-device recognizer and text-to-speech engine. */
internal class AndroidDeviceSpeech(
    private val context: Context,
    private val languageTag: String,
) : DeviceSpeech {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var recognitionGeneration = 0
    private var engine: TextToSpeech? = null
    private var engineReady = false
    private var engineFailure: String? = null
    private val queuedSpeech = ArrayDeque<QueuedSpeech>()
    private val speechCallbacks = mutableMapOf<String, SpeechCallbacks>()
    private var utteranceCounter = 0

    private val utteranceListener =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                main.post { completeSpeech(utteranceId, null) }
            }

            @Suppress("DEPRECATION") // UtteranceProgressListener requires the pre-API-21 overload.
            override fun onError(utteranceId: String?) {
                main.post { completeSpeech(utteranceId, "Text-to-speech failed") }
            }

            override fun onError(
                utteranceId: String?,
                errorCode: Int,
            ) {
                Log.w(TAG, "Text-to-speech error $errorCode on $utteranceId")
                main.post { completeSpeech(utteranceId, "Text-to-speech error $errorCode") }
            }
        }

    init {
        main.post {
            engine =
                TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val languageStatus = engine?.setLanguage(Locale.forLanguageTag(languageTag))
                        if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            val failure = "Text-to-speech language $languageTag is unavailable"
                            Log.w(TAG, failure)
                            engineFailure = failure
                            failQueuedSpeech(failure)
                            return@TextToSpeech
                        }
                        engineReady = true
                        engine?.setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        engine?.setOnUtteranceProgressListener(utteranceListener)
                        flushQueuedSpeech()
                    } else {
                        Log.w(TAG, "Text-to-speech engine unavailable")
                        engineFailure = "Text-to-speech is unavailable"
                        failQueuedSpeech("Text-to-speech is unavailable")
                    }
                }
        }
    }

    override fun isAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun startListening(
        onResult: (String) -> Unit,
        onIdle: () -> Unit,
        onError: (String) -> Unit,
    ) {
        main.post {
            cancelListening()
            val generation = ++recognitionGeneration
            val speechRecognizer =
                recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
            speechRecognizer.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit

                    override fun onBeginningOfSpeech() = Unit

                    override fun onRmsChanged(rmsdB: Float) = Unit

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        if (generation != recognitionGeneration) return
                        listening = false
                        // Silence and a missed match are normal half-duplex outcomes.
                        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ) {
                            onIdle()
                            return
                        }
                        onError("Speech recognition error $error")
                    }

                    override fun onResults(results: Bundle?) {
                        if (generation != recognitionGeneration) return
                        listening = false
                        val text =
                            results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                        if (text.isNotBlank()) onResult(text) else onIdle()
                    }

                    override fun onPartialResults(partialResults: Bundle?) = Unit

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?,
                    ) = Unit
                },
            )
            listening = true
            speechRecognizer.startListening(recognitionIntent())
        }
    }

    override fun stopListening() {
        main.post { cancelListening() }
    }

    private fun cancelListening() {
        if (!listening) return
        listening = false
        recognitionGeneration++
        recognizer?.cancel()
    }

    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    override fun speak(
        text: String,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (text.isBlank()) {
            onDone()
            return
        }
        main.post {
            val failure = engineFailure
            if (failure != null) {
                onError(failure)
                return@post
            }
            if (!engineReady) {
                queuedSpeech.addLast(QueuedSpeech(text, onDone, onError))
                return@post
            }
            speakOnMain(text, onDone, onError)
        }
    }

    private fun flushQueuedSpeech() {
        val queued = queuedSpeech.toList()
        queuedSpeech.clear()
        queued.forEach { item -> speakOnMain(item.text, item.onDone, item.onError) }
    }

    private fun failQueuedSpeech(message: String) {
        val queued = queuedSpeech.toList()
        queuedSpeech.clear()
        queued.firstOrNull()?.onError?.invoke(message)
    }

    private fun speakOnMain(
        text: String,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val speech = engine
        if (speech == null) {
            onError("Text-to-speech is unavailable")
            return
        }
        utteranceCounter++
        val utteranceID = "go-mode-$utteranceCounter"
        speechCallbacks[utteranceID] = SpeechCallbacks(onDone, onError)
        if (speech.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceID) == TextToSpeech.ERROR) {
            speechCallbacks.remove(utteranceID)
            onError("Text-to-speech failed")
        }
    }

    private fun completeSpeech(
        utteranceId: String?,
        error: String?,
    ) {
        val callbacks = utteranceId?.let { speechCallbacks.remove(it) } ?: return
        if (error == null) callbacks.onDone() else callbacks.onError(error)
    }

    override fun stopSpeaking() {
        main.post {
            queuedSpeech.clear()
            speechCallbacks.clear()
            engine?.stop()
        }
    }

    override fun close() {
        main.post {
            cancelListening()
            recognizer?.destroy()
            recognizer = null
            queuedSpeech.clear()
            speechCallbacks.clear()
            engine?.stop()
            engine?.shutdown()
            engine = null
        }
    }

    private data class QueuedSpeech(
        val text: String,
        val onDone: () -> Unit,
        val onError: (String) -> Unit,
    )

    private data class SpeechCallbacks(
        val onDone: () -> Unit,
        val onError: (String) -> Unit,
    )
}
