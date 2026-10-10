// Unit tests for NeuDecide direct speech-to-action engine on Google Pixel 8 Pro.
package com.fghbuild.gomode.voice

import com.caic.voicegateway.sdk.v1.ToolCall
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NeuDecideEngineTest {
    private val sampleTools =
        listOf(
            ToolDescriptor(
                name = "get_weather",
                description = "Get current weather in location",
                inputSchema = JsonObject(emptyMap()),
            ),
            ToolDescriptor(
                name = "create_task",
                description = "Create a new reminder task",
                inputSchema = JsonObject(emptyMap()),
            ),
        )

    private val sessionContext =
        EngineSessionContext(
            languageTag = "en-US",
            systemInstruction = "Terse assistant",
            serviceContextText = "",
            tools = sampleTools,
            mcpClient = null,
        )

    @Test
    fun `descriptor reports 43 MB Q4 ONNX and sub-100ms latency budget for Pixel 8 Pro`() {
        val engine = NeuDecideEngine(RuntimeEnvironment.getApplication())
        val descriptor = engine.descriptor

        assertEquals(OnDeviceEngine.NEUDECIDE, descriptor.engine)
        assertEquals("NeuDecide", descriptor.name)
        assertEquals(43, descriptor.modelSizeMb)
        assertTrue("latency budget is under 100ms", descriptor.latencyBudgetMs <= 100)
        assertEquals("Google Pixel 8 Pro", descriptor.targetHardware)
    }

    @Test
    fun `direct speech-to-action extracts tool call without intermediate LLM turn`() =
        runBlocking {
            val engine = NeuDecideEngine(RuntimeEnvironment.getApplication())
            engine.initialize(sessionContext)

            val deltas = mutableListOf<String>()
            var executedCall: ToolCall? = null

            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "What is the weather in Montreal?"),
                    onDelta = { deltas.add(it) },
                    onToolCall = { call ->
                        executedCall = call
                        JsonObject(mapOf("status" to JsonPrimitive("Sunny 22C")))
                    },
                )

            assertNotNull("tool call must be generated", executedCall)
            assertEquals("get_weather", executedCall?.name)
            val args = executedCall?.args as? JsonObject
            assertNotNull(args)
            assertEquals("Montreal", args?.get("location")?.let { (it as JsonPrimitive).content })
            assertTrue("deltas announce tool execution", deltas.any { it.contains("get_weather") })
            assertTrue("assistant text contains outcome", result.assistantText.contains("Sunny 22C"))

            engine.close()
        }

    @Test
    fun `conversational query returns direct response without tool invocation`() =
        runBlocking {
            val engine = NeuDecideEngine(RuntimeEnvironment.getApplication())
            engine.initialize(sessionContext)

            var toolCalled = false
            val deltas = mutableListOf<String>()

            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "Hello! Who are you?"),
                    onDelta = { deltas.add(it) },
                    onToolCall = {
                        toolCalled = true
                        null
                    },
                )

            assertTrue("no tool invoked for greeting", !toolCalled)
            assertTrue("tool calls list is empty", result.toolCalls.isEmpty())
            assertTrue("response mentions Pixel 8 Pro", result.assistantText.contains("Pixel 8 Pro"))
            assertTrue("streamed deltas match assistant text", deltas.any { it.contains("Pixel 8 Pro") })

            engine.close()
        }

    @Test
    fun `falls back gracefully when ONNX model file is not present`() =
        runBlocking {
            val engine = NeuDecideEngine(RuntimeEnvironment.getApplication())
            engine.initialize(sessionContext)

            assertFalse("no onnx session without deployed weights", engine.hasActiveOnnxSession)
            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "status"),
                    onDelta = {},
                    onToolCall = { _: ToolCall -> null },
                )
            assertTrue(result.assistantText.contains("NeuDecide"))

            engine.close()
            assertFalse(engine.hasActiveOnnxSession)
        }
}
