// Shared voice controller and ordered quiet-context/actionable-alert buffering.
package com.fghbuild.gomode.voice

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

// Keep the latest overview while retaining each actionable occurrence until the active turn ends.
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
            context = text
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
        val update = VoiceContextUpdate((listOfNotNull(context) + alerts).joinToString("\n"), alerts.isNotEmpty())
        clear()
        return update
    }
}
