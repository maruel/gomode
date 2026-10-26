// Tests quiet-speech visibility, signed capture samples, and WebRTC silence and remote-audio exclusion.
package com.fghbuild.gomode.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

class MicrophoneLevelTest {
    @Test
    fun `ordinary speech is visible while silence and invalid samples stay zero`() {
        assertTrue(microphoneLevelFromRms(0.01f) > 0.3f)
        assertEquals(0f, microphoneLevelFromRms(0f))
        assertEquals(0f, microphoneLevelFromRms(Float.NaN))
        assertEquals(1f, microphoneLevelFromRms(1f))
    }

    @Test
    fun `PCM windows retain signed samples across reads and ignore unused buffer bytes`() {
        val meter = MicrophoneRmsWindow()
        // Alternating positive and negative half-scale samples, followed by unused silence.
        val pcm =
            ByteArray(2000) { index ->
                if (index >= 1600 || index % 2 == 0) {
                    0
                } else if (index % 4 == 1) {
                    0x40
                } else {
                    0xc0.toByte()
                }
            }
        assertNull(meter.append(pcm, 800))
        assertEquals(0.5f, meter.append(pcm, 800)!!, 0.0001f)
        assertEquals(0f, meter.append(ByteArray(1600), 1600)!!)
    }

    private fun report(
        type: String,
        vararg members: Pair<String, Any>,
    ) = RTCStatsReport(0, mapOf("mic" to RTCStats(0, type, "mic", mapOf("kind" to "audio", *members))))

    @Test
    fun `cloud meter excludes remote playback and clears after silence missing stats and reset counters`() {
        val meter = RtcMicrophoneLevel()
        assertTrue(meter.read(report("media-source", "audioLevel" to 0.01)) > 0.3f)
        assertEquals(0f, meter.read(report("inbound-rtp", "audioLevel" to 1.0)))
        assertEquals(0f, meter.read(report("remote-outbound-rtp", "audioLevel" to 1.0)))
        assertEquals(0f, meter.read(report("media-source", "totalAudioEnergy" to 0.1, "totalSamplesDuration" to 1.0)))
        assertTrue(
            meter.read(report("media-source", "totalAudioEnergy" to 0.1001, "totalSamplesDuration" to 2.0)) > 0.3f,
        )
        assertEquals(
            0f,
            meter.read(report("media-source", "totalAudioEnergy" to 0.1001, "totalSamplesDuration" to 3.0)),
        )
        assertEquals(0f, meter.read(report("media-source", "totalAudioEnergy" to 0.0, "totalSamplesDuration" to 0.0)))
        meter.clear()
        assertEquals(0f, meter.read(report("media-source", "totalAudioEnergy" to 0.5, "totalSamplesDuration" to 5.0)))
        assertEquals(0f, meter.read(RTCStatsReport(0, emptyMap())))
    }
}
