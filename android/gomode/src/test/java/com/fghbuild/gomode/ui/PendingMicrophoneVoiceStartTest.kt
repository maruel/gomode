// Verifies timed microphone permission starts cannot authorize unprepared or replacement frontend identities.
package com.fghbuild.gomode.ui

import android.net.Uri
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.WebShellLoadState
import com.fghbuild.gomode.ui.web.completeFrontendPreparation
import com.fghbuild.gomode.voice.MicrophoneMode
import com.fghbuild.gomode.voice.VoiceSessionController
import com.fghbuild.gomode.voice.VoiceStartupSource
import com.fghbuild.gomode.voice.VoiceStartupStage
import com.fghbuild.gomode.voice.VoiceStartupTiming
import com.fghbuild.gomode.voice.VoiceState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class PendingMicrophoneVoiceStartTest {
    @Test
    fun permissionWaitAndTransportConnectionRemainOneManualAttempt() =
        runTest {
            val page = preparedPage("first")
            val session = Voice()
            val timing = VoiceStartupTiming(VoiceStartupSource.MANUAL)
            timing.enter(VoiceStartupStage.MICROPHONE)
            val pending = PendingMicrophoneVoiceStart(page, session, timing)
            ShadowSystemClock.advanceBy(Duration.ofMillis(47))
            assertTrue(pending.connectIfCurrent(page, session))
            session.state.value = VoiceState(connectStatus = "Connecting")
            observeVoiceStartup(backgroundScope, session, timing)
            runCurrent()
            assertTrue(ShadowLog.getLogsForTag("GoModeVoiceStartup").none { it.msg.contains("event=finished") })
            ShadowSystemClock.advanceBy(Duration.ofMillis(13))
            session.state.value = VoiceState(connected = true)
            runCurrent()
            val records = ShadowLog.getLogsForTag("GoModeVoiceStartup").map { it.msg }
            assertTrue(records.any { it.contains("stage=MICROPHONE stage_ms=47 elapsed_ms=47") })
            assertTrue(records.last().contains("outcome=CONNECTED stage=TRANSPORT stage_ms=13 elapsed_ms=60"))
            assertEquals(1, records.filter { it.contains("event=finished") }.size)
            page.close()
        }

    @Test
    fun timedOutOperationCanRecoverOnTheSameLiveManualAttempt() =
        runTest {
            val timing = VoiceStartupTiming(VoiceStartupSource.MANUAL)
            timing.enter(VoiceStartupStage.TRANSPORT)
            val session = Voice()
            session.state.value = VoiceState(connectStatus = "Connecting")
            observeVoiceStartup(backgroundScope, session, timing)
            runCurrent()
            ShadowSystemClock.advanceBy(Duration.ofMillis(31))
            timing.noteTimeout()
            val waiting = ShadowLog.getLogsForTag("GoModeVoiceStartup").map { it.msg }
            assertTrue(waiting.last().contains("event=stage_timeout stage=TRANSPORT"))
            assertTrue(waiting.none { it.contains("event=finished") })
            session.state.value = VoiceState(connected = true)
            runCurrent()
            val completed = ShadowLog.getLogsForTag("GoModeVoiceStartup").map { it.msg }
            assertEquals(1, completed.count { it.contains("event=finished outcome=CONNECTED") })
            assertEquals(1, completed.map { it.substringBefore(" source=") }.distinct().size)
            timing.noteTimeout()
            assertEquals(completed, ShadowLog.getLogsForTag("GoModeVoiceStartup").map { it.msg })
        }

    @Test
    fun canceledManualObserverCannotReportLateConnection() =
        runTest {
            val timing = VoiceStartupTiming(VoiceStartupSource.MANUAL)
            val session = Voice()
            session.state.value = VoiceState(connectStatus = "Connecting")
            observeVoiceStartup(backgroundScope, session, timing)
            runCurrent()
            backgroundScope.cancel()
            runCurrent()
            session.state.value = VoiceState(connected = true)
            runCurrent()
            val terminal = ShadowLog.getLogsForTag("GoModeVoiceStartup").filter { it.msg.contains("event=finished") }
            assertEquals(1, terminal.size)
            assertTrue(terminal.single().msg.contains("outcome=CANCELED"))
        }

    @Test
    fun manualTransportFailureHasAFixedOutcomeWithoutLoggingProviderDetails() =
        runTest {
            val timing = VoiceStartupTiming(VoiceStartupSource.MANUAL)
            val session = Voice()
            session.state.value = VoiceState(error = "Sensitive provider detail")
            observeVoiceStartup(backgroundScope, session, timing)
            runCurrent()
            val records = ShadowLog.getLogsForTag("GoModeVoiceStartup").map { it.msg }
            assertTrue(records.last().contains("outcome=FAILED"))
            assertTrue(records.none { it.contains("Sensitive") })
        }

    @Test
    fun grantedPermissionStartsTheOriginalPreparedOwner() {
        val page = preparedPage("first")
        val session = Voice()
        val pending = PendingMicrophoneVoiceStart(page, session, VoiceStartupTiming(VoiceStartupSource.MANUAL))
        pending.connectIfCurrent(page, session)
        assertEquals(1, session.connections)
        page.close()
    }

    @Test
    fun readinessLossOrReplacementCannotConsumeAnEarlierPermissionIntent() {
        val page = preparedPage("first")
        val session = Voice()
        val waiting = PendingMicrophoneVoiceStart(page, session, VoiceStartupTiming(VoiceStartupSource.MANUAL))
        page.invalidateIdentity()
        waiting.connectIfCurrent(page, session)
        assertEquals(0, session.connections)
        completeFrontendPreparation(page)
        val originalAccount = PendingMicrophoneVoiceStart(page, session, VoiceStartupTiming(VoiceStartupSource.MANUAL))
        page.bearerStore.receive(Uri.parse(page.key.url), true, page.key.url, """{"bearerToken":"second"}""")
        completeFrontendPreparation(page)
        assertTrue(page.isFrontendReady)
        originalAccount.connectIfCurrent(page, session)
        assertEquals(0, session.connections)
        val current = PendingMicrophoneVoiceStart(page, session, VoiceStartupTiming(VoiceStartupSource.MANUAL))
        val replacement = Voice()
        current.connectIfCurrent(page, replacement)
        assertEquals(0, replacement.connections)
        assertEquals(0, session.connections)
        val replacementPage = preparedPage("second")
        current.connectIfCurrent(replacementPage, session)
        assertEquals(0, session.connections)
        replacementPage.close()
        page.close()
    }

    @Test
    fun sameAccountReloadCannotConsumeTheOriginalDocumentsPermissionIntent() {
        val page = preparedPage("account")
        val session = Voice()
        val pending = PendingMicrophoneVoiceStart(page, session, VoiceStartupTiming(VoiceStartupSource.MANUAL))
        val epoch = page.bearerStore.identityEpoch.value
        val document = page.frontendPreparation.document
        page.invalidatePageAuthentication()
        page.bearerStore.onNavigation(page.key.url)
        check(page.bearerStore.receive(Uri.parse(page.key.url), true, page.key.url, """{"bearerToken":"account"}"""))
        completeFrontendPreparation(page)
        assertEquals(epoch, page.bearerStore.identityEpoch.value)
        assertTrue(document != page.frontendPreparation.document)
        assertTrue(page.isFrontendReady)
        pending.connectIfCurrent(page, session)
        assertEquals(0, session.connections)
        PendingMicrophoneVoiceStart(
            page,
            session,
            VoiceStartupTiming(VoiceStartupSource.MANUAL),
        ).connectIfCurrent(page, session)
        assertEquals(1, session.connections)
        page.close()
    }

    private fun preparedPage(token: String): ServiceShellSession {
        val page = ServiceShellSession(ServiceShellKey("saved", "https://service.test")) {}
        page.updateLoad(WebShellLoadState.Ready)
        page.bearerStore.onNavigation(page.key.url)
        check(page.bearerStore.receive(Uri.parse(page.key.url), true, page.key.url, """{"bearerToken":"$token"}"""))
        completeFrontendPreparation(page)
        return page
    }

    private class Voice : VoiceSessionController {
        override val state = MutableStateFlow(VoiceState())
        var connections = 0

        override fun connect(
            preserveTranscript: Boolean,
            conversationID: String?,
        ) {
            connections++
        }

        override fun disconnect() = Unit

        override fun setMicrophoneMode(mode: MicrophoneMode) = Unit

        override fun selectAudioDevice(deviceId: Int) = Unit

        override fun clearTranscript() = Unit

        override fun injectText(text: String) = Unit

        override fun setError(message: String) = Unit

        override fun close() = Unit
    }
}
