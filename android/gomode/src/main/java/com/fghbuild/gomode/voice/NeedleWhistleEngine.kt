// Matches recognized text to tool names using heuristic confidence scores.
package com.fghbuild.gomode.voice

import android.content.Context
import android.util.Log
import com.caic.voicegateway.sdk.v1.MessageKind
import com.caic.voicegateway.sdk.v1.ToolCall
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.UUID

private const val TAG = "NeedleWhistleEngine"
private const val CONFIDENCE_THRESHOLD = 0.70f

/**
 * NeedleWhistleEngine matches recognized text against tool names and their fragments.
 *
 * Its confidence score is a lexical heuristic. Android supplies the transcript;
 * native Whistle recognition and Needle model inference require separate integration.
 */
internal class NeedleWhistleEngine(
    private val context: Context,
) : OnDeviceVoiceEngine {
    override val engineType: OnDeviceEngine = OnDeviceEngine.NEEDLE_WHISTLE

    private var sessionContext: EngineSessionContext? = null
    private val biasedKeywords = mutableSetOf<String>()
    private val toolMap = mutableMapOf<String, ToolDescriptor>()

    override suspend fun initialize(context: EngineSessionContext) {
        this.sessionContext = context
        biasedKeywords.clear()
        toolMap.clear()
        context.tools.forEach { tool ->
            toolMap[tool.name] = tool
            biasedKeywords.add(tool.name.lowercase(Locale.ROOT))
            tool.name.split('_', '-').forEach { part ->
                if (part.length > 2) biasedKeywords.add(part.lowercase(Locale.ROOT))
            }
        }
        Log.i(TAG, "Initialized NeedleWhistleEngine on Google Pixel 8 Pro with ${toolMap.size} biased tools")
    }

    override suspend fun processTurn(
        input: UserTurnInput,
        onDelta: (String) -> Unit,
        onToolCall: suspend (ToolCall) -> JsonElement?,
    ): EngineTurnResult {
        val query = input.text.trim()
        if (query.isEmpty()) return EngineTurnResult()

        val (matchedTool, confidence) = scoreToolConfidence(query)
        if (matchedTool != null && confidence >= CONFIDENCE_THRESHOLD) {
            val toolCallId = UUID.randomUUID().toString()
            val arguments =
                buildJsonObject {
                    put("query", query)
                    put("confidence", confidence.toDouble())
                }
            val call =
                ToolCall(
                    kind = MessageKind.ToolCall,
                    id = toolCallId,
                    name = matchedTool.name,
                    args = arguments,
                )

            val formattedConfidence = String.format(Locale.ROOT, "%.2f", confidence)
            val transcriptLead = "Recognized: \"$query\" (confidence: $formattedConfidence)."
            onDelta("$transcriptLead Executing ${matchedTool.name}…")
            val toolResult = onToolCall(call)
            val summary = formatToolResult(toolResult)
            if (summary.isNotEmpty()) {
                onDelta(" $summary")
            }
            val fullAssistantText = "$transcriptLead Executing ${matchedTool.name}… $summary".trim()
            return EngineTurnResult(assistantText = fullAssistantText, toolCalls = listOf(call))
        }

        // Low confidence or conversational utterance: emit verbatim audio_text.
        val formattedConfidence = String.format(Locale.ROOT, "%.2f", confidence)
        val conversationalResponse =
            "Heard: \"$query\". No high-confidence action matched (confidence: $formattedConfidence)."
        onDelta(conversationalResponse)
        return EngineTurnResult(assistantText = conversationalResponse)
    }

    override suspend fun injectContext(text: String) {
        Log.d(TAG, "Injected context into NeedleWhistleEngine: ${text.take(50)}")
    }

    override fun close() {
        biasedKeywords.clear()
        toolMap.clear()
        sessionContext = null
    }

    private fun scoreToolConfidence(query: String): Pair<ToolDescriptor?, Float> {
        val lower = query.lowercase(Locale.ROOT)
        var bestTool: ToolDescriptor? = null
        var bestConfidence = 0.0f

        for (tool in toolMap.values) {
            val toolName = tool.name.lowercase(Locale.ROOT)
            val parts = toolName.split('_', '-')
            if (lower.contains(toolName)) {
                return Pair(tool, 0.95f)
            }
            val matchedParts = parts.count { it.length > 2 && lower.contains(it) }
            if (matchedParts > 0) {
                val confidence = 0.60f + (0.15f * matchedParts)
                if (confidence > bestConfidence) {
                    bestConfidence = confidence
                    bestTool = tool
                }
            }
        }
        return Pair(bestTool, bestConfidence)
    }

    private fun formatToolResult(result: JsonElement?): String {
        if (result == null) return "Success."
        val obj = result as? JsonObject ?: return "Done."
        return obj["message"]?.toString()?.trim('"') ?: obj["status"]?.toString()?.trim('"') ?: "Done."
    }
}
