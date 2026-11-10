// Exercises opaque server model context and native resource ownership through actual MCP reads.
package com.fghbuild.gomode.service

import androidx.test.platform.app.InstrumentationRegistry
import com.fghbuild.gomode.voice.McpClient
import com.fghbuild.gomode.voice.gatewaySessionSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ServiceResourcesContextTest {
    @Test
    fun conversationItemsIgnoreAlertReadsAndReleaseTheirOwner() =
        runTest {
            val alertReads = AtomicInteger()
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            val method = body.getValue("method").jsonPrimitive.content
                            val result =
                                when (method) {
                                    "resources/list" -> {
                                        """{"resources":[{"_meta":{"com.fghbuild.gomode/modelContext":true},"uri":"fixture://catalog","name":"items"},{"_meta":{"com.fghbuild.gomode/modelContext":true},"uri":"fixture://context","name":"context"},{"uri":"gomode://notifications","name":"alerts"}]}"""
                                    }

                                    "server/discover" -> {
                                        """{"supportedVersions":["2026-07-28"],"capabilities":{"resources":{},"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
                                    }

                                    "resources/read" -> {
                                        val uri =
                                            body
                                                .getValue("params")
                                                .jsonObject
                                                .getValue("uri")
                                                .jsonPrimitive.content
                                        if (uri == "gomode://notifications") {
                                            alertReads.incrementAndGet()
                                            return MockResponse().setResponseCode(503)
                                        }
                                        val text =
                                            if (uri ==
                                                FIXTURE_CONTEXT_URI
                                            ) {
                                                """{"sélection":"selected"}"""
                                            } else {
                                                """{"items":[{"id":"selected","title":"Selected document"}]}"""
                                            }
                                        buildJsonObject {
                                            put(
                                                "contents",
                                                buildJsonArray {
                                                    add(
                                                        buildJsonObject {
                                                            put("uri", uri)
                                                            put("text", text)
                                                        },
                                                    )
                                                },
                                            )
                                        }.toString()
                                    }

                                    else -> {
                                        error("Unexpected request $method")
                                    }
                                }
                            val fields =
                                Json.parseToJsonElement(result).jsonObject +
                                    mapOf(
                                        "resultType" to kotlinx.serialization.json.JsonPrimitive("complete"),
                                        "ttlMs" to kotlinx.serialization.json.JsonPrimitive(0),
                                        "cacheScope" to kotlinx.serialization.json.JsonPrimitive("private"),
                                    )
                            return MockResponse().setBody(
                                buildJsonObject {
                                    put("jsonrpc", "2.0")
                                    put("id", body.getValue("id"))
                                    put("result", kotlinx.serialization.json.JsonObject(fields))
                                }.toString(),
                            )
                        }
                    }
                server.start()
                val monitor =
                    ServiceMonitor(
                        backgroundScope,
                        "native-conversation",
                        ServiceMonitorPurpose.CONVERSATION_CONTEXT,
                    ) { endpoint, protocol ->
                        McpClient(endpoint, protocol, { null })
                    }
                val settings =
                    com.fghbuild.gomode.sdk.v1.Settings(
                        service = "fixture",
                        apiVersion = 1,
                        webShell =
                            com.fghbuild.gomode.sdk.v1.WebShellSettings(
                                bridgeVersion = 3,
                                toolGroups =
                                    listOf(
                                        com.fghbuild.gomode.sdk.v1.ToolGroup(
                                            name = "workspace",
                                            endpoint = "/mcp",
                                            protocolVersion = "2026-07-28",
                                            authRequired = false,
                                        ),
                                    ),
                                voiceGateway =
                                    com.fghbuild.gomode.sdk.v1
                                        .VoiceGatewaySettings(required = false),
                            ),
                    )
                try {
                    monitor.start(server.url("/").toString(), settings)
                    val snapshot =
                        withContext(Dispatchers.Default) {
                            withTimeout(5000) {
                                monitor.state
                                    .first {
                                        it.snapshot
                                            ?.context
                                            ?.getOrNull()
                                            ?.let {
                                                Json
                                                    .parseToJsonElement(
                                                        it,
                                                    ).jsonObject["sélection"]
                                                    ?.jsonPrimitive
                                                    ?.content
                                            } ==
                                            "selected"
                                    }.snapshot
                            }
                        }
                    assertEquals(
                        "selected",
                        checkNotNull(snapshot)
                            .context
                            ?.getOrNull()
                            ?.let {
                                Json
                                    .parseToJsonElement(
                                        it,
                                    ).jsonObject["sélection"]
                                    ?.jsonPrimitive
                                    ?.content
                            },
                    )
                    assertEquals(0, alertReads.get())
                } finally {
                    monitor.stop()
                }
                assertEquals(null, monitor.state.value.snapshot)
            }
        }

    @Test
    fun activeContextSurvivesNativeResourceReads() =
        runTest {
            val source = AtomicReference<String?>(null)
            val items = AtomicReference("""{"items":[{"id":"one","title":"Build"}]}""")
            val contextStatus = AtomicInteger()
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            val text = source.get()
                            if (body.getValue("method").jsonPrimitive.content == "resources/read" &&
                                body
                                    .getValue("params")
                                    .jsonObject
                                    .getValue("uri")
                                    .jsonPrimitive.content ==
                                FIXTURE_CONTEXT_URI &&
                                contextStatus.get() != 0
                            ) {
                                return MockResponse().setResponseCode(contextStatus.get()).setBody(
                                    """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"Context temporarily unavailable"}}""",
                                )
                            }
                            val result =
                                buildJsonObject {
                                    put("resultType", "complete")
                                    put("ttlMs", 0)
                                    put("cacheScope", "private")
                                    if (body.getValue("method").jsonPrimitive.content == "resources/list") {
                                        put(
                                            "resources",
                                            buildJsonArray {
                                                if (text != null) {
                                                    for (uri in listOf(
                                                        FIXTURE_CATALOG_URI,
                                                        FIXTURE_CONTEXT_URI,
                                                    )) {
                                                        add(
                                                            buildJsonObject {
                                                                put("uri", uri)
                                                                put("name", uri)
                                                                put(
                                                                    "_meta",
                                                                    buildJsonObject {
                                                                        put(
                                                                            "com.fghbuild.gomode/modelContext",
                                                                            true,
                                                                        )
                                                                    },
                                                                )
                                                                put("mimeType", "application/json")
                                                            },
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    } else {
                                        put(
                                            "contents",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        val uri =
                                                            body
                                                                .getValue(
                                                                    "params",
                                                                ).jsonObject
                                                                .getValue("uri")
                                                                .jsonPrimitive.content
                                                        put("uri", uri)
                                                        put("mimeType", "application/json")
                                                        put(
                                                            "text",
                                                            if (uri ==
                                                                FIXTURE_CONTEXT_URI
                                                            ) {
                                                                checkNotNull(text)
                                                            } else {
                                                                items.get()
                                                            },
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    }
                                }
                            return MockResponse().setBody(
                                buildJsonObject {
                                    put("jsonrpc", "2.0")
                                    put("id", body.getValue("id"))
                                    put("result", result)
                                }.toString(),
                            )
                        }
                    }
                server.start()
                val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null })
                for (content in listOf("""{"選択":null,"_meta":"public body"}""", "Aucune sélection.", "[42, false]")) {
                    source.set(content)
                    val setup =
                        gatewaySessionSetup(
                            tools = emptyList(),
                            systemInstruction = "",
                            serviceContextText = readInitialServiceContext(client, "focus-session"),
                            languageTag = "en-US",
                        )
                    val received =
                        Json
                            .parseToJsonElement(
                                checkNotNull(setup.context.text).lineSequence().first {
                                    Json
                                        .parseToJsonElement(it)
                                        .jsonObject["resource"]
                                        ?.jsonPrimitive
                                        ?.content ==
                                        FIXTURE_CONTEXT_URI
                                },
                            ).jsonObject
                    assertEquals(content, received.getValue("content").jsonPrimitive.content)
                }
                items.set("""{"items":[{"id":"one","title":"Build"}]}""")
                for (status in listOf(200, 429, 503)) {
                    contextStatus.set(status)
                    val context = readInitialServiceContext(client, "focus-session")
                    assertTrue(context.contains("Build"))
                    assertTrue(context.contains("\"status\":\"error\""))
                    val failure =
                        runCatching {
                            client.readResource(
                                FIXTURE_CONTEXT_URI,
                                "focus-session",
                            )
                        }.exceptionOrNull()
                    assertTrue(failure is com.fghbuild.mcp.sdk.v1.ApiException)
                    assertEquals(status, (failure as com.fghbuild.mcp.sdk.v1.ApiException).statusCode)
                }
                contextStatus.set(0)
                source.set(null)
                assertEquals("", readInitialServiceContext(client, "focus-session"))
                source.set("""{"sélection":null}""")
                items.set("""{"items":[]}""")
                val empty =
                    readInitialServiceContext(client, "focus-session")
                        .lineSequence()
                        .map { Json.parseToJsonElement(it).jsonObject }
                        .toList()
                empty.forEach { assertEquals("known", it.getValue("status").jsonPrimitive.content) }
                assertEquals(
                    """{"sélection":null}""",
                    empty
                        .single { it.getValue("resource").jsonPrimitive.content == FIXTURE_CONTEXT_URI }
                        .getValue("content")
                        .jsonPrimitive.content,
                )
                assertEquals(
                    """{"items":[]}""",
                    empty
                        .single { it.getValue("resource").jsonPrimitive.content == FIXTURE_CATALOG_URI }
                        .getValue("content")
                        .jsonPrimitive.content,
                )
            }
        }
}

private const val FIXTURE_CONTEXT_URI = "fixture://context"
private const val FIXTURE_CATALOG_URI = "fixture://catalog"
private val ServiceMonitoringSnapshot.context: Result<String?>?
    get() = resources[FIXTURE_CONTEXT_URI]?.map { it?.let { read -> resourceText(read.result, FIXTURE_CONTEXT_URI) } }
