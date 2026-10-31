// Exercises the custom LFM3 bundle with real tool calls, conversation reset and long-context retrieval.
package com.fghbuild.gomode.voice

import androidx.test.platform.app.InstrumentationRegistry
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.minutes

class Lfm3ModelTest {
    @Test
    fun realModelPreservesLiteralToolMarkersAndExactSchemaNumbers() =
        runTest(timeout = 5.minutes) {
            withContext(Dispatchers.IO) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val model = LocalLlmModel.LFM_3_1_7B
                assumeTrue("Install the custom 64K bundle", selectedModelPaths(context, model).any { File(it).isFile })
                val literal = "abc<think>keep me</think>def"
                val generated = StringBuilder()
                val engine =
                    LocalLlmEngine(
                        context,
                        { runnerContext, config ->
                            val native = LiteRtLmRunner(runnerContext, config, model = model)
                            object : EdgeLlmRunner by native {
                                override suspend fun generateStream(message: com.google.ai.edge.litertlm.Message) =
                                    native.generateStream(message).onEach { chunk ->
                                        chunk.contents.contents
                                            .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                                            .forEach { generated.append(it.text) }
                                    }
                            }
                        },
                        model = model,
                    )
                try {
                    engine.initialize(
                        EngineSessionContext(
                            languageTag = "en-US",
                            systemInstruction =
                                "Use record_value to save the exact value and id requested. " +
                                    "Preserve all characters. After the tool succeeds, answer Saved.",
                            serviceContextText = "",
                            tools =
                                listOf(
                                    ToolDescriptor(
                                        name = "record_value",
                                        description = "Save a literal value and exact numeric identifier.",
                                        inputSchema =
                                            Json.parseToJsonElement(
                                                """{"type":"object","properties":{"value":{"type":"string"},""" +
                                                    """"id":{"type":"integer","enum":[9007199254740993]}},""" +
                                                    """"required":["value","id"],"additionalProperties":false}""",
                                            ) as JsonObject,
                                    ),
                                ),
                            mcpClient = null,
                        ),
                    )
                    var calls = 0
                    val spoken = StringBuilder()
                    engine.processTurn(
                        UserTurnInput("Save the literal value $literal with id 9007199254740993."),
                        spoken::append,
                    ) { call ->
                        calls++
                        assertEquals("record_value", call.name)
                        val value =
                            call.args.jsonObject
                                .getValue("value")
                                .jsonPrimitive.content
                        // Compare the transport with actual output, independently of model copying ability.
                        assertTrue(value.contains("<think>"))
                        assertTrue(
                            "Dispatched argument must occur verbatim within the native parameter",
                            generated.contains("<parameter=value>\n$value\n</parameter>") ||
                                generated.contains("<parameter=value>$value</parameter>"),
                        )
                        assertEquals(
                            "9007199254740993",
                            call.args.jsonObject
                                .getValue("id")
                                .jsonPrimitive.content,
                        )
                        // This test ends at dispatch; the weather test covers a complete result round trip.
                        engine.invalidateTurns()
                        buildJsonObject { put("saved", true) }
                    }
                    assertEquals(1, calls)
                    assertEquals("", spoken.toString())
                } finally {
                    engine.closeAndJoin()
                }
            }
        }

    @Test
    fun realModelUsesToolResultAndResetsConversation() =
        runTest(timeout = 5.minutes) {
            withContext(Dispatchers.IO) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val model = LocalLlmModel.LFM_3_1_7B
                assumeTrue(
                    "Install the custom 64K LFM3 bundle before this model acceptance test",
                    selectedModelPaths(context, model).any {
                        File(it).isFile
                    },
                )
                val engine = LocalLlmEngine(context, model = model)
                val setup =
                    EngineSessionContext(
                        languageTag = "en-US",
                        systemInstruction =
                            "Use get_weather for weather questions. Include its verification code in your answer.",
                        serviceContextText = "",
                        tools =
                            listOf(
                                ToolDescriptor(
                                    name = "get_weather",
                                    description = "Look up current weather and its verification code.",
                                    inputSchema =
                                        Json.parseToJsonElement(
                                            """{"type":"object","properties":{"city":{"type":"string"}},""" +
                                                """"required":["city"]}""",
                                        ) as JsonObject,
                                ),
                            ),
                        mcpClient = null,
                    )
                try {
                    engine.initialize(setup)
                    val spoken = StringBuilder()
                    val reply =
                        engine.processTurn(UserTurnInput("What is the weather in Paris?"), spoken::append) { call ->
                            assertEquals("get_weather", call.name)
                            buildJsonObject {
                                put("weather", "18 Celsius, light rain")
                                put("verification_code", "MINT-739")
                            }
                        }
                    assertTrue(reply.toolCalls.isNotEmpty())
                    assertTrue(reply.assistantText.contains("MINT-739"))
                    assertFalse(spoken.contains("<tool_call>"))
                    assertFalse(spoken.contains("<think>"))
                    engine.resetConversation(setup.copy(tools = emptyList(), systemInstruction = "Answer briefly."))
                    val fresh = engine.processTurn(UserTurnInput("What is the capital of Japan?"), {}) { null }
                    assertTrue(fresh.assistantText.contains("Tokyo", ignoreCase = true))
                } finally {
                    engine.closeAndJoin()
                }
            }
        }

    @Test
    fun realModelRecallsNotesBeyondSixteenThousandTokens() =
        runTest(timeout = 60.minutes) {
            assumeTrue(
                "Enable gomodeLongContext for long-context model acceptance",
                InstrumentationRegistry.getArguments().getString("gomodeLongContext") == "true",
            )
            withContext(Dispatchers.IO) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val model = LocalLlmModel.LFM_3_1_7B
                assertTrue("Install the custom 64K bundle", selectedModelPaths(context, model).any { File(it).isFile })
                val engine = LocalLlmEngine(context, model = model)
                try {
                    engine.initialize(
                        EngineSessionContext(
                            languageTag = "en-US",
                            systemInstruction =
                                "Recall facts from the user's notes. Answer with only the requested code.",
                            serviceContextText = "",
                            tools = emptyList(),
                            mcpClient = null,
                        ),
                    )
                    // The pinned source tokenizer encodes each ` neutral` as one
                    // token. The note is separated from the question by 20K tokens.
                    val notes =
                        "The archive access code is ORCHID-6829.\n" +
                            " neutral".repeat(20_000) + "\nWhat is the archive access code?"
                    val reply = engine.processTurn(UserTurnInput(notes), {}) { error("No tools are advertised") }
                    assertTrue(reply.assistantText.contains("ORCHID-6829"))
                } finally {
                    engine.closeAndJoin()
                }
            }
        }
}
