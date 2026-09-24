// Unit tests for Go Mode shell recovery and voice account changes.
package com.fghbuild.gomode.ui

import com.fghbuild.gomode.ui.web.WebShellLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoModeAppTest {
    @Test
    fun accountSwitchInvalidatesVoiceIdentity() {
        val identity = VoiceAuthIdentity()

        assertFalse(identity.update("account-a", 0))
        assertFalse(identity.update("account-a", 0))
        assertTrue(identity.update("account-b", 0))
        assertFalse(identity.update("account-b", 0))
        assertTrue(identity.update(null, 0))
        assertTrue(identity.update("account-c", 0))
        assertTrue(identity.update("account-c", 1))
        assertFalse(identity.update("account-c", 1))
    }

    @Test
    fun cookieOnlyAccountChangeInvalidatesVoiceIdentity() {
        val identity = VoiceAuthIdentity()
        assertFalse(identity.update(null, 0))
        assertTrue(identity.update(null, 1))
        assertTrue(identity.update(null, 2))
    }

    @Test
    fun bootstrapFailureTakesPriorityOverWebFailure() {
        val recovery =
            shellRecoveryState(
                bootstrapError = "Unsupported service version.",
                webLoadState = WebShellLoadState.Failed("The service took too long to respond."),
            )

        assertEquals(
            ShellRecoveryState(
                title = "Could not use service",
                message = "Unsupported service version.",
                retryTarget = ShellRecoveryRetryTarget.BOOTSTRAP,
            ),
            recovery,
        )
    }

    @Test
    fun webFailureOffersServiceRetryAndMarksVoiceUnavailable() {
        val recovery =
            shellRecoveryState(
                bootstrapError = null,
                webLoadState = WebShellLoadState.Failed("The service took too long to respond."),
            )

        assertEquals(
            ShellRecoveryState(
                title = "Could not load service",
                message = "The service took too long to respond.",
                retryTarget = ShellRecoveryRetryTarget.WEB,
            ),
            recovery,
        )
    }

    @Test
    fun webReconnectSuppressesCompetingActions() {
        val recovery =
            shellRecoveryState(
                bootstrapError = null,
                webLoadState = WebShellLoadState.Reconnecting,
            )

        assertEquals(
            ShellRecoveryState(
                title = "Reconnecting to service",
                message = "Voice will be available when the service reconnects.",
                retryTarget = null,
            ),
            recovery,
        )
    }

    @Test
    fun readyServiceAndWebViewNeedNoRecoveryStrip() {
        assertNull(shellRecoveryState(bootstrapError = null, webLoadState = WebShellLoadState.Ready))
    }
}
