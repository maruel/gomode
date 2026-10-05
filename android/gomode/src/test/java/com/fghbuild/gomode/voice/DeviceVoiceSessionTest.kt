// Unit tests for the on-device text voice session state machine and helpers.
package com.fghbuild.gomode.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DeviceVoiceSessionTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `listens only after the assistant turn and speech finish`() {
        val speech = FakeSpeech()
        val socket = FakeWebSocket()
        var listener: WebSocketListener? = null
        val session =
            createSession(speech) { _, captured ->
                listener = captured
                socket
            }

        session.connect()
        val connected = requireNotNull(listener)
        connected.onOpen(socket, upgradeResponse())
        assertTrue(socket.sent.single().contains("\"kind\":\"session.setup\""))

        connected.onMessage(socket, """{"kind":"session.ready"}""")
        assertTrue(session.state.value.connected)
        assertTrue(socket.sent.any { it.contains("\"kind\":\"user.message\"") })
        assertFalse("must not listen during the greeting turn", speech.listening)

        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Hello."}""")
        assertEquals(listOf("Hello."), speech.spoken)
        assertEquals(
            "Hello.",
            session.state.value.transcript
                .single()
                .text,
        )
        assertFalse("still in the greeting turn", speech.listening)

        connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
        assertTrue("starts listening after the turn and speech end", speech.listening)

        session.disconnect()
        assertTrue(socket.closed)
        assertFalse(session.state.value.connected)
    }

    @Test
    fun `gateway error remains visible after idle and socket closure`() {
        val speech = FakeSpeech()
        val socket = FakeWebSocket()
        var listener: WebSocketListener? = null
        val session =
            createSession(speech) { _, captured ->
                listener = captured
                socket
            }
        session.connect()
        val connected = requireNotNull(listener)
        connected.onOpen(socket, upgradeResponse())
        connected.onMessage(socket, """{"kind":"session.ready"}""")
        connected.onMessage(socket, """{"kind":"turn.status","state":"thinking"}""")
        connected.onMessage(socket, """{"kind":"error","message":"Voice turn failed (llm)","recoverable":false}""")
        connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
        connected.onClosed(socket, 1000, "")

        assertEquals("Voice turn failed (llm)", session.state.value.error)
        assertEquals(1L, session.state.value.errorId)
        assertFalse(session.state.value.connected)
        assertFalse(speech.listening)
        assertTrue(socket.closed)
        session.disconnect()
        assertEquals(null, session.state.value.error)
        session.close()
    }

    @Test
    fun `device session sends the selected speech language`() {
        val socket = FakeWebSocket()
        var listener: WebSocketListener? = null
        val session =
            createSession(FakeSpeech(), languageTag = "fr-CA") { _, captured ->
                listener = captured
                socket
            }
        session.connect()
        requireNotNull(listener).onOpen(socket, upgradeResponse())
        val setup =
            kotlinx.serialization.json.Json.decodeFromString<com.caic.voicegateway.sdk.v1.SessionSetup>(
                socket.sent.single(),
            )
        assertEquals("fr-CA", setup.voice.language)
        session.close()
    }

    @Test
    fun `mute stops listening until unmuted`() {
        val speech = FakeSpeech()
        val socket = FakeWebSocket()
        var listener: WebSocketListener? = null
        val session =
            createSession(speech) { _, captured ->
                listener = captured
                socket
            }

        session.connect()
        val connected = requireNotNull(listener)
        connected.onOpen(socket, upgradeResponse())
        connected.onMessage(socket, """{"kind":"session.ready"}""")
        connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
        assertTrue(speech.listening)

        session.toggleMute()
        assertTrue(session.state.value.muted)
        assertFalse(speech.listening)

        session.toggleMute()
        assertFalse(session.state.value.muted)
        assertTrue(speech.listening)
    }

    @Test
    fun `restarts listening after an empty recognition result`() {
        val speech = FakeSpeech()
        val socket = FakeWebSocket()
        var listener: WebSocketListener? = null
        val session =
            createSession(speech) { _, captured ->
                listener = captured
                socket
            }

        session.connect()
        val connected = requireNotNull(listener)
        connected.onOpen(socket, upgradeResponse())
        connected.onMessage(socket, """{"kind":"session.ready"}""")
        connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
        assertTrue(speech.listening)

        val idle = requireNotNull(speech.idle)
        speech.listening = false
        idle()

        assertTrue("listening restarts after an empty result", speech.listening)
    }

    @Test
    fun `ignores recognition callbacks from a superseded attempt`() {
        val speech = FakeSpeech()
        val socket = FakeWebSocket()
        var listener: WebSocketListener? = null
        val session =
            createSession(speech) { _, captured ->
                listener = captured
                socket
            }

        session.connect()
        val connected = requireNotNull(listener)
        connected.onOpen(socket, upgradeResponse())
        connected.onMessage(socket, """{"kind":"session.ready"}""")
        connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
        val staleIdle = requireNotNull(speech.idle)

        session.connect()
        val reopened = requireNotNull(listener)
        reopened.onOpen(socket, upgradeResponse())
        reopened.onMessage(socket, """{"kind":"session.ready"}""")
        reopened.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
        val startsBefore = speech.listeningStarts

        staleIdle()

        assertEquals("stale recognition must not restart listening", startsBefore, speech.listeningStarts)
    }

    @Test
    fun `starts and activates the Telecom call and ends it on overlay disconnect`() {
        val calls = FakeVoiceCallController()
        val socket = FakeWebSocket()
        var listener: WebSocketListener? = null
        val session =
            createSession(FakeSpeech(), calls) { _, captured ->
                listener = captured
                socket
            }

        session.connect()
        requireNotNull(listener).onOpen(socket, upgradeResponse())
        requireNotNull(listener).onMessage(socket, """{"kind":"session.ready"}""")
        assertEquals(1, calls.started)
        assertEquals(1, calls.activated)

        calls.audio.value =
            CallAudioState(
                devices = listOf(AudioDevice(7, 1, "Earpiece"), AudioDevice(8, 2, "Headphones")),
                selectedDeviceId = 7,
            )
        assertEquals(
            "Earpiece",
            session.state.value.availableDevices
                .first()
                .name,
        )
        session.selectAudioDevice(8)
        assertEquals(8, calls.selectedDevice)
        assertEquals("selection waits for Telecom confirmation", 7, session.state.value.selectedDeviceId)
        calls.audio.value = calls.audio.value.copy(selectedDeviceId = 8)
        assertEquals(8, session.state.value.selectedDeviceId)

        session.disconnect()

        assertEquals(1, calls.endedLocal)
    }

    @Test
    fun `reports a rejected Telecom call before opening the session`() {
        val calls = FakeVoiceCallController(startResult = false)
        var listener: WebSocketListener? = null
        val session =
            createSession(FakeSpeech(), calls) { _, captured ->
                listener = captured
                FakeWebSocket()
            }

        session.connect()

        assertTrue(session.state.value.error != null)
        assertEquals(1, calls.started)
        assertEquals(0, calls.activated)
        assertTrue("WebSocket must not open when Telecom rejects the call", listener == null)
    }

    @Test
    fun `telecom hang-up disconnects once and ignores duplicates`() {
        val calls = FakeVoiceCallController()
        val socket = FakeWebSocket()
        var listener: WebSocketListener? = null
        val session =
            createSession(FakeSpeech(), calls) { _, captured ->
                listener = captured
                socket
            }

        session.connect()
        requireNotNull(listener).onOpen(socket, upgradeResponse())
        requireNotNull(listener).onMessage(socket, """{"kind":"session.ready"}""")

        calls.telecomDisconnect()
        assertFalse(session.state.value.connected)
        assertEquals("Telecom hang-up must not re-disconnect", 0, calls.endedLocal)

        calls.telecomDisconnect()
        assertFalse(session.state.value.connected)
        assertEquals(0, calls.endedLocal)
    }

    @Test
    fun `textSessionWebSocketURL maps gateway schemes and path`() {
        assertEquals(
            "wss://gateway.example.com/api/voicegateway/v1/voice/text",
            textSessionWebSocketURL("https://gateway.example.com"),
        )
        assertEquals(
            "ws://127.0.0.1:3479/api/voicegateway/v1/voice/text",
            textSessionWebSocketURL("http://127.0.0.1:3479/"),
        )
    }

    @Test
    fun `sentence segmenter splits on sentence boundaries`() {
        val segmenter = SentenceSegmenter()
        assertEquals(listOf("One."), segmenter.push("One. Two"))
        assertEquals(listOf("Two!"), segmenter.push("!"))
        assertTrue(segmenter.push(" tail").isEmpty())
        assertEquals("tail", segmenter.flush())
    }

    private fun createSession(
        speech: DeviceSpeech,
        calls: VoiceCallController = NoopVoiceCallController(),
        languageTag: String = "en-US",
        factory: (Request, WebSocketListener) -> WebSocket,
    ): DeviceVoiceSession =
        DeviceVoiceSession(
            appContext = RuntimeEnvironment.getApplication(),
            settingsRepository = SettingsRepository(InMemoryPreferencesDataStore()),
            speech = speech,
            languageTag = languageTag,
            voiceChimePlayer = NoopChime(),
            callController = calls,
            setupProvider = { testSetup() },
            webSocketFactory = factory,
        )

    private fun testSetup(): VoiceSetup =
        VoiceSetup(
            gatewayEndpointURL = "https://gateway.example.com",
            service = null,
            tokenEndpointURL = null,
            gatewayHeaders = emptyMap(),
            mcpClient = McpClient("https://gateway.example.com/mcp", "2024-11-05", { null }),
            tools = emptyList(),
            systemInstruction = "be terse",
            serviceContextText = "",
        )

    private fun upgradeResponse(): Response =
        Response
            .Builder()
            .request(Request.Builder().url("https://gateway.example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()

    private class FakeSpeech : DeviceSpeech {
        val spoken = mutableListOf<String>()
        var listening = false
        var listeningStarts = 0
        var idle: (() -> Unit)? = null

        override fun isAvailable(): Boolean = true

        override fun startListening(
            onResult: (String) -> Unit,
            onIdle: () -> Unit,
            onError: (String) -> Unit,
        ) {
            listeningStarts++
            listening = true
            idle = onIdle
        }

        override fun stopListening() {
            listening = false
            idle = null
        }

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

    private class FakeWebSocket : WebSocket {
        val sent = mutableListOf<String>()
        var closed = false
        private val request = Request.Builder().url("https://gateway.example.com").build()

        override fun request(): Request = request

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean {
            sent.add(text)
            return true
        }

        override fun send(bytes: ByteString): Boolean = true

        override fun close(
            code: Int,
            reason: String?,
        ): Boolean {
            closed = true
            return true
        }

        override fun cancel() {
            closed = true
        }
    }

    private class NoopChime : VoiceChimePlayer {
        override fun playConnected() = Unit

        override fun playDisconnected(onComplete: () -> Unit) = Unit
    }

    private class FakeVoiceCallController(
        private val supported: Boolean = true,
        private val startResult: Boolean = true,
    ) : VoiceCallController {
        val audio = MutableStateFlow(CallAudioState())
        override val audioState = audio
        var selectedDevice: Int? = null

        override fun selectAudioDevice(deviceId: Int) {
            selectedDevice = deviceId
        }

        var started = 0
        var activated = 0
        var endedLocal = 0
        private var disconnectHandler: (() -> Unit)? = null
        private var telecomEnded = false

        override fun isSupported(): Boolean = supported

        override suspend fun start(onTelecomDisconnect: () -> Unit): Boolean {
            started++
            telecomEnded = false
            disconnectHandler = {
                telecomEnded = true
                onTelecomDisconnect()
            }
            return supported && startResult
        }

        override fun activate() {
            activated++
        }

        override fun endLocal() {
            if (telecomEnded) return
            endedLocal++
        }

        fun telecomDisconnect() {
            disconnectHandler?.invoke()
        }
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val current = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val after = transform(current.value)
            current.value = after
            return after
        }
    }
}
