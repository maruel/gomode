// Compose UI tests for Go Mode voice controls, activity, and transcript layout.
package com.fghbuild.gomode.voice

import android.media.AudioDeviceInfo
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.LockedCarCall
import com.fghbuild.gomode.ui.theme.GoModeTheme
import com.fghbuild.voicegateway.sdk.v2.TurnState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class VoicePanelTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun usageRemainsReviewableAfterDisconnectAndAtLargeText() {
        val showStats = mutableStateOf(false)
        val large = mutableStateOf(false)
        val narrow = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        val counts =
            com.fghbuild.voicegateway.sdk.v2
                .TokenUsage(inputTokens = 1234, outputTokens = 64, cachedTokens = 128, reasoningTokens = 8)
        val report =
            com.fghbuild.voicegateway.sdk.v2.SessionUsage(
                com.fghbuild.voicegateway.sdk.v2.MessageKind.SessionUsage,
                "fixture",
                "llamacpp",
                "LFM3-audio",
                counts,
                lastResponse =
                    counts.copy(
                        inputTokens = 234,
                        outputTokens = 0,
                        cachedTokens = 0,
                        reasoningTokens = null,
                    ),
            )
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, if (large.value) 2f else 1f)) {
                GoModeTheme {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.statusBars,
                            ).testTag("voice-panel-viewport"),
                    ) {
                        Spacer(Modifier.weight(1f))
                        Box(if (narrow.value) Modifier.width(320.dp) else Modifier.fillMaxWidth()) {
                            VoicePanel(
                                VoiceState(
                                    usage = VoiceUsage(counts, report),
                                    error = error.value,
                                    transcript =
                                        listOf(
                                            TranscriptEntry(
                                                TranscriptSpeaker.USER,
                                                "What should I work on next?",
                                                true,
                                            ),
                                            TranscriptEntry(
                                                TranscriptSpeaker.ASSISTANT,
                                                "Review the launch brief first.",
                                                true,
                                                responseID = "first",
                                                responseUsage =
                                                    counts.copy(
                                                        inputTokens = 1000,
                                                        outputTokens = 52,
                                                        reasoningTokens = 8,
                                                    ),
                                            ),
                                            TranscriptEntry(
                                                TranscriptSpeaker.ASSISTANT,
                                                "Compare deadlines.",
                                                true,
                                                TranscriptKind.REASONING,
                                                responseID = "second",
                                            ),
                                            TranscriptEntry(
                                                TranscriptSpeaker.ASSISTANT,
                                                "The prototype feedback is due today too.",
                                                true,
                                                responseID = "second",
                                                responseUsage =
                                                    counts.copy(
                                                        inputTokens = 234,
                                                        outputTokens = 0,
                                                        cachedTokens = 0,
                                                        reasoningTokens = null,
                                                    ),
                                            ),
                                            TranscriptEntry(
                                                TranscriptSpeaker.ASSISTANT,
                                                "The design review is tomorrow.",
                                                true,
                                                responseID = "third",
                                                responseUsage =
                                                    com.fghbuild.voicegateway.sdk.v2
                                                        .TokenUsage(outputTokens = 12),
                                            ),
                                        ),
                                ),
                                true,
                                {},
                                {},
                                {},
                                {},
                                {},
                                serviceReconnect = null,
                                onCycleApp = {},
                                canCycleApps = false,
                                showUsageStats = showStats.value,
                                modifier = Modifier.testTag("voice-panel"),
                            )
                        }
                    }
                }
            }
        }

        fun assertFullWidth() {
            val viewport = composeRule.onNodeWithTag("voice-panel-viewport").fetchSemanticsNode().boundsInRoot
            val panel = composeRule.onNodeWithTag("voice-panel").fetchSemanticsNode().boundsInRoot
            assertEquals(viewport.left, panel.left, 1f)
            assertEquals(viewport.right, panel.right, 1f)
            val transcript = composeRule.onNodeWithTag("gomode-voice-transcript").fetchSemanticsNode().boundsInRoot
            val inset = with(composeRule.density) { 8.dp.toPx() }
            assertEquals(viewport.left + inset, transcript.left, 1f)
            assertEquals(viewport.right - inset, transcript.right, 1f)
        }
        assertFullWidth()
        capture("usage-collapsed")
        composeRule.runOnIdle { showStats.value = true }
        assertFullWidth()
        composeRule.onNodeWithContentDescription("Input tokens: 1,234").assertIsDisplayed()
        val cached =
            composeRule
                .onNode(
                    hasContentDescription(
                        "Cached input tokens: 128",
                    ) and hasAnyAncestor(hasTestTag("gomode-voice-transcript")),
                ).fetchSemanticsNode()
                .boundsInRoot
        val reasoning =
            composeRule
                .onNode(
                    hasContentDescription("Reasoning tokens: 8") and
                        hasAnyAncestor(hasTestTag("gomode-voice-transcript")),
                ).fetchSemanticsNode()
                .boundsInRoot
        assertEquals(cached.top, reasoning.top, 1f)
        composeRule
            .onNodeWithTag(
                "gomode-voice-transcript",
            ).performScrollToNode(hasContentDescription("Input tokens: 234"))
        composeRule.onNodeWithContentDescription("Input tokens: 234").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Connect voice assistant").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Input tokens: 1,234").performScrollTo()
        capture("usage")
        composeRule.runOnIdle { large.value = true }
        composeRule.onNodeWithContentDescription("Input tokens: 1,234").assertIsDisplayed()
        assertFullWidth()
        capture("usage-large-text")
        composeRule.runOnIdle { narrow.value = true }
        composeRule.onNodeWithContentDescription("Input tokens: 1,234").assertIsDisplayed()
        capture("usage-narrow-large-text")
        composeRule
            .onNodeWithTag(
                "gomode-voice-transcript",
            ).performScrollToNode(hasContentDescription("Input tokens: 234"))
        composeRule.onNodeWithContentDescription("Input tokens: 234").assertIsDisplayed()
        composeRule.runOnIdle { error.value = "Gateway offline" }
        composeRule.onNodeWithText("Gateway offline").assertIsDisplayed()
        composeRule
            .onNodeWithTag(
                "gomode-voice-transcript",
            ).performScrollToNode(hasContentDescription("Input tokens: 234"))
        composeRule.onNodeWithContentDescription("Input tokens: 234").assertIsDisplayed()
        composeRule.runOnIdle {
            large.value = false
            narrow.value = false
            error.value = null
        }
        composeRule
            .onNodeWithTag("gomode-voice-transcript")
            .performScrollToNode(hasContentDescription("Input tokens: 234"))
        composeRule.onNodeWithContentDescription("Input tokens: 234").performTouchInput { longClick() }
        composeRule.onNodeWithText("Input tokens: 234").assertIsDisplayed()
        capture("usage-tooltip")
    }

    @Test
    fun liveDraftRevisesAndCommitsWithoutDuplicatingHistory() {
        val state =
            mutableStateOf(
                VoiceState(
                    connected = true,
                    transcriptDraft =
                        com.fghbuild.voicegateway.sdk.v2.TranscriptDraft(
                            com.fghbuild.voicegateway.sdk.v2.MessageKind.TranscriptDraft,
                            "a",
                            "I love pears",
                        ),
                ),
            )
        composeRule.setContent {
            GoModeTheme {
                Box(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    VoicePanel(
                        voiceState = state.value,
                        voiceEnabled = true,
                        onConnect = {},
                        onDisconnect = {},
                        onSelectMicrophoneMode = {},
                        onSelectDevice = {},
                        onOpenSettings = {},
                        serviceReconnect = null,
                        onCycleApp = {},
                        canCycleApps = false,
                    )
                }
            }
        }
        composeRule.onNodeWithText("You: I love pears").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide transcript").performClick()
        composeRule.onNodeWithTag("gomode-voice-transcript-draft").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show transcript").performClick()
        composeRule.onNodeWithText("You: I love pears").assertIsDisplayed()
        capture("transcript-live-draft")
        composeRule.runOnIdle {
            state.value =
                state.value.withTranscriptDraft(
                    com.fghbuild.voicegateway.sdk.v2.TranscriptDraft(
                        com.fghbuild.voicegateway.sdk.v2.MessageKind.TranscriptDraft,
                        "a",
                        "I love bananas",
                    ),
                )
        }
        composeRule.onNodeWithText("You: I love bananas").assertIsDisplayed()
        capture("transcript-live-revision")
        composeRule.runOnIdle {
            state.value =
                state.value.withCommittedTranscript(
                    com.fghbuild.voicegateway.sdk.v2.TranscriptDelta(
                        com.fghbuild.voicegateway.sdk.v2.MessageKind.TranscriptDelta,
                        com.fghbuild.voicegateway.sdk.v2.Speaker.User,
                        "I love bananas.",
                        draft_id = "a",
                    ),
                )
        }
        composeRule.onNodeWithTag("gomode-voice-transcript-draft").assertDoesNotExist()
        composeRule.onNodeWithText("You: I love bananas.").assertIsDisplayed()
        capture("transcript-live-final")
        composeRule.runOnIdle {
            state.value =
                state.value.withTranscriptDraft(
                    com.fghbuild.voicegateway.sdk.v2.TranscriptDraft(
                        com.fghbuild.voicegateway.sdk.v2.MessageKind.TranscriptDraft,
                        "b",
                        "Canceled",
                    ),
                )
        }
        composeRule.onNodeWithTag("gomode-voice-transcript-draft").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value =
                state.value.withTranscriptDraft(
                    com.fghbuild.voicegateway.sdk.v2.TranscriptDraft(
                        com.fghbuild.voicegateway.sdk.v2.MessageKind.TranscriptDraft,
                        "b",
                        "",
                    ),
                )
        }
        composeRule.onNodeWithTag("gomode-voice-transcript-draft").assertDoesNotExist()
        composeRule.onNodeWithText("You: I love bananas.").assertIsDisplayed()
        capture("transcript-live-cancel")
    }

    @Test
    fun transcriptLabelsReasoningSeparatelyFromSpokenText() {
        composeRule.setContent {
            GoModeTheme {
                TranscriptLog(
                    entries =
                        listOf(
                            TranscriptEntry(
                                TranscriptSpeaker.ASSISTANT,
                                "Weigh both dates.",
                                kind = TranscriptKind.REASONING,
                            ),
                            TranscriptEntry(TranscriptSpeaker.ASSISTANT, "The first date is earlier."),
                            TranscriptEntry(TranscriptSpeaker.USER, "Which date is earlier?"),
                        ),
                    modifier = Modifier.testTag("gomode-voice-transcript"),
                )
            }
        }
        composeRule.onNodeWithText("Reasoning: Weigh both dates.").assertIsDisplayed()
        composeRule.onNodeWithText("Assistant: The first date is earlier.").assertIsDisplayed()
        composeRule.onNodeWithText("You: Which date is earlier?").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        composeRule.waitForIdle()
        device.waitForIdle()
        assertTrue(
            device.takeScreenshot(
                File(instrumentation.targetContext.getExternalFilesDir(null), "transcript-reasoning.png"),
            ),
        )
    }

    @Test
    fun speechLanguageDownloadHasProgressAndDoesNotStartVoice() {
        val language = mutableStateOf<SpeechLanguageState>(SpeechLanguageState.Missing)
        val active = mutableStateOf(false)
        var starts = 0
        composeRule.setContent {
            GoModeTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    VoicePanel(
                        voiceState = VoiceState(connected = active.value, listening = active.value),
                        voiceEnabled = true,
                        onConnect = { starts++ },
                        onDisconnect = {},
                        onSelectMicrophoneMode = {},
                        onSelectDevice = {},
                        onOpenSettings = {},
                        serviceReconnect = null,
                        onCycleApp = {},
                        canCycleApps = false,
                        modifier = Modifier.width(280.dp),
                        speechRecovery = {
                            SpeechLanguageRecovery(
                                language.value,
                                "fr-CA",
                                active = active.value,
                                onDownload = { language.value = SpeechLanguageState.Downloading(37) },
                                onCheck = { language.value = SpeechLanguageState.Ready },
                                onSettings = {},
                            )
                        },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Download language").assertIsDisplayed()
        capture("speech-language-missing")
        composeRule.onNodeWithText("Download language").performClick()
        composeRule.onNodeWithText("Downloading speech language fr-CA: 37%").assertIsDisplayed()
        composeRule.onNodeWithText("Download language").assertDoesNotExist()
        capture("speech-language-downloading")
        composeRule.runOnIdle { language.value = SpeechLanguageState.Pending }
        composeRule.onNodeWithText("Check language").performClick()
        composeRule.onNodeWithTag("gomode-speech-language-recovery").assertDoesNotExist()
        assertEquals(0, starts)
        composeRule.onNodeWithTag("gomode-voice-error-message").assertDoesNotExist()
        composeRule.runOnIdle {
            active.value = true
            language.value = SpeechLanguageState.Unverified("The provider cannot check this language.")
        }
        composeRule
            .onNodeWithText(
                "The provider cannot check this language. Speech may still work.",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Download language").assertDoesNotExist()
        composeRule.onNodeWithText("Check language").assertDoesNotExist()
    }

    @Test
    fun serviceReconnectSpinnerSitsBesideTheMicrophoneControl() {
        val reconnect =
            mutableStateOf<ServiceReconnectDetail?>(
                ServiceReconnectDetail("Tasks", "MCP subscription stream ended"),
            )
        composeRule.setContent {
            GoModeTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    VoicePanel(
                        voiceState = VoiceState(),
                        voiceEnabled = true,
                        onConnect = {},
                        onDisconnect = {},
                        onSelectMicrophoneMode = {},
                        onSelectDevice = {},
                        onOpenSettings = {},
                        serviceReconnect = reconnect.value,
                        onCycleApp = {},
                        canCycleApps = false,
                        modifier = Modifier.width(280.dp),
                    )
                }
            }
        }
        val indicator =
            composeRule
                .onNodeWithTag("gomode-service-status")
                .assertIsDisplayed()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        "Reason: MCP subscription stream ended",
                    ),
                )
        val spinner = indicator.fetchSemanticsNode().boundsInRoot
        val microphone = composeRule.onNodeWithTag("gomode-voice-connect").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Reconnect feedback must not add a row to the panel",
            abs(spinner.center.y - microphone.center.y) < 1f,
        )
        assertTrue(
            "Reconnect feedback must sit beside the microphone control",
            spinner.right <= microphone.left,
        )
        capture("service-reconnecting")
        composeRule.runOnIdle { reconnect.value = null }
        composeRule.onNodeWithTag("gomode-service-status").assertDoesNotExist()
    }

    @Test
    fun serviceReconnectDetailsStayUntilDismissed() {
        composeRule.setContent {
            GoModeTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    VoicePanel(
                        voiceState = VoiceState(),
                        voiceEnabled = true,
                        onConnect = {},
                        onDisconnect = {},
                        onSelectMicrophoneMode = {},
                        onSelectDevice = {},
                        onOpenSettings = {},
                        serviceReconnect = ServiceReconnectDetail("Tasks", "MCP subscription stream ended"),
                        onCycleApp = {},
                        canCycleApps = false,
                        modifier = Modifier.width(280.dp),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("gomode-service-status").performClick()
        composeRule.onNodeWithText("Service updates are reconnecting").assertIsDisplayed()
        composeRule.onNodeWithText("Loading service items and notifications from Tasks.").assertIsDisplayed()
        composeRule.onNodeWithText("Reason: MCP subscription stream ended").assertIsDisplayed()
        composeRule.onNodeWithText("Retries automatically. No action needed.").assertIsDisplayed()
        capture("service-reconnecting-details")
        // Rich tooltips can auto-dismiss after 1500 ms. These details must outlast that window.
        SystemClock.sleep(2_000)
        composeRule.onNodeWithText("Reason: MCP subscription stream ended").assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.onNodeWithText("Reason: MCP subscription stream ended").assertDoesNotExist()
    }

    @Test
    fun settingsGlyphAlignsWithActivityAndTranscriptWhileKeepingItsTouchTarget() {
        composeRule.setContent {
            GoModeTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    VoicePanel(
                        VoiceState(
                            connected = true,
                            listening = true,
                            transcript =
                                listOf(
                                    TranscriptEntry(TranscriptSpeaker.USER, "Find the brief", final = true),
                                ),
                        ),
                        true,
                        {},
                        {},
                        {},
                        {},
                        {},
                        serviceReconnect = null,
                        {},
                        false,
                    )
                }
            }
        }
        val icon =
            composeRule
                .onNodeWithContentDescription("Settings", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val activity = composeRule.onNodeWithTag("gomode-voice-activity").fetchSemanticsNode().boundsInRoot
        assertEquals(activity.left, icon.left, 0.5f)
        val transcript =
            composeRule
                .onNodeWithText("Find the brief", substring = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(transcript.left, icon.left, 0.5f)
        val touch = composeRule.onNodeWithTag("gomode-web-open-settings").fetchSemanticsNode().touchBoundsInRoot
        assertTrue(touch.left >= 0)
        assertTrue(touch.width >= icon.width * 2)
    }

    @Test
    fun deniedMicrophoneKeepsSettingsUsableAndOffersRecoveryAtNarrowWidth() {
        var requests = 0
        var settingsOpened = 0
        var starts = 0
        val recovery = mutableStateOf<MicrophonePermissionRecovery?>(null)
        recovery.value = MicrophonePermissionRecovery(onRequest = { requests++ }, onOpenSettings = { requests++ })
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    VoicePanel(
                        voiceState = VoiceState(),
                        voiceEnabled = true,
                        onConnect = { starts++ },
                        onDisconnect = {},
                        onSelectMicrophoneMode = {},
                        onSelectDevice = {},
                        onOpenSettings = { settingsOpened++ },
                        serviceReconnect = null,
                        onCycleApp = {},
                        canCycleApps = false,
                        modifier = Modifier.width(280.dp),
                        microphoneRecovery = recovery.value,
                    )
                }
            }
        }
        composeRule.onNodeWithText("Enable microphone").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        assertEquals(1, requests)
        assertEquals(1, settingsOpened)
        assertEquals(0, starts)
        capture("microphone-requestable")
        composeRule.runOnIdle {
            recovery.value = MicrophonePermissionRecovery(onRequest = null, onOpenSettings = { requests++ })
        }
        composeRule.onNodeWithText("Open app settings").assertIsDisplayed().performClick()
        capture("microphone-denied")
        assertEquals(2, requests)
        composeRule.runOnIdle { recovery.value = null }
        composeRule.onNodeWithTag("gomode-microphone-recovery").assertDoesNotExist()
    }

    @Test
    fun microphoneControlUsesAClockBadgeWithoutToolbarLabels() {
        val state = mutableStateOf(VoiceState(connected = true, listening = true))
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    VoicePanel(
                        state.value,
                        true,
                        {},
                        {},
                        { state.value = state.value.copy(microphoneMode = it) },
                        {},
                        {},
                        serviceReconnect = null,
                        onCycleApp = {},
                        canCycleApps = false,
                    )
                }
            }
        }

        val microphone = composeRule.onNodeWithTag("gomode-microphone-mode")

        fun assertState(expected: String) {
            microphone.assert(
                SemanticsMatcher
                    .expectValue(SemanticsProperties.StateDescription, expected),
            )
        }
        assertState("Listening")
        microphone.assertContentDescriptionEquals("Microphone mode. Next: Mute once")
        composeRule.onNodeWithTag("gomode-microphone-clock", useUnmergedTree = true).assertDoesNotExist()
        capture("listening")
        microphone.performClick()
        assertState("Mute once. Mutes during the next reply, then returns to Listening.")
        microphone.assertContentDescriptionEquals("Microphone mode. Next: Muted")
        composeRule.onNodeWithTag("gomode-microphone-clock", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Mute once").assertDoesNotExist()
        capture("armed")
        microphone.performClick()
        assertState("Muted. Stays muted until you unmute.")
        microphone.assertContentDescriptionEquals("Microphone mode. Next: Listening")
        composeRule.onNodeWithTag("gomode-microphone-clock", useUnmergedTree = true).assertDoesNotExist()
        capture("muted")
        microphone.performClick()
        assertState("Listening")
        microphone.performClick()
        composeRule.runOnIdle { state.value = state.value.copy(speaking = true, listening = false) }
        assertState("Mute once. Muted until this reply ends.")
        capture("mute-once")
        composeRule.runOnIdle {
            state.value =
                state.value.copy(microphoneMode = MicrophoneMode.LISTENING, speaking = false, listening = true)
        }
        assertState("Listening")
        composeRule.onNodeWithTag("gomode-microphone-clock", useUnmergedTree = true).assertDoesNotExist()
        microphone.performClick()
        assertState("Mute once. Mutes during the next reply, then returns to Listening.")
    }

    @Test
    fun longToolActivityUsesItsOwnRowBelowEveryPrimaryControl() {
        val tool = "workspace_search_project_documents_by_author_and_revision_with_extended_filters_and_full_metadata"
        val state =
            mutableStateOf(
                VoiceState(
                    connected = true,
                    activeTool = tool,
                    toolHistory =
                        listOf(
                            ToolInvocation(1, "task_list"),
                            ToolInvocation(2, "project_read"),
                            ToolInvocation(3, tool),
                        ),
                ),
            )
        var disconnects = 0
        var settings = 0
        var switches = 0
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.width(320.dp)) {
                            VoicePanel(
                                state.value,
                                true,
                                {},
                                { disconnects++ },
                                { state.value = state.value.copy(microphoneMode = it) },
                                {},
                                { settings++ },
                                serviceReconnect = null,
                                onCycleApp = { switches++ },
                                canCycleApps = true,
                            )
                        }
                    }
                }
            }
        }
        val status = composeRule.onNodeWithContentDescription("Calling tool: $tool…").assertIsDisplayed()
        capture("long-activity")
        val textBounds = status.fetchSemanticsNode().boundsInRoot
        val controls =
            listOf(
                composeRule.onNodeWithTag("gomode-microphone-mode"),
                composeRule.onNodeWithTag("gomode-web-open-settings"),
                composeRule.onNodeWithTag("gomode-next-app"),
                composeRule.onNodeWithContentDescription("End voice"),
            )
        val firstControl = controls.first().fetchSemanticsNode().boundsInRoot
        for (control in controls) {
            val bounds = control.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Controls must be above the activity", bounds.bottom <= textBounds.top)
            assertEquals(firstControl.center.y, bounds.center.y, 0.5f)
        }
        val actions = composeRule.onNodeWithTag("gomode-voice-actions").fetchSemanticsNode().boundsInRoot
        val activity = composeRule.onNodeWithTag("gomode-voice-activity").fetchSemanticsNode().boundsInRoot
        assertTrue(actions.bottom <= activity.top)
        val settingsBounds =
            composeRule
                .onNodeWithContentDescription("Settings", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(settingsBounds.left, activity.left, 1f)
        assertEquals(actions.right, activity.right, 1f)
        val history = composeRule.onNodeWithTag("gomode-voice-tool-history").assertIsDisplayed()
        val historyBounds = history.fetchSemanticsNode().boundsInRoot
        assertTrue("History remains one line", historyBounds.height <= textBounds.height + 1f)
        composeRule.onNodeWithText(tool).assertIsDisplayed()
        composeRule.onNodeWithText("task_list").assertIsNotDisplayed()
        composeRule.onNodeWithText("task_list").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(tool).assertIsNotDisplayed()
        composeRule.runOnIdle { state.value = state.value.withToolCall("next_tool") }
        composeRule.onNodeWithText("task_list").assertIsDisplayed()
        capture("tool-history-older")
        composeRule.onNodeWithText("next_tool").performScrollTo().assertIsDisplayed()
        capture("tool-history-newest")
        val full =
            (3..32).fold(VoiceState().withToolCall("duplicate").withToolCall("duplicate")) { historyState, index ->
                historyState.withToolCall("tool_$index")
            }
        composeRule.runOnIdle {
            state.value =
                state.value.copy(toolHistory = full.toolHistory, activeTool = full.activeTool)
        }
        composeRule.onNodeWithText("tool_10").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { state.value = state.value.withToolCall("tool_33") }
        composeRule.onNodeWithText("tool_10").assertIsDisplayed()
        composeRule.runOnIdle { state.value = state.value.withToolCall("tool_34").withToolCall("tool_35") }
        composeRule.onNodeWithText("tool_10").assertIsDisplayed()
        capture("tool-history-retention")
        controls.forEach { it.performClick() }
        composeRule.runOnIdle {
            assertEquals(MicrophoneMode.MUTE_ONCE, state.value.microphoneMode)
            assertEquals(1, settings)
            assertEquals(1, switches)
            assertEquals(1, disconnects)
        }
        composeRule.runOnIdle { state.value = state.value.copy(activeTool = null, turnState = TurnState.Thinking) }
        composeRule.onNodeWithText("Thinking…").assertIsDisplayed()
        capture("thinking-activity")
    }

    @Test
    fun documentationSceneShowsTasksAndAnOngoingConversation() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val html = assets.open("host.html").bufferedReader().use { it.readText() }
        val scene = JSONObject(assets.open("conversation.json").bufferedReader().use { it.readText() })
        val entries = scene.getJSONArray("transcript")
        val transcript =
            (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                TranscriptEntry(
                    speaker =
                        when (entry.getString("speaker")) {
                            "user" -> TranscriptSpeaker.USER
                            "assistant" -> TranscriptSpeaker.ASSISTANT
                            else -> error("Invalid fixture speaker")
                        },
                    text = entry.getString("text"),
                    final = entry.getBoolean("final"),
                )
            }
        val state =
            mutableStateOf(
                VoiceState(
                    connected = false,
                    activeTool = scene.getString("tool"),
                    transcript = transcript,
                    availableDevices =
                        listOf(
                            AudioDevice(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Phone speaker"),
                            AudioDevice(2, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Earpiece"),
                        ),
                    selectedDeviceId = 1,
                ),
            )
        val loaded = AtomicBoolean(false)
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
        composeRule.setContent {
            GoModeTheme {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                    AndroidView(
                        modifier = Modifier.weight(1f),
                        factory = { context ->
                            WebView(context).apply {
                                webViewClient =
                                    object : WebViewClient() {
                                        override fun onPageFinished(
                                            view: WebView?,
                                            url: String?,
                                        ) {
                                            loaded.set(true)
                                        }
                                    }
                                loadDataWithBaseURL("https://fixture.invalid/", html, "text/html", "UTF-8", null)
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                    VoicePanel(
                        voiceState = state.value,
                        voiceEnabled = true,
                        onConnect = {
                            state.value =
                                state.value.copy(
                                    connected = true,
                                    activeTool = scene.getString("tool"),
                                    toolHistory =
                                        listOf(
                                            ToolInvocation(1, "task_list"),
                                            ToolInvocation(2, "project_read"),
                                            ToolInvocation(3, scene.getString("tool")),
                                        ),
                                )
                        },
                        onDisconnect = { state.value = state.value.copy(connected = false, activeTool = null) },
                        onSelectMicrophoneMode = { state.value = state.value.copy(microphoneMode = it) },
                        onSelectDevice = { state.value = state.value.copy(selectedDeviceId = it) },
                        onOpenSettings = {},
                        serviceReconnect = null,
                        onCycleApp = {},
                        canCycleApps = true,
                    )
                }
            }
        }
        composeRule.waitUntil(10_000) { loaded.get() }
        composeRule.onNodeWithContentDescription("Connect voice assistant").assertIsDisplayed()
        capture("documentation-idle")
        composeRule.onNodeWithContentDescription("Connect voice assistant").performClick()
        composeRule.onNodeWithContentDescription("Calling tool: ${scene.getString("tool")}…").assertIsDisplayed()
        val log =
            composeRule
                .onNodeWithTag(
                    "gomode-voice-transcript",
                ).assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val end = composeRule.onNodeWithTag("gomode-voice-transcript-end").fetchSemanticsNode().boundsInRoot
        assertTrue("Short transcripts must not reserve empty space", log.bottom - end.bottom < 2f)
        composeRule.onNodeWithContentDescription("End voice").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Next server").assertIsDisplayed()
        val earpiece = composeRule.onNodeWithTag("gomode-voice-audio-2").assertIsDisplayed()
        val speaker = composeRule.onNodeWithTag("gomode-voice-audio-1").assertIsDisplayed()
        speaker.assertIsOn()
        earpiece.performClick().assertIsOn()
        speaker.performClick().assertIsOn()
        capture("documentation")
        composeRule.onNodeWithContentDescription("Hide transcript").performClick()
        composeRule.onNodeWithTag("gomode-voice-transcript").assertDoesNotExist()
        val restore = composeRule.onNodeWithContentDescription("Show transcript").assertIsDisplayed()
        val microphone = composeRule.onNodeWithTag("gomode-microphone-mode").fetchSemanticsNode().boundsInRoot
        val routeBounds = speaker.fetchSemanticsNode().boundsInRoot
        val level = composeRule.onNodeWithTag("gomode-voice-level").fetchSemanticsNode().boundsInRoot
        assertEquals(microphone.center.y, routeBounds.center.y, 1f)
        assertEquals(microphone.center.y, level.center.y, 1f)
        val hangup = composeRule.onNodeWithContentDescription("End voice").fetchSemanticsNode().boundsInRoot
        assertTrue(microphone.right <= level.left)
        assertTrue(level.right <= hangup.left)
        assertEquals(hangup.center.y, level.center.y, 1f)
        val activity =
            composeRule
                .onNodeWithContentDescription(
                    "Calling tool: ${scene.getString("tool")}…",
                ).fetchSemanticsNode()
                .boundsInRoot
        val restoreBounds = restore.fetchSemanticsNode().boundsInRoot
        assertTrue(restoreBounds.left >= activity.right)
        assertEquals(activity.center.y, restoreBounds.center.y, 1f)
        capture("documentation-hidden")
        composeRule.onNodeWithContentDescription("Show transcript").performClick()
        composeRule.onNodeWithTag("gomode-voice-transcript").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("End voice").performClick()
        composeRule.onNodeWithContentDescription("Connect voice assistant").assertIsDisplayed()
        speaker.assertIsDisplayed().assertIsOn()
    }

    @Test
    fun audioCanBeChosenBeforeConnectingWithoutMovingPersistentControls() {
        val state =
            mutableStateOf(
                VoiceState(
                    availableDevices =
                        listOf(
                            AudioDevice(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker"),
                            AudioDevice(2, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Earpiece"),
                        ),
                    selectedDeviceId = 1,
                ),
            )
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(
                    state.value,
                    true,
                    {},
                    {},
                    {},
                    { state.value = state.value.copy(selectedDeviceId = it) },
                    {},
                    serviceReconnect = null,
                    {},
                    true,
                )
            }
        }
        composeRule.onNodeWithTag("gomode-voice-audio-2").performClick().assertIsOn()
        val tags = listOf("gomode-web-open-settings", "gomode-next-app", "gomode-voice-audio-1", "gomode-voice-audio-2")
        val before = tags.associateWith { composeRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        for (status in listOf(
            VoiceState(connectStatus = "Connecting…"),
            VoiceState(connected = true),
            VoiceState(error = "Speech failed"),
        )) {
            composeRule.runOnIdle {
                state.value = status.copy(availableDevices = state.value.availableDevices, selectedDeviceId = 2)
            }
            for (tag in tags) {
                val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertEquals(before.getValue(tag).left, bounds.left, 1f)
                assertEquals(before.getValue(tag).center.y, bounds.center.y, 1f)
            }
        }
        composeRule.onNodeWithContentDescription("Connect voice assistant").assertIsDisplayed()
    }

    @Test
    fun gatewayFailureShowsTheCauseAndCanBeRetried() {
        var retries = 0
        val error = mutableStateOf("Voice gateway unavailable (HTTP 502).")
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(
                    VoiceState(
                        error = error.value,
                    ),
                    true,
                    { retries++ },
                    {},
                    {},
                    {},
                    {},
                    serviceReconnect = null,
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
        composeRule.onNodeWithContentDescription("Connect voice assistant").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
        composeRule.runOnIdle { error.value = VOICE_TOOL_RESULT_DELIVERY_ERROR }
        composeRule.onNodeWithText(VOICE_TOOL_RESULT_DELIVERY_ERROR).assertIsDisplayed()
        capture("delivery-error")
        composeRule.onNodeWithContentDescription("Connect voice assistant").performClick()
        composeRule.runOnIdle { assertEquals(2, retries) }
    }

    @Test
    fun longErrorUsesThePanelWidthBelowThePersistentToolbar() {
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        VoicePanel(
                            VoiceState(
                                error = VOICE_TOOL_RESULT_DELIVERY_ERROR,
                            ),
                            true,
                            {},
                            {},
                            {},
                            {},
                            {},
                            serviceReconnect = null,
                            onCycleApp = {},
                            canCycleApps = false,
                        )
                    }
                }
            }
        }
        val message = composeRule.onNodeWithTag("gomode-voice-error-message").fetchSemanticsNode().boundsInRoot
        val actions = composeRule.onNodeWithTag("gomode-voice-actions").fetchSemanticsNode().boundsInRoot
        val settingsBounds =
            composeRule
                .onNodeWithContentDescription("Settings", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(settingsBounds.left, message.left, 1f)
        assertEquals(actions.right, message.right, 1f)
        assertTrue(actions.bottom <= message.top)
        composeRule.onNodeWithContentDescription("Connect voice assistant").assertIsDisplayed()
        capture("delivery-error-large-text")
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
                    onSelectMicrophoneMode = {},
                    onSelectDevice = {},
                    onOpenSettings = {},
                    serviceReconnect = null,
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
                VoicePanel(
                    state.value,
                    true,
                    {},
                    {},
                    {},
                    {},
                    {},
                    serviceReconnect = null,
                    onCycleApp = {},
                    canCycleApps = false,
                )
            }
        }
        composeRule.onNodeWithText("Thinking…").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = state.value.withToolCall("example_tool")
        }
        composeRule.onNodeWithContentDescription("Calling tool: example_tool…").assertIsDisplayed()
        composeRule.onNodeWithText("Thinking…").assertDoesNotExist()
        composeRule.runOnIdle {
            state.value = state.value.copy(activeTool = null)
        }
        composeRule.onNodeWithText("Thinking…").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Calling tool: example_tool…").assertDoesNotExist()
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
                            availableDevices =
                                listOf(
                                    AudioDevice(1, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Earpiece"),
                                    AudioDevice(2, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Headphones"),
                                    AudioDevice(3, AudioDeviceInfo.TYPE_BLE_HEADSET, "Bluetooth headset"),
                                    AudioDevice(4, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker"),
                                    AudioDevice(5, AudioDeviceInfo.TYPE_USB_DEVICE, "USB audio"),
                                ),
                            selectedDeviceId = 1,
                        ),
                    voiceEnabled = true,
                    onConnect = {},
                    onDisconnect = {},
                    onSelectMicrophoneMode = {},
                    onSelectDevice = { selected = it },
                    onOpenSettings = {},
                    serviceReconnect = null,
                    onCycleApp = {},
                    canCycleApps = false,
                )
            }
        }
        composeRule.onNodeWithTag("gomode-voice-audio-1").assertIsOn()
        for (id in 1..5) {
            composeRule.onNodeWithTag("gomode-voice-audio-$id").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("gomode-voice-audio-icon-$id", useUnmergedTree = true).assertIsDisplayed()
        }
        composeRule
            .onNodeWithTag("gomode-voice-audio-2")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(2, selected)
    }

    @Test
    fun lockedCardShowsLiveTranscriptAndConfirmedAudioRouteWhileSwitching() {
        val state =
            mutableStateOf(
                VoiceState(
                    connected = true,
                    availableDevices =
                        listOf(
                            AudioDevice(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker"),
                            AudioDevice(2, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Tesla"),
                        ),
                    selectedDeviceId = 1,
                    transcript =
                        listOf(
                            TranscriptEntry(TranscriptSpeaker.USER, "How is the service doing?", final = true),
                            TranscriptEntry(TranscriptSpeaker.ASSISTANT, "Your service is ready."),
                        ),
                ),
            )
        var requested: Int? = null
        var ended = false
        composeRule.setContent {
            GoModeTheme {
                LockedCarCall(
                    state = CarDialVoiceState("call", "Example service", CarDialStatus.CONNECTED),
                    voiceState = state.value,
                    onSelectDevice = { requested = it },
                    onEnd = { ended = true },
                )
            }
        }
        composeRule.onNodeWithText("You: How is the service doing?").assertIsDisplayed()
        composeRule.onNodeWithText("Assistant: Your service is ready.").assertIsDisplayed()
        composeRule.onNodeWithTag("gomode-voice-audio-1").assertIsDisplayed().assertIsOn()
        composeRule.onNodeWithTag("gomode-voice-audio-2").assertIsDisplayed().performClick()
        assertEquals(2, requested)
        captureLockedCard("routes")
        composeRule.runOnIdle { state.value = state.value.copy(audioSwitching = true) }
        composeRule.onNodeWithTag("gomode-voice-audio-1").assertIsOn().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-voice-audio-2").assertIsNotEnabled()
        captureLockedCard("switching")
        composeRule.runOnIdle {
            state.value =
                state.value.copy(
                    audioSwitching = false,
                    selectedDeviceId = 2,
                    transcript = state.value.transcript + TranscriptEntry(TranscriptSpeaker.USER, "Switch to the car."),
                )
        }
        composeRule.onNodeWithTag("gomode-voice-audio-2").assertIsOn()
        composeRule.onNodeWithText("You: Switch to the car.").assertIsDisplayed()
        composeRule.onNodeWithTag("car_dial_end").assertIsDisplayed().performClick()
        assertTrue(ended)
    }

    private fun captureLockedCard(scene: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle()
        assertTrue(
            device.takeScreenshot(
                File(instrumentation.targetContext.getExternalFilesDir(null), "locked-card-$scene.png"),
            ),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun transcriptCanBeHiddenAndReopenedWithoutDeletingText() {
        lateinit var inputModes: InputModeManager
        composeRule.setContent {
            inputModes = LocalInputModeManager.current
            MaterialTheme {
                VoicePanel(
                    voiceState =
                        VoiceState(
                            connected = true,
                            listening = true,
                            transcript =
                                listOf(
                                    TranscriptEntry(TranscriptSpeaker.ASSISTANT, "Four tasks are running."),
                                    TranscriptEntry(
                                        TranscriptSpeaker.USER,
                                        "Show the launch brief and the prototype feedback before the design review.",
                                    ),
                                    TranscriptEntry(
                                        TranscriptSpeaker.ASSISTANT,
                                        "Review the launch brief and prototype feedback, then prepare the notes for tomorrow's design review.",
                                    ),
                                ),
                        ),
                    voiceEnabled = true,
                    onConnect = {},
                    onDisconnect = {},
                    onSelectMicrophoneMode = {},
                    onSelectDevice = {},
                    onOpenSettings = {},
                    serviceReconnect = null,
                    onCycleApp = {},
                    canCycleApps = false,
                )
            }
        }

        composeRule.runOnIdle { inputModes.requestInputMode(InputMode.Keyboard) }
        val toggle = composeRule.onNodeWithTag("gomode-voice-transcript-toggle")
        toggle.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        toggle.assertIsFocused()
        val content = composeRule.onNodeWithTag("gomode-voice-transcript-content").fetchSemanticsNode().boundsInRoot
        val close = toggle.fetchSemanticsNode().boundsInRoot
        assertEquals(content.top, close.top, 1f)
        assertEquals(content.right, close.right, 1f)
        val transcript = composeRule.onNodeWithTag("gomode-voice-transcript").assertIsDisplayed()
        assertEquals(content.right, transcript.fetchSemanticsNode().boundsInRoot.right, 1f)
        val message =
            composeRule
                .onNode(
                    hasTestTag("gomode-voice-text-part") and hasText("Assistant: Four tasks are running."),
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
                .boundsInRoot
        assertTrue(message.right <= close.left)
        val lowerMessage =
            composeRule
                .onNodeWithText(
                    "Assistant: Review the launch brief and prototype feedback, then prepare the notes for tomorrow's design review.",
                ).fetchSemanticsNode()
                .boundsInRoot
        assertTrue(lowerMessage.top >= close.bottom)
        assertTrue(lowerMessage.right > close.left)
        val touch = toggle.fetchSemanticsNode().touchBoundsInRoot
        with(composeRule.density) {
            assertEquals(32.dp.toPx(), touch.width, 1f)
            assertEquals(32.dp.toPx(), touch.height, 1f)
        }
        toggle.performKeyInput { pressKey(Key.Enter) }
        composeRule.onNodeWithTag("gomode-voice-transcript").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show transcript").assertIsDisplayed()
        val activity = composeRule.onNodeWithText("Listening…").fetchSemanticsNode().boundsInRoot
        assertEquals(
            activity.center.y,
            toggle
                .fetchSemanticsNode()
                .boundsInRoot.center.y,
            1f,
        )
        toggle.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        composeRule.onNodeWithText("Assistant: Four tasks are running.").assertIsDisplayed()
        transcript.assertIsFocused()
        capture("transcript-focus")
    }

    @Test
    fun hiddenTranscriptKeepsRevisionsWithoutShowingDrafts() {
        val state =
            mutableStateOf(
                VoiceState(
                    connected = true,
                    listening = true,
                    transcript = listOf(TranscriptEntry(TranscriptSpeaker.USER, "Send a")),
                ),
            )
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(
                    state.value,
                    true,
                    {},
                    {},
                    {},
                    {},
                    {},
                    serviceReconnect = null,
                    onCycleApp = {},
                    canCycleApps = false,
                )
            }
        }
        composeRule.onNodeWithContentDescription("Hide transcript").performClick()
        composeRule.onNodeWithText("You: Send a").assertDoesNotExist()
        composeRule.runOnIdle {
            state.value =
                state.value.copy(transcript = listOf(TranscriptEntry(TranscriptSpeaker.USER, "Send the message")))
        }
        composeRule.onNodeWithText("You: Send the message").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show transcript").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("You: Send the message").assertIsDisplayed()
    }

    @Test
    fun liveDraftFollowsItsBottomWithoutOverridingManualScroll() {
        val state =
            mutableStateOf(
                VoiceState(
                    connected = true,
                    transcript =
                        (1..20).map {
                            TranscriptEntry(
                                TranscriptSpeaker.ASSISTANT,
                                "Earlier turn $it",
                                final = true,
                            )
                        },
                    transcriptDraft =
                        com.fghbuild.voicegateway.sdk.v2.TranscriptDraft(
                            com.fghbuild.voicegateway.sdk.v2.MessageKind.TranscriptDraft,
                            "a",
                            (1..100).joinToString("\n") { "Recognized phrase $it." },
                        ),
                ),
            )
        composeRule.setContent {
            GoModeTheme {
                Box(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    VoicePanel(
                        state.value,
                        true,
                        {},
                        {},
                        {},
                        {},
                        {},
                        serviceReconnect = null,
                        onCycleApp = {},
                        canCycleApps = false,
                    )
                }
            }
        }
        composeRule.onNodeWithTag("gomode-voice-transcript-end").assertIsDisplayed()

        fun appendDraft() {
            composeRule.runOnIdle {
                val draft = requireNotNull(state.value.transcriptDraft)
                state.value =
                    state.value.withTranscriptDraft(draft.copy(text = draft.text + "\nNewest recognized words."))
            }
        }
        appendDraft()
        composeRule.onNodeWithTag("gomode-voice-transcript-end").assertIsDisplayed()
        capture("transcript-live-overflow-tail")
        val transcript = composeRule.onNodeWithTag("gomode-voice-transcript")
        transcript.performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("gomode-voice-transcript-end").assertIsNotDisplayed()
        val before = transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        appendDraft()
        composeRule.waitForIdle()
        val after = transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, after, 0.01f)
        composeRule.onNodeWithTag("gomode-voice-transcript-end").assertIsNotDisplayed()
        capture("transcript-live-overflow-manual")
        transcript.performScrollToNode(hasTestTag("gomode-voice-transcript-end"))
        appendDraft()
        composeRule.onNodeWithTag("gomode-voice-transcript-end").assertIsDisplayed()
        capture("transcript-live-overflow-resumed")
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
                VoicePanel(
                    state.value,
                    true,
                    {},
                    {},
                    {},
                    {},
                    {},
                    serviceReconnect = null,
                    onCycleApp = {},
                    canCycleApps = false,
                )
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
    fun wrappedTranscriptRowsKeepFollowingAfterCloseReservationChanges() {
        val entries =
            mutableStateOf(
                (1..30).map {
                    TranscriptEntry(
                        TranscriptSpeaker.ASSISTANT,
                        "Response $it: " + "Review carefully. ".repeat(it % 5 + 1),
                    )
                },
            )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    VoicePanel(
                        VoiceState(connected = true, speaking = true, transcript = entries.value),
                        true,
                        {},
                        {},
                        {},
                        {},
                        {},
                        serviceReconnect = null,
                        onCycleApp = {},
                        canCycleApps = false,
                    )
                }
            }
        }
        val end = composeRule.onNodeWithTag("gomode-voice-transcript-end")
        end.assertIsDisplayed()
        repeat(10) { index ->
            composeRule.runOnIdle {
                entries.value +=
                    TranscriptEntry(TranscriptSpeaker.ASSISTANT, "Response ${31 + index} should be reviewed soon.")
            }
            end.assertIsDisplayed()
        }
    }

    @Test
    fun floatingTextKeepsInlineMetricsOnTheTextBaseline() {
        val value = mutableStateOf("Assistant: Done.")
        composeRule.setContent {
            MaterialTheme {
                VoiceTextFlow(
                    androidx.compose.ui.text
                        .AnnotatedString(value.value),
                    MaterialTheme.typography.bodySmall,
                    MaterialTheme.colorScheme.onSurface,
                    with(LocalDensity.current) { TextExclusion(48.dp.roundToPx(), 52.dp.roundToPx()) },
                    Modifier.width(320.dp).testTag("floating-text"),
                    metrics = {
                        androidx.compose.material3.Text(
                            "↑ 12",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("inline-count"),
                        )
                    },
                )
            }
        }

        fun layouts(tag: String): List<TextLayoutResult> {
            val result = mutableListOf<TextLayoutResult>()
            if (tag == "floating-text") {
                val nodes = composeRule.onAllNodesWithTag("gomode-voice-text-part", useUnmergedTree = true)
                repeat(nodes.fetchSemanticsNodes().size) { index ->
                    nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
                }
            } else {
                composeRule
                    .onNodeWithTag(
                        tag,
                    ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
            }
            return result
        }

        fun checkBaseline() {
            val parts = layouts("floating-text")
            val last = parts.last()
            val textBox =
                composeRule
                    .onAllNodesWithTag("gomode-voice-text-part", useUnmergedTree = true)[parts.lastIndex]
                    .fetchSemanticsNode()
                    .boundsInRoot
            val countBox = composeRule.onNodeWithTag("inline-count").fetchSemanticsNode().boundsInRoot
            val expected = textBox.top + last.getLineBaseline(last.lineCount - 1)
            val actual = countBox.top + layouts("inline-count").single().firstBaseline
            assertEquals(expected, actual, 1f)
        }
        checkBaseline()
        composeRule.runOnIdle {
            value.value = "Assistant: " + "Compare the launch brief and the prototype feedback carefully. ".repeat(8)
        }
        composeRule.onNodeWithText(value.value).assert(
            SemanticsMatcher.keyNotDefined(SemanticsActions.GetTextLayoutResult),
        )
        val parts = layouts("floating-text")
        assertTrue(
            parts
                .first()
                .layoutInput.constraints.maxWidth <
                parts
                    .last()
                    .layoutInput.constraints.maxWidth,
        )
        checkBaseline()
        composeRule.runOnIdle { value.value = "Assistant: " + "مرحبا بالعالم ".repeat(24) }
        val arabic = layouts("floating-text")
        assertEquals(arabic.first().getParagraphDirection(0), arabic.last().getParagraphDirection(0))
    }

    @Test
    fun unknownUsageTotalsKeepTheModelAndReportingStatusVisible() {
        val counts =
            com.fghbuild.voicegateway.sdk.v2
                .TokenUsage()
        val report =
            com.fghbuild.voicegateway.sdk.v2.SessionUsage(
                com.fghbuild.voicegateway.sdk.v2.MessageKind.SessionUsage,
                "fixture",
                "llamacpp",
                "LFM3-audio",
                counts,
            )
        composeRule.setContent {
            GoModeTheme {
                VoicePanel(
                    VoiceState(usage = VoiceUsage(counts, report)),
                    true,
                    {},
                    {},
                    {},
                    {},
                    {},
                    serviceReconnect = null,
                    onCycleApp = {},
                    canCycleApps = false,
                    showUsageStats = true,
                )
            }
        }
        composeRule.onNodeWithText("llamacpp · LFM3-audio · Not reported").assertIsDisplayed()
    }

    @Test
    fun voiceErrorRemainsVisibleWhenVoiceIsUnavailable() {
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(
                    voiceState = VoiceState(error = "llm: out of memory"),
                    voiceEnabled = false,
                    onConnect = {},
                    onDisconnect = {},
                    onSelectMicrophoneMode = {},
                    onSelectDevice = {},
                    onOpenSettings = {},
                    serviceReconnect = null,
                    onCycleApp = {},
                    canCycleApps = false,
                )
            }
        }

        composeRule.onNodeWithText("llm: out of memory").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Voice unavailable").assertIsNotEnabled()
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureVoicePanel") != "true") return
        composeRule.waitForIdle()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        if (name.startsWith("documentation")) {
            // Touch the inert hosted page so captures show the panel without control focus.
            device.click(device.displayWidth / 2, device.displayHeight / 4)
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("gomode-voice-actions").captureToImage()
        device.waitForIdle()
        val path = "/data/local/tmp/gomode-voice-panel-$name.png"
        device.executeShellCommand("screencap -p $path")
        assertTrue(device.executeShellCommand("stat -c %s $path").trim().toLong() > 0)
    }
}
