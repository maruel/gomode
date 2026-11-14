// Exercises the gateway voice setup: discovery, each stage combination, the on-device constraint, and errors.
package com.fghbuild.gomode

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.data.GatewayVoiceSetup
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.SpeechStage
import com.fghbuild.gomode.data.SpeechStageLocation.DEVICE
import com.fghbuild.gomode.data.SpeechStageLocation.GATEWAY
import com.fghbuild.gomode.data.VoiceLanguageModel
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.data.VoiceSetupPreference
import com.fghbuild.gomode.ui.settings.SettingsScreen
import com.fghbuild.gomode.ui.settings.rememberSettingsEdits
import com.fghbuild.gomode.voice.GatewayVoiceChoices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceChoicesSettingsTest {
    @get:Rule
    @OptIn(ExperimentalTestApi::class)
    val composeRule = createComposeRule(effectContext = StandardTestDispatcher())

    private val manifest =
        """
        {"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":3,
        "toolGroups":[],"voiceGateway":{"required":false,"url":"/"}}}
        """.trimIndent()

    // Two native audio models beside two composable language models with every stage ownership.
    private val catalog =
        """
        {"choices":[
        {"id":"live","label":"Gemini Live","transports":["webrtc"],"voices":["Kore","Orus"]},
        {"id":"mini","label":"Realtime Mini","transports":["webrtc"]},
        {"id":"llm","label":"Gateway LLM","transports":["text","webrtc"],"composable":true,"speechModes":[
        {"input":"client","output":"client"},{"input":"client","output":"gateway"},
        {"input":"gateway","output":"client"},{"input":"gateway","output":"gateway"}]},
        {"id":"other","label":"Other LLM","transports":["text","webrtc"],"composable":true,"speechModes":[
        {"input":"client","output":"client"},{"input":"client","output":"gateway"},
        {"input":"gateway","output":"client"},{"input":"gateway","output":"gateway"}]}],
        "defaults":[{"transport":"text","id":"llm"},{"transport":"webrtc","id":"live"}],
        "recognitionModels":[{"id":"whisper","label":"Whisper"},{"id":"parakeet","label":"Parakeet"}],
        "synthesisModels":[{"id":"kokoro","label":"Kokoro"},{"id":"piper","label":"Piper"}]}
        """.trimIndent()

    private val gatewayStages =
        VoiceSetupPreference(mode = VoiceSetupMode.SEPARATE_STAGES, recognition = GATEWAY, speech = GATEWAY)

    @Test
    fun followingTheGatewayShowsTheResolvedSetupUntilAnExplicitPick() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString().trimEnd('/')
                val store = MemoryPreferences()
                val repository = SettingsRepository(store)
                repository.addService("Choices fixture", base)
                repository.settings.first { it.activeServiceURL == base }
                val active = mutableStateOf(false)
                server.enqueue(MockResponse().setBody(manifest))
                server.enqueue(MockResponse().setResponseCode(503))
                show(repository) { active.value }
                waitForTag("gomode-voice-choices-retry")
                // A followed setup is unknown without the catalog: neither option is selected or selectable.
                for (tag in listOf("gomode-voice-setup-all-in-one", "gomode-voice-setup-separate-stages")) {
                    composeRule.onNodeWithTag(tag).assertIsNotSelected().assertIsNotEnabled()
                }
                composeRule.onNodeWithTag("gomode-language-model").assertDoesNotExist()
                server.enqueue(MockResponse().setBody(manifest))
                server.enqueue(MockResponse().setBody(catalog))
                composeRule.onNodeWithTag("gomode-voice-choices-retry").performScrollTo().performClick()
                waitForTag("gomode-voice-model")

                // The gateway's WebRTC default is a native audio model, so the setup shows as all-in-one.
                composeRule.onNodeWithTag("gomode-voice-setup-all-in-one").assertIsSelected()
                composeRule.onNodeWithTag("gomode-voice-model").assertTextContains("Gemini Live")
                composeRule.onNodeWithTag("gomode-recognition").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-device-speech-rate").assertDoesNotExist()
                assertEquals(VoiceSetupMode.FOLLOW_GATEWAY, repository.settings.value.voiceSetup.mode)

                // The speaking voice marks its current value and reports a failed save under itself.
                // Saving one pins the setup to the model it belongs to.
                val voice = composeRule.onNodeWithTag("gomode-speaking-voice")
                voice.performScrollTo().assertTextContains("Provider default").performClick()
                composeRule.onNodeWithTag("gomode-speaking-voice-default").assertIsSelected()
                composeRule.runOnIdle { store.failWrites = true }
                composeRule.onNodeWithTag("gomode-speaking-voice-Kore").assertIsNotSelected().performClick()
                waitForTag("gomode-speaking-voice-save-error")
                voice.assertTextContains("Provider default")
                assertEquals(VoiceSetupMode.FOLLOW_GATEWAY, repository.settings.value.voiceSetup.mode)
                composeRule.runOnIdle { store.failWrites = false }
                voice.performClick()
                composeRule.onNodeWithTag("gomode-speaking-voice-Kore").performClick()
                composeRule.waitUntil {
                    repository.settings.value.speakingVoices[base]
                        ?.get("live") == "Kore"
                }
                assertEquals(VoiceSetupMode.ALL_IN_ONE, repository.settings.value.voiceSetup.mode)
                assertEquals(GatewayVoiceSetup(allInOneChoiceID = "live"), repository.settings.value.gateway(base))
                composeRule.onNodeWithTag("gomode-speaking-voice-save-error").assertDoesNotExist()
                voice.assertTextContains("Kore").performClick()
                composeRule.onNodeWithTag("gomode-speaking-voice-Kore").assertIsSelected().performClick()
                // After a save, reselecting the current voice closes the menu.
                composeRule.onNodeWithTag("gomode-speaking-voices").assertDoesNotExist()

                // All-in-one lists only native audio models.
                composeRule.onNodeWithTag("gomode-voice-model").performScrollTo().performClick()
                composeRule.onNodeWithTag("gomode-voice-choice-live").assertIsSelected()
                composeRule.onNodeWithTag("gomode-voice-choice-llm").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-voice-choice-mini").performClick()
                composeRule.waitUntil {
                    repository.settings.value.gateway(base) == GatewayVoiceSetup(allInOneChoiceID = "mini")
                }
                composeRule.onNodeWithTag("gomode-voice-model").assertTextContains("Realtime Mini")
                composeRule.onNodeWithTag("gomode-speaking-voice").assertDoesNotExist()

                composeRule.runOnIdle { active.value = true }
                composeRule.onNodeWithTag("gomode-voice-setup-all-in-one").assertIsSelected().assertIsNotEnabled()
                composeRule.onNodeWithTag("gomode-voice-setup-separate-stages").assertIsNotEnabled()
                composeRule.onNodeWithTag("gomode-voice-model").assertIsNotEnabled()
                assertEquals(4, server.requestCount)
            }
        }

    @Test
    fun separateStagesComposeEveryStageOwnerAndSwitchingRestoresTheOtherMode() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString().trimEnd('/')
                val store = MemoryPreferences()
                val repository = SettingsRepository(store)
                repository.addService("Choices fixture", base)
                repository.settings.first { it.activeServiceURL == base }
                server.enqueue(MockResponse().setBody(manifest))
                server.enqueue(MockResponse().setBody(catalog))
                show(repository)
                waitForTag("gomode-voice-model")

                // Leaving a followed all-in-one setup keeps its model for a switch back.
                composeRule.onNodeWithTag("gomode-voice-setup-separate-stages").performClick()
                composeRule.waitUntil { repository.settings.value.voiceSetup.mode == VoiceSetupMode.SEPARATE_STAGES }
                assertEquals(
                    "live",
                    repository.settings.value
                        .gateway(base)
                        .allInOneChoiceID,
                )

                // Device recognition and speech: the gateway's text default runs the language model.
                stage("gomode-recognition").assertTextContains("Local")
                stage("gomode-language-model").assertTextContains("Gateway LLM")
                stage("gomode-speech").assertTextContains("Local")
                stage("gomode-device-speech-rate").assertTextContains("1× (Normal)")
                composeRule.onNodeWithTag("gomode-speaking-voice").assertDoesNotExist()

                // Gateway recognition with device speech.
                pick("gomode-recognition", "gomode-recognition-model-parakeet")
                composeRule.waitUntil { repository.settings.value.voiceSetup.recognition == GATEWAY }
                assertEquals(
                    "parakeet",
                    repository.settings.value
                        .gateway(base)
                        .recognitionModelID,
                )
                stage("gomode-recognition").assertTextContains("Parakeet")
                stage("gomode-device-speech-rate").assertIsEnabled()

                // Gateway recognition and speech.
                pick("gomode-speech", "gomode-speech-model-piper")
                composeRule.waitUntil { repository.settings.value.voiceSetup.speech == GATEWAY }
                assertEquals(
                    "piper",
                    repository.settings.value
                        .gateway(base)
                        .synthesisModelID,
                )
                stage("gomode-speech").assertTextContains("Piper")
                composeRule.onNodeWithTag("gomode-device-speech-rate").assertDoesNotExist()

                // Device recognition with gateway speech.
                stage("gomode-recognition").performClick()
                composeRule.onNodeWithTag("gomode-recognition-model-parakeet").assertIsSelected()
                // A held write keeps the pickers disabled while it saves, as a slow disk does.
                store.writes.lock()
                composeRule.onNodeWithTag("gomode-recognition-device").assertIsNotSelected().performClick()
                stage("gomode-recognition").assertIsNotEnabled()
                store.writes.unlock()
                composeRule.waitUntil { repository.settings.value.voiceSetup.recognition == DEVICE }
                stage("gomode-recognition").assertTextContains("Local").performClick()
                // After a save, reselecting the current stage closes the menu.
                composeRule.onNodeWithTag("gomode-recognition-device").assertIsSelected().performClick()
                composeRule.onNodeWithTag("gomode-recognition-options").assertDoesNotExist()
                stage("gomode-speech").assertTextContains("Piper")

                pick("gomode-language-model", "gomode-voice-choice-other")
                composeRule.waitUntil {
                    repository.settings.value
                        .gateway(base)
                        .separateStagesChoiceID == "other"
                }
                stage("gomode-language-model").assertTextContains("Other LLM")
                val separate = repository.settings.value.voiceSetup

                composeRule.onNodeWithTag("gomode-voice-setup-all-in-one").performScrollTo().performClick()
                composeRule.waitUntil { repository.settings.value.voiceSetup.mode == VoiceSetupMode.ALL_IN_ONE }
                stage("gomode-voice-model").assertTextContains("Gemini Live")
                composeRule.onNodeWithTag("gomode-language-model").assertDoesNotExist()

                composeRule.onNodeWithTag("gomode-voice-setup-separate-stages").performScrollTo().performClick()
                composeRule.waitUntil { repository.settings.value.voiceSetup.mode == VoiceSetupMode.SEPARATE_STAGES }
                assertEquals(separate, repository.settings.value.voiceSetup)
                stage("gomode-recognition").assertTextContains("Local")
                stage("gomode-language-model").assertTextContains("Other LLM")
                stage("gomode-speech").assertTextContains("Piper")
                assertEquals(
                    GatewayVoiceSetup(
                        allInOneChoiceID = "live",
                        separateStagesChoiceID = "other",
                        recognitionModelID = "parakeet",
                        synthesisModelID = "piper",
                    ),
                    repository.settings.value.gateway(base),
                )
            }
        }

    @Test
    fun onDeviceLanguageModelsRequireDeviceStagesAndKeepGatewaySelections() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString().trimEnd('/')
                val repository = SettingsRepository(MemoryPreferences())
                repository.addService("Choices fixture", base)
                repository.settings.first { it.activeServiceURL == base }
                repository.updateSpeechModel(base, SpeechStage.SYNTHESIS, "piper") {
                    it.copy(preference = gatewayStages)
                }
                server.enqueue(MockResponse().setBody(manifest))
                server.enqueue(MockResponse().setBody(catalog))
                show(repository)
                waitForTag("gomode-recognition")
                stage("gomode-recognition").assertTextContains("Whisper")
                stage("gomode-speech").assertTextContains("Piper")
                composeRule.onNodeWithTag("gomode-model-download-status").assertDoesNotExist()

                stage("gomode-language-model").performClick()
                composeRule.onNodeWithTag("gomode-voice-choice-llm").assertIsSelected()
                composeRule.onNodeWithTag("gomode-voice-choice-live").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-model-GEMMA_4_E4B").assertIsNotSelected().performClick()
                composeRule.waitUntil { repository.settings.value.localLlmModel == LocalLlmModel.GEMMA_4_E4B }
                val local = repository.settings.value.voiceSetup
                assertEquals(VoiceLanguageModel.LOCAL_LLM, local.languageModel)
                // The saved gateway stages stay for a later gateway language model.
                assertEquals(GATEWAY, local.recognition)
                assertEquals(GATEWAY, local.speech)

                stage("gomode-language-model").assertTextContains("Gemma 4 E4B")
                stage("gomode-model-download-status").assertExists()
                stage("gomode-recognition").assertTextContains("Local").performClick()
                composeRule.onNodeWithTag("gomode-recognition-device").assertIsSelected()
                composeRule.onNodeWithTag("gomode-recognition-model-whisper").assertIsNotEnabled()
                composeRule.onNodeWithTag("gomode-recognition-device").performClick()
                stage("gomode-speech").assertTextContains("Local").performClick()
                composeRule.onNodeWithTag("gomode-speech-model-piper").assertIsNotEnabled()
                composeRule.onNodeWithTag("gomode-speech-device").performClick()
                stage("gomode-device-speech-rate").assertIsEnabled()
                assertEquals(local, repository.settings.value.voiceSetup)

                pick("gomode-language-model", "gomode-language-model-needle-whistle")
                composeRule.waitUntil {
                    repository.settings.value.voiceSetup.languageModel == VoiceLanguageModel.NEEDLE_WHISTLE
                }
                stage("gomode-language-model").assertTextContains("Whistle + Needle")
                composeRule.onNodeWithTag("gomode-model-download-status").assertDoesNotExist()

                pick("gomode-language-model", "gomode-voice-choice-llm")
                composeRule.waitUntil {
                    repository.settings.value.voiceSetup.languageModel == VoiceLanguageModel.GATEWAY
                }
                stage("gomode-recognition").assertTextContains("Whisper")
                stage("gomode-speech").assertTextContains("Piper")
            }
        }

    @Test
    fun aWithdrawnFollowedChoiceShowsTheDefaultTheSessionUses() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString().trimEnd('/')
                val repository = SettingsRepository(MemoryPreferences())
                repository.addService("Choices fixture", base)
                repository.settings.first { it.activeServiceURL == base }
                val withdrawn = GatewayVoiceSetup(followedChoiceID = "gone")
                repository.updateSavedVoiceSetup(base) { it.copy(gateway = withdrawn) }
                val gateway = GatewayVoiceChoices(base, Json { ignoreUnknownKeys = true }.decodeFromString(catalog))
                assertEquals("live", gateway.resolveSetup(repository.settings.value).choice.id)
                server.enqueue(MockResponse().setBody(manifest))
                server.enqueue(MockResponse().setBody(catalog))
                show(repository)
                waitForTag("gomode-voice-model")

                // Settings shows the WebRTC default a session uses, without an error, and saves nothing.
                composeRule.onNodeWithTag("gomode-voice-setup-all-in-one").assertIsSelected()
                stage("gomode-voice-model").assertTextContains("Gemini Live")
                composeRule.onNodeWithTag("gomode-voice-model-error").assertDoesNotExist()
                assertEquals("gone", gateway.savedSetup(repository.settings.value).followedChoiceID)
                pick("gomode-voice-model", "gomode-voice-choice-mini")
                composeRule.waitUntil { gateway.resolveSetup(repository.settings.value).choice.id == "mini" }
                assertEquals(VoiceSetupMode.ALL_IN_ONE, repository.settings.value.voiceSetup.mode)
            }
        }

    @Test
    fun errorsAppearUnderTheControlThatOwnsThem() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString().trimEnd('/')
                val store = MemoryPreferences()
                val repository = SettingsRepository(store)
                repository.addService("Choices fixture", base)
                repository.settings.first { it.activeServiceURL == base }
                // An on-device language model keeps its pickers when discovery fails.
                repository.updateSavedVoiceSetup(null) {
                    it.copy(preference = gatewayStages.withLanguageModel(VoiceLanguageModel.NEEDLE_WHISTLE))
                }
                server.enqueue(MockResponse().setBody(manifest))
                server.enqueue(MockResponse().setResponseCode(503))
                show(repository)
                waitForTag("gomode-voice-choices-retry")
                stage("gomode-language-model").assertTextContains("Whistle + Needle")
                stage("gomode-recognition").assertTextContains("Local")

                // A gateway language model needs the catalog, so the failure replaces the pickers.
                repository.updateSavedVoiceSetup(null) {
                    it.copy(preference = it.preference.withLanguageModel(VoiceLanguageModel.GATEWAY))
                }
                composeRule.waitUntil {
                    composeRule.onAllNodes(hasTestTag("gomode-language-model")).fetchSemanticsNodes().isEmpty()
                }
                server.enqueue(MockResponse().setBody(manifest))
                server.enqueue(MockResponse().setBody(catalog))
                composeRule.onNodeWithTag("gomode-voice-choices-retry").performScrollTo().performClick()
                waitForTag("gomode-language-model")

                // Withdrawn selections show the defaults sessions use, without an error.
                pick("gomode-speech", "gomode-speech-model-piper")
                stage("gomode-speech").assertTextContains("Piper")
                pick("gomode-language-model", "gomode-voice-choice-other")
                stage("gomode-language-model").assertTextContains("Other LLM")
                repository.updateSpeechModel(base, SpeechStage.SYNTHESIS, "removed") { it }
                repository.updateVoiceChoice(base, "gone") { it }
                composeRule.waitUntil {
                    repository.settings.value.gatewayVoiceSetups.values.any {
                        it.synthesisModelID == "removed" && it.separateStagesChoiceID == "gone"
                    }
                }
                stage("gomode-speech").assertTextContains("Kokoro")
                stage("gomode-language-model").assertTextContains("Gateway LLM")
                listOf("gomode-speech-error", "gomode-language-model-error", "gomode-recognition-error").forEach {
                    composeRule.onNodeWithTag(it).assertDoesNotExist()
                }

                // A failed save stays under its control until a later save succeeds.
                composeRule.runOnIdle { store.failWrites = true }
                pick("gomode-recognition", "gomode-recognition-model-parakeet")
                waitForTag("gomode-recognition-error")
                stage("gomode-recognition-error").assertTextContains("Unable to save recognition", substring = true)
                composeRule.onNodeWithTag("gomode-speech-error").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-voice-setup-all-in-one").performScrollTo().performClick()
                waitForTag("gomode-voice-setup-error")
                composeRule.onNodeWithTag("gomode-recognition-error").assertDoesNotExist()
                composeRule.runOnIdle { store.failWrites = false }
                composeRule.onNodeWithTag("gomode-voice-setup-all-in-one").performClick()
                composeRule.waitUntil { repository.settings.value.voiceSetup.mode == VoiceSetupMode.ALL_IN_ONE }
                composeRule.onNodeWithTag("gomode-voice-setup-error").assertDoesNotExist()
            }
        }

    private fun show(
        repository: SettingsRepository,
        active: () -> Boolean = { false },
    ) {
        composeRule.setContent {
            val settings by repository.settings.collectAsState()
            MaterialTheme {
                SettingsScreen(
                    settings,
                    repository,
                    active(),
                    {},
                    {},
                    edits = rememberSettingsEdits(repository, settings),
                )
            }
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun stage(tag: String): SemanticsNodeInteraction = composeRule.onNodeWithTag(tag).performScrollTo()

    private fun pick(
        field: String,
        item: String,
    ) {
        stage(field).performClick()
        // Long menus scroll within their popup.
        composeRule.onNodeWithTag(item).performScrollTo().performClick()
    }

    private fun SettingsState.gateway(base: String) = gatewayVoiceSetups[base] ?: GatewayVoiceSetup()

    private class MemoryPreferences : DataStore<Preferences> {
        var failWrites = false

        /** Serializes writes; a test holds it to keep a save in progress. */
        val writes = Mutex()
        private val current = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            writes.withLock {
                if (failWrites) throw java.io.IOException("Fixture write failure")
                transform(current.value).also { current.value = it }
            }
    }
}
