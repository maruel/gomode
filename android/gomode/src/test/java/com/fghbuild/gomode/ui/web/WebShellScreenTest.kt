// Unit tests for Go Mode WebView recovery and new-window routing helpers.
package com.fghbuild.gomode.ui.web

import android.content.Intent
import android.net.Uri
import android.webkit.TimeoutWebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    shadows = [HostedWebViewFeatures::class, HostedWebViewCompat::class],
    instrumentedPackages = ["androidx.webkit"],
)
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
    fun timeoutAndRouteRecoveryShareOneAutomaticRetryBudget() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                for (routeReload in listOf(false, true)) {
                    val entry = ServiceShellSession(ServiceShellKey("test", "https://service.test")) {}
                    try {
                        val page = entry.page(RuntimeEnvironment.getApplication())
                        val view = page.webView
                        val shadow = shadowOf(view)
                        val client = shadow.webViewClient
                        page.load(0)
                        if (routeReload) {
                            entry.updateLoad(WebShellLoadState.Failed("Old route", networkFailure = true))
                            entry.resetAutomaticRetryBudget()
                            entry.retryWebAutomatically()
                            page.load(entry.state.value.webReloadToken)
                        } else {
                            client.onPageStarted(view, entry.key.url, null)
                            client.onReceivedError(view, mainFrameRequest(entry.key.url), TimeoutWebResourceError())
                            // A new route can arrive while the timeout retry is still pending.
                            entry.resetAutomaticRetryBudget()
                            advanceTimeBy(1_000)
                            runCurrent()
                        }
                        assertEquals(1, shadow.reloadInvocations)
                        client.onPageStarted(view, entry.key.url, null)
                        client.onReceivedError(view, mainFrameRequest(entry.key.url), TimeoutWebResourceError())
                        advanceTimeBy(2_000)
                        runCurrent()
                        entry.retryWebAutomatically()
                        page.load(entry.state.value.webReloadToken)
                        assertEquals(1, shadow.reloadInvocations)
                        assertEquals(true, entry.state.value.load is WebShellLoadState.Failed)
                    } finally {
                        entry.close()
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun mainFrameRequest(url: String) =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)

            override fun isForMainFrame(): Boolean = true

            override fun isRedirect(): Boolean = false

            override fun hasGesture(): Boolean = false

            override fun getMethod(): String = "GET"

            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }

    @Test
    fun timeoutMessageExplainsRecoveryAfterAutomaticRetryFails() {
        assertEquals(
            "The service took too long to respond.",
            webLoadErrorMessage(WebViewClient.ERROR_TIMEOUT),
        )
    }

    @Test
    fun nonTimeoutMessageExplainsServiceRecovery() {
        assertEquals(
            "Could not connect to the service.",
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
