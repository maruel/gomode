// Instrumented smoke coverage for the Go Mode WebView shell.
package com.fghbuild.gomode

import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.Assert.assertFalse
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
    @Test
    @DeniedMicrophonePermission
    fun microphoneDenialOffersPromptThenSettingsWithoutStartingVoiceOnReenable() {
        openWebShell()
        waitForHostedFrontend()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        fun waitForAppFocus() {
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                composeRule.runOnUiThread { composeRule.activity.hasWindowFocus() }
            }
        }

        fun denyPermission() {
            val deny =
                device.wait(
                    Until.findObject(By.res(Pattern.compile(".*:id/permission_deny.*_button"))),
                    GOMODE_DEFAULT_TIMEOUT_MS,
                )
            requireNotNull(deny) { "Microphone permission prompt did not appear" }.click()
            waitForAppFocus()
        }
        composeRule.onNodeWithTag("gomode-voice-connect").performClick()
        requireNotNull(device.wait(Until.findObject(By.textContains("record audio")), GOMODE_DEFAULT_TIMEOUT_MS))
        device.pressBack()
        waitForAppFocus()
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
                val shown = device.findObjects(By.text(Pattern.compile(".+"))).mapNotNull { it.text }
                "System settings did not show $text in ${device.currentPackageName}; visible: $shown"
            }.click()
        }
        clickSystemText("Permissions")
        clickSystemText("Microphone")
        clickSystemText("Allow only while using the app")
        repeat(3) { device.pressBack() }
        waitForAppFocus()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-microphone-recovery").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("gomode-voice-connect").assertExists()
        composeRule.onNodeWithTag("gomode-voice-disconnect").assertDoesNotExist()
        tapDomElement("#save-note")
        waitForText("Saved to your field notes.")
    }

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

    @Test
    fun pinchGestureZoomsTheHostedFrontend() {
        openWebShell()
        waitForHostedFrontend()
        val webView = waitForWebView()
        composeRule.runOnUiThread {
            assertTrue("Hosted WebView does not support zoom", webView.settings.supportZoom())
            assertTrue("Hosted WebView does not use built-in zoom controls", webView.settings.builtInZoomControls)
            assertFalse("Hosted WebView shows zoom buttons", webView.settings.displayZoomControls)
        }
        val initialScale = js("window.visualViewport.scale").toDouble()
        val location = webViewScreenLocation()
        val (width, height) = composeRule.runOnUiThread { webView.width to webView.height }
        pinchOpen(
            centerX = location[0] + width / 2f,
            centerY = location[1] + height / 3f,
            fromHalfSpan = 40f,
            toHalfSpan = 200f,
        )
        waitForDom("pinch zoom enlarges the hosted page") {
            "window.visualViewport.scale > ${initialScale + 0.1}"
        }
    }

    // WebView zooms on real two-finger touch, so inject trusted multi-pointer events.
    private fun pinchOpen(
        centerX: Float,
        centerY: Float,
        fromHalfSpan: Float,
        toHalfSpan: Float,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val properties =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
                MotionEvent.PointerProperties().apply {
                    id = 1
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )

        fun event(
            action: Int,
            pointerCount: Int,
            halfSpan: Float,
        ) {
            val coords =
                Array(2) { index ->
                    MotionEvent.PointerCoords().apply {
                        x = centerX + if (index == 0) -halfSpan else halfSpan
                        y = centerY
                        pressure = 1f
                        size = 1f
                    }
                }
            val motion =
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    action,
                    pointerCount,
                    properties,
                    coords,
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0,
                )
            automation.injectInputEvent(motion, true)
            motion.recycle()
        }

        val secondPointerDown = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        event(MotionEvent.ACTION_DOWN, 1, fromHalfSpan)
        SystemClock.sleep(TOUCH_EVENT_INTERVAL_MS)
        event(MotionEvent.ACTION_POINTER_DOWN or secondPointerDown, 2, fromHalfSpan)
        repeat(PINCH_STEPS) { step ->
            SystemClock.sleep(TOUCH_EVENT_INTERVAL_MS)
            event(
                MotionEvent.ACTION_MOVE,
                2,
                fromHalfSpan + ((toHalfSpan - fromHalfSpan) * (step + 1) / PINCH_STEPS),
            )
        }
        SystemClock.sleep(TOUCH_EVENT_INTERVAL_MS)
        event(MotionEvent.ACTION_POINTER_UP or secondPointerDown, 2, toHalfSpan)
        SystemClock.sleep(TOUCH_EVENT_INTERVAL_MS)
        event(MotionEvent.ACTION_UP, 1, toHalfSpan)
    }

    @Test
    fun webShellSettingsButtonOpensNativeSettingsAndBackReturnsToWebShell() {
        openWebShell()

        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-settings").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("gomode-web-open-settings").assertDoesNotExist()
        composeRule.onNodeWithTag("gomode-voice-connect").assertDoesNotExist()
        if (InstrumentationRegistry.getArguments().getString("captureVoicePanel") == "true") {
            UiDevice
                .getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand("screencap -p /data/local/tmp/gomode-settings.png")
        }
        pressActivityBack()

        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("gomode-web-shell").fetchSemanticsNodes().isNotEmpty()
        }
        waitForSettingsButton()
        composeRule.onNodeWithTag("gomode-voice-connect").assertExists()
    }

    @Test
    @LocalEmulatorOnly
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

        // Aliases are unique: a conflicting alias blocks Done until it changes.
        composeRule.onAllNodesWithContentDescription("Edit service").onFirst().performClick()
        waitForServiceEditor()
        fillServiceField("gomode-service-label", "second")
        composeRule.onNodeWithText("Another service uses this alias.").assertExists()
        composeRule.onNodeWithTag("gomode-service-done").performScrollTo().assertIsNotEnabled()
        fillServiceField("gomode-service-label", "First renamed")
        closeServiceEditor()
        composeRule.onNodeWithText("First renamed").assertExists()

        // URLs are unique after normalization: a duplicate blocks creation.
        composeRule.onNodeWithTag("gomode-add-service").performScrollTo().performClick()
        waitForServiceEditor()
        fillServiceField("gomode-service-label", "Third")
        fillServiceField("gomode-service-url", "$baseUrl/")
        composeRule.onNodeWithText("Another service uses this URL.").assertExists()
        composeRule.onNodeWithTag("gomode-create-service").performScrollTo().assertIsNotEnabled()
        dismissServiceKeyboard()
        composeRule.onNodeWithTag("gomode-cancel-service").performScrollTo().performClick()

        // Deleting asks for confirmation, then removes only that service.
        composeRule.onAllNodesWithContentDescription("Delete service").onFirst().performClick()
        composeRule.onNodeWithTag("gomode-confirm-delete-service").performClick()
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodesWithText("First renamed").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("Second").assertExists()
        val observer = SettingsRepository(composeRule.activity.goModeSettingsDataStore)
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            observer.settings.value.services
                .map { it.label } == listOf("Second")
        }
    }

    @Test
    fun backFlushesAPendingServiceEditBeforeClosingSettings() {
        openWebShell()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        addService("Second")
        waitForSettingsButton()
        val observer = SettingsRepository(composeRule.activity.goModeSettingsDataStore)
        composeRule.waitUntil(
            conditionDescription = "second service is saved",
            timeoutMillis = GOMODE_DEFAULT_TIMEOUT_MS,
        ) { observer.settings.value.services.size == 2 }
        val activeServiceId = observer.settings.value.activeServiceId
        val first =
            observer.settings.value.services
                .first()
        composeRule.onNodeWithTag("gomode-web-open-settings").performClick()
        composeRule.onNodeWithTag("gomode-edit-service-${first.id}").performClick()
        composeRule.waitUntil(
            conditionDescription = "service editor shows the saved label",
            timeoutMillis = GOMODE_DEFAULT_TIMEOUT_MS,
        ) {
            composeRule
                .onNodeWithTag("gomode-service-label")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text == first.label
        }
        // Freeze the autosave debounce so the edit is still pending when the screen
        // closes. Typing through semantics keeps the soft keyboard out of the test:
        // its appearance and dismissal are device state, not app state.
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag("gomode-service-label").performTextReplacement("Pending name")
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(
                "the edit stays pending until the screen closes",
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
            pressActivityBack()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitUntil(
            conditionDescription = "back flushes the pending service edit",
            timeoutMillis = GOMODE_DEFAULT_TIMEOUT_MS,
        ) {
            observer.settings.value.services
                .first()
                .label == "Pending name" && observer.settings.value.activeServiceId == activeServiceId
        }
        waitForSettingsButton()
        composeRule.waitUntil(
            conditionDescription = "back closes the settings screen",
            timeoutMillis = GOMODE_DEFAULT_TIMEOUT_MS,
        ) {
            composeRule.onAllNodesWithTag("gomode-settings").fetchSemanticsNodes().isEmpty()
        }
    }

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
        composeRule.onNodeWithTag("gomode-shell-retry").assertContentDescriptionEquals("Retry service")
        composeRule.onNodeWithTag("gomode-shell-open-settings").assertContentDescriptionEquals("Settings")
        UiDevice
            .getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("screencap -p /data/local/tmp/gomode-service-recovery.png")
        composeRule.onNodeWithTag("gomode-shell-retry").performClick()
        waitForHostedFrontend()
        assertNotSame(second, waitForWebView())
        waitForDom("Renderer recovery creates a new document") { "typeof window.retainedMarker === 'undefined'" }
    }

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

    @Test
    fun gomodePackageNameIsAppSpecific() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.fghbuild.gomode", context.packageName)
    }

    private fun addService(label: String) {
        composeRule.onNodeWithTag("gomode-add-service").performScrollTo().performClick()
        waitForServiceEditor()
        fillServiceField("gomode-service-label", label)
        // The fixture serves every path; a distinct path keeps service URLs unique.
        fillServiceField("gomode-service-url", "$baseUrl/${label.lowercase().replace(' ', '-')}")
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
        composeRule.onNodeWithTag("gomode-service-done").performScrollTo().performClick()
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
        private const val PINCH_STEPS = 24
        private const val TOUCH_EVENT_INTERVAL_MS = 16L
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
