// Compose UI tests for the compact and expandable Go Mode voice panel transcript.
package com.fghbuild.gomode.voice

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.fghbuild.gomode.data.VoiceMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoicePanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun transcriptIsCollapsedByDefaultAndCanBeExpanded() {
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
                    voiceMode = VoiceMode.CLOUD,
                    onVoiceModeChange = {},
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

        assertTrue(composeRule.onAllNodesWithTag("gomode-voice-transcript").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("gomode-voice-transcript-toggle").performClick()
        composeRule.onNodeWithTag("gomode-voice-transcript").assertIsDisplayed()
    }

    @Test
    fun voiceModeChipsReportSelection() {
        var selected: VoiceMode? = null
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(
                    voiceState = VoiceState(),
                    voiceEnabled = true,
                    voiceMode = VoiceMode.CLOUD,
                    onVoiceModeChange = { selected = it },
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

        composeRule.onNodeWithTag("gomode-voice-mode-device").performClick()
        assertTrue(selected == VoiceMode.DEVICE)
    }
}
