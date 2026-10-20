// Voice session surface shared by the cloud WebRTC mode and the on-device text mode.
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

    fun toggleMute()

    fun selectAudioDevice(deviceId: Int)

    fun clearTranscript()

    fun injectText(text: String)

    fun setError(message: String)

    /** close ends the session and releases resources the controller owns. */
    fun close()
}
