// Unit tests for the on-device text voice session state machine and helpers.
package com.fghbuild.gomode.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

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
    fun `speech fragments do not split a gateway reply and muted replies finalize on idle`() {
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
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"First sentence. "}""")
        assertFalse(
            session.state.value.transcript
                .single()
                .final,
        )
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Second sentence."}""")
        assertEquals(
            listOf(TranscriptEntry(TranscriptSpeaker.ASSISTANT, "First sentence. Second sentence.")),
            session.state.value.transcript,
        )
        connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
        assertTrue(
            session.state.value.transcript
                .single()
                .final,
        )
        session.toggleMute()
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Muted reply."}""")
        assertFalse(
            session.state.value.transcript
                .last()
                .final,
        )
        connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
        assertTrue(
            session.state.value.transcript
                .last()
                .final,
        )
        assertEquals(2, session.state.value.transcript.size)
        session.close()
    }

    @Test
    fun `gateway reply finalizes before playback ends and listening waits for playback`() {
        val speech = FakeSpeech(completeSpeechImmediately = false)
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
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"First sentence. "}""")
        speech.speechCompletions.removeAt(0).invoke()
        assertFalse(
            session.state.value.transcript
                .single()
                .final,
        )
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Last fragment"}""")
        connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
        assertEquals(
            listOf(TranscriptEntry(TranscriptSpeaker.ASSISTANT, "First sentence. Last fragment", final = true)),
            session.state.value.transcript,
        )
        assertTrue(session.state.value.speaking)
        assertFalse(speech.listening)
        speech.speechCompletions.removeAt(0).invoke()
        assertFalse(session.state.value.speaking)
        assertTrue(speech.listening)
        session.close()
    }

    @Test
    fun `interruption closes the reply without leaking its unfinished speech into the next reply`() {
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
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Interrupted fragment"}""")
        connected.onMessage(socket, """{"kind":"interrupted","source":"user"}""")
        assertTrue(
            session.state.value.transcript
                .single()
                .final,
        )
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"New reply."}""")
        connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
        assertEquals(listOf("New reply."), speech.spoken)
        assertEquals(2, session.state.value.transcript.size)
        session.close()
    }

    @Test
    fun `local reply remains one finalized row across speech fragments and tool errors`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(toolFailureResponse())
                server.start()
                val speech = FakeSpeech()
                val repo = SettingsRepository(InMemoryPreferencesDataStore())
                selectEngine(repo, OnDeviceEngine.NEUDECIDE)
                val session =
                    createSession(
                        speech = speech,
                        settingsRepo = repo,
                        setupProvider = { toolSetup(server) },
                        engineFactory = { engineType ->
                            object : OnDeviceVoiceEngine {
                                override val engineType = engineType

                                override suspend fun initialize(context: EngineSessionContext) = Unit

                                override suspend fun processTurn(
                                    input: UserTurnInput,
                                    onDelta: (String) -> Unit,
                                    onToolCall: suspend (com.caic.voicegateway.sdk.v1.ToolCall) ->
                                    kotlinx.serialization.json.JsonElement?,
                                ): EngineTurnResult {
                                    onDelta("First sentence. ")
                                    val result =
                                        onToolCall(
                                            com.caic.voicegateway.sdk.v1.ToolCall(
                                                kind = com.caic.voicegateway.sdk.v1.MessageKind.ToolCall,
                                                id = "call-1",
                                                name = "example_tool",
                                                args = kotlinx.serialization.json.JsonObject(emptyMap()),
                                            ),
                                        )
                                    assertEquals(
                                        "Unavailable",
                                        result
                                            ?.jsonObject
                                            ?.get("error")
                                            ?.jsonPrimitive
                                            ?.content,
                                    )
                                    onDelta("Second sentence.")
                                    return EngineTurnResult(assistantText = "First sentence. Second sentence.")
                                }

                                override suspend fun injectContext(text: String) = Unit

                                override fun close() = Unit
                            }
                        },
                    )
                session.connect()
                requireNotNull(speech.resultCallback)("Hello")
                val finished =
                    session.state.first {
                        it.transcript.lastOrNull()?.final == true &&
                            it.transcript.size == 3
                    }
                assertEquals(
                    listOf(
                        TranscriptEntry(TranscriptSpeaker.USER, "Hello", final = true),
                        TranscriptEntry(TranscriptSpeaker.ASSISTANT, "[example_tool] Unavailable", final = true),
                        TranscriptEntry(TranscriptSpeaker.ASSISTANT, "First sentence. Second sentence.", final = true),
                    ),
                    finished.transcript,
                )
                session.close()
            }
        }

    @Test
    fun `gateway tool diagnostic does not close or split the generated reply`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(toolFailureResponse())
                server.start()
                val speech = FakeSpeech()
                val socket = FakeWebSocket()
                var listener: WebSocketListener? = null
                val session =
                    createSession(
                        speech = speech,
                        setupProvider = { toolSetup(server) },
                        factory = { _, captured ->
                            listener = captured
                            socket
                        },
                    )
                session.connect()
                val connected = requireNotNull(listener)
                connected.onOpen(socket, upgradeResponse())
                connected.onMessage(socket, """{"kind":"session.ready"}""")
                connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"First sentence. "}""")
                connected.onMessage(socket, """{"kind":"tool.call","id":"call-1","name":"example_tool","args":{}}""")
                session.state.first { it.transcript.size == 2 }
                connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Second sentence."}""")
                connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
                assertEquals(
                    listOf(
                        TranscriptEntry(TranscriptSpeaker.ASSISTANT, "[example_tool] Unavailable", final = true),
                        TranscriptEntry(TranscriptSpeaker.ASSISTANT, "First sentence. Second sentence.", final = true),
                    ),
                    session.state.value.transcript,
                )
                session.close()
            }
        }

    private fun toolSetup(server: MockWebServer): VoiceSetup =
        testSetup().copy(mcpClient = McpClient(server.url("/mcp").toString(), "2026-07-28", { null }))

    private fun toolFailureResponse(): MockResponse =
        MockResponse().setBody(
            """
            {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","content":[],
              "isError":true,"structuredContent":{"error":"Unavailable"}}}
            """.trimIndent(),
        )

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
    fun `partial recognition replaces draft and final recognition submits one message`() {
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
        val partial = requireNotNull(speech.partialCallback)
        val result = requireNotNull(speech.resultCallback)
        val sentBefore = socket.sent.size
        partial("Send a")
        partial("Send the message")
        assertEquals(
            listOf(TranscriptEntry(TranscriptSpeaker.USER, "Send the message", final = false)),
            session.state.value.transcript,
        )
        assertEquals(sentBefore, socket.sent.size)
        result("Send the final message")
        assertEquals(
            listOf(TranscriptEntry(TranscriptSpeaker.USER, "Send the final message", final = true)),
            session.state.value.transcript,
        )
        assertEquals(sentBefore + 1, socket.sent.size)
        result("Duplicate result")
        partial("Late partial")
        connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
        partial("Stale partial from previous utterance")
        result("Stale final from previous utterance")
        assertEquals(
            "Send the final message",
            session.state.value.transcript
                .single()
                .text,
        )
        assertEquals(sentBefore + 1, socket.sent.size)
        session.close()
    }

    @Test
    fun `mute preserves drafts while idle and disconnect discard them and retired callbacks are ignored`() {
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
        val retired = requireNotNull(speech.partialCallback)
        retired("Unfinished")
        requireNotNull(speech.idle).invoke()
        assertTrue(
            session.state.value.transcript
                .isEmpty(),
        )
        retired("Late hypothesis")
        assertTrue(
            session.state.value.transcript
                .isEmpty(),
        )
        val beforeMutePartial = requireNotNull(speech.partialCallback)
        val beforeMuteResult = requireNotNull(speech.resultCallback)
        beforeMutePartial("Another draft")
        val draft = session.state.value.transcript
        val sentBeforeMute = socket.sent.size
        session.toggleMute()
        assertEquals(draft, session.state.value.transcript)
        assertFalse(session.state.value.listening)
        assertTrue(session.state.value.muted)
        beforeMutePartial("Late muted hypothesis")
        beforeMuteResult("Late muted result")
        assertEquals(draft, session.state.value.transcript)
        assertEquals(sentBeforeMute, socket.sent.size)
        session.toggleMute()
        assertEquals(draft, session.state.value.transcript)
        assertTrue(session.state.value.listening)
        beforeMutePartial("Late previous hypothesis")
        beforeMuteResult("Late previous result")
        assertEquals(draft, session.state.value.transcript)
        assertEquals(sentBeforeMute, socket.sent.size)
        requireNotNull(speech.partialCallback)("New draft")
        requireNotNull(speech.partialCallback)("")
        assertTrue(
            session.state.value.transcript
                .isEmpty(),
        )
        val beforeDisconnect = requireNotNull(speech.partialCallback)
        beforeDisconnect("Disconnected draft")
        session.disconnect()
        beforeDisconnect("Late disconnected draft")
        assertTrue(
            session.state.value.transcript
                .isEmpty(),
        )
        session.close()
    }

    @Test
    fun `incoming assistant text retires recognition without committing the draft`() {
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
        val partial = requireNotNull(speech.partialCallback)
        val result = requireNotNull(speech.resultCallback)
        partial("Unfinished user draft")
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Notice."}""")
        partial("Late partial")
        result("Late final")
        assertFalse(session.state.value.listening)
        assertFalse(speech.listening)
        assertEquals(
            "Notice.",
            session.state.value.transcript
                .single()
                .text,
        )
        assertEquals(
            TranscriptSpeaker.ASSISTANT,
            session.state.value.transcript
                .single()
                .speaker,
        )
        connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
        assertTrue(session.state.value.listening)
        partial("Retired callback after idle")
        assertEquals(1, session.state.value.transcript.size)
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

    @Test
    fun `on-device voice session connects and runs turn with NeuDecide without network websocket`() =
        runTest {
            val speech = FakeSpeech()
            var webSocketOpened = false
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            repo.updateVoiceMode(com.fghbuild.gomode.data.VoiceMode.DEVICE)
            selectEngine(repo, OnDeviceEngine.NEUDECIDE)

            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    factory = { _, _ ->
                        webSocketOpened = true
                        FakeWebSocket()
                    },
                )

            session.connect()
            assertTrue("session connects", session.state.value.connected)
            assertFalse("must not open network websocket in local engine mode", webSocketOpened)
            assertEquals("speaks initial greeting", listOf("Ready"), speech.spoken)
            assertTrue("starts listening after greeting", speech.listening)

            val onResult = requireNotNull(speech.resultCallback)
            onResult("Hello")
            assertTrue(speech.spoken.any { it.contains("Pixel 8 Pro") })
            assertTrue("resumes listening after turn", speech.listening)

            session.disconnect()
            assertFalse(session.state.value.connected)
            session.close()
        }

    @Test
    fun `missing local LLM model reports initialization failure without starting speech`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val speech = FakeSpeech()
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            repo.updateVoiceMode(com.fghbuild.gomode.data.VoiceMode.DEVICE)
            selectEngine(repo, OnDeviceEngine.LOCAL_LLM)

            val owner = LocalModelOwner(RuntimeEnvironment.getApplication(), dispatcher, readServiceContext = { "" })
            owner.select(LocalModelSelection("en-US", { testSetup() }, { true }))
            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    ioDispatcher = dispatcher,
                    localModelOwner = owner,
                )
            session.connect()
            testScheduler.runCurrent()
            assertFalse(session.state.value.connected)
            assertTrue(
                session.state.value.error
                    .orEmpty()
                    .contains("make android-push-model"),
            )
            assertTrue(speech.spoken.isEmpty())
            assertFalse(speech.listening)

            session.disconnect()
            session.close()
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `startup preparation starts no audio and first voice call reuses model and discovery`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val speech = FakeSpeech()
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            repo.updateVoiceMode(com.fghbuild.gomode.data.VoiceMode.DEVICE)
            selectEngine(repo, OnDeviceEngine.LOCAL_LLM)

            var allocations = 0
            var discoveries = 0
            val calls = FakeVoiceCallController()
            val owner =
                LocalModelOwner(RuntimeEnvironment.getApplication(), dispatcher, readServiceContext = { "" }) {
                    LocalLlmEngine(RuntimeEnvironment.getApplication(), { _, _ ->
                        allocations++
                        object : EdgeLlmRunner {
                            override val isModelLoaded = true
                            override val backendName = "test"

                            override suspend fun generateStream(message: Message) =
                                kotlinx.coroutines.flow.flowOf(Message.model("Hello"))

                            override fun replaceConversation(config: ConversationConfig) = Unit

                            override fun close() = Unit
                        }
                    }, dispatcher)
                }
            owner.select(
                LocalModelSelection("en-US", {
                    discoveries++
                    testSetup()
                }, { true }),
            )

            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    ioDispatcher = dispatcher,
                    calls = calls,
                    localModelOwner = owner,
                    setupProvider = { error("Connect must reuse startup discovery") },
                    engineFactory = { error("Connect must reuse startup allocation") },
                )

            testScheduler.runCurrent()
            assertTrue(speech.spoken.isEmpty())
            assertFalse(speech.listening)
            assertEquals(0, calls.started)
            session.connect()
            testScheduler.runCurrent()
            assertTrue(session.state.value.connected)
            assertEquals(1, allocations)
            assertEquals(1, discoveries)
            assertEquals(1, calls.started)
            assertNull(session.state.value.connectStatus)
            requireNotNull(speech.resultCallback)("Hi")
            testScheduler.runCurrent()
            assertTrue(speech.spoken.contains("Hello"))
            session.disconnect()
            session.close()
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `disconnect during preparation preserves native owner and later connect reuses its result`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val speech = FakeSpeech()
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            repo.updateVoiceMode(com.fghbuild.gomode.data.VoiceMode.DEVICE)
            selectEngine(repo, OnDeviceEngine.LOCAL_LLM)
            val finishSetup = CompletableDeferred<Unit>()
            val calls = FakeVoiceCallController()
            val application = RuntimeEnvironment.getApplication()
            var allocations = 0
            val owner =
                LocalModelOwner(RuntimeEnvironment.getApplication(), dispatcher, readServiceContext = { "" }) {
                    LocalLlmEngine(RuntimeEnvironment.getApplication(), { _, _ ->
                        allocations++
                        object : EdgeLlmRunner {
                            override val isModelLoaded = true
                            override val backendName = "test"

                            override suspend fun generateStream(message: Message) =
                                kotlinx.coroutines.flow.flowOf(Message.model("Hello"))

                            override fun replaceConversation(config: ConversationConfig) = Unit

                            override fun close() = Unit
                        }
                    }, dispatcher)
                }
            owner.select(
                LocalModelSelection("en-US", {
                    finishSetup.await()
                    testSetup()
                }, { true }),
            )
            val session =
                createSession(
                    speech = speech,
                    calls = calls,
                    settingsRepo = repo,
                    ioDispatcher = dispatcher,
                    localModelOwner = owner,
                )
            session.connect()
            testScheduler.runCurrent()
            assertNull(
                "Preparation cannot start a microphone foreground service",
                shadowOf(application).nextStartedService,
            )
            assertEquals(0, calls.started)
            assertEquals(0, calls.activated)
            assertTrue(speech.spoken.isEmpty())
            assertFalse(speech.listening)
            session.disconnect()
            finishSetup.complete(Unit)
            testScheduler.runCurrent()
            assertFalse(session.state.value.connected)
            assertTrue(speech.spoken.isEmpty())
            assertEquals(1, allocations)
            assertNull(shadowOf(application).nextStartedService)
            session.connect()
            testScheduler.runCurrent()
            assertTrue(session.state.value.connected)
            assertEquals(1, allocations)
            assertEquals(1, calls.started)
            assertEquals(VoiceService::class.java.name, shadowOf(application).nextStartedService.component?.className)
            session.close()
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `disconnect during local turn drains native work before reconnect and drops stale speech`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val speech = FakeSpeech()
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            repo.updateVoiceMode(com.fghbuild.gomode.data.VoiceMode.DEVICE)
            selectEngine(repo, OnDeviceEngine.LOCAL_LLM)
            val finishTurn = CompletableDeferred<Unit>()
            var allocations = 0
            val events = mutableListOf<String>()
            val owner =
                LocalModelOwner(RuntimeEnvironment.getApplication(), dispatcher, readServiceContext = { "" }) {
                    LocalLlmEngine(RuntimeEnvironment.getApplication(), { _, _ ->
                        val id = ++allocations
                        events.add("allocate $id")
                        object : EdgeLlmRunner {
                            override val isModelLoaded = true
                            override val backendName = "test"

                            override suspend fun generateStream(message: Message) =
                                kotlinx.coroutines.flow.flow {
                                    finishTurn.await()
                                    events.add("native done")
                                    emit(Message.model("Stale answer"))
                                    emit(
                                        Message.model(
                                            toolCalls =
                                                listOf(
                                                    com.google.ai.edge.litertlm
                                                        .ToolCall("stale", emptyMap()),
                                                ),
                                        ),
                                    )
                                }

                            override fun replaceConversation(config: ConversationConfig) {
                                events.add("reset $id")
                            }

                            override fun close() {
                                events.add("close $id")
                            }
                        }
                    }, dispatcher)
                }
            owner.select(LocalModelSelection("en-US", { testSetup() }, { true }))
            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    ioDispatcher = dispatcher,
                    localModelOwner = owner,
                )
            session.connect()
            testScheduler.runCurrent()
            requireNotNull(speech.resultCallback)("Hi")
            testScheduler.runCurrent()
            session.disconnect()
            session.connect()
            testScheduler.runCurrent()
            assertFalse(session.state.value.connected)
            assertEquals(listOf("allocate 1"), events)
            finishTurn.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(listOf("allocate 1", "native done", "reset 1"), events)
            assertEquals(1, allocations)
            assertTrue(session.state.value.connected)
            assertFalse(speech.spoken.contains("Stale answer"))
            session.close()
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `selection before disconnect invalidates native turn before async disposal`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val speech = FakeSpeech()
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            repo.updateVoiceMode(com.fghbuild.gomode.data.VoiceMode.DEVICE)
            selectEngine(repo, OnDeviceEngine.LOCAL_LLM)
            val finishTurn = CompletableDeferred<Unit>()
            var generations = 0
            var closes = 0
            val owner =
                LocalModelOwner(RuntimeEnvironment.getApplication(), dispatcher, readServiceContext = { "" }) {
                    LocalLlmEngine(RuntimeEnvironment.getApplication(), { _, _ ->
                        object : EdgeLlmRunner {
                            override val isModelLoaded = true
                            override val backendName = "test"

                            override suspend fun generateStream(message: Message) =
                                kotlinx.coroutines.flow.flow {
                                    generations++
                                    finishTurn.await()
                                    emit(
                                        Message.model(
                                            toolCalls =
                                                listOf(
                                                    com.google.ai.edge.litertlm
                                                        .ToolCall("stale", emptyMap()),
                                                ),
                                        ),
                                    )
                                }

                            override fun replaceConversation(config: ConversationConfig) = Unit

                            override fun close() {
                                closes++
                            }
                        }
                    }, dispatcher)
                }
            owner.select(
                LocalModelSelection("en-US", {
                    testSetup().copy(
                        tools =
                            listOf(
                                com.fghbuild.mcp.sdk.v1
                                    .ToolDescriptor(
                                        name = "stale",
                                        inputSchema =
                                            kotlinx.serialization.json.Json
                                                .parseToJsonElement("{}"),
                                    ),
                            ),
                    )
                }, { true }),
            )
            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    ioDispatcher = dispatcher,
                    localModelOwner = owner,
                )
            session.connect()
            testScheduler.runCurrent()
            requireNotNull(speech.resultCallback)("Run")
            testScheduler.runCurrent()
            // Complete native work before the queued selection-disposal worker starts.
            finishTurn.complete(Unit)
            owner.select(null)
            session.disconnect()
            testScheduler.runCurrent()
            assertFalse(session.state.value.connected)
            assertEquals(1, generations)
            assertEquals(1, closes)
            session.close()
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `native hang up releases retained model without another inference round`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val speech = FakeSpeech()
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            repo.updateVoiceMode(com.fghbuild.gomode.data.VoiceMode.DEVICE)
            selectEngine(repo, OnDeviceEngine.LOCAL_LLM)
            var generations = 0
            var resets = 0
            val owner =
                LocalModelOwner(RuntimeEnvironment.getApplication(), dispatcher, readServiceContext = { "" }) {
                    LocalLlmEngine(RuntimeEnvironment.getApplication(), { _, _ ->
                        object : EdgeLlmRunner {
                            override val isModelLoaded = true
                            override val backendName = "test"

                            override suspend fun generateStream(message: Message) =
                                kotlinx.coroutines.flow.flow {
                                    generations++
                                    emit(
                                        Message.model(
                                            toolCalls =
                                                listOf(
                                                    com.google.ai.edge.litertlm
                                                        .ToolCall(HANG_UP_TOOL_NAME, emptyMap()),
                                                    com.google.ai.edge.litertlm
                                                        .ToolCall(HANG_UP_TOOL_NAME, emptyMap()),
                                                ),
                                        ),
                                    )
                                }

                            override fun replaceConversation(config: ConversationConfig) {
                                resets++
                            }

                            override fun close() = Unit
                        }
                    }, dispatcher)
                }
            owner.select(
                LocalModelSelection("en-US", {
                    testSetup().copy(
                        tools =
                            listOf(
                                com.fghbuild.mcp.sdk.v1
                                    .ToolDescriptor(
                                        name = HANG_UP_TOOL_NAME,
                                        inputSchema =
                                            kotlinx.serialization.json.Json
                                                .parseToJsonElement("{}"),
                                    ),
                            ),
                    )
                }, { true }),
            )
            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    ioDispatcher = dispatcher,
                    localModelOwner = owner,
                )
            session.connect()
            testScheduler.runCurrent()
            requireNotNull(speech.resultCallback)("Hang up")
            testScheduler.runCurrent()
            assertFalse(session.state.value.connected)
            assertEquals(1, generations)
            assertEquals(1, resets)
            session.close()
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `on-device voice session runs Needle Whistle engine with confidence threshold tool dispatch`() =
        runTest {
            val speech = FakeSpeech()
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            repo.updateVoiceMode(com.fghbuild.gomode.data.VoiceMode.DEVICE)
            selectEngine(repo, OnDeviceEngine.NEEDLE_WHISTLE)

            var turnProcessed = false
            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    engineFactory = { engineType ->
                        object : OnDeviceVoiceEngine {
                            override val engineType = engineType

                            override suspend fun initialize(context: EngineSessionContext) = Unit

                            override suspend fun processTurn(
                                input: UserTurnInput,
                                onDelta: (String) -> Unit,
                                onToolCall: suspend (
                                    com.caic.voicegateway.sdk.v1.ToolCall,
                                ) -> kotlinx.serialization.json.JsonElement?,
                            ): EngineTurnResult {
                                onDelta("Recognized: hang up (confidence: 0.95). Executing hang_up…")
                                onToolCall(
                                    com.caic.voicegateway.sdk.v1.ToolCall(
                                        kind = com.caic.voicegateway.sdk.v1.MessageKind.ToolCall,
                                        id = "test-call-1",
                                        name = HANG_UP_TOOL_NAME,
                                        args = kotlinx.serialization.json.JsonObject(emptyMap()),
                                    ),
                                )
                                turnProcessed = true
                                return EngineTurnResult(assistantText = "Done")
                            }

                            override suspend fun injectContext(text: String) = Unit

                            override fun close() = Unit
                        }
                    },
                )

            session.connect()
            val onResult = requireNotNull(speech.resultCallback)
            onResult("hang up the call")
            assertTrue("engine processed turn", turnProcessed)
            assertFalse("hang up tool disconnected session", session.state.value.connected)
            session.close()
        }

    @Test
    fun `offline fallback runs on-device engine when remote gateway setup fails`() =
        runTest {
            val speech = FakeSpeech()
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            repo.updateVoiceMode(com.fghbuild.gomode.data.VoiceMode.DEVICE)
            selectEngine(repo, OnDeviceEngine.NEUDECIDE)

            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    setupProvider = {
                        throw VoiceSetupException("Gateway unreachable / offline")
                    },
                )

            session.connect()
            assertTrue("connects using standalone offline setup", session.state.value.connected)
            assertNull("no error reported", session.state.value.error)
            assertEquals(listOf("Ready"), speech.spoken)

            session.disconnect()
            session.close()
        }

    private suspend fun selectEngine(
        repository: SettingsRepository,
        engine: OnDeviceEngine,
    ) {
        repository.updateOnDeviceEngine(engine)
        // Saving preferences does not wait for the repository's IO collector to publish them.
        repository.settings.first { it.onDeviceEngine == engine }
    }

    private fun createSession(
        speech: DeviceSpeech,
        calls: VoiceCallController = NoopVoiceCallController(),
        languageTag: String = "en-US",
        settingsRepo: SettingsRepository = SettingsRepository(InMemoryPreferencesDataStore()),
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        engineFactory: (OnDeviceEngine) -> OnDeviceVoiceEngine = {
            createOnDeviceEngine(RuntimeEnvironment.getApplication(), it, ioDispatcher)
        },
        setupProvider: suspend () -> VoiceSetup = { testSetup() },
        localModelOwner: LocalModelOwner = (RuntimeEnvironment.getApplication() as GoModeApplication).localModelOwner,
        factory: (Request, WebSocketListener) -> WebSocket = { _, _ -> FakeWebSocket() },
    ): DeviceVoiceSession =
        DeviceVoiceSession(
            appContext = RuntimeEnvironment.getApplication(),
            settingsRepository = settingsRepo,
            speech = speech,
            languageTag = languageTag,
            localModelOwner = localModelOwner,
            voiceChimePlayer = NoopChime(),
            callController = calls,
            setupProvider = setupProvider,
            webSocketFactory = factory,
            ioDispatcher = ioDispatcher,
            engineFactory = engineFactory,
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

    private class FakeSpeech(
        private val completeSpeechImmediately: Boolean = true,
    ) : DeviceSpeech {
        val spoken = mutableListOf<String>()
        val speechCompletions = mutableListOf<() -> Unit>()
        var listening = false
        var listeningStarts = 0
        var idle: (() -> Unit)? = null
        var resultCallback: ((String) -> Unit)? = null
        var partialCallback: ((String) -> Unit)? = null

        override fun isAvailable(): Boolean = true

        override fun startListening(
            onResult: (String) -> Unit,
            onPartial: (String) -> Unit,
            onIdle: () -> Unit,
            onError: (String) -> Unit,
        ) {
            listeningStarts++
            listening = true
            resultCallback = onResult
            partialCallback = onPartial
            idle = onIdle
        }

        override fun stopListening() {
            listening = false
            idle = null
            resultCallback = null
            partialCallback = null
        }

        override fun speak(
            text: String,
            onDone: () -> Unit,
            onError: (String) -> Unit,
        ) {
            spoken.add(text)
            if (completeSpeechImmediately) onDone() else speechCompletions.add(onDone)
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
