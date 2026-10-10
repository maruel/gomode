// Contracts and descriptors for on-device voice model engines running on Google Pixel 8 Pro.
package com.fghbuild.gomode.voice

import android.content.Context
import com.caic.voicegateway.sdk.v1.ToolCall
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
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
                description = "Gemma on-device conversational model via LiteRT-LM (Backend.GOOGLE_TENSOR)",
                latencyBudgetMs = 250,
                modelSizeMb = 85,
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

        OnDeviceEngine.NEUDECIDE -> {
            EngineDescriptor(
                engine = this,
                name = "NeuDecide",
                description = "Direct speech-to-action ONNX model (FastConformer + Needle, 55.5M params)",
                latencyBudgetMs = 85,
                modelSizeMb = 43,
            )
        }

        OnDeviceEngine.REMOTE_GATEWAY -> {
            EngineDescriptor(
                engine = this,
                name = "Remote gateway",
                description = "Offloads text turns and tool selection to the voice gateway over WebSocket",
                latencyBudgetMs = 800,
                modelSizeMb = 0,
            )
        }
    }

/** UserTurnInput provides user speech as recognized text and optional raw PCM audio. */
data class UserTurnInput(
    val text: String,
    val audioPcm: ByteArray? = null,
)

/** EngineSessionContext encapsulates active tools and system instructions for a voice session. */
data class EngineSessionContext(
    val languageTag: String,
    val systemInstruction: String,
    val serviceContextText: String,
    val tools: List<ToolDescriptor>,
    val mcpClient: McpClient?,
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

    /** Initializes the engine before turns begin. */
    suspend fun initialize(context: EngineSessionContext)

    /**
     * Processes one user turn, streaming text fragments through [onDelta]
     * and requesting tool executions through [onToolCall].
     */
    suspend fun processTurn(
        input: UserTurnInput,
        onDelta: (String) -> Unit,
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
): OnDeviceVoiceEngine =
    when (engine) {
        OnDeviceEngine.LOCAL_LLM -> LocalLlmEngine(context)
        OnDeviceEngine.NEEDLE_WHISTLE -> NeedleWhistleEngine(context)
        OnDeviceEngine.NEUDECIDE -> NeuDecideEngine(context)
        OnDeviceEngine.REMOTE_GATEWAY -> RemoteGatewayEngine()
    }

/** RemoteGatewayEngine serves as a descriptor placeholder when delegating to the WebSocket voice gateway. */
internal class RemoteGatewayEngine : OnDeviceVoiceEngine {
    override val engineType: OnDeviceEngine = OnDeviceEngine.REMOTE_GATEWAY

    override suspend fun initialize(context: EngineSessionContext) = Unit

    override suspend fun processTurn(
        input: UserTurnInput,
        onDelta: (String) -> Unit,
        onToolCall: suspend (ToolCall) -> JsonElement?,
    ): EngineTurnResult = EngineTurnResult()

    override suspend fun injectContext(text: String) = Unit

    override fun close() = Unit
}
