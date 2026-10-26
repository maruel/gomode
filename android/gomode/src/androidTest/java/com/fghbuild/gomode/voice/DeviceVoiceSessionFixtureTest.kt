// Instrumented coverage for a device voice session against the local voice fixture.
package com.fghbuild.gomode.voice

import android.Manifest
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.fghbuild.gomode.VoiceFixture
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real device voice session against the fixture: settings, MCP, the text
 * WebSocket, and the Telecom handover. No seam is stubbed, so a broken URL,
 * header, or frame fails here.
 */
@RunWith(AndroidJUnit4::class)
class DeviceVoiceSessionFixtureTest {
    @get:Rule
    val recordAudioPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    @VoiceFixture
    fun deviceSessionConnects(): Unit =
        runBlocking {
            val baseUrl =
                requireNotNull(InstrumentationRegistry.getArguments().getString("baseUrl")) {
                    "baseUrl instrumentation argument is required"
                }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dataStore =
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                    produceFile = { File(context.filesDir, "voice-fixture-${System.nanoTime()}.preferences_pb") },
                )
            val repository = SettingsRepository(dataStore)
            repository.addService("fixture", baseUrl)
            repository.updateVoiceMode(VoiceMode.DEVICE)

            val speech = AndroidDeviceSpeech(context, "en-US")
            val calls = TelecomCallController(context, onTelecomEnded = {})
            val session =
                DeviceVoiceSession(
                    appContext = context,
                    settingsRepository = repository,
                    languageTag = "en-US",
                    callController = calls,
                    speech = speech,
                )
            try {
                prepareSpeechLanguage(speech)
                session.connect(preserveTranscript = false)
                withTimeout(TIMEOUT_MS) {
                    session.state.first { it.connected || it.error != null }
                }
                assertNull("device session reported ${session.state.value.error}", session.state.value.error)
                assertTrue("device session did not connect", session.state.value.connected)
            } finally {
                session.close()
                calls.close()
            }
        }

    private suspend fun prepareSpeechLanguage(speech: AndroidDeviceSpeech) =
        coroutineScope {
            var language = speech.checkLanguage()
            Log.i("GoModeSpeechFixture", "Language preflight: $language")
            if (language == SpeechLanguageState.Missing || language == SpeechLanguageState.Pending) {
                val download = async(start = CoroutineStart.UNDISPATCHED) { speech.downloadLanguage() }
                // The stock provider requires consent before installing its offline pack.
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                device.wait(Until.findObject(By.text("Download")), 10_000)?.click()
                download.await()
                Log.i("GoModeSpeechFixture", "Download outcome: ${speech.languageState.value}")
                assertTrue(
                    "Speech download failed: ${speech.languageState.value}",
                    speech.languageState.value !is SpeechLanguageState.Failed,
                )
                withTimeout(180_000) {
                    do {
                        language = speech.checkLanguage()
                        if (language != SpeechLanguageState.Ready) delay(1000)
                    } while (language == SpeechLanguageState.Pending || language == SpeechLanguageState.Missing)
                }
            }
            assertTrue(
                "Speech language unavailable: $language",
                language == SpeechLanguageState.Ready || language is SpeechLanguageState.Unverified,
            )
        }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
