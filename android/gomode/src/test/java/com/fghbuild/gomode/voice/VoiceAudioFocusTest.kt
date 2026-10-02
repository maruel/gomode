// Tests voice session audio focus policy and callback wiring.
package com.fghbuild.gomode.voice

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class VoiceAudioFocusTest {
    @Test
    fun transientLossKeepsTheSession() {
        assertEquals(
            VoiceAudioFocusOutcome.KEEP,
            voiceAudioFocusOutcome(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
    }

    @Test
    fun transientDuckKeepsTheSession() {
        assertEquals(
            VoiceAudioFocusOutcome.KEEP,
            voiceAudioFocusOutcome(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
    }

    @Test
    fun permanentLossDisconnects() {
        assertEquals(
            VoiceAudioFocusOutcome.DISCONNECT,
            voiceAudioFocusOutcome(AudioManager.AUDIOFOCUS_LOSS),
        )
    }

    @Test
    fun listenerOnlyDisconnectsOnPermanentLoss() {
        val audioManager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        var disconnected = false
        audioManager.requestAudioFocus(voiceAudioFocusRequest { disconnected = true })
        val listener = shadowOf(audioManager).lastAudioFocusRequest.listener

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse("a transient loss must not end the session", disconnected)

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse("regaining focus must not end the session", disconnected)

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue("a permanent loss ends the session", disconnected)
    }
}
