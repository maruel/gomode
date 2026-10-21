// Compose UI tests for the compact and expandable Go Mode voice panel transcript.
package com.fghbuild.gomode.voice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.caic.voicegateway.sdk.v1.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoicePanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun gatewayFailureShowsTheCauseAndCanBeRetried() {
        var retries = 0
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(
                    VoiceState(
                        error = "Voice gateway unavailable (HTTP 502).",
                    ),
                    true,
                    { retries++ },
                    {},
                    {},
                    {},
                    {},
                    {},
                    null,
                    onCycleApp = {},
                    canCycleApps = false,
                )
            }
        }
        composeRule
            .onNodeWithText(
                "Voice gateway unavailable (HTTP 502).",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Voice connection unavailable").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Reconnect").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun longErrorUsesThePanelWidthAboveItsIconActions() {
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        VoicePanel(
                            VoiceState(
                                error =
                                    "Voice gateway unavailable (HTTP 502). " +
                                        "The server couldn't reach the voice gateway.",
                            ),
                            true,
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            null,
                            onCycleApp = {},
                            canCycleApps = false,
                        )
                    }
                }
            }
        }
        val message = composeRule.onNodeWithTag("gomode-voice-error-message").fetchSemanticsNode().boundsInRoot
        val actions = composeRule.onNodeWithTag("gomode-voice-error-actions").fetchSemanticsNode().boundsInRoot
        assertEquals(actions.left, message.left, 1f)
        assertEquals(actions.right, message.right, 1f)
        assertTrue(message.bottom <= actions.top)
        composeRule.onNodeWithContentDescription("Reconnect").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
    }

    @Test
    fun nextAppStaysBesideSettingsRegardlessOfVoiceState() {
        val state = mutableStateOf(VoiceState())
        val canCycle = mutableStateOf(true)
        val voiceEnabled = mutableStateOf(false)
        var cycles = 0
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(
                    voiceState = state.value,
                    voiceEnabled = voiceEnabled.value,
                    onConnect = {},
                    onDisconnect = {},
                    onToggleMute = {},
                    onSelectDevice = {},
                    onClearTranscript = {},
                    onOpenSettings = {},
                    serviceStatusText = null,
                    onCycleApp = { cycles++ },
                    canCycleApps = canCycle.value,
                )
            }
        }
        for ((voiceState, enabled) in listOf(
            VoiceState() to false,
            VoiceState() to true,
            VoiceState(connectStatus = "Connecting…") to true,
            VoiceState(connected = true) to true,
            VoiceState(error = "Voice unavailable") to false,
        )) {
            composeRule.runOnIdle {
                state.value = voiceState
                voiceEnabled.value = enabled
            }
            val next = composeRule.onNodeWithTag("gomode-next-app").assertIsDisplayed()
            val settingsBounds = composeRule.onNodeWithTag("gomode-web-open-settings").fetchSemanticsNode().boundsInRoot
            val nextBounds = next.fetchSemanticsNode().boundsInRoot
            assertTrue(nextBounds.left >= settingsBounds.right)
            assertEquals(settingsBounds.center.y, nextBounds.center.y, 0.5f)
            next.performClick()
        }
        assertEquals(5, cycles)
        composeRule.runOnIdle { canCycle.value = false }
        composeRule.onNodeWithTag("gomode-next-app").assertDoesNotExist()
    }

    @Test
    fun statusShowsToolExecutionThenReturnsToThinking() {
        val state = mutableStateOf(VoiceState(connected = true, turnState = TurnState.Thinking))
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(state.value, true, {}, {}, {}, {}, {}, {}, null, {}, false)
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
                    onCycleApp = {},
                    canCycleApps = false,
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
                    onCycleApp = {},
                    canCycleApps = false,
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
                VoicePanel(state.value, true, {}, {}, {}, {}, {}, {}, null, {}, false)
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
                VoicePanel(state.value, true, {}, {}, {}, {}, {}, {}, null, {}, false)
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
                    onCycleApp = {},
                    canCycleApps = false,
                )
            }
        }

        composeRule.onNodeWithText("Voice turn failed (llm)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Reconnect").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Dismiss").assertIsDisplayed().performClick()
        assertTrue(dismissed)
    }
}
