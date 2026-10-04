// Origin-scoped WebView frontend tool declarations and synchronous voice dispatch.

package com.fghbuild.gomode.ui.web

import android.webkit.WebView
import com.caic.voicegateway.sdk.v1.ToolDeclaration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

internal object FrontendVoiceTools {
    private var generation = 0L
    private var owner = WeakReference<WebView>(null)

    @Volatile
    var declarations: List<ToolDeclaration> = emptyList()
        private set

    fun register(
        view: WebView,
        payload: String,
    ) {
        val tools = Json.decodeFromString<List<ToolDeclaration>>(payload)
        require(tools.map { it.name }.distinct().size == tools.size)
        require(tools.none { it.name == "hang_up" })
        generation++
        owner = WeakReference(view)
        declarations = tools
    }

    fun clear(view: WebView) {
        if (owner.get() !== view) return
        generation++
        owner.clear()
        declarations = emptyList()
    }

    suspend fun execute(
        name: String,
        args: JsonObject,
        isCurrent: () -> Boolean,
    ): JsonObject? =
        withContext(Dispatchers.Main) {
            if (!isCurrent()) return@withContext buildJsonObject { put("error", "Voice session ended.") }
            if (declarations.none { it.name == name }) return@withContext null
            val view = owner.get() ?: return@withContext null
            val started = generation
            withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine { continuation ->
                    val script =
                        "window.executeGoModeVoiceTool?.(${JsonPrimitive(name)},$args) " +
                            "?? {error:'Frontend voice tool is unavailable.'}"
                    view.evaluateJavascript(script) { result ->
                        if (!continuation.isActive) return@evaluateJavascript
                        val value =
                            if (owner.get() !== view || generation != started) {
                                buildJsonObject { put("error", "Hosted page changed during voice tool execution.") }
                            } else {
                                runCatching { Json.parseToJsonElement(result).jsonObject }
                                    .getOrElse {
                                        buildJsonObject {
                                            put(
                                                "error",
                                                "Frontend voice tool returned an invalid result.",
                                            )
                                        }
                                    }
                            }
                        continuation.resume(value)
                    }
                }
            } ?: buildJsonObject { put("error", "Frontend voice tool timed out.") }
        }
}
