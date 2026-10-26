// Compose UI tests for Go Mode voice controls, activity, and transcript layout.
package com.fghbuild.gomode.voice

import android.media.AudioDeviceInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.caic.voicegateway.sdk.v1.TurnState
import com.fghbuild.gomode.ui.theme.GoModeTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class VoicePanelTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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
                        serviceStatusText = null,
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
                        null,
                        onCycleApp = {},
                        canCycleApps = false,
                    )
                }
            }
        }

        val microphone = composeRule.onNodeWithTag("gomode-microphone-mode")

        fun assertState(expected: String) {
            microphone.assert(
                androidx.compose.ui.test.SemanticsMatcher
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
        val state = mutableStateOf(VoiceState(connected = true, activeTool = tool))
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
                                null,
                                onCycleApp = { switches++ },
                                canCycleApps = true,
                            )
                        }
                    }
                }
            }
        }
        val status = composeRule.onNodeWithText("Calling tool: $tool…").assertIsDisplayed()
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
        assertEquals(actions.left, activity.left, 1f)
        assertEquals(actions.right, activity.right, 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        status.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(layout.lineCount >= 3)
        assertFalse(layout.hasVisualOverflow)
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
                                state.value.copy(connected = true, activeTool = scene.getString("tool"))
                        },
                        onDisconnect = { state.value = state.value.copy(connected = false, activeTool = null) },
                        onSelectMicrophoneMode = { state.value = state.value.copy(microphoneMode = it) },
                        onSelectDevice = { state.value = state.value.copy(selectedDeviceId = it) },
                        onOpenSettings = {},
                        serviceStatusText = null,
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
        composeRule.onNodeWithText("Calling tool: ${scene.getString("tool")}…").assertIsDisplayed()
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
                .onNodeWithText(
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
                    null,
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
        composeRule.onNodeWithContentDescription("Connect voice assistant").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
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
                            null,
                            onCycleApp = {},
                            canCycleApps = false,
                        )
                    }
                }
            }
        }
        val message = composeRule.onNodeWithTag("gomode-voice-error-message").fetchSemanticsNode().boundsInRoot
        val actions = composeRule.onNodeWithTag("gomode-voice-actions").fetchSemanticsNode().boundsInRoot
        assertEquals(actions.left, message.left, 1f)
        assertEquals(actions.right, message.right, 1f)
        assertTrue(actions.bottom <= message.top)
        composeRule.onNodeWithContentDescription("Connect voice assistant").assertIsDisplayed()
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
                VoicePanel(state.value, true, {}, {}, {}, {}, {}, null, {}, false)
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
                    serviceStatusText = null,
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
                                ),
                        ),
                    voiceEnabled = true,
                    onConnect = {},
                    onDisconnect = {},
                    onSelectMicrophoneMode = {},
                    onSelectDevice = {},
                    onOpenSettings = {},
                    serviceStatusText = null,
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
        assertTrue(transcript.fetchSemanticsNode().boundsInRoot.right <= close.left)
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
        toggle.assertIsFocused()
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
                VoicePanel(state.value, true, {}, {}, {}, {}, {}, null, {}, false)
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
                VoicePanel(state.value, true, {}, {}, {}, {}, {}, null, {}, false)
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
        composeRule.setContent {
            MaterialTheme {
                VoicePanel(
                    voiceState = VoiceState(error = "Voice turn failed (llm)"),
                    voiceEnabled = false,
                    onConnect = {},
                    onDisconnect = {},
                    onSelectMicrophoneMode = {},
                    onSelectDevice = {},
                    onOpenSettings = {},
                    serviceStatusText = null,
                    onCycleApp = {},
                    canCycleApps = false,
                )
            }
        }

        composeRule.onNodeWithText("Voice turn failed (llm)").assertIsDisplayed()
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
