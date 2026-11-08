// Exercises active-item contracts and item-only conversation ownership through native MCP reads on an emulator.
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
                                        """{"resources":[{"uri":"gomode://items","name":"items"},{"uri":"gomode://notifications","name":"alerts"}]}"""
                                    }

                                    "server/discover" -> {
                                        """{"supportedVersions":["2026-07-28"],"capabilities":{"resources":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
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
                                        """{"contents":[{"uri":"gomode://items","text":"{\"items\":[{\"id\":\"selected\",\"title\":\"Selected document\",\"active\":true}]}"}]}"""
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
                        ServiceMonitorPurpose.CONVERSATION_ITEMS,
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
                            withTimeout(5000) { monitor.state.first { it.snapshot != null }.snapshot }
                        }
                    assertEquals("selected", checkNotNull(snapshot).activeItem?.id)
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
            val vectors =
                InstrumentationRegistry
                    .getInstrumentation()
                    .context.assets
                    .open("active-context.json")
                    .bufferedReader()
                    .use { Json.parseToJsonElement(it.readText()).jsonArray }
            val source = AtomicReference<String?>(null)
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            val text = source.get()
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
                                                    add(
                                                        buildJsonObject {
                                                            put("uri", "gomode://items")
                                                            put("name", "items")
                                                            put("mimeType", "application/json")
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    } else {
                                        put(
                                            "contents",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("uri", "gomode://items")
                                                        put("mimeType", "application/json")
                                                        put("text", checkNotNull(text))
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
                for (vector in vectors) {
                    val fields = vector.jsonObject
                    val name = fields.getValue("name").jsonPrimitive.content
                    source.set(fields.getValue("resource").toString())
                    val setup =
                        gatewaySessionSetup(
                            tools = emptyList(),
                            systemInstruction = "",
                            serviceContextText = readInitialServiceContext(client, "focus-session"),
                            languageTag = "en-US",
                        )
                    val context = checkNotNull(setup.context.text)
                    val focus = fields.getValue("focus").jsonPrimitive.contentOrNull
                    assertEquals(name, focus != null, context.startsWith("Active service item:"))
                    if (focus != null) assertTrue(name, context.startsWith(focus))
                    assertTrue(name, context.length <= 4000)
                }
                source.set(null)
                assertEquals("No visible service items.", readInitialServiceContext(client, "focus-session"))
            }
        }
}
