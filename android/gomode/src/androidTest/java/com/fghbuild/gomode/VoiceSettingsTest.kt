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
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class VoiceSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

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
