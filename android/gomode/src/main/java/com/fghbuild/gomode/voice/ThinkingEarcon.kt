// Streams the shared piano score while a voice turn is thinking.
package com.fghbuild.gomode.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

internal interface ThinkingEarcon {
    fun setThinking(active: Boolean)
}

internal class AndroidThinkingEarcon(
    private val context: Context,
    private val preview: Boolean = false,
    private val onPlaybackChange: ((Boolean, Exception?) -> Unit)? = null,
) : ThinkingEarcon {
    private class Playback(
        val revision: Long,
    ) {
        @Volatile var cancelled = false
        var track: AudioTrack? = null
        var focus: AudioFocusRequest? = null
    }

    private val lock = Any()
    private var current: Playback? = null
    private var revision = 0L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val audio by lazy { context.getSystemService(AudioManager::class.java) }

    private fun changed(
        revision: Long,
        playing: Boolean,
        error: Exception?,
    ) {
        val listener = onPlaybackChange ?: return
        val notify = {
            synchronized(lock) {
                if (this.revision == revision) listener(playing, error)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) notify() else main.post { notify() }
    }

    private fun abandonFocus(playback: Playback) {
        playback.focus?.let { audio.abandonAudioFocusRequest(it) }
        playback.focus = null
    }

    override fun setThinking(active: Boolean) {
        synchronized(lock) {
            if (!active) {
                revision++
                val playback = current
                current = null
                if (playback == null) {
                    changed(revision, false, null)
                    return
                }
                playback.cancelled = true
                // Pause immediately silences queued frames and interrupts any blocking worker write.
                try {
                    playback.track?.pause()
                    playback.track?.flush()
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Exception, // Preserve voice state.
                ) {
                    Log.w("GoModeThinkingEarcon", "Could not pause thinking piano", error)
                }
                abandonFocus(playback)
                changed(revision, false, null)
                return
            }
            if (current != null) return
            val playback = Playback(++revision)
            current = playback
            try {
                if (preview) {
                    val focus =
                        AudioFocusRequest
                            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attributes())
                            .setOnAudioFocusChangeListener { change ->
                                synchronized(lock) {
                                    if (change <= 0 && current === playback) setThinking(false)
                                }
                            }.build()
                    playback.focus = focus
                    check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        "Audio focus is unavailable"
                    }
                }
                Thread({ stream(playback) }, "GoModeThinkingPiano").start()
                changed(playback.revision, true, null)
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception, // Audio failure must not break settings.
            ) {
                current = null
                playback.cancelled = true
                abandonFocus(playback)
                changed(playback.revision, false, error)
                Log.w("GoModeThinkingEarcon", "Could not start thinking piano", error)
            }
        }
    }

    private fun stream(playback: Playback) {
        var output: AudioTrack? = null
        var failure: Exception? = null
        try {
            if (playback.cancelled) return
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            output = createTrack()
            val piano = ThinkingPiano(ThinkingScore.load(context), loop = !preview)
            val samples = ShortArray(THINKING_SAMPLE_RATE / 50)
            piano.render(samples)
            if (playback.cancelled) return
            check(output.write(samples, 0, samples.size) == samples.size) { "Incomplete thinking piano priming" }
            synchronized(lock) {
                if (current !== playback) return
                playback.track = output
                output.play()
            }
            while (!playback.cancelled && !piano.finished) {
                piano.render(samples)
                var offset = 0
                while (offset < samples.size && !playback.cancelled) {
                    val written = output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (playback.cancelled) break
                    check(written > 0) { "Thinking piano write failed: $written" }
                    offset += written
                }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception, // Audio failure must not end the voice call.
        ) {
            if (!playback.cancelled) {
                failure = error
                Log.w("GoModeThinkingEarcon", "Could not play thinking piano", error)
            }
        } finally {
            // Detach under the lifecycle lock; release only after this worker has stopped writing.
            val report =
                synchronized(lock) {
                    playback.track = null
                    val report = current === playback
                    if (report) {
                        current = null
                        abandonFocus(playback)
                    }
                    report
                }
            output?.release()
            if (report) changed(playback.revision, false, failure)
        }
    }

    private fun createTrack(): AudioTrack {
        val minimum =
            AudioTrack.getMinBufferSize(
                THINKING_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        check(minimum > 0) { "Thinking piano format is unavailable" }
        return AudioTrack
            .Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(
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

    private fun attributes(): AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(
                if (preview) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
            ).setContentType(
                if (preview) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SONIFICATION,
            ).build()
}
