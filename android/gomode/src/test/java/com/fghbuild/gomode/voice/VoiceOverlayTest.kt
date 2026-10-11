// Unit tests for the Go Mode voice overlay status line.
package com.fghbuild.gomode.voice

import com.caic.voicegateway.sdk.v1.TurnState
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceOverlayTest {
    @Test
    fun voiceStatusTextDistinguishesToolExecutionFromThinking() {
        val thinking = VoiceState(connected = true, turnState = TurnState.Thinking)
        assertEquals("Thinking…", voiceStatusText(thinking))
        val calling = thinking.copy(activeTool = "example_tool")
        assertEquals("Calling tool: example_tool…", voiceStatusText(calling))
        assertEquals("Calling tool: example_tool…", voiceStatusText(calling.copy(muted = true, speaking = true)))
        assertEquals("Thinking…", voiceStatusText(calling.copy(activeTool = null)))
    }

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
