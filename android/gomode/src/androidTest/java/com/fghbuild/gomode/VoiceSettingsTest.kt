// Compose tests for saved voice mode, language, and the active-session settings guard.
package com.fghbuild.gomode

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.ui.settings.SettingsScreen
import com.fghbuild.gomode.ui.settings.rememberSettingsEdits
import com.fghbuild.gomode.ui.theme.GoModeTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class VoiceSettingsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun serviceURLsUseTheFullWidthAndRetryStaysBesideStatusAtLargerTextSizes() {
        val repository = SettingsRepository(MemoryPreferences())
        val readyID: String
        val loadingID: String
        val longURL = "https://development.giraffe-cobra.example.net"
        runBlocking {
            readyID = repository.addService("Quick", "https://quick.example.net")
            loadingID = repository.addService("Documents", longURL)
            repository.switchService(readyID)
            // Keep gateway discovery out of this controlled layout fixture.
            repository.updateVoiceMode(VoiceMode.DEVICE)
            repository.updateOnDeviceEngine(OnDeviceEngine.LOCAL_LLM)
        }
        val fontScale = mutableStateOf(1f)
        val serviceError = mutableStateOf<String?>(null)
        var retried: String? = null
        val errorMessage = "The service could not be reached. Check your connection and retry."
        composeRule.setContent {
            val settings by repository.settings.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                GoModeTheme {
                    Box(Modifier.width(360.dp)) {
                        SettingsScreen(
                            settings = settings,
                            settingsRepository = repository,
                            voiceSessionActive = false,
                            onDone = {},
                            onOpenHalo = {},
                            edits = rememberSettingsEdits(repository, settings),
                            serviceReadiness = mapOf(readyID to "Ready", loadingID to "Loading page"),
                            serviceErrors = serviceError.value?.let { mapOf(loadingID to it) } ?: emptyMap(),
                            serviceRetryLabels = mapOf(loadingID to "Reload page"),
                            onRetryService = { retried = it },
                        )
                    }
                }
            }
        }
        for (scale in listOf(1f, 1.5f)) {
            composeRule.runOnIdle { fontScale.value = scale }
            val retry = composeRule.onNodeWithTag("gomode-retry-$loadingID")
            retry.performScrollTo().assertContentDescriptionEquals("Reload page")
            val url = composeRule.onNodeWithText(longURL, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val screen = composeRule.onNodeWithTag("gomode-settings").fetchSemanticsNode().boundsInRoot
            assertTrue("Service actions must not narrow the URL", url.width > screen.width * 0.8f)
            val status =
                composeRule
                    .onNodeWithTag("gomode-readiness-$loadingID", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val button = retry.fetchSemanticsNode().boundsInRoot
            assertTrue("Reload must share the status row", button.top < status.bottom && status.top < button.bottom)
            retry.performClick()
            composeRule.runOnIdle { assertEquals(loadingID, retried) }
        }
        composeRule.runOnIdle { serviceError.value = errorMessage }
        composeRule.onNodeWithTag("gomode-retry-$loadingID").performScrollTo().assertIsEnabled()
        val error = composeRule.onNodeWithText(errorMessage).fetchSemanticsNode().boundsInRoot
        val status = composeRule.onNodeWithTag("gomode-readiness-$loadingID").fetchSemanticsNode().boundsInRoot
        val screen = composeRule.onNodeWithTag("gomode-settings").fetchSemanticsNode().boundsInRoot
        assertTrue("Errors must wrap below status", error.top >= status.bottom)
        assertTrue("Errors must stay within the screen", error.left >= screen.left && error.right <= screen.right)
    }

    @Test
    fun thinkingMusicPreviewStopsForCollapseFocusLossVoiceAndSettingsExit() {
        val active = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val repository = SettingsRepository(MemoryPreferences())
        val audio =
            InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(
                AudioManager::class.java,
            )
        composeRule.setContent {
            val settings by repository.settings.collectAsState()
            MaterialTheme {
                if (visible.value) {
                    SettingsScreen(
                        settings,
                        repository,
                        active.value,
                        {},
                        {},
                        edits = rememberSettingsEdits(repository, settings),
                    )
                }
            }
        }

        fun awaitMusic(playing: Boolean) {
            composeRule.waitUntil(5_000) {
                audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA } ==
                    playing
            }
        }

        fun play() {
            composeRule
                .onNodeWithTag("gomode-thinking-music-preview")
                .performScrollTo()
                .assertIsEnabled()
                .assertContentDescriptionEquals("Play thinking music")
                .performClick()
            awaitMusic(true)
            composeRule
                .onNodeWithTag(
                    "gomode-thinking-music-preview",
                ).assertContentDescriptionEquals("Stop thinking music")
        }
        composeRule.onNodeWithTag("gomode-credits").performScrollTo().performClick()
        play()
        composeRule.onNodeWithTag("gomode-credits").performScrollTo().performClick()
        awaitMusic(false)
        composeRule.onNodeWithTag("gomode-credits").performScrollTo().performClick()
        play()
        val otherFocus =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener {}
                .build()
        try {
            composeRule.runOnIdle { audio.requestAudioFocus(otherFocus) }
            awaitMusic(false)
            composeRule
                .onNodeWithTag(
                    "gomode-thinking-music-preview",
                ).assertContentDescriptionEquals("Play thinking music")
        } finally {
            audio.abandonAudioFocusRequest(otherFocus)
        }
        play()
        composeRule.runOnIdle { active.value = true }
        awaitMusic(false)
        composeRule
            .onNodeWithTag("gomode-thinking-music-preview")
            .assertIsNotEnabled()
            .assertContentDescriptionEquals("Play thinking music")
        composeRule.runOnIdle { active.value = false }
        play()
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        awaitMusic(false)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        play()
        composeRule.runOnIdle { visible.value = false }
        awaitMusic(false)
    }

    @Test
    fun lockScreenServiceTogglePersistsAndCannotChangeDuringCalls() {
        val active = mutableStateOf(false)
        val store = MemoryPreferences()
        val repository = SettingsRepository(store)
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
        composeRule.setContent {
            val settings by repository.settings.collectAsState()
            GoModeTheme {
                SettingsScreen(
                    settings = settings,
                    settingsRepository = repository,
                    edits = rememberSettingsEdits(repository, settings),
                    voiceSessionActive = active.value,
                    onDone = {},
                    onOpenHalo = {},
                )
            }
        }
        val toggle = composeRule.onNodeWithTag("gomode-show-service-on-lock-screen")
        toggle.performScrollTo().assertIsOff()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val directory = instrumentation.targetContext.getExternalFilesDir(null)
        composeRule.waitForIdle()
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(directory, "lock-screen-setting-off.png")))
        toggle.performClick()
        composeRule.waitUntil { repository.settings.value.showServiceOnLockScreen }
        toggle.performScrollTo().assertIsOn()
        composeRule.waitForIdle()
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(directory, "lock-screen-setting-on.png")))
        val reopened = SettingsRepository(store)
        composeRule.waitUntil { reopened.settings.value.showServiceOnLockScreen }
        composeRule.runOnIdle { active.value = true }
        toggle.performScrollTo().assertIsNotEnabled()
        composeRule.waitForIdle()
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(directory, "lock-screen-setting-disabled.png")))
        composeRule.runOnIdle { active.value = false }
        toggle.performClick()
        composeRule.waitUntil { !repository.settings.value.showServiceOnLockScreen }
        toggle.assertIsOff()
    }

    @Test
    fun voiceModeChipsSaveSelectionAndDisableDuringAnActiveSession() {
        val active = mutableStateOf(false)
        val store = MemoryPreferences()
        val repository = SettingsRepository(store)
        composeRule.setContent {
            val settings by repository.settings.collectAsState()
            MaterialTheme {
                SettingsScreen(
                    settings = settings,
                    settingsRepository = repository,
                    edits = rememberSettingsEdits(repository, settings),
                    voiceSessionActive = active.value,
                    onDone = {},
                    onOpenHalo = {},
                )
            }
        }
        composeRule.onNodeWithTag("gomode-voice-mode-cloud").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("gomode-voice-mode-device").performScrollTo().performClick()
        composeRule.waitUntil { repository.settings.value.voiceMode == VoiceMode.DEVICE }
        composeRule.onNodeWithTag("gomode-voice-mode-device").assertIsSelected()
        val reopened = SettingsRepository(store)
        composeRule.waitUntil { reopened.settings.value.voiceMode == VoiceMode.DEVICE }

        composeRule.runOnIdle { active.value = true }
        composeRule.onNodeWithTag("gomode-voice-mode-cloud").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-voice-mode-device").assertIsNotEnabled()
        composeRule.runOnIdle { active.value = false }
        composeRule.onNodeWithTag("gomode-voice-mode-cloud").performClick()
        composeRule.waitUntil { repository.settings.value.voiceMode == VoiceMode.CLOUD }
        composeRule.onNodeWithTag("gomode-voice-mode-cloud").assertIsSelected()
    }

    @Test
    fun languageCannotChangeDuringAnActiveSession() {
        val active = mutableStateOf(true)
        val repository = SettingsRepository(MemoryPreferences())
        composeRule.setContent {
            val settings by repository.settings.collectAsState()
            MaterialTheme {
                SettingsScreen(
                    settings = settings,
                    settingsRepository = repository,
                    edits = rememberSettingsEdits(repository, settings),
                    voiceSessionActive = active.value,
                    onDone = {},
                    onOpenHalo = {},
                )
            }
        }
        composeRule.onNodeWithTag("gomode-voice-language").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { active.value = false }
        composeRule
            .onNodeWithTag("gomode-voice-language")
            .performScrollTo()
            .assertIsEnabled()
            .performTextReplacement("fr-CA")
        composeRule.waitUntil(5000) { repository.settings.value.voiceLanguageTag == "fr-CA" }
        composeRule.runOnIdle { active.value = true }
        composeRule.onNodeWithTag("gomode-voice-language").assertIsNotEnabled()
    }

    @Test
    fun backFlushesLanguageWithoutWaitingForAutosave() {
        val store = MemoryPreferences()
        val repository = SettingsRepository(store)
        runBlocking {
            val id = repository.addService("Service", "https://example.test")
            repository.settings.first { it.activeServiceId == id }
        }
        var closed = false
        composeRule.setContent {
            val settings by repository.settings.collectAsState()
            MaterialTheme {
                SettingsScreen(
                    settings,
                    repository,
                    false,
                    { closed = true },
                    {},
                    edits = rememberSettingsEdits(repository, settings),
                )
            }
        }
        composeRule.onNodeWithTag("gomode-voice-language").performScrollTo().performTextReplacement("fr-CA")
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil { closed }
        val reopened = SettingsRepository(store)
        composeRule.waitUntil { reopened.settings.value.voiceLanguageTag == "fr-CA" }
    }

    @Test
    fun onDeviceEngineChipsSaveSelectionAndDisableDuringAnActiveSession() {
        val active = mutableStateOf(false)
        val store = MemoryPreferences()
        val repository = SettingsRepository(store)
        composeRule.setContent {
            val settings by repository.settings.collectAsState()
            MaterialTheme {
                SettingsScreen(
                    settings = settings,
                    settingsRepository = repository,
                    edits = rememberSettingsEdits(repository, settings),
                    voiceSessionActive = active.value,
                    onDone = {},
                    onOpenHalo = {},
                )
            }
        }
        // Switch to device voice mode to reveal engine selection chips.
        composeRule.onNodeWithTag("gomode-voice-mode-device").performScrollTo().performClick()
        composeRule.waitUntil { repository.settings.value.voiceMode == VoiceMode.DEVICE }

        // Initial default engine is remote gateway.
        composeRule.onNodeWithText("Engine").assertExists()
        composeRule
            .onNodeWithTag("gomode-engine-remote-gateway")
            .performScrollTo()
            .assertIsSelected()
            .assertTextContains("Remote LLM")

        // Select NeuDecide.
        composeRule.onNodeWithTag("gomode-engine-neudecide").performScrollTo().performClick()
        composeRule.waitUntil { repository.settings.value.onDeviceEngine == OnDeviceEngine.NEUDECIDE }
        composeRule.onNodeWithTag("gomode-engine-neudecide").assertIsSelected()

        // Verify chips disable during an active voice session.
        composeRule.runOnIdle { active.value = true }
        composeRule.onNodeWithTag("gomode-engine-remote-gateway").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-engine-neudecide").assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-engine-local-llm").assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-engine-needle-whistle").assertIsNotEnabled()

        composeRule.runOnIdle { active.value = false }
        composeRule.onNodeWithTag("gomode-engine-local-llm").performScrollTo().performClick()
        composeRule.waitUntil { repository.settings.value.onDeviceEngine == OnDeviceEngine.LOCAL_LLM }
        composeRule.onNodeWithTag("gomode-engine-local-llm").assertIsSelected().assertTextContains("Local LLM")
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E2B").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E4B").performScrollTo().performClick()
        composeRule.waitUntil {
            repository.settings.value.localLlmModel == com.fghbuild.gomode.data.LocalLlmModel.GEMMA_4_E4B
        }
        composeRule.onNodeWithTag("gomode-model-LFM_3_1_7B").performScrollTo().performClick()
        composeRule.waitUntil {
            repository.settings.value.localLlmModel == com.fghbuild.gomode.data.LocalLlmModel.LFM_3_1_7B
        }
        composeRule.onNodeWithTag("gomode-model-LFM_3_1_7B").assertIsSelected()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val directory = instrumentation.targetContext.getExternalFilesDir(null)
        composeRule.waitForIdle()
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(directory, "lfm3-model-picker.png")))
        composeRule.runOnIdle { active.value = true }
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E2B").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E4B").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-model-LFM_3_1_7B").performScrollTo().assertIsNotEnabled()
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val writeMutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            writeMutex.withLock { transform(state.value).also { state.value = it } }
    }
}
