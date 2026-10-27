// Tests native frontend voice dispatch, page ownership, and session cancellation.

package com.fghbuild.gomode.ui.web

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FrontendVoiceToolsTest {
    private val tools = FrontendVoiceTools()
    private lateinit var view: ToolWebView

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        view = ToolWebView()
        tools.register(
            view,
            """[{"name":"select_item","description":"Select item","parameters":{}}]""",
        )
    }

    @After
    fun tearDown() {
        tools.clear(view)
        view.destroy()
        Dispatchers.resetMain()
    }

    @Test
    fun ownedReloadRevokesPageAccessAndSettlesPendingCallsBeforeReloading() =
        runTest {
            val entry = ServiceShellSession(ServiceShellKey("source", "https://service.test")) {}
            entry.bearerStore.onNavigation(entry.key.url)
            entry.bearerStore.receive(Uri.parse(entry.key.url), true, entry.key.url, """{"bearerToken":"account"}""")
            entry.updateLoad(WebShellLoadState.Ready)
            completeFrontendPreparation(entry)
            val identity = entry.bearerStore.identityEpoch.value
            entry.frontendTools.register(
                view,
                """[{"name":"select_item","description":"Select item","parameters":{}}]""",
            )
            view.deferResult = true
            val request =
                async(Dispatchers.Unconfined) {
                    entry.frontendTools.execute("select_item", JsonObject(emptyMap())) { true }
                }
            assertFalse(request.isCompleted)
            var reloads = 0
            entry.reloadPage {
                reloads++
                assertFalse(entry.bearerStore.authReady.value)
                assertNull(entry.bearerStore.tokenFor(entry.key.url))
                assertTrue(request.isCompleted)
                assertEquals(identity, entry.bearerStore.identityEpoch.value)
            }
            assertTrue(requireNotNull(request.await()).containsKey("error"))
            view.callback?.onReceiveValue("{\"selected\":true}")
            assertTrue(requireNotNull(request.await()).containsKey("error"))
            entry.close()
            entry.reloadPage { reloads++ }
            assertEquals(1, reloads)
        }

    @Test
    fun dispatchesToOwningPageAndReturnsItsResult() =
        runTest {
            val result = tools.execute("select_item", JsonObject(emptyMap())) { true }
            assertEquals("{\"selected\":true}", result.toString())
            assertEquals(true, view.script.contains("select_item"))
            assertNull(tools.execute("unknown", JsonObject(emptyMap())) { true })
        }

    @Test
    fun cancelledAttemptCannotExecuteFrontendCode() =
        runTest {
            val result = tools.execute("select_item", JsonObject(emptyMap())) { false }
            assertEquals(true, result?.containsKey("error"))
            assertEquals("", view.script)
        }

    @Test
    fun resultFromPreviousPageCannotSucceedAfterRegistrationChanges() =
        runTest {
            view.deferResult = true
            val request = async { tools.execute("select_item", JsonObject(emptyMap())) { true } }
            runCurrent()
            tools.clear(view)
            tools.register(view, "[]")
            view.callback?.onReceiveValue("{\"selected\":true}")
            assertEquals(true, request.await()?.containsKey("error"))
        }

    @Test
    fun navigationRemovesToolsAndOldPageCannotClearNewOwner() =
        runTest {
            val newer = ToolWebView()
            try {
                tools.register(newer, """[{"name":"new_item","description":"New item","parameters":{}}]""")
                tools.clear(view)
                assertEquals(listOf("new_item"), tools.declarations.map { it.name })
                assertNull(tools.execute("select_item", JsonObject(emptyMap())) { true })
                tools.clear(newer)
            } finally {
                newer.destroy()
            }
        }

    @Test
    fun independentRegistrationsNeverDispatchToAnotherService() =
        runTest {
            val otherView = ToolWebView()
            val otherTools = FrontendVoiceTools()
            try {
                otherTools.register(otherView, """[{"name":"select_item","description":"Other","parameters":{}}]""")
                tools.execute("select_item", JsonObject(emptyMap())) { true }
                assertEquals("", otherView.script)
                otherTools.execute("select_item", JsonObject(emptyMap())) { true }
                assertEquals(true, otherView.script.contains("select_item"))
                tools.clear(view)
                assertEquals(listOf("select_item"), otherTools.declarations.map { it.name })
            } finally {
                otherTools.clear()
                otherView.destroy()
            }
        }

    @Test
    fun identityChangeCompletesPendingCallBeforeOldPageReturns() =
        runTest {
            view.deferResult = true
            val request = async { tools.execute("select_item", JsonObject(emptyMap())) { true } }
            runCurrent()
            tools.invalidate()
            runCurrent()
            assertEquals(true, request.isCompleted)
            assertEquals(true, request.await()?.containsKey("error"))
            view.callback?.onReceiveValue("{\"selected\":true}")
            assertEquals(true, request.await()?.containsKey("error"))
        }

    @Test
    fun revokedIdentityCannotExecutePreviouslyRegisteredTools() =
        runTest {
            var authorized = true
            val guarded = FrontendVoiceTools { authorized }
            guarded.register(view, """[{"name":"select_item","description":"Select","parameters":{}}]""")
            authorized = false
            guarded.invalidate()
            assertEquals(true, guarded.execute("select_item", JsonObject(emptyMap())) { true }?.containsKey("error"))
            assertEquals("", view.script)
            guarded.clear()
        }

    @Test
    fun navigationCannotDeliverArgumentsOrResultsAcrossTheRegisteredOrigin() =
        runTest {
            view.pageURL = "https://foreign.test"
            assertTrue(checkNotNull(tools.execute("select_item", JsonObject(emptyMap())) { true }).containsKey("error"))
            assertEquals("", view.script)
            view.pageURL = "https://service.test"
            view.deferResult = true
            val pending = async { tools.execute("select_item", JsonObject(emptyMap())) { true } }
            runCurrent()
            view.pageURL = "https://foreign.test"
            view.callback?.onReceiveValue("{\"selected\":true}")
            assertTrue(checkNotNull(pending.await()).containsKey("error"))
        }

    @Test
    fun frontendCannotOverrideNativeVoiceTools() {
        for (name in listOf("hang_up", "list_servers", "switch_server")) {
            val error =
                runCatching {
                    tools.register(view, """[{"name":"$name","description":"Override","parameters":{}}]""")
                }.exceptionOrNull()
            assertEquals(true, error is IllegalArgumentException)
        }
        assertEquals(listOf("select_item"), tools.declarations.map { it.name })
    }

    private class ToolWebView : WebView(RuntimeEnvironment.getApplication()) {
        var pageURL = "https://service.test"
        var script = ""
        var deferResult = false
        var callback: ValueCallback<String>? = null

        override fun getUrl(): String = pageURL

        override fun evaluateJavascript(
            script: String,
            resultCallback: ValueCallback<String>?,
        ) {
            this.script = script
            callback = resultCallback
            if (!deferResult) resultCallback?.onReceiveValue("{\"selected\":true}")
        }
    }
}
