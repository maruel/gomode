// Exercises WebRTC sessions whose recognition and speech run on different sides against the controlled gateway.
package com.fghbuild.gomode.voice

import android.Manifest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fghbuild.gomode.MainActivity
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SpeechStageLocation
import com.fghbuild.gomode.data.VoiceLanguageModel
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.voicegateway.sdk.v2.TurnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

// Device stages use a scripted DeviceSpeech: the model-free platform recognizer only reports silence.
@RunWith(AndroidJUnit4::class)
class MixedSpeechSessionTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun gatewayRecognitionSpeaksAssistantTextOnTheDeviceAndStopsOnBargeIn() =
        // Held playback keeps the reply audible after generation ends.
        withSession(SpeechStageLocation.GATEWAY, SpeechStageLocation.DEVICE, holdPlayback = true) { session, speech ->
            awaitState(session) { it.speaking && it.turnState == TurnState.Idle }
            assertEquals(
                listOf(
                    TranscriptSpeaker.USER to "How is the service doing?",
                    TranscriptSpeaker.ASSISTANT to "Your service is ready.",
                ),
                session.state.value.transcript
                    .map { it.speaker to it.text },
            )
            assertTrue(
                session.state.value.transcript
                    .all { it.final },
            )
            assertEquals(listOf("Your service is ready."), speech.spoken)
            val setup = setupFrame()
            assertEquals("""{"input":"gateway","output":"client"}""", setup.getJSONObject("speech").toString())
            assertEquals("fixture-asr", setup.getString("recognitionModelID"))
            assertFalse(setup.has("synthesisModelID"))
            assertNull("Gateway recognition must not start device recognition", speech.listening.value)

            fixtureRequest("/fixture/interrupt", "POST")
            val interrupted = awaitState(session) { !it.speaking }
            assertEquals(TurnState.Idle, interrupted.turnState)
            assertTrue("Barge-in did not stop device playback", speech.stops > 0)
            assertNull(interrupted.error)
        }

    @Test
    fun deviceRecognitionSendsUserTextAndCancelsGatewaySpeechOnBargeIn() =
        withSession(SpeechStageLocation.DEVICE, SpeechStageLocation.GATEWAY) { session, speech ->
            val micFrames = fixtureStats().getInt("micFrames")
            val messages = fixtureStats().getJSONArray("rtcUserMessages").length()
            val cancels = fixtureStats().getInt("rtcTurnCancels")
            val setup = setupFrame()
            assertEquals("""{"input":"client","output":"gateway"}""", setup.getJSONObject("speech").toString())
            assertEquals("fixture-tts", setup.getString("synthesisModelID"))
            assertFalse(setup.has("recognitionModelID"))

            var listener = awaitListener(speech, null)
            withContext(Dispatchers.Main) { listener.onPartial("Status") }
            awaitState(session) { state -> state.transcript.any { !it.final && it.text == "Status" } }
            withContext(Dispatchers.Main) { listener.onResult("Status please") }
            val replied =
                awaitState(session) {
                    it.turnState == TurnState.Idle && !it.speaking &&
                        it.transcript.lastOrNull()?.text == "Your service is ready."
                }
            assertEquals(
                listOf(
                    TranscriptSpeaker.USER to "Status please",
                    TranscriptSpeaker.ASSISTANT to "Your service is ready.",
                ),
                replied.transcript.map { it.speaker to it.text },
            )
            assertEquals("Status please", fixtureStats().getJSONArray("rtcUserMessages").getString(messages))

            // Gateway speech keeps device recognition open, so user speech cancels the reply.
            listener = awaitListener(speech, listener)
            withContext(Dispatchers.Main) { listener.onResult("hold") }
            awaitState(session) { it.speaking && it.listening }
            listener = awaitListener(speech, listener)
            withContext(Dispatchers.Main) { listener.onPartial("wait") }
            val cancelled = awaitState(session) { !it.speaking && it.turnState == TurnState.Idle }
            assertNull(cancelled.error)
            awaitFixture { it.getInt("rtcTurnCancels") == cancels + 1 }
            withContext(Dispatchers.Main) { listener.onResult("wait") }
            val resumed =
                awaitState(session) {
                    it.turnState == TurnState.Idle && !it.speaking && it.transcript.lastOrNull()?.text ==
                        "Your service is ready." && it.transcript.any { entry -> entry.text == "wait" }
                }
            assertEquals(
                listOf("Status please", "hold", "wait"),
                fixtureStats().getJSONArray("rtcUserMessages").let { all ->
                    (messages until all.length()).map { all.getString(it) }
                },
            )
            assertTrue(resumed.transcript.all { it.final })
            assertTrue("Gateway speech must not use device speech", speech.spoken.isEmpty())
            val streamed = fixtureStats().getInt("micFrames") - micFrames
            assertEquals("Device recognition must not stream the microphone", 0, streamed)
        }

    @Test
    fun recoveryResendsTheMixedSetupAndKeepsSpeakingOnTheDevice() =
        withSession(SpeechStageLocation.GATEWAY, SpeechStageLocation.DEVICE) { session, speech ->
            awaitState(session) { it.turnState == TurnState.Idle && it.transcript.size == 2 }
            assertEquals(listOf("Your service is ready."), speech.spoken)
            val setups = fixtureStats().getInt("rtcSetups")
            val first = setupFrame()

            // The fixture drops the peer as a lost gateway does; ICE loss starts recovery.
            fixtureRequest("/fixture/drop", "POST")
            awaitState(session) { !it.connected }
            awaitFixture { it.getInt("rtcSetups") == setups + 1 }
            awaitState(session) { it.connected }
            val recovered = setupFrame()
            for (key in listOf("speech", "recognitionModelID")) {
                assertEquals(key, first.get(key).toString(), recovered.get(key).toString())
            }
            assertFalse(recovered.has("synthesisModelID"))
            withTimeout(TIMEOUT_MS) {
                while (speech.spoken.size < 2) delay(50)
            }
            assertEquals(listOf("Your service is ready.", "Your service is ready."), speech.spoken)
            assertEquals("Recovery must keep the session's device speech", 1, speech.creations)
            assertNull("Gateway recognition must not start device recognition", speech.listening.value)
        }

    @Test
    fun gatewayStagesNameTheirModelsAndLeaveDeviceSpeechUnused() =
        withSession(SpeechStageLocation.GATEWAY, SpeechStageLocation.GATEWAY) { session, speech ->
            val state = awaitState(session) { it.transcript.size >= 2 }
            assertEquals(
                listOf(TranscriptSpeaker.USER, TranscriptSpeaker.ASSISTANT),
                state.transcript.map { it.speaker },
            )
            val setup = setupFrame()
            assertEquals("""{"input":"gateway","output":"gateway"}""", setup.getJSONObject("speech").toString())
            assertEquals("fixture-asr", setup.getString("recognitionModelID"))
            assertEquals("fixture-tts", setup.getString("synthesisModelID"))
            assertEquals("Gateway stages must not create device speech", 0, speech.creations)
        }

    private fun withSession(
        recognition: SpeechStageLocation,
        speech: SpeechStageLocation,
        holdPlayback: Boolean = false,
        block: suspend (VoiceSession, ScriptedSpeech) -> Unit,
    ): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dataStoreJob = SupervisorJob()
            try {
                val repository =
                    SettingsRepository(
                        PreferenceDataStoreFactory.create(
                            scope = CoroutineScope(Dispatchers.IO + dataStoreJob),
                            produceFile = {
                                File(
                                    context.filesDir,
                                    "mixed-speech-${System.nanoTime()}.preferences_pb",
                                )
                            },
                        ),
                    )
                repository.addService("fixture", baseUrl)
                repository.updateVoiceSetup {
                    it.copy(
                        mode = VoiceSetupMode.SEPARATE_STAGES,
                        recognition = recognition,
                        speech = speech,
                        languageModel = VoiceLanguageModel.GATEWAY,
                    )
                }
                repository.settings.first {
                    it.activeServiceURL == baseUrl && it.voiceSetup.effectiveRecognition == recognition &&
                        it.voiceSetup.effectiveSpeech == speech
                }
                val activity = ActivityScenario.launch(MainActivity::class.java)
                val pages = ServiceShellOwner(context, repository)
                val conversation =
                    VoiceConversation(repository, ServiceSettingsClient(), pages.catalog) { pages.entries }
                val deviceSpeech = ScriptedSpeech(holdPlayback)
                val session =
                    VoiceSession(
                        context,
                        repository,
                        conversation,
                        "en-US",
                        thinkingEarcon =
                            object : ThinkingEarcon {
                                override fun setThinking(active: Boolean) = Unit
                            },
                        createDeviceSpeech = {
                            deviceSpeech.creations++
                            deviceSpeech
                        },
                    )
                try {
                    withTimeout(TIMEOUT_MS) {
                        withContext(Dispatchers.Main) { pages.prepareCall(repository.settings.value) {} }
                    }
                    activity.onActivity { session.connect() }
                    val ready = awaitState(session) { it.connected || it.error != null }
                    assertNull(ready.error)
                    block(session, deviceSpeech)
                } finally {
                    withContext(Dispatchers.Main) {
                        session.close()
                        pages.close()
                    }
                    activity.close()
                }
            } finally {
                withContext(NonCancellable) { dataStoreJob.cancelAndJoin() }
            }
        }

    private suspend fun awaitState(
        session: VoiceSession,
        predicate: (VoiceState) -> Boolean,
    ): VoiceState =
        withTimeout(TIMEOUT_MS) {
            session.state.first {
                check(it.error == null) { "Voice session failed: ${it.error}" }
                predicate(it)
            }
        }

    // Each utterance starts a new listener; [previous] already delivered its result.
    private suspend fun awaitListener(
        speech: ScriptedSpeech,
        previous: Listener?,
    ): Listener = withTimeout(TIMEOUT_MS) { speech.listening.filterNotNull().first { it !== previous } }

    private suspend fun awaitFixture(predicate: (JSONObject) -> Boolean) =
        withTimeout(TIMEOUT_MS) {
            while (!predicate(fixtureStats())) delay(50)
        }

    private suspend fun setupFrame(): JSONObject = fixtureStats().getJSONObject("rtcSetup")

    private suspend fun fixtureStats(): JSONObject = JSONObject(fixtureRequest("/fixture/stats", "GET"))

    private suspend fun fixtureRequest(
        path: String,
        method: String,
    ): String =
        withContext(Dispatchers.IO) {
            val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                check(connection.responseCode == HttpURLConnection.HTTP_OK) { "$path: HTTP ${connection.responseCode}" }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

    private val baseUrl: String
        get() =
            requireNotNull(InstrumentationRegistry.getArguments().getString("gatewayBaseUrl")) {
                "gatewayBaseUrl instrumentation argument is required"
            }

    /** One device recognition utterance's callbacks; the session calls and stops them on the main thread. */
    private class Listener(
        val onResult: (String) -> Unit,
        val onPartial: (String) -> Unit,
    )

    private class ScriptedSpeech(
        private val holdPlayback: Boolean,
    ) : DeviceSpeech {
        override val microphoneRms = MutableStateFlow(0f)
        override val languageState = MutableStateFlow<SpeechLanguageState>(SpeechLanguageState.Ready)
        val listening = MutableStateFlow<Listener?>(null)
        val spoken = CopyOnWriteArrayList<String>()

        @Volatile var creations = 0

        @Volatile var stops = 0

        override suspend fun checkLanguage() = languageState.value

        override suspend fun downloadLanguage() = Unit

        override fun isAvailable() = true

        override fun startListening(
            onResult: (String) -> Unit,
            onPartial: (String) -> Unit,
            onIdle: () -> Unit,
            onError: (String) -> Unit,
        ) {
            listening.value = Listener(onResult, onPartial)
        }

        override fun stopListening() {
            listening.value = null
        }

        override fun speak(
            text: String,
            onDone: () -> Unit,
            onError: (String) -> Unit,
        ) {
            spoken.add(text)
            if (!holdPlayback) onDone()
        }

        override fun stopSpeaking() {
            stops++
        }

        override fun close() = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
