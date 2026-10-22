// Unit tests for the Go Mode voice overlay status line and audio device icons.
package com.fghbuild.gomode.voice

import android.media.AudioDeviceInfo
import com.caic.voicegateway.sdk.v1.TurnState
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceOverlayTest {
    @Test
    fun audioDeviceIconsUsePlatformTypesAndHandleUnknownRoutes() {
        assertEquals("Filled.Bluetooth", audioDeviceIcon(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).name)
        assertEquals("Filled.Bluetooth", audioDeviceIcon(AudioDeviceInfo.TYPE_BLE_HEADSET).name)
        assertEquals("Filled.Bluetooth", audioDeviceIcon(AudioDeviceInfo.TYPE_BLE_SPEAKER).name)
        assertEquals("Filled.Headphones", audioDeviceIcon(AudioDeviceInfo.TYPE_WIRED_HEADSET).name)
        assertEquals("Filled.Headphones", audioDeviceIcon(AudioDeviceInfo.TYPE_USB_HEADSET).name)
        assertEquals("Filled.PhoneInTalk", audioDeviceIcon(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).name)
        assertEquals("Filled.Speaker", audioDeviceIcon(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).name)
        assertEquals("Filled.Usb", audioDeviceIcon(AudioDeviceInfo.TYPE_USB_DEVICE).name)
        assertEquals("Filled.VolumeUp", audioDeviceIcon(AudioDeviceInfo.TYPE_UNKNOWN).name)
    }

    @Test
    fun voiceStatusTextDistinguishesToolExecutionFromThinking() {
        val thinking = VoiceState(connected = true, turnState = TurnState.Thinking)
        assertEquals("Thinking…", voiceStatusText(thinking))
        val calling = thinking.copy(activeTool = "example_tool")
        assertEquals("Calling tool: example_tool…", voiceStatusText(calling))
        assertEquals(
            "Calling tool: example_tool…",
            voiceStatusText(calling.copy(microphoneMode = MicrophoneMode.MUTED, speaking = true)),
        )
        assertEquals("Thinking…", voiceStatusText(calling.copy(activeTool = null)))
    }

    @Test
    fun voiceStatusTextShowsGatewayWorkBehindSpeechAndAheadOfMuting() {
        val muted = VoiceState(connected = true, microphoneMode = MicrophoneMode.MUTED)
        assertEquals("Transcribing…", voiceStatusText(muted.copy(turnState = TurnState.Transcribing)))
        assertEquals("Thinking…", voiceStatusText(muted.copy(turnState = TurnState.Thinking)))
        assertEquals("Speaking…", voiceStatusText(muted.copy(turnState = TurnState.Thinking, speaking = true)))
        assertEquals("Muted", voiceStatusText(muted))
        assertEquals(
            "Listening…",
            voiceStatusText(
                muted.copy(microphoneMode = MicrophoneMode.LISTENING, turnState = TurnState.Other("future")),
            ),
        )
    }
}
