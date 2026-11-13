// Compose tests for saved voice mode, language, and the active-session settings guard.
package com.fghbuild.gomode

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
import androidx.compose.ui.test.ExperimentalTestApi
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
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceLanguageModel
import com.fghbuild.gomode.data.VoiceSetupPreference
import com.fghbuild.gomode.ui.settings.ServiceStatus
import com.fghbuild.gomode.ui.settings.SettingsScreen
import com.fghbuild.gomode.ui.settings.rememberSettingsEdits
import com.fghbuild.gomode.ui.theme.GoModeTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// The stock test emulator's display: layout assertions measure against its width.
@Config(qualifiers = "w411dp-h914dp-280dpi")
class VoiceSettingsTest {
    @get:Rule
    @OptIn(ExperimentalTestApi::class)
    val composeRule = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
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
            repository.updateVoiceSetup(VoiceSetupPreference::withDeviceSpeech)
            repository.updateVoiceSetup { it.copy(languageModel = VoiceLanguageModel.LOCAL_LLM) }
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
                            serviceStatus =
                                mapOf(
                                    readyID to ServiceStatus(readiness = "Ready"),
                                    loadingID to
                                        ServiceStatus(
                                            readiness = "Loading page",
                                            error = serviceError.value,
                                            retryLabel = "Reload page",
                                        ),
                                ),
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
        toggle.performClick()
        composeRule.waitUntil { repository.settings.value.showServiceOnLockScreen }
        toggle.performScrollTo().assertIsOn()
        val reopened = SettingsRepository(store)
        composeRule.waitUntil { reopened.settings.value.showServiceOnLockScreen }
        composeRule.runOnIdle { active.value = true }
        toggle.performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { active.value = false }
        toggle.performClick()
        composeRule.waitUntil { !repository.settings.value.showServiceOnLockScreen }
        toggle.assertIsOff()
    }

    @Test
    fun reasoningTogglePersistsAndStaysAvailableDuringCalls() {
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
        val toggle = composeRule.onNodeWithTag("gomode-show-reasoning")
        toggle.performScrollTo().assertIsOff()
        toggle.performClick()
        composeRule.waitUntil { repository.settings.value.showReasoning }
        toggle.performScrollTo().assertIsOn()
        val reopened = SettingsRepository(store)
        composeRule.waitUntil { reopened.settings.value.showReasoning }
        composeRule.runOnIdle { active.value = true }
        // Display-only: it stays available while a call runs.
        toggle.performScrollTo().assertIsEnabled()
    }

    @Test
    fun usageTogglePersistsAndStaysAvailableDuringCalls() {
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
        val toggle = composeRule.onNodeWithTag("gomode-show-usage-stats")
        toggle.performScrollTo().assertIsOff()
        toggle.performClick()
        composeRule.waitUntil { repository.settings.value.showUsageStats }
        toggle.performScrollTo().assertIsOn()
        val reopened = SettingsRepository(store)
        composeRule.waitUntil { reopened.settings.value.showUsageStats }
        composeRule.runOnIdle { active.value = true }
        // Display-only: it stays available while a call runs.
        toggle.performScrollTo().assertIsEnabled()
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
        composeRule.waitUntil { repository.settings.value.voiceSetup.deviceSpeech }
        composeRule.onNodeWithTag("gomode-voice-mode-device").assertIsSelected()
        val reopened = SettingsRepository(store)
        composeRule.waitUntil { reopened.settings.value.voiceSetup.deviceSpeech }

        composeRule.runOnIdle { active.value = true }
        composeRule.onNodeWithTag("gomode-voice-mode-cloud").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-voice-mode-device").assertIsNotEnabled()
        composeRule.runOnIdle { active.value = false }
        composeRule.onNodeWithTag("gomode-voice-mode-cloud").performClick()
        composeRule.waitUntil { !repository.settings.value.voiceSetup.deviceSpeech }
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
        composeRule.waitUntil { repository.settings.value.voiceSetup.deviceSpeech }

        // Initial default engine is remote gateway.
        composeRule.onNodeWithText("Engine").assertExists()
        composeRule
            .onNodeWithTag("gomode-engine-remote-gateway")
            .performScrollTo()
            .assertIsSelected()
            .assertTextContains("Remote LLM")

        // Select Whistle + Needle.
        composeRule.onNodeWithTag("gomode-engine-needle-whistle").performScrollTo().performClick()
        composeRule.waitUntil {
            repository.settings.value.voiceSetup.effectiveLanguageModel == VoiceLanguageModel.NEEDLE_WHISTLE
        }
        composeRule.onNodeWithTag("gomode-engine-needle-whistle").assertIsSelected()

        // Verify chips disable during an active voice session.
        composeRule.runOnIdle { active.value = true }
        composeRule.onNodeWithTag("gomode-engine-remote-gateway").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-engine-needle-whistle").assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-engine-local-llm").assertIsNotEnabled()

        composeRule.runOnIdle { active.value = false }
        composeRule.onNodeWithTag("gomode-engine-local-llm").performScrollTo().performClick()
        composeRule.waitUntil {
            repository.settings.value.voiceSetup.effectiveLanguageModel == VoiceLanguageModel.LOCAL_LLM
        }
        composeRule.onNodeWithTag("gomode-engine-local-llm").assertIsSelected().assertTextContains("Local LLM")
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E2B").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E4B").performScrollTo().performClick()
        composeRule.waitUntil {
            repository.settings.value.localLlmModel == com.fghbuild.gomode.data.LocalLlmModel.GEMMA_4_E4B
        }
        composeRule.onNodeWithTag("gomode-model-LFM_3_1_7B_64K").performScrollTo().performClick()
        composeRule.waitUntil {
            repository.settings.value.localLlmModel == com.fghbuild.gomode.data.LocalLlmModel.LFM_3_1_7B_64K
        }
        composeRule.onNodeWithTag("gomode-model-LFM_3_1_7B_64K").assertIsSelected()
        composeRule.onNodeWithTag("gomode-model-LFM_3_1_7B_32K").performScrollTo().performClick()
        composeRule.waitUntil {
            repository.settings.value.localLlmModel == com.fghbuild.gomode.data.LocalLlmModel.LFM_3_1_7B_32K
        }
        composeRule.onNodeWithTag("gomode-model-LFM_3_1_7B_32K").assertIsSelected()
        composeRule.onNodeWithTag("gomode-model-LFM_3_1_7B_64K").assertTextContains("64K", substring = true)
        composeRule.onNodeWithTag("gomode-model-LFM_3_1_7B_32K").assertTextContains("32K", substring = true)
        composeRule.runOnIdle { active.value = true }
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E2B").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E4B").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-model-LFM_3_1_7B_64K").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-model-LFM_3_1_7B_32K").performScrollTo().assertIsNotEnabled()
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val writeMutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            writeMutex.withLock { transform(state.value).also { state.value = it } }
    }
}
