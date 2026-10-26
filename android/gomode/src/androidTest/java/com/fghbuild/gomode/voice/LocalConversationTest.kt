// Controlled-inference emulator tests for one local conversation across retained servers and preparation focus changes.
package com.fghbuild.gomode.voice

import android.Manifest
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.caic.voicegateway.sdk.v1.TurnState
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceReadiness
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.GoModeApp
import com.fghbuild.gomode.ui.ServiceBootstrapState
import com.fghbuild.gomode.ui.theme.GoModeTheme
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.WebShellScreen
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalConversationTest {
    @get:Rule(order = 0)
    val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativePickerDuringPrefillDoesNotReplacePreparationOrNativeConversation() {
        LocalHost("A").use { a ->
            LocalHost("B").use { b ->
                val repository = SettingsRepository(MemoryPreferences())
                val ids = configure(repository, a, b)
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val configurations = CopyOnWriteArrayList<ConversationConfig>()
                val owner =
                    LocalModelOwner(context) {
                        LocalLlmEngine(context, { _, config ->
                            configurations += config
                            started.countDown()
                            check(release.await(30, TimeUnit.SECONDS))
                            ControlledRunner(configurations)
                        })
                    }
                val shellOwner = ServiceShellOwner(context, repository)
                val mounted = mutableStateOf(true)
                try {
                    composeRule.setContent { if (mounted.value) GoModeTheme { GoModeApp(shellOwner, owner) } }
                    assertTrue(started.await(30, TimeUnit.SECONDS))
                    for (id in listOf(ids.second, ids.first)) {
                        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
                        composeRule.onNodeWithTag("gomode-service-$id").performScrollTo().performClick()
                        composeRule.waitUntil(30_000) { repository.settings.value.activeServiceId == id }
                    }
                    release.countDown()
                    composeRule.waitUntil(30_000) { owner.state.value == LocalModelState.Ready }
                    assertEquals(1, configurations.size)
                    assertTrue(configurations.single().tools.isNotEmpty())
                } finally {
                    release.countDown()
                    composeRule.runOnIdle { mounted.value = false }
                    composeRule.waitForIdle()
                    composeRule.runOnIdle {
                        shellOwner.close()
                        owner.close()
                    }
                }
            }
        }
    }

    @Test
    fun oneLocalSessionRetainsHistoryAndRoutesPendingResultsAcrossManualAndToolFocusChanges() {
        LocalHost("A").use { a ->
            LocalHost("B").use { b ->
                val repository = SettingsRepository(MemoryPreferences())
                val ids = configure(repository, a, b)
                val entries = listOf(a.entry(ids.first), b.entry(ids.second))
                val speech = ControlledSpeech()
                val configs = CopyOnWriteArrayList<ConversationConfig>()
                val runner = ControlledRunner(configs, ids.first, ids.second)
                val owner =
                    LocalModelOwner(context) {
                        LocalLlmEngine(context, { _, config ->
                            configs += config
                            runner
                        })
                    }
                lateinit var catalog: ServiceCatalog
                lateinit var conversation: VoiceConversation
                lateinit var session: DeviceVoiceSession
                composeRule.setContent {
                    val scope = rememberCoroutineScope()
                    catalog = androidx.compose.runtime.remember { ServiceCatalog(scope) }
                    catalog.reconcile(entries.associateBy { it.key }, emptyMap())
                    conversation =
                        androidx.compose.runtime.remember {
                            VoiceConversation(
                                repository,
                                ServiceSettingsClient(),
                                catalog,
                            ) { entries.associateBy { it.key } }
                        }
                    session =
                        androidx.compose.runtime.remember {
                            DeviceVoiceSession(
                                context,
                                repository,
                                languageTag = "en-US",
                                speech = speech,
                                voiceConversation = conversation,
                                localModelOwner = owner,
                                voiceChimePlayer = QuietChime(),
                            )
                        }
                    val settings by repository.settings.collectAsState()
                    val catalogState by catalog.state.collectAsState()
                    LaunchedEffect(settings.activeServiceId, catalogState) {
                        if (session.state.value.connected) session.injectText(conversation.contextText())
                    }
                    DisposableEffect(session) {
                        onDispose {
                            session.close()
                            owner.close()
                            catalog.close()
                            entries.forEach { it.close() }
                        }
                    }
                    entries.forEach { entry ->
                        key(entry) {
                            WebShellScreen(
                                voiceConnected = true,
                                visible = settings.activeServiceId == entry.key.serviceID,
                                reloadToken = 0,
                                serviceSession = entry,
                                onAuthInvalidated = {},
                            )
                        }
                    }
                }
                composeRule.waitUntil(30_000) {
                    catalog.state.value.values
                        .count { it.readiness == ServiceReadiness.READY } == 2 &&
                        entries.all { it.frontendTools.declarations.isNotEmpty() }
                }
                composeRule.runOnIdle {
                    owner.select(
                        LocalModelSelection("en-US", {
                            withContext(Dispatchers.Main) { conversation.prepareLocal() }
                        }, { true }),
                    )
                }
                composeRule.waitUntil(30_000) { owner.state.value == LocalModelState.Ready }
                composeRule.runOnIdle { session.connect() }
                waitForListening(session, speech)
                composeRule.runOnIdle { speech.submit("USER_REMEMBER cedar") }
                waitForListening(session, speech)
                a.holdTools = true
                composeRule.runOnIdle { speech.submit("USER_HELD") }
                assertTrue(a.toolStarted.await(30, TimeUnit.SECONDS))
                runBlocking { repository.switchService(ids.second) }
                composeRule.waitUntil(30_000) { repository.settings.value.activeServiceId == ids.second }
                a.releaseTool.countDown()
                waitForListening(session, speech)
                composeRule.runOnIdle { speech.submit("USER_RETURN") }
                waitForListening(session, speech)
                composeRule.waitUntil(30_000) { repository.settings.value.activeServiceId == ids.first }
                assertEquals(1, configs.size)
                assertEquals(0, runner.replacements.get())
                val metadata = a.callMetadata.single()
                assertEquals("en-US", metadata.getString("openai/locale"))
                assertTrue(metadata.getString("openai/session").matches(Regex("[0-9a-f-]{36}")))
                assertTrue(runner.messages.any { it.role == Role.TOOL && it.toString().contains("A-mcp") })
                assertTrue(runner.messages.any { it.role == Role.TOOL && it.toString().contains("B-frontend") })
                assertTrue(
                    runner.messages
                        .last { it.role == Role.USER }
                        .toString()
                        .contains(ids.second),
                )
                assertTrue(
                    session.state.value.transcript
                        .any { it.text.contains("cedar back on A") },
                )
                assertEquals(
                    3,
                    session.state.value.transcript
                        .count { it.speaker == TranscriptSpeaker.USER },
                )
                assertTrue(session.state.value.connected)
                composeRule.runOnIdle { session.disconnect() }
            }
        }
    }

    @Test
    fun canceledAcquisitionRepreparesAuthorizationAndNativeContextBeforeReconnect() {
        LocalHost("A").use { a ->
            LocalHost("B").use { b ->
                val repository = SettingsRepository(MemoryPreferences())
                val ids = configure(repository, a, b)
                val entry = a.entry(ids.first)
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val configs = CopyOnWriteArrayList<ConversationConfig>()
                val runner = ControlledRunner(configs)
                val speech = ControlledSpeech()
                val owner =
                    LocalModelOwner(context) {
                        LocalLlmEngine(context, { _, config ->
                            configs += config
                            started.countDown()
                            check(release.await(30, TimeUnit.SECONDS))
                            runner
                        })
                    }
                lateinit var conversation: VoiceConversation
                lateinit var session: DeviceVoiceSession
                try {
                    composeRule.setContent {
                        val scope = rememberCoroutineScope()
                        val catalog = androidx.compose.runtime.remember { ServiceCatalog(scope) }
                        catalog.reconcile(mapOf(entry.key to entry), emptyMap())
                        conversation =
                            androidx.compose.runtime.remember {
                                VoiceConversation(repository, ServiceSettingsClient(), catalog) {
                                    mapOf(entry.key to entry)
                                }
                            }
                        session =
                            androidx.compose.runtime.remember {
                                DeviceVoiceSession(
                                    context,
                                    repository,
                                    languageTag = "en-US",
                                    speech = speech,
                                    voiceConversation = conversation,
                                    localModelOwner = owner,
                                    voiceChimePlayer = QuietChime(),
                                )
                            }
                        DisposableEffect(session) {
                            onDispose {
                                session.close()
                                owner.close()
                                catalog.close()
                                entry.close()
                            }
                        }
                    }
                    composeRule.runOnIdle {
                        entry.bearerStore.onNavigation(entry.key.url)
                        entry.bearerStore.receive(
                            Uri.parse(entry.key.url),
                            true,
                            entry.key.url,
                            """{"bearerToken":"A-token"}""",
                        )
                        owner.select(
                            LocalModelSelection("en-US", {
                                withContext(Dispatchers.Main) { conversation.prepareLocal() }
                            }, { true }),
                        )
                        session.connect()
                    }
                    assertTrue(started.await(30, TimeUnit.SECONDS))
                    composeRule.runOnIdle {
                        session.disconnect()
                        entry.bearerStore.receive(
                            Uri.parse(entry.key.url),
                            true,
                            entry.key.url,
                            """{"bearerToken":"replacement"}""",
                        )
                    }
                    release.countDown()
                    composeRule.waitUntil(30_000) { owner.state.value == LocalModelState.Ready }
                    composeRule.runOnIdle { session.connect() }
                    waitForListening(session, speech)
                    assertEquals(2, configs.size)
                    assertEquals(1, runner.replacements.get())
                    composeRule.runOnIdle {
                        assertTrue(conversation.isCurrent())
                        entry.bearerStore.receive(
                            Uri.parse(entry.key.url),
                            true,
                            entry.key.url,
                            """{"bearerToken":"third-account"}""",
                        )
                        assertFalse(conversation.isCurrent())
                        session.disconnect()
                        assertFalse(conversation.isCurrent())
                        assertTrue(runCatching { conversation.contextText() }.exceptionOrNull() is VoiceSetupException)
                    }
                } finally {
                    release.countDown()
                    composeRule.runOnIdle { owner.close() }
                }
            }
        }
    }

    private fun waitForListening(
        session: DeviceVoiceSession,
        speech: ControlledSpeech,
    ) {
        composeRule.waitUntil(30_000) {
            session.state.value.connected && speech.callback != null &&
                session.state.value.turnState == TurnState.Idle
        }
    }

    private fun configure(
        repository: SettingsRepository,
        a: LocalHost,
        b: LocalHost,
    ): Pair<String, String> =
        runBlocking {
            val second = repository.addService("B", b.url)
            val first = repository.addService("A", a.url)
            repository.updateVoiceMode(VoiceMode.DEVICE)
            repository.updateOnDeviceEngine(OnDeviceEngine.LOCAL_LLM)
            repository.settings.first {
                it.activeServiceId == first && it.onDeviceEngine == OnDeviceEngine.LOCAL_LLM &&
                    it.voiceMode == VoiceMode.DEVICE
            }
            first to second
        }

    private class ControlledRunner(
        private val configs: MutableList<ConversationConfig>,
        private val a: String = "",
        private val b: String = "",
    ) : EdgeLlmRunner {
        override val isModelLoaded = true
        override val backendName = "controlled"
        val messages = CopyOnWriteArrayList<Message>()
        val replacements = AtomicInteger()

        override suspend fun generateStream(message: Message): Flow<Message> {
            messages += message
            val text = message.toString()
            val reply =
                when {
                    message.role == Role.USER && text.contains("USER_REMEMBER") -> {
                        Message.model("Remembered cedar.")
                    }

                    message.role == Role.USER && text.contains("USER_HELD") -> {
                        Message.model(
                            toolCalls =
                                listOf(
                                    ToolCall(
                                        "dispatch_service_tool",
                                        mapOf(
                                            "server_id" to a,
                                            "kind" to "mcp",
                                            "tool" to "echo",
                                            "arguments" to mapOf("value" to "cedar"),
                                        ),
                                    ),
                                ),
                        )
                    }

                    message.role == Role.USER -> {
                        Message.model(
                            toolCalls =
                                listOf(
                                    ToolCall("switch_server", mapOf("server_id" to a)),
                                    ToolCall(
                                        "dispatch_service_tool",
                                        mapOf(
                                            "server_id" to b,
                                            "kind" to "frontend",
                                            "tool" to "echo",
                                            "arguments" to mapOf("value" to "cedar"),
                                        ),
                                    ),
                                ),
                        )
                    }

                    text.contains("A-mcp") -> {
                        Message.model(
                            toolCalls =
                                listOf(
                                    ToolCall(
                                        "switch_server",
                                        mapOf("server_id" to b),
                                    ),
                                ),
                        )
                    }

                    text.contains("B-frontend") -> {
                        Message.model("cedar back on A")
                    }

                    else -> {
                        Message.model("cedar on B")
                    }
                }
            return flowOf(reply)
        }

        override fun replaceConversation(config: ConversationConfig) {
            replacements.incrementAndGet()
            configs += config
        }

        override fun close() = Unit
    }

    private class ControlledSpeech : DeviceSpeech {
        override val microphoneRms = kotlinx.coroutines.flow.MutableStateFlow(0f)
        override val languageState =
            kotlinx.coroutines.flow.MutableStateFlow<SpeechLanguageState>(
                SpeechLanguageState.Ready,
            )

        override suspend fun checkLanguage() = languageState.value

        override suspend fun downloadLanguage() {
            languageState.value = SpeechLanguageState.Ready
        }

        @Volatile var callback: ((String) -> Unit)? = null

        override fun isAvailable() = true

        override fun startListening(
            onResult: (String) -> Unit,
            onPartial: (String) -> Unit,
            onIdle: () -> Unit,
            onError: (String) -> Unit,
        ) {
            callback = onResult
        }

        fun submit(text: String) {
            val current = checkNotNull(callback)
            callback = null
            current(text)
        }

        override fun stopListening() {
            callback = null
        }

        override fun speak(
            text: String,
            onDone: () -> Unit,
            onError: (String) -> Unit,
        ) {
            onDone()
        }

        override fun stopSpeaking() = Unit

        override fun close() = Unit
    }

    private class QuietChime : VoiceChimePlayer {
        override fun playConnected() = Unit

        override fun playDisconnected(onFinished: () -> Unit) {
            onFinished()
        }
    }

    private class LocalHost(
        private val name: String,
    ) : AutoCloseable {
        private val server = MockWebServer()
        val url get() = server.url("/").toString()

        @Volatile var holdTools = false
        val toolStarted = CountDownLatch(1)
        val callMetadata = CopyOnWriteArrayList<JSONObject>()
        val releaseTool = CountDownLatch(1)
        private val manifest = """{"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":1,
            "toolGroups":[{"name":"fixture","endpoint":"/api/mcp","protocolVersion":"2026-07-28","authRequired":true}],
            "voiceGateway":{"required":false}}}"""

        init {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.requestUrl?.encodedPath == "/.well-known/gomode.json") return json(manifest)
                        if (request.method == "GET") {
                            return MockResponse().setHeader("Content-Type", "text/html").setBody(
                                """
                                <html><body>Local host $name<script>
                                gomodeAuth.postMessage(JSON.stringify({bearerToken:'$name-token'}));
                                window.executeGoModeVoiceTool = (name,args) => (
                                  {source:'$name-frontend',value:args.value});
                                gomodeVoiceTools.postMessage(JSON.stringify([{
                                  name:'echo',description:'Echo',parameters:{
                                  type:'object',properties:{value:{type:'string'}}}}]));
                                </script></body></html>
                                """.trimIndent(),
                            )
                        }
                        check(request.getHeader("Authorization") == "Bearer $name-token")
                        val rpc = JSONObject(request.body.readUtf8())
                        val result =
                            when (rpc.getString("method")) {
                                "server/discover" -> {
                                    JSONObject(
                                        """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}},
                            "serverInfo":{"name":"fixture","version":"1"}}""",
                                    )
                                }

                                "tools/list" -> {
                                    JSONObject(
                                        """{"tools":[{"name":"echo","inputSchema":{
                            "type":"object","properties":{"value":{"type":"string"}}}}]}""",
                                    )
                                }

                                "tools/call" -> {
                                    callMetadata += rpc.getJSONObject("params").getJSONObject("_meta")
                                    toolStarted.countDown()
                                    if (holdTools) check(releaseTool.await(30, TimeUnit.SECONDS))
                                    JSONObject().put("content", org.json.JSONArray()).put(
                                        "structuredContent",
                                        JSONObject().put("source", "$name-mcp"),
                                    )
                                }

                                else -> {
                                    error("Unexpected local fixture request")
                                }
                            }.put("resultType", "complete").put("ttlMs", 60000).put("cacheScope", "private")
                        return json(
                            JSONObject()
                                .put("jsonrpc", "2.0")
                                .put("id", rpc.get("id"))
                                .put("result", result)
                                .toString(),
                        )
                    }
                }
            server.start()
        }

        fun entry(id: String) =
            ServiceShellSession(ServiceShellKey(id, url)) {}.apply {
                updateBootstrap(
                    ServiceBootstrapState.Ready(
                        kotlinx.serialization.json.Json
                            .decodeFromString(manifest),
                    ),
                )
            }

        override fun close() {
            releaseTool.countDown()
            server.close()
        }

        private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val writeMutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
            writeMutex.withLock { transform(state.value).also { state.value = it } }
    }
}
