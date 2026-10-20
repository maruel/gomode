// Verifies device timezone metadata on native MCP resource reads and subscriptions.
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
import org.junit.Test
import java.util.TimeZone

class McpClientResourceTest {
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
                    client.readResource("host://usage")
                    val readBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                    val read = json.decodeFromJsonElement<ResourcesReadParams>(requireNotNull(readBody["params"]))
                    assertEquals("America/Toronto", read._meta.userLocation?.timezone)
                    assertEquals(null, read._meta.locale)

                    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
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
                        ).first()
                    val listenBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                    val listen =
                        json.decodeFromJsonElement<SubscriptionsListenParams>(requireNotNull(listenBody["params"]))
                    assertEquals("Asia/Kolkata", listen._meta.userLocation?.timezone)
                    assertEquals(null, listen._meta.locale)
                }
            } finally {
                TimeZone.setDefault(original)
            }
        }
}
