// Synthesizes native voice-mode connection and disconnection chimes.
package com.fghbuild.gomode.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

private const val TAG = "GoModeVoiceChime"
private const val SAMPLE_RATE = 16_000
private const val NOTE_DURATION_MS = 120
private const val NOTE_GAP_MS = 35
private const val ENVELOPE_MS = 12
private const val CHIME_GAIN = 0.12

internal enum class VoiceChimeCue {
    CONNECTED,
    DISCONNECTED,
}

internal fun voiceChimeFrequencies(cue: VoiceChimeCue): DoubleArray =
    when (cue) {
        VoiceChimeCue.CONNECTED -> doubleArrayOf(523.25, 659.25)
        VoiceChimeCue.DISCONNECTED -> doubleArrayOf(659.25, 523.25)
    }

interface VoiceChimePlayer {
    fun playConnected()

    fun playDisconnected(onComplete: () -> Unit)
}

internal class VoiceModeChime(
    private val player: VoiceChimePlayer,
) {
    private var active = false

    fun connected() {
        if (active) return
        active = true
        player.playConnected()
    }

    fun disconnected(onComplete: () -> Unit): Boolean {
        if (!active) return false
        active = false
        player.playDisconnected(onComplete)
        return true
    }
}

class AndroidVoiceChime : VoiceChimePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null

    override fun playConnected() {
        play(VoiceChimeCue.CONNECTED)
    }

    override fun playDisconnected(onComplete: () -> Unit) {
        play(VoiceChimeCue.DISCONNECTED, onComplete)
    }

    private fun play(
        cue: VoiceChimeCue,
        onComplete: (() -> Unit)? = null,
    ) {
        playbackJob?.cancel()
        playbackJob =
            scope.launch {
                val samples = renderVoiceChime(voiceChimeFrequencies(cue))
                var track: AudioTrack? = null
                try {
                    val minimumBuffer =
                        AudioTrack.getMinBufferSize(
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                    track =
                        AudioTrack
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
                                    .setSampleRate(SAMPLE_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build(),
                            ).setBufferSizeInBytes(max(samples.size * Short.SIZE_BYTES, minimumBuffer))
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .build()
                    track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    track.play()
                    delay(voiceChimeDurationMs())
                } catch (error: CancellationException) {
                    throw error
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Exception,
                ) {
                    Log.w(TAG, "Could not play voice chime", error)
                } finally {
                    track?.release()
                }
                withContext(Dispatchers.Main.immediate) {
                    onComplete?.invoke()
                }
            }
    }
}

internal fun renderVoiceChime(frequencies: DoubleArray): ShortArray {
    val noteSamples = NOTE_DURATION_MS * SAMPLE_RATE / 1_000
    val gapSamples = NOTE_GAP_MS * SAMPLE_RATE / 1_000
    val envelopeSamples = ENVELOPE_MS * SAMPLE_RATE / 1_000
    val samples = ShortArray(noteSamples * frequencies.size + gapSamples * (frequencies.size - 1))
    frequencies.forEachIndexed { noteIndex, frequency ->
        val noteOffset = noteIndex * (noteSamples + gapSamples)
        repeat(noteSamples) { sampleIndex ->
            val envelope =
                minOf(
                    1.0,
                    sampleIndex.toDouble() / envelopeSamples,
                    (noteSamples - sampleIndex - 1).toDouble() / envelopeSamples,
                )
            val wave = sin(2.0 * PI * frequency * sampleIndex / SAMPLE_RATE)
            samples[noteOffset + sampleIndex] = (wave * envelope * CHIME_GAIN * Short.MAX_VALUE).toInt().toShort()
        }
    }
    return samples
}

private fun voiceChimeDurationMs(): Long = (NOTE_DURATION_MS * 2 + NOTE_GAP_MS).toLong()
