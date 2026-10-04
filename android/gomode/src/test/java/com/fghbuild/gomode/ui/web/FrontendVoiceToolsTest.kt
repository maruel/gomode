// Tests native frontend voice dispatch, page ownership, and session cancellation.

package com.fghbuild.gomode.ui.web

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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FrontendVoiceToolsTest {
    private lateinit var view: ToolWebView

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        view = ToolWebView()
        FrontendVoiceTools.register(
            view,
            """[{"name":"select_item","description":"Select item","parameters":{}}]""",
        )
    }

    @After
    fun tearDown() {
        FrontendVoiceTools.clear(view)
        view.destroy()
        Dispatchers.resetMain()
    }

    @Test
    fun dispatchesToOwningPageAndReturnsItsResult() =
        runTest {
            val result = FrontendVoiceTools.execute("select_item", JsonObject(emptyMap())) { true }
            assertEquals("{\"selected\":true}", result.toString())
            assertEquals(true, view.script.contains("select_item"))
            assertNull(FrontendVoiceTools.execute("unknown", JsonObject(emptyMap())) { true })
        }

    @Test
    fun cancelledAttemptCannotExecuteFrontendCode() =
        runTest {
            val result = FrontendVoiceTools.execute("select_item", JsonObject(emptyMap())) { false }
            assertEquals(true, result?.containsKey("error"))
            assertEquals("", view.script)
        }

    @Test
    fun resultFromPreviousPageCannotSucceedAfterRegistrationChanges() =
        runTest {
            view.deferResult = true
            val request = async { FrontendVoiceTools.execute("select_item", JsonObject(emptyMap())) { true } }
            runCurrent()
            FrontendVoiceTools.clear(view)
            FrontendVoiceTools.register(view, "[]")
            view.callback?.onReceiveValue("{\"selected\":true}")
            assertEquals(true, request.await()?.containsKey("error"))
        }

    @Test
    fun navigationRemovesToolsAndOldPageCannotClearNewOwner() =
        runTest {
            val newer = ToolWebView()
            try {
                FrontendVoiceTools.register(newer, """[{"name":"new_item","description":"New item","parameters":{}}]""")
                FrontendVoiceTools.clear(view)
                assertEquals(listOf("new_item"), FrontendVoiceTools.declarations.map { it.name })
                assertNull(FrontendVoiceTools.execute("select_item", JsonObject(emptyMap())) { true })
                FrontendVoiceTools.clear(newer)
            } finally {
                newer.destroy()
            }
        }

    private class ToolWebView : WebView(RuntimeEnvironment.getApplication()) {
        var script = ""
        var deferResult = false
        var callback: ValueCallback<String>? = null

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
