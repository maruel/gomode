// Matches recognized text to tools and optionally initializes a NeuDecide ONNX session.
package com.fghbuild.gomode.voice

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
import java.io.File
import java.util.Locale
import java.util.UUID

private const val TAG = "NeuDecideEngine"

/**
 * NeuDecideEngine matches recognized text against indexed tool names and descriptions.
 *
 * Model files can initialize an optional ONNX session. Turn processing uses keyword matching
 * independently of that session; audio inference requires a separate model integration.
 */
internal class NeuDecideEngine(
    private val context: Context,
) : OnDeviceVoiceEngine {
    override val engineType: OnDeviceEngine = OnDeviceEngine.NEUDECIDE

    private var sessionContext: EngineSessionContext? = null
    private val toolIndex = mutableMapOf<String, IndexedTool>()
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    val hasActiveOnnxSession: Boolean
        get() = ortSession != null

    private data class IndexedTool(
        val descriptor: ToolDescriptor,
        val keywords: Set<String>,
        val requiredParameters: Set<String>,
    )

    override suspend fun initialize(context: EngineSessionContext) {
        this.sessionContext = context
        toolIndex.clear()
        context.tools.forEach { tool ->
            indexTool(tool)
        }
        initOnnxSession()
        Log.i(
            TAG,
            "Initialized NeuDecide with ${toolIndex.size} indexed tools on Google Pixel 8 Pro " +
                "(ONNX active: $hasActiveOnnxSession)",
        )
    }

    override suspend fun processTurn(
        input: UserTurnInput,
        onDelta: (String) -> Unit,
        onToolCall: suspend (ToolCall) -> JsonElement?,
    ): EngineTurnResult {
        val query = input.text.trim()
        if (query.isEmpty()) return EngineTurnResult()

        val matchedTool = matchTool(query)
        if (matchedTool != null) {
            val toolCallId = UUID.randomUUID().toString()
            val arguments = extractArguments(query)
            val call =
                ToolCall(
                    kind = MessageKind.ToolCall,
                    id = toolCallId,
                    name = matchedTool.descriptor.name,
                    args = arguments,
                )

            onDelta("Calling ${matchedTool.descriptor.name}…")
            val toolResult = onToolCall(call)
            val resultSummary = formatToolOutcome(toolResult)
            if (resultSummary.isNotEmpty()) {
                onDelta(" $resultSummary")
            }
            return EngineTurnResult(
                assistantText = "Calling ${matchedTool.descriptor.name}… $resultSummary".trim(),
                toolCalls = listOf(call),
            )
        }

        // Direct conversational response when query is not an action.
        val conversationalResponse = generateDirectResponse(query)
        onDelta(conversationalResponse)
        return EngineTurnResult(assistantText = conversationalResponse)
    }

    override suspend fun injectContext(text: String) {
        Log.d(TAG, "Injected context into NeuDecide: ${text.take(50)}")
    }

    override fun close() {
        try {
            ortSession?.close()
            ortSession = null
            ortEnv?.close()
            ortEnv = null
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            Log.w(TAG, "Error closing ONNX session", e)
        }
        toolIndex.clear()
        sessionContext = null
    }

    private fun initOnnxSession() {
        val modelFile = resolveModelFile() ?: return
        if (!modelFile.exists()) return

        try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            val opts =
                OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                }
            ortSession = env.createSession(modelFile.absolutePath, opts)
            Log.i(TAG, "Loaded NeuDecide ONNX model from ${modelFile.name}")
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            Log.w(TAG, "ONNX session initialization skipped: ${e.message}")
        }
    }

    private fun resolveModelFile(): File? {
        val candidates =
            listOfNotNull(
                File(context.filesDir, "models/neudecide.onnx"),
                File(context.filesDir, "models/neudecide_audio.onnx"),
                context.getExternalFilesDir(null)?.let { File(it, "models/neudecide.onnx") },
            )
        return candidates.firstOrNull { it.exists() }
    }

    private fun indexTool(tool: ToolDescriptor) {
        val keywords = mutableSetOf<String>()
        keywords.add(tool.name.lowercase(Locale.ROOT))
        tool.name.split('_', '-').forEach { part ->
            if (part.length > 2) keywords.add(part.lowercase(Locale.ROOT))
        }
        tool.description?.split(' ')?.forEach { word ->
            val clean = word.filter { it.isLetter() }.lowercase(Locale.ROOT)
            if (clean.length > 3) keywords.add(clean)
        }
        toolIndex[tool.name] =
            IndexedTool(
                descriptor = tool,
                keywords = keywords,
                requiredParameters = emptySet(),
            )
    }

    private fun matchTool(query: String): IndexedTool? {
        val lowerQuery = query.lowercase(Locale.ROOT)
        // Score tools by keyword match count.
        var bestScore = 0
        var bestMatch: IndexedTool? = null
        for (indexed in toolIndex.values) {
            var score = 0
            if (lowerQuery.contains(indexed.descriptor.name.lowercase(Locale.ROOT))) {
                score += 10
            }
            for (kw in indexed.keywords) {
                if (lowerQuery.contains(kw)) {
                    score += 2
                }
            }
            if (score > bestScore && score >= 2) {
                bestScore = score
                bestMatch = indexed
            }
        }
        return bestMatch
    }

    private fun extractArguments(query: String): JsonObject {
        val lower = query.lowercase(Locale.ROOT)
        return buildJsonObject {
            put("query", query)
            if (lower.contains("weather")) {
                val rawLoc =
                    when {
                        lower.contains(" in ") -> query.substring(lower.indexOf(" in ") + 4)
                        lower.contains(" for ") -> query.substring(lower.indexOf(" for ") + 5)
                        else -> ""
                    }
                val location = rawLoc.trimEnd('?', '.', '!', ' ').trim()
                if (location.isNotEmpty()) put("location", location)
            }
            if (lower.contains("remind") || lower.contains("task")) {
                val title =
                    query
                        .substringAfter("to ", query)
                        .substringAfter("that ", query)
                        .trimEnd('?', '.', '!')
                        .trim()
                put("title", title)
            }
        }
    }

    private fun formatToolOutcome(result: JsonElement?): String {
        if (result == null) return "Completed."
        val obj = result as? JsonObject ?: return "Done."
        val summary = obj["message"]?.toString() ?: obj["status"]?.toString() ?: "Done."
        return summary.trim('"')
    }

    private fun generateDirectResponse(query: String): String {
        val lower = query.lowercase(Locale.ROOT)
        return when {
            lower.contains("hello") || lower.contains("hi") -> "Hello! I am running locally on your Pixel 8 Pro."
            lower.contains("who are you") -> "I am Go Mode running directly on your device."
            lower.contains("status") -> "NeuDecide engine is active and ready for tool commands."
            else -> "I heard: $query. You can ask me to run any available tool."
        }
    }
}
