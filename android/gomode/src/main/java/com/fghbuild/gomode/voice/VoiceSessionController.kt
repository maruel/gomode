// Shared voice controller and ordered quiet-context/actionable-alert buffering.
//
// Connected voice also reads gomode://notifications. The opening snapshot, repeated occurrence IDs,
// withdrawals, and expiry send nothing. Each new alert reaches the model as one "New notification:
// title: body [itemId]" line with context.update.generate: true, without inventing a user
// utterance. Native multi-server alerts carry the same server header as context. Updates wait for
// the active model, tool, and playback turn. Ending voice or replacing the authenticated scope
// stops monitoring.
package com.fghbuild.gomode.voice

import com.fghbuild.gomode.service.boundModelContext
import kotlinx.coroutines.flow.StateFlow

/**
 * VoiceSessionController is the shell-facing surface of an active voice mode.
 *
 * A controller owns one session lifetime. Creating a new controller or calling
 * [connect] again replaces the previous session.
 */
internal interface VoiceSessionController {
    val state: StateFlow<VoiceState>

    // Recovery preserves transcript and conversation identity. A server handoff
    // supplies the existing ID to its new controller; an ordinary start makes one.
    fun connect(
        preserveTranscript: Boolean = false,
        conversationID: String? = null,
    )

    fun disconnect()

    fun setMicrophoneMode(mode: MicrophoneMode)

    fun selectAudioDevice(deviceId: Int)

    fun clearTranscript()

    fun injectText(
        text: String,
        generate: Boolean = false,
    )

    fun setError(message: String)

    /** close ends the session and releases resources the controller owns. */
    fun close()
}

internal data class VoiceContextUpdate(
    val text: String,
    val generate: Boolean,
)

// TODO: Accumulated changes can carry a superseded version of the same section: two "Active
// server:" lines arrive in one update when focus changes twice during a tool call
// (ServerVoiceSwitchTest#spokenTextSwitchKeepsOneConversationAcrossBothDirections). The model
// reads the newest last, so behavior is correct, but the stale copy wastes tokens. The cause is
// VoiceConversation.contextUpdate() committing its sent map when GoModeApp/CarDialVoiceOwner call
// it, before delivery. Compute the diff when this buffer drains instead, so it holds no text and
// the over-budget fallback disappears. The browser has the same shape (web/src/VoiceSession.ts).
// Retain every context change and actionable occurrence until the active turn ends. Context updates
// carry changes only, so they accumulate; an over-budget total keeps the newest whole.
internal class PendingVoiceContext {
    private var context: String? = null
    private val alerts = mutableListOf<String>()

    fun add(
        text: String,
        generate: Boolean,
    ): Boolean {
        if (generate) {
            if (alerts.size >= 64) return false
            alerts.add(text)
        } else {
            context =
                boundModelContext(listOfNotNull(context, text).joinToString("\n\n")).ifEmpty { text }
        }
        return true
    }

    fun isNotEmpty() = context != null || alerts.isNotEmpty()

    fun hasAlerts() = alerts.isNotEmpty()

    fun clear() {
        context = null
        alerts.clear()
    }

    fun drain(): VoiceContextUpdate? {
        if (!isNotEmpty()) return null
        val update = VoiceContextUpdate((listOfNotNull(context) + alerts).joinToString("\n\n"), alerts.isNotEmpty())
        clear()
        return update
    }
}
