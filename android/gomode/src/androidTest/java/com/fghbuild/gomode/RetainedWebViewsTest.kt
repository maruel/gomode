// Emulator regressions for retained hidden-page authentication, frontend tools, focus, and prompt ownership.
package com.fghbuild.gomode

import android.Manifest
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellRegistry
import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.WebShellLoadState
import com.fghbuild.gomode.ui.web.WebShellScreen
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class RetainedWebViewsTest {
    @get:Rule(order = 0)
    val mediaPermissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun hiddenPagePublishesAuthAndToolsWithoutTakingFocusOrOpeningDialogs() {
        MockWebServer().use { firstServer ->
            MockWebServer().use { secondServer ->
                val dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            MockResponse().setBody("<html><body>Retained fixture</body></html>")
                    }
                firstServer.dispatcher = dispatcher
                secondServer.dispatcher = dispatcher
                firstServer.start()
                secondServer.start()
                verifyRetainedPages(firstServer.url("/").toString(), secondServer.url("/").toString())
            }
        }
    }

    @Test
    fun hiddenUserActivatedPageCancelsBeforeUnloadWithoutOpeningNativeUi() {
        MockWebServer().use { server ->
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse()
                            .setHeader("Content-Type", "text/html")
                            .setBody(
                                """
                                <html><body><button id="edit" style="width:220px;height:80px;margin-top:80px">Edit draft</button>
                                <script>
                                window.editCount = 0;
                                window.beforeUnloadAttempts = 0;
                                document.querySelector('#edit').addEventListener('click', event => {
                                  window.trustedActivation = event.isTrusted && navigator.userActivation.hasBeenActive;
                                  window.draft = 'unsaved';
                                  if (++window.editCount === 1) window.addEventListener('beforeunload', event => {
                                    window.beforeUnloadAttempts++;
                                    event.preventDefault();
                                    event.returnValue = '';
                                  });
                                });
                                </script></body></html>
                                """.trimIndent(),
                            )
                }
            server.start()
            val entry = ServiceShellSession(ServiceShellKey("draft", server.url("/").toString()))
            var visible by mutableStateOf(true)
            composeRule.setContent {
                WebShellScreen(
                    serviceID = entry.key.serviceID,
                    initialURL = entry.key.url,
                    voiceConnected = false,
                    visible = visible,
                    reloadToken = 0,
                    serviceSession = entry,
                    onLoadStateChanged = entry::updateLoad,
                    onAuthInvalidated = {},
                )
            }
            val view = visibleWebView()
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                evaluate(view, "document.readyState === 'complete' && Boolean(document.querySelector('#edit'))") ==
                    "true"
            }
            tapEditButton(view)
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                evaluate(view, "window.trustedActivation === true") == "true"
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            try {
                composeRule.runOnIdle { visible = false }
                composeRule.waitForIdle()
                assertFalse(composeRule.runOnUiThread { view.isAttachedToWindow })
                assertEquals("false", evaluate(view, "goModeHost.isPageVisible()"))
                composeRule.runOnUiThread { view.evaluateJavascript("location.href = '/next'", null) }
                composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                    entry.state.value.attentionRequired || device.hasObject(By.res("android", "button1"))
                }
                assertFalse(
                    "Hidden navigation opened a native confirmation",
                    device.hasObject(By.res("android", "button1")),
                )
                assertTrue(entry.state.value.attentionRequired)
                assertEquals("true", evaluate(view, "location.pathname === '/' && window.draft === 'unsaved'"))
                assertEquals("1", evaluate(view, "window.beforeUnloadAttempts"))
                composeRule.runOnUiThread { view.reload() }
                composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                    evaluate(view, "window.beforeUnloadAttempts === 2") == "true" ||
                        device.hasObject(By.res("android", "button1"))
                }
                assertFalse(
                    "Hidden reload opened a native confirmation",
                    device.hasObject(By.res("android", "button1")),
                )
                assertEquals("true", evaluate(view, "location.pathname === '/' && window.draft === 'unsaved'"))
                composeRule.runOnIdle { visible = true }
                composeRule.waitForIdle()
                tapEditButton(view)
                composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                    evaluate(view, "window.editCount === 2 && window.trustedActivation") == "true"
                }
            } finally {
                if (device.hasObject(By.res("android", "button1"))) device.pressBack()
            }
        }
    }

    private fun tapEditButton(view: WebView) {
        val target =
            JSONArray(
                evaluate(
                    view,
                    """
                    (() => {
                      const rect = document.querySelector('#edit').getBoundingClientRect();
                      return [rect.left + rect.width / 2, rect.top + rect.height / 2, innerWidth, innerHeight];
                    })()
                    """.trimIndent(),
                ),
            )
        val bounds =
            composeRule.runOnUiThread {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                intArrayOf(location[0], location[1], view.width, view.height)
            }
        assertTrue(
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).click(
                bounds[0] + (target.getDouble(0) * bounds[2] / target.getDouble(2)).roundToInt(),
                bounds[1] + (target.getDouble(1) * bounds[3] / target.getDouble(3)).roundToInt(),
            ),
        )
    }

    @Test
    fun hidingPageStopsRemovedCaptureTracksAndBothKindsOfClone() {
        MockWebServer().use { server ->
            server.dispatcher = lifecycleDispatcher()
            server.start()
            val entry = ServiceShellSession(ServiceShellKey("capture", server.url("/").toString()))
            var visible by mutableStateOf(true)
            composeRule.setContent {
                WebShellScreen(
                    serviceID = entry.key.serviceID,
                    initialURL = entry.key.url,
                    voiceConnected = false,
                    visible = visible,
                    reloadToken = 0,
                    serviceSession = entry,
                    onLoadStateChanged = entry::updateLoad,
                    onAuthInvalidated = {},
                )
            }
            val view = visibleWebView()
            waitForFixture(view)
            evaluate(
                view,
                """
                window.captureError = null;
                navigator.mediaDevices.getUserMedia({audio:true}).then(stream => {
                  const removed = stream.getTracks()[0];
                  const cloneStream = stream.clone();
                  const removedClone = cloneStream.getTracks()[0];
                  stream.removeTrack(removed);
                  cloneStream.removeTrack(removedClone);
                  const cloneTrack = removed.clone();
                  const nestedClone = new MediaStream([cloneTrack]).clone().getTracks()[0];
                  window.captureTracks = [removed, removedClone, cloneTrack, nestedClone];
                }).catch(error => { window.captureError = error.name; });
                true;
                """.trimIndent(),
            )
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                evaluate(view, "Boolean(window.captureTracks) || window.captureError !== null") == "true"
            }
            assertEquals("null", evaluate(view, "window.captureError"))
            assertEquals("true", evaluate(view, "window.captureTracks.every(track => track.readyState === 'live')"))
            composeRule.runOnIdle { visible = false }
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                evaluate(view, "window.captureTracks.every(track => track.readyState === 'ended')") == "true"
            }
        }
    }

    @Test
    fun captureGrantedAfterHidingIsStoppedBeforeThePromiseRejects() {
        MockWebServer().use { server ->
            server.dispatcher = lifecycleDispatcher()
            server.start()
            val entry = ServiceShellSession(ServiceShellKey("pending", server.url("/").toString()))
            var visible by mutableStateOf(true)
            composeRule.setContent {
                WebShellScreen(
                    serviceID = entry.key.serviceID,
                    initialURL = entry.key.url,
                    voiceConnected = false,
                    visible = visible,
                    reloadToken = 0,
                    serviceSession = entry,
                    onLoadStateChanged = entry::updateLoad,
                    onAuthInvalidated = {},
                )
            }
            val view = visibleWebView()
            waitForFixture(view)
            val requested = CountDownLatch(1)
            var pending: PermissionRequest? = null
            composeRule.runOnUiThread {
                view.webChromeClient =
                    object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) {
                            pending = request
                            requested.countDown()
                        }
                    }
            }
            evaluate(
                view,
                """
                window.stoppedTracks = [];
                window.pendingError = null;
                window.pendingResolved = false;
                const stop = MediaStreamTrack.prototype.stop;
                MediaStreamTrack.prototype.stop = function() {
                  stop.call(this);
                  window.stoppedTracks.push(this);
                };
                navigator.mediaDevices.getUserMedia({audio:true}).then(() => {
                  window.pendingResolved = true;
                }).catch(error => { window.pendingError = error.name; });
                true;
                """.trimIndent(),
            )
            assertTrue("Native capture request never started", requested.await(5, TimeUnit.SECONDS))
            composeRule.runOnIdle { visible = false }
            composeRule.runOnUiThread { requireNotNull(pending).grant(requireNotNull(pending).resources) }
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                evaluate(view, "window.pendingError === 'NotAllowedError' && window.stoppedTracks.length > 0") == "true"
            }
            assertEquals("false", evaluate(view, "window.pendingResolved"))
            assertEquals("true", evaluate(view, "window.stoppedTracks.every(track => track.readyState === 'ended')"))
        }
    }

    @Test
    fun liveServiceDeletionAndSharedRendererFailureReleaseRetainedOwners() {
        MockWebServer().use { server ->
            server.dispatcher = lifecycleDispatcher()
            server.start()
            val registry = ServiceShellRegistry()
            val firstKey = ServiceShellKey("first", server.url("/first").toString())
            val secondKey = ServiceShellKey("second", server.url("/second").toString())
            var entries by mutableStateOf(registry.reconcile(setOf(firstKey, secondKey)))
            val first = entries.getValue(firstKey)
            val second = entries.getValue(secondKey)
            var selected by mutableStateOf(first)
            composeRule.setContent {
                DisposableEffect(registry) { onDispose { registry.close() } }
                entries.values.forEach { entry ->
                    key(entry) {
                        WebShellScreen(
                            serviceID = entry.key.serviceID,
                            initialURL = entry.key.url,
                            voiceConnected = false,
                            visible = selected === entry,
                            reloadToken = 0,
                            serviceSession = entry,
                            onLoadStateChanged = entry::updateLoad,
                            onSharedAuthInvalidated = { registry.invalidateSharedOrigin(entry) },
                            onAuthInvalidated = {},
                        )
                    }
                }
            }
            val firstView = visibleWebView()
            waitForFixture(firstView)
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { first.bearerStore.authReady.value }
            var deletedRendererCallbacks = 0
            composeRule.runOnUiThread {
                val client = firstView.webViewClient
                firstView.webViewClient =
                    object : WebViewClient() {
                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail,
                        ): Boolean {
                            deletedRendererCallbacks++
                            return client.onRenderProcessGone(view, detail)
                        }
                    }
                entries = registry.reconcile(setOf(secondKey))
                selected = second
            }
            val secondView = visibleWebView()
            waitForFixture(secondView)
            composeRule.runOnUiThread { assertFalse(firstView.isAttachedToWindow) }
            assertFalse(first.bearerStore.authReady.value)
            assertTrue(first.frontendTools.declarations.isEmpty())
            assertEquals("true", evaluate(secondView, "goModeHost.isPageVisible()"))
            val replacementKey = ServiceShellKey("replacement", server.url("/replacement").toString())
            composeRule.runOnIdle {
                entries = registry.reconcile(setOf(secondKey, replacementKey))
                selected = entries.getValue(replacementKey)
            }
            val replacement = entries.getValue(replacementKey)
            val replacementView = visibleWebView()
            waitForFixture(replacementView)
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                second.bearerStore.authReady.value && replacement.bearerStore.authReady.value
            }
            composeRule.runOnUiThread {
                val renderer = requireNotNull(secondView.webViewRenderProcess)
                assertSame("Fixture views must share a renderer", renderer, replacementView.webViewRenderProcess)
                assertTrue(renderer.terminate())
            }
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                second.state.value.load is WebShellLoadState.Failed &&
                    replacement.state.value.load is WebShellLoadState.Failed
            }
            composeRule.waitForIdle()
            composeRule.runOnUiThread {
                assertFalse(secondView.isAttachedToWindow)
                assertFalse(replacementView.isAttachedToWindow)
                assertEquals("Deleted WebView survived to receive renderer callbacks", 0, deletedRendererCallbacks)
            }
            assertFalse(second.bearerStore.authReady.value)
            assertFalse(replacement.bearerStore.authReady.value)
            assertTrue(second.frontendTools.declarations.isEmpty())
            assertTrue(replacement.frontendTools.declarations.isEmpty())
        }
    }

    private fun lifecycleDispatcher(): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody(
                        """
                        <html><body>Retained fixture<script>
                        gomodeAuth.postMessage('{"authChanged":true,"nativeAccess":true}');
                        gomodeVoiceTools.postMessage('[{"name":"fixture","description":"Fixture","parameters":{}}]');
                        </script></body></html>
                        """.trimIndent(),
                    )
        }

    private fun verifyRetainedPages(
        firstURL: String,
        secondURL: String,
    ) {
        val first = ServiceShellSession(ServiceShellKey("a", firstURL))
        val second = ServiceShellSession(ServiceShellKey("b", secondURL))
        var selected by mutableStateOf(first)
        composeRule.setContent {
            listOf(first, second).forEach { entry ->
                key(entry) {
                    WebShellScreen(
                        serviceID = entry.key.serviceID,
                        initialURL = entry.key.url,
                        voiceConnected = false,
                        visible = selected === entry,
                        reloadToken = 0,
                        serviceSession = entry,
                        onLoadStateChanged = entry::updateLoad,
                        onAuthInvalidated = {},
                    )
                }
            }
        }
        val firstView = visibleWebView()
        waitForFixture(firstView)
        composeRule.runOnIdle { selected = second }
        val secondView = visibleWebView()
        assertNotSame(firstView, secondView)
        waitForFixture(secondView)
        composeRule.runOnUiThread {
            firstView.evaluateJavascript(
                """
                gomodeAuth.postMessage(JSON.stringify({bearerToken:'first-token'}));
                gomodeVoiceTools.postMessage(JSON.stringify([{name:'first_tool', description:'First', parameters:{}}]));
                window.focus();
                alert('hidden prompt');
                """.trimIndent(),
                null,
            )
            secondView.evaluateJavascript(
                "gomodeAuth.postMessage(JSON.stringify({bearerToken:'second-token'}))",
                null,
            )
        }
        try {
            composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                first.bearerStore.authReady.value && second.bearerStore.authReady.value &&
                    first.frontendTools.declarations.isNotEmpty()
            }
        } catch (failure: Exception) {
            throw AssertionError(
                "First ready=${first.bearerStore.authReady.value} state=${first.state.value} " +
                    "tools=${first.frontendTools.declarations.size}; " +
                    "second ready=${second.bearerStore.authReady.value} " +
                    "state=${second.state.value}; first DOM=${evaluate(firstView, "document.body.innerText")}",
                failure,
            )
        }
        composeRule.runOnUiThread {
            assertEquals("first-token", first.bearerStore.tokenFor(first.key.url))
            assertEquals("second-token", second.bearerStore.tokenFor(second.key.url))
            assertEquals(
                "first_tool",
                first.frontendTools.declarations
                    .single()
                    .name,
            )
            assertFalse(firstView.isAttachedToWindow)
            assertFalse(firstView.hasFocus())
            assertTrue(secondView.isAttachedToWindow)
        }
        composeRule.runOnIdle { selected = first }
        assertTrue(firstView === visibleWebView())
    }

    private fun visibleWebView(): WebView {
        composeRule.waitForIdle()
        var view: WebView? = null
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            composeRule.runOnUiThread {
                view = composeRule.activity.findViewById(R.id.web_shell)
            }
            view != null
        }
        return requireNotNull(view)
    }

    private fun evaluate(
        view: WebView,
        script: String,
    ): String {
        val completed = CountDownLatch(1)
        var result = ""
        composeRule.runOnUiThread {
            view.evaluateJavascript(script) {
                result = it
                completed.countDown()
            }
        }
        check(completed.await(5, TimeUnit.SECONDS)) { "JavaScript evaluation did not complete" }
        return result
    }

    private fun waitForFixture(view: WebView) {
        composeRule.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            evaluate(
                view,
                "typeof gomodeAuth === 'object' && document.body.innerText === 'Retained fixture' " +
                    "&& document.readyState === 'complete'",
            ) ==
                "true"
        }
    }
}
