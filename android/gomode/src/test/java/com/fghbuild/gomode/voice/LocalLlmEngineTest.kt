// Tests native LiteRT tool declarations, manual tool-result rounds, and local model lifecycle.
package com.fghbuild.gomode.voice

import com.caic.voicegateway.sdk.v1.ToolCall
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolManager
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.lang.reflect.Modifier

// Capture both parts of the actual native prefill configuration for owner lifecycle tests.
internal fun ConversationConfig.prefillEvidence(): String =
    systemInstruction.toString() + ToolManager(tools).getToolsDescription().toString()

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
                                """{"type":"object","properties":{"service":{"type":"string"}},""" +
                                    """"required":["service"],"additionalProperties":false}""",
                            ),
                    ),
                ),
            mcpClient = null,
        )

    private fun engine(
        configs: MutableList<ConversationConfig> = mutableListOf(),
        onClose: () -> Unit = {},
        reply: (Message) -> Flow<Message> = { flowOf(Message.model("Ready.")) },
    ): LocalLlmEngine =
        LocalLlmEngine(
            RuntimeEnvironment.getApplication(),
            { _, config ->
                configs.add(config)
                object : EdgeLlmRunner {
                    override val isModelLoaded = true
                    override val backendName = "test"

                    override suspend fun generateStream(message: Message): Flow<Message> = reply(message)

                    override fun replaceConversation(config: ConversationConfig) {
                        configs.add(config)
                    }

                    override fun close() = onClose()
                }
            },
        )

    private fun assertRecommendedSampler(config: ConversationConfig): SamplerConfig {
        val sampler = checkNotNull(config.samplerConfig)
        assertEquals(64, sampler.topK)
        assertEquals(0.95, sampler.topP, 0.0)
        assertEquals(1.0, sampler.temperature, 0.0)
        return sampler
    }

    private fun callMessage(
        name: String = "switch_service",
        args: Map<String, Any?> = mapOf("service" to "media"),
    ) = Message.model(
        toolCalls =
            listOf(
                com.google.ai.edge.litertlm
                    .ToolCall(name, args),
            ),
    )

    @Test
    fun `descriptor and formatted model names identify installed models`() {
        val engine = engine()
        assertEquals(OnDeviceEngine.LOCAL_LLM, engine.descriptor.engine)
        assertEquals("Gemma 4", engine.descriptor.name)
        assertEquals(2588, engine.descriptor.modelSizeMb)
        assertEquals("Gemma 4 E2B", formatModelName("gemma-4-E2B-it"))
        assertEquals("Gemma 4 E4B", formatModelName("gemma-4-E4B-it.litertlm"))
        assertEquals("LFM 2.5 1.2B", formatModelName("LFM2.5-1.2B-Instruct_int4_16k_fp16.litertlm"))
        assertEquals("LFM 2.5 1.2B", formatModelName("LFM2.5-1.2B-Instruct"))
        assertEquals("LFM 2.5 2.6B", formatModelName("LFM2.5-2.6B_int4_16k_fp16.litertlm"))
        assertEquals("LFM 2.5 2.6B", formatModelName("LFM2.5-2.6B"))
        assertEquals("custom-model", formatModelName("custom-model.litertlm"))
        assertEquals("Qwen2-0.5B-Instruct", formatModelName("Qwen2-0.5B-Instruct"))
        engine.close()
    }

    @Test
    fun `variant paths and display names never select the other named model`() {
        val app = RuntimeEnvironment.getApplication()
        val e2b = selectedModelPaths(app, LocalLlmModel.GEMMA_4_E2B)
        val e4b = selectedModelPaths(app, LocalLlmModel.GEMMA_4_E4B)
        val lfm12 = selectedModelPaths(app, LocalLlmModel.LFM_2_5_1_2B)
        val lfm = selectedModelPaths(app, LocalLlmModel.LFM_2_5_2_6B)
        assertTrue(e2b.first().endsWith("models/gemma-4-E2B-it.litertlm"))
        assertTrue(e4b.first().endsWith("models/gemma-4-E4B-it.litertlm"))
        assertTrue(lfm12.first().endsWith("models/LFM2.5-1.2B-Instruct_int4_16k_fp16.litertlm"))
        assertTrue(lfm.first().endsWith("models/LFM2.5-2.6B_int4_16k_fp16.litertlm"))
        assertTrue(lfm12[1].endsWith("/data/local/tmp/llm/LFM2.5-1.2B-Instruct_int4_16k_fp16.litertlm"))
        assertTrue(lfm[1].endsWith("/data/local/tmp/llm/LFM2.5-2.6B_int4_16k_fp16.litertlm"))
        assertFalse(e4b.any { it.contains("E2B") || it.endsWith("models/model.litertlm") })
        assertFalse(lfm12.any { it.contains("gemma") || it.contains("2.6B") })
        assertFalse(lfm.any { it.contains("gemma") || it.contains("1.2B") })
        val engine = LocalLlmEngine(app, model = LocalLlmModel.GEMMA_4_E4B)
        assertEquals("Gemma 4 E4B", engine.modelName)
        val lfm12Engine = LocalLlmEngine(app, model = LocalLlmModel.LFM_2_5_1_2B)
        assertEquals("LFM 2.5 1.2B", lfm12Engine.modelName)
        val lfmEngine = LocalLlmEngine(app, model = LocalLlmModel.LFM_2_5_2_6B)
        assertEquals("LFM 2.5 2.6B", lfmEngine.modelName)
        engine.close()
        lfm12Engine.close()
        lfmEngine.close()
    }

    @Test
    fun `legacy metadata accepts generic E2B without accepting known different weights`() {
        for (metadata in listOf(null, "model", "model.litertlm", "gemma", "gemma-4-E2B-it")) {
            assertTrue(acceptsLegacyModel(LocalLlmModel.GEMMA_4_E2B, metadata))
            assertFalse(acceptsLegacyModel(LocalLlmModel.GEMMA_4_E4B, metadata))
            assertFalse(acceptsLegacyModel(LocalLlmModel.LFM_2_5_1_2B, metadata))
            assertFalse(acceptsLegacyModel(LocalLlmModel.LFM_2_5_2_6B, metadata))
        }
        assertFalse(acceptsLegacyModel(LocalLlmModel.GEMMA_4_E2B, "gemma-4-E4B-it"))
        assertTrue(acceptsLegacyModel(LocalLlmModel.GEMMA_4_E4B, "gemma-4-E4B-it"))
        assertFalse(acceptsLegacyModel(LocalLlmModel.GEMMA_4_E2B, "LFM2.5-1.2B-Instruct"))
        assertTrue(acceptsLegacyModel(LocalLlmModel.LFM_2_5_1_2B, "LFM2.5-1.2B-Instruct"))
        assertFalse(acceptsLegacyModel(LocalLlmModel.GEMMA_4_E2B, "LFM2.5-2.6B"))
        assertTrue(acceptsLegacyModel(LocalLlmModel.LFM_2_5_2_6B, "LFM2.5-2.6B"))
        assertFalse(acceptsLegacyModel(LocalLlmModel.LFM_2_5_1_2B, "lfm"))
        assertFalse(acceptsLegacyModel(LocalLlmModel.LFM_2_5_2_6B, "lfm"))
        assertFalse(acceptsLegacyModel(LocalLlmModel.GEMMA_4_E2B, "Qwen2-0.5B-Instruct"))
    }

    @Test
    fun `split malformed prefix retries unchanged user message with successful history only`() =
        runTest {
            val configs = mutableListOf<ConversationConfig>()
            val requests = mutableListOf<Message>()
            val engine =
                engine(configs) { request ->
                    requests.add(request)
                    when (requests.size) {
                        1 -> flowOf(Message.model("Hello"), Message.model(" world"))
                        2 -> flowOf(Message.model("  <!to"), Message.model("ol_call>garbage"), callMessage())
                        else -> flowOf(Message.model("Recovered"))
                    }
                }
            engine.initialize(sessionContext)
            engine.processTurn(UserTurnInput("Hi"), {}, { error("No call") })
            val deltas = mutableListOf<String>()
            val result = engine.processTurn(UserTurnInput("Run"), deltas::add) { error("No malformed dispatch") }
            assertEquals("Recovered", result.assistantText)
            assertEquals(listOf("Recovered"), deltas)
            assertEquals(requests[1].toString(), requests[2].toString())
            val initialSampler = assertRecommendedSampler(configs.first())
            val retrySampler = assertRecommendedSampler(configs.last())
            assertTrue(retrySampler.seed >= 2)
            assertTrue(retrySampler.seed != initialSampler.seed)
            assertEquals(2, configs.last().initialMessages.size)
            assertEquals(
                Role.USER,
                configs
                    .last()
                    .initialMessages
                    .first()
                    .role,
            )
            assertTrue(
                configs
                    .last()
                    .initialMessages
                    .first()
                    .toString()
                    .contains("Hi"),
            )
            val replayedText =
                configs
                    .last()
                    .initialMessages
                    .last()
                    .contents.contents
                    .single() as Content.Text
            assertEquals("Hello world", replayedText.text)
            assertFalse(
                configs
                    .last()
                    .initialMessages
                    .toString()
                    .contains("garbage"),
            )
            engine.closeAndJoin()
        }

    @Test
    fun `malformed tool result reply retries result without redispatching tools`() =
        runTest {
            val configs = mutableListOf<ConversationConfig>()
            val requests = mutableListOf<Message>()
            var dispatches = 0
            val engine =
                engine(configs) { request ->
                    requests.add(request)
                    when (requests.size) {
                        1 -> flowOf(callMessage())
                        2 -> flowOf(Message.model("<!tool_call>bad"))
                        else -> flowOf(Message.model("Done"))
                    }
                }
            engine.initialize(sessionContext)
            val result =
                engine.processTurn(UserTurnInput("Run"), {}) {
                    dispatches++
                    Json.parseToJsonElement("""{"ok":true}""")
                }
            assertEquals(1, dispatches)
            assertEquals("Done", result.assistantText)
            assertEquals(Role.TOOL, requests[1].role)
            assertEquals(requests[1].toString(), requests[2].toString())
            assertEquals(2, configs.last().initialMessages.size)
            assertEquals(
                "switch_service",
                configs
                    .last()
                    .initialMessages
                    .last()
                    .toolCalls
                    .single()
                    .name,
            )
            engine.closeAndJoin()
        }

    @Test
    fun `repeated malformed prefix fails after one retry without speech or dispatch`() =
        runTest {
            var attempts = 0
            val configs = mutableListOf<ConversationConfig>()
            val engine =
                engine(configs) {
                    attempts++
                    flowOf(Message.model("<!tool_call>bad"))
                }
            engine.initialize(sessionContext)
            val failure =
                runCatching {
                    engine.processTurn(UserTurnInput("Run"), { error("No speech") }, { error("No dispatch") })
                }.exceptionOrNull()
            assertEquals("Local model repeated a malformed tool-call prefix", failure?.message)
            assertEquals(2, attempts)
            assertEquals(2, configs.size)
            val logs = ShadowLog.getLogsForTag("LocalLlmEngine").map { it.msg }
            assertTrue(logs.any { it.startsWith("local_retry ") && it.contains("outcome=failed") })
            assertTrue(logs.single { it.startsWith("local_turn ") }.contains("retries=1 outcome=failed"))
            engine.closeAndJoin()
        }

    @Test
    fun `native parser failure on second turn retries that message with prior history`() =
        runTest {
            val requests = mutableListOf<Message>()
            val configs = mutableListOf<ConversationConfig>()
            val engine =
                engine(configs) { request ->
                    requests.add(request)
                    when (requests.size) {
                        1 -> flowOf(Message.model("First answer"))
                        2 -> flow { throw nativeParseFailure() }
                        else -> flowOf(Message.model("Second answer"))
                    }
                }
            engine.initialize(sessionContext)
            engine.processTurn(UserTurnInput("First request"), {}, { error("No tool") })
            val deltas = mutableListOf<String>()
            val result = engine.processTurn(UserTurnInput("Second request"), deltas::add) { error("No tool") }
            assertEquals("Second answer", result.assistantText)
            assertEquals(listOf("Second answer"), deltas)
            assertEquals(requests[1].toString(), requests[2].toString())
            assertEquals(2, configs.last().initialMessages.size)
            assertTrue(
                configs
                    .last()
                    .initialMessages
                    .first()
                    .toString()
                    .contains("First request"),
            )
            assertFalse(ShadowLog.getLogsForTag("LocalLlmEngine").joinToString { it.msg }.contains("private-output"))
            engine.closeAndJoin()
        }

    @Test
    fun `successive native retries use fresh seeds and keep recommended sampling on reset`() =
        runTest {
            val configs = mutableListOf<ConversationConfig>()
            var attempts = 0
            val engine =
                engine(configs) {
                    attempts++
                    if (attempts % 2 == 1) flow { throw nativeParseFailure() } else flowOf(Message.model("Recovered"))
                }
            engine.initialize(sessionContext)
            engine.processTurn(UserTurnInput("First request"), {}, { error("No tool") })
            engine.processTurn(UserTurnInput("Second request"), {}, { error("No tool") })
            assertEquals(3, configs.size)
            val seeds = configs.map { assertRecommendedSampler(it).seed }
            assertEquals(3, seeds.distinct().size)
            assertTrue(seeds.drop(1).all { it >= 2 })
            engine.resetConversation(sessionContext)
            assertEquals(seeds.last(), assertRecommendedSampler(configs.last()).seed)
            engine.closeAndJoin()
        }

    @Test
    fun `native parse retries stop after one attempt and expose no parser dump`() =
        runTest {
            var attempts = 0
            val engine =
                engine {
                    attempts++
                    flow { throw nativeParseFailure() }
                }
            engine.initialize(sessionContext)
            val failure =
                runCatching {
                    engine.processTurn(UserTurnInput("Request"), { error("No speech") }, { error("No tool") })
                }.exceptionOrNull()
            assertEquals("Local model could not form a valid tool call. Try again.", failure?.message)
            assertEquals(2, attempts)
            engine.closeAndJoin()
        }

    @Test
    fun `native parser recovery reuses tool result without repeating side effects`() =
        runTest {
            val requests = mutableListOf<Message>()
            val configs = mutableListOf<ConversationConfig>()
            var dispatches = 0
            val engine =
                engine(configs) { message ->
                    requests.add(message)
                    when (requests.size) {
                        1 -> flowOf(callMessage())
                        2 -> flow { throw nativeParseFailure() }
                        else -> flowOf(Message.model("Done"))
                    }
                }
            engine.initialize(sessionContext)
            engine.processTurn(UserTurnInput("Run"), {}) {
                dispatches++
                JsonObject(emptyMap())
            }
            assertEquals(1, dispatches)
            assertEquals(Role.TOOL, requests[1].role)
            assertEquals(requests[1].toString(), requests[2].toString())
            assertEquals(
                "switch_service",
                configs
                    .last()
                    .initialMessages
                    .last()
                    .toolCalls
                    .single()
                    .name,
            )
            engine.closeAndJoin()
        }

    @Test
    fun `native parser failure after close does not retry`() =
        runTest {
            val configs = mutableListOf<ConversationConfig>()
            var active: LocalLlmEngine? = null
            val engine =
                engine(configs) {
                    flow {
                        active?.close()
                        throw nativeParseFailure()
                    }
                }
            active = engine
            engine.initialize(sessionContext)
            engine.processTurn(UserTurnInput("Run"), { error("No speech") }, { error("No tool") })
            assertEquals(1, configs.size)
            engine.closeAndJoin()
        }

    @Test
    fun `parse failure after streamed speech does not retry or expose generated arguments`() =
        runTest {
            var attempts = 0
            val engine =
                engine {
                    attempts++
                    flow {
                        emit(Message.model("Already spoken."))
                        throw nativeParseFailure()
                    }
                }
            engine.initialize(sessionContext)
            val deltas = mutableListOf<String>()
            val failure =
                runCatching {
                    engine.processTurn(UserTurnInput("Request"), deltas::add) { error("No tool") }
                }.exceptionOrNull()
            assertEquals("Local model could not form a valid tool call. Try again.", failure?.message)
            assertEquals(1, attempts)
            assertEquals(listOf("Already spoken."), deltas)
            engine.closeAndJoin()
        }

    @Test
    fun `other native failures do not retry`() =
        runTest {
            var attempts = 0
            val engine =
                engine {
                    attempts++
                    flow { throw LiteRtLmJniException("Status Code: 3. Message: Context is full") }
                }
            engine.initialize(sessionContext)
            val failure =
                runCatching {
                    engine.processTurn(UserTurnInput("Request"), {}, { error("No tool") })
                }.exceptionOrNull()
            assertTrue(failure is LiteRtLmJniException)
            assertEquals(1, attempts)
            engine.closeAndJoin()
        }

    private fun nativeParseFailure() =
        LiteRtLmJniException(
            "Status Code: 3. Message: Failed to parse tool calls from code block: private-output\n" +
                "full response: <|tool_call>private-output<tool_call|>",
        )

    @Test
    fun `partial prefix and marker after ordinary text remain ordinary speech`() =
        runTest {
            val engine = engine { flowOf(Message.model("<!tool")) }
            engine.initialize(sessionContext)
            assertEquals("<!tool", engine.processTurn(UserTurnInput("Hi"), {}, { error("No dispatch") }).assistantText)
            engine.closeAndJoin()
            val normal = engine { flowOf(Message.model("A marker: <!tool_call>")) }
            normal.initialize(sessionContext)
            assertEquals(
                "A marker: <!tool_call>",
                normal.processTurn(UserTurnInput("Hi"), {}, { error("No dispatch") }).assistantText,
            )
            normal.closeAndJoin()
        }

    @Test
    fun `turn and retry diagnostics include safe metadata without conversation content`() =
        runTest {
            var generations = 0
            val engine =
                engine {
                    when (++generations) {
                        1 -> flowOf(callMessage(args = mapOf("service" to "private-argument")))
                        2 -> flowOf(Message.model("<!tool_call>private-malformed-text"))
                        else -> flowOf(Message.model("private-assistant-text"))
                    }
                }
            engine.initialize(sessionContext)
            engine.processTurn(UserTurnInput("private-user-prompt"), {}) {
                Json.parseToJsonElement("""{"message":"private-tool-result"}""")
            }
            val logs = ShadowLog.getLogsForTag("LocalLlmEngine").map { it.msg }
            val summary = logs.single { it.startsWith("local_turn ") }
            assertTrue(summary.contains("model=GEMMA_4_E2B backend=test"))
            assertTrue(summary.contains("duration_ms="))
            assertTrue(summary.contains("tool_rounds=1 retries=1 outcome=completed"))
            assertTrue(logs.any { it.startsWith("local_retry ") && it.contains("outcome=started") })
            assertTrue(logs.any { it.startsWith("local_retry ") && it.contains("outcome=recovered") })
            assertFalse(logs.joinToString().contains("private-"))
            assertFalse(logs.joinToString().contains("switch_service"))
            engine.closeAndJoin()
        }

    @Test
    fun `local prompt adds voice rules after host instructions and retains service facts`() =
        runTest {
            val configs = mutableListOf<ConversationConfig>()
            val engine = engine(configs)
            engine.initialize(
                sessionContext.copy(
                    systemInstruction = "Explain status in detail.",
                    serviceContextText = "One job is waiting.",
                ),
            )
            val prompt = configs.single().systemInstruction.toString()
            assertTrue(prompt.contains("Explain status in detail."))
            assertTrue(prompt.contains("One job is waiting."))
            assertTrue(prompt.contains("plain text"))
            assertTrue(prompt.contains("Do not use Markdown"))
            assertTrue(prompt.contains("raw logs"))
            assertTrue(prompt.contains("unless the user asks"))
            assertTrue(prompt.indexOf("Voice response rules:") > prompt.indexOf("Explain status in detail."))
            engine.closeAndJoin()
        }

    @Test
    fun `native declarations carry schemas and manual execution with real prefill`() =
        runTest {
            val configs = mutableListOf<ConversationConfig>()
            val engine = engine(configs)
            engine.initialize(sessionContext)
            val config = configs.single()
            assertRecommendedSampler(config)
            assertFalse(config.automaticToolCalling)
            assertTrue(config.prefillPrefaceOnInit)
            val description =
                ToolManager(config.tools)
                    .getToolsDescription()
                    .single()
                    .asJsonObject["function"]
                    .asJsonObject
            assertEquals("switch_service", description["name"].asString)
            assertEquals("Switch active service", description["description"].asString)
            assertEquals(
                "service",
                description["parameters"]
                    .asJsonObject["required"]
                    .asJsonArray
                    .single()
                    .asString,
            )
            assertFalse(config.systemInstruction.toString().contains("TOOL_CALL"))
            assertFalse(config.systemInstruction.toString().contains("inputSchema"))
            engine.closeAndJoin()
        }

    @Test
    fun `structured calls coexist with preamble and model receives structured results`() =
        runTest {
            var round = 0
            val requests = mutableListOf<Message>()
            val engine =
                engine { request ->
                    requests.add(request)
                    if (round++ == 0) {
                        flowOf(Message.model("Sure. "), callMessage())
                    } else {
                        flowOf(Message.model("Switched to media."))
                    }
                }
            engine.initialize(sessionContext)
            val dispatched = mutableListOf<ToolCall>()
            val deltas = mutableListOf<String>()
            val result =
                engine.processTurn(UserTurnInput("Use music"), deltas::add) { call ->
                    dispatched.add(call)
                    Json.parseToJsonElement("""{"message":"Switched to media"}""")
                }
            assertEquals(Json.parseToJsonElement("""{"service":"media"}"""), dispatched.single().args)
            assertEquals(Role.USER, requests.first().role)
            assertEquals(Role.TOOL, requests.last().role)
            val response =
                requests
                    .last()
                    .contents.contents
                    .single() as Content.ToolResponse
            assertEquals("switch_service", response.name)
            assertEquals("Switched to media", (response.response as com.google.gson.JsonObject)["message"].asString)
            assertEquals("Sure. Switched to media.", deltas.joinToString(""))
            assertEquals(deltas.joinToString(""), result.assistantText)
            assertEquals(dispatched, result.toolCalls)
            engine.closeAndJoin()
        }

    @Test
    fun `invalid argument batch executes nothing and corrected call uses unchanged arguments`() =
        runTest {
            var round = 0
            val engine =
                engine { request ->
                    when (round++) {
                        0 -> {
                            flowOf(
                                Message.model(
                                    toolCalls =
                                        callMessage().toolCalls +
                                            callMessage(args = mapOf("query" to "private-value")).toolCalls,
                                ),
                            )
                        }

                        1 -> {
                            val feedback = request.toString()
                            assertTrue(feedback.contains("query"))
                            assertTrue(feedback.contains("not executed"))
                            flowOf(callMessage(args = mapOf("service" to "corrected")))
                        }

                        else -> {
                            flowOf(Message.model("Done"))
                        }
                    }
                }
            engine.initialize(sessionContext)
            val calls = mutableListOf<ToolCall>()
            val result =
                engine.processTurn(UserTurnInput("Switch"), {}) {
                    calls.add(it)
                    JsonObject(emptyMap())
                }
            assertEquals(1, calls.size)
            assertEquals(Json.parseToJsonElement("""{"service":"corrected"}"""), calls.single().args)
            assertEquals(calls, result.toolCalls)
            assertEquals("Done", result.assistantText)
            engine.closeAndJoin()
        }

    @Test
    fun `schema replacement changes validation and malformed schemas fail before native preparation`() =
        runTest {
            val configs = mutableListOf<ConversationConfig>()
            var round = 0
            val engine =
                engine(configs) {
                    when (round++) {
                        0 -> flowOf(callMessage(args = mapOf("service" to "old")))
                        1 -> flowOf(callMessage(args = mapOf("service" to "new")))
                        else -> flowOf(Message.model("Done"))
                    }
                }
            val malformed =
                sessionContext.copy(
                    tools =
                        listOf(
                            ToolDescriptor(
                                name = "switch_service",
                                inputSchema = Json.parseToJsonElement("""{"required":"service"}"""),
                            ),
                        ),
                )
            assertTrue(runCatching { engine.initialize(malformed) }.isFailure)
            assertTrue(configs.isEmpty())
            assertFalse(engine.isModelLoaded)
            engine.initialize(sessionContext)
            val replacement =
                sessionContext.copy(
                    tools =
                        listOf(
                            ToolDescriptor(
                                name = "switch_service",
                                inputSchema =
                                    Json.parseToJsonElement(
                                        """{"properties":{"service":{"enum":["new"]}},"required":["service"]}""",
                                    ),
                            ),
                        ),
                )
            engine.resetConversation(replacement)
            val calls = mutableListOf<ToolCall>()
            engine.processTurn(UserTurnInput("Switch"), {}) {
                calls.add(it)
                null
            }
            assertEquals(1, calls.size)
            assertEquals(Json.parseToJsonElement("""{"service":"new"}"""), calls.single().args)
            engine.closeAndJoin()
        }

    @Test
    fun `unknown native tool receives correction feedback without dispatch`() =
        runTest {
            var round = 0
            val engine =
                engine { request ->
                    when (round++) {
                        0 -> {
                            flowOf(callMessage("unknown"))
                        }

                        1 -> {
                            assertTrue(request.toString().contains("Unknown tool"))
                            flowOf(callMessage())
                        }

                        else -> {
                            flowOf(Message.model("Done"))
                        }
                    }
                }
            engine.initialize(sessionContext)
            val calls = mutableListOf<ToolCall>()
            engine.processTurn(UserTurnInput("Switch"), {}) {
                calls.add(it)
                null
            }
            assertEquals(listOf("switch_service"), calls.map { it.name })
            engine.closeAndJoin()
        }

    @Test
    fun `native arguments preserve nested arrays booleans null and number precision`() =
        runTest {
            val args =
                mapOf(
                    "options" to
                        mapOf(
                            "ids" to listOf(JsonParser.parseString("9007199254740993").asNumber, 2),
                            "enabled" to true,
                            "note" to null,
                            "ratio" to JsonParser.parseString("0.1234567890123456789").asNumber,
                        ),
                )
            val engine =
                engine { message ->
                    if (message.role ==
                        Role.USER
                    ) {
                        flowOf(callMessage("configure", args))
                    } else {
                        flowOf(Message.model("Done"))
                    }
                }
            engine.initialize(
                sessionContext.copy(
                    tools = listOf(ToolDescriptor(name = "configure", inputSchema = Json.parseToJsonElement("{}"))),
                ),
            )
            var dispatched: ToolCall? = null
            engine.processTurn(UserTurnInput("Configure it"), {}) {
                dispatched = it
                JsonObject(emptyMap())
            }
            assertEquals(
                Json.parseToJsonElement(
                    """{"options":{"ids":[9007199254740993,2],"enabled":true,"note":null,""" +
                        """"ratio":0.1234567890123456789}}""",
                ),
                dispatched?.args,
            )
            engine.closeAndJoin()
        }

    @Test
    fun `all batch names are validated before any dispatch and repeated rounds are bounded`() =
        runTest {
            var dispatches = 0
            val invalid =
                engine {
                    flowOf(Message.model(toolCalls = callMessage().toolCalls + callMessage("unknown").toolCalls))
                }
            invalid.initialize(sessionContext)
            val failure =
                runCatching {
                    invalid.processTurn(UserTurnInput("Run"), {}) {
                        dispatches++
                        null
                    }
                }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("round limit"))
            assertEquals(0, dispatches)
            invalid.closeAndJoin()
            val looping = engine { flowOf(callMessage()) }
            looping.initialize(sessionContext)
            val loop =
                runCatching {
                    looping.processTurn(UserTurnInput("Run"), {}) {
                        dispatches++
                        null
                    }
                }.exceptionOrNull()
            assertTrue(loop?.message.orEmpty().contains("round limit"))
            assertTrue(dispatches in 1..16)
            looping.closeAndJoin()
        }

    @Test
    fun `batch calls preserve order and empty args and feed errors back`() =
        runTest {
            val engine =
                engine { request ->
                    if (request.role == Role.USER) {
                        flowOf(
                            Message.model(
                                toolCalls = callMessage(args = emptyMap()).toolCalls + callMessage().toolCalls,
                            ),
                        )
                    } else {
                        flowOf(Message.model("The service rejected the change."))
                    }
                }
            engine.initialize(
                sessionContext.copy(
                    tools =
                        listOf(
                            ToolDescriptor(name = "switch_service", inputSchema = Json.parseToJsonElement("{}")),
                        ),
                ),
            )
            val calls = mutableListOf<ToolCall>()
            val result =
                engine.processTurn(UserTurnInput("Run twice"), {}) {
                    calls.add(it)
                    Json.parseToJsonElement("""{"error":{"code":42}}""")
                }
            assertEquals(2, calls.size)
            assertEquals(JsonObject(emptyMap()), calls.first().args)
            assertEquals("The service rejected the change.", result.assistantText)
            engine.closeAndJoin()
        }

    @Test
    fun `closing from a tool callback does not dispatch remaining calls or resume inference`() =
        runTest {
            var generations = 0
            val engine =
                engine {
                    generations++
                    flowOf(Message.model(toolCalls = callMessage().toolCalls + callMessage().toolCalls))
                }
            engine.initialize(sessionContext)
            val result =
                engine.processTurn(UserTurnInput("Disconnect"), {}) {
                    engine.close()
                    null
                }
            assertEquals(1, generations)
            assertEquals(1, result.toolCalls.size)
            engine.closeAndJoin()
        }

    @Test
    fun `plain text and Markdown never dispatch tools from a keyword or marker`() =
        runTest {
            val text = "Explain switch_service and `TOOL_CALL:`, without running it."
            val engine = engine { flowOf(Message.model(text)) }
            engine.initialize(sessionContext)
            val result = engine.processTurn(UserTurnInput("Explain the tool"), {}) { error("No structured call") }
            assertEquals(text, result.assistantText)
            assertTrue(result.toolCalls.isEmpty())
            engine.closeAndJoin()
        }

    @Test
    fun `close while native tool generation drains without dispatch`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var generations = 0
            var dispatches = 0
            val engine =
                engine {
                    flow {
                        generations++
                        entered.complete(Unit)
                        finish.await()
                        emit(callMessage())
                    }
                }
            engine.initialize(sessionContext)
            val turn =
                launch {
                    engine.processTurn(UserTurnInput("Run"), {}) {
                        dispatches++
                        null
                    }
                }
            entered.await()
            engine.close()
            finish.complete(Unit)
            turn.join()
            engine.closeAndJoin()
            assertEquals(0, dispatches)
            assertEquals(1, generations)
            val summary = ShadowLog.getLogsForTag("LocalLlmEngine").single { it.msg.startsWith("local_turn ") }.msg
            assertTrue(summary.contains("outcome=invalidated"))
        }

    @Test
    fun `preamble does not hide empty conclusion after a tool`() =
        runTest {
            val engine =
                engine { request ->
                    if (request.role == Role.USER) {
                        flowOf(Message.model("Sure."), callMessage())
                    } else {
                        flowOf(Message.model(" "))
                    }
                }
            engine.initialize(sessionContext)
            val failure =
                runCatching {
                    engine.processTurn(UserTurnInput("Run"), {}, { null })
                }.exceptionOrNull()
            assertEquals("Local model returned an empty response", failure?.message)
            engine.closeAndJoin()
        }

    @Test
    fun `empty response surfaces without tool dispatch`() =
        runTest {
            val engine = engine { flowOf(Message.model("")) }
            engine.initialize(sessionContext)
            val failure =
                runCatching {
                    engine.processTurn(UserTurnInput("Hello"), {}) { error("No call") }
                }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            engine.closeAndJoin()
        }

    @Test
    fun `context updates preserve native prefill and enter the next user turn`() =
        runTest {
            val configs = mutableListOf<ConversationConfig>()
            val messages = mutableListOf<Message>()
            val engine =
                engine(configs) { message ->
                    messages += message
                    flowOf(Message.model("Ready."))
                }
            engine.initialize(sessionContext.copy(serviceContextText = "Current item: alpha"))
            engine.processTurn(UserTurnInput("Remember cedar"), {}) { error("No tool") }
            engine.injectContext("Current item: beta")
            engine.processTurn(UserTurnInput("Recall it"), {}) { error("No tool") }
            assertEquals(1, configs.size)
            assertTrue(messages.last().toString().contains("beta"))
            assertTrue(messages.last().toString().contains("Recall it"))
            engine.processTurn(UserTurnInput("Continue"), {}) { error("No tool") }
            assertFalse(messages.last().toString().contains("beta"))
            engine.closeAndJoin()
        }

    @Test
    fun `focus context waits for the original tool result and preserves native history`() =
        runTest {
            val configs = mutableListOf<ConversationConfig>()
            val messages = mutableListOf<Message>()
            val toolStarted = CompletableDeferred<Unit>()
            val finishTool = CompletableDeferred<Unit>()
            var first = true
            val engine =
                engine(configs) { message ->
                    messages += message
                    if (first) {
                        first = false
                        flowOf(callMessage())
                    } else {
                        flowOf(Message.model("cedar"))
                    }
                }
            engine.initialize(sessionContext)
            val turn =
                launch {
                    engine.processTurn(UserTurnInput("Remember cedar and call A"), {}) {
                        toolStarted.complete(Unit)
                        finishTool.await()
                        Json.parseToJsonElement("""{"source":"A","fact":"cedar"}""")
                    }
                }
            toolStarted.await()
            val update = launch { engine.injectContext("Selected server B; original call remains on A") }
            finishTool.complete(Unit)
            turn.join()
            update.join()
            engine.processTurn(UserTurnInput("Recall cedar on B"), {}) { error("No new tool") }
            assertEquals(listOf(Role.USER, Role.TOOL, Role.USER), messages.map { it.role })
            assertTrue(messages[1].toString().contains("cedar"))
            assertFalse(messages[1].toString().contains("Selected server B"))
            assertTrue(messages[2].toString().contains("Selected server B"))
            assertEquals(1, configs.size)
            engine.closeAndJoin()
        }

    @Test
    fun `context queued behind a retired turn cannot reach the retained engine`() =
        runTest {
            val messages = mutableListOf<Message>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var first = true
            val engine =
                engine { message ->
                    messages += message
                    if (first) {
                        first = false
                        flowOf(callMessage())
                    } else {
                        flowOf(Message.model("Fresh turn"))
                    }
                }
            engine.initialize(sessionContext)
            val turn =
                launch {
                    engine.processTurn(UserTurnInput("Hold the old turn"), {}) {
                        entered.complete(Unit)
                        release.await()
                        null
                    }
                }
            entered.await()
            val update =
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    engine.injectContext("retired-account-context")
                }
            engine.invalidateTurns()
            release.complete(Unit)
            turn.join()
            update.join()
            engine.processTurn(UserTurnInput("Fresh user turn"), {}) { error("No fresh tool") }
            assertFalse(messages.last().toString().contains("retired-account-context"))
            engine.closeAndJoin()
        }

    @Test
    fun `missing model reports installation guidance and native linkage errors are not masked`() =
        runTest {
            val engine =
                LocalLlmEngine(RuntimeEnvironment.getApplication(), { context, config ->
                    LiteRtLmRunner(context, config, "/nonexistent/path/model.litertlm")
                })
            val failure = runCatching { engine.initialize(sessionContext) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("make android-push-model"))
            assertFalse(engine.isModelLoaded)
            engine.closeAndJoin()
            val model = File.createTempFile("invalid-local-model", ".litertlm")
            try {
                val linkage =
                    runCatching {
                        LiteRtLmRunner(RuntimeEnvironment.getApplication(), ConversationConfig(), model.absolutePath)
                    }.exceptionOrNull()
                assertTrue(linkage is LinkageError)
            } finally {
                model.delete()
            }
        }

    @Test
    fun `coroutine runtime provides LiteRT asynchronous callback close ABI`() {
        val close =
            SendChannel::class.java.getDeclaredMethod(
                "close\$default",
                SendChannel::class.java,
                Throwable::class.java,
                Integer.TYPE,
                Any::class.java,
            )
        assertTrue(Modifier.isStatic(close.modifiers))
        val channel = Channel<Unit>()
        assertEquals(true, close.invoke(null, channel, null, 1, null))
        assertFalse(channel.close())
    }

    @Test
    fun `close is immediate and cleanup waits for turn and ignores queued context updates`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var closes = 0
            val engine =
                engine(onClose = { closes++ }) {
                    flow {
                        entered.complete(Unit)
                        finish.await()
                        emit(Message.model("Done"))
                    }
                }
            engine.initialize(sessionContext)
            val turn = launch { engine.processTurn(UserTurnInput("Hello"), {}, { null }) }
            entered.await()
            val update = launch { engine.injectContext("Late context") }
            val disposal = launch { engine.closeAndJoin() }
            testScheduler.runCurrent()
            assertFalse(engine.isModelLoaded)
            assertFalse(disposal.isCompleted)
            assertEquals(0, closes)
            finish.complete(Unit)
            turn.join()
            update.join()
            disposal.join()
            engine.injectContext("Closed context")
            assertEquals(1, closes)
        }

    private fun lifecycleRunner(onClose: () -> Unit) =
        object : EdgeLlmRunner {
            override val isModelLoaded = true
            override val backendName = "test"

            override suspend fun generateStream(message: Message): Flow<Message> = error("No turn")

            override fun replaceConversation(config: ConversationConfig) = Unit

            override fun close() = onClose()
        }

    @Test
    fun `failed reinitialization releases old model and leaves no ready session`() =
        runTest {
            var closes = 0
            var creations = 0
            val engine =
                LocalLlmEngine(
                    RuntimeEnvironment.getApplication(),
                    { _, _ ->
                        check(creations++ == 0) { "Native allocation failed" }
                        lifecycleRunner { closes++ }
                    },
                    StandardTestDispatcher(testScheduler),
                )
            engine.initialize(sessionContext)
            val failure = runCatching { engine.initialize(sessionContext) }.exceptionOrNull()
            assertEquals("Native allocation failed", failure?.message)
            assertFalse(engine.isModelLoaded)
            assertEquals(1, closes)
            engine.closeAndJoin()
            assertEquals(1, closes)
        }

    @Test
    fun `close during allocation drains prepared model exactly once`() =
        runTest {
            var closes = 0
            var engineRef: LocalLlmEngine? = null
            val engine =
                LocalLlmEngine(
                    RuntimeEnvironment.getApplication(),
                    { _, _ ->
                        engineRef?.close()
                        lifecycleRunner { closes++ }
                    },
                    StandardTestDispatcher(testScheduler),
                )
            engineRef = engine
            assertTrue(runCatching { engine.initialize(sessionContext) }.exceptionOrNull() is IllegalStateException)
            assertFalse(engine.isModelLoaded)
            engine.closeAndJoin()
            engine.closeAndJoin()
            assertEquals(1, closes)
        }

    @Test
    fun `inference and tool dispatch failures remain visible`() =
        runTest {
            val native = engine { flow { error("Native inference failed") } }
            native.initialize(sessionContext)
            assertEquals(
                "Native inference failed",
                runCatching { native.processTurn(UserTurnInput("Hi"), {}, { null }) }.exceptionOrNull()?.message,
            )
            native.closeAndJoin()
            val dispatch = engine { flowOf(callMessage()) }
            dispatch.initialize(sessionContext)
            assertEquals(
                "Dispatch failed",
                runCatching {
                    dispatch.processTurn(UserTurnInput("Run"), {}, { error("Dispatch failed") })
                }.exceptionOrNull()?.message,
            )
            dispatch.closeAndJoin()
        }

    @Test
    fun `cancellation during allocation cleans up and disposal failure remains visible`() =
        runTest {
            var closes = 0
            var initialization: Job? = null
            val engine =
                LocalLlmEngine(
                    RuntimeEnvironment.getApplication(),
                    { _, _ ->
                        initialization?.cancel()
                        object : EdgeLlmRunner {
                            override val isModelLoaded = true
                            override val backendName = "test"

                            override suspend fun generateStream(message: Message): Flow<Message> = error("No turn")

                            override fun replaceConversation(config: ConversationConfig) = Unit

                            override fun close() {
                                closes++
                            }
                        }
                    },
                    StandardTestDispatcher(testScheduler),
                )
            initialization = launch { engine.initialize(sessionContext) }
            testScheduler.runCurrent()
            assertTrue(initialization.isCancelled)
            assertEquals(1, closes)
            engine.closeAndJoin()
            val broken = engine(onClose = { error("Native deletion failed") })
            broken.initialize(sessionContext)
            assertEquals("Native deletion failed", runCatching { broken.closeAndJoin() }.exceptionOrNull()?.message)
        }

    @Test
    fun `lfm model configures 16K context disciplined sampler and excludes TPU backend`() =
        runTest {
            val configs = mutableListOf<ConversationConfig>()
            val engine =
                LocalLlmEngine(
                    RuntimeEnvironment.getApplication(),
                    { _, config ->
                        configs.add(config)
                        object : EdgeLlmRunner {
                            override val isModelLoaded = true
                            override val backendName = "GPU"

                            override suspend fun generateStream(message: Message) = flowOf(Message.model("Ready."))

                            override fun replaceConversation(config: ConversationConfig) {
                                configs.add(config)
                            }

                            override fun close() = Unit
                        }
                    },
                    model = LocalLlmModel.LFM_2_5_2_6B,
                )
            engine.initialize(sessionContext)
            val config = configs.single()
            val sampler = checkNotNull(config.samplerConfig)
            assertEquals(50, sampler.topK)
            assertEquals(1.0, sampler.topP, 0.0)
            assertEquals(0.1, sampler.temperature, 0.0)
            assertEquals(16_384, LocalLlmModel.LFM_2_5_1_2B.maxNumTokens)
            assertEquals(listOf("GPU", "CPU"), backendsFor(LocalLlmModel.LFM_2_5_1_2B).map { it.name })
            assertEquals(16_384, LocalLlmModel.LFM_2_5_2_6B.maxNumTokens)
            val backends = backendsFor(LocalLlmModel.LFM_2_5_2_6B)
            assertEquals(listOf("GPU", "CPU"), backends.map { it.name })
            engine.closeAndJoin()
        }
}
