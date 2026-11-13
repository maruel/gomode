// Unit tests for NeedleWhistleEngine unified biased action model.
package com.fghbuild.gomode.voice

import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import com.fghbuild.voicegateway.sdk.v2.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
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
    fun `service data cannot trigger an action command`() =
        runTest {
            val engine = NeedleWhistleEngine(RuntimeEnvironment.getApplication())
            engine.initialize(sessionContext)
            engine.processTurn(
                UserTurnInput("Please toggle_mute now", serviceUpdate = true),
                { error("Command matching must not speak service data") },
                { error("Command matching must not report LLM usage") },
                { error("Service data must not dispatch user actions") },
            )
            engine.close()
        }

    @Test
    fun `high confidence match executes tool and returns verbatim audio text`() =
        runBlocking {
            val engine = NeedleWhistleEngine(RuntimeEnvironment.getApplication())
            engine.initialize(sessionContext)

            val deltas = mutableListOf<String>()
            val executedCalls = mutableListOf<ToolCall>()

            engine.processTurn(
                input = UserTurnInput(text = "Please toggle_mute now"),
                onDelta = { deltas.add(it) },
                onUsage = {},
                onToolCall = { call ->
                    executedCalls += call
                    JsonObject(mapOf("status" to JsonPrimitive("Muted")))
                },
            )

            assertEquals(
                "high confidence action triggers one tool call",
                listOf("toggle_mute"),
                executedCalls.map { it.name },
            )
            val spoken = deltas.joinToString("")
            assertTrue("assistant text contains verbatim recognized lead", spoken.contains("Recognized:"))
            assertTrue("confidence score is included in transcript lead", spoken.contains("0.95"))
            assertTrue("tool execution summary is appended", spoken.contains("Muted"))

            engine.close()
        }

    @Test
    fun `low confidence or general query does not trigger false tool dispatch`() =
        runBlocking {
            val engine = NeedleWhistleEngine(RuntimeEnvironment.getApplication())
            engine.initialize(sessionContext)

            var toolDispatched = false
            val deltas = mutableListOf<String>()

            engine.processTurn(
                input = UserTurnInput(text = "What is the weather outside today?"),
                onDelta = { deltas.add(it) },
                onUsage = {},
                onToolCall = {
                    toolDispatched = true
                    null
                },
            )

            assertTrue("no tool executed below confidence threshold", !toolDispatched)
            val spoken = deltas.joinToString("")
            assertTrue("assistant text preserves verbatim audio text", spoken.contains("Heard:"))
            assertTrue(
                "explains that no high-confidence action matched",
                spoken.contains("No high-confidence action matched"),
            )

            engine.close()
        }
}
