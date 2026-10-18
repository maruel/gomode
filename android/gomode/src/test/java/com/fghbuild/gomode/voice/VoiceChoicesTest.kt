// Tests native gateway choice filtering, credential boundaries, removed selections, and setup ownership.
package com.fghbuild.gomode.voice

import android.webkit.CookieManager
import com.caic.voicegateway.sdk.v1.VoiceChoice
import com.caic.voicegateway.sdk.v1.VoiceChoicesResp
import com.caic.voicegateway.sdk.v1.VoiceTransport
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.sdk.v1.Settings
import com.fghbuild.gomode.sdk.v1.VoiceGatewaySettings
import com.fghbuild.gomode.sdk.v1.WebShellSettings
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceChoicesTest {
    private val gateway = "https://gateway.example"
    private val choices =
        GatewayVoiceChoices(
            gateway,
            VoiceChoicesResp(
                listOf(
                    VoiceChoice("live", "Gemini Live", listOf(VoiceTransport.WebRTC)),
                    VoiceChoice("remote", "Remote LLM", listOf(VoiceTransport.Text, VoiceTransport.WebRTC)),
                    VoiceChoice("local", "Local LLM", listOf(VoiceTransport.Text)),
                ),
                "live",
            ),
        )
    private val settings = SettingsState(loadState = SettingsLoadState.Ready)

    @Test
    fun `default and saved choices respect the speech transport`() {
        assertEquals(listOf("live", "remote"), choices.compatible(settings).map { it.id })
        assertEquals("live", choices.selection(settings).id)
        val device = settings.copy(voiceMode = VoiceMode.DEVICE)
        assertEquals(listOf("remote", "local"), choices.compatible(device).map { it.id })
        assertEquals("remote", choices.selection(device).id)
        val saved = device.copy(voiceChoices = mapOf(gateway to "local"))
        assertEquals("local", choices.selection(saved).id)
        assertEquals("live", choices.selection(saved.copy(voiceMode = VoiceMode.CLOUD)).id)
    }

    @Test
    fun `removed selection and unreadable preferences block a new session`() {
        assertThrows(VoiceSetupException::class.java) {
            choices.selection(settings.copy(voiceChoices = mapOf(gateway to "removed")))
        }
        assertThrows(VoiceSetupException::class.java) { choices.selection(SettingsState()) }
        assertThrows(VoiceSetupException::class.java) {
            choices.selection(settings.copy(loadState = SettingsLoadState.Failed("read failed")))
        }
    }

    @Test
    fun `fully native engines do not use gateway choices`() {
        OnDeviceEngine.entries.filter { it != OnDeviceEngine.REMOTE_GATEWAY }.forEach {
            assertFalse(usesGatewayVoice(settings.copy(voiceMode = VoiceMode.DEVICE, onDeviceEngine = it)))
        }
        assertTrue(usesGatewayVoice(settings))
    }

    @Test
    fun `setup rejects changed service or selection`() {
        val captured = settings.copy(activeServiceId = "one", activeServiceURL = "https://one.example")
        assertThrows(VoiceSetupException::class.java) {
            ensureVoiceSettingsCurrent(captured, captured.copy(activeServiceId = "two"))
        }
        assertThrows(VoiceSetupException::class.java) {
            ensureVoiceSettingsCurrent(captured, captured.copy(voiceChoices = mapOf(gateway to "remote")))
        }
    }

    @Test
    fun `metadata forwards embedded credentials and never foreign origin credentials`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString().trimEnd('/')
                CookieManager.getInstance().setCookie(base, "session=embedded")
                val service =
                    Settings(
                        "fixture",
                        apiVersion = 1,
                        webShell =
                            WebShellSettings(
                                1,
                                emptyList(),
                                VoiceGatewaySettings(false, base),
                            ),
                    )
                repeat(2) { server.enqueue(MockResponse().setBody(CATALOG)) }
                discoverVoiceChoices(base, service) { "embedded-bearer" }
                val embedded = requireNotNull(server.takeRequest())
                assertEquals("/api/voicegateway/v1/voice/choices", embedded.path)
                assertEquals("Bearer embedded-bearer", embedded.getHeader("Authorization"))
                assertEquals("session=embedded", embedded.getHeader("Cookie"))
                discoverVoiceChoices("https://service.example", service) { "must-not-leak" }
                val external = requireNotNull(server.takeRequest())
                assertNull(external.getHeader("Authorization"))
                assertNull(external.getHeader("Cookie"))
            }
        }

    @Test
    fun `text choice query is escaped and cannot inject another parameter`() {
        assertEquals(
            "wss://gateway.example/api/voicegateway/v1/voice/text?choiceID=local%26other%3Dvalue",
            textSessionWebSocketURL(gateway, "local&other=value"),
        )
    }

    companion object {
        private const val CATALOG =
            """{"choices":[{"id":"live","label":"Gemini Live","transports":["webrtc"]}],""" +
                """"defaultChoiceID":"live"}"""
    }
}
