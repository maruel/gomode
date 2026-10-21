// Streams a quiet live synth pad while a half-duplex voice turn is thinking.
package com.fghbuild.gomode.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log

internal interface ThinkingEarcon {
    fun setThinking(active: Boolean)
}

internal class AndroidThinkingEarcon : ThinkingEarcon {
    private class Playback {
        @Volatile var cancelled = false
        var track: AudioTrack? = null
    }

    private val lock = Any()
    private var current: Playback? = null

    override fun setThinking(active: Boolean) {
        synchronized(lock) {
            if (!active) {
                val playback = current ?: return
                current = null
                playback.cancelled = true
                // Pause immediately silences queued frames and interrupts any blocking worker write.
                try {
                    playback.track?.pause()
                    playback.track?.flush()
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Exception, // Preserve voice state.
                ) {
                    Log.w("GoModeThinkingEarcon", "Could not pause thinking pad", error)
                }
                return
            }
            if (current != null) return
            val playback = Playback()
            current = playback
            Thread({ stream(playback) }, "GoModeThinkingPad").start()
        }
    }

    private fun stream(playback: Playback) {
        var output: AudioTrack? = null
        try {
            if (playback.cancelled) return
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            output = createTrack()
            val pad = ThinkingPad()
            val samples = ShortArray(THINKING_SAMPLE_RATE / 50)
            pad.render(samples)
            if (playback.cancelled) return
            check(output.write(samples, 0, samples.size) == samples.size) { "Incomplete thinking pad priming" }
            synchronized(lock) {
                if (current !== playback) return
                playback.track = output
                output.play()
            }
            while (!playback.cancelled) {
                pad.render(samples)
                var offset = 0
                while (offset < samples.size && !playback.cancelled) {
                    val written = output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (playback.cancelled) break
                    check(written > 0) { "Thinking pad write failed: $written" }
                    offset += written
                }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception, // Audio failure must not end the voice call.
        ) {
            if (!playback.cancelled) Log.w("GoModeThinkingEarcon", "Could not play thinking pad", error)
        } finally {
            // Detach under the lifecycle lock; release only after this worker has stopped writing.
            synchronized(lock) {
                playback.track = null
                if (current === playback) current = null
            }
            output?.release()
        }
    }

    private fun createTrack(): AudioTrack {
        val minimum =
            AudioTrack.getMinBufferSize(
                THINKING_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        check(minimum > 0) { "Thinking pad format is unavailable" }
        return AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).setAudioFormat(
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(THINKING_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            ).setBufferSizeInBytes(maxOf(minimum, THINKING_SAMPLE_RATE * Short.SIZE_BYTES * 80 / 1_000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
