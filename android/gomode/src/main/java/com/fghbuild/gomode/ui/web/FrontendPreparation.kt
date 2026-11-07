// Owns document-bound frontend preparation and authenticated native voice-lifetime publication.
package com.fghbuild.gomode.ui.web

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private val preparedVoiceSession = MutableStateFlow<String?>(null)
    private val voiceSessionID = MutableStateFlow<String?>(null)
    private var voiceSessionCurrent: () -> Boolean = { false }
    private var voiceSubscriber: VoiceSubscriber? = null

    private class VoiceSubscriber(
        val document: String,
        val token: String,
        val scope: String,
        val reply: (String) -> Unit,
    )

    fun navigate() {
        document = UUID.randomUUID().toString()
        invalidateIdentity()
    }

    fun invalidateIdentity() {
        publishVoiceSession(null)
        voiceSubscriber = null
        preparedVoiceSession.value = null
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
            .put("voiceSessionVersion", 1)
            .toString()
    }

    fun accepts(payload: JSONObject): Boolean =
        payload.opt("document") == document && token != null && payload.opt("token") == token &&
            payload.opt("ready") is Boolean

    fun update(
        payload: JSONObject,
        reply: (String) -> Unit,
    ) {
        check(accepts(payload))
        mutableReady.value = payload.getBoolean("ready")
        val scope = payload.opt("scope") as? String
        voiceSubscriber =
            if (mutableReady.value && payload.opt("voiceSessionVersion") == 1 &&
                scope != null && scope.isNotBlank() && scope.length <= 128
            ) {
                VoiceSubscriber(document, checkNotNull(token), scope, reply)
            } else {
                null
            }
        val current = voiceSessionID.value?.takeIf { voiceSessionCurrent() }
        preparedVoiceSession.value = current?.takeIf { payload.opt("voiceSessionPrepared") == it }
        publishVoiceSession(current)
    }

    fun setVoiceSession(
        sessionID: String,
        isCurrent: () -> Boolean,
    ) {
        voiceSessionID.value = sessionID
        voiceSessionCurrent = isCurrent
        preparedVoiceSession.value = null
        publishVoiceSession(sessionID.takeIf { isCurrent() })
    }

    fun clearVoiceSession(sessionID: String) {
        if (voiceSessionID.value != sessionID) return
        voiceSessionID.value = null
        voiceSessionCurrent = { false }
        preparedVoiceSession.value = null
        publishVoiceSession(null)
    }

    // Older clients have no subscriber and do not acquire an initial-publication gate.
    suspend fun awaitVoiceSessionPrepared(sessionID: String) {
        if (voiceSubscriber == null) return
        combine(ready, preparedVoiceSession, voiceSessionID) { ready, prepared, active ->
            !ready || voiceSubscriber == null || active != sessionID || prepared == sessionID
        }.first { it }
    }

    private fun publishVoiceSession(sessionID: String?) {
        val subscriber = voiceSubscriber ?: return
        val message =
            JSONObject()
                .put("document", subscriber.document)
                .put("token", subscriber.token)
                .put("scope", subscriber.scope)
                .put("voiceSessionVersion", 1)
                .put("sessionID", sessionID ?: JSONObject.NULL)
                .toString()
        runCatching { subscriber.reply(message) }.onFailure {
            voiceSubscriber = null
            Log.w("GoModePreparation", "Hosted voice-lifetime receiver is unavailable", it)
        }
    }
}
