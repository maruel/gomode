// Tests live pad continuity, bounded levels, evolving controls, and chunk-independent DSP state.
package com.fghbuild.gomode.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ThinkingEarconTest {
    @Test
    fun arbitraryChunkSizesPreserveTheExactWaveform() {
        val expected = ShortArray(71_321)
        ThinkingPad().render(expected)
        val actual = ShortArray(expected.size)
        val pad = ThinkingPad()
        val sizes = intArrayOf(1, 119, 120, 121, 479, 503, 4_001)
        var offset = 0
        var chunk = 0
        while (offset < actual.size) {
            val buffer = ShortArray(minOf(sizes[chunk++ % sizes.size], actual.size - offset))
            pad.render(buffer)
            buffer.copyInto(actual, offset)
            offset += buffer.size
        }
        assertArrayEquals("render boundaries must not reset phases, filters, or modulation", expected, actual)
    }

    @Test
    fun longWaitStaysQuietContinuousAndKeepsEvolving() {
        val pad = ThinkingPad()
        val buffer = ShortArray(2_400)
        val levels = mutableListOf<Double>()
        var peak = 0
        var largestStep = 0
        var previous = 0
        var sum = 0L
        var afterThirtySeconds: ShortArray? = null
        var early: ShortArray? = null
        repeat(900) { chunk ->
            pad.render(buffer)
            for (sample in buffer) {
                peak = maxOf(peak, abs(sample.toInt()))
                largestStep = maxOf(largestStep, abs(sample - previous))
                previous = sample.toInt()
                sum += sample
            }
            if (chunk >= 12) levels += rms(buffer)
            if (chunk == 20) early = buffer.copyOf()
            if (chunk == 320) afterThirtySeconds = buffer.copyOf()
        }
        assertTrue("quiet yet audible peak", peak in 500..2_500)
        assertTrue("no dropouts during a 90-second wait", levels.all { it > 100 })
        assertTrue("gentle movement remains audible", levels.max() > levels.min() * 1.15)
        assertTrue("level movement remains gradual", levels.zipWithNext().all { (a, b) -> abs(a - b) < 150 })
        assertTrue("neither chunk boundaries nor controls click", largestStep < 180)
        assertTrue("no sustained DC offset", abs(sum.toDouble() / (900 * buffer.size)) < 2)
        assertTrue("the former 30-second loop does not repeat", !early.contentEquals(afterThirtySeconds))
    }

    @Test
    fun eachNewTurnFadesInWithoutAnInitialClick() {
        val buffer = ShortArray(THINKING_SAMPLE_RATE * 2)
        ThinkingPad().render(buffer)
        assertEquals(0, buffer.first().toInt())
        val onset = rms(buffer.copyOfRange(0, 480))
        val sustained = rms(buffer.copyOfRange(buffer.size - 480, buffer.size))
        assertTrue("onset is substantially quieter", onset < sustained * 0.1)
    }

    private fun rms(samples: ShortArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
}
