// Verifies microphone permission results cannot start unprepared or replacement frontend identities.
package com.fghbuild.gomode.ui

import android.net.Uri
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.WebShellLoadState
import com.fghbuild.gomode.ui.web.completeFrontendPreparation
import com.fghbuild.gomode.voice.MicrophoneMode
import com.fghbuild.gomode.voice.VoiceSessionController
import com.fghbuild.gomode.voice.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingMicrophoneVoiceStartTest {
    @Test
    fun grantedPermissionStartsTheOriginalPreparedOwner() {
        val page = preparedPage("first")
        val session = Voice()
        val pending = PendingMicrophoneVoiceStart(page, session)
        pending.connectIfCurrent(page, session)
        assertEquals(1, session.connections)
        page.close()
    }

    @Test
    fun readinessLossOrReplacementCannotConsumeAnEarlierPermissionIntent() {
        val page = preparedPage("first")
        val session = Voice()
        val waiting = PendingMicrophoneVoiceStart(page, session)
        page.invalidateIdentity()
        waiting.connectIfCurrent(page, session)
        assertEquals(0, session.connections)
        completeFrontendPreparation(page)
        val originalAccount = PendingMicrophoneVoiceStart(page, session)
        page.bearerStore.receive(Uri.parse(page.key.url), true, page.key.url, """{"bearerToken":"second"}""")
        completeFrontendPreparation(page)
        assertTrue(page.isFrontendReady)
        originalAccount.connectIfCurrent(page, session)
        assertEquals(0, session.connections)
        val current = PendingMicrophoneVoiceStart(page, session)
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
        val pending = PendingMicrophoneVoiceStart(page, session)
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
        PendingMicrophoneVoiceStart(page, session).connectIfCurrent(page, session)
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
