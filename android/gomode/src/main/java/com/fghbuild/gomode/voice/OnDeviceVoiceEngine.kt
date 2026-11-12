// Contracts and labels for on-device voice model engines.
package com.fghbuild.gomode.voice

import android.content.Context
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import com.fghbuild.voicegateway.sdk.v2.ToolCall
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** User-visible engine name, shared by settings diagnostics and voice errors. */
val OnDeviceEngine.label: String
    get() =
        when (this) {
            OnDeviceEngine.LOCAL_LLM -> "Local LLM"
            OnDeviceEngine.NEEDLE_WHISTLE -> "Whistle + Needle"
            OnDeviceEngine.REMOTE_GATEWAY -> "Remote LLM"
        }

/** UserTurnInput provides recognized user text or an explicit service update to respond to. */
data class UserTurnInput(
    val text: String,
    val audioPcm: ByteArray? = null,
    val serviceUpdate: Boolean = false,
)

/** EngineSessionContext encapsulates active tools and system instructions for a voice session. */
data class EngineSessionContext(
    val languageTag: String,
    val systemInstruction: String,
    val serviceContextText: String,
    val tools: List<ToolDescriptor>,
    val mcpClient: McpClient?,
)

/** Runtime-measured prefill and decode counts with the latest context occupancy. */
data class EngineUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val contextTokens: Long,
    val contextWindow: Long,
)

/** EngineTurnResult summarizes the finished turn. */
data class EngineTurnResult(
    val assistantText: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
)

/**
 * OnDeviceVoiceEngine handles conversation turns and tool decisions locally on device.
 */
interface OnDeviceVoiceEngine {
    val engineType: OnDeviceEngine

    val modelName: String?
        get() = null

    val isModelLoaded: Boolean
        get() = true

    /** Initializes the engine before turns begin. */
    suspend fun initialize(context: EngineSessionContext)

    /**
     * Processes one user turn, streaming text fragments through [onDelta]
     * and requesting tool executions through [onToolCall]. [onUsage] receives cumulative
     * counts after each completed model request, including requests preceding a failed turn.
     */
    suspend fun processTurn(
        input: UserTurnInput,
        onDelta: (String) -> Unit,
        onUsage: suspend (EngineUsage) -> Unit,
        onToolCall: suspend (ToolCall) -> JsonElement?,
    ): EngineTurnResult

    /** Injects external context into the engine memory. */
    suspend fun injectContext(text: String)

    /** Releases native resources and active sessions. */
    fun close()
}

/** Creates the configured on-device voice engine. */
fun createOnDeviceEngine(
    context: Context,
    engine: OnDeviceEngine,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): OnDeviceVoiceEngine =
    when (engine) {
        OnDeviceEngine.LOCAL_LLM -> LocalLlmEngine(context, ioDispatcher = ioDispatcher)
        OnDeviceEngine.NEEDLE_WHISTLE -> NeedleWhistleEngine(context)
        OnDeviceEngine.REMOTE_GATEWAY -> RemoteGatewayEngine()
    }

/** RemoteGatewayEngine is the placeholder engine when text turns go to the WebSocket voice gateway. */
internal class RemoteGatewayEngine : OnDeviceVoiceEngine {
    override val engineType: OnDeviceEngine = OnDeviceEngine.REMOTE_GATEWAY

    override suspend fun initialize(context: EngineSessionContext) = Unit

    override suspend fun processTurn(
        input: UserTurnInput,
        onDelta: (String) -> Unit,
        onUsage: suspend (EngineUsage) -> Unit,
        onToolCall: suspend (ToolCall) -> JsonElement?,
    ): EngineTurnResult = EngineTurnResult()

    override suspend fun injectContext(text: String) = Unit

    override fun close() = Unit
}
