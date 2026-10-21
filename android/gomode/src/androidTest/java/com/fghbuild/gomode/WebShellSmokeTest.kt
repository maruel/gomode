// Instrumented smoke coverage for the Go Mode WebView shell.
package com.fghbuild.gomode

import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WebShellSmokeTest : GoModeE2eTestBase() {
    @StandaloneHostedFixture
    @Test
    fun webShellLoadsHostedFrontendAndHandlesSpaBack() {
        openWebShell()
        waitForHostedFrontend()
        waitForDom("hosted frontend is visible") { "isVisible('#demo-service')" }
        tapDomElement("#save-note")
        waitForText("Saved to your field notes.")
        val webView = waitForWebView()
        val fillsViewport =
            composeRule.runOnUiThread {
                val visibleBounds = Rect()
                val windowBounds = composeRule.activity.window.decorView
                webView.getGlobalVisibleRect(visibleBounds) &&
                    visibleBounds.width() > windowBounds.width / 2 &&
                    visibleBounds.height() > windowBounds.height / 2
            }
        assertTrue("Hosted WebView does not fill the activity viewport", fillsViewport)
        waitForDom("CSS viewport height matches the hosted viewport") {
            """
            window.innerHeight > 0 &&
              Math.abs(parseFloat(getComputedStyle(document.querySelector('main')).minHeight) -
                window.innerHeight) < 1
            """.trimIndent()
        }

        executeDom("push SPA route") {
            """
            (() => {
              window.history.pushState(null, "", "$SPA_BACK_TEST_PATH");
              window.dispatchEvent(new PopStateEvent("popstate"));
              return true;
            })()
            """.trimIndent()
        }
        waitForDom("pushed SPA route") { "location.pathname === '$SPA_BACK_TEST_PATH'" }

        pressActivityBack()
        waitForDom("SPA back returned home") { "location.pathname === '/'" }
    }

    @StandaloneHostedFixture
    @Test
    fun webShellSettingsButtonOpensNativeSettingsAndBackReturnsToWebShell() {
        openWebShell()

        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-settings").fetchSemanticsNodes().isNotEmpty()
        }

        pressActivityBack()

        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-web-shell").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @StandaloneHostedFixture
    @Test
    fun settingsCanAddAndEditServicesWithoutSwitchingTheActiveService() {
        openWebShell()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.onNodeWithTag("gomode-add-service").performClick()
        composeRule.onNodeWithTag("gomode-service-label").performTextReplacement("Second")
        composeRule.onNodeWithTag("gomode-service-url").performTextReplacement(baseUrl)
        composeRule.onNodeWithTag("gomode-create-service").performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-web-shell").fetchSemanticsNodes().isNotEmpty()
        }
        waitForSettingsButton()

        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.onAllNodesWithContentDescription("Edit service").onFirst().performClick()
        composeRule.onNodeWithTag("gomode-service-label").performTextReplacement("First renamed")
        composeRule.onNodeWithTag("gomode-close-editor").performClick()

        composeRule.onNodeWithText("First renamed").assertExists()
        composeRule.onNodeWithText("Second").assertExists()
        composeRule.onNodeWithText("Active").assertExists()
    }

    @StandaloneHostedFixture
    @Test
    fun nextAppFlushesAPendingServiceEditBeforeClosingSettings() {
        openWebShell()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.onNodeWithTag("gomode-add-service").performClick()
        composeRule.onNodeWithTag("gomode-service-label").performTextReplacement("Second")
        composeRule.onNodeWithTag("gomode-service-url").performTextReplacement(baseUrl)
        composeRule.onNodeWithTag("gomode-create-service").performClick()
        waitForSettingsButton()
        val observer = SettingsRepository(composeRule.activity.goModeSettingsDataStore)
        composeRule.waitUntil { observer.settings.value.services.size == 2 }
        val first =
            observer.settings.value.services
                .first()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.onNodeWithTag("gomode-edit-service-${first.id}").performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule
                .onNodeWithTag("gomode-service-label")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text == first.label
        }
        composeRule.onNodeWithTag("gomode-service-label").performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { isImeVisible() }
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag("gomode-service-label").performTextReplacement("Pending name")
            composeRule.mainClock.advanceTimeByFrame()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.pressBack()
            device.waitForIdle()
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { !isImeVisible() }
            val deadline = composeRule.mainClock.currentTime + 500
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                check(composeRule.mainClock.currentTime < deadline) { "Voice controls did not reappear" }
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.onAllNodesWithTag("gomode-next-app").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(
                first.label,
                observer.settings.value.services
                    .first()
                    .label,
            )
            assertEquals(
                "Pending name",
                composeRule
                    .onNodeWithTag("gomode-service-label")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.EditableText]
                    .text,
            )
            composeRule.onNodeWithTag("gomode-next-app").performClick()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            observer.settings.value.services
                .first()
                .label == "Pending name" && observer.settings.value.activeServiceId == first.id
        }
        waitForSettingsButton()
        composeRule.waitUntil { composeRule.onAllNodesWithTag("gomode-settings").fetchSemanticsNodes().isEmpty() }
    }

    @StandaloneHostedFixture
    @Test
    fun switchingServersRetainsEachWebViewAndItsSpaState() {
        openWebShell()
        waitForHostedFrontend()
        val first = waitForWebView()
        executeDom("set first page state") { "(window.retainedMarker = 'first', true)" }
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.onAllNodesWithContentDescription("Edit service").onFirst().performClick()
        composeRule.onNodeWithTag("gomode-service-label").performTextReplacement("First retained")
        composeRule.onNodeWithTag("gomode-close-editor").performClick()
        composeRule.onNodeWithTag("gomode-add-service").performClick()
        composeRule.onNodeWithTag("gomode-service-label").performTextReplacement("Second retained")
        composeRule.onNodeWithTag("gomode-service-url").performTextReplacement(baseUrl)
        composeRule.onNodeWithTag("gomode-create-service").performClick()
        waitForHostedFrontend()
        val second = waitForWebView()
        assertNotSame(first, second)
        executeDom("set second page state") { "(window.retainedMarker = 'second', true)" }
        waitForSettingsButton()
        composeRule.onNodeWithTag("gomode-next-app").performClick()
        waitForHostedFrontend()
        assertSame(first, waitForWebView())
        waitForDom("first SPA state survives server switches") { "window.retainedMarker === 'first'" }
        waitForSettingsButton()
        composeRule.onNodeWithTag("gomode-next-app").performClick()
        waitForHostedFrontend()
        assertSame(second, waitForWebView())
        waitForDom("second SPA state survives server switches") { "window.retainedMarker === 'second'" }
        val staleRendererCallbacks = AtomicInteger()
        val renderer =
            composeRule.runOnUiThread {
                listOf(first, second).forEach { view ->
                    val client = view.webViewClient
                    view.webViewClient =
                        object : WebViewClient() {
                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: RenderProcessGoneDetail,
                            ): Boolean {
                                staleRendererCallbacks.incrementAndGet()
                                return client.onRenderProcessGone(view, detail)
                            }
                        }
                }
                requireNotNull(second.webViewRenderProcess)
            }
        composeRule.activityRule.scenario.recreate()
        waitForHostedFrontend()
        val rebuilt = waitForWebView()
        assertNotSame(second, rebuilt)
        waitForDom("Activity recreation rebuilds page state") { "typeof window.retainedMarker === 'undefined'" }
        composeRule.runOnUiThread {
            assertTrue(!first.isAttachedToWindow && !second.isAttachedToWindow)
            renderer.terminate()
        }
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.runOnUiThread { !renderer.terminate() }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("Disposed Activity retained live renderer callbacks", 0, staleRendererCallbacks.get())
    }

    @StandaloneHostedFixture
    @Test
    fun targetBlankLinksOpenInDefaultBrowser() {
        openWebShell()
        loadHostedExternalLinkTestPage()
        waitForDom("external link loaded") { "document.getElementById('external-link') !== null" }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter =
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            }
        val monitor = instrumentation.addMonitor(filter, ActivityResult(0, null), true)
        try {
            tapDomElement("#external-link")
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { monitor.hits > 0 }
            assertEquals(1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @StandaloneHostedFixture
    @Test
    fun gomodePackageNameIsAppSpecific() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.fghbuild.gomode", context.packageName)
    }

    private fun loadHostedExternalLinkTestPage() {
        loadHostedHtml(EXTERNAL_LINK_TEST_PAGE)
    }

    private fun loadHostedHtml(html: String) {
        val latch = CountDownLatch(1)
        val view = waitForWebView()
        composeRule.activity.runOnUiThread {
            view.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
            latch.countDown()
        }
        check(latch.await(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Test page load timed out" }
    }

    companion object {
        private const val SPA_BACK_TEST_PATH = "/gomode-e2e-route"
        private const val JS_TIMEOUT_MS = 5_000L
        private const val EXTERNAL_LINK_URL = "https://example.com/gomode-external-link"
        private val EXTERNAL_LINK_TEST_PAGE =
            """
            <!doctype html>
            <html>
              <body>
                <a id="external-link" href="$EXTERNAL_LINK_URL" target="_blank" rel="noopener">External docs</a>
              </body>
            </html>
            """.trimIndent()
    }
}
