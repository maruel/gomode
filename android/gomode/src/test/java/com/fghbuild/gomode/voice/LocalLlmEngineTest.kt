// Unit tests for LocalLlmEngine running compact sub-300M parameter models on Google Pixel 8 Pro.
package com.fghbuild.gomode.voice

import com.caic.voicegateway.sdk.v1.ToolCall
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalLlmEngineTest {
    private val sampleTools =
        listOf(
            ToolDescriptor(
                name = "switch_service",
                description = "Switch active service",
                inputSchema = JsonObject(emptyMap()),
            ),
        )

    private val sessionContext =
        EngineSessionContext(
            languageTag = "en-US",
            systemInstruction = "You are a terse assistant.",
            serviceContextText = "",
            tools = sampleTools,
            mcpClient = null,
        )

    @Test
    fun `descriptor reports Gemma 4 model size and latency budget for Pixel 8 Pro`() {
        val engine = LocalLlmEngine(RuntimeEnvironment.getApplication())
        val descriptor = engine.descriptor

        assertEquals(OnDeviceEngine.LOCAL_LLM, descriptor.engine)
        assertEquals("Gemma 4", descriptor.name)
        assertEquals(2588, descriptor.modelSizeMb)
        assertEquals(250, descriptor.latencyBudgetMs)
        assertEquals("Google Pixel 8 Pro", descriptor.targetHardware)
        assertEquals("Gemma 4", engine.modelName)
    }

    @Test
    fun `formatModelName formats Gemma and Qwen model filename stems`() {
        assertEquals("Gemma 4 E2B", formatModelName("gemma-4-E2B-it"))
        assertEquals("Gemma 4 E4B", formatModelName("gemma-4-E4B-it.litertlm"))
        assertEquals("Gemma 3 1B", formatModelName("Gemma3-1B-IT_int4hadamard_aot_ekv4096_G3"))
        assertEquals("Qwen", formatModelName("Qwen2-0.5B-Instruct"))
        assertEquals("custom-model", formatModelName("custom-model.litertlm"))
    }

    @Test
    fun `local conversational turn streams sentence fragments to onDelta`() =
        runBlocking {
            val engine = LocalLlmEngine(RuntimeEnvironment.getApplication())
            engine.initialize(sessionContext)

            val deltas = mutableListOf<String>()
            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "Hello there!"),
                    onDelta = { deltas.add(it) },
                    onToolCall = { null },
                )

            assertTrue("sentence fragments emitted", deltas.isNotEmpty())
            assertTrue("assistant text matches conversation", result.assistantText.contains("Pixel 8 Pro"))
            assertTrue("tool calls empty for conversation", result.toolCalls.isEmpty())

            engine.close()
        }

    @Test
    fun `action query triggers tool call and concludes response`() =
        runBlocking {
            val engine = LocalLlmEngine(RuntimeEnvironment.getApplication())
            engine.initialize(sessionContext)

            val deltas = mutableListOf<String>()
            var executedCall: ToolCall? = null

            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "Please switch_service to home"),
                    onDelta = { deltas.add(it) },
                    onToolCall = { call ->
                        executedCall = call
                        JsonObject(mapOf("status" to JsonPrimitive("Switched to home")))
                    },
                )

            assertNotNull("tool call must be dispatched", executedCall)
            assertEquals("switch_service", executedCall?.name)
            assertTrue("assistant text incorporates conclusion", result.assistantText.contains("Switched to home"))
            assertEquals(1, result.toolCalls.size)

            engine.close()
        }

    @Test
    fun `edge llm runner generates tokens and dispatches tool when model loaded`() =
        runBlocking {
            val fakeRunner =
                object : EdgeLlmRunner {
                    override val isModelLoaded: Boolean = true

                    override suspend fun generateStream(prompt: String): Flow<String> =
                        flowOf("TOOL_CALL: {\"name\": \"switch_service\", \"query\": \"switch to media\"}")

                    override suspend fun generate(prompt: String): String =
                        "TOOL_CALL: {\"name\": \"switch_service\", \"query\": \"switch to media\"}"

                    override fun close() = Unit
                }

            val engine =
                LocalLlmEngine(
                    context = RuntimeEnvironment.getApplication(),
                    runnerFactory = { fakeRunner },
                )
            engine.initialize(sessionContext)

            val deltas = mutableListOf<String>()
            var executedCall: ToolCall? = null
            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "switch to media"),
                    onDelta = { deltas.add(it) },
                    onToolCall = { call ->
                        executedCall = call
                        JsonObject(mapOf("message" to JsonPrimitive("Switched to media")))
                    },
                )

            assertNotNull(executedCall)
            assertEquals("switch_service", executedCall?.name)
            assertTrue(result.assistantText.contains("Switched to media"))
            assertEquals(1, result.toolCalls.size)
            engine.close()
        }

    @Test
    fun `injectContext incorporates notifications into conversation history`() =
        runBlocking {
            val engine = LocalLlmEngine(RuntimeEnvironment.getApplication())
            engine.initialize(sessionContext)

            engine.injectContext("Server restarted at 12:00")
            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "What is the status?"),
                    onDelta = {},
                    onToolCall = { null },
                )

            assertTrue("assistant returns valid response after context update", result.assistantText.isNotEmpty())
            engine.close()
        }

    @Test
    fun `LiteRtLmRunner handles missing model gracefully`() =
        runBlocking {
            val runner = LiteRtLmRunner(RuntimeEnvironment.getApplication(), "/nonexistent/path/model.litertlm")
            assertTrue("model not loaded for nonexistent path", !runner.isModelLoaded)
            assertEquals("", runner.generate("Hello"))
            runner.close()
        }
}
