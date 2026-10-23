// Synthesizes the shared piano score with bounded voices, pedal release, and warm feedback echoes.
package com.fghbuild.gomode.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

internal const val THINKING_SAMPLE_RATE = 24_000

internal class ThinkingPiano(
    private val score: ThinkingScore,
    private val loop: Boolean = true,
) {
    private class Voice(
        note: PianoNote,
        score: ThinkingScore,
    ) {
        private val frequency = 440 * 2.0.pow((note.midi - 69) / 12.0)
        private val decay = 2.4 * sqrt(220 / frequency)
        private val partials = score.partials.filter { frequency * it.ratio < THINKING_SAMPLE_RATE * 0.45 }
        private val previous = DoubleArray(partials.size)
        private val current = DoubleArray(partials.size)
        private val a = DoubleArray(partials.size)
        private val b = DoubleArray(partials.size)
        private val gain = score.gain * note.level
        private val attackFrames = score.attack * THINKING_SAMPLE_RATE
        private val holdFrames = (note.duration * THINKING_SAMPLE_RATE).roundToInt()
        private val releaseFrames = score.release * THINKING_SAMPLE_RATE
        private var age = 0

        val finished: Boolean get() = age >= holdFrames + releaseFrames

        init {
            for (index in partials.indices) {
                val partial = partials[index]
                val angle = 2 * PI * frequency * partial.ratio / THINKING_SAMPLE_RATE
                val damping = exp(-partial.decay / (decay * THINKING_SAMPLE_RATE))
                a[index] = 2 * cos(angle) * damping
                b[index] = damping * damping
                previous[index] = -sin(angle) * partial.level / damping
            }
        }

        fun next(): Double {
            var mixed = 0.0
            for (index in partials.indices) {
                mixed += current[index]
                val next = a[index] * current[index] - b[index] * previous[index]
                previous[index] = current[index]
                current[index] = next
            }
            val attack = (age / attackFrames).coerceAtMost(1.0)
            val release = (1 - (age - holdFrames).coerceAtLeast(0) / releaseFrames).coerceAtLeast(0.0)
            age++
            return mixed * gain * attack * release * release
        }
    }

    private class Echo(
        private val echo: PianoEcho,
    ) {
        private val samples = DoubleArray((echo.delay * THINKING_SAMPLE_RATE).roundToInt())
        private var index = 0
        private var warmth = 0.0

        fun next(input: Double): Double {
            warmth += 0.46 * (samples[index] - warmth)
            samples[index] = input + warmth * echo.feedback
            index = (index + 1) % samples.size
            return warmth * echo.level
        }
    }

    private val voices = ArrayList<Voice>(32)
    private val echoes = score.echoes.map { Echo(it) }
    private val loopFrames = (score.duration * THINKING_SAMPLE_RATE).roundToLong()
    private var frame = -(THINKING_SAMPLE_RATE * 0.2).roundToLong()
    private var note = 0
    private var warmth = 0.0

    val finished: Boolean get() = !loop && frame >= loopFrames

    fun render(output: ShortArray) {
        for (index in output.indices) {
            if (finished) {
                output[index] = 0
                continue
            }
            if (frame < 0) {
                output[index] = 0
                frame++
                continue
            }
            val position = frame % loopFrames
            if (position == 0L) note = 0
            while (note < score.notes.size && (score.notes[note].at * THINKING_SAMPLE_RATE).roundToLong() <= position) {
                if (voices.size == 32) voices.removeAt(0)
                voices.add(Voice(score.notes[note++], score))
            }
            var mixed = 0.0
            var voice = 0
            while (voice < voices.size) {
                if (voices[voice].finished) {
                    voices.removeAt(voice)
                } else {
                    mixed += voices[voice++].next()
                }
            }
            warmth += 0.67 * (mixed - warmth)
            var wet = warmth
            for (echo in echoes) wet += echo.next(warmth)
            val fadeIn = (frame / (THINKING_SAMPLE_RATE * 1.2)).coerceAtMost(1.0)
            val fadeOut = ((loopFrames - position) / (THINKING_SAMPLE_RATE * 2.0)).coerceAtMost(1.0)
            output[index] =
                (wet * fadeIn * fadeOut * Short.MAX_VALUE)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            frame++
        }
    }
}
