// Android Telecom self-managed call boundary for a voice session.
package com.fghbuild.gomode.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CallAudioState(
    val devices: List<AudioDevice> = emptyList(),
    val selectedDeviceId: Int? = null,
    val error: String? = null,
    val switching: Boolean = false,
)

/**
 * VoiceCallController maps one active voice session to an Android Telecom
 * self-managed call. A car or headset hang-up arrives as [start]'s
 * onTelecomDisconnect callback.
 *
 * The implementation owns the Telecom call lifecycle. A session calls [start]
 * once, [activate] when the voice transport is ready, and [endLocal] for an
 * overlay hang-up.
 */
internal interface VoiceCallController {
    val audioState: StateFlow<CallAudioState>

    fun selectAudioDevice(deviceId: Int)

    /** isSupported reports whether this device can host a self-managed call. */
    fun isSupported(): Boolean

    /**
     * start adds an outgoing audio call. It returns true when Telecom reports the
     * call ready and false when Telecom rejects it or the device lacks support.
     * onTelecomDisconnect runs on a car or headset hang-up.
     */
    suspend fun start(onTelecomDisconnect: () -> Unit): Boolean

    /** activate marks the call active after the voice transport is ready. */
    fun activate()

    /** endLocal disconnects a call this app owns. It is a no-op after a Telecom hang-up. */
    fun endLocal()
}

/** NoopVoiceCallController serves devices and tests without Telecom. */
internal class NoopVoiceCallController : VoiceCallController {
    override val audioState = MutableStateFlow(CallAudioState()).asStateFlow()

    override fun selectAudioDevice(deviceId: Int) = Unit

    override fun isSupported(): Boolean = false

    override suspend fun start(onTelecomDisconnect: () -> Unit): Boolean = false

    override fun activate() = Unit

    override fun endLocal() = Unit
}
