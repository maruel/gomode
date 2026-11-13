// Exercises the thinking-music preview against real audio playback and focus loss.
package com.fghbuild.gomode

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.ui.settings.SettingsScreen
import com.fghbuild.gomode.ui.settings.rememberSettingsEdits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test

// Robolectric has no playback configurations or competing focus owners, so this stays instrumented.
class ThinkingMusicPreviewTest {
    @get:Rule
    @OptIn(ExperimentalTestApi::class)
    val composeRule = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

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

    private class MemoryPreferences : DataStore<Preferences> {
        private val writeMutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            writeMutex.withLock { transform(state.value).also { state.value = it } }
    }
}
