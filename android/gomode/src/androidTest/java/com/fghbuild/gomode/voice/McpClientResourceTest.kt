// Verifies native MCP metadata on tool results, resource reads, and subscription reconnects.
package com.fghbuild.gomode.voice

import com.fghbuild.mcp.sdk.v1.ResourcesReadParams
import com.fghbuild.mcp.sdk.v1.SubscriptionFilter
import com.fghbuild.mcp.sdk.v1.SubscriptionsListenParams
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class McpClientResourceTest {
    @Test
    fun authoritativeReadRejectsPrivateMetadataWithoutFailingAppliedTool() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null })
                val metadata = """{"com.fghbuild.gomode/context":{
                    "uri":"fixture://context","epoch":"owner","version":"private-authority-sentinel","dependencies":[]}}"""
                val body = """{"resultType":"complete","ttlMs":0,"cacheScope":"private",
                    "contents":[{"_meta":{"com.fghbuild.gomode/modelContext":true},"uri":"fixture://context","text":"資料 _meta public intact"}],"_meta":$metadata}"""
                server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":$body}"""))
                val error = runCatching { client.readResource("fixture://context", "conversation") }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
                assertFalse(error?.message.orEmpty().contains("private-authority-sentinel"))
                server.enqueue(
                    MockResponse().setBody(
                        """{"jsonrpc":"2.0","result":{"resultType":"complete","content":[],
                    "structuredContent":{"saved":true},"_meta":$metadata}}""",
                    ),
                )
                val result = client.callTool("save", Json.parseToJsonElement("{}").jsonObject, "conversation")
                assertFalse(result.isError)
                assertTrue(result.structuredContent.toString().contains("true"))
                assertFalse(result.structuredContent.toString().contains("private-authority-sentinel"))
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun toolResultsPreserveOpaqueMetadata() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null })
                val meta =
                    Json
                        .parseToJsonElement(
                            """{"example.org/dependencies":{"uris":["host://one"],"revision":7},
                        "other.example/opaque":[null,true,{"nested":"private transport value"}]}""",
                        ).jsonObject
                for (isError in listOf(false, true)) {
                    val structuredField = if (isError) "" else """, "structuredContent":{"state":"ready"}"""
                    server.enqueue(
                        MockResponse().setBody(
                            """
                            {"jsonrpc":"2.0","id":1,"result":{
                            "resultType":"complete","content":[{"type":"text","text":"Readable tool message"}],
                            "isError":$isError,"_meta":$meta$structuredField}}
                            """.trimIndent(),
                        ),
                    )
                    val result = client.callTool("read_item", Json.parseToJsonElement("{}").jsonObject)
                    assertEquals(meta, result.meta)
                    assertEquals(isError, result.isError)
                    assertEquals(
                        Json.parseToJsonElement(
                            if (isError) """{"error":"Readable tool message"}""" else """{"state":"ready"}""",
                        ),
                        result.structuredContent,
                    )
                }
            }
        }

    @Test
    fun resourceRequestsUseDeviceTimezone() =
        runTest {
            val original = TimeZone.getDefault()
            val json = Json { ignoreUnknownKeys = true }
            try {
                MockWebServer().use { server ->
                    server.start()
                    val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null })
                    TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
                    server.enqueue(
                        MockResponse().setBody(
                            """{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","contents":[],"ttlMs":0,"cacheScope":"private"}}""",
                        ),
                    )
                    client.readResource("host://usage", "conversation-one")
                    val readBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                    val read = json.decodeFromJsonElement<ResourcesReadParams>(requireNotNull(readBody["params"]))
                    assertEquals("America/Toronto", read._meta.userLocation?.timezone)
                    assertEquals(null, read._meta.locale)
                    assertEquals("conversation-one", read._meta.session)

                    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
                    repeat(2) {
                        server.enqueue(
                            MockResponse()
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody(
                                    "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/subscriptions/acknowledged\",\"params\":{}}\n\n",
                                ),
                        )
                        client
                            .listenSubscriptions(
                                SubscriptionFilter(resourceSubscriptions = listOf("host://usage")),
                                "conversation-one",
                            ).first()
                        val listenBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                        val listen =
                            json.decodeFromJsonElement<SubscriptionsListenParams>(requireNotNull(listenBody["params"]))
                        assertEquals("Asia/Kolkata", listen._meta.userLocation?.timezone)
                        assertEquals(null, listen._meta.locale)
                        assertEquals("conversation-one", listen._meta.session)
                    }
                }
            } finally {
                TimeZone.setDefault(original)
            }
        }
}
