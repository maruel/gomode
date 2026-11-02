// Exercises streamed speech filtering and raw transcripts on an emulator with controlled device speech.
package com.fghbuild.gomode.voice

import android.Manifest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.caic.voicegateway.sdk.v1.AssistantTextDelta
import com.caic.voicegateway.sdk.v1.MessageKind
import com.fghbuild.gomode.MainActivity
import com.fghbuild.gomode.data.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechTextFilterSessionTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun streamedMarkdownChangesSpeechNotTranscriptAndResetsBetweenTurns(): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = SettingsRepository(MemoryPreferences())
            val speech = RecordingSpeech()
            val socket = ControlledSocket()
            var ready = CompletableDeferred<WebSocketListener>()
            val activity = ActivityScenario.launch(MainActivity::class.java)
            val session =
                DeviceVoiceSession(
                    appContext = context,
                    settingsRepository = repository,
                    languageTag = "en-US",
                    speech = speech,
                    voiceChimePlayer = SilentChime(),
                    thinkingEarcon =
                        object : ThinkingEarcon {
                            override fun setThinking(active: Boolean) = Unit
                        },
                    setupProvider = { standaloneVoiceSetup().copy(gatewayEndpointURL = "https://gateway.example") },
                    webSocketFactory = { _, listener ->
                        ready.complete(listener)
                        socket
                    },
                    ioDispatcher = Dispatchers.Unconfined,
                )

            suspend fun connect(): WebSocketListener {
                withContext(Dispatchers.Main) { session.connect() }
                val backend = withTimeout(10_000) { ready.await() }
                withContext(Dispatchers.Main) {
                    backend.onOpen(socket, socket.upgradeResponse())
                    backend.onMessage(socket, """{"kind":"session.ready"}""")
                    yield()
                }
                return backend
            }

            suspend fun delta(
                backend: WebSocketListener,
                text: String,
            ) {
                backend.onMessage(socket, Json.encodeToString(AssistantTextDelta(MessageKind.AssistantTextDelta, text)))
                yield()
            }
            try {
                val backend = connect()
                withContext(Dispatchers.Main) {
                    val raw = "## Result\n- **Read** [the guide](https://example.com/a.b) and `snake_case` 1.25"
                    raw.forEach { delta(backend, it.toString()) }
                    assertEquals(listOf("Result"), speech.spoken)
                    backend.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
                    yield()
                    assertEquals(listOf("Result", "Read the guide and snake_case 1.25"), speech.spoken)
                    assertEquals(
                        raw,
                        session.state.value.transcript
                            .last()
                            .text,
                    )
                    delta(backend, "123")
                    backend.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
                    yield()
                    assertEquals("123", speech.spoken.last())
                    delta(backend, "[unfinished](https://")
                    backend.onMessage(socket, """{"kind":"interrupted","source":"user"}""")
                    yield()
                    delta(backend, "**Fresh**")
                    backend.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
                    yield()
                    assertEquals("Fresh", speech.spoken.last())
                    val before = speech.spoken.size
                    delta(backend, "word ".repeat(1000))
                    assertTrue("Long unpunctuated text must speak before idle", speech.spoken.size > before)
                    delta(backend, "\n```kotlin")
                    session.disconnect()
                    ready = CompletableDeferred()
                }
                val replacement = connect()
                withContext(Dispatchers.Main) {
                    delta(replacement, "**New reply**")
                    replacement.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
                    yield()
                    assertEquals("New reply", speech.spoken.last())
                }
            } finally {
                withContext(Dispatchers.Main) { session.close() }
                activity.close()
            }
        }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class RecordingSpeech : DeviceSpeech {
        override val microphoneRms = MutableStateFlow(0f)
        override val languageState = MutableStateFlow<SpeechLanguageState>(SpeechLanguageState.Ready)
        val spoken = mutableListOf<String>()

        override suspend fun checkLanguage() = languageState.value

        override suspend fun downloadLanguage() = Unit

        override fun isAvailable() = true

        override fun startListening(
            onResult: (String) -> Unit,
            onPartial: (String) -> Unit,
            onIdle: () -> Unit,
            onError: (String) -> Unit,
        ) = Unit

        override fun stopListening() = Unit

        override fun speak(
            text: String,
            onDone: () -> Unit,
            onError: (String) -> Unit,
        ) {
            spoken.add(text)
            onDone()
        }

        override fun stopSpeaking() = Unit

        override fun close() = Unit
    }

    private class SilentChime : VoiceChimePlayer {
        override fun playConnected() = Unit

        override fun playDisconnected(onComplete: () -> Unit) = onComplete()
    }

    private class ControlledSocket : WebSocket {
        override fun request(): Request = Request.Builder().url("https://gateway.example").build()

        fun upgradeResponse(): Response =
            Response
                .Builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build()

        override fun queueSize() = 0L

        override fun send(text: String) = true

        override fun send(bytes: ByteString) = true

        override fun close(
            code: Int,
            reason: String?,
        ) = true

        override fun cancel() = Unit
    }
}
