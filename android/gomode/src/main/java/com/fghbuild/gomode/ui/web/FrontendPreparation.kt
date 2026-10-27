// Owns ephemeral document and authenticated-identity challenges for frontend preparation.
package com.fghbuild.gomode.ui.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

// All mutations run on the WebView's main thread. A trusted main-frame
// document-start message owns navigation; the reply is captured by that document.
internal class FrontendPreparation {
    @Volatile
    var document: String = UUID.randomUUID().toString()
        private set

    private var token: String? = null
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()

    fun navigate() {
        document = UUID.randomUUID().toString()
        invalidateIdentity()
    }

    fun invalidateIdentity() {
        token = null
        mutableReady.value = false
    }

    fun acceptsAuth(payload: JSONObject): Boolean {
        val request = payload.opt("frontendRequest") as? String ?: return false
        return payload.opt("frontendDocument") == document && request.isNotBlank() && request.length <= 128
    }

    // Authentication acknowledges a particular request from a particular document.
    // The frontend captures this reply in its identity owner, never reads a fresh
    // native token when delayed tool/data work eventually completes.
    fun acknowledge(payload: JSONObject): String? {
        if (!acceptsAuth(payload)) return null
        val request = payload.getString("frontendRequest")
        invalidateIdentity()
        val current = UUID.randomUUID().toString()
        token = current
        return JSONObject()
            .put("frontendDocument", document)
            .put("frontendRequest", request)
            .put("frontendToken", current)
            .toString()
    }

    fun accepts(payload: JSONObject): Boolean =
        payload.opt("document") == document && token != null && payload.opt("token") == token &&
            payload.opt("ready") is Boolean

    fun update(payload: JSONObject) {
        check(accepts(payload))
        mutableReady.value = payload.getBoolean("ready")
    }
}
