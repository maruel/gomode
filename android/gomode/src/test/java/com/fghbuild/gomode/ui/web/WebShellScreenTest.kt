// Unit tests for Go Mode WebView recovery and new-window routing helpers.
package com.fghbuild.gomode.ui.web

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebShellScreenTest {
    @Test
    fun attachmentsBrowseDocumentsWithAllAcceptedFileTypes() {
        val intent = webFileChooserIntent(arrayOf("image/*", "application/pdf"), true)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(true, intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", intent.type)
        assertArrayEquals(arrayOf("image/*", "application/pdf"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertEquals(true, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun attachmentExtensionsIncludeScreenshotsAndDocuments() {
        val intent = webFileChooserIntent(arrayOf(".png, .pdf", "image/jpeg"), false)

        assertArrayEquals(
            arrayOf("image/png", "application/pdf", "image/jpeg"),
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
        assertEquals(false, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun unrestrictedOrUnknownAttachmentTypesAllowBrowsingAllFiles() {
        for (acceptTypes in listOf(emptyArray(), arrayOf(""), arrayOf("image/*", "*/*"), arrayOf(".unknownext"))) {
            val intent = webFileChooserIntent(acceptTypes, false)

            assertEquals("*/*", intent.type)
            assertNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        }
    }

    @Test
    fun hostBridgeExposesNativeVoiceConnectionState() {
        val bridge = GoModeHostBridge()

        GoModeHostBridge.setVoiceConnected(false)
        assertEquals(false, bridge.isVoiceConnected())
        GoModeHostBridge.setVoiceConnected(true)
        assertEquals(true, bridge.isVoiceConnected())
    }

    @Test
    fun mediaPermissionOriginMustMatchTrustedServiceOrigin() {
        val serviceURL = "https://service.test:8443/workspace?goModeHost=1"

        assertEquals(true, isTrustedPermissionOrigin(Uri.parse("https://service.test:8443"), serviceURL))
        assertEquals(false, isTrustedPermissionOrigin(Uri.parse("https://attacker.test"), serviceURL))
        assertEquals(false, isTrustedPermissionOrigin(Uri.parse("https://service.test"), serviceURL))
        assertEquals(false, isTrustedPermissionOrigin(Uri.parse("http://service.test:8443"), serviceURL))
        assertEquals(false, isTrustedPermissionOrigin(Uri.parse("https://service.test.evil.test:8443"), serviceURL))
        assertEquals(false, isTrustedPermissionOrigin(Uri.parse("file:///android_asset/evil"), serviceURL))
    }

    @Test
    fun frontendVoiceToolsAcceptEquivalentOriginsOnlyFromTheHostedMainPage() {
        val serviceURL = "https://Service.test:443/workspace"
        assertEquals(
            true,
            acceptsFrontendVoiceTools(Uri.parse("https://service.test"), true, "https://service.test/", serviceURL),
        )
        assertEquals(
            false,
            acceptsFrontendVoiceTools(Uri.parse("https://service.test"), false, "https://service.test/", serviceURL),
        )
        assertEquals(
            false,
            acceptsFrontendVoiceTools(Uri.parse("https://attacker.test"), true, "https://service.test/", serviceURL),
        )
        assertEquals(
            false,
            acceptsFrontendVoiceTools(Uri.parse("https://service.test"), true, "https://attacker.test/", serviceURL),
        )
        assertEquals(false, acceptsFrontendVoiceTools(Uri.parse("https://service.test"), true, null, serviceURL))
    }

    @Test
    fun firstTimeoutIsRetriedSilently() {
        assertEquals(true, shouldAutomaticallyRetryWebLoadError(WebViewClient.ERROR_TIMEOUT, retryAttempts = 0))
        assertEquals(false, shouldAutomaticallyRetryWebLoadError(WebViewClient.ERROR_TIMEOUT, retryAttempts = 1))
    }

    @Test
    fun nonTimeoutFailuresAreNotRetriedAutomatically() {
        assertEquals(false, shouldAutomaticallyRetryWebLoadError(WebViewClient.ERROR_CONNECT, retryAttempts = 0))
    }

    @Test
    fun newNavigationCancelsPendingAutomaticRetryAndResetsItsBudget() {
        val state = AutomaticTimeoutRetryState(attempts = 1, pending = true)

        assertEquals(AutomaticTimeoutRetryState(), automaticTimeoutRetryStateOnPageStarted(state))
    }

    @Test
    fun automaticRetryNavigationPreservesItsBudget() {
        val state = AutomaticTimeoutRetryState(attempts = 1, inProgress = true)

        assertEquals(
            AutomaticTimeoutRetryState(attempts = 1),
            automaticTimeoutRetryStateOnPageStarted(state),
        )
    }

    @Test
    fun timeoutMessageExplainsRecoveryAfterAutomaticRetryFails() {
        assertEquals(
            "The service took too long to respond. Check your network connection, then retry.",
            webLoadErrorMessage(WebViewClient.ERROR_TIMEOUT),
        )
    }

    @Test
    fun nonTimeoutMessageExplainsServiceRecovery() {
        assertEquals(
            "Could not connect to the service. Check your network connection and service address, then retry.",
            webLoadErrorMessage(WebViewClient.ERROR_CONNECT),
        )
    }

    @Test
    fun newWindowRequestUriAcceptsAnchorHitTestUrls() {
        val uri =
            newWindowRequestUriOrNull(
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                "https://example.com/docs",
            )

        assertEquals("https://example.com/docs", uri.toString())
    }

    @Test
    fun newWindowRequestUriAcceptsImageAnchorHitTestUrls() {
        val uri =
            newWindowRequestUriOrNull(
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                "mailto:support@example.com",
            )

        assertEquals("mailto:support@example.com", uri.toString())
    }

    @Test
    fun newWindowRequestUriIgnoresUnknownHitTestUrls() {
        val uri =
            newWindowRequestUriOrNull(
                WebView.HitTestResult.UNKNOWN_TYPE,
                "https://example.com/docs",
            )

        assertNull(uri)
    }

    @Test
    fun newWindowRequestUriIgnoresBlankAndRelativeUrls() {
        assertNull(newWindowRequestUriOrNull(WebView.HitTestResult.SRC_ANCHOR_TYPE, ""))
        assertNull(newWindowRequestUriOrNull(WebView.HitTestResult.SRC_ANCHOR_TYPE, "/docs"))
    }
}
