// Tests language readiness, model-download outcomes, and cancellation against Android speech APIs.
package com.fghbuild.gomode.voice

import android.content.Intent
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowSpeechRecognizerImpl
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [DownloadSpeechRecognizerShadow::class])
class SpeechLanguageTest {
    @Before
    fun prepare() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        DownloadSpeechRecognizerShadow.requests.clear()
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test
    fun `installed downloadable pending and unsupported languages remain distinct`() {
        val support =
            RecognitionSupport
                .Builder()
                .addInstalledOnDeviceLanguage("en-US")
                .addSupportedOnDeviceLanguage("fr-CA")
                .addPendingOnDeviceLanguage("de-DE")
                .build()
        assertEquals(SpeechLanguageState.Ready, classifySpeechLanguage(support, "en-us"))
        assertEquals(SpeechLanguageState.Missing, classifySpeechLanguage(support, "fr-CA"))
        assertEquals(SpeechLanguageState.Pending, classifySpeechLanguage(support, "de-DE"))
        assertEquals(SpeechLanguageState.Unsupported, classifySpeechLanguage(support, "ja-JP"))
        assertTrue(classifySpeechLanguage(support, "fr") is SpeechLanguageState.Unverified)
        assertTrue(classifySpeechLanguage(support, "en-GB") is SpeechLanguageState.Unverified)
    }

    @Test
    fun `preflight uses the same recognizer for listening`() =
        runTest {
            val speech = AndroidDeviceSpeech(RuntimeEnvironment.getApplication(), "fr-CA")
            val check = async { speech.checkLanguage() }
            runCurrent()
            val recognizer = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            shadowOf(recognizer).triggerSupportResult(
                RecognitionSupport.Builder().addInstalledOnDeviceLanguage("fr-CA").build(),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SpeechLanguageState.Ready, check.await())
            assertFalse(shadowOf(recognizer).isDestroyed)
            speech.startListening({}, {}, {}, { error(it) })
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ShadowSpeechRecognizer.getLatestSpeechRecognizer() === recognizer)
            assertEquals(
                "fr-CA",
                shadowOf(recognizer).lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
            )
            speech.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(recognizer).isDestroyed)
        }

    @Test
    fun `cancel after eventless fallback keeps the request alive through a canceled check until close`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val language = AndroidSpeechLanguage(RuntimeEnvironment.getApplication(), "en-US")
            val download = async { language.download() }
            runCurrent()
            val recognizer = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            val request = DownloadSpeechRecognizerShadow.requests.last()
            request.listener.onError(SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS)
            download.cancelAndJoin()
            assertFalse(shadowOf(recognizer).isDestroyed)
            val check = async { language.check() }
            runCurrent()
            check.cancelAndJoin()
            assertFalse(shadowOf(recognizer).isDestroyed)
            request.dispatch { it.onSuccess() }
            assertEquals(SpeechLanguageState.Idle, language.state.value)
            language.close()
            assertTrue(shadowOf(recognizer).isDestroyed)
        }

    @Test
    fun `canceled support check releases its recognizer and ignores late results`() =
        runTest {
            val language = AndroidSpeechLanguage(RuntimeEnvironment.getApplication(), "en-US")
            val check = async { language.check() }
            runCurrent()
            val recognizer = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            check.cancelAndJoin()
            shadowOf(recognizer).triggerSupportResult(
                RecognitionSupport.Builder().addInstalledOnDeviceLanguage("en-US").build(),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SpeechLanguageState.Idle, language.state.value)
            assertTrue(shadowOf(recognizer).isDestroyed)
        }

    @Test
    fun `unsupported support query permits a recognizer attempt without claiming installation`() =
        runTest {
            val language = AndroidSpeechLanguage(RuntimeEnvironment.getApplication(), "en-US")
            val check = async { language.check() }
            runCurrent()
            shadowOf(checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
                .triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(check.await() is SpeechLanguageState.Unverified)
        }

    @Test
    fun `download reports progress scheduled failure and success without treating requests as installed`() =
        runTest {
            for (outcome in listOf("scheduled", "failed", "ready", "legacy")) {
                val language = AndroidSpeechLanguage(RuntimeEnvironment.getApplication(), "fr-CA")
                val download = async { language.download() }
                runCurrent()
                val request = DownloadSpeechRecognizerShadow.requests.last()
                assertEquals("fr-CA", request.intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
                request.dispatch { it.onProgress(37) }
                assertEquals(SpeechLanguageState.Downloading(37), language.state.value)
                when (outcome) {
                    "scheduled" -> request.dispatch { it.onScheduled() }
                    "failed" -> request.dispatch { it.onError(SpeechRecognizer.ERROR_NETWORK) }
                    "legacy" -> request.dispatch { it.onError(SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS) }
                    else -> request.dispatch { it.onSuccess() }
                }
                download.await()
                when (outcome) {
                    "scheduled" -> {
                        assertEquals(SpeechLanguageState.Pending, language.state.value)
                    }

                    "failed" -> {
                        assertTrue(language.state.value is SpeechLanguageState.Failed)
                    }

                    "legacy" -> {
                        assertEquals(SpeechLanguageState.Pending, language.state.value)
                        val recognizer = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
                        assertEquals(
                            "fr-CA",
                            checkNotNull(shadowOf(recognizer).latestModelDownloadIntent)
                                .getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
                        )
                        assertFalse(shadowOf(recognizer).isDestroyed)
                        val check = async { language.check() }
                        runCurrent()
                        shadowOf(recognizer).triggerSupportResult(
                            RecognitionSupport.Builder().addInstalledOnDeviceLanguage("fr-CA").build(),
                        )
                        shadowOf(Looper.getMainLooper()).idle()
                        assertEquals(SpeechLanguageState.Ready, check.await())
                        assertFalse(shadowOf(recognizer).isDestroyed)
                        language.close()
                        assertTrue(shadowOf(recognizer).isDestroyed)
                    }

                    else -> {
                        assertEquals(SpeechLanguageState.Ready, language.state.value)
                    }
                }
            }
        }

    @Test
    fun `canceled download destroys its recognizer and ignores late completion`() =
        runTest {
            val language = AndroidSpeechLanguage(RuntimeEnvironment.getApplication(), "en-US")
            val download = async { language.download() }
            runCurrent()
            val recognizer = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            val request = DownloadSpeechRecognizerShadow.requests.last()
            download.cancelAndJoin()
            request.dispatch { it.onSuccess() }
            assertEquals(SpeechLanguageState.Pending, language.state.value)
            assertTrue(shadowOf(recognizer).isDestroyed)
        }
}

@Implements(className = "android.speech.SpeechRecognizerImpl", isInAndroidSdk = false)
class DownloadSpeechRecognizerShadow : ShadowSpeechRecognizerImpl() {
    data class Request(
        val intent: Intent,
        val executor: Executor,
        val listener: ModelDownloadListener,
    ) {
        fun dispatch(block: (ModelDownloadListener) -> Unit) {
            executor.execute { block(listener) }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Implementation(minSdk = 34)
    fun triggerModelDownload(
        intent: Intent,
        executor: Executor,
        listener: ModelDownloadListener,
    ) {
        requests.add(Request(intent, executor, listener))
    }

    companion object {
        val requests = mutableListOf<Request>()
    }
}
