// Exercises real microphone PCM delivery across playback, recognition restart, and mute cleanup.
package com.fghbuild.gomode.voice

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MicrophoneAudioSourceTest {
    @get:Rule
    val microphonePermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun stalledRecognizerRetainsOnlyRecentAudioAcrossAppAndKernelBuffers() {
        val audio = RecognitionAudioBuffer()

        fun frame(number: Int) = ByteArray(640) { if (it % 2 == 0) number.toByte() else 0 }
        try {
            // Capture before recognition starts, then stall a real descriptor consumer.
            for (number in 1..40) audio.append(frame(number), 640)
            val source = audio.openSource()
            val consumer = ParcelFileDescriptor.dup(source.fileDescriptor)
            try {
                for (number in 41..100) audio.append(frame(number), 640)
                val recent = readAvailable(consumer)
                assertEquals("500 ms of 16 kHz mono 16-bit PCM", 16_000, recent.size)
                for (number in 76..100) {
                    val offset = (number - 76) * 640
                    assertArrayEquals(frame(number), recent.copyOfRange(offset, offset + 640))
                }
                audio.append(frame(101), 640)
                assertArrayEquals(frame(101), readAvailable(consumer))
            } finally {
                consumer.close()
            }
        } finally {
            audio.close()
        }
    }

    @Test
    fun microphoneDeliversDuringPlaybackAndAfterRecognitionRestart(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val source = MicrophoneAudioSource(scope)
            val errors = mutableListOf<String>()
            val playback =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setSampleRate(16_000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    ).setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(3_200)
                    .build()
            try {
                // Loop a quiet signal through the same playback usage as device TTS.
                val tone = ShortArray(1_600) { if (it % 40 < 20) 100 else -100 }
                playback.write(tone, 0, tone.size)
                playback.setLoopPoints(0, tone.size, -1)
                playback.play()
                val first = withContext(Dispatchers.Main) { source.open(errors::add) }
                assertPcmAvailable(first)
                source.assistantSpeaking = true
                assertPcmAvailable(first)
                withContext(Dispatchers.Main) { source.finishUtterance() }
                val next = withContext(Dispatchers.Main) { source.open(errors::add) }
                assertPcmAvailable(next)
                source.assistantSpeaking = false
                assertPcmAvailable(next)
                withContext(Dispatchers.Main) { source.stop() }
                val resumed = withContext(Dispatchers.Main) { source.open(errors::add) }
                assertPcmAvailable(resumed)
                assertTrue("Capture errors: $errors", errors.isEmpty())
            } finally {
                withContext(Dispatchers.Main) { source.stop() }
                scope.cancel()
                playback.stop()
                playback.release()
            }
        }

    private fun assertPcmAvailable(source: ParcelFileDescriptor) {
        val poll =
            StructPollfd().apply {
                fd = source.fileDescriptor
                events = OsConstants.POLLIN.toShort()
            }
        assertTrue("Timed out waiting for microphone PCM", Os.poll(arrayOf(poll), 10_000) > 0)
        val bytes = ByteArray(640)
        assertTrue("Microphone pipe closed without audio", Os.read(source.fileDescriptor, bytes, 0, bytes.size) > 0)
    }

    private fun readAvailable(source: ParcelFileDescriptor): ByteArray {
        val poll =
            StructPollfd().apply {
                fd = source.fileDescriptor
                events = OsConstants.POLLIN.toShort()
            }
        val bytes = ByteArray(64_000)
        var size = 0
        while (Os.poll(arrayOf(poll), 0) > 0) {
            val count = Os.read(source.fileDescriptor, bytes, size, bytes.size - size)
            assertTrue("Descriptor closed without audio", count > 0)
            size += count
            assertTrue("Unbounded recognition backlog", size < bytes.size)
        }
        return bytes.copyOf(size)
    }
}
