// Exercises native gateway choice loading, retry, transport filtering, and active-session controls.
package com.fghbuild.gomode

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.data.VoiceSetupPreference
import com.fghbuild.gomode.ui.settings.SettingsScreen
import com.fghbuild.gomode.ui.settings.rememberSettingsEdits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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

    @Test
    fun gatewayChoicesRetrySaveFilterAndDisableWhileConnected() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString().trimEnd('/')
                val store = MemoryPreferences()
                val repository = SettingsRepository(store)
                repository.addService("Choices fixture", base)
                repository.settings.first { it.activeServiceURL == base }
                val active = mutableStateOf(false)
                val manifest =
                    """
                    {"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":3,
                    "toolGroups":[],"voiceGateway":{"required":false,"url":"/"}}}
                    """.trimIndent()
                val catalog =
                    """
                    {"choices":[{"id":"live","label":"Gemini Live","transports":["webrtc"],
                    "voices":["Kore","Orus"]},
                    {"id":"remote","label":"Remote LLM","transports":["webrtc","text"]},
                    {"id":"local","label":"Local LLM","transports":["text"]}],
                    "defaults":[{"transport":"text","id":"remote"},{"transport":"webrtc","id":"live"}]}
                    """.trimIndent()
                server.enqueue(MockResponse().setBody(manifest))
                server.enqueue(MockResponse().setResponseCode(503))
                composeRule.setContent {
                    val settings by repository.settings.collectAsState()
                    MaterialTheme {
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
                composeRule.waitUntil(10_000) {
                    composeRule
                        .onAllNodes(
                            androidx.compose.ui.test
                                .hasTestTag("gomode-voice-choices-retry"),
                        ).fetchSemanticsNodes()
                        .isNotEmpty()
                }
                server.enqueue(MockResponse().setBody(manifest))
                server.enqueue(MockResponse().setBody(catalog))
                composeRule.onNodeWithTag("gomode-voice-choices-retry").performScrollTo().performClick()
                composeRule.waitUntil(10_000) {
                    composeRule
                        .onAllNodes(
                            androidx.compose.ui.test
                                .hasTestTag("gomode-voice-choice-live"),
                        ).fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithTag("gomode-voice-choice-live").performScrollTo().assertIsSelected()
                composeRule.onNodeWithText("Model").assertExists()
                composeRule.onNodeWithTag("gomode-voice-choice-local").assertDoesNotExist()
                composeRule
                    .onNodeWithTag(
                        "gomode-speaking-voice",
                    ).performScrollTo()
                    .assertTextContains("Provider default")
                composeRule.runOnIdle { store.failWrites = true }
                composeRule.onNodeWithTag("gomode-speaking-voice").performClick()
                composeRule.onNodeWithTag("gomode-speaking-voice-Kore").performClick()
                composeRule.waitUntil {
                    composeRule
                        .onAllNodes(hasTestTag("gomode-speaking-voice-save-error"))
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithTag("gomode-speaking-voice").assertTextContains("Provider default")
                composeRule.runOnIdle { store.failWrites = false }
                composeRule.onNodeWithTag("gomode-speaking-voice").performClick()
                composeRule.onNodeWithTag("gomode-speaking-voice-Kore").performClick()
                composeRule.waitUntil {
                    repository.settings.value.speakingVoices[base]
                        ?.get("live") == "Kore"
                }
                composeRule.onNodeWithTag("gomode-speaking-voice-save-error").assertDoesNotExist()
                composeRule.runOnIdle { active.value = true }
                composeRule.onNodeWithTag("gomode-speaking-voice").assertTextContains("Kore").assertIsNotEnabled()
                composeRule.runOnIdle { active.value = false }
                // Gateway speech follows the picked choice, a native model or a language model.
                composeRule.onNodeWithTag("gomode-voice-choice-remote").performScrollTo().performClick()
                composeRule.waitUntil { repository.settings.value.followsGateway(base, "remote") }
                composeRule.onNodeWithTag("gomode-speaking-voice").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-voice-choice-live").performClick()
                composeRule.waitUntil { repository.settings.value.followsGateway(base, "live") }
                composeRule.onNodeWithTag("gomode-speaking-voice").performScrollTo().assertTextContains("Kore")
                composeRule.onNodeWithTag("gomode-voice-choice-remote").performScrollTo().performClick()
                composeRule.waitUntil { repository.settings.value.followsGateway(base, "remote") }
                composeRule.onNodeWithTag("gomode-voice-mode-device").performScrollTo().performClick()
                composeRule.waitUntil { repository.settings.value.voiceSetup.deviceSpeech }
                composeRule.onNodeWithTag("gomode-voice-choice-live").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-voice-choice-remote").performScrollTo().assertIsSelected()
                composeRule.onNodeWithTag("gomode-voice-choice-local").performClick()
                composeRule.waitUntil {
                    repository.settings.value.let {
                        it.voiceSetup.mode == VoiceSetupMode.SEPARATE_STAGES &&
                            it.gatewayVoiceSetups[base]?.separateStagesChoiceID == "local"
                    }
                }
                composeRule.runOnIdle { active.value = true }
                composeRule.onNodeWithTag("gomode-voice-choice-local").assertIsSelected().assertIsNotEnabled()
                composeRule.onNodeWithTag("gomode-voice-choice-remote").assertIsNotEnabled()
                composeRule.runOnIdle { active.value = false }
                composeRule.onNodeWithTag("gomode-voice-mode-cloud").performScrollTo().performClick()
                composeRule.waitUntil { repository.settings.value.voiceSetup.mode == VoiceSetupMode.FOLLOW_GATEWAY }
                // Gateway speech restores the language model picked under it; device speech keeps its own.
                composeRule.onNodeWithTag("gomode-voice-choice-remote").performScrollTo().assertIsSelected()
                composeRule.onNodeWithTag("gomode-voice-choice-local").assertDoesNotExist()
                assertEquals(
                    "local",
                    repository.settings.value.gatewayVoiceSetups[base]
                        ?.separateStagesChoiceID,
                )
                composeRule.onNodeWithTag("gomode-voice-choice-live").performScrollTo().performClick()
                composeRule.waitUntil { repository.settings.value.followsGateway(base, "live") }
                composeRule.onNodeWithTag("gomode-speaking-voice").performScrollTo().performClick()
                composeRule.onNodeWithTag("gomode-speaking-voice-default").performClick()
                composeRule.waitUntil {
                    repository.settings.value.speakingVoices[base]
                        ?.get("live") == ""
                }
                composeRule.onNodeWithTag("gomode-speaking-voice").assertTextContains("Provider default")
                assertEquals(4, server.requestCount)
            }
        }

    @Test
    fun singleCompatibleModelIsHiddenButSpeakingVoiceAndStaleSelectionRecoveryRemainAvailable() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString().trimEnd('/')
                val repository = SettingsRepository(MemoryPreferences())
                repository.addService("Single model fixture", base)
                repository.settings.first { it.activeServiceURL == base }
                server.enqueue(
                    MockResponse().setBody(
                        """
                        {"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":3,
                        "toolGroups":[],"voiceGateway":{"required":false,"url":"/"}}}
                        """.trimIndent(),
                    ),
                )
                server.enqueue(
                    MockResponse().setBody(
                        """
                        {"choices":[{"id":"live","label":"Gemini Live","transports":["webrtc"],
                        "voices":["Kore","Orus"]},
                        {"id":"local","label":"Local LLM","transports":["text"]}],
                        "defaults":[{"transport":"text","id":"local"},{"transport":"webrtc","id":"live"}]}
                        """.trimIndent(),
                    ),
                )
                composeRule.setContent {
                    val settings by repository.settings.collectAsState()
                    MaterialTheme {
                        SettingsScreen(
                            settings,
                            repository,
                            false,
                            {},
                            {},
                            edits = rememberSettingsEdits(repository, settings),
                        )
                    }
                }
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodes(hasTestTag("gomode-speaking-voice")).fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithText("Model").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-voice-choice-live").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-voice-choice-local").assertDoesNotExist()
                composeRule
                    .onNodeWithTag(
                        "gomode-speaking-voice",
                    ).performScrollTo()
                    .assertTextContains("Provider default")
                composeRule.onNodeWithTag("gomode-speaking-voice").performClick()
                composeRule.onNodeWithTag("gomode-speaking-voice-Kore").performClick()
                composeRule.waitUntil {
                    repository.settings.value.speakingVoices[base]
                        ?.get("live") == "Kore"
                }

                composeRule.onNodeWithTag("gomode-voice-mode-device").performScrollTo().performClick()
                composeRule.waitUntil { repository.settings.value.voiceSetup.deviceSpeech }
                composeRule.onNodeWithText("Engine").assertExists()
                composeRule.onNodeWithTag("gomode-engine-remote-gateway").assertTextContains("Remote LLM")
                composeRule.onNodeWithText("LLM").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-voice-choice-local").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-speaking-voice").assertDoesNotExist()

                repository.updateVoiceChoice(base, "missing", VoiceSetupPreference::withDeviceSpeech)
                composeRule.waitUntil {
                    composeRule.onAllNodes(hasTestTag("gomode-voice-choice-error")).fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("gomode-voice-choice-local").performScrollTo().performClick()
                composeRule.waitUntil {
                    repository.settings.value.let {
                        it.voiceSetup.mode == VoiceSetupMode.SEPARATE_STAGES &&
                            it.gatewayVoiceSetups[base]?.separateStagesChoiceID == "local"
                    }
                }
                composeRule.onNodeWithText("LLM").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-voice-choice-error").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-voice-choice-local").assertDoesNotExist()
                assertEquals(2, server.requestCount)
            }
        }

    private fun SettingsState.followsGateway(
        gateway: String,
        choiceID: String,
    ): Boolean =
        voiceSetup.mode == VoiceSetupMode.FOLLOW_GATEWAY && gatewayVoiceSetups[gateway]?.allInOneChoiceID == choiceID

    private class MemoryPreferences : DataStore<Preferences> {
        var failWrites = false
        private val writeMutex = Mutex()
        private val current = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            writeMutex.withLock {
                if (failWrites) throw java.io.IOException("Fixture write failure")
                transform(current.value).also { current.value = it }
            }
    }
}
