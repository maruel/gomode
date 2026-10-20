// Emulator coverage for simultaneous authenticated MCP streams and explicit retained-page tool routing.
package com.fghbuild.gomode

import android.net.Uri
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.sdk.v1.Settings
import com.fghbuild.gomode.sdk.v1.ToolGroup
import com.fghbuild.gomode.sdk.v1.VoiceGatewaySettings
import com.fghbuild.gomode.sdk.v1.WebShellSettings
import com.fghbuild.gomode.service.CatalogNotification
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceReadiness
import com.fghbuild.gomode.ui.ServiceBootstrapState
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.WebShellLoadState
import com.fghbuild.gomode.ui.web.WebShellScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConcurrentServiceCatalogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun twoRetainedPagesKeepIndependentStreamsSchemasAndFrontendOwners() {
        ParticipantFixture("A").use { first ->
            ParticipantFixture("B").use { second ->
                val entries = listOf(first.entry("a"), second.entry("b"))
                var selected by mutableStateOf(entries.first())
                lateinit var catalog: ServiceCatalog
                val notifications = CopyOnWriteArrayList<CatalogNotification>()
                composeRule.setContent {
                    val scope = rememberCoroutineScope()
                    catalog = remember { ServiceCatalog(scope) }
                    catalog.reconcile(entries.associateBy { it.key }, mapOf("a" to "Alpha", "b" to "Beta"))
                    DisposableEffect(catalog) {
                        val job = scope.launch { catalog.notifications.collect { notifications += it } }
                        onDispose {
                            job.cancel()
                            catalog.close()
                            entries.forEach { it.close() }
                        }
                    }
                    entries.forEach { entry ->
                        key(
                            entry,
                            entry.state
                                .collectAsState()
                                .value.webViewGeneration,
                        ) {
                            WebShellScreen(
                                serviceID = entry.key.serviceID,
                                initialURL = entry.key.url,
                                voiceConnected = false,
                                visible = entry === selected,
                                reloadToken = 0,
                                serviceSession = entry,
                                onLoadStateChanged = entry::updateLoad,
                                onAuthInvalidated = {},
                            )
                        }
                    }
                }
                composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                    catalog.state.value.values
                        .count { it.readiness == ServiceReadiness.READY } == 2 &&
                        first.streams.get() >= 1 && second.streams.get() >= 1 && notifications.size >= 2 &&
                        entries.all { it.frontendTools.declarations.isNotEmpty() }
                }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                    device.hasObject(By.text("Generic retained participant A"))
                }
                assertNotEquals(notifications[0].notification.id, notifications[1].notification.id)
                assertEquals(setOf("a", "b"), notifications.map { it.source.serviceID }.toSet())
                assertTrue(notifications.any { it.notification.title.startsWith("Alpha:") })
                assertTrue(notifications.any { it.notification.title.startsWith("Beta:") })
                for ((id, source) in listOf("a" to "A", "b" to "B")) {
                    for (kind in listOf("mcp", "frontend")) {
                        val result =
                            runBlocking {
                                withContext(Dispatchers.Main) {
                                    catalog.execute(
                                        "dispatch_service_tool",
                                        buildJsonObject {
                                            put("server_id", id)
                                            put("kind", kind)
                                            put("tool", "duplicate")
                                            put("arguments", buildJsonObject { put("value", source) })
                                        },
                                    ) { true }
                                }
                            }
                        assertFalse(requireNotNull(result).isError)
                        val structured = result.structuredContent
                        assertEquals(id, structured.getValue("server_id").jsonPrimitive.content)
                        assertEquals(
                            "$source-$kind",
                            structured
                                .getValue("result")
                                .jsonObject
                                .getValue("source")
                                .jsonPrimitive.content,
                        )
                    }
                }
                repeat(2) {
                    val initialStreams = listOf(first.streams.get(), second.streams.get())
                    val oldNotifications = notifications.toList()
                    lateinit var destroyed: WebView
                    composeRule.runOnUiThread {
                        destroyed = composeRule.activity.findViewById(R.id.web_shell)
                        assertTrue(requireNotNull(destroyed.webViewRenderProcess).terminate())
                    }
                    composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                        entries.all { it.state.value.load is WebShellLoadState.Failed }
                    }
                    composeRule.runOnIdle {
                        assertFalse(destroyed.isAttachedToWindow)
                        oldNotifications.forEach { assertFalse(catalog.canDeliver(it)) }
                        entries.forEach { it.reloadWeb() }
                    }
                    composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                        catalog.state.value.values
                            .count { it.readiness == ServiceReadiness.READY } == 2 &&
                            first.streams.get() == initialStreams[0] + 1 &&
                            second.streams.get() == initialStreams[1] + 1 &&
                            entries.all { it.frontendTools.declarations.size == 1 }
                    }
                    // Reconnection establishes a fresh notification baseline, not duplicate delivery.
                    composeRule.waitForIdle()
                    assertEquals(oldNotifications.size, notifications.size)
                }
                composeRule.runOnIdle { selected = entries[1] }
                composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                    device.hasObject(By.text("Generic retained participant B"))
                }
                composeRule.runOnUiThread {
                    composeRule.activity.findViewById<WebView>(R.id.web_shell).evaluateJavascript(
                        "gomodeAuth.postMessage(JSON.stringify({bearerToken:null}))",
                        null,
                    )
                }
                composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                    catalog.state.value[entries[1].key]?.readiness == ServiceReadiness.WAITING_FOR_SIGN_IN
                }
                assertEquals(ServiceReadiness.READY, catalog.state.value[entries[0].key]?.readiness)
                val oldEvent = notifications.first { it.source == entries[1].key }
                composeRule.runOnIdle {
                    assertFalse(catalog.canDeliver(oldEvent))
                    composeRule.activity.findViewById<WebView>(R.id.web_shell).evaluateJavascript(
                        "gomodeAuth.postMessage(JSON.stringify({bearerToken:'B-token'}))",
                        null,
                    )
                }
                composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                    catalog.state.value[entries[1].key]?.readiness == ServiceReadiness.READY &&
                        second.streams.get() >= 2
                }
                composeRule.runOnIdle {
                    assertFalse(catalog.canDeliver(oldEvent))
                    val originalEvent = notifications.first { it.source == entries[0].key }
                    // This event predates the renderer recovery and must stay revoked.
                    assertFalse(catalog.canDeliver(originalEvent))
                    val replacement = first.entry("a")
                    replacement.bearerStore.onNavigation(replacement.key.url)
                    replacement.bearerStore.receive(
                        Uri.parse(replacement.key.url),
                        true,
                        replacement.key.url,
                        "{\"bearerToken\":\"A-token\"}",
                    )
                    replacement.updateLoad(WebShellLoadState.Ready)
                    catalog.reconcile(mapOf(replacement.key to replacement, entries[1].key to entries[1]), emptyMap())
                    assertFalse(catalog.canDeliver(originalEvent))
                }
                composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                    first.streams.get() >= 2 && catalog.state.value[entries[0].key]?.readiness == ServiceReadiness.READY
                }
                composeRule.runOnIdle {
                    val originalEvent = notifications.first { it.source == entries[0].key }
                    assertFalse(catalog.canDeliver(originalEvent))
                    val replacedURL = ServiceShellSession(ServiceShellKey("a", entries[0].key.url + "replacement"))
                    catalog.reconcile(mapOf(replacedURL.key to replacedURL, entries[1].key to entries[1]), emptyMap())
                    assertFalse(catalog.canDeliver(originalEvent))
                    catalog.close()
                }
            }
        }
    }

    @Test
    fun queuedNotificationRejectsBearerLogoutAndReloginBeforeCollectorsCanRun() {
        ParticipantFixture("A").use { fixture ->
            val entry = fixture.entry("a")
            lateinit var catalog: ServiceCatalog
            val notifications = CopyOnWriteArrayList<CatalogNotification>()
            composeRule.setContent {
                val scope = rememberCoroutineScope()
                catalog = remember { ServiceCatalog(scope) }
                catalog.reconcile(mapOf(entry.key to entry), emptyMap())
                DisposableEffect(catalog) {
                    val job = scope.launch { catalog.notifications.collect { notifications += it } }
                    onDispose {
                        job.cancel()
                        catalog.close()
                        entry.close()
                    }
                }
                WebShellScreen(
                    serviceID = entry.key.serviceID,
                    initialURL = entry.key.url,
                    voiceConnected = false,
                    reloadToken = 0,
                    serviceSession = entry,
                    onLoadStateChanged = entry::updateLoad,
                    onAuthInvalidated = {},
                )
            }
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { notifications.isNotEmpty() }
            val queued = notifications.first()
            composeRule.runOnIdle {
                assertTrue(catalog.canDeliver(queued))
                val origin = Uri.parse(entry.key.url)
                assertTrue(entry.bearerStore.receive(origin, true, entry.key.url, "{\"bearerToken\":null}"))
                assertTrue(entry.bearerStore.receive(origin, true, entry.key.url, "{\"bearerToken\":\"A-token\"}"))
                // This is the permission-approval gate before the catalog collector has a chance to observe logout.
                assertFalse(catalog.canDeliver(queued))
            }
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                fixture.streams.get() >= 2 && catalog.state.value[entry.key]?.readiness == ServiceReadiness.READY
            }
            composeRule.runOnIdle {
                assertFalse(catalog.canDeliver(queued))
                catalog.close()
            }
        }
    }

    @Test
    fun toolsOnlyParticipantDiscoversSchemasWithoutResourceRequests() {
        ParticipantFixture("Tools only", supportsResources = false).use { fixture ->
            val entry = fixture.entry("a")
            lateinit var catalog: ServiceCatalog
            composeRule.setContent {
                val scope = rememberCoroutineScope()
                catalog = remember { ServiceCatalog(scope) }
                catalog.reconcile(mapOf(entry.key to entry), emptyMap())
                DisposableEffect(catalog) {
                    onDispose {
                        catalog.close()
                        entry.close()
                    }
                }
                WebShellScreen(
                    serviceID = entry.key.serviceID,
                    initialURL = entry.key.url,
                    voiceConnected = false,
                    reloadToken = 0,
                    serviceSession = entry,
                    onLoadStateChanged = entry::updateLoad,
                    onAuthInvalidated = {},
                )
            }
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                catalog.state.value[entry.key]?.readiness == ServiceReadiness.READY
            }
            runBlocking {
                withContext(Dispatchers.Main) {
                    val discovered =
                        requireNotNull(
                            catalog.execute(
                                "discover_service",
                                buildJsonObject {
                                    put("server_id", "a")
                                },
                            ) { true },
                        )
                    assertFalse(discovered.isError)
                    val detail = discovered.structuredContent.getValue("result").jsonObject
                    assertTrue(detail.getValue("mcp_tools").toString().contains("duplicate"))
                    assertEquals("[]", detail.getValue("resources").toString())
                    assertEquals("[]", detail.getValue("resource_templates").toString())
                    val read =
                        requireNotNull(
                            catalog.execute(
                                "read_service_resource",
                                buildJsonObject {
                                    put("server_id", "a")
                                    put("uri", "gomode://items")
                                },
                            ) { true },
                        )
                    assertTrue(read.isError)
                    assertEquals(0, fixture.resourceRequests.get())
                    catalog.close()
                }
            }
        }
    }

    private class ParticipantFixture(
        val source: String,
        private val supportsResources: Boolean = true,
    ) : AutoCloseable {
        private val server = MockWebServer()
        val streams = AtomicInteger()
        val resourceRequests = AtomicInteger()
        private val notificationReads = AtomicInteger()

        init {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "GET") {
                            return MockResponse().setHeader("Content-Type", "text/html").setBody(
                                """
                                <html><body>Generic retained participant $source<script>
                                gomodeAuth.postMessage(JSON.stringify({bearerToken:'$source-token'}));
                                gomodeVoiceTools.postMessage(JSON.stringify([{
                                  name:'duplicate',description:'Frontend',parameters:{
                                  type:'object',properties:{value:{type:'string'}},
                                  required:['value'],additionalProperties:false}}]));
                                window.executeGoModeVoiceTool = (name,args) => (
                                  {source:'$source-frontend',value:args.value});
                                </script></body></html>
                                """.trimIndent(),
                            )
                        }
                        check(request.getHeader("Authorization") == "Bearer $source-token")
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val method = body.getValue("method").jsonPrimitive.content
                        if (method.startsWith("resources/")) {
                            resourceRequests.incrementAndGet()
                            if (!supportsResources) return MockResponse().setResponseCode(400)
                        }
                        if (method == "subscriptions/listen") {
                            streams.incrementAndGet()
                            val events =
                                listOf("gomode://items", "gomode://notifications").joinToString("") { uri ->
                                    "data: " +
                                        buildJsonObject {
                                            put("jsonrpc", "2.0")
                                            put("method", "notifications/resources/updated")
                                            put("params", buildJsonObject { put("uri", uri) })
                                        } + "\n\n"
                                } + ": ping\n\n".repeat(10_000)
                            return MockResponse()
                                .setHeader("Content-Type", "text/event-stream")
                                .setChunkedBody(events, 512)
                                .throttleBody(512, 100, TimeUnit.MILLISECONDS)
                        }
                        val result =
                            when (method) {
                                "server/discover" -> {
                                    """
                                    {
                                      "supportedVersions": [
                                        "2026-07-28"
                                      ],
                                      "serverInfo": {
                                        "name": "fixture",
                                        "version": "1"
                                      },
                                      "capabilities": {
                                        "tools": {},
                                        "resources": {
                                          "subscribe": true
                                        }
                                      }
                                    }
                                    """.trimIndent()
                                }

                                "tools/list" -> {
                                    """
                                    {
                                      "tools": [
                                        {
                                          "name": "duplicate",
                                          "inputSchema": {
                                            "type": "object",
                                            "properties": {
                                              "value": {
                                                "type": "string"
                                              }
                                            },
                                            "required": [
                                              "value"
                                            ],
                                            "additionalProperties": false
                                          }
                                        }
                                      ]
                                    }
                                    """.trimIndent()
                                }

                                "tools/call" -> {
                                    """{"content":[],"structuredContent":{"source":"$source-mcp"},"isError":false}"""
                                }

                                "resources/list" -> {
                                    """
                                    {
                                      "resources": [
                                        {
                                          "uri": "gomode://items",
                                          "name": "items",
                                          "mimeType": "application/json"
                                        },
                                        {
                                          "uri": "gomode://notifications",
                                          "name": "notifications",
                                          "mimeType": "application/json"
                                        }
                                      ]
                                    }
                                    """.trimIndent()
                                }

                                "resources/read" -> {
                                    val uri =
                                        body
                                            .getValue("params")
                                            .jsonObject
                                            .getValue("uri")
                                            .jsonPrimitive.content
                                    val text =
                                        if (uri == "gomode://items") {
                                            """{"items":[{"id":"same","title":"Same item","state":"$source"}]}"""
                                        } else if (notificationReads.incrementAndGet() == 1) {
                                            "[]"
                                        } else {
                                            """[{"id":"same-event","title":"Ready","body":"$source updated"}]"""
                                        }
                                    buildJsonObject {
                                        put(
                                            "contents",
                                            JsonArray(
                                                listOf(
                                                    buildJsonObject {
                                                        put("uri", uri)
                                                        put("text", text)
                                                    },
                                                ),
                                            ),
                                        )
                                    }.toString()
                                }

                                else -> {
                                    error("Unexpected fixture method $method")
                                }
                            }
                        val decoded = Json.parseToJsonElement(result).jsonObject
                        val advertised =
                            if (method == "server/discover" && !supportsResources) {
                                decoded + ("capabilities" to buildJsonObject { put("tools", buildJsonObject {}) })
                            } else {
                                decoded
                            }
                        val fields =
                            advertised +
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

        fun entry(id: String): ServiceShellSession =
            ServiceShellSession(ServiceShellKey(id, server.url("/").toString())).apply {
                updateBootstrap(
                    ServiceBootstrapState.Ready(
                        Settings(
                            service = "generic",
                            apiVersion = 1,
                            webShell =
                                WebShellSettings(
                                    bridgeVersion = 1,
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
            }

        override fun close() {
            server.close()
        }
    }
}
