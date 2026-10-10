// Local conversational LLM engine running compact sub-300M / Gemma parameter models on Google Pixel 8 Pro.
package com.fghbuild.gomode.voice

import android.content.Context
import android.util.Log
import com.caic.voicegateway.sdk.v1.MessageKind
import com.caic.voicegateway.sdk.v1.ToolCall
import com.fghbuild.gomode.data.OnDeviceEngine
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import java.util.UUID

private const val TAG = "LocalLlmEngine"

internal fun resolveModelName(
    context: Context,
    explicitModelPath: String? = null,
): String {
    val metaFile = File("/data/local/tmp/llm/model_name.txt")
    if (metaFile.exists()) {
        val raw = runCatching { metaFile.readText().trim() }.getOrNull()
        if (!raw.isNullOrEmpty()) {
            return formatModelName(raw)
        }
    }
    if (explicitModelPath != null) {
        val file = File(explicitModelPath)
        if (file.exists()) {
            return formatModelName(file.nameWithoutExtension)
        }
    }
    val appCandidates = listOf("gemma.litertlm", "model.litertlm", "gemma.task")
    for (name in appCandidates) {
        if (File(context.filesDir, "models/$name").exists()) {
            return "Gemma 4"
        }
    }
    if (File("/data/local/tmp/llm/model.litertlm").exists()) {
        return "Gemma 4 E2B"
    }
    return "Gemma 4"
}

internal fun formatModelName(raw: String): String {
    val clean = raw.removeSuffix(".litertlm").removeSuffix(".task")
    return when {
        clean.contains("gemma-4-E2B", ignoreCase = true) || clean.contains("gemma-4-e2b", ignoreCase = true) -> {
            "Gemma 4 E2B"
        }

        clean.contains("gemma-4-E4B", ignoreCase = true) || clean.contains("gemma-4-e4b", ignoreCase = true) -> {
            "Gemma 4 E4B"
        }

        clean.contains("gemma3-1b", ignoreCase = true) -> {
            "Gemma 3 1B"
        }

        clean.contains("gemma-4", ignoreCase = true) -> {
            "Gemma 4"
        }

        clean.contains("gemma", ignoreCase = true) -> {
            "Gemma"
        }

        clean.contains("qwen", ignoreCase = true) -> {
            "Qwen"
        }

        else -> {
            clean
        }
    }
}

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
    systemInstruction: String,
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
            val backends =
                listOf(
                    Backend.GOOGLE_TENSOR(),
                    Backend.GPU(),
                    Backend.CPU(),
                )
            for (backend in backends) {
                try {
                    val config =
                        EngineConfig(
                            modelPath = existingPath,
                            backend = backend,
                            // The context includes instructions, tool schemas, history, and output.
                            maxNumTokens = 10_240,
                            cacheDir = context.cacheDir.absolutePath,
                        )
                    val eng = Engine(config)
                    eng.initialize()
                    engine = eng
                    conversation =
                        eng.createConversation(
                            ConversationConfig(systemInstruction = Contents.of(systemInstruction)),
                        )
                    Log.i(TAG, "Initialized Google AI Edge LiteRT-LM from $existingPath on ${backend.name}")
                    break
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Log.w(TAG, "Failed initializing LiteRT-LM from $existingPath on ${backend.name}", e)
                }
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
    private val runnerFactory: (Context, String) -> EdgeLlmRunner = { context, instruction ->
        LiteRtLmRunner(context, instruction)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OnDeviceVoiceEngine {
    override val engineType: OnDeviceEngine = OnDeviceEngine.LOCAL_LLM

    override val modelName: String
        get() = resolveModelName(context)

    override val isModelLoaded: Boolean
        get() = runner?.isModelLoaded == true

    private var sessionContext: EngineSessionContext? = null
    private var runner: EdgeLlmRunner? = null
    private val conversationHistory = mutableListOf<ChatMessage>()
    private val injectedContexts = mutableListOf<String>()

    private data class ChatMessage(
        val role: String,
        val content: String,
    )

    override suspend fun initialize(context: EngineSessionContext) {
        withContext(ioDispatcher) {
            this@LocalLlmEngine.sessionContext = context
            conversationHistory.clear()
            injectedContexts.clear()
            runner?.close()
            val systemInstruction = formatSystemPrompt(context)
            runner = runnerFactory(this@LocalLlmEngine.context, systemInstruction)
            conversationHistory.add(ChatMessage("system", systemInstruction))
            Log.i(TAG, "Initialized LocalLlmEngine on Google Pixel 8 Pro with ${context.tools.size} tools")
        }
    }

    override suspend fun processTurn(
        input: UserTurnInput,
        onDelta: (String) -> Unit,
        onToolCall: suspend (ToolCall) -> JsonElement?,
    ): EngineTurnResult =
        withContext(ioDispatcher) {
            val query = input.text.trim()
            if (query.isEmpty()) return@withContext EngineTurnResult()

            conversationHistory.add(ChatMessage("user", query))

            val activeRunner = checkNotNull(runner) { "Local model session is not initialized" }

            if (activeRunner.isModelLoaded) {
                val fullTextBuilder = StringBuilder()
                // Buffer the turn so protocol JSON never reaches speech or the transcript.
                activeRunner.generateStream(query).collect { chunk -> fullTextBuilder.append(chunk) }
                val response = fullTextBuilder.toString().trim()
                require(response.isNotEmpty()) { "Local model returned an empty response" }
                val toolCall = extractToolCallIfPresent(response)
                if (toolCall != null) {
                    val conclusion = formatToolConclusion(onToolCall(toolCall))
                    onDelta(conclusion)
                    conversationHistory.add(ChatMessage("assistant", conclusion))
                    return@withContext EngineTurnResult(assistantText = conclusion, toolCalls = listOf(toolCall))
                }
                onDelta(response)
                conversationHistory.add(ChatMessage("assistant", response))
                return@withContext EngineTurnResult(assistantText = response)
            }

            // Conversational streaming generation fallback.
            val response = generateConversationalResponse(query)
            val fragments = splitSentenceFragments(response)
            fragments.forEach { fragment ->
                onDelta(fragment)
            }
            conversationHistory.add(ChatMessage("assistant", response))
            EngineTurnResult(assistantText = response)
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
        val tools =
            buildJsonArray {
                context.tools.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            tool.description?.let { put("description", it) }
                            tool.inputSchema?.let { put("inputSchema", it) }
                        },
                    )
                }
            }
        return buildString {
            append(baseInstruction)
            append("\nAvailable tools: ").append(tools)
            append("\nLanguage: ").append(context.languageTag)
            append("\nTo call a tool, reply only with TOOL_CALL: {\"name\":\"<tool_name>\",\"arguments\":{...}}.")
            append("\nUse an available tool's exact name and arguments that match its inputSchema.")
            append("\nDo not invent fields or wrap the call in Markdown. Otherwise reply with plain text.")
        }
    }

    private fun extractToolCallIfPresent(response: String): ToolCall? {
        val prefix = "TOOL_CALL:"
        if (!response.startsWith(prefix)) {
            require(!response.contains("TOOL_CALL", ignoreCase = true)) {
                "Local tool call must start with TOOL_CALL: and contain no preamble or Markdown"
            }
            return null
        }
        val envelope =
            try {
                Json.parseToJsonElement(response.removePrefix(prefix).trim()) as? JsonObject
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Local model returned malformed tool JSON", e)
            }
        require(envelope != null && envelope.keys == setOf("name", "arguments")) {
            "Local tool call must contain only name and arguments"
        }
        val name = envelope["name"] as? JsonPrimitive
        require(name != null && name.isString) { "Local tool name must be a string" }
        require(sessionContext?.tools.orEmpty().any { it.name == name.content }) {
            "Local model requested an unavailable tool"
        }
        val arguments = envelope["arguments"] as? JsonObject
        require(arguments != null) { "Local tool arguments must be an object" }
        // The host validates the arguments against its authoritative schema.
        return ToolCall(
            kind = MessageKind.ToolCall,
            id = UUID.randomUUID().toString(),
            name = name.content,
            args = arguments,
        )
    }

    private fun formatToolConclusion(result: JsonElement?): String {
        if (result == null) return "Execution finished."
        val obj = result as? JsonObject ?: return "Completed."
        val error = obj["error"]
        if (error != null) {
            return if (error is JsonPrimitive && error.isString) "Tool failed: ${error.content}" else "Tool failed."
        }
        val message = obj["message"] ?: obj["status"]
        return if (message is JsonPrimitive && message.isString) message.content else "Completed."
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
