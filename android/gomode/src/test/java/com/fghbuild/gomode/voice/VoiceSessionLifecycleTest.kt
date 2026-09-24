// Tests that disconnected voice attempts cannot continue discovery or deliver stale tool results.
package com.fghbuild.gomode.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.caic.voicegateway.sdk.v1.MessageKind
import com.caic.voicegateway.sdk.v1.ToolCall
import com.fghbuild.gomode.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
                settings.saveActiveService(label = "old", url = server.url("/").toString())
                settings.settings.first { it.activeServiceURL.isNotBlank() }
                val session = VoiceSession(RuntimeEnvironment.getApplication(), settings)

                session.connect()
                assertEquals(true, discoveryStarted.await(5, TimeUnit.SECONDS))
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
                settings.saveActiveService(label = "old", url = oldServer.url("/").toString())
                settings.settings.first { it.activeServiceURL == oldServer.url("/").toString().trimEnd('/') }
                val session = VoiceSession(RuntimeEnvironment.getApplication(), settings)
                session.connect()
                assertEquals(true, oldStarted.await(5, TimeUnit.SECONDS))

                settings.saveActiveService(label = "new", url = newServer.url("/").toString())
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
    fun lateToolResultsAndErrorsDoNotReachAnotherSession() =
        runBlocking {
            for (fails in listOf(false, true)) {
                val server = MockWebServer()
                val toolStarted = CountDownLatch(1)
                val releaseTool = CountDownLatch(1)
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            toolStarted.countDown()
                            releaseTool.await(5, TimeUnit.SECONDS)
                            if (fails) return MockResponse().setResponseCode(500)
                            val id = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("id")
                            return MockResponse().setBody(
                                """{"jsonrpc":"2.0","id":$id,"result":{"content":[],"structuredContent":{"ok":true}}}""",
                            )
                        }
                    }
                server.start()
                try {
                    val settings = SettingsRepository(InMemoryPreferencesDataStore())
                    val session = VoiceSession(RuntimeEnvironment.getApplication(), settings)
                    val oldChannel = RecordingDataChannel()
                    val newChannel = RecordingDataChannel()
                    val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null })
                    VoiceSession::class.java.getDeclaredField("dataChannel").apply {
                        isAccessible = true
                        set(session, oldChannel)
                    }
                    VoiceSession::class.java.getDeclaredField("mcpClient").apply {
                        isAccessible = true
                        set(session, client)
                    }
                    val call =
                        async(Dispatchers.Default) {
                            invokeHandleToolCall(
                                session,
                                ToolCall(MessageKind.ToolCall, "tool-1", "slow", JsonObject(emptyMap())),
                                oldChannel,
                            )
                        }
                    assertEquals(true, toolStarted.await(5, TimeUnit.SECONDS))
                    session.disconnect()
                    VoiceSession::class.java.getDeclaredField("dataChannel").apply {
                        isAccessible = true
                        set(session, newChannel)
                    }
                    releaseTool.countDown()
                    call.await()

                    assertEquals(emptyList<String>(), oldChannel.sent)
                    assertEquals(emptyList<String>(), newChannel.sent)
                    assertEquals(emptyList<TranscriptEntry>(), session.state.value.transcript)
                    assertNull(session.state.value.activeTool)
                    assertNull(session.state.value.error)
                } finally {
                    releaseTool.countDown()
                    server.shutdown()
                }
            }
        }

    @Test
    fun changedCookieDisconnectsBeforeOldVoiceContextCanCallMCP() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                var currentCookie = "session=account-a"
                val credentials =
                    VoiceMcpCredentials(currentCookie, null, { currentCookie }, { null })
                val client =
                    McpClient(
                        server.url("/mcp").toString(),
                        "2026-07-28",
                        credentials::cookieForRequest,
                        credentials::bearerForRequest,
                    )
                val settings = SettingsRepository(InMemoryPreferencesDataStore())
                val session = VoiceSession(RuntimeEnvironment.getApplication(), settings)
                val channel = RecordingDataChannel()
                VoiceSession::class.java.getDeclaredField("dataChannel").apply {
                    isAccessible = true
                    set(session, channel)
                }
                VoiceSession::class.java.getDeclaredField("mcpClient").apply {
                    isAccessible = true
                    set(session, client)
                }

                currentCookie = "session=account-b"
                invokeHandleToolCall(
                    session,
                    ToolCall(MessageKind.ToolCall, "tool-1", "sensitive", JsonObject(emptyMap())),
                    channel,
                )

                assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
                assertEquals(emptyList<String>(), channel.sent)
                assertNull(session.state.value.activeTool)
                assertEquals(false, session.state.value.connected)
            } finally {
                server.shutdown()
            }
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
