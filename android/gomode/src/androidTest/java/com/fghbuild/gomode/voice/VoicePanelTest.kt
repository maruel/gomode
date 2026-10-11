// Compose UI tests for the compact and expandable Go Mode voice panel transcript.
package com.fghbuild.gomode.voice

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.caic.voicegateway.sdk.v1.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoicePanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun statusShowsToolExecutionThenReturnsToThinking() {
        val state = mutableStateOf(VoiceState(connected = true, turnState = TurnState.Thinking))
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(state.value, true, {}, {}, {}, {}, {}, {}, null)
            }
        }
        composeRule.onNodeWithText("Thinking…").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = state.value.copy(activeTool = "example_tool")
        }
        composeRule.onNodeWithText("Calling tool: example_tool…").assertIsDisplayed()
        composeRule.onNodeWithText("Thinking…").assertDoesNotExist()
        composeRule.runOnIdle {
            state.value = state.value.copy(activeTool = null)
        }
        composeRule.onNodeWithText("Thinking…").assertIsDisplayed()
        composeRule.onNodeWithText("Calling tool: example_tool…").assertDoesNotExist()
    }

    @Test
    fun audioOutputShowsTheConfirmedRouteAndOffersHeadphones() {
        var selected: Int? = null
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(
                    voiceState =
                        VoiceState(
                            connected = true,
                            availableDevices = listOf(AudioDevice(1, 1, "Earpiece"), AudioDevice(2, 2, "Headphones")),
                            selectedDeviceId = 1,
                        ),
                    voiceEnabled = true,
                    onConnect = {},
                    onDisconnect = {},
                    onToggleMute = {},
                    onSelectDevice = { selected = it },
                    onClearTranscript = {},
                    onOpenSettings = {},
                    serviceStatusText = null,
                )
            }
        }
        composeRule.onNodeWithText("Audio output").assertIsDisplayed()
        composeRule.onNodeWithTag("gomode-voice-audio-1").assertIsSelected()
        composeRule.onNodeWithTag("gomode-voice-audio-2").assertIsDisplayed().performClick()
        assertEquals(2, selected)
    }

    @Test
    fun transcriptIsExpandedByDefaultAndCanBeCollapsed() {
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(
                    voiceState =
                        VoiceState(
                            connected = true,
                            listening = true,
                            transcript =
                                listOf(
                                    TranscriptEntry(TranscriptSpeaker.ASSISTANT, "Four tasks are running."),
                                ),
                        ),
                    voiceEnabled = true,
                    onConnect = {},
                    onDisconnect = {},
                    onToggleMute = {},
                    onSelectDevice = {},
                    onClearTranscript = {},
                    onOpenSettings = {},
                    serviceStatusText = null,
                )
            }
        }

        composeRule.onNodeWithTag("gomode-voice-transcript").assertIsDisplayed()
        composeRule.onNodeWithTag("gomode-voice-transcript-toggle").performClick()
        assertTrue(composeRule.onAllNodesWithTag("gomode-voice-transcript").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("gomode-voice-transcript-toggle").performClick()
        composeRule.onNodeWithTag("gomode-voice-transcript").assertIsDisplayed()
    }

    @Test
    fun recognitionDraftRevisesWhileTheTranscriptIsCollapsed() {
        val state = mutableStateOf(VoiceState(connected = true, listening = true))
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(state.value, true, {}, {}, {}, {}, {}, {}, null)
            }
        }
        composeRule.runOnIdle {
            state.value = state.value.copy(transcript = listOf(TranscriptEntry(TranscriptSpeaker.USER, "Send a")))
        }
        composeRule.onNodeWithTag("gomode-voice-transcript-toggle").performClick()
        composeRule.onNodeWithTag("gomode-voice-recognition-draft").assertIsDisplayed()
        composeRule.onNodeWithText("You: Send a").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value =
                state.value.copy(transcript = listOf(TranscriptEntry(TranscriptSpeaker.USER, "Send the message")))
        }
        composeRule.onNodeWithText("You: Send a").assertDoesNotExist()
        composeRule.onNodeWithText("You: Send the message").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = state.value.copy(muted = true, listening = false)
        }
        composeRule.onNodeWithTag("gomode-voice-recognition-draft").assertIsDisplayed()
        composeRule.onNodeWithText("You: Send the message").assertIsDisplayed()
        composeRule.onNodeWithTag("gomode-voice-transcript-toggle").performClick()
        composeRule.onNodeWithTag("gomode-voice-transcript").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = state.value.copy(muted = false, listening = true)
        }
        composeRule.onNodeWithTag("gomode-voice-transcript").assertIsDisplayed()
        composeRule.onNodeWithTag("gomode-voice-transcript-toggle").performClick()
        composeRule.onNodeWithText("You: Send the message").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value =
                state.value.copy(
                    listening = false,
                    transcript =
                        listOf(
                            TranscriptEntry(TranscriptSpeaker.USER, "Send the message", final = true),
                        ),
                )
        }
        composeRule.onNodeWithTag("gomode-voice-recognition-draft").assertDoesNotExist()
    }

    @Test
    fun longSpeakingResponseFollowsItsBottomWithoutOverridingManualScroll() {
        val longText = (1..100).joinToString("\n") { "Response paragraph $it." }
        val state =
            mutableStateOf(
                VoiceState(
                    connected = true,
                    speaking = true,
                    transcript = listOf(TranscriptEntry(TranscriptSpeaker.ASSISTANT, longText)),
                ),
            )
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(state.value, true, {}, {}, {}, {}, {}, {}, null)
            }
        }
        composeRule.onNodeWithTag("gomode-voice-transcript-end").assertIsDisplayed()

        fun appendResponse() {
            composeRule.runOnIdle {
                state.value =
                    state.value.copy(
                        transcript =
                            listOf(
                                TranscriptEntry(
                                    TranscriptSpeaker.ASSISTANT,
                                    state.value.transcript
                                        .single()
                                        .text + "\nNew paragraph.",
                                ),
                            ),
                    )
            }
        }
        appendResponse()
        composeRule.onNodeWithTag("gomode-voice-transcript-end").assertIsDisplayed()
        val transcript = composeRule.onNodeWithTag("gomode-voice-transcript")
        transcript.performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("gomode-voice-transcript-end").assertIsNotDisplayed()
        val before = transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        appendResponse()
        composeRule.waitForIdle()
        val after = transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, after, 0.01f)
        composeRule.onNodeWithTag("gomode-voice-transcript-end").assertIsNotDisplayed()
        transcript.performScrollToNode(hasTestTag("gomode-voice-transcript-end"))
        appendResponse()
        composeRule.onNodeWithTag("gomode-voice-transcript-end").assertIsDisplayed()
    }

    @Test
    fun voiceErrorRemainsVisibleWhenVoiceIsUnavailable() {
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(
                    voiceState = VoiceState(error = "Voice turn failed (llm)"),
                    voiceEnabled = false,
                    onConnect = {},
                    onDisconnect = { dismissed = true },
                    onToggleMute = {},
                    onSelectDevice = {},
                    onClearTranscript = {},
                    onOpenSettings = {},
                    serviceStatusText = null,
                )
            }
        }

        composeRule.onNodeWithText("Voice turn failed (llm)").assertIsDisplayed()
        composeRule.onNodeWithText("Reconnect").assertIsNotEnabled()
        composeRule.onNodeWithText("Dismiss").assertIsDisplayed().performClick()
        assertTrue(dismissed)
    }
}
