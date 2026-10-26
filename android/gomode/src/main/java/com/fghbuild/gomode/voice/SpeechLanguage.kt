// Checks and downloads the selected on-device speech language without opening the microphone.
package com.fghbuild.gomode.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface SpeechLanguageState {
    data object Idle : SpeechLanguageState

    data object Checking : SpeechLanguageState

    data object Ready : SpeechLanguageState

    data object Missing : SpeechLanguageState

    data object Pending : SpeechLanguageState

    data class Downloading(
        val percent: Int?,
    ) : SpeechLanguageState

    data object Unsupported : SpeechLanguageState

    data class Unverified(
        val message: String,
    ) : SpeechLanguageState

    data class Failed(
        val message: String,
    ) : SpeechLanguageState
}

internal class AndroidSpeechLanguage(
    private val context: Context,
    private val languageTag: String,
) {
    private val mutableState = MutableStateFlow<SpeechLanguageState>(SpeechLanguageState.Idle)
    val state = mutableState.asStateFlow()
    private var pendingDownloadRecognizer: SpeechRecognizer? = null

    fun close() {
        pendingDownloadRecognizer?.destroy()
        pendingDownloadRecognizer = null
    }

    fun recognitionFailed(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> mutableState.value = SpeechLanguageState.Missing
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> mutableState.value = SpeechLanguageState.Unsupported
        }
    }

    private fun intent() =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    suspend fun check(): SpeechLanguageState =
        withContext(Dispatchers.Main) {
            mutableState.value = SpeechLanguageState.Checking
            val result =
                withTimeoutOrNull(10_000) { withRecognizer { query(it) } }
                    ?: SpeechLanguageState.Unverified("The speech language check timed out.")
            mutableState.value = result
            result
        }

    private suspend fun query(recognizer: SpeechRecognizer): SpeechLanguageState {
        val result =
            suspendCancellableCoroutine<SpeechLanguageState> { continuation ->
                recognizer.checkRecognitionSupport(
                    intent(),
                    context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            if (continuation.isActive) continuation.resume(classifySpeechLanguage(support, languageTag))
                        }

                        override fun onError(error: Int) {
                            val result =
                                when (error) {
                                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                                        SpeechLanguageState.Unsupported
                                    }

                                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                                        SpeechLanguageState.Missing
                                    }

                                    else -> {
                                        SpeechLanguageState.Unverified(
                                            "The recognizer could not check language support (error $error).",
                                        )
                                    }
                                }
                            if (continuation.isActive) continuation.resume(result)
                        }
                    },
                )
            }
        if (pendingDownloadRecognizer === recognizer) pendingDownloadRecognizer = null
        return result
    }

    suspend fun download() =
        withContext(Dispatchers.Main) {
            mutableState.value = SpeechLanguageState.Downloading(null)
            val result =
                withTimeoutOrNull(120_000) {
                    withRecognizer { recognizer ->
                        if (Build.VERSION.SDK_INT < 34) {
                            requestWithoutEvents(recognizer)
                        } else {
                            awaitDownload(recognizer)
                        }
                    }
                } ?: SpeechLanguageState.Pending
            mutableState.value = result
        }

    @androidx.annotation.RequiresApi(34)
    private suspend fun awaitDownload(recognizer: SpeechRecognizer): SpeechLanguageState =
        suspendCancellableCoroutine { continuation ->
            recognizer.triggerModelDownload(
                intent(),
                context.mainExecutor,
                object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) {
                        if (continuation.isActive) {
                            mutableState.value = SpeechLanguageState.Downloading(completedPercent.coerceIn(0, 100))
                        }
                    }

                    override fun onSuccess() {
                        if (continuation.isActive) continuation.resume(SpeechLanguageState.Ready)
                    }

                    override fun onScheduled() {
                        if (continuation.isActive) continuation.resume(SpeechLanguageState.Pending)
                    }

                    override fun onError(error: Int) {
                        if (continuation.isActive) {
                            continuation.resume(
                                if (error == SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS) {
                                    requestWithoutEvents(recognizer)
                                } else {
                                    SpeechLanguageState.Failed(
                                        "The recognizer could not download $languageTag (error $error). " +
                                            "Check your connection or choose another voice mode.",
                                    )
                                },
                            )
                        }
                    }
                },
            )
        }

    @Suppress("TooGenericExceptionCaught") // Platform providers can throw rather than delivering their error callback.
    private suspend fun withRecognizer(block: suspend (SpeechRecognizer) -> SpeechLanguageState): SpeechLanguageState {
        var recognizer: SpeechRecognizer? = null
        return try {
            // Reuse a fire-and-forget download's binding so its queued request is
            // delivered before the subsequent status query, even on a cold service.
            val current = pendingDownloadRecognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = current
            block(current)
        } catch (e: CancellationException) {
            mutableState.value =
                if (mutableState.value is SpeechLanguageState.Downloading) {
                    SpeechLanguageState.Pending
                } else {
                    SpeechLanguageState.Idle
                }
            throw e
        } catch (e: Exception) {
            SpeechLanguageState.Failed("Speech language setup failed: ${e.message}")
        } finally {
            if (recognizer !== pendingDownloadRecognizer) recognizer?.destroy()
        }
    }

    // A provider may support downloads without supporting their newer event listener.
    @Suppress("TooGenericExceptionCaught") // Listener exceptions cannot propagate to the suspended caller's boundary.
    private fun requestWithoutEvents(recognizer: SpeechRecognizer): SpeechLanguageState =
        try {
            recognizer.triggerModelDownload(intent())
            // This overload has no completion callback. Destroying immediately can
            // discard the request before the recognition service finishes binding.
            // The next check reuses it; closing the speech owner also releases it.
            pendingDownloadRecognizer = recognizer
            SpeechLanguageState.Pending
        } catch (e: RuntimeException) {
            SpeechLanguageState.Failed("The recognizer could not request $languageTag: ${e.message}")
        }
}

internal fun classifySpeechLanguage(
    support: RecognitionSupport,
    languageTag: String,
): SpeechLanguageState {
    fun List<String>.includesLanguage() = any { it.equals(languageTag, ignoreCase = true) }
    val variants =
        (
            support.installedOnDeviceLanguages + support.pendingOnDeviceLanguages +
                support.supportedOnDeviceLanguages
        ).filter {
            java.util.Locale
                .forLanguageTag(it)
                .language ==
                java.util.Locale
                    .forLanguageTag(languageTag)
                    .language
        }
    return when {
        support.installedOnDeviceLanguages.includesLanguage() -> {
            SpeechLanguageState.Ready
        }

        support.pendingOnDeviceLanguages.includesLanguage() -> {
            SpeechLanguageState.Pending
        }

        support.supportedOnDeviceLanguages.includesLanguage() -> {
            SpeechLanguageState.Missing
        }

        variants.isNotEmpty() -> {
            SpeechLanguageState.Unverified(
                "The recognizer lists ${variants.sorted().first()} for $languageTag. " +
                    "Try voice or select a listed regional language in Settings.",
            )
        }

        else -> {
            SpeechLanguageState.Unsupported
        }
    }
}
