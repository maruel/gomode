// Converts captured PCM and WebRTC RMS into a consistent, speech-sensitive microphone meter.
package com.fghbuild.gomode.voice

import org.webrtc.RTCStatsReport
import kotlin.math.log10
import kotlin.math.sqrt

// Show -60 dBFS as silence and reach full height at -12 dBFS. Raw linear RMS
// makes ordinary speech almost indistinguishable from the minimum-height bars.
internal fun microphoneLevelFromRms(rms: Float): Float =
    if (!rms.isFinite() || rms <= 0f) 0f else ((20f * log10(rms) + 60f) / 48f).coerceIn(0f, 1f)

// A 50ms window of 16kHz, signed little-endian PCM. Capture already owns these
// samples, so metering requires neither another microphone nor recognizer callbacks.
internal class MicrophoneRmsWindow {
    private var squares = 0.0
    private var samples = 0

    fun append(
        pcm: ByteArray,
        count: Int,
    ): Float? {
        for (index in 0 until count - 1 step 2) {
            val sample = ((pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)) / 32768.0
            squares += sample * sample
            samples++
        }
        if (samples < 800) return null
        val rms = sqrt(squares / samples).toFloat()
        squares = 0.0
        samples = 0
        return rms
    }
}

internal class RtcMicrophoneLevel {
    private data class Energy(
        val total: Double,
        val duration: Double,
    )

    private val previous = mutableMapOf<String, Energy>()

    fun clear() = previous.clear()

    fun read(report: RTCStatsReport): Float {
        var rms = 0f
        report.statsMap.forEach { (id, stat) ->
            val type = stat.type.lowercase()
            if ("inbound" in type || type.startsWith("remote-")) return@forEach
            val kind = stat.members["kind"] ?: stat.members["mediaType"]
            if (kind != null && kind != "audio") return@forEach
            val direct = (stat.members["audioLevel"] as? Number)?.toFloat()
            if (direct != null && direct.isFinite()) {
                rms = maxOf(rms, direct.coerceIn(0f, 1f))
                return@forEach
            }
            val energy = (stat.members["totalAudioEnergy"] as? Number)?.toDouble() ?: return@forEach
            val duration = (stat.members["totalSamplesDuration"] as? Number)?.toDouble() ?: return@forEach
            if (!energy.isFinite() || !duration.isFinite()) return@forEach
            val before = previous.put(id, Energy(energy, duration)) ?: return@forEach
            val elapsed = duration - before.duration
            val delta = energy - before.total
            if (elapsed > 0 && delta >= 0) rms = maxOf(rms, sqrt(delta / elapsed).toFloat())
        }
        return microphoneLevelFromRms(rms)
    }
}
