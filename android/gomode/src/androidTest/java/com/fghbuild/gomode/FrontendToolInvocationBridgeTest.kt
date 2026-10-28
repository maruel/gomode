// Exercises correlated asynchronous frontend completion and cancellation through real WebView messages.
package com.fghbuild.gomode

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.WebShellScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FrontendToolInvocationBridgeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @StandaloneHostedFixture
    fun delayedConcurrentResultsRejectForgedAndDuplicateCompletions() =
        fixture { entry, view, scope ->
            val first = invoke(entry, scope, """{"defer":true,"index":1}""")
            compose.waitUntil { evaluate(view, "calls.length") == "1" }
            val serialized = evaluate(view, "JSON.stringify(calls[0])")
            val firstCall = JSONArray("[$serialized]").getString(0)
            val forged = JSONObject(firstCall).put("result", JSONObject().put("forged", true)).toString()
            evaluate(
                view,
                "document.querySelector('iframe').contentWindow.gomodeFrontendToolResult.postMessage(" +
                    JSONObject.quote(forged) + ")",
            )
            compose.waitForIdle()
            assertFalse(first.isCompleted)
            MockWebServer().use { foreign ->
                foreign.enqueue(
                    MockResponse().setHeader("Content-Type", "text/html").setBody(
                        """<script>addEventListener('message',e=>{
                    const available=typeof gomodeFrontendToolResult!=='undefined';
                    if(available)gomodeFrontendToolResult.postMessage(e.data);
                    parent.postMessage({attempted:true,available},'*');});</script>""",
                    ),
                )
                foreign.start()
                val url = JSONObject.quote(foreign.url("/").toString())
                evaluate(
                    view,
                    """
                    window.foreignAttempted=false; window.foreignAvailable=null;
                    addEventListener('message',e=>{if(e.data.attempted){foreignAttempted=true;foreignAvailable=e.data.available;}});
                    const foreignFrame=document.createElement('iframe');foreignFrame.src=$url;
                    foreignFrame.onload=()=>foreignFrame.contentWindow.postMessage(${JSONObject.quote(forged)},'*');
                    document.body.append(foreignFrame);
                    """.trimIndent(),
                )
                compose.waitUntil { evaluate(view, "foreignAttempted") == "true" }
                assertEquals("false", evaluate(view, "foreignAvailable"))
                assertFalse(first.isCompleted)
            }
            val second = invoke(entry, scope, """{"defer":true,"index":2}""")
            compose.waitUntil { evaluate(view, "calls.length") == "2" }
            evaluate(view, "resolveCall(1,{index:2})")
            await(second)
            assertEquals("{\"index\":2}", runBlocking { second.await() }.toString())
            assertFalse(first.isCompleted)
            evaluate(
                view,
                "gomodeFrontendToolResult.postMessage(JSON.stringify({...calls[1],result:{duplicate:true}}))",
            )
            compose.waitForIdle()
            assertFalse(first.isCompleted)
            evaluate(view, "resolveCall(0,{index:1})")
            await(first)
            assertEquals("{\"index\":1}", runBlocking { first.await() }.toString())
            val sync = invoke(entry, scope, "{}")
            await(sync)
            assertEquals("{\"sync\":true}", runBlocking { sync.await() }.toString())
            val rejected = invoke(entry, scope, """{"reject":true}""")
            await(rejected)
            assertEquals("{\"error\":\"Rejected\"}", runBlocking { rejected.await() }.toString())
            val invalid = invoke(entry, scope, """{"invalid":true}""")
            await(invalid)
            assertEquals(
                "{\"error\":\"Frontend voice tool returned an invalid result.\"}",
                runBlocking { invalid.await() }.toString(),
            )
        }

    @Test
    @StandaloneHostedFixture
    fun cancellationTimeoutIdentityReloadAndDisposalRetirePendingCalls() =
        fixture { entry, view, scope ->
            val canceled = invoke(entry, scope, """{"defer":true}""")
            compose.waitUntil { evaluate(view, "calls.length") == "1" }
            canceled.cancel()
            compose.waitUntil { evaluate(view, "cancellations") == "1" }
            evaluate(view, "resolveCall(0,{late:true})")
            assertTrue(canceled.isCancelled)
            val timed = invoke(entry, scope, """{"defer":true}""")
            await(timed)
            assertEquals("{\"error\":\"Frontend voice tool timed out.\"}", runBlocking { timed.await() }.toString())
            compose.waitUntil { evaluate(view, "cancellations") == "2" }
            evaluate(view, "lastCall = null")
            for (replace in listOf("replaceScope()", "authenticate()", "location.reload()")) {
                val pending = invoke(entry, scope, """{"defer":true}""")
                compose.waitUntil { evaluate(view, "window.lastCall !== null") == "true" }
                evaluate(view, replace)
                await(pending)
                assertTrue(checkNotNull(runBlocking { pending.await() }).containsKey("error"))
                compose.waitUntil { entry.isFrontendReady }
                evaluate(view, "lastCall = null")
            }
            val disposed = invoke(entry, scope, """{"defer":true}""")
            compose.waitUntil { evaluate(view, "lastCall !== null") == "true" }
            compose.runOnUiThread { entry.close() }
            await(disposed)
            assertTrue(checkNotNull(runBlocking { disposed.await() }).containsKey("error"))
        }

    @Test
    @StandaloneHostedFixture
    fun rendererLossReleasesOutstandingFrontendInvocation() =
        fixture { entry, view, scope ->
            val pending = invoke(entry, scope, """{"defer":true}""")
            compose.waitUntil { evaluate(view, "lastCall !== null") == "true" }
            compose.runOnUiThread { entry.rendererLost() }
            await(pending)
            assertTrue(checkNotNull(runBlocking { pending.await() }).containsKey("error"))
        }

    private fun invoke(
        entry: ServiceShellSession,
        scope: CoroutineScope,
        args: String,
    ) = scope.async {
        entry.frontendTools.execute("fixture", Json.parseToJsonElement(args).jsonObject) { true }
    }

    private fun await(call: Deferred<JsonObject?>) {
        compose.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) { call.isCompleted }
    }

    private fun fixture(block: (ServiceShellSession, WebView, CoroutineScope) -> Unit) {
        MockWebServer().use { server ->
            server.dispatcher =
                object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                        MockResponse().setHeader("Content-Type", "text/html").setBody(
                            if (request.path ==
                                "/frame"
                            ) {
                                "<p>Frame</p>"
                            } else {
                                PAGE
                            },
                        )
                }
            server.start()
            val entry = ServiceShellSession(ServiceShellKey("async", server.url("/").toString())) {}
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            compose.setContent {
                WebShellScreen(voiceConnected = false, reloadToken = 0, serviceSession = entry, onAuthInvalidated = {})
            }
            lateinit var view: WebView
            compose.runOnIdle { view = entry.page(compose.activity).webView }
            try {
                compose.waitUntil(GOMODE_DEFAULT_TIMEOUT_MS) {
                    entry.isFrontendReady &&
                        evaluate(view, "frameReady") == "true"
                }
                block(entry, view, scope)
            } finally {
                scope.cancel()
                compose.runOnUiThread { entry.close() }
            }
        }
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
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result
    }

    private companion object {
        const val PAGE = """
        <!doctype html><html><body><iframe src='/frame' onload='frameReady=true'></iframe><script>
        let frameReady=false, documentID=null, token=null, scopeID=0, request=0;
        let calls=[], resolvers=[], cancellations=0, lastCall=null;
        const binding=window.goModeFrontendDocument;
        function publish() {
          gomodeFrontendReadiness.postMessage(JSON.stringify({document:documentID,token,scope:String(scopeID),ready:true,
            tools:[{name:'fixture',description:'Fixture',parameters:{type:'object'}}]}));
        }
        function authenticate() {
          binding.then(document => {
            documentID=document; const id=String(++request);
            gomodeAuth.onmessage=e=>{const ack=JSON.parse(e.data); if(ack.frontendRequest!==id)return;
              token=ack.frontendToken; scopeID++; publish();};
            gomodeAuth.postMessage(JSON.stringify({frontendDocument:document,frontendRequest:id,authChanged:true,nativeAccess:true}));
          });
        }
        function replaceScope() { scopeID++; publish(); }
        const owns=call=>call.document===documentID&&call.token===token&&call.scope===String(scopeID);
        window.dispatchGoModeVoiceTool=call=>{
          if(!owns(call))return;
          lastCall=call; const index=calls.push(call)-1;
          const controller=new AbortController(); call.controller=controller;
          const work=call.args.reject?Promise.reject(new Error('Rejected')):
            call.args.defer?new Promise(resolve=>resolvers[index]=resolve):Promise.resolve(call.args.invalid?[]:{sync:true});
          work.then(result=>{if(owns(call)&&!controller.signal.aborted)
            gomodeFrontendToolResult.postMessage(JSON.stringify({...call,controller:undefined,result}));})
            .catch(error=>gomodeFrontendToolResult.postMessage(JSON.stringify({...call,controller:undefined,result:{error:error.message}})));
        };
        window.cancelGoModeVoiceTool=call=>{
          const pending=calls.find(value=>value.invocation===call.invocation);
          if(pending&&owns(call)){pending.controller.abort();cancellations++;}
        };
        function resolveCall(index,result) { resolvers[index](result); }
        authenticate();
        </script></body></html>
        """
    }
}
