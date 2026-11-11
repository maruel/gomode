// Tests advertised resource isolation, ordered reconciliation, stream recovery, and native alert ownership.
package com.fghbuild.gomode.service

import com.fghbuild.gomode.sdk.v1.Settings
import com.fghbuild.gomode.sdk.v1.ToolGroup
import com.fghbuild.gomode.sdk.v1.WebShellSettings
import com.fghbuild.mcp.sdk.v1.CacheScope
import com.fghbuild.mcp.sdk.v1.Capabilities
import com.fghbuild.mcp.sdk.v1.Implementation
import com.fghbuild.mcp.sdk.v1.JSONPresence
import com.fghbuild.mcp.sdk.v1.JSONRPCNotification
import com.fghbuild.mcp.sdk.v1.NotificationMethod
import com.fghbuild.mcp.sdk.v1.ResourceContent
import com.fghbuild.mcp.sdk.v1.ResourceDescriptor
import com.fghbuild.mcp.sdk.v1.ResourcesCapability
import com.fghbuild.mcp.sdk.v1.ResourcesReadResult
import com.fghbuild.mcp.sdk.v1.ResultType
import com.fghbuild.mcp.sdk.v1.ServerDiscoverResult
import com.fghbuild.mcp.sdk.v1.SubscriptionFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceMonitorTest {
    @Test
    fun `marked resources refresh independently and unmarked resources stay on demand`() =
        runTest {
            val client = Fixture()
            val monitor =
                ServiceMonitor(
                    backgroundScope,
                    "conversation",
                    ServiceMonitorPurpose.CONVERSATION_CONTEXT,
                ) { _, _ -> client }
            monitor.start("https://service.test", settings())
            runCurrent()
            assertEquals(listOf(FIXTURE_CONTEXT_URI, FIXTURE_CATALOG_URI), client.reads)
            assertTrue(
                monitor.state.value.voiceContext
                    .contains("東京"),
            )
            assertFalse(
                monitor.state.value.voiceContext
                    .contains("private-epoch"),
            )
            client.reads.clear()
            client.text[FIXTURE_CATALOG_URI] = "Dossier modifié"
            client.changed(FIXTURE_CATALOG_URI)
            runCurrent()
            assertEquals(listOf(FIXTURE_CATALOG_URI), client.reads)
            assertTrue(
                monitor.state.value.voiceContext
                    .contains("Dossier modifié"),
            )
            client.failures += FIXTURE_CONTEXT_URI
            client.changed(FIXTURE_CONTEXT_URI)
            runCurrent()
            assertEquals("error", status(monitor, FIXTURE_CONTEXT_URI))
            assertEquals("known", status(monitor, FIXTURE_CATALOG_URI))
            client.failures.clear()
            client.changed(FIXTURE_CONTEXT_URI)
            runCurrent()
            assertEquals("known", status(monitor, FIXTURE_CONTEXT_URI))
            monitor.stop()
            runCurrent()
            assertEquals(client.filters.size, client.completedStreams)
        }

    @Test
    fun `withdrawal retires subscriptions and reappearance is unread before its read completes`() =
        runTest {
            val client = Fixture()
            val monitor =
                ServiceMonitor(
                    backgroundScope,
                    "conversation",
                    ServiceMonitorPurpose.CONVERSATION_CONTEXT,
                ) { _, _ -> client }
            monitor.start("https://service.test", settings())
            runCurrent()
            client.resources = client.resources.filter { it.uri != FIXTURE_CONTEXT_URI }
            client.listChanged()
            runCurrent()
            assertEquals("absent", status(monitor, FIXTURE_CONTEXT_URI))
            assertFalse(
                client.filters
                    .last()
                    .resourceSubscriptions
                    .orEmpty()
                    .contains(FIXTURE_CONTEXT_URI),
            )
            val gate = CompletableDeferred<Unit>()
            client.gates[FIXTURE_CONTEXT_URI] = gate
            client.resources += model(FIXTURE_CONTEXT_URI)
            client.listChanged()
            runCurrent()
            assertEquals("unread", status(monitor, FIXTURE_CONTEXT_URI))
            assertEquals("known", status(monitor, FIXTURE_CATALOG_URI))
            gate.complete(Unit)
            runCurrent()
            assertEquals("known", status(monitor, FIXTURE_CONTEXT_URI))
            assertTrue(
                client.filters
                    .last()
                    .resourceSubscriptions
                    .orEmpty()
                    .contains(FIXTURE_CONTEXT_URI),
            )
            monitor.stop()
        }

    @Test
    fun `authority rejects obsolete reads and accepts a new epoch without exposing metadata`() =
        runTest {
            val client = Fixture()
            val monitor =
                ServiceMonitor(
                    backgroundScope,
                    "conversation",
                    ServiceMonitorPurpose.CONVERSATION_CONTEXT,
                ) { _, _ -> client }
            monitor.start("https://service.test", settings())
            runCurrent()
            client.version = 3
            client.text[FIXTURE_CONTEXT_URI] = "nouveau"
            client.changed(FIXTURE_CONTEXT_URI)
            runCurrent()
            client.version = 2
            client.text[FIXTURE_CONTEXT_URI] = "obsolete"
            client.changed(FIXTURE_CONTEXT_URI)
            runCurrent()
            assertTrue(
                monitor.state.value.voiceContext
                    .contains("nouveau"),
            )
            assertFalse(
                monitor.state.value.voiceContext
                    .contains("obsolete"),
            )
            client.epoch = "replacement"
            client.version = 1
            client.text[FIXTURE_CONTEXT_URI] = "recréé"
            client.changed(FIXTURE_CONTEXT_URI)
            runCurrent()
            assertTrue(
                monitor.state.value.voiceContext
                    .contains("recréé"),
            )
            assertFalse(
                monitor.state.value.voiceContext
                    .contains("replacement"),
            )
            monitor.stop()
        }

    @Test
    fun `stop cancels a pending resource read before fixture cleanup and cannot publish its result`() =
        runTest {
            val client = Fixture()
            val gate = CompletableDeferred<Unit>()
            client.gates[FIXTURE_CONTEXT_URI] = gate
            val monitor =
                ServiceMonitor(backgroundScope, "old", ServiceMonitorPurpose.CONVERSATION_CONTEXT) { _, _ -> client }
            monitor.start("https://service.test", settings())
            runCurrent()
            assertEquals("known", status(monitor, FIXTURE_CATALOG_URI))
            monitor.stop()
            runCurrent()
            assertTrue(client.cancelledReads.contains(FIXTURE_CONTEXT_URI))
            assertFalse(gate.isCompleted)
            gate.complete(Unit)
            runCurrent()
            assertEquals(ServiceMonitorState(), monitor.state.value)
        }

    @Test
    fun `alert invalidation during initial read retains publication for its followup`() =
        runTest {
            val client = Fixture()
            val uri = GOMODE_NOTIFICATIONS_RESOURCE_URI
            client.resources += ResourceDescriptor(uri = uri, name = "alerts")
            client.text[uri] = "[]"
            client.responses[uri] = ArrayDeque(listOf("[]", """[{"id":"new","title":"Review","body":"Ready"}]"""))
            val gate = CompletableDeferred<Unit>()
            client.gates[uri] = gate
            val monitor = ServiceMonitor(backgroundScope) { _, _ -> client }
            monitor.start("https://service.test", settings())
            runCurrent()
            client.changed(uri)
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(uri, uri), client.reads)
            assertEquals(
                listOf("new"),
                monitor.state.value.notifications
                    .map { it.id },
            )
            monitor.stop()
        }

    @Test
    fun `background owner reads only alerts and reconciles occurrences without duplicate delivery`() =
        runTest {
            val client = Fixture()
            client.resources += ResourceDescriptor(uri = GOMODE_NOTIFICATIONS_RESOURCE_URI, name = "alerts")
            client.text[GOMODE_NOTIFICATIONS_RESOURCE_URI] =
                """[{"id":"a","title":"Review","body":"Pending","url":"/first"}]"""
            val monitor = ServiceMonitor(backgroundScope) { _, _ -> client }
            monitor.start("https://service.test", settings())
            runCurrent()
            assertEquals(listOf(GOMODE_NOTIFICATIONS_RESOURCE_URI), client.reads)
            assertTrue(
                monitor.state.value.notifications
                    .isEmpty(),
            )
            assertEquals(
                "/first",
                monitor.state.value.activeNotifications
                    ?.single()
                    ?.url,
            )
            client.text[GOMODE_NOTIFICATIONS_RESOURCE_URI] =
                """[{"id":"a","title":"Review","body":"Changed","url":"/second"},{"id":"b","title":"Review","body":"New"}]"""
            client.changed(GOMODE_NOTIFICATIONS_RESOURCE_URI)
            runCurrent()
            assertEquals(
                listOf("b"),
                monitor.state.value.notifications
                    .map { it.id },
            )
            assertEquals(
                "/second",
                monitor.state.value.activeNotifications
                    ?.first()
                    ?.url,
            )
            client.changed(GOMODE_NOTIFICATIONS_RESOURCE_URI)
            runCurrent()
            assertTrue(
                monitor.state.value.notifications
                    .isEmpty(),
            )
            client.text[GOMODE_NOTIFICATIONS_RESOURCE_URI] = "[]"
            client.changed(GOMODE_NOTIFICATIONS_RESOURCE_URI)
            runCurrent()
            assertTrue(checkNotNull(monitor.state.value.activeNotifications).isEmpty())
            monitor.stop()
        }

    @Test
    fun `discovery failures retry with bounded backoff and stop retires the retry owner`() =
        runTest {
            val client = Fixture()
            client.listFailure = true
            val monitor =
                ServiceMonitor(
                    backgroundScope,
                    "conversation",
                    ServiceMonitorPurpose.CONVERSATION_CONTEXT,
                ) { _, _ -> client }
            monitor.start("https://service.test", settings())
            runCurrent()
            assertEquals(1, client.lists)
            assertTrue(monitor.state.value.retryReason != null)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(2, client.lists)
            advanceTimeBy(1999)
            runCurrent()
            assertEquals(2, client.lists)
            client.listFailure = false
            advanceTimeBy(1)
            runCurrent()
            assertEquals("known", status(monitor, FIXTURE_CATALOG_URI))
            monitor.stop()
            val calls = client.lists
            advanceTimeBy(60000)
            runCurrent()
            assertEquals(calls, client.lists)
        }

    @Test
    fun `successful alert withdrawal revokes active occurrences`() =
        runTest {
            val client = Fixture()
            val uri = GOMODE_NOTIFICATIONS_RESOURCE_URI
            client.resources += ResourceDescriptor(uri = uri, name = "alerts")
            client.text[uri] = """[{"id":"a","title":"Review","body":"Ready"}]"""
            val monitor = ServiceMonitor(backgroundScope) { _, _ -> client }
            monitor.start("https://service.test", settings())
            runCurrent()
            assertEquals(
                listOf("a"),
                monitor.state.value.activeNotifications
                    ?.map { it.id },
            )
            client.resources = client.resources.filter { it.uri != uri }
            client.listChanged()
            runCurrent()
            assertEquals(emptyList<ServiceNotification>(), monitor.state.value.activeNotifications)
            assertTrue(
                monitor.state.value.notifications
                    .isEmpty(),
            )
            monitor.stop()
        }

    @Test
    fun `delivered alert baseline supersedes old read and preserves queued announcements`() =
        runTest {
            val client = Fixture()
            val uri = GOMODE_NOTIFICATIONS_RESOURCE_URI
            client.resources += ResourceDescriptor(uri = uri, name = "alerts")
            val old = alerts("old")
            val baseline = alerts("base")
            val current = alerts("base", "new")
            client.responses[uri] = ArrayDeque(listOf(old, current))
            client.text[uri] = current
            val gate = CompletableDeferred<Unit>()
            client.gates[uri] = gate
            client.uncancellable += uri
            val monitor = ServiceMonitor(backgroundScope) { _, _ -> client }
            monitor.start("https://service.test", settings())
            runCurrent()
            client.changed(uri)
            runCurrent()
            client.initial(uri, baseline)
            runCurrent()
            assertEquals(
                listOf("base"),
                monitor.state.value.activeNotifications
                    ?.map { it.id },
            )
            client.changed(uri) // Legacy update paired with the initial payload.
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(
                listOf("base", "new"),
                monitor.state.value.activeNotifications
                    ?.map { it.id },
            )
            assertEquals(
                listOf("new"),
                monitor.state.value.notifications
                    .map { it.id },
            )
            client.initial(uri, old) // A duplicate payload must not replace newer state.
            runCurrent()
            assertEquals(
                listOf("base", "new"),
                monitor.state.value.activeNotifications
                    ?.map { it.id },
            )
            assertEquals(2, client.reads.size)
            monitor.stop()
        }

    @Test
    fun `late pre-subscription read cannot overwrite delivered alert baseline`() =
        runTest {
            val client = Fixture()
            val uri = GOMODE_NOTIFICATIONS_RESOURCE_URI
            client.resources += ResourceDescriptor(uri = uri, name = "alerts")
            client.text[uri] = alerts("old")
            val gate = CompletableDeferred<Unit>()
            client.gates[uri] = gate
            client.uncancellable += uri
            val monitor = ServiceMonitor(backgroundScope) { _, _ -> client }
            monitor.start("https://service.test", settings())
            runCurrent()
            client.initial(uri, alerts("base"))
            runCurrent()
            client.changed(uri)
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(
                listOf("base"),
                monitor.state.value.activeNotifications
                    ?.map { it.id },
            )
            assertTrue(
                monitor.state.value.notifications
                    .isEmpty(),
            )
            assertEquals(1, client.reads.size)
            monitor.stop()
        }

    @Test
    fun `model initial payload preserves paired invalidation behind a pending authoritative read`() =
        runTest {
            val client = Fixture()
            val uri = FIXTURE_CONTEXT_URI
            client.responses[uri] = ArrayDeque(listOf("old", "post-subscription"))
            val gate = CompletableDeferred<Unit>()
            client.gates[uri] = gate
            val monitor =
                ServiceMonitor(
                    backgroundScope,
                    "conversation",
                    ServiceMonitorPurpose.CONVERSATION_CONTEXT,
                ) { _, _ -> client }
            monitor.start("https://service.test", settings())
            runCurrent()
            client.initial(uri, "unverified payload")
            runCurrent()
            assertEquals("unread", status(monitor, uri))
            client.changed(uri)
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(2, client.reads.count { it == uri })
            assertTrue(
                monitor.state.value.voiceContext
                    .contains("post-subscription"),
            )
            assertFalse(
                monitor.state.value.voiceContext
                    .contains("unverified payload"),
            )
            monitor.stop()
        }

    @Test
    fun `failed model reads retry independently with backoff and retirement cancels retries`() =
        runTest {
            val client = Fixture()
            client.failures += FIXTURE_CONTEXT_URI
            val monitor =
                ServiceMonitor(
                    backgroundScope,
                    "conversation",
                    ServiceMonitorPurpose.CONVERSATION_CONTEXT,
                ) { _, _ -> client }
            monitor.start("https://service.test", settings())
            runCurrent()
            assertEquals("error", status(monitor, FIXTURE_CONTEXT_URI))
            assertEquals("known", status(monitor, FIXTURE_CATALOG_URI))
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(2, client.reads.count { it == FIXTURE_CONTEXT_URI })
            assertEquals(1, client.reads.count { it == FIXTURE_CATALOG_URI })
            advanceTimeBy(1999)
            runCurrent()
            assertEquals(2, client.reads.count { it == FIXTURE_CONTEXT_URI })
            client.failures.clear()
            advanceTimeBy(1)
            runCurrent()
            assertEquals("known", status(monitor, FIXTURE_CONTEXT_URI))
            client.failures += FIXTURE_CONTEXT_URI
            client.changed(FIXTURE_CONTEXT_URI)
            runCurrent()
            client.resources = client.resources.filter { it.uri != FIXTURE_CONTEXT_URI }
            client.listChanged()
            runCurrent()
            val reads = client.reads.size
            advanceTimeBy(60000)
            runCurrent()
            assertEquals(reads, client.reads.size)
            assertEquals("absent", status(monitor, FIXTURE_CONTEXT_URI))
            client.resources += model(FIXTURE_CONTEXT_URI)
            client.listChanged()
            runCurrent()
            assertEquals("error", status(monitor, FIXTURE_CONTEXT_URI))
            monitor.stop()
            runCurrent()
            val stoppedReads = client.reads.size
            advanceTimeBy(60000)
            runCurrent()
            assertEquals(stoppedReads, client.reads.size)
        }

    private class Fixture : ServiceResourceClient {
        var resources =
            listOf(
                model(FIXTURE_CONTEXT_URI),
                model(FIXTURE_CATALOG_URI),
                ResourceDescriptor(uri = "fixture://on-demand", name = "unmarked"),
            )
        val text = mutableMapOf(FIXTURE_CONTEXT_URI to "資料 東京", FIXTURE_CATALOG_URI to "Catalogue")
        val reads = mutableListOf<String>()
        val filters = mutableListOf<SubscriptionFilter>()
        val failures = mutableSetOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val cancelledReads = mutableSetOf<String>()
        val uncancellable = mutableSetOf<String>()
        val events = MutableSharedFlow<JSONRPCNotification>(extraBufferCapacity = 16)
        var completedStreams = 0
        var lists = 0
        var listFailure = false
        var epoch = "private-epoch"
        var version = 1L

        override suspend fun serverDiscover(sessionID: String?) =
            ServerDiscoverResult(
                resultType = ResultType.Complete,
                ttlMs = 0,
                cacheScope = CacheScope.Private,
                supportedVersions = listOf("2026-07-28"),
                capabilities =
                    Capabilities(
                        resources = ResourcesCapability(subscribe = true, listChanged = true),
                        tools =
                            com.fghbuild.mcp.sdk.v1
                                .ToolsCapability(),
                    ),
                serverInfo = Implementation(name = "fixture", version = "1"),
            )

        override suspend fun listResources(sessionID: String?): List<ResourceDescriptor> {
            lists++
            if (listFailure) throw IOException("offline")
            return resources
        }

        val responses = mutableMapOf<String, ArrayDeque<String>>()

        override suspend fun readResource(
            uri: String,
            sessionID: String?,
        ): ServiceResourceRead {
            reads += uri
            val response = responses[uri]?.removeFirstOrNull()
            try {
                gates[uri]?.await()
            } catch (
                error: kotlinx.coroutines.CancellationException,
            ) {
                cancelledReads += uri
                if (uri !in uncancellable) throw error
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gates[uri]?.await() }
            }
            if (uri in failures) throw IOException("advisory")
            return ServiceResourceRead(
                uri,
                ResourcesReadResult(
                    resultType = ResultType.Complete,
                    contents =
                        listOf(
                            ResourceContent(
                                uri = uri,
                                text =
                                    response ?: checkNotNull(text[uri]),
                            ),
                        ),
                    ttlMs = 0,
                    cacheScope = CacheScope.Private,
                    _meta =
                        if (uri == FIXTURE_CONTEXT_URI) {
                            mapOf(
                                "com.fghbuild.gomode/context" to
                                    buildJsonObject {
                                        put("uri", uri)
                                        put("epoch", epoch)
                                        put("version", version)
                                        put("dependencies", kotlinx.serialization.json.JsonArray(emptyList()))
                                    },
                            )
                        } else {
                            null
                        },
                ),
            )
        }

        override fun listenSubscriptions(
            notifications: SubscriptionFilter,
            sessionID: String?,
        ): Flow<JSONRPCNotification> {
            filters += notifications
            return events.onCompletion { completedStreams++ }
        }

        fun changed(uri: String) {
            check(
                events
                    .tryEmit(
                        JSONRPCNotification(
                            jsonrpc = "2.0",
                            method = NotificationMethod.ResourcesUpdated,
                            params =
                                JSONPresence
                                    .Value(
                                        buildJsonObject {
                                            put("uri", uri)
                                        },
                                    ),
                        ),
                    ),
            )
        }

        fun initial(
            uri: String,
            text: String,
        ) {
            check(
                events.tryEmit(
                    JSONRPCNotification(
                        jsonrpc = "2.0",
                        method = NotificationMethod.SubscriptionsInitialState,
                        params =
                            JSONPresence.Value(
                                buildJsonObject {
                                    put("uri", uri)
                                    put(
                                        "contents",
                                        kotlinx.serialization.json.buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("uri", uri)
                                                    put("text", text)
                                                },
                                            )
                                        },
                                    )
                                },
                            ),
                    ),
                ),
            )
        }

        fun listChanged() {
            check(
                events.tryEmit(JSONRPCNotification(jsonrpc = "2.0", method = NotificationMethod.ResourcesListChanged)),
            )
        }
    }
}

private fun model(uri: String) =
    ResourceDescriptor(
        uri = uri,
        name = uri,
        _meta =
            mapOf(MODEL_CONTEXT_META_KEY to JsonPrimitive(true)),
    )

private fun settings() =
    Settings(
        apiVersion = 1,
        service = "fixture",
        webShell =
            WebShellSettings(
                bridgeVersion = 3,
                voiceGateway =
                    com.fghbuild.gomode.sdk.v1
                        .VoiceGatewaySettings(required = false),
                toolGroups =
                    listOf(
                        ToolGroup(
                            name = "fixture",
                            endpoint = "/mcp",
                            protocolVersion = "2026-07-28",
                            authRequired = false,
                        ),
                    ),
            ),
    )

// Read outcome the monitor tracks for one resource; model text carries no status.
private fun status(
    monitor: ServiceMonitor,
    uri: String,
): String {
    val state = monitor.state.value
    if (state.retryReason != null) return "error"
    val resources = state.snapshot?.resources ?: return "unread"
    check(uri in resources) { "$uri is not monitored" }
    val read = resources[uri] ?: return "unread"
    return when {
        read.isFailure -> "error"
        read.getOrNull() == null -> "absent"
        else -> "known"
    }
}

private fun alerts(vararg ids: String): String =
    kotlinx.serialization.json
        .buildJsonArray {
            ids.forEach { id ->
                add(
                    buildJsonObject {
                        put("id", id)
                        put("title", id)
                        put("body", id)
                    },
                )
            }
        }.toString()
