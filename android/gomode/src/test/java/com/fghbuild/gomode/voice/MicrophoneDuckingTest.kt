// Verifies continuous microphone gain ramps across PCM chunks, reversals, and sample-rate changes.
package com.fghbuild.gomode.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MicrophoneDuckingTest {
    @Test
    fun `capture gain ramps down across buffers and reverses without a step`() {
        var speaking = false
        val processor = MicrophoneDucking { speaking }
        processor.initialize(48_000, 1)
        val buffer = ByteBuffer.allocateDirect(480 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

        fun process(): FloatArray {
            for (offset in 0 until buffer.capacity() step Float.SIZE_BYTES) buffer.putFloat(offset, 10_000f)
            processor.process(3, 480, buffer)
            assertEquals(0, buffer.position())
            return FloatArray(480) { buffer.getFloat(it * Float.SIZE_BYTES) }
        }
        assertTrue(process().all { it == 10_000f })
        speaking = true
        val first = process()
        val second = process()
        assertTrue(first.first() in 9_990f..10_000f)
        assertTrue((1 until first.size).all { first[it] < first[it - 1] })
        assertTrue(kotlin.math.abs(second.first() - first.last()) < 10)
        speaking = false
        val reversed = process()
        assertTrue(kotlin.math.abs(reversed.first() - second.last()) < 10)
        assertTrue((1 until reversed.size).all { reversed[it] > reversed[it - 1] })
        repeat(10) { process() }
        assertTrue(process().all { it == 10_000f })
        speaking = true
        repeat(4) { process() }
        assertTrue(process().all { it in 999f..1_001f })
        processor.initialize(48_000, 1)
        assertTrue("restarting capture during speech must retain attenuation", process().all { it in 999f..1_001f })
    }

    @Test
    fun `recognizer ramps handle signed PCM and leave unread bytes intact`() {
        val gain = MicrophoneGainRamp(16_000, false)
        gain.setSpeaking(true)
        val frame = ByteBuffer.allocate(1_282).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in 0 until 1_280 step 4) {
            frame.putShort(offset, Short.MAX_VALUE)
            frame.putShort(offset + 2, Short.MIN_VALUE)
        }
        frame.putShort(1_280, 1234)
        gain.processPcm(frame.array(), 1_280)
        assertTrue(frame.getShort(0).toInt() in 32_000..32_767)
        assertTrue(frame.getShort(1_276).toInt() in 3_270..3_280)
        assertTrue(frame.getShort(1_278).toInt() in -3_280..-3_270)
        assertArrayEquals(byteArrayOf(0xd2.toByte(), 4), frame.array().copyOfRange(1_280, 1_282))
        gain.setSpeaking(false)
        gain.configure(48_000)
        val values = FloatArray(4_800) { gain.next() }
        assertTrue(values.first() in 0.1f..0.101f)
        assertEquals(1f, values.last(), 0f)
    }
}
