// Generates an indefinitely evolving quiet pad with bounded state across arbitrary PCM chunks.
package com.fghbuild.gomode.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val THINKING_SAMPLE_RATE = 24_000

internal class ThinkingPad {
    private val frequencies = doubleArrayOf(138.59, 207.65, 277.18, 311.13, 349.23)
    private val levels = doubleArrayOf(0.021, 0.016, 0.009, 0.006, 0.004)
    private val phases = DoubleArray(frequencies.size) { 0.25 }
    private val shadows = DoubleArray(frequencies.size) { 0.25 }
    private val increments = DoubleArray(frequencies.size)
    private val gains = DoubleArray(frequencies.size)
    private var frame = 0L
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun render(output: ShortArray) {
        for (index in output.indices) {
            // Control cadence belongs to the audio clock, independently of the caller's buffer sizes.
            if (frame % 120 == 0L) updateControls()
            var mixed = 0.0
            for (voice in frequencies.indices) {
                mixed += (triangle(phases[voice]) + 0.14 * triangle(shadows[voice])) * gains[voice]
                phases[voice] = (phases[voice] + increments[voice]) % 1.0
                shadows[voice] = (shadows[voice] + frequencies[voice] * 1.002 / THINKING_SAMPLE_RATE) % 1.0
            }
            val filtered = b0 * mixed + z1
            z1 = b1 * mixed - a1 * filtered + z2
            z2 = b2 * mixed - a2 * filtered
            val fade = (frame.toDouble() / (THINKING_SAMPLE_RATE * 1.2)).coerceAtMost(1.0)
            output[index] = (filtered * fade * Short.MAX_VALUE).roundToInt().toShort()
            frame++
        }
    }

    private fun updateControls() {
        val time = frame.toDouble() / THINKING_SAMPLE_RATE
        for (voice in frequencies.indices) {
            val cents = 1.5 * sin(2 * PI * (0.023 + voice * 0.0047) * time)
            increments[voice] = frequencies[voice] * 2.0.pow(cents / 1_200) / THINKING_SAMPLE_RATE
            gains[voice] = levels[voice] * (1 + 0.1 * sin(2 * PI * (0.031 + voice * 0.0073) * time))
        }
        val cutoff = 650 + 150 * sin(2 * PI * 0.019 * time)
        val omega = 2 * PI * cutoff / THINKING_SAMPLE_RATE
        val cosine = cos(omega)
        val alpha = sin(omega) / (2 * 0.4)
        val denominator = 1 + alpha
        b0 = (1 - cosine) / (2 * denominator)
        b1 = (1 - cosine) / denominator
        b2 = b0
        a1 = -2 * cosine / denominator
        a2 = (1 - alpha) / denominator
    }

    private fun triangle(phase: Double): Double = 1 - 4 * abs(phase - 0.5)
}
