// Tests piano score continuity, bounded levels, loop playback, and chunk-independent synthesis.
package com.fghbuild.gomode.voice

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ThinkingEarconTest {
    @Test
    fun previewFinishesAndStaysSilentInsteadOfLooping() {
        val preview =
            ThinkingPiano(
                score.copy(duration = 1.0, notes = listOf(PianoNote(0.01, 60, 0.1, 0.4))),
                loop = false,
            )
        val music = ShortArray((THINKING_SAMPLE_RATE * 1.3).toInt())
        preview.render(music)
        assertTrue("preview contains piano audio", music.any { it.toInt() != 0 })
        assertTrue("preview reaches its end", preview.finished)
        val after = ShortArray(THINKING_SAMPLE_RATE)
        preview.render(after)
        assertTrue("preview does not restart or leave echo tails", after.all { it.toInt() == 0 })
    }

    private val score =
        Json.decodeFromString<ThinkingScore>(
            requireNotNull(javaClass.getResource("/thinking-score.json")).readText(),
        )

    @Test
    fun arbitraryChunkSizesPreserveTheExactWaveform() {
        val expected = ShortArray(71_321)
        ThinkingPiano(score).render(expected)
        val actual = ShortArray(expected.size)
        val piano = ThinkingPiano(score)
        val sizes = intArrayOf(1, 119, 120, 121, 479, 503, 4_001)
        var offset = 0
        var chunk = 0
        while (offset < actual.size) {
            val buffer = ShortArray(minOf(sizes[chunk++ % sizes.size], actual.size - offset))
            piano.render(buffer)
            buffer.copyInto(actual, offset)
            offset += buffer.size
        }
        assertArrayEquals("render boundaries must not reset notes, resonances, or echoes", expected, actual)
    }

    @Test
    fun fullScoreAndLoopStayQuietWithoutClipping() {
        val piano = ThinkingPiano(score)
        val buffer = ShortArray(2_400)
        val levels = mutableListOf<Double>()
        var peak = 0
        var largestStep = 0
        var previous = 0
        var sum = 0L
        val chunks = ((score.duration + 5) * 10).toInt()
        var restartAudible = false
        repeat(chunks) { chunk ->
            piano.render(buffer)
            for (sample in buffer) {
                peak = maxOf(peak, abs(sample.toInt()))
                largestStep = maxOf(largestStep, abs(sample - previous))
                previous = sample.toInt()
                sum += sample
            }
            if (chunk >= 12) levels += rms(buffer)
            if (chunk > score.duration * 10 + 10 && rms(buffer) > 50) restartAudible = true
        }
        assertTrue("quiet yet audible piano with headroom", peak in 500..12_000)
        assertTrue("new score material continues beyond the first minute", levels.drop(1_700).any { it > 50 })
        assertTrue("the score restarts after its release tail", restartAudible)
        assertTrue("no abrupt full-scale steps", largestStep < 4_000)
        assertTrue("no sustained DC offset", abs(sum.toDouble() / (chunks * buffer.size)) < 5)
    }

    @Test
    fun eachNewTurnFadesInWithoutAnInitialClick() {
        val buffer = ShortArray(THINKING_SAMPLE_RATE * 2)
        ThinkingPiano(score).render(buffer)
        assertEquals(0, buffer.first().toInt())
        val onset = rms(buffer.copyOfRange(0, 480))
        val sustained = rms(buffer.copyOfRange(buffer.size - 480, buffer.size))
        assertTrue("onset is substantially quieter", onset < sustained * 0.1)
    }

    private fun rms(samples: ShortArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
}
