// Origin-scoped frontend declarations and bounded, correlated asynchronous WebView tool dispatch.
package com.fghbuild.gomode.ui.web

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.net.toUri
import com.caic.voicegateway.sdk.v1.ToolDeclaration
import com.fghbuild.gomode.voice.nativeVoiceToolNames
import kotlinx.coroutines.CancellableContinuation
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
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.resume

internal class FrontendVoiceTools(
    private val canDispatch: () -> Boolean = { true },
) {
    private data class Invocation(
        val continuation: CancellableContinuation<JsonObject>,
        val view: WebView,
        val generation: Long,
        val binding: JsonObject,
        val isCurrent: () -> Boolean,
    )

    private val pending = mutableMapOf<String, Invocation>()
    private var generation = 0L
    private var owner = WeakReference<WebView>(null)
    private var registeredURL: String? = null
    private var binding = JsonObject(emptyMap())
    val revision: Long get() = generation
    val isReady: Boolean get() = canDispatch()

    @Volatile
    var declarations: List<ToolDeclaration> = emptyList()
        private set

    fun register(
        view: WebView,
        payload: String,
        preparation: JSONObject,
    ) {
        val tools = Json.decodeFromString<List<ToolDeclaration>>(payload)
        require(tools.map { it.name }.distinct().size == tools.size)
        require(tools.none { it.name in nativeVoiceToolNames })
        val next =
            buildJsonObject {
                for (key in listOf("document", "token", "scope")) {
                    val value = preparation.getString(key)
                    require(value.isNotBlank() && value.length <= 128)
                    put(key, value)
                }
            }
        val url = requireNotNull(view.url) { "Frontend document URL is unavailable" }
        invalidate()
        owner = WeakReference(view)
        registeredURL = url
        binding = next
        declarations = tools
    }

    fun clear(view: WebView) {
        if (owner.get() === view) clear()
    }

    fun clear() {
        invalidate()
        owner.clear()
        registeredURL = null
        binding = JsonObject(emptyMap())
        declarations = emptyList()
    }

    // Main-thread ownership ensures cancellation and result delivery settle each invocation once.
    fun invalidate() {
        generation++
        val retired = pending.toMap()
        pending.clear()
        retired.forEach { (id, call) ->
            cancelJavascript(id, call)
            if (call.continuation.isActive) call.continuation.resume(error("Hosted identity or page changed."))
        }
    }

    // The WebMessage owner validates trusted origin, main frame, page generation and authentication first.
    fun complete(
        view: WebView,
        payload: String,
    ) {
        val message = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
        val id = (message["invocation"] as? JsonPrimitive)?.content ?: return
        val call = pending[id] ?: return
        if (call.view !== view || call.binding.any { (key, value) -> message[key] != value }) return
        if (!call.continuation.isActive) return
        pending.remove(id)
        val result =
            if (!current(call)) {
                error("Hosted page changed during voice tool execution.")
            } else {
                message["result"] as? JsonObject ?: error("Frontend voice tool returned an invalid result.")
            }
        call.continuation.resume(result)
    }

    suspend fun execute(
        name: String,
        args: JsonObject,
        isCurrent: () -> Boolean,
    ): JsonObject? =
        withContext(Dispatchers.Main) {
            if (!isCurrent()) return@withContext error("Voice session ended.")
            if (!canDispatch()) return@withContext error("Hosted frontend is not ready.")
            if (declarations.none { it.name == name }) return@withContext null
            val view = owner.get() ?: return@withContext null
            if (!ownsOrigin(view)) return@withContext error("Hosted page origin changed.")
            val id = UUID.randomUUID().toString()
            withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine { continuation ->
                    val call = Invocation(continuation, view, generation, binding, isCurrent)
                    pending[id] = call
                    continuation.invokeOnCancellation {
                        // Cancellation may originate off Main; WebView access always returns to its thread.
                        val cancel = { if (pending.remove(id) === call) cancelJavascript(id, call) }
                        if (Looper.myLooper() ==
                            Looper.getMainLooper()
                        ) {
                            cancel()
                        } else {
                            Handler(Looper.getMainLooper()).post { cancel() }
                        }
                    }
                    val request =
                        buildJsonObject {
                            binding.forEach { (key, value) -> put(key, value) }
                            put("invocation", id)
                            put("name", name)
                            put("args", args)
                        }
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    if (!current(call)) {
                        pending.remove(id)
                        continuation.resume(error("Hosted page changed before voice tool execution."))
                        return@suspendCancellableCoroutine
                    }
                    view.evaluateJavascript("window.dispatchGoModeVoiceTool?.($request)", null)
                }
            } ?: error("Frontend voice tool timed out.")
        }

    private fun current(call: Invocation): Boolean =
        call.isCurrent() && canDispatch() && owner.get() === call.view && generation == call.generation &&
            ownsOrigin(call.view)

    private fun cancelJavascript(
        id: String,
        call: Invocation,
    ) {
        if (owner.get() !== call.view || !ownsOrigin(call.view)) return
        val request =
            buildJsonObject {
                call.binding.forEach { (key, value) -> put(key, value) }
                put("invocation", id)
            }
        call.view.evaluateJavascript("window.cancelGoModeVoiceTool?.($request)", null)
    }

    private fun ownsOrigin(view: WebView): Boolean =
        view.url?.let { url -> registeredURL?.let { isTrustedPermissionOrigin(url.toUri(), it) } } == true

    private fun error(message: String) = buildJsonObject { put("error", message) }
}
