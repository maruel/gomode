// Unit tests for the Go Mode voice overlay status line.
package com.fghbuild.gomode.voice

import com.caic.voicegateway.sdk.v1.TurnState
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceOverlayTest {
    @Test
    fun voiceStatusTextShowsGatewayWorkBehindSpeechAndAheadOfMuting() {
        val muted = VoiceState(connected = true, muted = true)
        assertEquals("Transcribing…", voiceStatusText(muted.copy(turnState = TurnState.Transcribing)))
        assertEquals("Thinking…", voiceStatusText(muted.copy(turnState = TurnState.Thinking)))
        assertEquals("Speaking…", voiceStatusText(muted.copy(turnState = TurnState.Thinking, speaking = true)))
        assertEquals("Muted", voiceStatusText(muted))
        assertEquals("Listening…", voiceStatusText(muted.copy(muted = false, turnState = TurnState.Other("future"))))
    }
}
