// Local Gemma and LFM3 conversations with validated tools and serialized native inference.
package com.fghbuild.gomode.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.service.boundModelContext
import com.fghbuild.voicegateway.sdk.v2.MessageKind
import com.fghbuild.voicegateway.sdk.v2.ToolCall
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
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
import kotlin.random.Random

private const val TAG = "LocalLlmEngine"
private const val MAX_TOOL_ROUNDS = 8
private const val MALFORMED_TOOL_PREFIX = "<!tool_call>"
private const val NATIVE_TOOL_PARSE_ERROR =
    "Status Code: 3. Message: Failed to parse tool calls from code block:"
private const val INVALID_TOOL_REPLY = "Local model could not form a valid tool call."
private val toolJson = GsonBuilder().serializeNulls().create()

internal class LocalModelUnavailableException(
    val model: LocalLlmModel,
) : IllegalStateException("${model.label} is not ready yet. Check its download in Settings.")

private enum class TurnOutcome { CANCELLED, COMPLETED, FAILED, INVALIDATED }

private enum class RetryOutcome { CANCELLED, FAILED, INVALIDATED, RECOVERED }

// Diagnostic fields exclude prompts, generated text, tool names, arguments, and exception messages.
private class LocalTurnDiagnostics(
    val model: String,
    val backend: String,
) {
    private val started = SystemClock.elapsedRealtime()
    var toolRounds = 0
    var retries = 0
    var outcome = TurnOutcome.FAILED

    fun metadata(): String = "model=$model backend=$backend"

    fun finish() {
        Log.i(
            TAG,
            "local_turn ${metadata()} duration_ms=${SystemClock.elapsedRealtime() - started} " +
                "tool_rounds=$toolRounds retries=$retries outcome=${outcome.name.lowercase()}",
        )
    }
}

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
        add(File(context.noBackupFilesDir, "models/${model.fileName}").absolutePath)
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
    if (metadata == null) return model == LocalLlmModel.GEMMA_4_E2B
    val matched = LocalLlmModel.fromRawName(metadata)
    if (matched != null) return matched == model
    val generic =
        metadata.removeSuffix(".litertlm").lowercase() in setOf("gemma", "gemma 4", "model")
    return generic && model == LocalLlmModel.GEMMA_4_E2B
}

internal fun formatModelName(raw: String): String =
    LocalLlmModel.fromRawName(raw)?.label ?: raw.removeSuffix(".litertlm")

internal fun backendsFor(model: LocalLlmModel): List<Backend> =
    when (model) {
        LocalLlmModel.LFM_3_1_7B_64K,
        LocalLlmModel.LFM_3_1_7B_32K,
        -> listOf(Backend.GPU())

        LocalLlmModel.GEMMA_4_E2B,
        LocalLlmModel.GEMMA_4_E4B,
        -> listOf(Backend.GOOGLE_TENSOR(), Backend.GPU(), Backend.CPU())
    }

/**
 * Interface abstracting Google AI Edge LiteRT-LM for testability.
 */
internal interface EdgeLlmRunner : AutoCloseable {
    val isModelLoaded: Boolean
    val backendName: String

    suspend fun generateStream(message: Message): Flow<Message>

    // Read the last completed native request before replacing the conversation.
    fun getUsage(): EngineUsage?

    // Reset conversation state; recurrent models also require fresh native engine state.
    fun replaceConversation(config: ConversationConfig)
}

/**
 * LiteRT-LM production runner for the selected model and its supported backends.
 */
internal class LiteRtLmRunner(
    private val context: Context,
    conversationConfig: ConversationConfig,
    modelPath: String? = null,
    private val model: LocalLlmModel = LocalLlmModel.GEMMA_4_E2B,
) : EdgeLlmRunner {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    override var backendName: String = ""
        private set
    private val existingPath: String

    override val isModelLoaded: Boolean
        get() = engine != null && conversation != null

    init {
        val candidatePaths = selectedModelPaths(context, model, modelPath)
        existingPath =
            candidatePaths.firstOrNull { File(it).isFile } ?: throw LocalModelUnavailableException(model)
        initialize(conversationConfig)
    }

    @OptIn(ExperimentalApi::class)
    private fun initialize(conversationConfig: ConversationConfig) {
        // LiteRT reads this flag at engine creation; it enables runtime token counters.
        ExperimentalFlags.enableBenchmark = true
        val diagnostics = (context.applicationContext as GoModeApplication).modelLoadDiagnostics
        val backends = backendsFor(model)
        val failure =
            IllegalStateException(
                "Unable to load local model $existingPath on ${backends.joinToString { it.name }}. " +
                    "Choose another local model in Settings. " +
                    "See LiteRT-LM backend failures in logcat for details.",
            )
        var initializedBackend: String? = null
        for (backend in backends) {
            val attempt = diagnostics.begin(model, backend.name)
            var attemptEngine: Engine? = null
            try {
                val config =
                    EngineConfig(
                        modelPath = existingPath,
                        backend = backend,
                        // The context includes instructions, tool schemas, history, and output.
                        maxNumTokens = model.maxNumTokens,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                val eng = Engine(config)
                attemptEngine = eng
                diagnostics.stage(attempt, ModelLoadStage.ENGINE_INITIALIZATION)
                eng.initialize()
                diagnostics.stage(attempt, ModelLoadStage.CONVERSATION_CREATION)
                val conv = eng.createConversation(conversationConfig)
                Log.i(TAG, "Initialized Google AI Edge LiteRT-LM from $existingPath on ${backend.name}")
                engine = eng
                conversation = conv
                initializedBackend = backend.name
                diagnostics.finish(attempt)
                break
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable, // Record native Errors too, then rethrow them.
            ) {
                diagnostics.finish(attempt, e)
                when (e) {
                    is CancellationException -> {
                        throw e
                    }

                    is Exception -> {
                        failure.addSuppressed(e)
                        Log.w(TAG, "Failed initializing LiteRT-LM from $existingPath on ${backend.name}", e)
                    }

                    else -> {
                        throw e
                    }
                }
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
        backendName = initializedBackend ?: throw failure
    }

    override fun replaceConversation(config: ConversationConfig) {
        if (model.isLfm3) {
            // LiteRT-LM #3165: short-convolution state survives conversation deletion.
            close()
            initialize(config)
            return
        }
        val activeEngine = checkNotNull(engine) { "Local model engine is closed" }
        val oldConversation = conversation
        conversation = null
        oldConversation?.let { closeNativeResource(it) }
        conversation = activeEngine.createConversation(config)
    }

    override suspend fun generateStream(message: Message): Flow<Message> {
        val conv = checkNotNull(conversation) { "Local model session is closed" }
        return if (model.isLfm3) {
            val outgoing =
                if (message.role == com.google.ai.edge.litertlm.Role.TOOL) {
                    Message.tool(
                        Contents.of(
                            message.contents.contents.map { content ->
                                if (content is Content.ToolResponse) {
                                    Content.Text(
                                        toolJson.toJson(content.response),
                                    )
                                } else {
                                    content
                                }
                            },
                        ),
                    )
                } else {
                    message
                }
            conv.sendMessageAsync(
                outgoing,
                repetitionPenaltyConfig =
                    com.google.ai.edge.litertlm
                        .RepetitionPenaltyConfig(repetitionPenalty = 1.1f),
            )
        } else {
            conv.sendMessageAsync(message)
        }
    }

    @OptIn(ExperimentalApi::class)
    override fun getUsage(): EngineUsage {
        val conv = checkNotNull(conversation) { "Local model session is closed" }
        val info = conv.getBenchmarkInfo()
        return EngineUsage(
            inputTokens = info.lastPrefillTokenCount.toLong(),
            outputTokens = info.lastDecodeTokenCount.toLong(),
            contextTokens = conv.getTokenCount().toLong(),
            contextWindow = model.maxNumTokens.toLong(),
        )
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
 * LocalLlmEngine runs Gemma and LFM3 through LiteRT-LM with serialized native operations.
 *
 * LFM3 uses generic model metadata and [parseLfm3Reply] for its XML-like tool grammar.
 * Buffer the complete reply before parsing so malformed control syntax cannot reach speech.
 */
internal class LocalLlmEngine(
    private val context: Context,
    private val runnerFactory: ((Context, ConversationConfig) -> EdgeLlmRunner)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val model: LocalLlmModel = LocalLlmModel.GEMMA_4_E2B,
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
    private var samplerSeed = 0
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
                        toolValidator = null
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
    private var toolValidator: ToolCallValidator? = null

    @Volatile
    private var runner: EdgeLlmRunner? = null

    // Successful native messages only. Retry replays these without repeating tool dispatch.
    private val conversationHistory = mutableListOf<Message>()
    private var pendingContext: String? = null

    override suspend fun initialize(context: EngineSessionContext) {
        val boundedContext = context.copy(serviceContextText = boundModelContext(context.serviceContextText))
        nativeOperation.withLock {
            check(!closed.get()) { "Local model engine is closed" }
            val previousRunner = runner
            runner = null
            sessionContext = null
            toolValidator = null
            conversationHistory.clear()
            var preparedRunner: EdgeLlmRunner? = null
            try {
                withContext(NonCancellable + ioDispatcher) { previousRunner?.close() }
                val preparedValidator = withContext(ioDispatcher) { ToolCallValidator(context.tools) }
                withContext(ioDispatcher) {
                    check(!closed.get()) { "Local model engine is closed" }
                    preparedRunner =
                        runnerFactory?.invoke(this@LocalLlmEngine.context, conversationConfig(boundedContext))
                            ?: LiteRtLmRunner(
                                this@LocalLlmEngine.context,
                                conversationConfig(boundedContext),
                                model = model,
                            )
                }
                check(!closed.get()) { "Local model engine was closed during initialization" }
                runner = preparedRunner
                sessionContext = boundedContext
                pendingContext = null
                toolValidator = preparedValidator
                Log.i(TAG, "Initialized LocalLlmEngine with ${context.tools.size} tools")
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

    private suspend fun replaceConversation(context: EngineSessionContext) {
        val boundedContext = context.copy(serviceContextText = boundModelContext(context.serviceContextText))
        check(!closed.get()) { "Local model engine is closed" }
        val activeRunner = checkNotNull(runner) { "Local model session is not initialized" }
        val preparedValidator = withContext(ioDispatcher) { ToolCallValidator(context.tools) }
        // A blocking native prefill must finish before either reuse or disposal can proceed.
        withContext(
            NonCancellable + ioDispatcher,
        ) { activeRunner.replaceConversation(conversationConfig(boundedContext)) }
        sessionContext = boundedContext
        pendingContext = null
        toolValidator = preparedValidator
        conversationHistory.clear()
    }

    override suspend fun processTurn(
        input: UserTurnInput,
        onDelta: (String) -> Unit,
        onUsage: suspend (EngineUsage) -> Unit,
        onToolCall: suspend (ToolCall) -> JsonElement?,
    ): EngineTurnResult {
        val generation = turnGeneration.get()

        return withContext(ioDispatcher) {
            nativeOperation.withLock {
                check(!closed.get()) { "Local model engine is closed" }
                val query = input.text.trim()
                if (query.isEmpty()) return@withContext EngineTurnResult()

                val activeRunner = checkNotNull(runner) { "Local model session is not initialized" }
                val validator = checkNotNull(toolValidator)

                val diagnostics = LocalTurnDiagnostics(model.name, activeRunner.backendName)

                fun validateTurn(): Boolean {
                    val current = !closed.get() && turnGeneration.get() == generation
                    if (!current) diagnostics.outcome = TurnOutcome.INVALIDATED
                    return current
                }
                try {
                    val text = StringBuilder()
                    val executed = mutableListOf<ToolCall>()
                    var usage: EngineUsage? = null

                    suspend fun observeUsage(next: EngineUsage) {
                        val total =
                            next.copy(
                                inputTokens = (usage?.inputTokens ?: 0) + next.inputTokens,
                                outputTokens = (usage?.outputTokens ?: 0) + next.outputTokens,
                            )
                        usage = total
                        onUsage(total)
                    }
                    val contextUpdate = pendingContext
                    pendingContext = null
                    // Context changes precede the request; the request is always last.
                    var message = Message.user(listOfNotNull(contextUpdate, query).joinToString("\n\n"))
                    while (true) {
                        if (!validateTurn()) {
                            return@withContext EngineTurnResult(
                                assistantText = text.toString(),
                                toolCalls = executed,
                            )
                        }
                        val roundStart = text.length
                        val reply =
                            generateReply(activeRunner, message, diagnostics, ::validateTurn, ::observeUsage) { delta ->
                                text.append(delta)
                                onDelta(delta)
                            }
                                ?: return@withContext EngineTurnResult(
                                    assistantText = text.toString(),
                                    toolCalls = executed,
                                )
                        val calls = reply.toolCalls
                        conversationHistory.add(message)
                        conversationHistory.add(reply)
                        if (!validateTurn()) {
                            return@withContext EngineTurnResult(
                                assistantText = text.toString(),
                                toolCalls = executed,
                            )
                        }
                        if (calls.isEmpty()) {
                            require(
                                text.substring(roundStart).isNotBlank(),
                            ) { "Local model returned an empty response" }
                            break
                        }
                        require(
                            diagnostics.toolRounds < MAX_TOOL_ROUNDS,
                        ) { "Local model exceeded the tool round limit" }
                        diagnostics.toolRounds++
                        val requested =
                            calls.map { call ->
                                ToolCall(
                                    kind = MessageKind.ToolCall,
                                    id = UUID.randomUUID().toString(),
                                    name = call.name,
                                    args = Json.parseToJsonElement(toolJson.toJson(call.arguments)) as JsonObject,
                                )
                            }
                        // Reject the whole batch before side effects; Gemma can submit a corrected batch.
                        val errors = requested.map { validator.validate(it.name, it.args as JsonObject) }
                        if (errors.any { it != null }) {
                            message =
                                Message.tool(
                                    Contents.of(
                                        requested.mapIndexed { index, call ->
                                            val error =
                                                errors[index]
                                                    ?: (
                                                        "Call not executed: batch validation failed. " +
                                                            "Submit a corrected batch."
                                                    )
                                            Content.ToolResponse(
                                                call.name,
                                                JsonParser.parseString(errorJson(error).toString()),
                                            )
                                        },
                                    ),
                                )
                            continue
                        }
                        val results = mutableListOf<Content.ToolResponse>()
                        for (dispatched in requested) {
                            if (!validateTurn()) {
                                return@withContext EngineTurnResult(
                                    assistantText = text.toString(),
                                    toolCalls = executed,
                                )
                            }
                            val result = onToolCall(dispatched)
                            executed.add(dispatched)
                            // Hang-up invalidates the turn even when the owner retains the loaded model.
                            if (!validateTurn()) {
                                return@withContext EngineTurnResult(
                                    assistantText = text.toString(),
                                    toolCalls = executed,
                                )
                            }
                            results.add(
                                Content.ToolResponse(
                                    dispatched.name,
                                    JsonParser.parseString(result?.toString() ?: "null"),
                                ),
                            )
                        }
                        message = Message.tool(Contents.of(results))
                    }
                    val response = text.toString().trim()
                    diagnostics.outcome = TurnOutcome.COMPLETED
                    EngineTurnResult(assistantText = response, toolCalls = executed)
                } catch (e: CancellationException) {
                    diagnostics.outcome = TurnOutcome.CANCELLED
                    throw e
                } finally {
                    diagnostics.finish()
                }
            }
        }
    }

    private suspend fun generateReply(
        activeRunner: EdgeLlmRunner,
        message: Message,
        diagnostics: LocalTurnDiagnostics,
        validateTurn: () -> Boolean,
        onUsage: suspend (EngineUsage) -> Unit,
        onDelta: (String) -> Unit,
    ): Message? {
        var retried = false
        var retryOutcome = RetryOutcome.FAILED
        try {
            repeat(2) { attempt ->
                val contents = mutableListOf<Content>()
                val replayText = StringBuilder()
                val calls = mutableListOf<com.google.ai.edge.litertlm.ToolCall>()
                val channels = mutableMapOf<String, String>()
                val prefix = StringBuilder()
                var decided = false
                var malformed = false
                var nativeParseFailure = false
                var emittedText = false
                try {
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
                        if (model.isLfm3) return@collect
                        reply.contents.contents.filterIsInstance<Content.Text>().forEach { content ->
                            if (!malformed) {
                                if (decided) {
                                    emittedText = true
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
                                            emittedText = true
                                            onDelta(prefix.toString())
                                            prefix.clear()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: LiteRtLmJniException) {
                    // LiteRT includes the generated tool call in this parser error. Never display or log it.
                    if (e.message?.startsWith(NATIVE_TOOL_PARSE_ERROR) != true) throw e
                    nativeParseFailure = true
                }
                val measured = activeRunner.getUsage()
                if (measured != null) onUsage(measured)
                // Always drain native work before rebuilding or handling invalidation.
                if (!validateTurn()) {
                    retryOutcome = RetryOutcome.INVALIDATED
                    return null
                }
                if (!malformed && !nativeParseFailure) {
                    if (model.isLfm3) {
                        val parsed = parseLfm3Reply(replayText.toString(), checkNotNull(toolValidator))
                        if (parsed.text.isNotEmpty()) onDelta(parsed.text)
                        if (parsed.thought.isNotEmpty()) {
                            channels["thought"] = channels["thought"].orEmpty() + parsed.thought
                        }
                        return Message.model(
                            Contents.of(parsed.text),
                            parsed.calls,
                            channels,
                        )
                    }
                    if (!decided && prefix.isNotEmpty()) onDelta(prefix.toString())
                    if (replayText.isNotEmpty()) contents.add(Content.Text(replayText.toString()))
                    retryOutcome = RetryOutcome.RECOVERED
                    return Message.model(Contents.of(contents), calls, channels)
                }
                if (nativeParseFailure) {
                    // Speech cannot be retracted. Retry only when this generation emitted no text.
                    check(attempt == 0 && !emittedText) { INVALID_TOOL_REPLY }
                } else {
                    check(attempt == 0) { "Local model repeated a malformed tool-call prefix" }
                }
                retried = true
                diagnostics.retries++
                val previousSeed = samplerSeed
                do {
                    // std::default_random_engine aliases seeds 0 and 1. Avoid both on recovery.
                    samplerSeed = Random.nextInt(2, Int.MAX_VALUE)
                } while (samplerSeed == previousSeed)
                val reason = if (nativeParseFailure) "native_tool_parse" else "malformed_tool_prefix"
                Log.w(TAG, "local_retry ${diagnostics.metadata()} outcome=started reason=$reason seed=$samplerSeed")
                val config =
                    conversationConfig(
                        checkNotNull(sessionContext),
                    ).copy(initialMessages = conversationHistory.toList())
                withContext(NonCancellable + ioDispatcher) { activeRunner.replaceConversation(config) }
                if (!validateTurn()) {
                    retryOutcome = RetryOutcome.INVALIDATED
                    return null
                }
            }
            error("Local model retry exhausted")
        } catch (e: CancellationException) {
            retryOutcome = RetryOutcome.CANCELLED
            throw e
        } finally {
            if (retried) Log.i(TAG, "local_retry ${diagnostics.metadata()} outcome=${retryOutcome.name.lowercase()}")
        }
    }

    override suspend fun injectContext(text: String) {
        val generation = turnGeneration.get()
        nativeOperation.withLock {
            // Updates are changes only, so queued ones accumulate; an over-budget total keeps the newest whole.
            val context = boundModelContext(text)
            if (closed.get() || generation != turnGeneration.get() || context.isEmpty()) return
            pendingContext =
                boundModelContext(listOfNotNull(pendingContext, context).joinToString("\n\n")).ifEmpty { context }
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

    private fun formatSystemPrompt(session: EngineSessionContext): String =
        buildString {
            // Mirrors policy.Render in voicegateway/policy/policy.go.
            val instructions = session.systemInstruction.trim()
            append(
                context.assets
                    .open("voice-policy.txt")
                    .bufferedReader()
                    .use { it.readText() }
                    .trim()
                    .replace("\n\n{{instructions}}", if (instructions.isEmpty()) "" else "\n\n$instructions")
                    .replace("{{language}}", session.languageTag),
            )
            if (session.serviceContextText.isNotBlank()) {
                append(
                    "\n",
                ).append(session.serviceContextText)
            }
        }

    private fun conversationConfig(context: EngineSessionContext): ConversationConfig =
        ConversationConfig(
            systemInstruction = Contents.of(formatSystemPrompt(context)),
            // Model defaults; recovery changes only the seed.
            samplerConfig =
                SamplerConfig(
                    topK = model.topK,
                    topP = model.topP,
                    temperature = model.temperature,
                    seed = samplerSeed,
                ),
            // LFM3's grammar owns thought boundaries, including literal markers in arguments.
            // Null inherits metadata channels and would strip values before our parser sees them.
            channels = if (model.isLfm3) emptyList() else null,
            tools =
                if (model.isLfm3) {
                    emptyList()
                } else {
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
                    }
                },
            extraContext =
                if (model.isLfm3) {
                    mapOf(
                        "tools" to
                            context.tools.map { descriptor ->
                                mapOf(
                                    "type" to "function",
                                    "function" to
                                        mapOf(
                                            "name" to descriptor.name,
                                            "description" to descriptor.description.orEmpty(),
                                            "parameters" to
                                                JsonParser.parseString(descriptor.inputSchema.toString()),
                                        ),
                                )
                            },
                    )
                } else {
                    emptyMap()
                },
            thinkingConfig =
                if (model.isLfm3) {
                    com.google.ai.edge.litertlm
                        .ThinkingConfig(enableThinking = false)
                } else {
                    null
                },
            automaticToolCalling = false,
            prefillPrefaceOnInit = true,
        )
}
