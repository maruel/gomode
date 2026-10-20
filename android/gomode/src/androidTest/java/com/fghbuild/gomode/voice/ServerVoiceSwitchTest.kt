// Exercises spoken server handoffs between cookie and delayed bearer authentication.
package com.fghbuild.gomode.voice

import android.Manifest
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.ui.GoModeApp
import com.fghbuild.gomode.ui.theme.GoModeTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class ServerVoiceSwitchTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun spokenSwitchReconnectsInBothDirectionsAfterDestinationSignIn() =
        runTest {
            MockWebServer().use { cookieServer ->
                MockWebServer().use { bearerServer ->
                    val cookie = HostedVoiceFixture(bearer = false)
                    val bearer = HostedVoiceFixture(bearer = true)
                    cookieServer.dispatcher = cookie
                    bearerServer.dispatcher = bearer
                    cookieServer.start()
                    bearerServer.start()
                    val repository = SettingsRepository(MemoryPreferences())
                    val bearerID = repository.addService("Bearer service", bearerServer.url("/").toString())
                    val cookieID = repository.addService("Cookie service", cookieServer.url("/").toString())
                    repository.updateVoiceMode(VoiceMode.DEVICE)
                    repository.updateVoiceLanguageTag("fr-CA")
                    repository.settings.first {
                        it.activeServiceId == cookieID && it.voiceMode == VoiceMode.DEVICE &&
                            it.voiceLanguageTag == "fr-CA"
                    }
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    val owner = LocalModelOwner(context)
                    val mounted = mutableStateOf(true)
                    try {
                        composeRule.setContent {
                            if (mounted.value) GoModeTheme { GoModeApp(repository, owner) }
                        }
                        composeRule.waitUntil(30_000) {
                            composeRule.onAllNodesWithTag("gomode-voice-connect").fetchSemanticsNodes().isNotEmpty()
                        }
                        composeRule.onNodeWithTag("gomode-voice-connect").performClick()
                        composeRule.waitUntil(30_000) { cookie.sessions.size == 1 }
                        waitForConnected()
                        cookie.sessions.last().send(probeCommand())
                        composeRule.waitUntil(10_000) { cookie.callMetadata.size == 1 }
                        cookie.sessions.last().send(switchCommand(bearerID))
                        composeRule.waitUntil(30_000) { bearer.sessions.size == 1 }
                        waitForConnected()
                        bearer.sessions.last().send(probeCommand())
                        composeRule.waitUntil(10_000) { bearer.callMetadata.size == 1 }
                        bearer.sessions.last().send(switchCommand(cookieID))
                        composeRule.waitUntil(30_000) { cookie.sessions.size == 2 }
                        waitForConnected()
                        cookie.sessions.last().send(probeCommand())
                        composeRule.waitUntil(10_000) { cookie.callMetadata.size == 2 }
                        val conversationID = cookie.callMetadata.first().getString("openai/session")
                        assertTrue(conversationID.matches(Regex("[0-9a-f-]{36}")))
                        assertEquals(listOf(conversationID), bearer.callMetadata.map { it.getString("openai/session") })
                        assertEquals(
                            listOf(conversationID, conversationID),
                            cookie.callMetadata.map { it.getString("openai/session") },
                        )
                        assertEquals(listOf("fr-CA"), bearer.callMetadata.map { it.getString("openai/locale") })
                        assertEquals(
                            listOf("fr-CA", "fr-CA"),
                            cookie.callMetadata.map { it.getString("openai/locale") },
                        )
                    } finally {
                        composeRule.runOnIdle { mounted.value = false }
                        composeRule.waitForIdle()
                        owner.close()
                    }
                }
            }
        }

    private fun switchCommand(id: String): String =
        """{"kind":"tool.call","id":"switch","name":"switch_server","args":{"server_id":"$id"}}"""

    private fun probeCommand(): String = """{"kind":"tool.call","id":"probe","name":"probe","args":{}}"""

    private fun waitForConnected() {
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithContentDescription("End voice").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class HostedVoiceFixture(
        private val bearer: Boolean,
    ) : Dispatcher() {
        val sessions = CopyOnWriteArrayList<WebSocket>()
        val callMetadata = CopyOnWriteArrayList<JSONObject>()

        override fun dispatch(request: RecordedRequest): MockResponse =
            when (request.requestUrl?.encodedPath) {
                "/.well-known/gomode.json" -> {
                    json(
                        """
                        {"service":"fixture","serviceVersion":"1","apiVersion":1,"webShell":{
                        "bridgeVersion":1,"toolGroups":[{"name":"fixture","endpoint":"/mcp",
                        "protocolVersion":"2026-07-28","authRequired":true}],
                        "voiceGateway":{"required":false,"url":"/","authRequired":false}}}
                        """.trimIndent(),
                    )
                }

                "/api/voicegateway/v1/voice/choices" -> {
                    json(
                        """{"choices":[{"id":"text","label":"Text","transports":["text"]}],"defaultChoiceID":"text"}""",
                    )
                }

                "/mcp" -> {
                    val rpc = JSONObject(request.body.readUtf8())
                    val result =
                        when (rpc.getString("method")) {
                            "tools/call" -> {
                                callMetadata.add(
                                    rpc.getJSONObject("params").getJSONObject("_meta"),
                                )
                                """{"resultType":"complete","content":[],"structuredContent":{}}"""
                            }

                            "server/discover" -> {
                                """{"resultType":"complete","supportedVersions":["2026-07-28"],"capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"},"instructions":"Be brief.","ttlMs":60000,"cacheScope":"private"}"""
                            }

                            "tools/list" -> {
                                """{"resultType":"complete","tools":[{"name":"probe","inputSchema":{"type":"object"}}],"ttlMs":60000,"cacheScope":"private"}"""
                            }

                            else -> {
                                """{"resultType":"complete","resources":[],"ttlMs":60000,"cacheScope":"private"}"""
                            }
                        }
                    json("""{"jsonrpc":"2.0","id":${rpc.get("id")},"result":$result}""")
                        .apply { if (bearer) setHeadersDelay(250, TimeUnit.MILLISECONDS) }
                }

                "/api/voicegateway/v1/voice/text" -> {
                    MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onClosing(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String,
                            ) {
                                webSocket.close(code, reason)
                            }

                            override fun onMessage(
                                webSocket: WebSocket,
                                text: String,
                            ) {
                                if (JSONObject(text).getString("kind") == "session.setup") {
                                    webSocket.send("""{"kind":"session.ready"}""")
                                    sessions += webSocket
                                }
                            }
                        },
                    )
                }

                else -> {
                    val auth =
                        if (bearer) {
                            """
                            gomodeAuth.postMessage('{"bearerToken":null}');
                            setTimeout(() => gomodeAuth.postMessage('{"bearerToken":"validated"}'), 0);
                            if (!sessionStorage.getItem('reloaded')) {
                              sessionStorage.setItem('reloaded', '1');
                              setTimeout(() => location.reload(), 100);
                            }
                            """.trimIndent()
                        } else {
                            "document.cookie='session=confirmed; path=/';" +
                                """gomodeAuth.postMessage('{"authChanged":true,"nativeAccess":true}');"""
                        }
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody("<!doctype html><html><body>Hosted service<script>$auth</script></body></html>")
                }
            }

        private fun json(body: String): MockResponse =
            MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
