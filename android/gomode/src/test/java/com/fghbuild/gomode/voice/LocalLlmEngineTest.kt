// Tests schema-bearing Gemma prompts, parsed tool arguments, and fail-closed local tool dispatch.
package com.fghbuild.gomode.voice

import com.caic.voicegateway.sdk.v1.ToolCall
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
    private val sessionContext =
        EngineSessionContext(
            languageTag = "en-US",
            systemInstruction = "You are a terse assistant.",
            serviceContextText = "",
            tools =
                listOf(
                    ToolDescriptor(
                        name = "switch_service",
                        description = "Switch active service",
                        inputSchema =
                            Json.parseToJsonElement(
                                """{
                                    "type":"object",
                                    "properties":{"service":{"type":"string"}},
                                    "required":["service"],
                                    "additionalProperties":false
                                }""",
                            ),
                    ),
                ),
            mcpClient = null,
        )

    private fun engineWithResponse(
        response: String,
        onSystemInstruction: (String) -> Unit = {},
        onPrompt: (String) -> Unit = {},
    ): LocalLlmEngine =
        LocalLlmEngine(
            context = RuntimeEnvironment.getApplication(),
            runnerFactory = { _, instruction ->
                onSystemInstruction(instruction)
                object : EdgeLlmRunner {
                    override val isModelLoaded = true

                    override suspend fun generateStream(prompt: String): Flow<String> {
                        onPrompt(prompt)
                        // Split the marker and JSON across chunks like a real token stream.
                        return flowOf(response.take(4), response.drop(4))
                    }

                    override suspend fun generate(prompt: String): String = response

                    override fun close() = Unit
                }
            },
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
    fun `tool call preserves generated arguments and does not speak tool JSON`() =
        runTest {
            val arguments = Json.parseToJsonElement("""{"service":"media"}""")
            var prompt = ""
            val engine =
                engineWithResponse(
                    """TOOL_CALL: {"name":"switch_service","arguments":$arguments}""",
                    onSystemInstruction = { prompt = it },
                )
            engine.initialize(sessionContext)
            val deltas = mutableListOf<String>()
            var executedCall: ToolCall? = null
            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "Use my music service"),
                    onDelta = { deltas.add(it) },
                    onToolCall = { call ->
                        executedCall = call
                        JsonObject(mapOf("message" to JsonPrimitive("Switched to media")))
                    },
                )

            assertNotNull(executedCall)
            assertEquals("switch_service", executedCall?.name)
            assertEquals(arguments, executedCall?.args)
            assertTrue(prompt.contains("Switch active service"))
            assertTrue(prompt.contains("\"required\":[\"service\"]"))
            assertTrue(prompt.contains("\"additionalProperties\":false"))
            assertEquals("Switched to media", deltas.joinToString(""))
            assertEquals("Switched to media", result.assistantText)
            assertEquals(listOf(executedCall), result.toolCalls)
            engine.close()
        }

    @Test
    fun `tool parser preserves nested arrays booleans numbers and null arguments`() =
        runTest {
            val arguments = Json.parseToJsonElement("""{"options":{"ids":[1,2],"enabled":true,"note":null}}""")
            val engine = engineWithResponse("""TOOL_CALL: {"name":"configure","arguments":$arguments}""")
            engine.initialize(
                sessionContext.copy(
                    tools = listOf(ToolDescriptor(name = "configure", inputSchema = JsonObject(emptyMap()))),
                ),
            )
            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "Configure it"),
                    onDelta = {},
                    onToolCall = { call ->
                        assertEquals(arguments, call.args)
                        JsonObject(emptyMap())
                    },
                )
            assertEquals(1, result.toolCalls.size)
            engine.close()
        }

    @Test
    fun `no argument tools receive an empty object without query`() =
        runTest {
            val engine = engineWithResponse("""TOOL_CALL: {"name":"refresh","arguments":{}}""")
            engine.initialize(
                sessionContext.copy(
                    tools = listOf(ToolDescriptor(name = "refresh", inputSchema = JsonObject(emptyMap()))),
                ),
            )
            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "Refresh"),
                    onDelta = {},
                    onToolCall = { call ->
                        assertEquals(JsonObject(emptyMap()), call.args)
                        JsonObject(emptyMap())
                    },
                )
            assertEquals(1, result.toolCalls.size)
            engine.close()
        }

    @Test
    fun `mentioning a tool in conversation never executes it`() =
        runTest {
            val response = "The switch_service tool changes your active service."
            val engine = engineWithResponse(response)
            engine.initialize(sessionContext)
            val deltas = mutableListOf<String>()
            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "Explain switch_service"),
                    onDelta = { deltas.add(it) },
                    onToolCall = { error("Must not execute a tool from a keyword match") },
                )
            assertEquals(response, result.assistantText)
            assertEquals(response, deltas.joinToString(""))
            assertTrue(result.toolCalls.isEmpty())
            engine.close()
        }

    @Test
    fun `malformed and unknown tool calls fail without dispatch or speech`() =
        runTest {
            val invalidResponses =
                listOf(
                    "",
                    "TOOL_CALL: not JSON",
                    """Sure.
                        TOOL_CALL: {"name":"switch_service","arguments":{"service":"media"}}
                    """,
                    """```json
                        TOOL_CALL: {"name":"switch_service","arguments":{"service":"media"}}
                        ```
                    """,
                    "tool_call: {}",
                    "TOOL_CALL missing colon",
                    """TOOL_CALL: {"name":"switch_service","query":"media"}""",
                    """TOOL_CALL: {"name":"switch_service","arguments":null}""",
                    """TOOL_CALL: {"name":"switch_service","arguments":"media"}""",
                    """TOOL_CALL: {"name":"switch_service","arguments":[]}""",
                    """TOOL_CALL: {"name":42,"arguments":{}}""",
                    """TOOL_CALL: {"name":"unknown","arguments":{}}""",
                    """TOOL_CALL: {"name":"switch_service","arguments":{}} trailing text""",
                )
            for (response in invalidResponses) {
                val engine = engineWithResponse(response)
                engine.initialize(sessionContext)
                val result =
                    runCatching {
                        engine.processTurn(
                            input = UserTurnInput(text = "switch_service to media"),
                            onDelta = { error("Must not speak malformed tool output") },
                            onToolCall = { error("Must not dispatch malformed tool output") },
                        )
                    }
                assertTrue(response, result.exceptionOrNull() is IllegalArgumentException)
                engine.close()
            }
        }

    @Test
    fun `missing model never fabricates a tool call`() =
        runTest {
            val engine =
                LocalLlmEngine(
                    RuntimeEnvironment.getApplication(),
                    runnerFactory = { context, instruction ->
                        LiteRtLmRunner(context, instruction, "/nonexistent/path/model.litertlm")
                    },
                )
            engine.initialize(sessionContext)
            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "Please switch_service to home"),
                    onDelta = {},
                    onToolCall = { error("Missing model cannot generate arguments") },
                )
            assertTrue(result.toolCalls.isEmpty())
            engine.close()
        }

    @Test
    fun `tool schemas and protocol are installed once across multiple turns`() =
        runTest {
            val instructions = mutableListOf<String>()
            val prompts = mutableListOf<String>()
            val engine =
                engineWithResponse(
                    "Ready.",
                    onSystemInstruction = { instructions.add(it) },
                    onPrompt = { prompts.add(it) },
                )
            engine.initialize(sessionContext)
            for (query in listOf("First request", "Second request")) {
                engine.processTurn(
                    input = UserTurnInput(text = query),
                    onDelta = {},
                    onToolCall = { error("Conversational response cannot dispatch a tool") },
                )
            }
            assertEquals(1, instructions.size)
            assertTrue(instructions.single().contains("\"required\":[\"service\"]"))
            assertTrue(instructions.single().contains("TOOL_CALL:"))
            assertEquals(listOf("First request", "Second request"), prompts)
            engine.close()
        }

    @Test
    fun `structured tool results never reach speech as JSON`() =
        runTest {
            val results =
                listOf(
                    """{"error":{"code":42}}""" to "Tool failed.",
                    """{"message":["internal","details"]}""" to "Completed.",
                    """{"status":true}""" to "Completed.",
                )
            for ((toolResult, expected) in results) {
                val engine =
                    engineWithResponse("""TOOL_CALL: {"name":"switch_service","arguments":{"service":"media"}}""")
                engine.initialize(sessionContext)
                val deltas = mutableListOf<String>()
                val result =
                    engine.processTurn(
                        input = UserTurnInput(text = "Switch to media"),
                        onDelta = { deltas.add(it) },
                        onToolCall = { Json.parseToJsonElement(toolResult) },
                    )
                assertEquals(expected, deltas.joinToString(""))
                assertEquals(expected, result.assistantText)
                engine.close()
            }
        }

    @Test
    fun `tool execution errors are not reported as completed`() =
        runTest {
            val engine = engineWithResponse("""TOOL_CALL: {"name":"switch_service","arguments":{"service":"media"}}""")
            engine.initialize(sessionContext)
            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "Switch to media"),
                    onDelta = {},
                    onToolCall = { JsonObject(mapOf("error" to JsonPrimitive("Invalid service"))) },
                )
            assertTrue(result.assistantText.contains("Invalid service"))
            engine.close()
        }
}
