// Local conversational LLM engine running compact sub-300M / Gemma parameter models on Google Pixel 8 Pro.
package com.fghbuild.gomode.voice

import android.content.Context
import android.util.Log
import com.caic.voicegateway.sdk.v1.MessageKind
import com.caic.voicegateway.sdk.v1.ToolCall
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.OnDeviceEngine
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.tool
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "LocalLlmEngine"
private const val MAX_TOOL_ROUNDS = 8
private const val MALFORMED_TOOL_PREFIX = "<!tool_call>"
private val toolJson = GsonBuilder().serializeNulls().create()

internal fun selectedModelPaths(
    context: Context,
    model: LocalLlmModel,
    explicitModelPath: String? = null,
): List<String> {
    val deviceDir = File("/data/local/tmp/llm")
    val metadata = File(deviceDir, "model_name.txt")
    val legacyName = if (metadata.isFile) metadata.readText().trim() else null
    return buildList {
        explicitModelPath?.let(::add)
        add(File(context.filesDir, "models/${model.fileName}").absolutePath)
        add(File(deviceDir, model.fileName).absolutePath)
        // Old installs used one generic file. Never select a known different variant.
        if (model == LocalLlmModel.GEMMA_4_E2B) {
            add(File(context.filesDir, "models/gemma.litertlm").absolutePath)
            add(File(context.filesDir, "models/model.litertlm").absolutePath)
        }
        if (acceptsLegacyModel(model, legacyName)) {
            add(File(deviceDir, "model.litertlm").absolutePath)
        }
    }
}

internal fun acceptsLegacyModel(
    model: LocalLlmModel,
    metadata: String?,
): Boolean {
    val name = metadata?.let(::formatModelName)
    val generic = name == null || name in setOf("Gemma", "Gemma 4", "gemma", "model")
    return name == model.label || (generic && model == LocalLlmModel.GEMMA_4_E2B)
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

    suspend fun generateStream(message: Message): Flow<Message>

    // Replace only the conversation/prefill; model weights stay owned by this runner.
    fun replaceConversation(config: ConversationConfig)
}

/**
 * LiteRT-LM production runner targeting Gemma models on Tensor G3.
 */
internal class LiteRtLmRunner(
    private val context: Context,
    conversationConfig: ConversationConfig,
    modelPath: String? = null,
    model: LocalLlmModel = LocalLlmModel.GEMMA_4_E2B,
) : EdgeLlmRunner {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override val isModelLoaded: Boolean
        get() = engine != null && conversation != null

    init {
        val candidatePaths = selectedModelPaths(context, model, modelPath)
        val existingPath = candidatePaths.firstOrNull { File(it).isFile }
        checkNotNull(existingPath) {
            "${model.label} not found. Install weights with make android-push-model. " +
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
                val conv = eng.createConversation(conversationConfig)
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

    override fun replaceConversation(config: ConversationConfig) {
        val activeEngine = checkNotNull(engine) { "Local model engine is closed" }
        val oldConversation = conversation
        conversation = null
        oldConversation?.let { closeNativeResource(it) }
        conversation = activeEngine.createConversation(config)
    }

    override suspend fun generateStream(message: Message): Flow<Message> {
        val conv = checkNotNull(conversation) { "Local model session is closed" }
        return conv.sendMessageAsync(message)
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
    private val runnerFactory: ((Context, ConversationConfig) -> EdgeLlmRunner)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val model: LocalLlmModel = LocalLlmModel.GEMMA_4_E2B,
) : OnDeviceVoiceEngine {
    override val engineType: OnDeviceEngine = OnDeviceEngine.LOCAL_LLM

    override val modelName: String
        get() = model.label

    override val isModelLoaded: Boolean
        get() = !closed.get() && runner?.isModelLoaded == true

    private val closed = AtomicBoolean(false)
    private val turnGeneration = AtomicLong()

    // Invalidate queued and active turns without cancelling native inference or unloading weights.
    fun invalidateTurns() {
        turnGeneration.incrementAndGet()
    }

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

    // Successful native messages only. Retry replays these without repeating tool dispatch.
    private val conversationHistory = mutableListOf<Message>()

    override suspend fun initialize(context: EngineSessionContext) {
        nativeOperation.withLock {
            check(!closed.get()) { "Local model engine is closed" }
            val previousRunner = runner
            runner = null
            sessionContext = null
            conversationHistory.clear()
            var preparedRunner: EdgeLlmRunner? = null
            try {
                withContext(NonCancellable + ioDispatcher) { previousRunner?.close() }
                withContext(ioDispatcher) {
                    check(!closed.get()) { "Local model engine is closed" }
                    preparedRunner = runnerFactory?.invoke(this@LocalLlmEngine.context, conversationConfig(context))
                        ?: LiteRtLmRunner(this@LocalLlmEngine.context, conversationConfig(context), model = model)
                }
                check(!closed.get()) { "Local model engine was closed during initialization" }
                runner = preparedRunner
                sessionContext = context
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
        // A blocking native prefill must finish before either reuse or disposal can proceed.
        withContext(NonCancellable + ioDispatcher) { activeRunner.replaceConversation(conversationConfig(context)) }
        sessionContext = context
        conversationHistory.clear()
    }

    override suspend fun processTurn(
        input: UserTurnInput,
        onDelta: (String) -> Unit,
        onToolCall: suspend (ToolCall) -> JsonElement?,
    ): EngineTurnResult {
        val generation = turnGeneration.get()

        fun currentTurn() = !closed.get() && turnGeneration.get() == generation
        return withContext(ioDispatcher) {
            nativeOperation.withLock {
                check(!closed.get()) { "Local model engine is closed" }
                val query = input.text.trim()
                if (query.isEmpty()) return@withContext EngineTurnResult()

                val activeRunner = checkNotNull(runner) { "Local model session is not initialized" }

                val text = StringBuilder()
                val executed = mutableListOf<ToolCall>()
                var message = Message.user(query)
                var rounds = 0
                while (true) {
                    if (!currentTurn()) {
                        return@withContext EngineTurnResult(assistantText = text.toString(), toolCalls = executed)
                    }
                    val roundStart = text.length
                    val reply =
                        generateReply(activeRunner, message, ::currentTurn) { delta ->
                            text.append(delta)
                            onDelta(delta)
                        } ?: return@withContext EngineTurnResult(assistantText = text.toString(), toolCalls = executed)
                    val calls = reply.toolCalls
                    conversationHistory.add(message)
                    conversationHistory.add(reply)
                    if (!currentTurn()) {
                        return@withContext EngineTurnResult(assistantText = text.toString(), toolCalls = executed)
                    }
                    if (calls.isEmpty()) {
                        require(text.substring(roundStart).isNotBlank()) { "Local model returned an empty response" }
                        break
                    }
                    require(rounds++ < MAX_TOOL_ROUNDS) { "Local model exceeded the tool round limit" }
                    val tools = checkNotNull(sessionContext).tools.map { it.name }.toSet()
                    // Validate the complete batch before executing any call.
                    require(calls.all { it.name in tools }) { "Local model requested an unavailable tool" }
                    val results = mutableListOf<Content.ToolResponse>()
                    for (call in calls) {
                        if (!currentTurn()) {
                            return@withContext EngineTurnResult(assistantText = text.toString(), toolCalls = executed)
                        }
                        val args = Json.parseToJsonElement(toolJson.toJson(call.arguments)) as JsonObject
                        val dispatched =
                            ToolCall(
                                kind = MessageKind.ToolCall,
                                id = UUID.randomUUID().toString(),
                                name = call.name,
                                args = args,
                            )
                        val result = onToolCall(dispatched)
                        executed.add(dispatched)
                        // Hang-up invalidates the turn even when the owner retains the loaded model.
                        if (!currentTurn()) {
                            return@withContext EngineTurnResult(assistantText = text.toString(), toolCalls = executed)
                        }
                        results.add(
                            Content.ToolResponse(call.name, JsonParser.parseString(result?.toString() ?: "null")),
                        )
                    }
                    message = Message.tool(Contents.of(results))
                }
                val response = text.toString().trim()
                EngineTurnResult(assistantText = response, toolCalls = executed)
            }
        }
    }

    private suspend fun generateReply(
        activeRunner: EdgeLlmRunner,
        message: Message,
        currentTurn: () -> Boolean,
        onDelta: (String) -> Unit,
    ): Message? {
        repeat(2) { attempt ->
            val contents = mutableListOf<Content>()
            val replayText = StringBuilder()
            val calls = mutableListOf<com.google.ai.edge.litertlm.ToolCall>()
            val channels = mutableMapOf<String, String>()
            val prefix = StringBuilder()
            var decided = false
            var malformed = false
            activeRunner.generateStream(message).collect { reply ->
                // Gemma trims each content item in its template. Keep text chunk boundaries out of replay.
                reply.contents.contents.forEach { content ->
                    if (content is Content.Text) {
                        replayText.append(content.text)
                    } else {
                        if (replayText.isNotEmpty()) contents.add(Content.Text(replayText.toString()))
                        replayText.clear()
                        contents.add(content)
                    }
                }
                calls.addAll(reply.toolCalls)
                reply.channels.forEach { (name, text) -> channels[name] = channels[name].orEmpty() + text }
                reply.contents.contents.filterIsInstance<Content.Text>().forEach { content ->
                    if (!malformed) {
                        if (decided) {
                            onDelta(content.text)
                        } else {
                            prefix.append(content.text)
                            val start = prefix.toString().trimStart()
                            when {
                                start.startsWith(MALFORMED_TOOL_PREFIX) -> {
                                    malformed = true
                                }

                                !MALFORMED_TOOL_PREFIX.startsWith(start) -> {
                                    decided = true
                                    onDelta(prefix.toString())
                                    prefix.clear()
                                }
                            }
                        }
                    }
                }
            }
            // Always drain native work before rebuilding or handling invalidation.
            if (!currentTurn()) return null
            if (!malformed) {
                if (!decided && prefix.isNotEmpty()) onDelta(prefix.toString())
                if (replayText.isNotEmpty()) contents.add(Content.Text(replayText.toString()))
                return Message.model(Contents.of(contents), calls, channels)
            }
            check(attempt == 0) { "Local model repeated a malformed tool-call prefix" }
            Log.w(TAG, "Retrying local message after malformed tool-call prefix")
            val config =
                conversationConfig(
                    checkNotNull(sessionContext),
                ).copy(initialMessages = conversationHistory.toList())
            withContext(NonCancellable + ioDispatcher) { activeRunner.replaceConversation(config) }
            if (!currentTurn()) return null
        }
        error("Local model retry exhausted")
    }

    override suspend fun injectContext(text: String) {
        nativeOperation.withLock {
            // A service update queued behind native work can outlive the voice session.
            if (closed.get()) return
            // Authoritative service facts are reconciled by the owner before the next acquisition.
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

    private fun formatSystemPrompt(context: EngineSessionContext): String =
        buildString {
            append(context.systemInstruction)
            if (context.serviceContextText.isNotBlank()) {
                append("\nService context: ").append(context.serviceContextText)
            }
            append("\nLanguage: ").append(context.languageTag)
        }

    private fun conversationConfig(context: EngineSessionContext): ConversationConfig =
        ConversationConfig(
            systemInstruction = Contents.of(formatSystemPrompt(context)),
            tools =
                context.tools.map { descriptor ->
                    tool(
                        object : OpenApiTool {
                            override fun getToolDescriptionJsonString(): String =
                                buildJsonObject {
                                    put("name", descriptor.name)
                                    descriptor.description?.let { put("description", it) }
                                    descriptor.inputSchema?.let { put("parameters", it) }
                                }.toString()

                            override fun execute(paramsJsonString: String): String =
                                error("Local tools must be dispatched by the voice session")
                        },
                    )
                },
            automaticToolCalling = false,
            prefillPrefaceOnInit = true,
        )
}
