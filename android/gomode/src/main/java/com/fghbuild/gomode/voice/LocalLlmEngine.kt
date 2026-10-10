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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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

    // Replace only the conversation/prefill; model weights stay owned by this runner.
    fun replaceConversation(systemInstruction: String)
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
        val existingPath = candidatePaths.firstOrNull { File(it).isFile }
        checkNotNull(existingPath) {
            "Local model not found. Install model weights with make android-push-model. " +
                "Searched: ${candidatePaths.joinToString()}"
        }
        val backends = listOf(Backend.GOOGLE_TENSOR(), Backend.GPU(), Backend.CPU())
        val failure =
            IllegalStateException(
                "Unable to load local model $existingPath on ${backends.joinToString { it.name }}. " +
                    "Check that the weights are a compatible LiteRT-LM model; " +
                    "reinstall with make android-push-model. " +
                    "See LiteRT-LM backend failures in logcat for details.",
            )
        for (backend in backends) {
            var attemptEngine: Engine? = null
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
                attemptEngine = eng
                eng.initialize()
                val conv =
                    eng.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(systemInstruction),
                            // Prefill instructions and tool schemas, not a fabricated warm-up turn.
                            prefillPrefaceOnInit = true,
                        ),
                    )
                Log.i(TAG, "Initialized Google AI Edge LiteRT-LM from $existingPath on ${backend.name}")
                engine = eng
                conversation = conv
                break
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // Try the remaining native backends.
            ) {
                failure.addSuppressed(e)
                Log.w(TAG, "Failed initializing LiteRT-LM from $existingPath on ${backend.name}", e)
            } finally {
                // Engine.close requires initialization; failed native creation has no Kotlin-owned handle.
                if (engine !== attemptEngine && attemptEngine?.isInitialized() == true) {
                    try {
                        closeNativeResource(attemptEngine)
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception, // Retry after cleanup failure.
                    ) {
                        failure.addSuppressed(e)
                    }
                }
            }
        }
        if (!isModelLoaded) throw failure
    }

    override fun replaceConversation(systemInstruction: String) {
        val activeEngine = checkNotNull(engine) { "Local model engine is closed" }
        val oldConversation = conversation
        conversation = null
        oldConversation?.let { closeNativeResource(it) }
        conversation =
            activeEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    prefillPrefaceOnInit = true,
                ),
            )
    }

    override suspend fun generateStream(prompt: String): Flow<String> {
        val conv = checkNotNull(conversation) { "Local model session is closed" }
        return conv.sendMessageAsync(prompt).map { message ->
            message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
        }
    }

    override suspend fun generate(prompt: String): String {
        val conv = checkNotNull(conversation) { "Local model session is closed" }
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
        val oldConversation = conversation
        val oldEngine = engine
        conversation = null
        engine = null
        // Delete the conversation before its engine, and still attempt the engine if deletion fails.
        try {
            oldConversation?.let { closeNativeResource(it) }
        } finally {
            oldEngine?.let { closeNativeResource(it) }
        }
    }

    private fun closeNativeResource(resource: AutoCloseable) {
        try {
            resource.close()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // Continue releasing the other native resources.
        ) {
            Log.w(TAG, "Failed closing LiteRT-LM resource", e)
            throw e
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
        get() = !closed.get() && runner?.isModelLoaded == true

    private val closed = AtomicBoolean(false)
    private val nativeOperation = Mutex()
    private val cleanupScope = CoroutineScope(ioDispatcher)
    private val cleanup =
        cleanupScope
            .async(start = CoroutineStart.LAZY) {
                nativeOperation.withLock {
                    try {
                        runner?.close()
                    } finally {
                        runner = null
                        sessionContext = null
                        conversationHistory.clear()
                        injectedContexts.clear()
                    }
                }
            }.apply {
                invokeOnCompletion { failure ->
                    if (failure != null) Log.e(TAG, "Failed releasing local model resources", failure)
                    cleanupScope.cancel()
                }
            }

    private var sessionContext: EngineSessionContext? = null

    @Volatile
    private var runner: EdgeLlmRunner? = null
    private val conversationHistory = mutableListOf<ChatMessage>()
    private val injectedContexts = mutableListOf<String>()

    private data class ChatMessage(
        val role: String,
        val content: String,
    )

    override suspend fun initialize(context: EngineSessionContext) {
        nativeOperation.withLock {
            check(!closed.get()) { "Local model engine is closed" }
            val previousRunner = runner
            runner = null
            sessionContext = null
            conversationHistory.clear()
            injectedContexts.clear()
            var preparedRunner: EdgeLlmRunner? = null
            val systemInstruction = formatSystemPrompt(context)
            try {
                withContext(NonCancellable + ioDispatcher) { previousRunner?.close() }
                withContext(ioDispatcher) {
                    check(!closed.get()) { "Local model engine is closed" }
                    preparedRunner = runnerFactory(this@LocalLlmEngine.context, systemInstruction)
                }
                check(!closed.get()) { "Local model engine was closed during initialization" }
                runner = preparedRunner
                sessionContext = context
                conversationHistory.add(ChatMessage("system", systemInstruction))
                Log.i(TAG, "Initialized LocalLlmEngine on Google Pixel 8 Pro with ${context.tools.size} tools")
            } finally {
                // Also release a model if cancellation or close wins during blocking initialization.
                if (runner !== preparedRunner) {
                    withContext(NonCancellable + ioDispatcher) { preparedRunner?.close() }
                }
            }
        }
    }

    // The owner calls this after disconnect without cancelling a native turn collector.
    suspend fun resetConversation(context: EngineSessionContext) {
        nativeOperation.withLock {
            replaceConversation(context)
        }
    }

    suspend fun reconcileServiceContext(text: String) {
        nativeOperation.withLock {
            val previous = checkNotNull(sessionContext) { "Local model session is not initialized" }
            if (previous.serviceContextText != text) {
                replaceConversation(previous.copy(serviceContextText = text))
            }
        }
    }

    private suspend fun replaceConversation(context: EngineSessionContext) {
        check(!closed.get()) { "Local model engine is closed" }
        val activeRunner = checkNotNull(runner) { "Local model session is not initialized" }
        val instruction = formatSystemPrompt(context)
        // A blocking native prefill must finish before either reuse or disposal can proceed.
        withContext(NonCancellable + ioDispatcher) { activeRunner.replaceConversation(instruction) }
        sessionContext = context
        conversationHistory.clear()
        conversationHistory.add(ChatMessage("system", instruction))
        injectedContexts.clear()
    }

    override suspend fun processTurn(
        input: UserTurnInput,
        onDelta: (String) -> Unit,
        onToolCall: suspend (ToolCall) -> JsonElement?,
    ): EngineTurnResult =
        withContext(ioDispatcher) {
            nativeOperation.withLock {
                check(!closed.get()) { "Local model engine is closed" }
                val query = input.text.trim()
                if (query.isEmpty()) return@withContext EngineTurnResult()

                conversationHistory.add(ChatMessage("user", query))

                val activeRunner = checkNotNull(runner) { "Local model session is not initialized" }

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
                EngineTurnResult(assistantText = response)
            }
        }

    override suspend fun injectContext(text: String) {
        nativeOperation.withLock {
            // A service update queued behind native work can outlive the voice session.
            if (closed.get()) return
            injectedContexts.add(text)
            conversationHistory.add(ChatMessage("system", "Context update: $text"))
        }
    }

    override fun close() {
        // Terminal and immediately unready; deletion waits for in-flight native operations on IO.
        // The engine owns cleanup independently of voice-session coroutine cancellation.
        closed.set(true)
        cleanup.start()
    }

    // Await disposal, including an in-flight initialization/turn, before allocating a replacement owner.
    // Turns must finish: cancelling LiteRT's Flow collector does not stop native inference.
    suspend fun closeAndJoin() {
        close()
        cleanup.await()
    }

    private fun formatSystemPrompt(context: EngineSessionContext): String {
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
            append(context.systemInstruction)
            if (context.serviceContextText.isNotBlank()) {
                append("\nService context: ").append(context.serviceContextText)
            }
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
}
