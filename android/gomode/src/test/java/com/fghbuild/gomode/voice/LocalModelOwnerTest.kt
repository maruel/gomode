// Tests startup prefill reuse, invalidation, retry, and drained single-owner native replacement.
package com.fghbuild.gomode.voice

import android.app.Application
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
                LocalModelOwner(app, localModelInstallation(ModelDownloadState.Ready), dispatcher) {
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
    fun `changing Gemma variant drains previous weights before allocating selected variant`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val events = mutableListOf<String>()
            val owner =
                LocalModelOwner(app, localModelInstallation(ModelDownloadState.Ready), dispatcher) { model ->
                    events.add("allocate ${model.label}")
                    LocalLlmEngine(app, { _, _ ->
                        object : Runner() {
                            override fun close() {
                                events.add("close ${model.label}")
                            }
                        }
                    }, dispatcher, model)
                }
            owner.select(LocalModelSelection("en-US", { setup() }, { true }))
            val first = async { owner.acquire() }
            testScheduler.runCurrent()
            owner.select(LocalModelSelection("en-US", { setup() }, { true }, LocalLlmModel.GEMMA_4_E4B))
            val second = async { owner.acquire() }
            testScheduler.runCurrent()
            assertFalse(first.await().engine.isModelLoaded)
            assertEquals("Gemma 4 E4B", second.await().engine.modelName)
            assertEquals(listOf("allocate Gemma 4 E2B", "close Gemma 4 E2B", "allocate Gemma 4 E4B"), events)
            owner.release(second.await())
            testScheduler.runCurrent()
            assertEquals(3, events.size)
            owner.select(LocalModelSelection("en-US", { setup() }, { true }, LocalLlmModel.LFM_3_1_7B))
            val third = async { owner.acquire() }
            testScheduler.runCurrent()
            assertFalse(second.await().engine.isModelLoaded)
            assertEquals("LFM3 1.7B · 64K", third.await().engine.modelName)
            assertEquals(
                listOf(
                    "allocate Gemma 4 E2B",
                    "close Gemma 4 E2B",
                    "allocate Gemma 4 E4B",
                    "close Gemma 4 E4B",
                    "allocate LFM3 1.7B · 64K",
                ),
                events,
            )
            owner.release(third.await())
            testScheduler.runCurrent()
            assertEquals(5, events.size)
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `active installation waits before service discovery and prepares automatically when installed`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val installation =
                MutableStateFlow<Map<LocalLlmModel, ModelDownloadState>>(
                    mapOf(LocalLlmModel.GEMMA_4_E2B to ModelDownloadState.Queued(1_000_000, false)),
                )
            var allocations = 0
            var discoveries = 0
            val owner =
                LocalModelOwner(app, installation, dispatcher) {
                    LocalLlmEngine(app, { _, _ ->
                        allocations++
                        Runner()
                    }, dispatcher)
                }
            owner.select(
                LocalModelSelection("en-US", {
                    discoveries++
                    setup()
                }, { true }),
            )
            testScheduler.runCurrent()
            assertEquals(LocalModelState.AwaitingDownload, owner.state.value)
            assertEquals(0, allocations)
            assertEquals(0, discoveries)
            installation.value = mapOf(LocalLlmModel.GEMMA_4_E2B to ModelDownloadState.Ready)
            owner.modelInstalled(LocalLlmModel.GEMMA_4_E2B)
            val prepared = async { owner.acquire() }
            testScheduler.runCurrent()
            assertTrue(prepared.await().engine.isModelLoaded)
            assertEquals(1, allocations)
            assertEquals(1, discoveries)
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `missing model waits for installation without a preparation failure or repeated allocation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var attempts = 0
            var installed = false
            val owner =
                LocalModelOwner(app, localModelInstallation(ModelDownloadState.Ready), dispatcher) {
                    LocalLlmEngine(app, { _, _ ->
                        attempts++
                        if (!installed) throw LocalModelUnavailableException(LocalLlmModel.GEMMA_4_E2B)
                        Runner()
                    }, dispatcher)
                }
            owner.select(LocalModelSelection("en-US", { setup() }, { true }))
            testScheduler.runCurrent()
            assertEquals(LocalModelState.AwaitingDownload, owner.state.value)
            assertTrue(runCatching { owner.acquire() }.exceptionOrNull() is LocalModelUnavailableException)
            owner.retry()
            testScheduler.runCurrent()
            assertEquals(1, attempts)
            owner.modelInstalled(LocalLlmModel.GEMMA_4_E4B)
            testScheduler.runCurrent()
            assertEquals(LocalModelState.AwaitingDownload, owner.state.value)
            installed = true
            owner.modelInstalled(LocalLlmModel.GEMMA_4_E2B)
            val prepared = async { owner.acquire() }
            testScheduler.runCurrent()
            assertTrue(prepared.await().engine.isModelLoaded)
            assertEquals(LocalModelState.Ready, owner.state.value)
            assertEquals(2, attempts)
            owner.close()
            testScheduler.runCurrent()
        }

    @Test
    fun `preparation failure is visible and repeated retries allocate only once`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var attempts = 0
            var fail = true
            val owner =
                LocalModelOwner(app, localModelInstallation(ModelDownloadState.Ready), dispatcher) {
                    LocalLlmEngine(app, { _, _ ->
                        attempts++
                        check(!fail) { "Download the model in Settings" }
                        Runner()
                    }, dispatcher)
                }
            owner.select(LocalModelSelection("en-US", { setup() }, { true }))
            testScheduler.runCurrent()
            assertTrue((owner.state.value as LocalModelState.Failed).message.contains("in Settings"))
            assertEquals(1, attempts)
            fail = false
            owner.modelInstalled(LocalLlmModel.GEMMA_4_E4B)
            testScheduler.runCurrent()
            assertEquals(1, attempts)
            owner.modelInstalled(LocalLlmModel.GEMMA_4_E2B)
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
                LocalModelOwner(app, localModelInstallation(ModelDownloadState.Ready), dispatcher) {
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
                LocalModelOwner(app, localModelInstallation(ModelDownloadState.Ready), dispatcher) {
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
                LocalModelOwner(app, localModelInstallation(ModelDownloadState.Ready), dispatcher) {
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
                LocalModelSelection("fr-CA", { setup("New service and account") }, { true }),
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
                LocalModelOwner(app, localModelInstallation(ModelDownloadState.Ready), dispatcher) {
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
                LocalModelOwner(app, localModelInstallation(ModelDownloadState.Ready), dispatcher) {
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
                LocalModelOwner(app, localModelInstallation(ModelDownloadState.Ready), dispatcher) {
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
                LocalModelOwner(app, localModelInstallation(ModelDownloadState.Ready), dispatcher) {
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
    fun `old activity disposal cannot clear a newer activity selection`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var allocations = 0
            val owner =
                LocalModelOwner(app, localModelInstallation(ModelDownloadState.Ready), dispatcher) {
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

    private fun setup(systemInstruction: String = "Use the selected service") =
        LocalConversationPreparation(
            VoiceSetup(
                serverName = "Fixture",
                serverID = null,
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
                systemInstruction = systemInstruction,
                serviceContextText = "current item: alpha",
            ),
            VoiceConversationLifetime(),
        )

    private open class Runner : EdgeLlmRunner {
        override val isModelLoaded = true
        override val backendName = "test"

        override suspend fun generateStream(message: Message): Flow<Message> = flowOf(Message.model("Done"))

        override fun replaceConversation(config: ConversationConfig) = Unit

        override fun close() = Unit
    }
}
