// Unit tests for NeedleWhistleEngine unified biased action model on Google Pixel 8 Pro.
package com.fghbuild.gomode.voice

import com.caic.voicegateway.sdk.v1.ToolCall
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
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
class NeedleWhistleEngineTest {
    private val sampleTools =
        listOf(
            ToolDescriptor(
                name = "toggle_mute",
                description = "Toggle microphone mute state",
                inputSchema = JsonObject(emptyMap()),
            ),
            ToolDescriptor(
                name = "search_notes",
                description = "Search personal notes catalog",
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
    fun `descriptor reports 52 MB size and 120ms latency budget for Pixel 8 Pro`() {
        val engine = NeedleWhistleEngine(RuntimeEnvironment.getApplication())
        val descriptor = engine.descriptor

        assertEquals(OnDeviceEngine.NEEDLE_WHISTLE, descriptor.engine)
        assertEquals("Whistle + Needle", descriptor.name)
        assertEquals(52, descriptor.modelSizeMb)
        assertEquals(120, descriptor.latencyBudgetMs)
        assertEquals("Google Pixel 8 Pro", descriptor.targetHardware)
    }

    @Test
    fun `high confidence match executes tool and returns verbatim audio text`() =
        runBlocking {
            val engine = NeedleWhistleEngine(RuntimeEnvironment.getApplication())
            engine.initialize(sessionContext)

            val deltas = mutableListOf<String>()
            var executedCall: ToolCall? = null

            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "Please toggle_mute now"),
                    onDelta = { deltas.add(it) },
                    onToolCall = { call ->
                        executedCall = call
                        JsonObject(mapOf("status" to JsonPrimitive("Muted")))
                    },
                )

            assertNotNull("high confidence action triggers tool call", executedCall)
            assertEquals("toggle_mute", executedCall?.name)
            assertTrue("assistant text contains verbatim recognized lead", result.assistantText.contains("Recognized:"))
            assertTrue("confidence score is included in transcript lead", result.assistantText.contains("0.95"))
            assertTrue("tool execution summary is appended", result.assistantText.contains("Muted"))
            assertEquals(1, result.toolCalls.size)

            engine.close()
        }

    @Test
    fun `low confidence or general query does not trigger false tool dispatch`() =
        runBlocking {
            val engine = NeedleWhistleEngine(RuntimeEnvironment.getApplication())
            engine.initialize(sessionContext)

            var toolDispatched = false
            val deltas = mutableListOf<String>()

            val result =
                engine.processTurn(
                    input = UserTurnInput(text = "What is the weather outside today?"),
                    onDelta = { deltas.add(it) },
                    onToolCall = {
                        toolDispatched = true
                        null
                    },
                )

            assertTrue("no tool executed below confidence threshold", !toolDispatched)
            assertTrue("tool calls list is empty", result.toolCalls.isEmpty())
            assertTrue("assistant text preserves verbatim audio text", result.assistantText.contains("Heard:"))
            assertTrue(
                "explains that no high-confidence action matched",
                result.assistantText.contains("No high-confidence action matched"),
            )

            engine.close()
        }
}
