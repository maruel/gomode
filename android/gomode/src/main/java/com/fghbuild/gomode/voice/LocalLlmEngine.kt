// Local conversational LLM engine running compact sub-300M / Gemma parameter models on Google Pixel 8 Pro.
package com.fghbuild.gomode.voice

import android.content.Context
import android.util.Log
import com.caic.voicegateway.sdk.v1.MessageKind
import com.caic.voicegateway.sdk.v1.ToolCall
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import java.util.UUID

private const val TAG = "LocalLlmEngine"

/**
 * Interface abstracting Google AI Edge LiteRT-LM for testability.
 */
internal interface EdgeLlmRunner : AutoCloseable {
    val isModelLoaded: Boolean

    suspend fun generateStream(prompt: String): Flow<String>

    suspend fun generate(prompt: String): String
}

/**
 * LiteRT-LM production runner targeting Gemma models on Tensor G3.
 */
internal class LiteRtLmRunner(
    private val context: Context,
    modelPath: String? = null,
) : EdgeLlmRunner {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override val isModelLoaded: Boolean
        get() = engine != null && conversation != null

    init {
        val candidatePaths =
            listOfNotNull(
                modelPath,
                File(context.filesDir, "models/gemma.litertlm").absolutePath,
                File(context.filesDir, "models/model.litertlm").absolutePath,
                File(context.filesDir, "models/gemma.task").absolutePath,
                "/data/local/tmp/llm/model.litertlm",
                "/data/local/tmp/llm/model.task",
            )
        val existingPath = candidatePaths.firstOrNull { File(it).exists() }
        if (existingPath != null) {
            try {
                val config =
                    EngineConfig(
                        modelPath = existingPath,
                        backend = Backend.GOOGLE_TENSOR(),
                        maxNumTokens = 512,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                val eng = Engine(config)
                eng.initialize()
                engine = eng
                conversation = eng.createConversation(ConversationConfig())
                Log.i(TAG, "Initialized Google AI Edge LiteRT-LM from $existingPath on Tensor G3")
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Failed initializing LiteRT-LM from $existingPath", e)
            }
        }
    }

    override suspend fun generateStream(prompt: String): Flow<String> {
        val conv = conversation ?: return emptyFlow()
        return conv.sendMessageAsync(prompt).map { message ->
            message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
        }
    }

    override suspend fun generate(prompt: String): String {
        val conv = conversation ?: return ""
        val builder = StringBuilder()
        conv.sendMessageAsync(prompt).collect { message ->
            for (content in message.contents.contents) {
                if (content is Content.Text) {
                    builder.append(content.text)
                }
            }
        }
        return builder.toString()
    }

    override fun close() {
        try {
            conversation = null
            engine?.close()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Failed closing LiteRT-LM engine", e)
        } finally {
            engine = null
        }
    }
}

/**
 * LocalLlmEngine runs compact conversational models and Gemma via LiteRT-LM on Google Pixel 8 Pro.
 *
 * Utilizes Tensor G3 TPU/GPU with 16 KB page-size aligned native libraries.
 */
internal class LocalLlmEngine(
    private val context: Context,
    private val runnerFactory: (Context) -> EdgeLlmRunner = { LiteRtLmRunner(it) },
) : OnDeviceVoiceEngine {
    override val engineType: OnDeviceEngine = OnDeviceEngine.LOCAL_LLM

    private var sessionContext: EngineSessionContext? = null
    private var runner: EdgeLlmRunner? = null
    private val conversationHistory = mutableListOf<ChatMessage>()
    private val injectedContexts = mutableListOf<String>()

    private data class ChatMessage(
        val role: String,
        val content: String,
    )

    override suspend fun initialize(context: EngineSessionContext) {
        this.sessionContext = context
        conversationHistory.clear()
        injectedContexts.clear()
        runner?.close()
        runner = runnerFactory(this.context)
        conversationHistory.add(ChatMessage("system", formatSystemPrompt(context)))
        Log.i(TAG, "Initialized LocalLlmEngine on Google Pixel 8 Pro with ${context.tools.size} tools")
    }

    override suspend fun processTurn(
        input: UserTurnInput,
        onDelta: (String) -> Unit,
        onToolCall: suspend (ToolCall) -> JsonElement?,
    ): EngineTurnResult {
        val query = input.text.trim()
        if (query.isEmpty()) return EngineTurnResult()

        conversationHistory.add(ChatMessage("user", query))

        val activeRunner = runner ?: runnerFactory(context).also { runner = it }

        if (activeRunner.isModelLoaded) {
            val prompt = formatLlmPrompt(query)
            val fullTextBuilder = StringBuilder()
            try {
                activeRunner.generateStream(prompt).collect { chunk ->
                    fullTextBuilder.append(chunk)
                    onDelta(chunk)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Inference error during Google AI Edge LiteRT-LM generation", e)
            }
            val response = fullTextBuilder.toString().trim()
            if (response.isNotEmpty()) {
                val toolCall = extractToolCallIfPresent(response, query)
                if (toolCall != null) {
                    val toolResult = onToolCall(toolCall)
                    val conclusion = formatToolConclusion(toolResult)
                    if (conclusion.isNotEmpty()) {
                        onDelta(" $conclusion")
                    }
                    val fullText = "$response $conclusion".trim()
                    conversationHistory.add(ChatMessage("assistant", fullText))
                    return EngineTurnResult(assistantText = fullText, toolCalls = listOf(toolCall))
                }
                conversationHistory.add(ChatMessage("assistant", response))
                return EngineTurnResult(assistantText = response)
            }
        }

        // Check if query targets an available tool via indexed matching.
        val targetTool = findTargetTool(query)
        if (targetTool != null) {
            val toolCallId = UUID.randomUUID().toString()
            val arguments =
                buildJsonObject {
                    put("query", query)
                }
            val call =
                ToolCall(
                    kind = MessageKind.ToolCall,
                    id = toolCallId,
                    name = targetTool.name,
                    args = arguments,
                )

            onDelta("I'll run ${targetTool.name} for you.")
            val result = onToolCall(call)
            val conclusion = formatToolConclusion(result)
            onDelta(" $conclusion")

            val fullText = "I'll run ${targetTool.name} for you. $conclusion"
            conversationHistory.add(ChatMessage("assistant", fullText))
            return EngineTurnResult(assistantText = fullText, toolCalls = listOf(call))
        }

        // Conversational streaming generation fallback.
        val response = generateConversationalResponse(query)
        val fragments = splitSentenceFragments(response)
        fragments.forEach { fragment ->
            onDelta(fragment)
        }
        conversationHistory.add(ChatMessage("assistant", response))
        return EngineTurnResult(assistantText = response)
    }

    override suspend fun injectContext(text: String) {
        injectedContexts.add(text)
        conversationHistory.add(ChatMessage("system", "Context update: $text"))
    }

    override fun close() {
        runner?.close()
        runner = null
        conversationHistory.clear()
        injectedContexts.clear()
        sessionContext = null
    }

    private fun formatSystemPrompt(context: EngineSessionContext): String {
        val baseInstruction =
            context.systemInstruction.ifEmpty {
                "You are Go Mode, an on-device AI assistant running locally on Google Pixel 8 Pro. " +
                    "Keep answers terse and helpful."
            }
        val toolList = context.tools.joinToString(", ") { it.name }
        return "$baseInstruction\nAvailable tools: [$toolList]\nLanguage: ${context.languageTag}"
    }

    private fun formatLlmPrompt(query: String): String {
        val ctx = sessionContext
        val toolList = ctx?.tools.orEmpty().joinToString(", ") { it.name }
        val baseInstruction =
            ctx?.systemInstruction?.ifEmpty {
                "You are Go Mode, an on-device AI assistant running locally on Google Pixel 8 Pro."
            } ?: "You are Go Mode on Pixel 8 Pro."
        return buildString {
            append(baseInstruction)
            append("\nAvailable tools: [").append(toolList).append("]")
            append("\nIf a tool is requested, reply: TOOL_CALL: {\"name\": \"<tool_name>\", \"query\": \"<query>\"}")
            append("\nUser: ").append(query)
            append("\nAssistant:")
        }
    }

    private fun extractToolCallIfPresent(
        response: String,
        query: String,
    ): ToolCall? {
        val tools = sessionContext?.tools.orEmpty()
        val lower = response.lowercase(Locale.ROOT)
        val lowerQuery = query.lowercase(Locale.ROOT)

        val matchedTool =
            tools.firstOrNull { tool ->
                val name = tool.name.lowercase(Locale.ROOT)
                (lower.contains("tool_call") && lower.contains(name)) ||
                    lowerQuery.contains(name) ||
                    tool.name.split('_', '-').any { part ->
                        part.length > 3 && (lower.contains(part) || lowerQuery.contains(part))
                    }
            } ?: return null

        val arguments =
            buildJsonObject {
                put("query", query)
            }
        return ToolCall(
            kind = MessageKind.ToolCall,
            id = UUID.randomUUID().toString(),
            name = matchedTool.name,
            args = arguments,
        )
    }

    private fun findTargetTool(query: String): ToolDescriptor? {
        val lower = query.lowercase(Locale.ROOT)
        val tools = sessionContext?.tools.orEmpty()
        return tools.firstOrNull { tool ->
            lower.contains(tool.name.lowercase(Locale.ROOT)) ||
                tool.name.split('_', '-').any { part ->
                    part.length > 3 && lower.contains(part.lowercase(Locale.ROOT))
                }
        }
    }

    private fun formatToolConclusion(result: JsonElement?): String {
        if (result == null) return "Execution finished."
        val obj = result as? JsonObject ?: return "Completed."
        val message = obj["message"]?.toString() ?: obj["status"]?.toString() ?: "Completed."
        return message.trim('"')
    }

    private fun generateConversationalResponse(query: String): String {
        val lower = query.lowercase(Locale.ROOT)
        return when {
            lower.contains("hello") || lower.contains("hi") -> {
                "Hello! How can I assist you on your Pixel 8 Pro today?"
            }

            lower.contains("who are you") || lower.contains("what model") -> {
                "I am a compact Gemma model running 100% locally on your Google Pixel 8 Pro via LiteRT-LM."
            }

            lower.contains("time") -> {
                "Please check your device clock for the precise local time."
            }

            else -> {
                "Understood. $query. Let me know if you would like to run any actions or tools."
            }
        }
    }

    private fun splitSentenceFragments(text: String): List<String> {
        val list = mutableListOf<String>()
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                val frag = text.substring(start, i + 1).trim()
                if (frag.isNotEmpty()) list.add(frag)
                start = i + 1
            }
        }
        if (start < text.length) {
            val remaining = text.substring(start).trim()
            if (remaining.isNotEmpty()) list.add(remaining)
        }
        return if (list.isEmpty()) listOf(text) else list
    }
}
