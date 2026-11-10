// Exercises real WebRTC and text gateway sessions, network recovery, and Telecom with controlled server responses.
package com.fghbuild.gomode.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.MainActivity
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File

// Uses production session clients against a controlled server for text and WebRTC
// transports, background setup, caller identity, network recovery, and Telecom ownership.
@RunWith(AndroidJUnit4::class)
class GatewaySessionIntegrationTest {
    @get:Rule(order = 0)
    val speech: TestRule = ModelFreeSpeechRule()

    @get:Rule(order = 1)
    val recordAudioPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun webRtcSessionRetainsItsConversationAndTelecomAcrossNetworkChanges(): Unit =
        runBlocking {
            val baseUrl = requireNotNull(InstrumentationRegistry.getArguments().getString("gatewayBaseUrl"))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dataStoreJob = SupervisorJob()
            try {
                val repository =
                    SettingsRepository(
                        PreferenceDataStoreFactory.create(
                            scope = CoroutineScope(Dispatchers.IO + dataStoreJob),
                            produceFile = { File(context.filesDir, "rtc-handoff-${System.nanoTime()}.preferences_pb") },
                        ),
                    )
                repository.addService("fixture", baseUrl)
                repository.updateVoiceMode(VoiceMode.CLOUD)
                repository.settings.first { it.activeServiceURL == baseUrl && it.voiceMode == VoiceMode.CLOUD }
                val activity = ActivityScenario.launch(MainActivity::class.java)
                val pages = ServiceShellOwner(context, repository)
                val conversation =
                    VoiceConversation(repository, ServiceSettingsClient(), pages.catalog) { pages.entries }
                val calls = TelecomCallController(context, onTelecomEnded = {})
                val session = VoiceSession(context, repository, conversation, "en-US", callController = calls)
                try {
                    withTimeout(TIMEOUT_MS) {
                        withContext(Dispatchers.Main) { pages.prepareCall(repository.settings.value) {} }
                    }
                    activity.onActivity { session.connect() }
                    awaitConnected(session)
                    val initial = withTimeout(TIMEOUT_MS) { session.state.first { it.transcript.size >= 2 }.transcript }
                    val conversationID = conversation.sessionID
                    exerciseNetworkChanges(session, calls)
                    assertEquals(conversationID, conversation.sessionID)
                    assertEquals(
                        initial.map { it.speaker to it.text },
                        session.state.value.transcript
                            .take(initial.size)
                            .map { it.speaker to it.text },
                    )
                } finally {
                    withContext(Dispatchers.Main) {
                        session.close()
                        calls.close()
                        pages.close()
                    }
                    activity.close()
                }
            } finally {
                withContext(NonCancellable) { dataStoreJob.cancelAndJoin() }
                assertTrue("Fixture DataStore scope did not finish", dataStoreJob.isCompleted)
            }
        }

    @Test
    @ModelFreeSpeech
    fun deviceSessionConnectsAfterBackgroundingDuringSetup(): Unit =
        runBlocking {
            val baseUrl =
                requireNotNull(InstrumentationRegistry.getArguments().getString("gatewayBaseUrl")) {
                    "gatewayBaseUrl instrumentation argument is required"
                }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dataStoreJob = SupervisorJob()
            try {
                val dataStore =
                    PreferenceDataStoreFactory.create(
                        scope = CoroutineScope(Dispatchers.IO + dataStoreJob),
                        produceFile = { File(context.filesDir, "voice-fixture-${System.nanoTime()}.preferences_pb") },
                    )
                val repository = SettingsRepository(dataStore)
                repository.addService("fixture", baseUrl)
                repository.updateVoiceMode(VoiceMode.DEVICE)
                repository.settings.first { it.activeServiceURL == baseUrl && it.voiceMode == VoiceMode.DEVICE }
                val expectedName = ServiceSettingsClient().fetch(baseUrl).service

                val speech = AndroidDeviceSpeech(context, "en-US")
                val calls = TelecomCallController(context, onTelecomEnded = {})
                val finishSetup = CompletableDeferred<Unit>()
                val activity = ActivityScenario.launch(MainActivity::class.java)
                val session =
                    DeviceVoiceSession(
                        appContext = context,
                        settingsRepository = repository,
                        languageTag = "en-US",
                        callController = calls,
                        speech = speech,
                        setupProvider = {
                            finishSetup.await()
                            prepareVoiceSetup(repository, ServiceSettingsClient(), { null })
                        },
                    )
                try {
                    prepareSpeechLanguage(speech)
                    activity.onActivity { session.connect(preserveTranscript = false) }
                    val notifications = context.getSystemService(NotificationManager::class.java)
                    awaitCallerName(notifications, "fixture")
                    activity.moveToState(Lifecycle.State.CREATED)
                    finishSetup.complete(Unit)
                    awaitCallerName(notifications, expectedName)
                    withTimeout(TIMEOUT_MS) {
                        session.state.first { it.connected || it.error != null }
                    }
                    assertNull("device session reported ${session.state.value.error}", session.state.value.error)
                    assertTrue("device session did not connect", session.state.value.connected)

                    exerciseNetworkChanges(session, calls)
                } finally {
                    withContext(Dispatchers.Main) {
                        session.close()
                        calls.close()
                    }
                    // Finish a resumed Activity; a stopped scenario otherwise waits
                    // for the platform's background Activity destruction timeout.
                    activity.moveToState(Lifecycle.State.RESUMED)
                    activity.close()
                }
            } finally {
                withContext(NonCancellable) { dataStoreJob.cancelAndJoin() }
                assertTrue("Fixture DataStore scope did not finish", dataStoreJob.isCompleted)
            }
        }

    private suspend fun exerciseNetworkChanges(
        session: VoiceSessionController,
        calls: VoiceCallController,
    ) = coroutineScope {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1") {
            "Network handoff coverage requires an emulator"
        }
        val wifiEnabled = device.executeShellCommand("settings get global wifi_on").trim() == "1"
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        try {
            device.executeShellCommand("svc wifi enable")
            awaitTransport(connectivity, NetworkCapabilities.TRANSPORT_WIFI)
            awaitConnected(session)
            // The stock fixture disables its modem. Cycling Wi-Fi exercises actual
            // route replacement and the offline wait without requiring a carrier.
            repeat(2) {
                val recovering =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_MS) {
                            session.state.first {
                                !it.connected && it.connectStatus?.contains("reconnecting") == true
                            }
                        }
                    }
                device.executeShellCommand("svc wifi disable")
                withTimeout(TIMEOUT_MS) { recovering.await() }
                assertNull(session.state.value.error)
                assertTrue("Telecom call ended while offline", calls.isStarted)
                device.executeShellCommand("svc wifi enable")
                awaitTransport(connectivity, NetworkCapabilities.TRANSPORT_WIFI)
                awaitConnected(session)
                assertTrue("Telecom call ended during handoff", calls.isStarted)
            }
        } finally {
            device.executeShellCommand(if (wifiEnabled) "svc wifi enable" else "svc wifi disable")
        }
    }

    private suspend fun awaitConnected(session: VoiceSessionController) {
        val ready = withTimeout(TIMEOUT_MS) { session.state.first { it.connected || it.error != null } }
        assertNull(ready.error)
        assertTrue(ready.connected)
    }

    private suspend fun awaitTransport(
        connectivity: ConnectivityManager,
        transport: Int,
    ) {
        withTimeout(TIMEOUT_MS) {
            while (connectivity.activeNetwork?.let {
                    connectivity
                        .getNetworkCapabilities(
                            it,
                        )?.hasTransport(transport)
                } !=
                true
            ) {
                delay(50)
            }
        }
    }

    private suspend fun prepareSpeechLanguage(speech: AndroidDeviceSpeech) {
        val language = speech.checkLanguage()
        assertTrue(
            "Speech language unavailable: $language",
            language == SpeechLanguageState.Ready || language is SpeechLanguageState.Unverified,
        )
    }

    private suspend fun awaitCallerName(
        notifications: NotificationManager,
        expectedName: String,
    ) {
        withTimeout(TIMEOUT_MS) {
            while (notifications.activeNotifications.none {
                    it.notification.extras
                        .getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
                        ?.name
                        ?.toString() == expectedName
                }
            ) {
                delay(50)
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
