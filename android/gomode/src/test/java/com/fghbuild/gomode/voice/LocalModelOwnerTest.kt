// Tests startup prefill reuse, invalidation, retry, and drained single-owner native replacement.
package com.fghbuild.gomode.voice

import android.app.Application
import com.fghbuild.gomode.service.readAuthoritativeServiceContext
import com.fghbuild.gomode.service.readInitialServiceContext
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LocalModelOwnerTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `startup prefills real instructions schemas and context once without starting audio`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var allocations = 0
            var discoveries = 0
            var prefill = ""
            val owner =
                LocalModelOwner(app, dispatcher, readServiceContext = { setup().serviceContextText }) {
                    LocalLlmEngine(app, { _, instruction ->
                        allocations++
                        prefill = instruction.prefillEvidence()
                        Runner()
                    }, dispatcher)
                }
            val selected =
                LocalModelSelection("fr-CA", {
                    discoveries++
                    setup()
                }, { true })
            owner.select(selected)
            owner.select(selected)
            val first = async { owner.acquire() }
            val second = async { owner.acquire() }
            testScheduler.runCurrent()
            assertSame(first.await().engine, second.await().engine)
            assertEquals(1, discoveries)
            assertEquals(1, allocations)
            assertTrue(prefill.contains("Use the selected service"))
            assertTrue(prefill.contains("current item: alpha"))
            assertTrue(prefill.contains("\"required\":[\"item\"]"))
            assertTrue(prefill.contains("fr-CA"))
            assertNull(shadowOf(app).nextStartedService)
            assertNull(shadowOf(app).nextStartedActivity)
            owner.close()
            testScheduler.runCurrent()
            assertFalse(first.await().engine.isModelLoaded)
        }

    @Test
    fun `preparation failure is visible and repeated retries allocate only once`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var attempts = 0
            var fail = true
            val owner =
                LocalModelOwner(app, dispatcher, readServiceContext = { setup().serviceContextText }) {
                    LocalLlmEngine(app, { _, _ ->
                        attempts++
                        check(!fail) { "Install weights with make android-push-model" }
                        Runner()
                    }, dispatcher)
                }
            owner.select(LocalModelSelection("en-US", { setup() }, { true }))
            testScheduler.runCurrent()
            assertTrue((owner.state.value as LocalModelState.Failed).message.contains("make android-push-model"))
            assertEquals(1, attempts)
            fail = false
            owner.retry()
            owner.retry()
            val prepared = async { owner.acquire() }
            testScheduler.runCurrent()
            assertTrue(prepared.await().engine.isModelLoaded)
            assertEquals(2, attempts)
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `native linkage failure is actionable rather than leaving startup preparing forever`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var brokenRuntime = true
            val owner =
                LocalModelOwner(app, dispatcher, readServiceContext = { setup().serviceContextText }) {
                    LocalLlmEngine(app, { _, _ ->
                        if (brokenRuntime) throw UnsatisfiedLinkError("JNI runtime unavailable")
                        Runner()
                    }, dispatcher)
                }
            owner.select(LocalModelSelection("en-US", { setup() }, { true }))
            testScheduler.runCurrent()
            assertTrue((owner.state.value as LocalModelState.Failed).message.contains("Reinstall Go Mode"))
            brokenRuntime = false
            owner.retry()
            testScheduler.runCurrent()
            assertEquals(LocalModelState.Ready, owner.state.value)
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `late service discovery cannot allocate or publish after auth invalidation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val finishDiscovery = CompletableDeferred<Unit>()
            var allocations = 0
            var credentialsReady = true
            val owner =
                LocalModelOwner(app, dispatcher, readServiceContext = { setup().serviceContextText }) {
                    allocations++
                    LocalLlmEngine(app, { _, _ -> Runner() }, dispatcher)
                }
            owner.select(
                LocalModelSelection("en-US", {
                    finishDiscovery.await()
                    setup()
                }, { credentialsReady }),
            )
            val waiting = async { runCatching { owner.acquire() } }
            testScheduler.runCurrent()
            credentialsReady = false
            owner.select(null)
            finishDiscovery.complete(Unit)
            testScheduler.runCurrent()
            assertTrue(waiting.await().isFailure)
            assertEquals(0, allocations)
            assertEquals(LocalModelState.Idle, owner.state.value)
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `service auth language and model deselection drain native turn before replacement`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val finishTurn = CompletableDeferred<Unit>()
            val startedTurn = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            var number = 0
            val owner =
                LocalModelOwner(app, dispatcher, readServiceContext = { setup().serviceContextText }) {
                    val id = ++number
                    events.add("allocate $id")
                    LocalLlmEngine(app, { _, _ ->
                        object : Runner() {
                            override suspend fun generateStream(message: Message): Flow<Message> =
                                flow {
                                    startedTurn.complete(Unit)
                                    finishTurn.await()
                                    events.add("native done")
                                    emit(Message.model("Done"))
                                }

                            override fun close() {
                                events.add("close $id")
                            }
                        }
                    }, dispatcher)
                }
            owner.select(LocalModelSelection("en-US", { setup() }, { true }))
            val first = async { owner.acquire() }
            testScheduler.runCurrent()
            val original = first.await()
            val turn = launch { original.engine.processTurn(UserTurnInput("hello"), {}, { null }) }
            testScheduler.runCurrent()
            assertTrue(startedTurn.isCompleted)
            owner.release(original)
            owner.select(
                LocalModelSelection("fr-CA", { setup().copy(systemInstruction = "New service and account") }, { true }),
            )
            val replacement = async { owner.acquire() }
            testScheduler.runCurrent()
            assertEquals(listOf("allocate 1"), events)
            assertFalse(turn.isCancelled)
            assertFalse(replacement.isCompleted)
            finishTurn.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(listOf("allocate 1", "native done", "close 1", "allocate 2"), events)
            assertNotSame(original.engine, replacement.await().engine)
            owner.release(original) // Late release must not tear down the replacement.
            testScheduler.runCurrent()
            assertEquals(2, number)
            owner.select(null) // Cloud/remote/other engine selection.
            testScheduler.runCurrent()
            assertEquals("close 2", events.last())
            assertEquals(LocalModelState.Idle, owner.state.value)
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `owner close waits for native turn and never allocates queued selection`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val finishTurn = CompletableDeferred<Unit>()
            var allocations = 0
            var disposed = false
            val owner =
                LocalModelOwner(app, dispatcher, readServiceContext = { setup().serviceContextText }) {
                    allocations++
                    LocalLlmEngine(app, { _, _ ->
                        object : Runner() {
                            override suspend fun generateStream(message: Message): Flow<Message> =
                                flow {
                                    finishTurn.await()
                                    emit(Message.model("Done"))
                                }

                            override fun close() {
                                disposed = true
                            }
                        }
                    }, dispatcher)
                }
            owner.select(LocalModelSelection("en-US", { setup() }, { true }))
            val prepared = async { owner.acquire() }
            testScheduler.runCurrent()
            val engine = prepared.await().engine
            val turn = launch { engine.processTurn(UserTurnInput("hello"), {}, { null }) }
            testScheduler.runCurrent()
            owner.release(prepared.await())
            owner.close()
            owner.select(LocalModelSelection("fr-CA", { setup() }, { true }))
            testScheduler.runCurrent()
            assertFalse(disposed)
            assertFalse(turn.isCancelled)
            finishTurn.complete(Unit)
            testScheduler.runCurrent()
            assertTrue(disposed)
            assertEquals(1, allocations)
            assertEquals(LocalModelState.Idle, owner.state.value)
        }

    @Test
    fun `late native initialization is disposed before a newer selection can allocate`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val events = mutableListOf<String>()
            var number = 0
            var ownerRef: LocalModelOwner? = null
            val owner =
                LocalModelOwner(app, dispatcher, readServiceContext = { setup().serviceContextText }) {
                    val id = ++number
                    LocalLlmEngine(app, { _, _ ->
                        events.add("initialize $id")
                        if (id == 1) {
                            ownerRef?.select(LocalModelSelection("fr-CA", { setup() }, { true }))
                        }
                        object : Runner() {
                            override fun close() {
                                events.add("close $id")
                            }
                        }
                    }, dispatcher)
                }
            ownerRef = owner
            owner.select(LocalModelSelection("en-US", { setup() }, { true }))
            testScheduler.runCurrent()
            assertEquals(listOf("initialize 1", "close 1", "initialize 2"), events)
            assertEquals(LocalModelState.Ready, owner.state.value)
            owner.close()
            testScheduler.runCurrent()
            assertEquals("close 2", events.last())
        }

    @Test
    fun `disposal failure prevents replacement allocation and is actionable`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var allocations = 0
            val owner =
                LocalModelOwner(app, dispatcher, readServiceContext = { setup().serviceContextText }) {
                    allocations++
                    LocalLlmEngine(app, { _, _ ->
                        object : Runner() {
                            override fun close() {
                                error("Native deletion failed")
                            }
                        }
                    }, dispatcher)
                }
            owner.select(LocalModelSelection("en-US", { setup() }, { true }))
            val first = async { owner.acquire() }
            testScheduler.runCurrent()
            first.await()
            owner.select(LocalModelSelection("fr-CA", { setup() }, { true }))
            testScheduler.runCurrent()
            assertTrue((owner.state.value as LocalModelState.Failed).message.contains("Native deletion failed"))
            owner.retry()
            testScheduler.runCurrent()
            assertEquals(1, allocations)
            assertTrue(owner.state.value is LocalModelState.Failed)
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `service setup failure never falls back to fabricated instructions or allocates a model`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var allocations = 0
            val owner =
                LocalModelOwner(app, dispatcher, readServiceContext = { setup().serviceContextText }) {
                    allocations++
                    LocalLlmEngine(app, { _, _ -> Runner() }, dispatcher)
                }
            owner.select(
                LocalModelSelection(
                    "en-US",
                    { throw VoiceSetupException("Sign in to the selected service") },
                    { true },
                ),
            )
            testScheduler.runCurrent()
            assertEquals(0, allocations)
            assertTrue((owner.state.value as LocalModelState.Failed).message.contains("Sign in"))
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `idle facts before first acquisition and delayed reconnect reach native prefill without reloading weights`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val server = MockWebServer()
            server.start()
            val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null })
            val prefills = mutableListOf<String>()
            var allocations = 0
            val owner =
                LocalModelOwner(app, dispatcher) {
                    LocalLlmEngine(app, { _, instruction ->
                        allocations++
                        prefills.add(instruction.prefillEvidence())
                        object : Runner() {
                            override fun replaceConversation(config: ConversationConfig) {
                                prefills.add(config.prefillEvidence())
                            }
                        }
                    }, dispatcher)
                }
            try {
                enqueueFacts(server, "alpha")
                owner.select(
                    LocalModelSelection("fr-CA", {
                        setup().copy(mcpClient = client, serviceContextText = readAuthoritativeServiceContext(client))
                    }, { true }),
                )
                owner.state.first { it == LocalModelState.Ready }
                assertTrue(prefills.single().contains("alpha"))
                enqueueFacts(server, "beta") // The service changes while voice is still idle.
                val first = owner.acquire()
                assertTrue(first.setup.serviceContextText.contains("beta"))
                assertFalse(prefills.last().contains("alpha"))
                assertTrue(prefills.last().contains(first.setup.serviceContextText))
                assertEquals(2, prefills.size)
                assertEquals(1, allocations)

                enqueueFacts(server, "beta")
                owner.release(first)
                owner.state.first { it == LocalModelState.Ready }
                assertEquals(3, prefills.size) // Fresh conversation, same engine/weights.
                assertEquals(1, allocations)
                enqueueFacts(server, "gamma") // Reconnect is delayed after its idle prefill.
                val reconnect = owner.acquire()
                assertSame(first.engine, reconnect.engine)
                assertTrue(reconnect.setup.serviceContextText.contains("gamma"))
                assertTrue(prefills.last().contains(reconnect.setup.serviceContextText))
                assertFalse(prefills.last().contains("beta"))
                assertEquals(4, prefills.size)
                assertEquals(1, allocations)

                enqueueFacts(server, "gamma")
                owner.acquire()
                assertEquals("Unchanged facts must preserve native prefill", 4, prefills.size)
                assertEquals(1, allocations)
                for (instruction in prefills) {
                    assertTrue(instruction.contains("Use the selected service"))
                    assertTrue(instruction.contains("\"required\":[\"item\"]"))
                    assertTrue(instruction.contains("fr-CA"))
                }
            } finally {
                owner.close()
                testScheduler.runCurrent()
                server.shutdown()
            }
        }

    @Test
    fun `failed authoritative acquisition cannot fabricate empty facts or replace native prefill`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val server = MockWebServer()
            server.start()
            val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null })
            var replacements = 0
            val owner =
                LocalModelOwner(app, dispatcher) {
                    LocalLlmEngine(app, { _, _ ->
                        object : Runner() {
                            override fun replaceConversation(config: ConversationConfig) {
                                replacements++
                            }
                        }
                    }, dispatcher)
                }
            try {
                owner.select(LocalModelSelection("en-US", { setup().copy(mcpClient = client) }, { true }))
                owner.state.first { it == LocalModelState.Ready }
                server.enqueue(MockResponse().setResponseCode(503))
                val failure = runCatching { owner.acquire() }
                assertTrue(failure.isFailure)
                assertTrue(owner.state.value is LocalModelState.Failed)
                assertEquals(0, replacements)
            } finally {
                owner.close()
                testScheduler.runCurrent()
                server.shutdown()
            }
        }

    @Test
    fun `old activity disposal cannot clear a newer activity selection`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var allocations = 0
            val owner =
                LocalModelOwner(app, dispatcher, readServiceContext = { setup().serviceContextText }) {
                    allocations++
                    LocalLlmEngine(app, { _, _ -> Runner() }, dispatcher)
                }
            val oldSelection = LocalModelSelection("en-US", { setup() }, { true })
            val newSelection = LocalModelSelection("fr-CA", { setup() }, { true })
            owner.select(oldSelection)
            testScheduler.runCurrent()
            owner.select(newSelection) // Standard-mode MainActivity can start before its predecessor is destroyed.
            owner.clearSelection(oldSelection) // The older Compose DisposableEffect runs late.
            owner.clearSelection(null) // An older cloud-mode shell must not affect the new local selection either.
            val prepared = async { owner.acquire() }
            testScheduler.runCurrent()
            assertTrue(prepared.await().engine.isModelLoaded)
            assertEquals(LocalModelState.Ready, owner.state.value)
            assertEquals(2, allocations)
            owner.clearSelection(newSelection)
            testScheduler.runCurrent()
            assertEquals(LocalModelState.Idle, owner.state.value)
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `late acquisition refresh after invalidation cannot reach native conversation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val finishRead = CompletableDeferred<Unit>()
            var resets = 0
            var current = true
            val owner =
                LocalModelOwner(app, dispatcher, readServiceContext = {
                    finishRead.await()
                    "changed facts"
                }) {
                    LocalLlmEngine(app, { _, _ ->
                        object : Runner() {
                            override fun replaceConversation(config: ConversationConfig) {
                                resets++
                            }
                        }
                    }, dispatcher)
                }
            val selection = LocalModelSelection("en-US", { setup() }, { current })
            owner.select(selection)
            val acquisition = async { runCatching { owner.acquire() } }
            testScheduler.runCurrent()
            current = false
            owner.clearSelection(selection)
            finishRead.complete(Unit)
            testScheduler.runCurrent()
            assertTrue(acquisition.await().isFailure)
            assertEquals(0, resets)
            assertEquals(LocalModelState.Idle, owner.state.value)
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `invalid advertised items MIME fails startup before model allocation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val server = MockWebServer()
            server.start()
            val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null })
            var allocations = 0
            val owner =
                LocalModelOwner(app, dispatcher) {
                    allocations++
                    LocalLlmEngine(app, { _, _ -> Runner() }, dispatcher)
                }
            try {
                enqueueResourceList(
                    server,
                    """[
                    {"uri":"gomode://items","name":"items","mimeType":"text/plain"}
                ]""",
                )
                owner.select(
                    LocalModelSelection("en-US", {
                        setup().copy(mcpClient = client, serviceContextText = readAuthoritativeServiceContext(client))
                    }, { true }),
                )
                val result = owner.state.first { it is LocalModelState.Failed || it == LocalModelState.Ready }
                assertTrue(result is LocalModelState.Failed)
                val message = (result as LocalModelState.Failed).message
                assertTrue(message.contains("gomode://items"))
                assertTrue(message.contains("application/json"))
                assertTrue(message.contains("text/plain"))
                assertTrue(message.contains("Update the service resource metadata"))
                assertEquals(0, allocations)
                assertEquals(1, server.requestCount)
            } finally {
                owner.close()
                testScheduler.runCurrent()
                server.shutdown()
            }
        }

    @Test
    fun `invalid advertised items MIME fails acquisition without replacing valid native facts`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val server = MockWebServer()
            server.start()
            val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null })
            var allocations = 0
            val prefills = mutableListOf<String>()
            val owner =
                LocalModelOwner(app, dispatcher) {
                    LocalLlmEngine(app, { _, instruction ->
                        allocations++
                        prefills.add(instruction.prefillEvidence())
                        object : Runner() {
                            override fun replaceConversation(config: ConversationConfig) {
                                prefills.add(config.prefillEvidence())
                            }
                        }
                    }, dispatcher)
                }
            try {
                enqueueFacts(server, "alpha")
                owner.select(
                    LocalModelSelection("en-US", {
                        setup().copy(mcpClient = client, serviceContextText = readAuthoritativeServiceContext(client))
                    }, { true }),
                )
                owner.state.first { it == LocalModelState.Ready }
                enqueueResourceList(
                    server,
                    """[
                    {"uri":"gomode://items","name":"items","mimeType":"text/plain"}
                ]""",
                )
                val failure = runCatching { owner.acquire() }
                assertTrue(failure.isFailure)
                assertTrue((owner.state.value as LocalModelState.Failed).message.contains("application/json"))
                assertTrue(prefills.single().contains("alpha"))
                assertFalse(prefills.single().contains("No visible service items"))
                assertEquals(1, allocations)
                assertEquals(3, server.requestCount)
            } finally {
                owner.close()
                testScheduler.runCurrent()
                server.shutdown()
            }
        }

    @Test
    fun `incompatible notifications cannot hide valid items from startup or acquisition native prefill`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val server = MockWebServer()
            server.start()
            val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null })
            var allocations = 0
            val prefills = mutableListOf<String>()
            val owner =
                LocalModelOwner(app, dispatcher) {
                    LocalLlmEngine(app, { _, instruction ->
                        allocations++
                        prefills.add(instruction.prefillEvidence())
                        object : Runner() {
                            override fun replaceConversation(config: ConversationConfig) {
                                prefills.add(config.prefillEvidence())
                            }
                        }
                    }, dispatcher)
                }
            try {
                enqueueFacts(server, "alpha", notificationsMimeType = "text/plain")
                owner.select(
                    LocalModelSelection("fr-CA", {
                        setup().copy(mcpClient = client, serviceContextText = readAuthoritativeServiceContext(client))
                    }, { true }),
                )
                owner.state.first { it == LocalModelState.Ready }
                assertTrue(prefills.single().contains("alpha"))
                enqueueFacts(server, "beta", notificationsMimeType = "text/plain")
                val prepared = owner.acquire()
                assertTrue(prepared.setup.serviceContextText.contains("beta"))
                assertTrue(prefills.last().contains(prepared.setup.serviceContextText))
                assertFalse(prefills.last().contains("alpha"))
                assertEquals(2, prefills.size)
                assertEquals(1, allocations)
                assertEquals(4, server.requestCount)
                for (instruction in prefills) {
                    assertTrue(instruction.contains("Use the selected service"))
                    assertTrue(instruction.contains("\"required\":[\"item\"]"))
                    assertTrue(instruction.contains("fr-CA"))
                }
                for (method in listOf("resources/list", "resources/read", "resources/list", "resources/read")) {
                    val request = server.takeRequest()
                    assertEquals(method, request.getHeader("Mcp-Method"))
                    if (method == "resources/read") assertEquals("gomode://items", request.getHeader("Mcp-Name"))
                }
                // Other transports retain their advisory monitoring-compatible fallback.
                enqueueResourceList(
                    server,
                    """[
                    {"uri":"gomode://items","name":"items","mimeType":"application/json"},
                    {"uri":"gomode://notifications","name":"notifications","mimeType":"text/plain"}
                ]""",
                )
                assertEquals("No visible service items.", readInitialServiceContext(client))
                assertEquals(5, server.requestCount)
            } finally {
                owner.close()
                testScheduler.runCurrent()
                server.shutdown()
            }
        }

    @Test
    fun `truly absent items remain absent through startup and acquisition`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val server = MockWebServer()
            server.start()
            val client = McpClient(server.url("/mcp").toString(), "2026-07-28", { null })
            val prefills = mutableListOf<String>()
            val owner =
                LocalModelOwner(app, dispatcher) {
                    LocalLlmEngine(app, { _, instruction ->
                        prefills.add(instruction.prefillEvidence())
                        object : Runner() {
                            override fun replaceConversation(config: ConversationConfig) {
                                prefills.add(config.prefillEvidence())
                            }
                        }
                    }, dispatcher)
                }
            try {
                enqueueResourceList(server, "[]")
                owner.select(
                    LocalModelSelection("en-US", {
                        setup().copy(mcpClient = client, serviceContextText = readAuthoritativeServiceContext(client))
                    }, { true }),
                )
                owner.state.first { it == LocalModelState.Ready }
                enqueueResourceList(server, "[]")
                val prepared = owner.acquire()
                assertEquals("No visible service items.", prepared.setup.serviceContextText)
                assertTrue(prefills.single().contains(prepared.setup.serviceContextText))
                assertEquals(2, server.requestCount)
            } finally {
                owner.close()
                testScheduler.runCurrent()
                server.shutdown()
            }
        }

    private fun enqueueResourceList(
        server: MockWebServer,
        resources: String,
    ) {
        server.enqueue(
            MockResponse().setBody(
                """{
            "jsonrpc":"2.0","id":1,"result":{
                "resultType":"complete","ttlMs":0,"cacheScope":"private","resources":$resources
            }
        }""",
            ),
        )
    }

    private fun enqueueFacts(
        server: MockWebServer,
        title: String,
        notificationsMimeType: String? = null,
    ) {
        val notifications =
            notificationsMimeType
                ?.let {
                    """,{"uri":"gomode://notifications","name":"notifications","mimeType":${JsonPrimitive(it)}}"""
                }.orEmpty()
        enqueueResourceList(
            server,
            """[{"uri":"gomode://items","name":"items","mimeType":"application/json"}$notifications]""",
        )
        val text = JsonPrimitive("""{"items":[{"id":"item-1","title":"$title","state":"ready"}]}""")
        server.enqueue(
            MockResponse().setBody(
                """{
            "jsonrpc":"2.0","id":2,"result":{
                "resultType":"complete","ttlMs":0,"cacheScope":"private",
                "contents":[{"uri":"gomode://items","mimeType":"application/json","text":$text}]
            }
        }""",
            ),
        )
    }

    private fun setup() =
        VoiceSetup(
            gatewayEndpointURL = "https://service.example",
            service = null,
            tokenEndpointURL = null,
            gatewayHeaders = emptyMap(),
            mcpClient = McpClient("https://service.example/mcp", "2024-11-05", { null }),
            tools =
                listOf(
                    ToolDescriptor(
                        name = "choose",
                        inputSchema =
                            Json.parseToJsonElement(
                                """{"type":"object","properties":{"item":{"type":"string"}},"required":["item"]}""",
                            ),
                    ),
                ),
            systemInstruction = "Use the selected service",
            serviceContextText = "current item: alpha",
        )

    private open class Runner : EdgeLlmRunner {
        override val isModelLoaded = true

        override suspend fun generateStream(message: Message): Flow<Message> = flowOf(Message.model("Done"))

        override fun replaceConversation(config: ConversationConfig) = Unit

        override fun close() = Unit
    }
}
