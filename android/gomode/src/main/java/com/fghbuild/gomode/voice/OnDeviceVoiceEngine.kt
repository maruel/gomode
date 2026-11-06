// Contracts and descriptors for on-device voice model engines running on Google Pixel 8 Pro.
package com.fghbuild.gomode.voice

import android.content.Context
import com.caic.voicegateway.sdk.v2.ToolCall
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** EngineDescriptor captures hardware resource budgets and execution properties. */
data class EngineDescriptor(
    val engine: OnDeviceEngine,
    val name: String,
    val description: String,
    val latencyBudgetMs: Int,
    val modelSizeMb: Int,
    val targetHardware: String = "Google Pixel 8 Pro",
)

/** Returns the execution descriptor for an engine. */
fun OnDeviceEngine.descriptor(): EngineDescriptor =
    when (this) {
        OnDeviceEngine.LOCAL_LLM -> {
            EngineDescriptor(
                engine = this,
                name = "Local LLM",
                description = "Gemma and LFM3 on-device conversations via LiteRT-LM",
                latencyBudgetMs = 250,
                modelSizeMb = 2588,
            )
        }

        OnDeviceEngine.NEEDLE_WHISTLE -> {
            EngineDescriptor(
                engine = this,
                name = "Whistle + Needle",
                description = "Biased multilingual ASR + Engram action matching (Cactus Compute)",
                latencyBudgetMs = 120,
                modelSizeMb = 52,
            )
        }

        OnDeviceEngine.REMOTE_GATEWAY -> {
            EngineDescriptor(
                engine = this,
                name = "Remote LLM",
                description = "Offloads text turns and tool selection to the voice gateway over WebSocket",
                latencyBudgetMs = 800,
                modelSizeMb = 0,
            )
        }
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

    val descriptor: EngineDescriptor
        get() = engineType.descriptor()

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

/** Creates the configured on-device voice engine for Google Pixel 8 Pro. */
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

/** RemoteGatewayEngine serves as a descriptor placeholder when delegating to the WebSocket voice gateway. */
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
