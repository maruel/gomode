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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class WebShellSmokeTest : GoModeE2eTestBase() {
    @StandaloneHostedFixture
    @Test
    fun microphoneDenialOffersPromptThenSettingsWithoutStartingVoiceOnReenable() {
        openWebShell()
        waitForHostedFrontend()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        fun denyPermission() {
            val deny =
                device.wait(
                    Until.findObject(By.res(Pattern.compile(".*:id/permission_deny.*_button"))),
                    GOMODE_DEFAULT_TIMEOUT_MS,
                )
            requireNotNull(deny) { "Microphone permission prompt did not appear" }.click()
        }
        composeRule.onNodeWithTag("gomode-voice-connect").performClick()
        requireNotNull(device.wait(Until.findObject(By.textContains("record audio")), GOMODE_DEFAULT_TIMEOUT_MS))
        device.pressBack()
        composeRule.onNodeWithText("Enable microphone").performClick()
        denyPermission()
        composeRule.onNodeWithText("Enable microphone").performClick()
        denyPermission()
        composeRule.onNodeWithText("Enable microphone").assertDoesNotExist()
        composeRule.onNodeWithTag("gomode-microphone-recovery").assertExists()
        if (InstrumentationRegistry.getArguments().getString("captureVoicePanel") == "true") {
            device.executeShellCommand("screencap -p /data/local/tmp/gomode-microphone-denied.png")
        }
        composeRule.onNodeWithText("Open app settings").performClick()

        fun clickSystemText(text: String) {
            requireNotNull(device.wait(Until.findObject(By.text(text)), GOMODE_DEFAULT_TIMEOUT_MS)) {
                "System settings did not show $text"
            }.click()
        }
        clickSystemText("Permissions")
        clickSystemText("Microphone")
        clickSystemText("Allow only while using the app")
        repeat(3) { device.pressBack() }
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-microphone-recovery").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("gomode-voice-connect").assertExists()
        composeRule.onNodeWithTag("gomode-voice-disconnect").assertDoesNotExist()
        tapDomElement("#save-note")
        waitForText("Saved to your field notes.")
    }

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
        addService("Second")
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-web-shell").fetchSemanticsNodes().isNotEmpty()
        }
        waitForSettingsButton()

        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.onAllNodesWithContentDescription("Edit service").onFirst().performClick()
        waitForServiceEditor()
        fillServiceField("gomode-service-label", "First renamed")
        closeServiceEditor()

        composeRule.onNodeWithText("First renamed").assertExists()
        composeRule.onNodeWithText("Second").assertExists()
        composeRule.onNodeWithText("Active").assertExists()
    }

    @StandaloneHostedFixture
    @Test
    fun nextAppFlushesAPendingServiceEditBeforeClosingSettings() {
        openWebShell()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        addService("Second")
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
        waitForServiceEditor()
        fillServiceField("gomode-service-label", "First retained")
        closeServiceEditor()
        addService("Second retained")
        waitForHostedFrontend()
        val second = waitForWebView()
        assertNotSame(first, second)
        executeDom("set second page state") { "(window.retainedMarker = 'second', true)" }
        waitForSettingsButton()
        composeRule.onNodeWithTag("gomode-next-app").performClick()
        // Selection is persisted asynchronously; the outgoing page also satisfies frontend readiness.
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.runOnUiThread { first.isAttachedToWindow }
        }
        waitForHostedFrontend()
        assertSame(first, waitForWebView())
        waitForDom("first SPA state survives server switches") { "window.retainedMarker === 'first'" }
        waitForSettingsButton()
        composeRule.onNodeWithTag("gomode-next-app").performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.runOnUiThread { second.isAttachedToWindow }
        }
        waitForHostedFrontend()
        assertSame(second, waitForWebView())
        waitForDom("second SPA state survives server switches") { "window.retainedMarker === 'second'" }
        val rendererCallbacks = AtomicInteger()
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
                                rendererCallbacks.incrementAndGet()
                                return client.onRenderProcessGone(view, detail)
                            }
                        }
                }
                requireNotNull(second.webViewRenderProcess)
            }
        composeRule.activityRule.scenario.recreate()
        waitForHostedFrontend()
        assertSame(second, waitForWebView())
        waitForDom("Activity recreation retains page state") { "window.retainedMarker === 'second'" }
        composeRule.runOnUiThread {
            assertTrue(!first.isAttachedToWindow && second.isAttachedToWindow)
            renderer.terminate()
        }
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-shell-retry").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Retained page did not report renderer loss", rendererCallbacks.get() > 0)
        composeRule.onNodeWithTag("gomode-shell-retry").performClick()
        waitForHostedFrontend()
        assertNotSame(second, waitForWebView())
        waitForDom("Renderer recovery creates a new document") { "typeof window.retainedMarker === 'undefined'" }
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

    private fun addService(label: String) {
        composeRule.onNodeWithTag("gomode-add-service").performScrollTo().performClick()
        waitForServiceEditor()
        fillServiceField("gomode-service-label", label)
        fillServiceField("gomode-service-url", baseUrl)
        dismissServiceKeyboard()
        composeRule.onNodeWithTag("gomode-create-service").performScrollTo().performClick()
    }

    private fun waitForServiceEditor() {
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-service-label").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun closeServiceEditor() {
        dismissServiceKeyboard()
        composeRule.onNodeWithTag("gomode-close-editor").performScrollTo().performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-service-label").fetchSemanticsNodes().isEmpty()
        }
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
