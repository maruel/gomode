// On-device speech recognition and synthesis for the text voice mode.
package com.fghbuild.gomode.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

private const val TAG = "GoModeDeviceSpeech"

// Give final decoding a short grace period after speech ends, then submit the
// latest hypothesis instead of waiting indefinitely on a provider callback.
private const val FINAL_RESULT_GRACE_MS = 750L
private const val RECOGNITION_RECOVERY_DELAY_MS = 250L

/** DeviceSpeech runs speech recognition and synthesis on the device. */
internal interface DeviceSpeech {
    val languageState: StateFlow<SpeechLanguageState>

    suspend fun checkLanguage(): SpeechLanguageState

    suspend fun downloadLanguage()

    /** isAvailable reports whether the device has an on-device speech recognizer. */
    fun isAvailable(): Boolean

    /**
     * startListening begins one utterance. onPartial replaces the live hypothesis.
     * onResult reports final text, onIdle reports no text, and onError reports a failure.
     */
    fun startListening(
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit,
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
    private val language = AndroidSpeechLanguage(context, languageTag)
    override val languageState = language.state

    override suspend fun checkLanguage() = language.check()

    override suspend fun downloadLanguage() = language.download()

    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val microphone = MicrophoneAudioSource(captureScope)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var recognitionGeneration = 0
    private var finalizationTimeout: Runnable? = null
    private var pendingRecovery: Runnable? = null
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
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
        onPartial: (String) -> Unit,
        onIdle: () -> Unit,
        onError: (String) -> Unit,
    ) {
        main.post { startListeningAttempt(onResult, onPartial, onIdle, onError, allowRecovery = true) }
    }

    @Suppress("TooGenericExceptionCaught") // Platform and pipe startup failures terminate recognition through onError.
    private fun startListeningAttempt(
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit,
        onIdle: () -> Unit,
        onError: (String) -> Unit,
        allowRecovery: Boolean,
    ) {
        cancelListening()
        val generation = ++recognitionGeneration
        val speechRecognizer =
            recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
        speechRecognizer.setRecognitionListener(
            object : RecognitionListener {
                private var lastPartial = ""

                private fun submit(text: String) {
                    if (text.isNotBlank()) onResult(text) else onIdle()
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    if (generation != recognitionGeneration || !listening || finalizationTimeout != null) return
                    val timeout =
                        Runnable {
                            if (generation == recognitionGeneration && listening) {
                                cancelListening()
                                submit(lastPartial)
                            }
                        }
                    finalizationTimeout = timeout
                    main.postDelayed(timeout, FINAL_RESULT_GRACE_MS)
                    // End-of-speech already starts provider finalization. Keep
                    // audio alive while it finishes; the timer bounds that wait.
                }

                override fun onError(error: Int) {
                    if (generation != recognitionGeneration || !listening) return
                    language.recognitionFailed(error)
                    finishListening()
                    if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                        speechRecognizer.destroy()
                        if (recognizer === speechRecognizer) recognizer = null
                        if (!allowRecovery) {
                            onError("Speech recognition service disconnected")
                            return
                        }
                        // Route or input replacement can disconnect the platform
                        // service. Rebind once without tearing down the LLM session.
                        val recovery =
                            Runnable {
                                pendingRecovery = null
                                if (generation == recognitionGeneration) {
                                    startListeningAttempt(onResult, onPartial, onIdle, onError, allowRecovery = false)
                                }
                            }
                        pendingRecovery = recovery
                        main.postDelayed(recovery, RECOGNITION_RECOVERY_DELAY_MS)
                        return
                    }
                    // A recognizer may report no match after already supplying
                    // useful text. Preserve that utterance instead of erasing it.
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        submit(lastPartial)
                        return
                    }
                    onError(
                        when (error) {
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                                "Speech language $languageTag is not installed. " +
                                    "Download it or choose another voice mode in Settings."
                            }

                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                                "Speech language $languageTag is not supported by this recognizer. " +
                                    "Choose another language or voice mode in Settings."
                            }

                            else -> {
                                "Speech recognition error $error"
                            }
                        },
                    )
                }

                override fun onResults(results: Bundle?) {
                    if (generation != recognitionGeneration || !listening) return
                    finishListening()
                    val text =
                        results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                    submit(text.ifBlank { lastPartial })
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (generation != recognitionGeneration || !listening) return
                    val text =
                        partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                    if (text.isNotBlank()) {
                        lastPartial = text
                        onPartial(text)
                    }
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) = Unit
            },
        )
        listening = true
        try {
            val source =
                microphone.open { message ->
                    if (listening) {
                        cancelListening()
                        microphone.stop()
                        onError(message)
                    }
                }
            speechRecognizer.startListening(recognitionIntent(source))
        } catch (e: Exception) {
            cancelListening()
            microphone.stop()
            onError("Speech recognition could not start: ${e.message}")
        }
    }

    override fun stopListening() {
        main.post {
            cancelListening()
            microphone.stop()
        }
    }

    private fun finishListening() {
        finalizationTimeout?.let(main::removeCallbacks)
        finalizationTimeout = null
        listening = false
        microphone.finishUtterance()
    }

    private fun cancelListening() {
        pendingRecovery?.let(main::removeCallbacks)
        pendingRecovery = null
        recognitionGeneration++
        if (!listening) return
        finishListening()
        val cancelled = recognizer
        recognizer = null
        cancelled?.cancel()
        // Retire the listener transport so callbacks from a canceled input
        // cannot be delivered to a replacement recognizer's listener.
        cancelled?.destroy()
    }

    private fun recognitionIntent(source: ParcelFileDescriptor): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
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
            language.close()
            cancelListening()
            microphone.stop()
            captureScope.cancel()
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
