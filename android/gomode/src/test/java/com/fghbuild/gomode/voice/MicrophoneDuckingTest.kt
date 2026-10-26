// Verifies signed PCM and WebRTC float attenuation and restoration after assistant speech.
package com.fghbuild.gomode.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MicrophoneDuckingTest {
    @Test
    fun `capture post processing attenuates signed samples only during speech`() {
        var speaking = false
        val processor = MicrophoneDucking { speaking }
        val buffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())

        fun fill() {
            buffer.putFloat(0, 10_000f)
            buffer.putFloat(4, -10_000f)
            buffer.putFloat(8, 0f)
            buffer.putFloat(12, 500f)
        }
        fill()
        processor.initialize(48_000, 1)
        processor.process(3, 4, buffer)
        assertEquals(10_000f, buffer.getFloat(0), 0f)
        speaking = true
        processor.process(3, 4, buffer)
        assertTrue(buffer.getFloat(0) in 1f..1_000f)
        assertEquals(-buffer.getFloat(0), buffer.getFloat(4), 0f)
        assertEquals(0f, buffer.getFloat(8), 0f)
        assertTrue(buffer.getFloat(12) in 1f..50f)
        assertEquals(0, buffer.position())
        speaking = false
        processor.reset(16_000)
        fill()
        processor.process(1, 4, buffer)
        assertEquals(10_000f, buffer.getFloat(0), 0f)
    }

    @Test
    fun `recognizer PCM reduction handles negative extremes and leaves unread bytes intact`() {
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        listOf(Short.MAX_VALUE, Short.MIN_VALUE, 0, -1, 1234).forEach { buffer.putShort(it.toShort()) }
        val frame = buffer.array()
        attenuateMicrophonePcm(frame, 8)
        assertTrue(buffer.getShort(0).toInt() in 1..3276)
        assertTrue(buffer.getShort(2).toInt() in -3277..-1)
        assertEquals(0, buffer.getShort(4).toInt())
        assertEquals(0, buffer.getShort(6).toInt())
        assertArrayEquals(byteArrayOf(0xd2.toByte(), 4), frame.copyOfRange(8, 10))
    }
}
