// Tests native voice-mode chime note ordering and lifecycle transitions.
package com.fghbuild.gomode.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceChimeTest {
    @Test
    fun connectionAndDisconnectionUseOppositeTwoNoteChimes() {
        assertArrayEquals(doubleArrayOf(523.25, 659.25), voiceChimeFrequencies(VoiceChimeCue.CONNECTED), 0.0)
        assertArrayEquals(doubleArrayOf(659.25, 523.25), voiceChimeFrequencies(VoiceChimeCue.DISCONNECTED), 0.0)
    }

    @Test
    fun repeatedLifecycleSignalsChimeOncePerTransition() {
        val player = RecordingVoiceChimePlayer()
        val chime = VoiceModeChime(player)
        var completed = 0

        chime.connected()
        chime.connected()
        assertEquals(true, chime.disconnected { completed++ })
        assertEquals(false, chime.disconnected { completed++ })

        assertEquals(listOf("connected", "disconnected"), player.events)
        assertEquals(0, completed)
        player.finishDisconnect()
        assertEquals(1, completed)
    }

    @Test
    fun renderedChimeContainsTwoNotesSeparatedBySilence() {
        val rendered = renderVoiceChime(voiceChimeFrequencies(VoiceChimeCue.CONNECTED))
        val noteSamples = 120 * 16_000 / 1_000
        val gapSamples = 35 * 16_000 / 1_000

        assertEquals(noteSamples * 2 + gapSamples, rendered.size)
        assertEquals(true, rendered.copyOfRange(noteSamples, noteSamples + gapSamples).all { it == 0.toShort() })
    }

    private class RecordingVoiceChimePlayer : VoiceChimePlayer {
        val events = mutableListOf<String>()
        private var disconnectComplete: (() -> Unit)? = null

        override fun playConnected() {
            events += "connected"
        }

        override fun playDisconnected(onComplete: () -> Unit) {
            events += "disconnected"
            disconnectComplete = onComplete
        }

        fun finishDisconnect() {
            disconnectComplete?.invoke()
        }
    }
}
