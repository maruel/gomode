// Reduces outgoing microphone amplitude during assistant speech without disabling interruptions.
package com.fghbuild.gomode.voice

import kotlinx.coroutines.flow.StateFlow
import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val SPEAKING_MICROPHONE_GAIN = 0.1f // -20 dB, shared by RTC and device recognition.

// The SDK's external processor is a native process-wide singleton. Keep its factory
// for the process lifetime, while releasing the session state when its owner closes.
internal object RtcMicrophoneProcessing {
    @Volatile
    private var owner: StateFlow<VoiceState>? = null
    private val factory by lazy {
        ExternalAudioProcessingFactory().apply {
            setCapturePostProcessing(MicrophoneDucking { owner?.value?.speaking == true })
        }
    }

    fun acquire(state: StateFlow<VoiceState>): ExternalAudioProcessingFactory {
        owner = state
        return factory
    }

    fun release(state: StateFlow<VoiceState>) {
        if (owner === state) owner = null
    }
}

internal class MicrophoneDucking(
    private val speaking: () -> Boolean,
) : ExternalAudioProcessingFactory.AudioProcessing {
    private lateinit var gain: MicrophoneGainRamp

    override fun initialize(
        sampleRateHz: Int,
        numChannels: Int,
    ) {
        gain = MicrophoneGainRamp(sampleRateHz, speaking())
    }

    override fun reset(newRate: Int) = gain.configure(newRate)

    // WebRTC supplies native-endian float samples after AEC and gain control.
    override fun process(
        numBands: Int,
        numFrames: Int,
        buffer: ByteBuffer,
    ) {
        gain.setSpeaking(speaking())
        buffer.order(ByteOrder.nativeOrder())
        for (offset in 0 until numFrames * Float.SIZE_BYTES step Float.SIZE_BYTES) {
            buffer.putFloat(offset, buffer.getFloat(offset) * gain.next())
        }
    }
}

// Audio-thread-owned ramp state; each sample advances it independently of capture chunk size.
internal class MicrophoneGainRamp(
    private var sampleRateHz: Int,
    speaking: Boolean,
) {
    private var current = if (speaking) SPEAKING_MICROPHONE_GAIN else 1f
    private var target = current
    private var remaining = 0
    private var step = 0f

    fun configure(sampleRateHz: Int) {
        if (remaining > 0) {
            remaining = maxOf(1, (remaining.toLong() * sampleRateHz / this.sampleRateHz).toInt())
            step = (target - current) / remaining
        }
        this.sampleRateHz = sampleRateHz
    }

    fun setSpeaking(speaking: Boolean) {
        val target = if (speaking) SPEAKING_MICROPHONE_GAIN else 1f
        if (this.target == target) return
        this.target = target
        remaining = maxOf(1, sampleRateHz * (if (speaking) 30 else 100) / 1_000)
        step = (target - current) / remaining
    }

    fun next(): Float {
        if (remaining > 0) {
            current += step
            if (--remaining == 0) current = target
        }
        return current
    }

    fun processPcm(
        frame: ByteArray,
        count: Int,
    ) {
        for (offset in 0 until count step 2) {
            val sample = ((frame[offset].toInt() and 0xff) or (frame[offset + 1].toInt() shl 8)).toShort()
            val attenuated = (sample * next()).toInt()
            frame[offset] = attenuated.toByte()
            frame[offset + 1] = (attenuated shr 8).toByte()
        }
    }
}
