// Unit tests for the on-device text voice session state machine and helpers.
package com.fghbuild.gomode.voice

import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellSession
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration
import java.util.concurrent.TimeUnit

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
    fun `Android speech end sends recognized text to the remote LLM without a final callback`() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val socket = FakeWebSocket()
        var listener: WebSocketListener? = null
        val speech = AndroidDeviceSpeech(RuntimeEnvironment.getApplication(), "en-US")
        val session =
            createSession(speech) { _, captured ->
                listener = captured
                socket
            }
        try {
            session.connect()
            val backend = requireNotNull(listener)
            backend.onOpen(socket, upgradeResponse())
            backend.onMessage(socket, """{"kind":"session.ready"}""")
            backend.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
            shadowOf(Looper.getMainLooper()).idle()
            val disconnected = shadowOf(checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
            disconnected.triggerOnError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertTrue("recognizer recovery must preserve the remote LLM connection", session.state.value.connected)
            assertNull(session.state.value.error)
            val recognizer = shadowOf(checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
            recognizer.triggerOnPartialResults(
                Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("What is next"))
                },
            )
            recognizer.triggerOnEndOfSpeech()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, socket.sent.count { it.contains("user.message") && it.contains("What is next") })
            assertEquals(
                TranscriptEntry(TranscriptSpeaker.USER, "What is next", final = true),
                session.state.value.transcript
                    .single(),
            )
            backend.onMessage(socket, """{"kind":"turn.status","state":"thinking"}""")
            backend.onMessage(socket, """{"kind":"assistant.text.delta","text":"Your next step."}""")
            assertEquals(
                "Your next step.",
                session.state.value.transcript
                    .last()
                    .text,
            )
        } finally {
            session.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `shared gateway keeps one call and orders switch results before focus context`() =
        runTest {
            MockWebServer().use { server ->
                server.dispatcher =
                    object : okhttp3.mockwebserver.Dispatcher() {
                        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                            MockResponse().setBody(
                                if (request.path == "/.well-known/gomode.json") {
                                    """{"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":1,"toolGroups":[],
                        "voiceGateway":{"url":"/","required":false,"authRequired":false}}}"""
                                } else {
                                    """{"choices":[{"id":"text","label":"Text","transports":["text"]}],"defaultChoiceID":"text"}"""
                                },
                            )
                    }
                server.start()
                val repository = SettingsRepository(InMemoryPreferencesDataStore())
                val second = repository.addService("Tools only", "https://second.invalid")
                val first = repository.addService("Sponsor", server.url("/").toString())
                repository.updateVoiceMode(VoiceMode.DEVICE)
                repository.settings.first { it.activeServiceId == first && it.voiceMode == VoiceMode.DEVICE }
                val entries =
                    repository.settings.value.services.associate { service ->
                        val key = ServiceShellKey(service.id, service.url)
                        key to
                            ServiceShellSession(key).also { entry ->
                                entry.bearerStore.onNavigation(key.url)
                                check(
                                    entry.bearerStore.receive(
                                        Uri.parse(key.url),
                                        true,
                                        key.url,
                                        """{"authChanged":true,"nativeAccess":true}""",
                                    ),
                                )
                            }
                    }
                val catalog = ServiceCatalog(backgroundScope)
                val owner = VoiceConversation(repository, ServiceSettingsClient(), catalog) { entries }
                val speech = FakeSpeech(completeSpeechImmediately = false)
                val call = FakeVoiceCallController()
                val socket = FakeWebSocket()
                val listener = CompletableDeferred<WebSocketListener>()
                var transports = 0
                var pendingSwitch: CompletableDeferred<Unit>? = null
                val switchBlocked = CompletableDeferred<Unit>()
                lateinit var session: DeviceVoiceSession
                session =
                    DeviceVoiceSession(
                        RuntimeEnvironment.getApplication(),
                        repository,
                        languageTag = "en-US",
                        speech = speech,
                        callController = call,
                        voiceConversation = owner,
                        switchServer = { id ->
                            repository.switchService(id)
                            repository.settings.first { it.activeServiceId == id }
                            session.injectText(owner.contextText())
                            pendingSwitch?.let {
                                switchBlocked.complete(Unit)
                                it.await()
                            }
                        },
                        webSocketFactory = { _, captured ->
                            transports++
                            listener.complete(captured)
                            socket
                        },
                    )
                try {
                    session.connect()
                    val backend = listener.await()
                    backend.onOpen(socket, upgradeResponse())
                    backend.onMessage(socket, """{"kind":"session.ready"}""")
                    backend.onMessage(socket, """{"kind":"assistant.text.delta","text":"Remember cedar."}""")
                    backend.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
                    assertTrue(session.state.value.speaking)
                    repository.switchService(second)
                    repository.settings.first { it.activeServiceId == second }
                    session.injectText(owner.contextText())
                    val queuedAt = socket.sent.size
                    requireNotNull(speech.partialCallback)("Continue")
                    requireNotNull(speech.resultCallback)("Continue")
                    val admitted = socket.sent.drop(queuedAt)
                    assertTrue(admitted.first().contains("context.update"))
                    assertTrue(admitted.first().contains(second))
                    assertTrue(admitted.last().contains("user.message"))
                    backend.onMessage(socket, """{"kind":"interrupted","source":"user"}""")
                    backend.onMessage(socket, """{"kind":"turn.status","state":"thinking"}""")
                    backend.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
                    for (target in listOf(second, first)) {
                        backend.onMessage(
                            socket,
                            """{"kind":"tool.call","id":"$target","name":"switch_server","args":{"server_id":"$target"}}""",
                        )
                        repository.settings.first { it.activeServiceId == target }
                        assertTrue(socket.sent.last().contains("tool.result"))
                        val toolResultIndex = socket.sent.lastIndex
                        val usersBefore = socket.sent.count { it.contains("user.message") }
                        val cancellationsBefore = socket.sent.count { it.contains("turn.cancel") }
                        requireNotNull(speech.resultCallback)("Use this service")
                        assertEquals(cancellationsBefore + 1, socket.sent.count { it.contains("turn.cancel") })
                        assertEquals(usersBefore, socket.sent.count { it.contains("user.message") })
                        backend.onMessage(socket, """{"kind":"interrupted","source":"user"}""")
                        val resumed = socket.sent.drop(toolResultIndex + 1)
                        assertTrue(resumed[resumed.lastIndex - 1].contains("context.update"))
                        assertTrue(resumed[resumed.lastIndex - 1].contains(target))
                        assertTrue(resumed.last().contains("user.message"))
                        backend.onMessage(socket, """{"kind":"turn.status","state":"thinking"}""")
                        backend.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
                        assertEquals(
                            "Remember cedar.",
                            session.state.value.transcript
                                .first()
                                .text,
                        )
                    }
                    val finishSwitch = CompletableDeferred<Unit>()
                    pendingSwitch = finishSwitch
                    backend.onMessage(
                        socket,
                        """{"kind":"tool.call","id":"delayed","name":"switch_server","args":{"server_id":"$second"}}""",
                    )
                    switchBlocked.await()
                    val usersBefore = socket.sent.count { it.contains("user.message") }
                    requireNotNull(speech.resultCallback)("Continue after the switch")
                    assertEquals(com.caic.voicegateway.sdk.v1.TurnState.Thinking, session.state.value.turnState)
                    backend.onMessage(socket, """{"kind":"interrupted","source":"user"}""")
                    assertEquals(com.caic.voicegateway.sdk.v1.TurnState.Thinking, session.state.value.turnState)
                    assertEquals(usersBefore, socket.sent.count { it.contains("user.message") })
                    finishSwitch.complete(Unit)
                    assertTrue(socket.sent[socket.sent.lastIndex - 2].contains("tool.result"))
                    assertTrue(socket.sent[socket.sent.lastIndex - 1].contains("context.update"))
                    assertTrue(socket.sent.last().contains("user.message"))
                    assertEquals(com.caic.voicegateway.sdk.v1.TurnState.Thinking, session.state.value.turnState)
                    assertEquals(1, transports)
                    assertEquals(1, call.started)
                    assertEquals(1, call.activated)
                    assertEquals(0, call.endedLocal)
                    assertTrue(session.state.value.connected)
                    session.disconnect()
                    assertEquals(1, call.endedLocal)
                } finally {
                    session.close()
                    entries.values.forEach { it.close() }
                    catalog.close()
                }
            }
        }

    @Test
    fun `listens throughout assistant generation and playback`() {
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
        assertTrue("listens during the greeting turn", speech.listening)

        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Hello."}""")
        assertEquals(listOf("Hello."), speech.spoken)
        assertEquals(
            "Hello.",
            session.state.value.transcript
                .single()
                .text,
        )
        assertTrue("still listening during the greeting turn", speech.listening)

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
        session.setMicrophoneMode(MicrophoneMode.MUTED)
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
    fun `gateway reply finalizes while playback and listening overlap`() {
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
        assertTrue(speech.listening)
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
                        languageTag = "fr-CA",
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
                val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val meta =
                    Json
                        .parseToJsonElement(request.body.readUtf8())
                        .jsonObject
                        .getValue("params")
                        .jsonObject
                        .getValue("_meta")
                        .jsonObject
                assertEquals("fr-CA", meta.getValue("openai/locale").jsonPrimitive.content)
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

    @Test
    fun `tool calls retain conversation identity on recovery and renew it for new conversations`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val socket = FakeWebSocket()
                var listener: WebSocketListener? = null
                val session =
                    createSession(
                        speech = FakeSpeech(),
                        languageTag = "fr-CA",
                        setupProvider = { toolSetup(server) },
                        factory = { _, captured ->
                            listener = captured
                            socket
                        },
                    )
                try {
                    val ids = mutableListOf<String>()
                    for ((preserve, inheritedID) in listOf(
                        false to null,
                        true to null,
                        false to "handoff-id",
                        false to null,
                    )) {
                        server.enqueue(toolFailureResponse())
                        session.connect(preserveTranscript = preserve, conversationID = inheritedID)
                        val connected = requireNotNull(listener)
                        connected.onOpen(socket, upgradeResponse())
                        connected.onMessage(socket, """{"kind":"session.ready"}""")
                        connected.onMessage(
                            socket,
                            """{"kind":"tool.call","id":"call","name":"example_tool","args":{}}""",
                        )
                        val request =
                            withContext(Dispatchers.IO) {
                                requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                            }
                        val meta =
                            Json
                                .parseToJsonElement(request.body.readUtf8())
                                .jsonObject
                                .getValue("params")
                                .jsonObject
                                .getValue("_meta")
                                .jsonObject
                        ids += meta.getValue("openai/session").jsonPrimitive.content
                        assertEquals("fr-CA", meta.getValue("openai/locale").jsonPrimitive.content)
                    }
                    assertTrue(listOf(ids[0], ids[1], ids[3]).all { it.matches(Regex("[0-9a-f-]{36}")) })
                    assertEquals(ids[0], ids[1])
                    assertEquals("handoff-id", ids[2])
                    assertFalse(ids[0] == ids[3])
                } finally {
                    session.close()
                }
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
        session.setMicrophoneMode(MicrophoneMode.MUTED)
        assertEquals(draft, session.state.value.transcript)
        assertFalse(session.state.value.listening)
        assertTrue(session.state.value.muted)
        beforeMutePartial("Late muted hypothesis")
        beforeMuteResult("Late muted result")
        assertEquals(draft, session.state.value.transcript)
        assertEquals(sentBeforeMute, socket.sent.size)
        session.setMicrophoneMode(MicrophoneMode.LISTENING)
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
    fun `barge in stops queued playback and suppresses stale reply until replacement turn`() {
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
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"First. Second."}""")
        assertTrue(session.state.value.listening)
        assertTrue(session.state.value.speaking)
        val retiredPlayback = speech.speechCompletions.toList()
        val stopsBefore = speech.stops
        val partial = requireNotNull(speech.partialCallback)
        partial("Wait")
        partial("Wait please")
        assertFalse(session.state.value.speaking)
        assertTrue(session.state.value.listening)
        assertEquals(stopsBefore + 1, speech.stops)
        assertEquals(1, socket.sent.count { it.contains("turn.cancel") })
        connected.onMessage(socket, """{"kind":"tool.call","id":"late","name":"example_tool","args":{}}""")
        assertTrue(
            "Retire a cancelled tool without executing it",
            socket.sent.any {
                it.contains("tool.result") && it.contains("late") && it.contains("Turn was interrupted")
            },
        )
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Discard this."}""")
        requireNotNull(speech.resultCallback)("Wait please")
        // A status already in flight from the cancelled reply cannot reopen playback.
        connected.onMessage(socket, """{"kind":"turn.status","state":"thinking"}""")
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Also discard this."}""")
        connected.onMessage(socket, """{"kind":"interrupted","source":"user"}""")
        connected.onMessage(socket, """{"kind":"turn.status","state":"thinking"}""")
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Replacement."}""")
        assertEquals(listOf("First.", "Second.", "Replacement."), speech.spoken)
        retiredPlayback.forEach { it() }
        assertTrue("late playback callbacks cannot finish the replacement", session.state.value.speaking)
        assertEquals(
            listOf(
                TranscriptEntry(TranscriptSpeaker.ASSISTANT, "First. Second.", final = true),
                TranscriptEntry(TranscriptSpeaker.USER, "Wait please", final = true),
                TranscriptEntry(TranscriptSpeaker.ASSISTANT, "Replacement."),
            ),
            session.state.value.transcript,
        )
        session.close()
    }

    @Test
    fun `rapid utterances cancel pending turns and acknowledge only their own cancellations`() {
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
        requireNotNull(speech.partialCallback)("A")
        requireNotNull(speech.resultCallback)("A")
        requireNotNull(speech.partialCallback)("B")
        assertEquals("Cancel greeting and pending A", 2, socket.sent.count { it.contains("turn.cancel") })
        requireNotNull(speech.resultCallback)("B")
        connected.onMessage(socket, """{"kind":"interrupted","source":"user"}""")
        connected.onMessage(socket, """{"kind":"turn.status","state":"thinking"}""")
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Discard A."}""")
        assertTrue(speech.spoken.isEmpty())
        connected.onMessage(socket, """{"kind":"interrupted","source":"user"}""")
        connected.onMessage(socket, """{"kind":"turn.status","state":"thinking"}""")
        requireNotNull(speech.partialCallback)("C")
        assertEquals("Cancel replacement B before its first delta", 3, socket.sent.count { it.contains("turn.cancel") })
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Discard B."}""")
        assertTrue(speech.spoken.isEmpty())
        requireNotNull(speech.resultCallback)("C")
        connected.onMessage(socket, """{"kind":"interrupted","source":"user"}""")
        connected.onMessage(socket, """{"kind":"turn.status","state":"thinking"}""")
        connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Final reply."}""")
        assertEquals(listOf("Final reply."), speech.spoken)
        assertEquals(
            listOf("A", "B", "C", "Final reply."),
            session.state.value.transcript
                .map { it.text },
        )
        session.close()
    }

    @Test
    fun `local barge in drains inference and ignores stale deltas tools and completion`() =
        runTest {
            val speech = FakeSpeech(completeSpeechImmediately = false)
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            selectEngine(repo, OnDeviceEngine.NEUDECIDE)
            val release = CompletableDeferred<Unit>()
            val turns = mutableListOf<String>()
            var staleToolRequested = false
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
                                onToolCall: suspend (com.caic.voicegateway.sdk.v1.ToolCall) ->
                                kotlinx.serialization.json.JsonElement?,
                            ): EngineTurnResult {
                                turns.add(input.text)
                                if (input.text == "First") {
                                    onDelta("First reply.")
                                    release.await()
                                    onDelta("Stale reply.")
                                    val cancellation =
                                        onToolCall(
                                            com.caic.voicegateway.sdk.v1.ToolCall(
                                                kind = com.caic.voicegateway.sdk.v1.MessageKind.ToolCall,
                                                id = "stale",
                                                name = HANG_UP_TOOL_NAME,
                                                args = kotlinx.serialization.json.JsonObject(emptyMap()),
                                            ),
                                        )
                                    assertEquals(
                                        "Turn was interrupted",
                                        cancellation
                                            ?.jsonObject
                                            ?.get("error")
                                            ?.jsonPrimitive
                                            ?.content,
                                    )
                                    staleToolRequested = true
                                } else {
                                    onDelta("Replacement.")
                                }
                                return EngineTurnResult()
                            }

                            override suspend fun injectContext(text: String) {
                                turns.add("context:$text")
                            }

                            override fun close() = Unit
                        }
                    },
                )
            session.connect()
            requireNotNull(speech.resultCallback)("First")
            requireNotNull(speech.partialCallback)("Second")
            requireNotNull(speech.resultCallback)("Second")
            assertEquals(listOf("First"), turns)
            release.complete(Unit)
            assertEquals(listOf("First", "Second"), turns)
            assertTrue(staleToolRequested)
            assertTrue(session.state.value.connected)
            assertTrue(session.state.value.listening)
            assertEquals(listOf("Ready", "First reply.", "Replacement."), speech.spoken)
            assertTrue(
                session.state.value.transcript
                    .none { it.text.contains("Stale") },
            )
            session.injectText("New focus")
            assertFalse(turns.contains("context:New focus"))
            requireNotNull(speech.partialCallback)("Third")
            requireNotNull(speech.resultCallback)("Third")
            assertEquals(listOf("context:New focus", "Third"), turns.takeLast(2))
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

        session.setMicrophoneMode(MicrophoneMode.MUTED)
        assertTrue(session.state.value.muted)
        assertFalse(speech.listening)

        session.setMicrophoneMode(MicrophoneMode.LISTENING)
        assertFalse(session.state.value.muted)
        assertTrue(speech.listening)
    }

    @Test
    fun `mute once returns to listening after the whole reply and full mute preserves speaker output`() {
        val speech = FakeSpeech(completeSpeechImmediately = false)
        val socket = FakeWebSocket()
        var listener: WebSocketListener? = null
        val session =
            createSession(speech) { _, captured ->
                listener = captured
                socket
            }
        try {
            session.connect()
            val connected = requireNotNull(listener)
            connected.onOpen(socket, upgradeResponse())
            connected.onMessage(socket, """{"kind":"session.ready"}""")
            connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
            session.setMicrophoneMode(MicrophoneMode.MUTE_ONCE)
            assertTrue(speech.listening)
            connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"First fragment."}""")
            assertFalse(speech.listening)
            assertTrue(session.state.value.muted)
            speech.speechCompletions.removeAt(0).invoke()
            assertFalse(speech.listening)
            assertEquals(MicrophoneMode.MUTE_ONCE, session.state.value.microphoneMode)
            connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Final fragment."}""")
            connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
            assertEquals(MicrophoneMode.MUTE_ONCE, session.state.value.microphoneMode)
            speech.speechCompletions.removeAt(0).invoke()
            assertTrue(speech.listening)
            assertEquals(MicrophoneMode.LISTENING, session.state.value.microphoneMode)
            session.setMicrophoneMode(MicrophoneMode.MUTE_ONCE)
            connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Interrupted."}""")
            connected.onMessage(socket, """{"kind":"interrupted","source":"user"}""")
            assertTrue(speech.listening)
            assertEquals(MicrophoneMode.LISTENING, session.state.value.microphoneMode)
            session.setMicrophoneMode(MicrophoneMode.MUTED)
            assertFalse(speech.listening)
            connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Still audible."}""")
            connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
            assertTrue(speech.spoken.contains("Still audible."))
            speech.speechCompletions.last().invoke()
            assertFalse(speech.listening)
            assertEquals(MicrophoneMode.MUTED, session.state.value.microphoneMode)
            session.setMicrophoneMode(MicrophoneMode.LISTENING)
            assertTrue(speech.listening)
        } finally {
            session.close()
        }
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
    fun `text handshake pins selected choice and preserves authentication headers`() {
        var request: Request? = null
        val session =
            createSession(
                FakeSpeech(),
                setupProvider = {
                    testSetup().copy(choiceID = "local", gatewayHeaders = mapOf("Authorization" to "Bearer scoped"))
                },
            ) { captured, _ ->
                request = captured
                FakeWebSocket()
            }
        session.connect()
        val handshake = requireNotNull(request)
        assertEquals("local", handshake.url.queryParameter("choiceID"))
        assertEquals("Bearer scoped", handshake.header("Authorization"))
        session.close()
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

            val conversation = localConversation(repo)
            val owner = LocalModelOwner(RuntimeEnvironment.getApplication(), dispatcher)
            owner.select(LocalModelSelection("en-US", { localSetup(conversation) }, { true }))
            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    ioDispatcher = dispatcher,
                    localModelOwner = owner,
                    voiceConversation = conversation,
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
            val conversation = localConversation(repo)
            val owner =
                LocalModelOwner(RuntimeEnvironment.getApplication(), dispatcher) {
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
                    localSetup(conversation)
                }, { true }),
            )

            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    ioDispatcher = dispatcher,
                    calls = calls,
                    localModelOwner = owner,
                    voiceConversation = conversation,
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
            val conversation = localConversation(repo)
            val owner =
                LocalModelOwner(RuntimeEnvironment.getApplication(), dispatcher) {
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
                    localSetup(conversation)
                }, { true }),
            )
            val session =
                createSession(
                    speech = speech,
                    calls = calls,
                    settingsRepo = repo,
                    ioDispatcher = dispatcher,
                    localModelOwner = owner,
                    voiceConversation = conversation,
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
            val conversation = localConversation(repo)
            val owner =
                LocalModelOwner(RuntimeEnvironment.getApplication(), dispatcher) {
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
            owner.select(LocalModelSelection("en-US", { localSetup(conversation) }, { true }))
            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    ioDispatcher = dispatcher,
                    localModelOwner = owner,
                    voiceConversation = conversation,
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
            val conversation = localConversation(repo)
            val owner =
                LocalModelOwner(RuntimeEnvironment.getApplication(), dispatcher) {
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
                    testSetup()
                        .copy(
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
                        ).let { localSetup(conversation, it) }
                }, { true }),
            )
            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    ioDispatcher = dispatcher,
                    localModelOwner = owner,
                    voiceConversation = conversation,
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
            val conversation = localConversation(repo)
            val owner =
                LocalModelOwner(RuntimeEnvironment.getApplication(), dispatcher) {
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
                    testSetup()
                        .copy(
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
                        ).let { localSetup(conversation, it) }
                }, { true }),
            )
            val session =
                createSession(
                    speech = speech,
                    settingsRepo = repo,
                    ioDispatcher = dispatcher,
                    localModelOwner = owner,
                    voiceConversation = conversation,
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
    fun `local engine dispatches native hang up and server listing commands`() =
        runTest {
            val speech = FakeSpeech()
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            repo.updateVoiceMode(com.fghbuild.gomode.data.VoiceMode.DEVICE)
            selectEngine(repo, OnDeviceEngine.NEEDLE_WHISTLE)
            val serverId = repo.addService("Home", "https://home.example")
            repo.settings.first { it.activeServiceId == serverId }

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
                                val servers =
                                    onToolCall(
                                        com.caic.voicegateway.sdk.v1.ToolCall(
                                            kind = com.caic.voicegateway.sdk.v1.MessageKind.ToolCall,
                                            id = "list-local",
                                            name = "list_servers",
                                            args = kotlinx.serialization.json.JsonObject(emptyMap()),
                                        ),
                                    )
                                assertTrue(servers.toString().contains(serverId))
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

    private fun localSetup(
        conversation: VoiceConversation,
        setup: VoiceSetup = testSetup(),
    ) = conversation.prepareLocal().copy(setup = setup)

    private fun localConversation(settingsRepo: SettingsRepository) =
        VoiceConversation(
            settingsRepo,
            com.fghbuild.gomode.service
                .ServiceSettingsClient(),
            com.fghbuild.gomode.service
                .ServiceCatalog(kotlinx.coroutines.CoroutineScope(Dispatchers.Main)),
        ) { emptyMap() }

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
        voiceConversation: VoiceConversation? = null,
        thinkingEarcon: ThinkingEarcon = RecordingThinkingEarcon(),
        factory: (Request, WebSocketListener) -> WebSocket = { _, _ -> FakeWebSocket() },
    ): DeviceVoiceSession {
        val localConversation =
            voiceConversation ?: if (settingsRepo.settings.value.onDeviceEngine == OnDeviceEngine.LOCAL_LLM) {
                VoiceConversation(
                    settingsRepo,
                    com.fghbuild.gomode.service
                        .ServiceSettingsClient(),
                    com.fghbuild.gomode.service
                        .ServiceCatalog(kotlinx.coroutines.CoroutineScope(Dispatchers.Main)),
                ) {
                    emptyMap()
                }.also { it.begin(false) }
            } else {
                null
            }
        return DeviceVoiceSession(
            appContext = RuntimeEnvironment.getApplication(),
            settingsRepository = settingsRepo,
            voiceConversation = localConversation,
            speech = speech,
            languageTag = languageTag,
            localModelOwner = localModelOwner,
            voiceChimePlayer = NoopChime(),
            thinkingEarcon = thinkingEarcon,
            callController = calls,
            setupProvider = setupProvider,
            webSocketFactory = factory,
            ioDispatcher = ioDispatcher,
            engineFactory = engineFactory,
        )
    }

    private class RecordingThinkingEarcon : ThinkingEarcon {
        var playing = false

        override fun setThinking(active: Boolean) {
            playing = active
        }
    }

    @Test
    fun `slow local turns play thinking music while listening through reconnect and close`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val speech = FakeSpeech()
            val earcon = RecordingThinkingEarcon()
            var finish = CompletableDeferred<Unit>()
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            selectEngine(repo, OnDeviceEngine.NEUDECIDE)
            val session =
                createSession(
                    speech,
                    settingsRepo = repo,
                    thinkingEarcon = earcon,
                    engineFactory = { type ->
                        object : OnDeviceVoiceEngine {
                            override val engineType = type

                            override suspend fun initialize(context: EngineSessionContext) = Unit

                            override suspend fun injectContext(text: String) = Unit

                            override fun close() = Unit

                            override suspend fun processTurn(
                                input: UserTurnInput,
                                onDelta: (String) -> Unit,
                                onToolCall: suspend (com.caic.voicegateway.sdk.v1.ToolCall) ->
                                kotlinx.serialization.json.JsonElement?,
                            ): EngineTurnResult {
                                finish.await()
                                return EngineTurnResult()
                            }
                        }
                    },
                )
            session.connect()
            requireNotNull(speech.resultCallback)("Take your time")
            assertFalse("quick turns should stay silent", earcon.playing)
            advanceTimeBy(199)
            runCurrent()
            assertFalse("thinking audio waits for the grace period", earcon.playing)
            advanceTimeBy(1)
            runCurrent()
            assertTrue("thinking music plays while the microphone listens", earcon.playing)
            assertTrue(speech.listening)
            requireNotNull(speech.partialCallback)("Wait")
            assertFalse("barge-in silences thinking music", earcon.playing)
            assertTrue(speech.listening)
            finish.complete(Unit)
            assertFalse(earcon.playing)
            assertTrue(speech.listening)
            finish = CompletableDeferred()
            requireNotNull(speech.resultCallback)("Another slow turn")
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue("thinking music plays while the microphone listens", earcon.playing)
            session.connect()
            assertFalse("reconnecting ends the old thinking cue", earcon.playing)
            finish.complete(Unit)
            finish = CompletableDeferred()
            requireNotNull(speech.resultCallback)("One last turn")
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue("thinking music plays while the microphone listens", earcon.playing)
            session.close()
            assertFalse(earcon.playing)
            finish.complete(Unit)
            assertFalse("late completion must not restart audio", earcon.playing)
        }

    @Test
    fun `gateway thinking music survives listening restarts and stops for speech mute and teardown`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val speech = FakeSpeech(completeSpeechImmediately = false)
            val earcon = RecordingThinkingEarcon()
            val socket = FakeWebSocket()
            var listener: WebSocketListener? = null
            val session =
                createSession(speech, thinkingEarcon = earcon) { _, captured ->
                    listener = captured
                    socket
                }
            session.connect()
            val connected = requireNotNull(listener)
            connected.onOpen(socket, upgradeResponse())
            connected.onMessage(socket, """{"kind":"session.ready"}""")

            fun thinking() {
                connected.onMessage(socket, """{"kind":"turn.status","state":"thinking"}""")
                advanceTimeBy(1_000)
                runCurrent()
                assertTrue("thinking music plays while the microphone listens", earcon.playing)
                assertTrue(speech.listening)
            }
            connected.onMessage(socket, """{"kind":"turn.status","state":"thinking"}""")
            advanceTimeBy(199)
            runCurrent()
            assertFalse(earcon.playing)
            connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
            advanceTimeBy(1_000)
            runCurrent()
            assertFalse("ending a quick turn cancels the pending earcon", earcon.playing)
            requireNotNull(speech.resultCallback)("Start")
            thinking()
            val starts = speech.listeningStarts
            requireNotNull(speech.idle).invoke()
            assertEquals(starts + 1, speech.listeningStarts)
            assertTrue(speech.listening)
            assertTrue("recognition restarts must not silence thinking music", earcon.playing)
            connected.onMessage(socket, """{"kind":"assistant.text.delta","text":"Hello."}""")
            assertFalse(earcon.playing)
            speech.speechCompletions.removeAt(0).invoke()
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue("thinking music plays while the microphone listens", earcon.playing)
            connected.onMessage(socket, """{"kind":"turn.status","state":"idle"}""")
            assertFalse(earcon.playing)
            assertTrue(speech.listening)
            requireNotNull(speech.resultCallback)("Next")
            thinking()
            session.setMicrophoneMode(MicrophoneMode.MUTED)
            assertFalse(earcon.playing)
            session.setMicrophoneMode(MicrophoneMode.LISTENING)
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue("thinking music plays while the microphone listens", earcon.playing)
            connected.onMessage(socket, """{"kind":"interrupted","source":"user"}""")
            assertFalse(earcon.playing)
            requireNotNull(speech.resultCallback)("Again")
            thinking()
            session.setError("Failed")
            assertFalse(earcon.playing)
            session.close()
            assertFalse(earcon.playing)
        }

    @Test
    fun `native tools reject non-object arguments over the device transport`() =
        runTest {
            val socket = FakeWebSocket()
            var listener: WebSocketListener? = null
            val session =
                createSession(FakeSpeech()) { _, captured ->
                    listener = captured
                    socket
                }
            try {
                session.connect()
                val connected = requireNotNull(listener)
                connected.onOpen(socket, upgradeResponse())
                connected.onMessage(socket, """{"kind":"session.ready"}""")
                for (payload in listOf("[]", "42", "null")) {
                    connected.onMessage(
                        socket,
                        """{"kind":"tool.call","id":"bad","name":"list_servers","args":$payload}""",
                    )
                    assertTrue(socket.sent.last().contains("JSON object"))
                }
            } finally {
                session.close()
            }
        }

    @Test
    fun `server switch persists focus while the device voice transport continues`() =
        runTest {
            val repo = SettingsRepository(InMemoryPreferencesDataStore())
            val first = repo.addService("First", "https://first.example")
            val second = repo.addService("Second", "https://second.example")
            repo.settings.first { it.activeServiceId == second }
            val socket = FakeWebSocket()
            var listener: WebSocketListener? = null
            val session =
                createSession(FakeSpeech(), settingsRepo = repo) { _, captured ->
                    listener = captured
                    socket
                }
            try {
                session.connect()
                val connected = requireNotNull(listener)
                connected.onOpen(socket, upgradeResponse())
                connected.onMessage(socket, """{"kind":"session.ready"}""")
                connected.onMessage(
                    socket,
                    """{"kind":"tool.call","id":"switch","name":"switch_server","args":{"server_id":"$first"}}""",
                )
                repo.settings.first { it.activeServiceId == first }
                assertFalse(socket.closed)
                assertTrue(session.state.value.connected)
                connected.onMessage(
                    socket,
                    """{"kind":"tool.call","id":"stale","name":"switch_server","args":{"server_id":"$second"}}""",
                )
                repo.settings.first { it.activeServiceId == second }
                assertEquals(second, repo.settings.value.activeServiceId)
            } finally {
                session.close()
            }
        }

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
        var stops = 0
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

        override fun stopSpeaking() {
            stops++
        }

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
