// Exercises native gateway choice loading, retry, transport filtering, and active-session controls.
package com.fghbuild.gomode

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceChoicesSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun gatewayChoicesRetrySaveFilterAndDisableWhileConnected() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString().trimEnd('/')
                val repository = SettingsRepository(MemoryPreferences())
                repository.addService("Choices fixture", base)
                repository.settings.first { it.activeServiceURL == base }
                val active = mutableStateOf(false)
                val manifest =
                    """
                    {"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":1,
                    "toolGroups":[],"voiceGateway":{"required":false,"url":"/"}}}
                    """.trimIndent()
                val catalog =
                    """
                    {"choices":[{"id":"live","label":"Gemini Live","transports":["webrtc"]},
                    {"id":"remote","label":"Remote LLM","transports":["webrtc","text"]},
                    {"id":"local","label":"Local LLM","transports":["text"]}],"defaultChoiceID":"live"}
                    """.trimIndent()
                server.enqueue(MockResponse().setBody(manifest))
                server.enqueue(MockResponse().setResponseCode(503))
                composeRule.setContent {
                    val settings by repository.settings.collectAsState()
                    MaterialTheme {
                        SettingsScreen(settings, repository, active.value, {}, {})
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
                composeRule.onNodeWithTag("gomode-voice-choice-local").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-voice-choice-remote").performClick()
                composeRule.waitUntil { repository.settings.value.voiceChoices[base] == "remote" }
                composeRule.onNodeWithTag("gomode-voice-mode-device").performScrollTo().performClick()
                composeRule.waitUntil { repository.settings.value.voiceMode == VoiceMode.DEVICE }
                composeRule.onNodeWithTag("gomode-voice-choice-live").assertDoesNotExist()
                composeRule.onNodeWithTag("gomode-voice-choice-remote").performScrollTo().assertIsSelected()
                composeRule.onNodeWithTag("gomode-voice-choice-local").performClick()
                composeRule.waitUntil { repository.settings.value.voiceChoices[base] == "local" }
                composeRule.runOnIdle { active.value = true }
                composeRule.onNodeWithTag("gomode-voice-choice-local").assertIsSelected().assertIsNotEnabled()
                composeRule.onNodeWithTag("gomode-voice-choice-remote").assertIsNotEnabled()
                assertEquals(4, server.requestCount)
            }
        }

    private class MemoryPreferences : DataStore<Preferences> {
        private val current = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(current.value)
            current.value = next
            return next
        }
    }
}
