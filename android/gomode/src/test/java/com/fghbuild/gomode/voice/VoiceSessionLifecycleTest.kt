// Tests that disconnected voice attempts cannot continue discovery or deliver stale tool results.
package com.fghbuild.gomode.voice

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.caic.voicegateway.sdk.v1.MessageKind
import com.caic.voicegateway.sdk.v1.ToolCall
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import org.webrtc.DataChannel
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@RunWith(RobolectricTestRunner::class)
class VoiceSessionLifecycleTest {
    private val cleanup = mutableListOf<() -> Unit>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        cleanup.asReversed().forEach { it() }
        Dispatchers.resetMain()
    }

    @Test
    fun changedOrUnavailableVoiceDuringDiscoveryStopsSetupBeforeMCP() =
        runBlocking {
            listOf("choice", "speaking voice", "recovery catalog").forEach { change ->
                val discoveryStarted = CountDownLatch(1)
                val releaseDiscovery = CountDownLatch(1)
                val server = MockWebServer()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.path) {
                                "/.well-known/gomode.json" -> {
                                    MockResponse().setBody(
                                        """
                                        {"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":1,
                                        "toolGroups":[{"name":"fixture","endpoint":"/mcp","protocolVersion":"2026-07-28",
                                        "authRequired":false}],"voiceGateway":{"required":false,"url":"/"}}}
                                        """.trimIndent(),
                                    )
                                }

                                "/api/voicegateway/v1/voice/choices" -> {
                                    discoveryStarted.countDown()
                                    releaseDiscovery.await(5, TimeUnit.SECONDS)
                                    MockResponse().setBody(
                                        """
                                        {"choices":[{"id":"live","label":"Gemini Live","transports":["webrtc"]}],
                                        "defaultChoiceID":"live"}
                                        """.trimIndent(),
                                    )
                                }

                                else -> {
                                    MockResponse().setResponseCode(500)
                                }
                            }
                    }
                server.start()
                try {
                    val base = server.url("/").toString().trimEnd('/')
                    val repository = SettingsRepository(InMemoryPreferencesDataStore())
                    repository.addService("Fixture", base)
                    repository.settings.first { it.activeServiceURL == base }
                    val attempt =
                        async(Dispatchers.IO) {
                            runCatching {
                                prepareVoiceSetup(
                                    repository,
                                    ServiceSettingsClient(),
                                    { null },
                                    pinnedVoice =
                                        if (change == "recovery catalog") {
                                            GatewayVoiceSelection(base, "live", "Orus")
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    assertTrue(discoveryStarted.await(5, TimeUnit.SECONDS))
                    when (change) {
                        "speaking voice" -> {
                            repository.updateSpeakingVoice(base, "live", "Kore")
                            repository.settings.first { it.speakingVoices[base]?.get("live") == "Kore" }
                        }

                        "choice" -> {
                            repository.updateVoiceChoice(base, "remote")
                            repository.settings.first { it.voiceChoices[base] == "remote" }
                        }
                    }
                    releaseDiscovery.countDown()
                    assertTrue(attempt.await().exceptionOrNull() is VoiceSetupException)
                    assertEquals("/.well-known/gomode.json", server.takeRequest(5, TimeUnit.SECONDS)?.path)
                    assertEquals("/api/voicegateway/v1/voice/choices", server.takeRequest(5, TimeUnit.SECONDS)?.path)
                    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
                } finally {
                    releaseDiscovery.countDown()
                    server.shutdown()
                }
            }
        }

    @Test
    fun serverToolsFollowEngineCapabilitiesAndRejectHostOverrides() =
        runBlocking {
            val server = MockWebServer()
            var hostTool = "host_tool"
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/.well-known/gomode.json") {
                            return MockResponse().setBody(
                                """{"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":1,
                            "toolGroups":[{"name":"fixture","endpoint":"/mcp","protocolVersion":"2026-07-28",
                            "authRequired":false}],"voiceGateway":{"required":false,"url":"/"}}}""",
                            )
                        }
                        val rpc = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val result =
                            when (rpc.getValue("method").toString()) {
                                "\"server/discover\"" -> {
                                    """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}},
                            "serverInfo":{"name":"fixture","version":"1"}}"""
                                }

                                "\"tools/list\"" -> {
                                    """{"tools":[{"name":"$hostTool","inputSchema":{"type":"object"}}]}"""
                                }

                                "\"resources/list\"" -> {
                                    """{"resources":[]}"""
                                }

                                else -> {
                                    error("Unexpected method: $rpc")
                                }
                            }
                        val fields = result.trim().removeSurrounding("{", "}")
                        return MockResponse().setBody(
                            """{"jsonrpc":"2.0","id":${rpc.getValue("id")},"result":{
                        "resultType":"complete","ttlMs":0,"cacheScope":"private",$fields}}""",
                        )
                    }
                }
            server.start()
            try {
                val repository = SettingsRepository(InMemoryPreferencesDataStore())
                repository.addService("Other", "https://other.example")
                repository.addService("Fixture", server.url("/").toString())
                repository.updateVoiceMode(VoiceMode.DEVICE)
                for (engine in listOf(
                    OnDeviceEngine.LOCAL_LLM,
                    OnDeviceEngine.NEUDECIDE,
                    OnDeviceEngine.NEEDLE_WHISTLE,
                )) {
                    repository.updateOnDeviceEngine(engine)
                    repository.settings.first { it.voiceMode == VoiceMode.DEVICE && it.onDeviceEngine == engine }
                    val setup = prepareVoiceSetup(repository, ServiceSettingsClient(), { null })
                    assertEquals(repository.settings.value.activeServiceId, setup.serverID)
                    val names = setup.tools.map { it.name }
                    assertTrue("host tools remain available", "host_tool" in names)
                    assertEquals(engine == OnDeviceEngine.LOCAL_LLM, "list_servers" in names)
                    assertEquals(engine == OnDeviceEngine.LOCAL_LLM, "switch_server" in names)
                }
                for (name in listOf("hang_up", "list_servers", "switch_server")) {
                    hostTool = name
                    val error =
                        runCatching {
                            prepareVoiceSetup(repository, ServiceSettingsClient(), { null })
                        }.exceptionOrNull()
                    assertTrue(error is IllegalArgumentException)
                    assertTrue(error?.message.orEmpty().contains("reserved native voice tool"))
                }
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun selectingAnUnavailableAudioDeviceReportsFailure() {
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        val session = createCloudSession(settings)
        session.selectAudioDevice(Int.MAX_VALUE)
        assertTrue(
            session.state.value.audioError
                .orEmpty()
                .contains("no longer available"),
        )
        assertTrue(session.state.value.selectedDeviceId != Int.MAX_VALUE)
        session.close()
    }

    @Test
    fun disconnectStopsPendingServiceDiscoveryBeforeMCP() =
        runBlocking {
            val server = MockWebServer()
            val discoveryStarted = CountDownLatch(1)
            val releaseDiscovery = CountDownLatch(1)
            server.dispatcher = delayedSettings(discoveryStarted, releaseDiscovery)
            server.start()
            try {
                val settings = SettingsRepository(InMemoryPreferencesDataStore())
                settings.addService(label = "old", url = server.url("/").toString())
                settings.settings.first { it.activeServiceURL.isNotBlank() }
                val session = createCloudSession(settings)

                session.connect()
                assertEquals(true, discoveryStarted.await(5, TimeUnit.SECONDS))
                assertEquals(
                    VoiceService::class.java.name,
                    shadowOf(RuntimeEnvironment.getApplication())
                        .nextStartedService.component
                        ?.className,
                )
                session.disconnect()
                releaseDiscovery.countDown()

                assertEquals("/.well-known/gomode.json", server.takeRequest(5, TimeUnit.SECONDS)?.path)
                assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
                assertNull(session.state.value.error)
            } finally {
                releaseDiscovery.countDown()
                server.shutdown()
            }
        }

    @Test
    fun recoveryKeepsAdvertisedCallerNameDuringDiscovery() =
        runBlocking {
            val server = MockWebServer()
            val discoveryStarted = CountDownLatch(1)
            val releaseDiscovery = CountDownLatch(1)
            server.dispatcher = delayedSettings(discoveryStarted, releaseDiscovery)
            server.start()
            val application = RuntimeEnvironment.getApplication()
            val settings = SettingsRepository(InMemoryPreferencesDataStore())
            settings.addService(label = "Work", url = server.url("/").toString())
            settings.settings.first { it.activeServiceURL.isNotBlank() }
            val session = createCloudSession(settings, beginConversation = true)
            try {
                // The connected call has already published the discovered name.
                VoiceForegroundLease(application).start("Example Server")
                assertEquals("Example Server", shadowOf(application).nextStartedService.getStringExtra("server_name"))

                session.connect(preserveTranscript = true)
                assertTrue(discoveryStarted.await(5, TimeUnit.SECONDS))
                assertNull(
                    "Recovery must not replace caller metadata with Work",
                    shadowOf(application).nextStartedService,
                )
            } finally {
                session.close()
                releaseDiscovery.countDown()
                server.shutdown()
            }
        }

    @Test
    fun switchingServicesStopsOldDiscoveryBeforeMCP() =
        runBlocking {
            val oldServer = MockWebServer()
            val newServer = MockWebServer()
            val oldStarted = CountDownLatch(1)
            val newStarted = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val releaseNew = CountDownLatch(1)
            oldServer.dispatcher = delayedSettings(oldStarted, releaseOld)
            newServer.dispatcher = delayedSettings(newStarted, releaseNew)
            oldServer.start()
            newServer.start()
            try {
                val settings = SettingsRepository(InMemoryPreferencesDataStore())
                settings.addService(label = "old", url = oldServer.url("/").toString())
                settings.settings.first { it.activeServiceURL == oldServer.url("/").toString().trimEnd('/') }
                val session = createCloudSession(settings)
                session.connect()
                assertEquals(true, oldStarted.await(5, TimeUnit.SECONDS))

                settings.addService(label = "new", url = newServer.url("/").toString())
                settings.settings.first { it.activeServiceURL == newServer.url("/").toString().trimEnd('/') }
                session.connect()
                assertEquals(true, newStarted.await(5, TimeUnit.SECONDS))
                releaseOld.countDown()

                assertEquals("/.well-known/gomode.json", oldServer.takeRequest(5, TimeUnit.SECONDS)?.path)
                assertNull(oldServer.takeRequest(500, TimeUnit.MILLISECONDS))
                session.disconnect()
            } finally {
                releaseOld.countDown()
                releaseNew.countDown()
                oldServer.shutdown()
                newServer.shutdown()
            }
        }

    @Test
    fun nativeServerToolsRejectNonObjectArgumentsOnTheDataChannel() =
        runBlocking {
            val settings = SettingsRepository(InMemoryPreferencesDataStore())
            val id = settings.addService("Home", "https://home.example")
            settings.settings.first { it.activeServiceId == id }
            val session = createCloudSession(settings)
            val channel = RecordingDataChannel()
            attachAuthorizedChannel(session, channel)
            try {
                for (payload in listOf("[]", "42", "null")) {
                    invokeHandleToolCall(
                        session,
                        ToolCall(MessageKind.ToolCall, payload, "list_servers", Json.parseToJsonElement(payload)),
                        channel,
                    )
                    val result =
                        Json
                            .parseToJsonElement(channel.sent.last())
                            .jsonObject
                            .getValue("result")
                            .jsonObject
                    assertTrue(result.getValue("error").toString().contains("JSON object"))
                }
                assertEquals(id, settings.settings.value.activeServiceId)
            } finally {
                session.close()
            }
        }

    @Test
    fun serverSwitchReturnsResultAndDisconnectedChannelCannotSwitchAgain() =
        runBlocking {
            val settings = SettingsRepository(InMemoryPreferencesDataStore())
            val first = settings.addService("First", "https://first.example")
            val second = settings.addService("Second", "https://second.example")
            settings.settings.first { it.activeServiceId == second }
            val session = createCloudSession(settings)
            val channel = RecordingDataChannel()
            attachAuthorizedChannel(session, channel)
            try {
                invokeHandleToolCall(
                    session,
                    ToolCall(MessageKind.ToolCall, "list", "list_servers", JsonObject(emptyMap())),
                    channel,
                )
                assertTrue(channel.sent.single().contains(first))
                invokeHandleToolCall(
                    session,
                    ToolCall(
                        MessageKind.ToolCall,
                        "switch",
                        "switch_server",
                        Json.parseToJsonElement("""{"server_id":"$first"}"""),
                    ),
                    channel,
                )
                settings.settings.first { it.activeServiceId == first }
                assertEquals(2, channel.sent.size)
                assertTrue(channel.sent.last().contains("switch"))
                assertNull(session.state.value.activeTool)
                session.disconnect()
                invokeHandleToolCall(
                    session,
                    ToolCall(
                        MessageKind.ToolCall,
                        "stale",
                        "switch_server",
                        Json.parseToJsonElement("""{"server_id":"$second"}"""),
                    ),
                    channel,
                )
                assertEquals(first, settings.settings.value.activeServiceId)
                assertEquals(2, channel.sent.size)
            } finally {
                session.close()
            }
        }

    @Test
    fun idleToolTurnWithoutSpeechFlushesFocusButIdleDuringPlaybackDoesNot() =
        runBlocking {
            val settings = SettingsRepository(InMemoryPreferencesDataStore())
            val first = settings.addService("First", "https://idle-first.example")
            val second = settings.addService("Second", "https://idle-second.example")
            settings.settings.first { it.activeServiceId == second }
            val session = createCloudSession(settings)
            val channel = RecordingDataChannel()
            attachAuthorizedChannel(session, channel)
            VoiceSession::class.java.getDeclaredField("_state").apply {
                isAccessible = true
                set(session, MutableStateFlow(VoiceState(connected = true)))
            }
            try {
                invokeHandleToolCall(
                    session,
                    ToolCall(
                        MessageKind.ToolCall,
                        "switch-without-speech",
                        "switch_server",
                        Json.parseToJsonElement("""{"server_id":"$first"}"""),
                    ),
                    channel,
                )
                settings.settings.first { it.activeServiceId == first }
                session.injectText("Focus is First")
                assertEquals(1, channel.sent.size) // The switch result precedes deferred focus metadata.
                invokeHandleServerMessage(session, """{"kind":"turn.status","state":"idle"}""", channel)
                assertEquals(2, channel.sent.size)
                assertTrue(channel.sent.last().contains("Focus is First"))

                invokeHandleServerMessage(session, """{"kind":"speech.started"}""", channel)
                session.injectText("Participant context during playback")
                invokeHandleServerMessage(session, """{"kind":"turn.status","state":"idle"}""", channel)
                assertEquals(2, channel.sent.size)
                invokeHandleServerMessage(session, """{"kind":"speech.ended"}""", channel)
                assertEquals(3, channel.sent.size)
                assertTrue(channel.sent.last().contains("Participant context during playback"))
            } finally {
                session.close()
            }
        }

    @Test
    fun microphoneModesGateTheRtpTrackAcrossRepliesAndInterruption() =
        runBlocking {
            val settings = SettingsRepository(InMemoryPreferencesDataStore())
            val id = settings.addService("Home", "https://home.example")
            settings.settings.first { it.activeServiceId == id }
            val session = createCloudSession(settings)
            val channel = RecordingDataChannel()
            val track = RecordingAudioTrack()
            attachAuthorizedChannel(session, channel)
            val trackField = VoiceSession::class.java.getDeclaredField("localAudioTrack").apply { isAccessible = true }
            trackField.set(session, track)
            try {
                invokeHandleServerMessage(session, """{"kind":"speech.started"}""", channel)
                assertTrue(track.microphoneEnabled)
                session.setMicrophoneMode(MicrophoneMode.MUTE_ONCE)
                assertFalse(track.microphoneEnabled)
                invokeHandleServerMessage(session, """{"kind":"speech.ended"}""", channel)
                assertTrue(track.microphoneEnabled)
                assertEquals(MicrophoneMode.LISTENING, session.state.value.microphoneMode)
                invokeHandleServerMessage(session, """{"kind":"speech.started"}""", channel)
                assertTrue(track.microphoneEnabled)
                session.setMicrophoneMode(MicrophoneMode.MUTE_ONCE)
                assertFalse(track.microphoneEnabled)
                invokeHandleServerMessage(session, """{"kind":"interrupted","source":"user"}""", channel)
                assertTrue(track.microphoneEnabled)
                assertEquals(MicrophoneMode.LISTENING, session.state.value.microphoneMode)
                session.setMicrophoneMode(MicrophoneMode.MUTED)
                assertFalse(track.microphoneEnabled)
                invokeHandleServerMessage(session, """{"kind":"speech.started"}""", channel)
                invokeHandleServerMessage(session, """{"kind":"speech.ended"}""", channel)
                invokeHandleServerMessage(session, """{"kind":"interrupted","source":"user"}""", channel)
                assertFalse(track.microphoneEnabled)
                session.setMicrophoneMode(MicrophoneMode.LISTENING)
                assertTrue(track.microphoneEnabled)
            } finally {
                trackField.set(session, null)
                session.close()
            }
        }

    private class RecordingAudioTrack : org.webrtc.AudioTrack(1L) {
        var microphoneEnabled = true

        override fun setEnabled(enable: Boolean): Boolean {
            microphoneEnabled = enable
            return true
        }
    }

    private fun attachAuthorizedChannel(
        session: VoiceSession,
        channel: RecordingDataChannel,
    ) {
        VoiceSession::class.java.getDeclaredField("voiceConversation").apply {
            isAccessible = true
            (get(session) as VoiceConversation).begin(false)
        }
        VoiceSession::class.java.getDeclaredField("dataChannel").apply {
            isAccessible = true
            set(session, channel)
        }
    }

    private suspend fun invokeHandleServerMessage(
        session: VoiceSession,
        text: String,
        channel: DataChannel,
    ): Unit =
        suspendCoroutine { continuation ->
            val method =
                VoiceSession::class.java
                    .getDeclaredMethod(
                        "handleServerMessage",
                        String::class.java,
                        Long::class.javaPrimitiveType,
                        DataChannel::class.java,
                        Continuation::class.java,
                    ).apply { isAccessible = true }
            try {
                val result = method.invoke(session, text, 0L, channel, continuation)
                if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
            } catch (e: InvocationTargetException) {
                continuation.resumeWithException(e.targetException)
            }
        }

    private fun createCloudSession(
        settings: SettingsRepository,
        beginConversation: Boolean = false,
    ): VoiceSession {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val registry = ServiceShellRegistry()
        val catalog = ServiceCatalog(scope)
        cleanup += { scope.cancel() }
        cleanup += registry::close
        cleanup += catalog::close
        val owner =
            VoiceConversation(settings, ServiceSettingsClient(), catalog) {
                registry
                    .reconcile(
                        settings.settings.value.services
                            .map { ServiceShellKey(it.id, it.url) }
                            .toSet(),
                    ).also { entries ->
                        entries.values.forEach { entry ->
                            if (!entry.bearerStore.authReady.value) {
                                entry.bearerStore.onNavigation(entry.key.url)
                                entry.bearerStore.receive(
                                    Uri.parse(entry.key.url),
                                    true,
                                    entry.key.url,
                                    """{"authChanged":true,"nativeAccess":true}""",
                                )
                            }
                        }
                    }
            }
        if (beginConversation) owner.begin(recovery = false)
        return VoiceSession(
            RuntimeEnvironment.getApplication(),
            settings,
            voiceConversation = owner,
            languageTag = "en-US",
        )
    }

    private suspend fun invokeHandleToolCall(
        session: VoiceSession,
        toolCall: ToolCall,
        channel: DataChannel,
    ): Unit =
        suspendCoroutine { continuation ->
            val method =
                VoiceSession::class.java
                    .getDeclaredMethod(
                        "handleToolCall",
                        ToolCall::class.java,
                        Long::class.javaPrimitiveType,
                        DataChannel::class.java,
                        Continuation::class.java,
                    ).apply { isAccessible = true }
            try {
                val result = method.invoke(session, toolCall, 0L, channel, continuation)
                if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
            } catch (e: InvocationTargetException) {
                continuation.resumeWithException(e.targetException)
            }
        }

    private class RecordingDataChannel : DataChannel(1L) {
        val sent = mutableListOf<String>()

        override fun state(): State = State.OPEN

        override fun send(buffer: Buffer): Boolean {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            sent += bytes.toString(Charsets.UTF_8)
            return true
        }

        override fun close() = Unit

        override fun dispose() = Unit
    }

    private fun delayedSettings(
        started: CountDownLatch,
        release: CountDownLatch,
    ): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/.well-known/gomode.json") {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return MockResponse().setBody(SETTINGS_JSON)
                }
                return MockResponse().setResponseCode(500)
            }
        }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val current = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            while (true) {
                val before = current.value
                val after = transform(before)
                if (current.compareAndSet(before, after)) return after
            }
        }
    }

    private companion object {
        const val SETTINGS_JSON = """
            {
              "service": "test-service",
              "serviceVersion": "1",
              "apiVersion": 1,
              "webShell": {
                "bridgeVersion": 1,
                "toolGroups": [{
                  "name": "service",
                  "endpoint": "/mcp",
                  "protocolVersion": "2026-07-28",
                  "authRequired": false
                }],
                "voiceGateway": {"required": false, "url": "/voice", "authRequired": false}
              }
            }
        """
    }
}
