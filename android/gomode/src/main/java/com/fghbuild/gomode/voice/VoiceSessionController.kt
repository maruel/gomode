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

    fun connect(preserveTranscript: Boolean = false)

    fun disconnect()

    fun toggleMute()

    fun selectAudioDevice(deviceId: Int)

    fun clearTranscript()

    fun injectText(text: String)

    fun setError(message: String)

    /** close ends the session and releases resources the controller owns. */
    fun close()
}
