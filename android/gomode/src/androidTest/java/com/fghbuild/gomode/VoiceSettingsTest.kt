// Compose tests for saved voice mode, language, and the active-session settings guard.
package com.fghbuild.gomode

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.ui.settings.SettingsScreen
import com.fghbuild.gomode.voice.ServerVoiceSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class VoiceSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun returningToTheSourceServerCancelsVoiceHandoffBeforeSelectingTheDestinationAgain() {
        val repository = SettingsRepository(MemoryPreferences())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val handoff = ServerVoiceSwitch(repository, scope)
        try {
            val (source, target) =
                runBlocking {
                    val target = repository.addService("Destination", "http://127.0.0.1:2")
                    val source = repository.addService("Source", "http://127.0.0.1:1")
                    withTimeout(10_000) { repository.settings.first { it.activeServiceId == source } }
                    handoff.switchServer(target)
                    withTimeout(10_000) { repository.settings.first { it.activeServiceId == target } }
                    source to target
                }
            composeRule.setContent {
                val settings by repository.settings.collectAsState()
                MaterialTheme {
                    SettingsScreen(
                        settings = settings,
                        settingsRepository = repository,
                        voiceSessionActive = false,
                        onDone = {},
                        onOpenHalo = {},
                        onSelectService = handoff::cancel,
                    )
                }
            }
            composeRule.onNodeWithTag("gomode-service-$source").performScrollTo().performClick()
            composeRule.waitUntil { repository.settings.value.activeServiceId == source }
            composeRule.onNodeWithTag("gomode-service-$target").performScrollTo().performClick()
            composeRule.waitUntil { repository.settings.value.activeServiceId == target }
            composeRule.runOnIdle {
                assertNull(handoff.pending.value)
                assertFalse(handoff.takeReady(repository.settings.value, ready = true))
            }
        } finally {
            scope.cancel()
        }
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
            MaterialTheme {
                SettingsScreen(
                    settings = SettingsState(),
                    settingsRepository = repository,
                    voiceSessionActive = active.value,
                    onDone = {},
                    onOpenHalo = {},
                )
            }
        }
        composeRule.onNodeWithTag("gomode-voice-language").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-save-voice-language").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { active.value = false }
        composeRule
            .onNodeWithTag("gomode-voice-language")
            .performScrollTo()
            .assertIsEnabled()
            .performTextReplacement("fr-CA")
        composeRule.onNodeWithTag("gomode-save-voice-language").performScrollTo().assertIsEnabled()
        composeRule.runOnIdle { active.value = true }
        composeRule.onNodeWithTag("gomode-save-voice-language").assertIsNotEnabled()
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
        composeRule.onNodeWithTag("gomode-engine-remote-gateway").performScrollTo().assertIsSelected()

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
        composeRule.onNodeWithTag("gomode-engine-local-llm").assertIsSelected()
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E2B").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E4B").performScrollTo().performClick()
        composeRule.waitUntil {
            repository.settings.value.localLlmModel == com.fghbuild.gomode.data.LocalLlmModel.GEMMA_4_E4B
        }
        composeRule.onNodeWithTag("gomode-model-LFM_2_5_1_2B").performScrollTo().performClick()
        composeRule.waitUntil {
            repository.settings.value.localLlmModel == com.fghbuild.gomode.data.LocalLlmModel.LFM_2_5_1_2B
        }
        composeRule.onNodeWithTag("gomode-model-LFM_2_5_1_2B").assertIsSelected()
        composeRule.onNodeWithTag("gomode-model-LFM_2_5_2_6B").performScrollTo().performClick()
        composeRule.waitUntil {
            repository.settings.value.localLlmModel == com.fghbuild.gomode.data.LocalLlmModel.LFM_2_5_2_6B
        }
        composeRule.onNodeWithTag("gomode-model-LFM_2_5_2_6B").assertIsSelected()
        composeRule.runOnIdle { active.value = true }
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E2B").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-model-GEMMA_4_E4B").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-model-LFM_2_5_1_2B").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("gomode-model-LFM_2_5_2_6B").performScrollTo().assertIsNotEnabled()
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
