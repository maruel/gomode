// Compose tests for the voice setup without a gateway, language, display toggles, and the active-session guard.
package com.fghbuild.gomode

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.ViewGroup
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceLanguageModel
import com.fghbuild.gomode.data.VoiceSetupMode
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

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
            repository.updateSavedVoiceSetup(null) {
                it.copy(preference = it.preference.withLanguageModel(VoiceLanguageModel.LOCAL_LLM))
            }
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
    fun withoutAServiceASavedAllInOneSetupAsksForAService() {
        val repository = SettingsRepository(MemoryPreferences())
        runBlocking {
            repository.updateSavedVoiceSetup(null) {
                it.copy(preference = it.preference.copy(mode = VoiceSetupMode.ALL_IN_ONE))
            }
        }
        showSettings(repository) { false }
        composeRule.onNodeWithTag("gomode-voice-setup-all-in-one").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("gomode-all-in-one-no-service").assertTextContains("Add a service to use All-in-one.")
        composeRule.onNodeWithTag("gomode-voice-setup-separate-stages").assertIsEnabled()
    }

    @Test
    fun withoutAServiceTheFollowedSetupShowsDeviceStagesAndLocksDuringASession() {
        val active = mutableStateOf(false)
        val store = MemoryPreferences()
        val repository = SettingsRepository(store)
        showSettings(repository) { active.value }
        // Without a gateway, the followed setup shows the device stages its first edit saves.
        val allInOne = composeRule.onNodeWithTag("gomode-voice-setup-all-in-one")
        val separate = composeRule.onNodeWithTag("gomode-voice-setup-separate-stages")
        allInOne.performScrollTo().assertIsNotSelected().assertIsNotEnabled()
        separate.assertIsSelected().assertIsEnabled()
        composeRule.onNodeWithTag("gomode-recognition").performScrollTo().assertTextContains("Local")
        composeRule
            .onNodeWithTag("gomode-language-model")
            .performScrollTo()
            .assertTextContains("Choose an available model")
        composeRule.onNodeWithTag("gomode-speech").performScrollTo().assertTextContains("Local")
        composeRule.onNodeWithTag("gomode-device-speech-rate").performScrollTo().assertTextContains("1× (Normal)")
        assertEquals(VoiceSetupMode.FOLLOW_GATEWAY, repository.settings.value.voiceSetup.mode)
        composeRule.onNodeWithTag("gomode-speech").performClick()
        composeRule.onNodeWithTag("gomode-speech-device").assertIsSelected()
        composeRule.onNodeWithTag("gomode-speech-device").performClick()
        assertEquals(VoiceSetupMode.FOLLOW_GATEWAY, repository.settings.value.voiceSetup.mode)
        composeRule.onNodeWithTag("gomode-language-model").performScrollTo().performClick()
        composeRule.onNodeWithTag("gomode-language-model-needle-whistle").performClick()
        val reopened = SettingsRepository(store)
        composeRule.waitUntil {
            reopened.settings.value.voiceSetup.let {
                it.mode == VoiceSetupMode.SEPARATE_STAGES && it.deviceSpeech &&
                    it.languageModel == VoiceLanguageModel.NEEDLE_WHISTLE
            }
        }
        separate.performScrollTo().assertIsSelected()

        composeRule.runOnIdle { active.value = true }
        separate.performScrollTo().assertIsSelected().assertIsNotEnabled()
        for (tag in listOf("gomode-recognition", "gomode-language-model", "gomode-speech")) {
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
    }

    @Test
    fun deviceSpeakingSpeedPersistsAndLocksDuringASession() {
        val active = mutableStateOf(false)
        val store = MemoryPreferences()
        val repository = SettingsRepository(store)
        showSettings(repository) { active.value }
        composeRule
            .onNodeWithTag("gomode-device-speech-rate")
            .performScrollTo()
            .assertTextContains("1× (Normal)")
            .performClick()
        composeRule.onNodeWithTag("gomode-device-speech-rate-1.0").assertIsSelected()
        // A held write keeps the picker disabled while it saves, as a slow disk does.
        runBlocking { store.writes.lock() }
        composeRule.onNodeWithTag("gomode-device-speech-rate-1.25").assertIsNotSelected().performClick()
        composeRule.onNodeWithTag("gomode-device-speech-rate").assertIsNotEnabled()
        store.writes.unlock()
        composeRule.waitUntil { repository.settings.value.voiceSetup.deviceSpeechRate == 1.25f }
        composeRule.onNodeWithTag("gomode-device-speech-rate").assertTextContains("1.25×")
        // After a save, reselecting the current speed closes the menu without saving.
        composeRule.onNodeWithTag("gomode-device-speech-rate").performClick()
        composeRule.onNodeWithTag("gomode-device-speech-rate-1.25").assertIsSelected().performClick()
        composeRule.onNodeWithTag("gomode-device-speech-rates").assertDoesNotExist()
        val reopened = SettingsRepository(store)
        composeRule.waitUntil { reopened.settings.value.voiceSetup.deviceSpeechRate == 1.25f }

        composeRule.runOnIdle { active.value = true }
        composeRule.onNodeWithTag("gomode-device-speech-rate").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun localSpeakingVoiceListsTheLanguageVoicesAfterTheDefaultAndPersists() {
        val active = mutableStateOf(false)
        val store = MemoryPreferences()
        val repository = SettingsRepository(store)
        val us = Voice("en-us-x-one", Locale.US, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
        val gb = Voice("en-gb-x-two", Locale.UK, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
        val french = Voice("fr-fr-x-three", Locale.FRANCE, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
        val missing =
            Voice(
                "en-us-x-missing",
                Locale.US,
                Voice.QUALITY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
            )
        listOf(us, gb, french, missing).forEach(ShadowTextToSpeech::addVoice)
        showSettings(repository) { active.value }
        val picker = composeRule.onNodeWithTag("gomode-device-speaking-voice")
        // The picker waits for the engine's voices.
        picker.performScrollTo().assertTextContains("Local default").assertIsNotEnabled()
        initializeTextToSpeech()
        composeRule.waitUntil { runCatching { picker.assertIsEnabled() }.isSuccess }
        // Speaking voice sits between Speech and Speaking speed, as on web.
        val tops =
            listOf("gomode-speech", "gomode-device-speaking-voice", "gomode-device-speech-rate").map {
                composeRule
                    .onNodeWithTag(it)
                    .fetchSemanticsNode()
                    .positionInRoot.y
            }
        assertEquals(tops.sorted(), tops)

        picker.performClick()
        val items =
            composeRule
                .onAllNodes(hasAnyAncestor(hasTestTag("gomode-device-speaking-voices")) and hasClickAction())
                .fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.TestTag] }
        assertEquals(
            listOf(
                "gomode-device-speaking-voice-default",
                "gomode-device-speaking-voice-en-gb-x-two",
                "gomode-device-speaking-voice-en-us-x-one",
            ),
            items,
        )
        composeRule.onNodeWithTag("gomode-device-speaking-voice-default").assertIsSelected()
        composeRule
            .onNodeWithTag("gomode-device-speaking-voice-en-us-x-one")
            .assertTextContains("en-us-x-one (en-US)")
            .performClick()
        val reopened = SettingsRepository(store)
        composeRule.waitUntil { reopened.settings.value.voiceSetup.deviceSpeechVoice == "en-us-x-one" }
        picker.assertTextContains("en-us-x-one (en-US)").performClick()
        composeRule.onNodeWithTag("gomode-device-speaking-voice-en-us-x-one").assertIsSelected()
        composeRule.onNodeWithTag("gomode-device-speaking-voice-default").assertIsNotSelected()
        composeRule.onNodeWithTag("gomode-device-speaking-voice-en-us-x-one").performClick()

        // A voice outside the voice language shows as the default speech uses; the saved name stays.
        runBlocking { repository.updateVoiceLanguageTag("fr-CA") }
        picker.performScrollTo().assertTextContains("Local default").performClick()
        assertEquals("en-us-x-one", repository.settings.value.voiceSetup.deviceSpeechVoice)
        composeRule.onNodeWithTag("gomode-device-speaking-voice-default").assertIsSelected()
        composeRule.onNodeWithTag("gomode-device-speaking-voice-fr-fr-x-three").assertIsNotSelected().performClick()
        composeRule.waitUntil { repository.settings.value.voiceSetup.deviceSpeechVoice == "fr-fr-x-three" }

        composeRule.runOnIdle { active.value = true }
        picker.performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun withoutAServiceTheGatewayLanguageModelAsksForAServiceOrALocalModel() {
        val repository = SettingsRepository(MemoryPreferences())
        showSettings(repository) { false }
        val hint = composeRule.onNodeWithTag("gomode-language-model-hint")
        hint.performScrollTo().assertTextContains("Add a service, or choose a Local language model.")
        composeRule
            .onNodeWithTag("gomode-language-model")
            .assertContentDescriptionEquals("Language model. Add a service, or choose a Local language model.")
        composeRule.onNodeWithTag("gomode-language-model").performClick()
        composeRule.onNodeWithTag("gomode-language-model-needle-whistle").performClick()
        composeRule.waitUntil {
            repository.settings.value.voiceSetup.effectiveLanguageModel == VoiceLanguageModel.NEEDLE_WHISTLE
        }
        hint.assertDoesNotExist()
    }

    @Test
    fun hintsAndDisplaySwitchLabelsAlignWithFieldLabelsAndTheLastSettingClearsTheNavigationBar() {
        val repository = SettingsRepository(MemoryPreferences())
        showSettings(repository) { false }
        val navigationBar = 48
        composeRule.runOnUiThread {
            val content = composeRule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBar))
                    .build()
            ViewCompat.dispatchApplyWindowInsets(content, insets)
        }
        val field =
            composeRule.onNodeWithText("Voice language", useUnmergedTree = true).performScrollTo().fetchSemanticsNode()
        val reasoning =
            composeRule
                .onNodeWithText("Show assistant reasoning", useUnmergedTree = true)
                .performScrollTo()
                .fetchSemanticsNode()
        assertEquals(field.positionInRoot.x, reasoning.positionInRoot.x, 0.5f)
        val hint = composeRule.onNodeWithText("Applies to recognition, speech, and replies.").fetchSemanticsNode()
        assertEquals(field.positionInRoot.x, hint.positionInRoot.x, 0.5f)
        composeRule
            .onNodeWithTag("gomode-voice-language")
            .assertContentDescriptionEquals("Voice language. Applies to recognition, speech, and replies.")
        val screen = composeRule.onNodeWithTag("gomode-settings")
        screen.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, Float.MAX_VALUE) }
        val credits = composeRule.onNodeWithTag("gomode-credits").fetchSemanticsNode()
        val creditsBottom = credits.positionInRoot.y + credits.size.height
        val screenBottom = screen.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "Credits end at $creditsBottom, inside the navigation bar above $screenBottom",
            creditsBottom <= screenBottom - navigationBar,
        )
    }

    private fun initializeTextToSpeech() {
        composeRule.waitUntil { ShadowTextToSpeech.getLastTextToSpeechInstance() != null }
        composeRule.runOnUiThread {
            val engine = checkNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
            shadowOf(engine).onInitListener.onInit(TextToSpeech.SUCCESS)
        }
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
    fun onDeviceLanguageModelsSaveSelectionAndDisableDuringAnActiveSession() {
        val active = mutableStateOf(false)
        val store = MemoryPreferences()
        val repository = SettingsRepository(store)
        showSettings(repository) { active.value }
        val languageModel = composeRule.onNodeWithTag("gomode-language-model")

        languageModel.performScrollTo().performClick()
        composeRule.onNode(hasText("Local") and isHeading()).assertExists()
        composeRule.onNodeWithTag("gomode-language-model-needle-whistle").assertIsNotSelected().performClick()
        composeRule.waitUntil {
            repository.settings.value.voiceSetup.effectiveLanguageModel == VoiceLanguageModel.NEEDLE_WHISTLE
        }
        languageModel.assertTextContains("Whistle + Needle")
        composeRule.onNodeWithTag("gomode-model-download-status").assertDoesNotExist()

        for (model in LocalLlmModel.entries) {
            languageModel.performScrollTo().performClick()
            composeRule.onNodeWithTag("gomode-model-${model.name}").assertTextContains(model.label).performClick()
            composeRule.waitUntil {
                repository.settings.value.let {
                    it.localLlmModel == model && it.voiceSetup.effectiveLanguageModel == VoiceLanguageModel.LOCAL_LLM
                }
            }
            languageModel.assertTextContains(model.label)
            composeRule.onNodeWithTag("gomode-model-download-status").performScrollTo().assertExists()
        }
        languageModel.performScrollTo().performClick()
        composeRule.onNodeWithTag("gomode-model-LFM_3_1_7B_32K").assertIsSelected()
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E2B").assertIsNotSelected().performClick()
        composeRule.waitUntil { repository.settings.value.localLlmModel == LocalLlmModel.GEMMA_4_E2B }
        val reopened = SettingsRepository(store)
        composeRule.waitUntil {
            reopened.settings.value.let {
                it.localLlmModel == LocalLlmModel.GEMMA_4_E2B &&
                    it.voiceSetup.effectiveLanguageModel == VoiceLanguageModel.LOCAL_LLM
            }
        }

        composeRule.runOnIdle { active.value = true }
        languageModel.performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-model-download").performScrollTo().assertIsNotEnabled()
    }

    private fun showSettings(
        repository: SettingsRepository,
        active: () -> Boolean,
    ) {
        composeRule.setContent {
            val settings by repository.settings.collectAsState()
            MaterialTheme {
                SettingsScreen(
                    settings = settings,
                    settingsRepository = repository,
                    edits = rememberSettingsEdits(repository, settings),
                    voiceSessionActive = active(),
                    onDone = {},
                    onOpenHalo = {},
                )
            }
        }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        /** Serializes writes; a test holds it to keep a save in progress. */
        val writes = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            writes.withLock { transform(state.value).also { state.value = it } }
    }
}
