// Exercises authenticated preparation across actual WebView documents, identities, and subframes.
package com.fghbuild.gomode

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.WebShellLoadState
import com.fghbuild.gomode.ui.web.WebShellScreen
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FrontendPreparationBridgeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @StandaloneHostedFixture
    fun documentStartOwnershipSurvivesLoadLinkAndReloadWithoutAcceptingOldSignals() {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher()
            server.start()
            val entry = ServiceShellSession(ServiceShellKey("prepared", server.url("/").toString())) {}
            compose.setContent {
                WebShellScreen(voiceConnected = false, reloadToken = 0, serviceSession = entry, onAuthInvalidated = {})
            }
            lateinit var view: WebView
            compose.runOnIdle { view = entry.page(compose.activity).webView }
            try {
                awaitAuthenticated(entry, view)
                assertFalse(entry.frontendPreparation.ready.value)
                evaluate(view, "publishReady(true)")
                compose.waitUntil { entry.frontendPreparation.ready.value }
                assertTrue(entry.frontendTools.declarations.isEmpty())
                val navigations = listOf("document.querySelector('a').click()", "location.reload()", "native-load")
                for (navigation in navigations) {
                    val oldDocument = evaluate(view, "capturedDocument")
                    val oldSignal = signal(view)
                    if (navigation == "native-load") {
                        compose.runOnUiThread { view.loadUrl(server.url("/native").toString()) }
                    } else {
                        evaluate(view, navigation)
                    }
                    compose.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                        evaluate(view, "window.authAcknowledged === true") == "true" &&
                            evaluate(view, "capturedDocument") != oldDocument &&
                            entry.state.value.load == WebShellLoadState.Ready
                    }
                    assertTrue(entry.bearerStore.authReady.value)
                    assertFalse(entry.frontendPreparation.ready.value)
                    evaluate(view, "gomodeFrontendReadiness.postMessage(${JSONObject.quote(oldSignal)})")
                    compose.waitForIdle()
                    assertFalse(entry.frontendPreparation.ready.value)
                    evaluate(view, "publishReady(true)")
                    compose.waitUntil { entry.frontendPreparation.ready.value }
                }
                evaluate(view, "tools=[{name:'fixture',description:'Fixture',parameters:{}}]; publishReady(true)")
                compose.waitUntil { entry.frontendTools.declarations.size == 1 }
                val priorAuth = entry.bearerStore.authEpoch.value
                evaluate(
                    view,
                    """
                    for (const bad of [undefined,null,'',' ',123,true,'x'.repeat(129)]) {
                      for (const auth of [{authChanging:true},{authChanged:true,nativeAccess:false},{bearerToken:'invalid'}]) {
                        gomodeAuth.postMessage(JSON.stringify({...auth,frontendDocument:capturedDocument,frontendRequest:bad}));
                      }
                    }
                    publishReady(true);
                    """.trimIndent(),
                )
                compose.waitForIdle()
                assertEquals(priorAuth, entry.bearerStore.authEpoch.value)
                assertTrue(entry.frontendPreparation.ready.value)
                assertEquals(1, entry.frontendTools.declarations.size)
                val priorIdentity = signal(view)
                val document = evaluate(view, "capturedDocument")
                evaluate(view, "authenticate()")
                compose.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                    evaluate(view, "window.authAcknowledged === true") == "true" && signal(view) != priorIdentity
                }
                assertEquals(document, evaluate(view, "capturedDocument"))
                assertFalse(entry.frontendPreparation.ready.value)
                assertTrue(entry.frontendTools.declarations.isEmpty())
                evaluate(view, "gomodeFrontendReadiness.postMessage(${JSONObject.quote(priorIdentity)})")
                compose.waitForIdle()
                assertFalse(entry.frontendPreparation.ready.value)
                evaluate(view, "publishReady(true)")
                compose.waitUntil { entry.frontendPreparation.ready.value }
                evaluate(view, "publishReady(false)")
                compose.waitUntil { !entry.frontendPreparation.ready.value }
                assertTrue(entry.frontendTools.declarations.isEmpty())
                evaluate(view, "authenticate({bearerToken:'same-account'})")
                compose.waitUntil { evaluate(view, "window.authAcknowledged === true") == "true" }
                evaluate(view, "publishReady(true)")
                compose.waitUntil { entry.frontendTools.declarations.size == 1 }
                val sameBearerEpoch = entry.bearerStore.authEpoch.value
                val sameBearerSignal = signal(view)
                evaluate(view, "authenticate({bearerToken:'same-account'})")
                compose.waitUntil {
                    evaluate(view, "window.authAcknowledged === true") == "true" &&
                        signal(view) != sameBearerSignal
                }
                assertEquals(sameBearerEpoch, entry.bearerStore.authEpoch.value)
                assertFalse(entry.frontendPreparation.ready.value)
                assertTrue(entry.frontendTools.declarations.isEmpty())
            } finally {
                compose.runOnUiThread { entry.close() }
            }
        }
    }

    @Test
    @StandaloneHostedFixture
    fun failedRendererReloadRevokesThePreparedDocumentWithoutAReplacementHandshake() {
        MockWebServer().use { server ->
            val failing = AtomicBoolean(false)
            val hosted = dispatcher()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (failing.get()) {
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        } else {
                            hosted.dispatch(request)
                        }
                }
            server.start()
            val entry = ServiceShellSession(ServiceShellKey("prepared", server.url("/").toString())) {}
            compose.setContent {
                WebShellScreen(voiceConnected = false, reloadToken = 0, serviceSession = entry, onAuthInvalidated = {})
            }
            lateinit var view: WebView
            compose.runOnIdle { view = entry.page(compose.activity).webView }
            try {
                awaitAuthenticated(entry, view)
                evaluate(view, "tools=[{name:'fixture',description:'Fixture',parameters:{}}]; publishReady(true)")
                compose.waitUntil { entry.frontendTools.declarations.size == 1 }
                failing.set(true)
                evaluate(view, "location.reload()")
                compose.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                    entry.state.value.load is WebShellLoadState.Failed ||
                        entry.state.value.load == WebShellLoadState.Reconnecting
                }
                assertFalse(entry.bearerStore.authReady.value)
                assertFalse(entry.frontendPreparation.ready.value)
                assertTrue(entry.frontendTools.declarations.isEmpty())
                failing.set(false)
                compose.runOnUiThread { view.loadUrl(server.url("/recovered").toString()) }
                awaitAuthenticated(entry, view)
                assertFalse(entry.frontendPreparation.ready.value)
                evaluate(view, "publishReady(true)")
                compose.waitUntil { entry.frontendPreparation.ready.value }
            } finally {
                compose.runOnUiThread { entry.close() }
            }
        }
    }

    @Test
    @StandaloneHostedFixture
    fun subframesAndUntrustedOriginsCannotPrepareTheMainFrame() {
        MockWebServer().use { server ->
            MockWebServer().use { foreign ->
                server.dispatcher = dispatcher()
                foreign.dispatcher = dispatcher()
                server.start()
                foreign.start()
                val entry = ServiceShellSession(ServiceShellKey("prepared", server.url("/").toString())) {}
                compose.setContent {
                    WebShellScreen(
                        voiceConnected = false,
                        reloadToken = 0,
                        serviceSession = entry,
                        onAuthInvalidated = {},
                    )
                }
                lateinit var view: WebView
                compose.runOnIdle { view = entry.page(compose.activity).webView }
                try {
                    awaitAuthenticated(entry, view)
                    for (origin in listOf(server, foreign)) {
                        val url =
                            origin
                                .url(
                                    "/frame",
                                ).newBuilder()
                                .addQueryParameter("signal", signal(view))
                                .build()
                                .toString()
                        val quotedURL = JSONObject.quote(url)
                        evaluate(
                            view,
                            "frameSent=null; var frame=document.createElement('iframe'); " +
                                "frame.src=$quotedURL; document.body.append(frame)",
                        )
                        compose.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { evaluate(view, "frameSent !== null") == "true" }
                        assertEquals(if (origin === server) "true" else "false", evaluate(view, "frameSent"))
                        assertFalse(entry.frontendPreparation.ready.value)
                    }
                    evaluate(view, "publishReady(true)")
                    compose.waitUntil { entry.frontendPreparation.ready.value }

                    // Neither native loadUrl, history back, nor form POST is
                    // guaranteed to visit shouldOverrideUrlLoading.
                    fun prepareService(path: String) {
                        compose.runOnUiThread { view.loadUrl(server.url(path).toString()) }
                        awaitAuthenticated(entry, view)
                        evaluate(
                            view,
                            "tools=[{name:'fixture',description:'Fixture',parameters:{}}]; publishReady(true)",
                        )
                        compose.waitUntil { entry.frontendPreparation.ready.value }
                        assertEquals(1, entry.frontendTools.declarations.size)
                    }

                    fun awaitForeign() {
                        compose.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                            entry.state.value.load == WebShellLoadState.Ready &&
                                evaluate(view, "location.port") == JSONObject.quote(foreign.port.toString())
                        }
                        assertFalse(entry.bearerStore.authReady.value)
                        assertFalse(entry.frontendPreparation.ready.value)
                        assertTrue(entry.frontendTools.declarations.isEmpty())
                    }
                    compose.runOnUiThread { view.loadUrl(foreign.url("/untrusted").toString()) }
                    awaitForeign()
                    prepareService("/after-foreign")
                    evaluate(view, "history.back()")
                    awaitForeign()
                    prepareService("/before-post")
                    val postURL = JSONObject.quote(foreign.url("/untrusted").toString())
                    evaluate(
                        view,
                        "var form=document.createElement('form'); form.method='post'; " +
                            "form.action=$postURL; document.body.append(form); form.submit()",
                    )
                    awaitForeign()
                    prepareService("/after-post")
                } finally {
                    compose.runOnUiThread { entry.close() }
                }
            }
        }
    }

    private fun awaitAuthenticated(
        entry: ServiceShellSession,
        view: WebView,
    ) {
        compose.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
            entry.state.value.load == WebShellLoadState.Ready && entry.bearerStore.authReady.value &&
                evaluate(view, "window.authAcknowledged === true") == "true"
        }
    }

    private fun signal(view: WebView): String {
        val serialized =
            evaluate(view, "JSON.stringify({document:capturedDocument,token:capturedToken,ready:true,tools:[]})")
        return JSONArray("[$serialized]").getString(0)
    }

    private fun evaluate(
        view: WebView,
        script: String,
    ): String {
        val done = CountDownLatch(1)
        var result = ""
        compose.runOnUiThread {
            view.evaluateJavascript(script) {
                result = it
                done.countDown()
            }
        }
        check(done.await(5, TimeUnit.SECONDS)) { "WebView script did not finish" }
        return result
    }

    private fun dispatcher(): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setHeader("Content-Type", "text/html").setBody(
                    if (request.path == "/untrusted") {
                        "<html><body>Untrusted main frame</body></html>"
                    } else if (request.path?.startsWith("/frame") == true) {
                        """<script>
                    const available = typeof gomodeFrontendReadiness === 'object';
                    if (available) gomodeFrontendReadiness.postMessage(new URL(location.href).searchParams.get('signal'));
                    parent.postMessage({frameSent:available}, '*');
                    </script>"""
                    } else {
                        """<a href="/linked">Navigate</a><script>
                    let capturedDocument = null, capturedToken = null, request = 0, tools = [];
                    window.authAcknowledged = false; window.frameSent = null;
                    addEventListener('message', event => { if ('frameSent' in event.data) frameSent = event.data.frameSent; });
                    function publishReady(ready) {
                      gomodeFrontendReadiness.postMessage(JSON.stringify({document:capturedDocument,token:capturedToken,ready,tools:ready?tools:[]}));
                    }
                    function authenticate(payload) {
                      window.authAcknowledged = false;
                      const frontendRequest = String(++request);
                      gomodeAuth.onmessage = event => {
                        const value = JSON.parse(event.data);
                        if (value.frontendDocument !== capturedDocument || value.frontendRequest !== frontendRequest) return;
                        capturedToken = value.frontendToken; window.authAcknowledged = true;
                      };
                      const auth = payload || {authChanged:true,nativeAccess:true};
                      gomodeAuth.postMessage(JSON.stringify({...auth,frontendDocument:capturedDocument,frontendRequest}));
                    }
                    const binding = goModeFrontendDocument;
                    binding.then(value => { capturedDocument = value; authenticate(); });
                    </script>"""
                    },
                )
        }
}
