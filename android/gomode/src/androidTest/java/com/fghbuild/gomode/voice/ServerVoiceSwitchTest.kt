// Exercises one gateway conversation across retained services, focus changes, and identity readiness.
package com.fghbuild.gomode.voice

import android.Manifest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fghbuild.gomode.R
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
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ServerVoiceSwitchTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun spokenTextSwitchKeepsOneConversationAcrossBothDirections() = exerciseSharedConversation(VoiceMode.DEVICE)

    @Test
    fun spokenWebRtcSwitchKeepsOneConversationAcrossBothDirections() = exerciseSharedConversation(VoiceMode.CLOUD)

    private fun exerciseSharedConversation(mode: VoiceMode) =
        runTest {
            MockWebServer().use { cookieServer ->
                MockWebServer().use { bearerServer ->
                    val pendingTool = CountDownLatch(1)
                    val cookie = HostedVoiceFixture(bearer = false, mcpToolGate = pendingTool)
                    val bearer = HostedVoiceFixture(bearer = true, gateway = false, reloadBearer = false)
                    cookieServer.dispatcher = cookie
                    bearerServer.dispatcher = bearer
                    cookieServer.start()
                    bearerServer.start()
                    val repository = SettingsRepository(MemoryPreferences())
                    val bearerID = repository.addService("Bearer service", bearerServer.url("/").toString())
                    val cookieID = repository.addService("Cookie service", cookieServer.url("/").toString())
                    repository.updateVoiceMode(mode)
                    repository.settings.first { it.activeServiceId == cookieID && it.voiceMode == mode }
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
                        val currentPage = visiblePage(cookieServer.url("/").toString())
                        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
                        composeRule.onNodeWithTag("gomode-settings").assertExists()
                        composeRule.onNodeWithTag("gomode-voice-connect").performClick()
                        composeRule.onNodeWithTag("gomode-settings").assertDoesNotExist()
                        assertSame(currentPage, visiblePage(cookieServer.url("/").toString()))
                        composeRule.waitUntil(30_000) { cookie.sessions.size == 1 }
                        waitForConnected()
                        val conversation = cookie.sessions.single()
                        // This controlled-backend test supplies model events, not microphone input.
                        if (mode == VoiceMode.DEVICE) composeRule.onNodeWithContentDescription("Mute").performClick()
                        conversation.send(
                            if (mode == VoiceMode.CLOUD) {
                                """{"kind":"transcript.delta","speaker":"assistant","text":"The code word is cedar."}"""
                            } else {
                                """{"kind":"assistant.text.delta","text":"The code word is cedar."}"""
                            },
                        )
                        composeRule.waitUntil(30_000) { bearer.pageReady }
                        var discoveryAttempt = 0

                        fun discoverParticipant() {
                            conversation.send(
                                """{"kind":"tool.call","id":"ready-$discoveryAttempt","name":"discover_service","args":{"server_id":"$bearerID"}}""",
                            )
                        }
                        discoverParticipant()
                        composeRule.waitUntil(30_000) {
                            val result =
                                cookie.toolResults.firstOrNull {
                                    it.optString("id") ==
                                        "ready-$discoveryAttempt"
                                }
                            if (result == null) {
                                false
                            } else if (result
                                    .getJSONObject("result")
                                    .optJSONObject("result")
                                    ?.optJSONArray("frontend_tools")
                                    ?.length() == 2
                            ) {
                                true
                            } else {
                                discoveryAttempt++
                                discoverParticipant()
                                false
                            }
                        }
                        conversation.send(catalogCommand("hidden-frontend", bearerID, "frontend", "echo"))
                        composeRule.waitUntil(
                            30_000,
                        ) { cookie.toolResults.any { it.optString("id") == "hidden-frontend" } }
                        assertTrue(
                            cookie.toolResults
                                .single { it.optString("id") == "hidden-frontend" }
                                .getJSONObject("result")
                                .getJSONObject("result")
                                .getString("source") == "bearer",
                        )
                        conversation.send(catalogCommand("pending-source", cookieID, "mcp", "echo"))
                        assertTrue(cookie.toolStarted.await(10, TimeUnit.SECONDS))
                        composeRule.onNodeWithTag("gomode-next-app").performClick()
                        repository.settings.first { it.activeServiceId == bearerID }
                        pendingTool.countDown()
                        composeRule.waitUntil(
                            30_000,
                        ) { cookie.toolResults.any { it.optString("id") == "pending-source" } }
                        assertTrue(
                            cookie.toolResults
                                .single { it.optString("id") == "pending-source" }
                                .getJSONObject("result")
                                .getJSONObject("result")
                                .getString("source") == "cookie",
                        )
                        val beforeManualFocus = cookie.messages.size
                        composeRule.onNodeWithTag("gomode-next-app").performClick()
                        repository.settings.first { it.activeServiceId == cookieID }
                        // Observe the manual focus before issuing a command that selects the preceding focus again.
                        composeRule.waitUntil(30_000) {
                            cookie.messages.drop(beforeManualFocus).any {
                                it.optString("kind") == "context.update" &&
                                    it
                                        .getJSONObject(
                                            "context",
                                        ).getString("text")
                                        .substringBefore('\n')
                                        .contains(cookieID)
                            }
                        }
                        conversation.send(switchCommand(bearerID))
                        composeRule.waitUntil(30_000) { repository.settings.value.activeServiceId == bearerID }
                        composeRule.waitUntil(30_000) { cookie.toolResults.any { it.optString("id") == "switch" } }
                        waitForConnected()
                        composeRule.waitUntil(30_000) {
                            val resultIndex =
                                cookie.messages.indexOfFirst {
                                    it.optString("kind") == "tool.result" && it.optString("id") == "switch"
                                }
                            resultIndex >= 0 &&
                                cookie.messages.drop(resultIndex + 1).any {
                                    it.optString("kind") == "context.update" &&
                                        it
                                            .getJSONObject(
                                                "context",
                                            ).getString("text")
                                            .substringBefore('\n')
                                            .contains(bearerID)
                                }
                        }
                        conversation.send(switchCommand(cookieID, "switch-back"))
                        composeRule.waitUntil(30_000) { repository.settings.value.activeServiceId == cookieID }
                        composeRule.waitUntil(30_000) { cookie.toolResults.any { it.optString("id") == "switch-back" } }
                        waitForConnected()
                        assertTrue(
                            composeRule
                                .onAllNodesWithText("The code word is cedar.", substring = true)
                                .fetchSemanticsNodes()
                                .isNotEmpty(),
                        )
                        for ((id, requestID) in listOf(bearerID to "metadata-b", cookieID to "metadata-a")) {
                            conversation.send(catalogCommand(requestID, id, "mcp", "echo"))
                            composeRule.waitUntil(30_000) {
                                cookie.toolResults.any { it.optString("id") == requestID }
                            }
                        }
                        val metadata = cookie.callMetadata + bearer.callMetadata
                        val conversationID = metadata.first().getString("openai/session")
                        assertTrue(conversationID.matches(Regex("[0-9a-f-]{36}")))
                        assertTrue(cookie.callMetadata.size == 2 && bearer.callMetadata.size == 1)
                        assertTrue(metadata.all { it.getString("openai/session") == conversationID })
                        assertTrue(metadata.all { it.getString("openai/locale") == "en-US" })
                        assertTrue(cookie.sessions.size == 1)
                        assertTrue(bearer.sessions.isEmpty())
                        assertTrue(cookie.setupMessages.size == 1)
                        val tools =
                            cookie.setupMessages
                                .single()
                                .getJSONArray("tools")
                        assertTrue(
                            (0 until tools.length())
                                .map { tools.getJSONObject(it).getString("name") }
                                .containsAll(
                                    listOf("discover_service", "dispatch_service_tool", "read_service_resource"),
                                ),
                        )
                        // Destroy the hidden participant's actual shared renderer. Native voice and its
                        // original setup/history must outlive both page owners becoming unavailable.
                        repeat(2) { recovery ->
                            repository.switchService(bearerID)
                            val hiddenView = visiblePage(bearerServer.url("/").toString())
                            composeRule.runOnUiThread { hiddenView.evaluateJavascript("window.holdTool=true", null) }
                            repository.switchService(cookieID)
                            val sponsorView = visiblePage(cookieServer.url("/").toString())
                            conversation.send(catalogCommand("lost-$recovery", bearerID, "frontend", "echo"))
                            composeRule.waitUntil(30_000) { bearer.pendingCalls.get() == recovery + 1 }
                            composeRule.runOnUiThread {
                                val renderer = requireNotNull(hiddenView.webViewRenderProcess)
                                assertSame(renderer, sponsorView.webViewRenderProcess)
                                assertTrue(renderer.terminate())
                            }
                            composeRule.waitUntil(30_000) {
                                composeRule
                                    .onAllNodesWithTag("gomode-shell-open-settings")
                                    .fetchSemanticsNodes()
                                    .isNotEmpty()
                            }
                            waitForConnected()
                            composeRule.waitUntil(30_000) {
                                cookie.toolResults.count { it.optString("id") == "lost-$recovery" } == 1
                            }
                            assertTrue(
                                cookie.toolResults
                                    .single { it.optString("id") == "lost-$recovery" }
                                    .getJSONObject("result")
                                    .has("error"),
                            )
                            composeRule.runOnUiThread {
                                assertFalse(hiddenView.isAttachedToWindow)
                                assertFalse(sponsorView.isAttachedToWindow)
                            }
                            composeRule.onNodeWithTag("gomode-shell-open-settings").performClick()
                            for (id in listOf(cookieID, bearerID)) {
                                if (id == bearerID && recovery == 0) bearer.failDiscovery = true
                                composeRule.onNodeWithTag("gomode-retry-$id").performScrollTo().performClick()
                                if (id == bearerID && recovery == 0) {
                                    composeRule.waitUntil(30_000) {
                                        composeRule
                                            .onAllNodesWithText(
                                                "Retry connection",
                                            ).fetchSemanticsNodes()
                                            .isNotEmpty()
                                    }
                                    waitForConnected()
                                    bearer.failDiscovery = false
                                    composeRule.onNodeWithTag("gomode-retry-$id").performScrollTo().performClick()
                                }
                                composeRule.waitUntil(30_000) {
                                    composeRule
                                        .onAllNodesWithTag("gomode-readiness-$id", useUnmergedTree = true)
                                        .fetchSemanticsNodes()
                                        .any {
                                            it.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                                                .any { text -> text.text == "Ready" }
                                        }
                                }
                            }
                            composeRule.runOnIdle {
                                composeRule.activity.onBackPressedDispatcher.onBackPressed()
                            }
                            val restoredSponsor = visiblePage(cookieServer.url("/").toString())
                            assertNotSame(sponsorView, restoredSponsor)
                            repository.switchService(bearerID)
                            assertNotSame(hiddenView, visiblePage(bearerServer.url("/").toString()))
                            repository.switchService(cookieID)
                            assertSame(restoredSponsor, visiblePage(cookieServer.url("/").toString()))
                            conversation.send(catalogCommand("restored-$recovery", bearerID, "frontend", "echo"))
                            composeRule.waitUntil(30_000) {
                                cookie.toolResults.any { it.optString("id") == "restored-$recovery" }
                            }
                            assertTrue(
                                cookie.toolResults
                                    .single { it.optString("id") == "restored-$recovery" }
                                    .getJSONObject("result")
                                    .getJSONObject("result")
                                    .getString("source") == "bearer",
                            )
                            assertTrue(cookie.sessions.size == 1)
                            assertTrue(cookie.setupMessages.size == 1)
                            assertTrue(bearer.pendingCalls.get() == recovery + 1)
                            assertTrue(cookie.toolResults.count { it.optString("id") == "lost-$recovery" } == 1)
                            assertTrue(
                                composeRule
                                    .onAllNodesWithText("The code word is cedar.", substring = true)
                                    .fetchSemanticsNodes()
                                    .isNotEmpty(),
                            )
                        }
                        if (mode == VoiceMode.DEVICE) {
                            repository.switchService(bearerID)
                            val retryView = visiblePage(bearerServer.url("/").toString())
                            composeRule.runOnUiThread {
                                retryView.loadUrl(bearerServer.url("/retry-page").toString())
                            }
                            composeRule.waitUntil(30_000) {
                                composeRule.onAllNodesWithTag("gomode-shell-retry").fetchSemanticsNodes().isNotEmpty()
                            }
                            bearer.failRetryPage = false
                            val nativeRequestsBeforeRetry = bearer.monitoringRequests.size
                            composeRule.onNodeWithTag("gomode-shell-retry").performClick()
                            composeRule.waitUntil(30_000) {
                                bearer.retryDocumentReady &&
                                    composeRule
                                        .onAllNodesWithTag(
                                            "gomode-shell-recovery",
                                        ).fetchSemanticsNodes()
                                        .isEmpty()
                            }
                            composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
                            composeRule.waitUntil(30_000) {
                                composeRule
                                    .onAllNodesWithTag("gomode-readiness-$bearerID", useUnmergedTree = true)
                                    .fetchSemanticsNodes()
                                    .any {
                                        it.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                                            .any { text -> text.text == "Sign in to connect" }
                                    }
                            }
                            conversation.send(catalogCommand("before-retry-validation", bearerID, "mcp", "echo"))
                            composeRule.waitUntil(30_000) {
                                cookie.toolResults.any { it.optString("id") == "before-retry-validation" }
                            }
                            assertTrue(
                                cookie.toolResults
                                    .single { it.optString("id") == "before-retry-validation" }
                                    .getJSONObject("result")
                                    .has("error"),
                            )
                            assertTrue(bearer.monitoringRequests.size == nativeRequestsBeforeRetry)
                            waitForConnected()
                            bearer.retryIdentity.countDown()
                            // Publication is asynchronous; explicitly discover until the validated participant is ready.
                            discoveryAttempt++
                            discoverParticipant()
                            composeRule.waitUntil(30_000) {
                                val result =
                                    cookie.toolResults.firstOrNull {
                                        it.optString("id") ==
                                            "ready-$discoveryAttempt"
                                    }
                                if (result == null) {
                                    false
                                } else if (result.getJSONObject("result").has("result")) {
                                    true
                                } else {
                                    discoveryAttempt++
                                    discoverParticipant()
                                    false
                                }
                            }
                            conversation.send(catalogCommand("after-retry-validation", bearerID, "frontend", "echo"))
                            composeRule.waitUntil(30_000) {
                                cookie.toolResults.any { it.optString("id") == "after-retry-validation" }
                            }
                            assertTrue(
                                cookie.toolResults
                                    .single { it.optString("id") == "after-retry-validation" }
                                    .getJSONObject("result")
                                    .getJSONObject("result")
                                    .getString("source") == "bearer",
                            )
                            waitForConnected()
                            assertTrue(cookie.sessions.size == 1 && cookie.setupMessages.size == 1)
                            assertTrue(
                                composeRule
                                    .onAllNodesWithText("The code word is cedar.", substring = true)
                                    .fetchSemanticsNodes()
                                    .isNotEmpty(),
                            )
                            composeRule.runOnIdle {
                                composeRule.activity.onBackPressedDispatcher.onBackPressed()
                            }
                            repository.switchService(cookieID)
                        }
                        conversation.send(catalogCommand("participant-logout", bearerID, "frontend", "revoke"))
                        composeRule.waitUntil(30_000) {
                            cookie.toolResults.any {
                                it.optString("id") ==
                                    "participant-logout"
                            }
                        }
                        waitForConnected()
                        conversation.send("""{"kind":"tool.call","id":"alive","name":"list_servers","args":{}}""")
                        composeRule.waitUntil(30_000) { cookie.toolResults.any { it.optString("id") == "alive" } }
                        if (mode == VoiceMode.DEVICE) {
                            repeat(2) { cycle ->
                                composeRule.onNodeWithContentDescription("End voice").performClick()
                                composeRule.waitUntil(30_000) {
                                    composeRule
                                        .onAllNodesWithTag(
                                            "gomode-voice-connect",
                                        ).fetchSemanticsNodes()
                                        .isNotEmpty()
                                }
                                composeRule.onNodeWithTag("gomode-voice-connect").performClick()
                                composeRule.waitUntil(30_000) { cookie.sessions.size == cycle + 2 }
                                waitForConnected()
                                composeRule.onNodeWithContentDescription("Mute").performClick()
                                cookie.sessions.last().send(
                                    """{"kind":"tool.call","id":"cycle-$cycle","name":"list_servers","args":{}}""",
                                )
                                composeRule.waitUntil(30_000) {
                                    cookie.toolResults.count { it.optString("id") == "cycle-$cycle" } == 1
                                }
                                assertTrue(cookie.setupMessages.size == cycle + 2)
                            }
                        }
                        cookie.sessions.last().send(catalogCommand("sponsor-logout", cookieID, "frontend", "revoke"))
                        composeRule.waitUntil(30_000) {
                            composeRule.onAllNodesWithContentDescription("End voice").fetchSemanticsNodes().isEmpty()
                        }
                    } finally {
                        pendingTool.countDown()
                        bearer.retryIdentity.countDown()
                        composeRule.runOnIdle { mounted.value = false }
                        composeRule.waitForIdle()
                        composeRule.waitUntil(30_000) { cookie.connections.isEmpty() && bearer.connections.isEmpty() }
                        owner.close()
                        cookie.close()
                        bearer.close()
                    }
                }
            }
        }

    @Test
    fun initialHiddenCookieReadinessOnTheSameOriginPreservesTheConnectedVoiceSession() =
        runTest {
            MockWebServer().use { server ->
                val readiness = CountDownLatch(1)
                val fixture = HostedVoiceFixture(bearer = false, secondPageGate = readiness)
                server.dispatcher = fixture
                server.start()
                val repository = SettingsRepository(MemoryPreferences())
                repository.addService("Hidden", server.url("/second").toString())
                val sourceID = repository.addService("Source", server.url("/first").toString())
                repository.updateVoiceMode(VoiceMode.CLOUD)
                repository.settings.first { it.activeServiceId == sourceID && it.voiceMode == VoiceMode.CLOUD }
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val owner = LocalModelOwner(context)
                val mounted = mutableStateOf(true)
                try {
                    composeRule.setContent { if (mounted.value) GoModeTheme { GoModeApp(repository, owner) } }
                    composeRule.waitUntil(30_000) {
                        composeRule.onAllNodesWithTag("gomode-voice-connect").fetchSemanticsNodes().isNotEmpty()
                    }
                    composeRule.onNodeWithTag("gomode-voice-connect").performClick()
                    composeRule.waitUntil(30_000) { fixture.sessions.size == 1 }
                    waitForConnected()
                    assertTrue(fixture.secondPageRequested.await(5, TimeUnit.SECONDS))
                    readiness.countDown()
                    composeRule.waitUntil(30_000) { fixture.secondPageReady }
                    assertTrue(
                        fixture.sessions.single().send(
                            """{"kind":"tool.call","id":"after-readiness","name":"list_servers","args":{}}""",
                        ),
                    )
                    composeRule.waitUntil(30_000) {
                        fixture.toolResults.any { it.optString("id") == "after-readiness" }
                    }
                    waitForConnected()
                    assertTrue(fixture.sessions.size == 1)
                } finally {
                    readiness.countDown()
                    composeRule.runOnIdle { mounted.value = false }
                    composeRule.waitForIdle()
                    owner.close()
                    fixture.close()
                }
            }
        }

    @Test
    fun selectedServiceWaitsForItsOwnBootstrapAndAuthenticationBeforeMonitoring() =
        runTest {
            MockWebServer().use { firstServer ->
                MockWebServer().use { secondServer ->
                    val bootstrap = CountDownLatch(1)
                    val authentication = CountDownLatch(1)
                    val first = HostedVoiceFixture(bearer = false)
                    val second =
                        HostedVoiceFixture(
                            bearer = false,
                            bootstrapGate = bootstrap,
                            authGate = authentication,
                            mcpPath = "/mcp-second",
                        )
                    firstServer.dispatcher = first
                    secondServer.dispatcher = second
                    firstServer.start()
                    secondServer.start()
                    val repository = SettingsRepository(MemoryPreferences())
                    val secondID = repository.addService("Second", secondServer.url("/").toString())
                    repository.addService("First", firstServer.url("/").toString())
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    val owner = LocalModelOwner(context)
                    val mounted = mutableStateOf(true)
                    try {
                        composeRule.setContent {
                            if (mounted.value) GoModeTheme { GoModeApp(repository, owner) }
                        }
                        composeRule.waitUntil(30_000) { first.monitoringRequests.isNotEmpty() }
                        repository.switchService(secondID)
                        composeRule.waitUntil(30_000) { second.bootstrapRequested }
                        composeRule.waitForIdle()
                        assertTrue(
                            "Destination monitored before its manifest arrived",
                            second.monitoringRequests.isEmpty(),
                        )

                        bootstrap.countDown()
                        composeRule.waitUntil(30_000) { second.authenticationRequested }
                        composeRule.waitForIdle()
                        assertTrue("Destination monitored before sign-in settled", second.monitoringRequests.isEmpty())

                        authentication.countDown()
                        composeRule.waitUntil(30_000) { second.monitoringRequests.isNotEmpty() }
                        assertTrue(second.monitoringRequests.all { it == "/mcp-second" })
                    } finally {
                        bootstrap.countDown()
                        authentication.countDown()
                        composeRule.runOnIdle { mounted.value = false }
                        composeRule.waitForIdle()
                        owner.close()
                    }
                }
            }
        }

    private fun catalogCommand(
        id: String,
        serverID: String,
        kind: String,
        tool: String,
    ): String =
        """
        {"kind":"tool.call","id":"$id","name":"dispatch_service_tool","args":{
        "server_id":"$serverID","kind":"$kind","tool":"$tool","arguments":{"value":"test"}}}
        """.trimIndent()

    private fun switchCommand(
        id: String,
        callID: String = "switch",
    ): String = """{"kind":"tool.call","id":"$callID","name":"switch_server","args":{"server_id":"$id"}}"""

    private fun visiblePage(url: String): WebView {
        lateinit var result: WebView
        composeRule.waitUntil(30_000) {
            var matches = false
            composeRule.runOnUiThread {
                val view = composeRule.activity.findViewById<WebView>(R.id.web_shell)
                if (view != null && view.url?.substringBefore('?') == url) {
                    result = view
                    matches = true
                }
            }
            matches
        }
        return result
    }

    private fun waitForConnected() {
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithContentDescription("End voice").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class HostedVoiceFixture(
        private val bearer: Boolean,
        private val gateway: Boolean = true,
        private val reloadBearer: Boolean = true,
        private val mcpToolGate: CountDownLatch? = null,
        private val bootstrapGate: CountDownLatch? = null,
        private val authGate: CountDownLatch? = null,
        private val mcpPath: String = "/mcp",
        private val secondPageGate: CountDownLatch? = null,
    ) : Dispatcher(),
        AutoCloseable {
        @Volatile
        var bootstrapRequested = false
            private set

        @Volatile
        var authenticationRequested = false
            private set

        val toolStarted = CountDownLatch(1)
        val pendingCalls = AtomicInteger()

        @Volatile
        var failRetryPage = true
        val retryIdentity = CountDownLatch(1)

        @Volatile
        var retryDocumentReady = false

        @Volatile
        var failDiscovery = false

        @Volatile
        var pageReady = false
            private set
        val monitoringRequests = CopyOnWriteArrayList<String>()
        val sessions = CopyOnWriteArrayList<ControlledTransport>()
        val connections = CopyOnWriteArrayList<WebSocket>()
        val messages = CopyOnWriteArrayList<JSONObject>()
        private var rtcGateway: ControlledRtcGateway? = null
        val setupMessages = CopyOnWriteArrayList<JSONObject>()
        val toolResults = CopyOnWriteArrayList<JSONObject>()
        val callMetadata = CopyOnWriteArrayList<JSONObject>()
        val secondPageRequested = CountDownLatch(1)

        @Volatile
        var secondPageReady = false
            private set

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl?.encodedPath
            if (path?.startsWith("/mcp") == true) monitoringRequests += path
            return when (path) {
                "/.well-known/gomode.json" -> {
                    bootstrapRequested = true
                    check(bootstrapGate?.await(30, TimeUnit.SECONDS) != false)
                    json(
                        """
                        {"service":"fixture","serviceVersion":"1","apiVersion":1,"webShell":{
                        "bridgeVersion":1,"toolGroups":[{"name":"fixture","endpoint":"$mcpPath",
                        "protocolVersion":"2026-07-28","authRequired":true}],
                        "voiceGateway":{"required":false,"url":${if (gateway) "\"/\"" else "null"},"authRequired":false}}}
                        """.trimIndent(),
                    )
                }

                "/api/voicegateway/v1/voice/choices" -> {
                    json(
                        """{"choices":[{"id":"text","label":"Text","transports":["text","webrtc"]}],"defaultChoiceID":"text"}""",
                    )
                }

                mcpPath -> {
                    val rpc = JSONObject(request.body.readUtf8())
                    if (failDiscovery && rpc.getString("method") == "server/discover") {
                        return MockResponse().setResponseCode(503)
                    }
                    val result =
                        when (rpc.getString("method")) {
                            "server/discover" -> {
                                """{"resultType":"complete","supportedVersions":["2026-07-28"],"capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"},"instructions":"Be brief.","ttlMs":60000,"cacheScope":"private"}"""
                            }

                            "tools/list" -> {
                                """
                                {"resultType":"complete","tools":[{"name":"echo","description":"Echo from this server",
                                "inputSchema":{"type":"object","properties":{"value":{"type":"string"}}}}],
                                "ttlMs":60000,"cacheScope":"private"}
                                """.trimIndent()
                            }

                            "tools/call" -> {
                                callMetadata += rpc.getJSONObject("params").getJSONObject("_meta")
                                toolStarted.countDown()
                                check(mcpToolGate?.await(30, TimeUnit.SECONDS) != false)
                                """{"resultType":"complete","content":[],"structuredContent":{"source":"${if (bearer) "bearer" else "cookie"}"}}"""
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
                            override fun onOpen(
                                webSocket: WebSocket,
                                response: okhttp3.Response,
                            ) {
                                connections += webSocket
                            }

                            override fun onClosed(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String,
                            ) {
                                connections -= webSocket
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: okhttp3.Response?,
                            ) {
                                connections -= webSocket
                            }

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
                                receive(text, webSocket::send)
                            }
                        },
                    )
                }

                "/api/voicegateway/v1/voice/rtc/offer" -> {
                    val gateway =
                        rtcGateway ?: ControlledRtcGateway { text, send -> receive(text, send) }
                            .also { rtcGateway = it }
                    json(gateway.answer(JSONObject(request.body.readUtf8()).getString("sdp")))
                }

                "/retry-identity" -> {
                    check(retryIdentity.await(30, TimeUnit.SECONDS))
                    json("{}")
                }

                "/retry-document-ready" -> {
                    retryDocumentReady = true
                    json("{}")
                }

                "/pending-tool" -> {
                    pendingCalls.incrementAndGet()
                    json("{}")
                }

                "/page-published" -> {
                    pageReady = true
                    json("{}")
                }

                "/second-ready" -> {
                    secondPageRequested.countDown()
                    check(secondPageGate?.await(30, TimeUnit.SECONDS) != false)
                    json("{}")
                }

                "/second-published" -> {
                    secondPageReady = true
                    json("{}")
                }

                "/identity-ready" -> {
                    authenticationRequested = true
                    check(authGate?.await(30, TimeUnit.SECONDS) != false)
                    json("{}")
                }

                else -> {
                    if (path == "/retry-page" && failRetryPage) {
                        return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    val auth =
                        if (bearer) {
                            """
                            if ($reloadBearer) gomodeAuth.postMessage('{"bearerToken":null}');
                            setTimeout(() => gomodeAuth.postMessage('{"bearerToken":"validated"}'), 0);
                            if ($reloadBearer && !sessionStorage.getItem('reloaded')) {
                              sessionStorage.setItem('reloaded', '1');
                              setTimeout(() => location.reload(), 100);
                            }
                            """.trimIndent()
                        } else {
                            "document.cookie='session=confirmed; path=/';" +
                                """gomodeAuth.postMessage('{"authChanged":true,"nativeAccess":true}');"""
                        }
                    val script =
                        when {
                            path == "/retry-page" -> {
                                "fetch('/retry-identity').then(() => {$auth}); fetch('/retry-document-ready');"
                            }

                            path == "/second" && secondPageGate != null -> {
                                "fetch('/second-ready').then(() => {$auth setTimeout(() => fetch('/second-published'), 0);});"
                            }

                            authGate != null -> {
                                "fetch('/identity-ready').then(() => {$auth});"
                            }

                            else -> {
                                auth
                            }
                        }
                    val revocation = if (bearer) "{bearerToken:null}" else "{authChanging:true}"
                    val tools =
                        """
                        window.executeGoModeVoiceTool = (name,args) => {
                          if (window.holdTool) { fetch('/pending-tool'); while (true) {} }
                          if (name === 'revoke') { gomodeAuth.postMessage(JSON.stringify($revocation)); return {}; }
                          return {source:'${if (bearer) "bearer" else "cookie"}',value:args.value};
                        };
                        gomodeVoiceTools.postMessage(JSON.stringify([
                          {name:'echo',description:'Echo from this page',parameters:{type:'object',properties:{value:{type:'string'}}}},
                          {name:'revoke',description:'Revoke fixture access',parameters:{type:'object',properties:{value:{type:'string'}}}}
                        ]));
                        setTimeout(() => fetch('/page-published'),0);
                        """.trimIndent()
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody(
                            "<!doctype html><html><body>Hosted service<script>$script $tools</script></body></html>",
                        )
                }
            }
        }

        private fun receive(
            text: String,
            send: (String) -> Boolean,
        ) {
            val message = JSONObject(text)
            messages += message
            when (message.getString("kind")) {
                "session.setup" -> {
                    setupMessages += message
                    sessions += ControlledTransport(send)
                    send("""{"kind":"session.ready"}""")
                }

                "tool.result" -> {
                    toolResults += message
                    // Local-stack turns can finish without producing speech fragments.
                    send("""{"kind":"turn.status","state":"idle"}""")
                }
            }
        }

        override fun close() {
            rtcGateway?.close()
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

private class ControlledTransport(
    private val sendMessage: (String) -> Boolean,
) {
    fun send(text: String): Boolean = sendMessage(text)
}
