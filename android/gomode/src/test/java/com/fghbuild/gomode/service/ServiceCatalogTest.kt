// Tests independent service discovery, schema routing, credential generations, and globally bounded context.
package com.fghbuild.gomode.service

import android.net.Uri
import android.webkit.WebView
import com.fghbuild.gomode.sdk.v1.Settings
import com.fghbuild.gomode.sdk.v1.ToolGroup
import com.fghbuild.gomode.sdk.v1.VoiceGatewaySettings
import com.fghbuild.gomode.sdk.v1.WebShellSettings
import com.fghbuild.gomode.ui.ServiceBootstrapState
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.WebShellLoadState
import com.fghbuild.gomode.ui.web.completeFrontendPreparation
import com.fghbuild.gomode.voice.McpToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ServiceCatalogTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `native ready preparation waits exactly once and cancelled waits ignore late frontend readiness`() =
        runTest {
            Fixture("ready").use { fixture ->
                val entry = fixture.entry("a")
                entry.invalidateIdentity()
                val catalog = ServiceCatalog(backgroundScope)
                try {
                    catalog.reconcile(mapOf(entry.key to entry), emptyMap())
                    catalog.state.first { it[entry.key]?.readiness == ServiceReadiness.READY }
                    assertTrue(catalog.isReady(entry.key))
                    assertTrue(entry.bearerStore.authReady.value)
                    assertEquals(WebShellLoadState.Ready, entry.state.value.load)
                    var connections = 0
                    val pending =
                        async {
                            entry.awaitFrontendReady(entry.bearerStore.identityEpoch.value)
                            connections++
                        }
                    runCurrent()
                    assertFalse(pending.isCompleted)
                    assertEquals(0, connections)
                    completeFrontendPreparation(entry)
                    pending.await()
                    assertEquals(1, connections)
                    assertTrue(entry.frontendTools.declarations.isEmpty())
                    completeFrontendPreparation(entry)
                    runCurrent()
                    assertEquals(1, connections)
                    entry.invalidateIdentity()
                    val cancelled =
                        async {
                            entry.awaitFrontendReady(entry.bearerStore.identityEpoch.value)
                            connections++
                        }
                    runCurrent()
                    cancelled.cancel()
                    completeFrontendPreparation(entry)
                    runCurrent()
                    assertTrue(cancelled.isCancelled)
                    assertEquals(1, connections)
                    entry.invalidateIdentity()
                    val replaced =
                        async {
                            runCatching {
                                entry.awaitFrontendReady(entry.bearerStore.identityEpoch.value)
                                connections++
                            }
                        }
                    runCurrent()
                    signIn(entry, "different-account")
                    assertTrue(replaced.await().exceptionOrNull() is IllegalStateException)
                    assertEquals(1, connections)
                } finally {
                    catalog.close()
                    entry.close()
                }
            }
        }

    @Test
    fun `late sign in duplicate names and logout stay scoped without requiring a gateway`() =
        runTest {
            Fixture("A").use { first ->
                Fixture("B").use { second ->
                    val a = first.entry("a")
                    val b = second.entry("b", authenticated = false)
                    val entries = mapOf(a.key to a, b.key to b)
                    val catalog = ServiceCatalog(backgroundScope)
                    try {
                        catalog.reconcile(entries, mapOf("a" to "Alpha", "b" to "Beta"))
                        catalog.state.first { it[a.key]?.monitoring?.snapshot != null }
                        assertEquals(ServiceReadiness.WAITING_FOR_SIGN_IN, catalog.state.value[b.key]?.readiness)
                        assertTrue(second.requests.isEmpty())
                        signIn(b, "token-b")
                        catalog.state.first { it[b.key]?.monitoring?.snapshot != null }
                        assertEquals("A", dispatch(catalog, "a").resultString("source"))
                        assertEquals("B", dispatch(catalog, "b").resultString("source"))
                        assertEquals("Bearer token-a", first.requests.last().getHeader("Authorization"))
                        assertEquals("Bearer token-b", second.requests.last().getHeader("Authorization"))
                        val discover =
                            requireNotNull(catalog.execute("discover_service", objectOf("server_id" to "b")) { true })
                        val detail = discover.structuredContent.getValue("result").jsonObject
                        assertTrue(detail.getValue("mcp_tools").toString().contains("additionalProperties"))
                        assertEquals("B instructions", detail.getValue("instructions").jsonPrimitive.content)
                        assertTrue(catalog.contextText().contains("Alpha"))
                        assertTrue(catalog.contextText().contains("Beta"))
                        assertTrue(catalog.contextText().contains("Same item"))
                        first.minimum = 5
                        assertTrue(dispatch(catalog, "a").isError)
                        first.minimum = 1
                        assertFalse(dispatch(catalog, "a").isError)
                        val callsBefore = first.calls
                        val invalid =
                            requireNotNull(
                                catalog.execute(
                                    "dispatch_service_tool",
                                    dispatchArgs("a", "mcp", "{\"n\":0}"),
                                ) { true },
                            )
                        assertTrue(invalid.isError)
                        assertEquals(callsBefore, first.calls)
                        b.bearerStore.invalidateIdentity()
                        catalog.state.first { it[b.key]?.readiness == ServiceReadiness.WAITING_FOR_SIGN_IN }
                        assertTrue(dispatch(catalog, "b").isError)
                        assertEquals("A", dispatch(catalog, "a").resultString("source"))
                        catalog.reconcile(mapOf(a.key to a), emptyMap())
                        assertTrue(dispatch(catalog, "b").isError)
                        assertEquals(1, catalog.state.value.size)
                    } finally {
                        catalog.close()
                    }
                }
            }
        }

    @Test
    fun `recovery requires a ready page and rejects calls synchronously while it is unavailable`() =
        runTest {
            Fixture("A").use { fixture ->
                fixture.supportsResources = false
                val entry = fixture.entry("a")
                entry.updateLoad(WebShellLoadState.Loading)
                val catalog = ServiceCatalog(backgroundScope)
                try {
                    catalog.reconcile(mapOf(entry.key to entry), emptyMap())
                    catalog.state.first { it[entry.key]?.readiness == ServiceReadiness.WAITING_FOR_PAGE }
                    assertTrue(fixture.requests.isEmpty())
                    entry.updateLoad(WebShellLoadState.Ready)
                    catalog.state.first { it[entry.key]?.readiness == ServiceReadiness.READY }
                    assertFalse(dispatch(catalog, "a").isError)
                    val calls = fixture.calls
                    entry.rendererLost()
                    assertTrue(dispatch(catalog, "a").isError)
                    assertEquals(calls, fixture.calls)
                    entry.reloadWeb()
                    signIn(entry, "token-a")
                    catalog.state.first { it[entry.key]?.readiness == ServiceReadiness.WAITING_FOR_PAGE }
                    assertTrue(dispatch(catalog, "a").isError)
                    entry.updateLoad(WebShellLoadState.Ready)
                    catalog.state.first { it[entry.key]?.readiness == ServiceReadiness.READY }
                    assertFalse(dispatch(catalog, "a").isError)
                    assertEquals(calls + 1, fixture.calls)
                } finally {
                    catalog.close()
                }
            }
        }

    @Test
    fun `failed discovery retries independently and pending calls cannot cross replacement generations`() =
        runTest {
            Fixture("A").use { first ->
                Fixture("B").use { second ->
                    second.failDiscovery = true
                    val a = first.entry("a")
                    val b = second.entry("b")
                    val catalog = ServiceCatalog(backgroundScope)
                    try {
                        catalog.reconcile(mapOf(a.key to a, b.key to b), emptyMap())
                        catalog.state.first { it[a.key]?.monitoring?.snapshot != null && it[b.key]?.error != null }
                        assertEquals("A", dispatch(catalog, "a").resultString("source"))
                        second.failDiscovery = false
                        advanceTimeBy(1_000)
                        catalog.state.first { it[b.key]?.readiness == ServiceReadiness.READY }
                        second.blockCall = true
                        val pending = async { dispatch(catalog, "b") }
                        val entered = async(Dispatchers.IO) { second.callEntered.await(5, TimeUnit.SECONDS) }
                        assertTrue(entered.await())
                        b.bearerStore.invalidateIdentity()
                        signIn(b, "replacement")
                        runCurrent()
                        second.releaseCall.countDown()
                        assertTrue(pending.await().isError)
                        catalog.state.first { it[b.key]?.readiness == ServiceReadiness.READY }
                        assertEquals("B", dispatch(catalog, "b").resultString("source"))
                        assertEquals("Bearer replacement", second.requests.last().getHeader("Authorization"))
                    } finally {
                        second.releaseCall.countDown()
                        catalog.close()
                    }
                }
            }
        }

    @Test
    fun `same named frontend and MCP tools have separate schemas and page replacement rejects pending frontend`() =
        runTest {
            Fixture("MCP").use { fixture ->
                val entry = fixture.entry("a")
                val view = DeferredWebView(entry.key.url, entry.frontendTools)
                entry.frontendTools.register(
                    view,
                    """
                    [{"name":"duplicate","description":"Frontend","parameters":{
                      "type":"object","properties":{"word":{"type":"string"}},"required":["word"],
                      "additionalProperties":false}}]
                    """.trimIndent(),
                    org.json.JSONObject("""{"document":"document","token":"identity","scope":"scope"}"""),
                )
                val catalog = ServiceCatalog(backgroundScope)
                try {
                    catalog.reconcile(mapOf(entry.key to entry), emptyMap())
                    catalog.state.first { it[entry.key]?.readiness == ServiceReadiness.READY }
                    assertEquals("MCP", dispatch(catalog, "a").resultString("source"))
                    val invalid =
                        requireNotNull(
                            catalog.execute(
                                "dispatch_service_tool",
                                dispatchArgs("a", "frontend", "{\"n\":2}"),
                            ) { true },
                        )
                    assertTrue(invalid.isError)
                    assertFalse(view.invoked.isCompleted)
                    val pending =
                        async {
                            catalog.execute(
                                "dispatch_service_tool",
                                dispatchArgs("a", "frontend", "{\"word\":\"hello\"}"),
                            ) { true }
                        }
                    view.invoked.await()
                    entry.openPage()
                    assertTrue(requireNotNull(pending.await()).isError)
                    view.complete("{\"source\":\"stale\"}")
                } finally {
                    catalog.close()
                    view.destroy()
                }
            }
        }

    @Test
    fun `frontend page is pinned before suspended metadata discovery`() =
        runTest {
            Fixture("A").use { fixture ->
                val entry = fixture.entry("a")
                val original = DeferredWebView(entry.key.url, entry.frontendTools)
                val replacement = DeferredWebView(entry.key.url, entry.frontendTools)
                val declaration = """[{"name":"duplicate","description":"Frontend","parameters":{}}]"""
                entry.frontendTools.register(
                    original,
                    declaration,
                    org.json.JSONObject("""{"document":"document","token":"identity","scope":"scope"}"""),
                )
                val catalog = ServiceCatalog(backgroundScope)
                try {
                    catalog.reconcile(mapOf(entry.key to entry), emptyMap())
                    catalog.state.first { it[entry.key]?.readiness == ServiceReadiness.READY }
                    fixture.blockToolsList = true
                    val pending =
                        async {
                            catalog.execute("dispatch_service_tool", dispatchArgs("a", "frontend")) { true }
                        }
                    assertTrue(async(Dispatchers.IO) { fixture.toolsListEntered.await(5, TimeUnit.SECONDS) }.await())
                    entry.openPage()
                    entry.frontendTools.register(
                        replacement,
                        declaration,
                        org.json.JSONObject("""{"document":"document","token":"identity","scope":"scope"}"""),
                    )
                    fixture.releaseToolsList.countDown()
                    assertTrue(requireNotNull(pending.await()).isError)
                    assertFalse(
                        "Replacement page executed a call started for another page",
                        replacement.invoked.isCompleted,
                    )
                    assertFalse(original.invoked.isCompleted)
                } finally {
                    fixture.releaseToolsList.countDown()
                    catalog.close()
                    original.destroy()
                    replacement.destroy()
                }
            }
        }

    @Test
    fun `ended caller cannot execute MCP side effects after metadata discovery`() =
        runTest {
            Fixture("A").use { fixture ->
                val entry = fixture.entry("a")
                val catalog = ServiceCatalog(backgroundScope)
                try {
                    catalog.reconcile(mapOf(entry.key to entry), emptyMap())
                    catalog.state.first { it[entry.key]?.readiness == ServiceReadiness.READY }
                    fixture.blockToolsList = true
                    var callerActive = true
                    val pending =
                        async {
                            catalog.execute("dispatch_service_tool", dispatchArgs("a")) { callerActive }
                        }
                    assertTrue(async(Dispatchers.IO) { fixture.toolsListEntered.await(5, TimeUnit.SECONDS) }.await())
                    callerActive = false
                    fixture.releaseToolsList.countDown()
                    assertTrue(requireNotNull(pending.await()).isError)
                    assertEquals("Ended caller caused a service side effect", 0, fixture.calls)
                } finally {
                    fixture.releaseToolsList.countDown()
                    catalog.close()
                }
            }
        }

    @Test
    fun `no yield bearer round trip revokes an MCP call waiting for metadata`() =
        runTest {
            Fixture("A").use { fixture ->
                val entry = fixture.entry("a")
                val catalog = ServiceCatalog(backgroundScope)
                try {
                    catalog.reconcile(mapOf(entry.key to entry), emptyMap())
                    catalog.state.first { it[entry.key]?.readiness == ServiceReadiness.READY }
                    fixture.blockToolsList = true
                    val pending = async { dispatch(catalog, "a") }
                    assertTrue(async(Dispatchers.IO) { fixture.toolsListEntered.await(5, TimeUnit.SECONDS) }.await())
                    val originalEpoch = entry.bearerStore.authEpoch.value
                    val origin = Uri.parse(entry.key.url)
                    assertTrue(entry.bearerStore.receive(origin, true, entry.key.url, "{\"bearerToken\":null}"))
                    assertTrue(entry.bearerStore.receive(origin, true, entry.key.url, "{\"bearerToken\":\"token-a\"}"))
                    assertEquals("token-a", entry.bearerStore.token.value)
                    assertTrue(entry.bearerStore.authEpoch.value > originalEpoch)
                    fixture.releaseToolsList.countDown()
                    assertTrue(pending.await().isError)
                    assertEquals(0, fixture.calls)
                } finally {
                    fixture.releaseToolsList.countDown()
                    catalog.close()
                }
            }
        }

    @Test
    fun `tools only participant exposes schemas and rejects resource reads without resource RPCs`() =
        runTest {
            Fixture("Tools only").use { fixture ->
                fixture.supportsResources = false
                val entry = fixture.entry("a")
                val catalog = ServiceCatalog(backgroundScope)
                try {
                    catalog.reconcile(mapOf(entry.key to entry), emptyMap())
                    catalog.state.first { it[entry.key]?.readiness == ServiceReadiness.READY }
                    val discovered =
                        requireNotNull(catalog.execute("discover_service", objectOf("server_id" to "a")) { true })
                    assertFalse(discovered.isError)
                    val detail = discovered.structuredContent.getValue("result").jsonObject
                    assertTrue(detail.getValue("mcp_tools").toString().contains("duplicate"))
                    assertEquals("[]", detail.getValue("resources").toString())
                    assertEquals("[]", detail.getValue("resource_templates").toString())
                    val read =
                        requireNotNull(
                            catalog.execute(
                                "read_service_resource",
                                objectOf(
                                    "server_id" to "a",
                                    "uri" to "gomode://items",
                                ),
                            ) { true },
                        )
                    assertTrue(read.isError)
                    assertEquals("Tools only", dispatch(catalog, "a").resultString("source"))
                    assertTrue(fixture.requests.none { it.getHeader("Mcp-Method")?.startsWith("resources/") == true })
                } finally {
                    catalog.close()
                }
            }
        }

    @Test
    fun `frontend only host dispatches without an MCP endpoint`() =
        runTest {
            Fixture("Unused MCP").use { fixture ->
                val entry = fixture.entry("a")
                val settings = (entry.state.value.bootstrap as ServiceBootstrapState.Ready).settings
                entry.updateBootstrap(
                    ServiceBootstrapState.Ready(
                        settings.copy(
                            webShell = settings.webShell.copy(toolGroups = emptyList()),
                        ),
                    ),
                )
                val view = DeferredWebView(entry.key.url, entry.frontendTools)
                entry.frontendTools.register(
                    view,
                    """[{"name":"duplicate","description":"Frontend","parameters":{"type":"object"}}]""",
                    org.json.JSONObject("""{"document":"document","token":"identity","scope":"scope"}"""),
                )
                val catalog = ServiceCatalog(backgroundScope)
                try {
                    catalog.reconcile(mapOf(entry.key to entry), emptyMap())
                    catalog.state.first { it[entry.key]?.readiness == ServiceReadiness.READY }
                    val pending =
                        async {
                            catalog.execute("dispatch_service_tool", dispatchArgs("a", "frontend", "{}")) { true }
                        }
                    view.invoked.await()
                    view.complete("""{"source":"browser"}""")
                    assertEquals("browser", requireNotNull(pending.await()).resultString("source"))
                    assertTrue(fixture.requests.isEmpty())
                    assertTrue(dispatch(catalog, "a").isError)
                } finally {
                    catalog.close()
                    view.destroy()
                }
            }
        }

    @Test
    fun `catalog context has one aggregate budget even with many large sources`() =
        runTest {
            val catalog = ServiceCatalog(backgroundScope)
            try {
                val entries =
                    (1..30).map {
                        ServiceShellSession(
                            ServiceShellKey("id-$it", "https://service-$it.example"),
                        ) {}
                    }
                catalog.reconcile(
                    entries.associateBy { it.key },
                    entries.associate {
                        it.key.serviceID to
                            "label".repeat(1000)
                    },
                )
                val context = catalog.contextText()
                assertTrue(context.length <= 12_000)
                assertTrue(context.contains("More servers omitted"))
                assertTrue(context.contains("id-1"))
            } finally {
                catalog.close()
            }
        }

    private class DeferredWebView(
        private val documentURL: String,
        private val tools: com.fghbuild.gomode.ui.web.FrontendVoiceTools,
    ) : WebView(RuntimeEnvironment.getApplication()) {
        override fun getUrl(): String = documentURL

        val invoked = CompletableDeferred<Unit>()
        private var callback: android.webkit.ValueCallback<String>? = null

        override fun evaluateJavascript(
            script: String,
            resultCallback: android.webkit.ValueCallback<String>?,
        ) {
            if (!script.contains("dispatchGoModeVoiceTool")) return
            val call = org.json.JSONObject(script.substringAfter("?.(").dropLast(1))
            callback =
                android.webkit.ValueCallback { result ->
                    tools.complete(this, call.put("result", org.json.JSONObject(result)).toString())
                }
            invoked.complete(Unit)
        }

        fun complete(result: String) {
            callback?.onReceiveValue(result)
        }
    }

    private class Fixture(
        val source: String,
    ) : AutoCloseable {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        var supportsResources = true
        var minimum = 1
        var failDiscovery = false
        var blockCall = false
        var blockToolsList = false
        val toolsListEntered = CountDownLatch(1)
        val releaseToolsList = CountDownLatch(1)
        var calls = 0
        val callEntered = CountDownLatch(1)
        val releaseCall = CountDownLatch(1)

        init {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests += request
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val method = body.getValue("method").jsonPrimitive.content
                        if (method == "server/discover" && failDiscovery) return MockResponse().setResponseCode(503)
                        if (!supportsResources && method.startsWith("resources/")) {
                            return MockResponse()
                                .setResponseCode(400)
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Unsupported resource method"}}""",
                                )
                        }
                        val capabilities =
                            if (supportsResources) {
                                """{"tools":{},"resources":{}}"""
                            } else {
                                """{"tools":{}}"""
                            }
                        val result =
                            when (method) {
                                "server/discover" -> {
                                    """
                                    {"supportedVersions":["2026-07-28"],
                                    "capabilities":$capabilities,
                                    "serverInfo":{"name":"fixture",
                                    "version":"1"},
                                    "instructions":"$source instructions"}
                                    """.trimIndent()
                                }

                                "tools/list" -> {
                                    if (blockToolsList) {
                                        toolsListEntered.countDown()
                                        check(releaseToolsList.await(5, TimeUnit.SECONDS))
                                    }
                                    """
                                    {
                                      "tools": [
                                        {
                                          "name": "duplicate",
                                          "inputSchema": {
                                            "type": "object",
                                            "properties": {
                                              "n": {
                                                "type": "integer",
                                                "minimum": $minimum
                                              }
                                            },
                                            "required": [
                                              "n"
                                            ],
                                            "additionalProperties": false
                                          }
                                        }
                                      ]
                                    }
                                    """.trimIndent()
                                }

                                "resources/list" -> {
                                    """
                                    {"resources":[{"name":"items","uri":"gomode://items","mimeType":"application/json"}]}
                                    """.trimIndent()
                                }

                                "resources/templates/list" -> {
                                    """{"resourceTemplates":[]}"""
                                }

                                "resources/read" -> {
                                    buildJsonObject {
                                        put(
                                            "contents",
                                            Json.parseToJsonElement(
                                                """
                                                [
                                                  {
                                                    "uri": "gomode://items",
                                                    "text": "{\"items\":[{\"id\":\"same\",\"title\":\"Same item\",\"state\":\"ready\"}]}"
                                                  }
                                                ]
                                                """.trimIndent(),
                                            ),
                                        )
                                    }.toString()
                                }

                                "tools/call" -> {
                                    calls++
                                    if (blockCall) {
                                        callEntered.countDown()
                                        check(releaseCall.await(5, TimeUnit.SECONDS))
                                    }
                                    """{"content":[],"structuredContent":{"source":"$source"},"isError":false}"""
                                }

                                else -> {
                                    error("Unexpected request $method")
                                }
                            }
                        val fields =
                            Json.parseToJsonElement(result).jsonObject +
                                mapOf(
                                    "resultType" to JsonPrimitive("complete"),
                                    "ttlMs" to JsonPrimitive(0),
                                    "cacheScope" to JsonPrimitive("private"),
                                )
                        return MockResponse().setHeader("Content-Type", "application/json").setBody(
                            buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("id", body.getValue("id"))
                                put("result", JsonObject(fields))
                            }.toString(),
                        )
                    }
                }
            server.start()
        }

        fun entry(
            id: String,
            authenticated: Boolean = true,
        ): ServiceShellSession {
            val entry = ServiceShellSession(ServiceShellKey(id, server.url("/").toString())) {}
            entry.updateBootstrap(
                ServiceBootstrapState.Ready(
                    Settings(
                        service = "generic",
                        apiVersion = 1,
                        webShell =
                            WebShellSettings(
                                bridgeVersion = 3,
                                toolGroups =
                                    listOf(
                                        ToolGroup(
                                            name = "fixture",
                                            endpoint = "/mcp",
                                            protocolVersion = "2026-07-28",
                                            authRequired = true,
                                        ),
                                    ),
                                voiceGateway = VoiceGatewaySettings(required = false),
                            ),
                    ),
                ),
            )
            entry.bearerStore.onNavigation(entry.key.url)
            if (authenticated) signIn(entry, "token-$id")
            entry.updateLoad(WebShellLoadState.Ready)
            return entry
        }

        override fun close() {
            server.close()
        }
    }

    private companion object {
        fun signIn(
            entry: ServiceShellSession,
            token: String,
        ) {
            entry.bearerStore.onNavigation(entry.key.url)
            assertTrue(
                entry.bearerStore.receive(
                    Uri.parse(entry.key.url),
                    true,
                    entry.key.url,
                    buildJsonObject { put("bearerToken", token) }.toString(),
                ),
            )
            completeFrontendPreparation(entry)
        }

        fun objectOf(vararg pairs: Pair<String, String>) =
            JsonObject(
                pairs.associate {
                    it.first to
                        JsonPrimitive(it.second)
                },
            )

        fun dispatchArgs(
            id: String,
            kind: String = "mcp",
            arguments: String = "{\"n\":2}",
        ) = buildJsonObject {
            put("server_id", id)
            put("kind", kind)
            put("tool", "duplicate")
            put("arguments", Json.parseToJsonElement(arguments))
        }

        suspend fun dispatch(
            catalog: ServiceCatalog,
            id: String,
        ): McpToolResult = requireNotNull(catalog.execute("dispatch_service_tool", dispatchArgs(id)) { true })

        fun McpToolResult.resultString(field: String) =
            structuredContent
                .getValue("result")
                .jsonObject
                .getValue(field)
                .jsonPrimitive.content
    }
}
