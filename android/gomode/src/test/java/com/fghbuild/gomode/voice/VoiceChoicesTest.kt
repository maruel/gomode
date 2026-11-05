// Tests native gateway choice filtering, credential boundaries, removed selections, and setup ownership.
package com.fghbuild.gomode.voice

import android.webkit.CookieManager
import com.caic.voicegateway.sdk.v1.ApiClient
import com.caic.voicegateway.sdk.v1.VoiceChoice
import com.caic.voicegateway.sdk.v1.VoiceChoiceDefault
import com.caic.voicegateway.sdk.v1.VoiceChoicesResp
import com.caic.voicegateway.sdk.v1.VoiceRTCDiagnosticsReq
import com.caic.voicegateway.sdk.v1.VoiceTransport
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.sdk.v1.Settings
import com.fghbuild.gomode.sdk.v1.VoiceGatewaySettings
import com.fghbuild.gomode.sdk.v1.WebShellSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    @Test
    fun `empty proxy responses explain gateway availability and preserve HTTP status`() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                val serviceURL = server.url("/").toString()
                val serviceSettings =
                    Settings(
                        "fixture",
                        apiVersion = 1,
                        webShell = WebShellSettings(1, emptyList(), VoiceGatewaySettings(false, serviceURL)),
                    )
                for ((status, message) in listOf(
                    502 to
                        "Voice gateway unavailable (HTTP 502).",
                    403 to "Voice access denied (HTTP 403).",
                )) {
                    server.enqueue(MockResponse().setResponseCode(status))
                    val error =
                        try {
                            discoverVoiceChoices(serviceURL, serviceSettings) { null }
                            throw AssertionError("Expected gateway failure")
                        } catch (error: java.io.IOException) {
                            error
                        }
                    assertEquals(message, error.message)
                }
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `missing diagnostics session is distinct from a missing gateway`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse().setResponseCode(404).setBody(
                        """{"error":{"code":"NOT_FOUND","message":"voice session not found"}}""",
                    ),
                )
                val client = ApiClient(server.url("/").toString().trimEnd('/'), httpClient = voiceGatewayHTTPClient)
                val error =
                    try {
                        client.diagnoseVoiceRTC("expired", VoiceRTCDiagnosticsReq())
                        throw AssertionError("Expected expired session")
                    } catch (error: java.io.IOException) {
                        error
                    }
                assertEquals(
                    "Voice session not found (HTTP 404).",
                    error.message,
                )
            }
        }

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
                listOf(
                    VoiceChoiceDefault(VoiceTransport.Text, "remote"),
                    VoiceChoiceDefault(VoiceTransport.WebRTC, "live"),
                ),
            ),
        )
    private val settings = SettingsState(loadState = SettingsLoadState.Ready)

    private fun audioCatalog(voices: List<String>?) =
        GatewayVoiceChoices(
            gateway,
            VoiceChoicesResp(
                listOf(VoiceChoice("live", "Gemini Live", listOf(VoiceTransport.WebRTC), voices)),
                listOf(VoiceChoiceDefault(VoiceTransport.WebRTC, "live")),
            ),
        )

    private val voiceFixtures =
        Json
            .parseToJsonElement(
                checkNotNull(javaClass.getResource("/selection.json")).readText(),
            ).jsonObject

    @Test
    fun `shared voice selection contract`() {
        for (entry in voiceFixtures.getValue("selection").jsonArray) {
            val row = entry.jsonObject
            val name = row.getValue("name").jsonPrimitive.content
            val error = row["error"]?.jsonPrimitive?.content
            val result =
                runCatching {
                    val choice =
                        Json.decodeFromJsonElement(
                            VoiceChoice.serializer(),
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("live"),
                                    "label" to JsonPrimitive("Gemini Live"),
                                    "transports" to JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive("webrtc"))),
                                ) + row.getValue("catalog").jsonObject,
                            ),
                        )
                    val catalog = GatewayVoiceChoices(gateway, VoiceChoicesResp(listOf(choice), emptyList()))
                    val saved = row["savedVoice"]?.jsonPrimitive?.content
                    val mode = if (row["mode"]?.jsonPrimitive?.content == "text") VoiceMode.DEVICE else VoiceMode.CLOUD
                    val preferences = if (saved == null) emptyMap() else mapOf(gateway to mapOf("live" to saved))
                    catalog.resolve(settings.copy(voiceMode = mode, speakingVoices = preferences)).voiceName
                }
            when (error) {
                "catalog" -> {
                    assertTrue(
                        name,
                        result.exceptionOrNull() is SerializationException ||
                            result.exceptionOrNull() is VoiceSetupException,
                    )
                }

                "voice" -> {
                    assertTrue(name, result.exceptionOrNull() is VoiceSetupException)
                }

                else -> {
                    assertEquals(name, row.getValue("voice").jsonPrimitive.content, result.getOrThrow())
                }
            }
        }
    }

    @Test
    fun `recovery uses pinned choice and voice after advertised defaults change`() {
        val original = audioCatalog(listOf("Kore", "Orus"))
        val saved = settings.copy(speakingVoices = mapOf(gateway to mapOf("live" to "Orus")))
        val pinned = original.resolve(saved)
        assertThrows(VoiceSetupException::class.java) {
            original.copy(gatewayURL = "https://other.example").resolve(settings, pinned)
        }
        val oldChoice = original.catalog.choices.single()
        val alternative = oldChoice.copy(id = "alternative")
        val updated =
            original.copy(
                catalog =
                    VoiceChoicesResp(
                        listOf(oldChoice, alternative),
                        listOf(VoiceChoiceDefault(VoiceTransport.WebRTC, "alternative")),
                    ),
            )
        val recovered = updated.resolve(settings, pinned)
        assertEquals("live", recovered.choiceID)
        assertEquals("Orus", recovered.voiceName)
        assertEquals("alternative", updated.resolve(settings).choiceID)
        assertEquals("", updated.resolve(settings).voiceName)
        val incompatible = oldChoice.copy(transports = listOf(VoiceTransport.Text))
        assertThrows(VoiceSetupException::class.java) {
            updated
                .copy(
                    catalog =
                        VoiceChoicesResp(
                            listOf(incompatible, alternative),
                            listOf(VoiceChoiceDefault(VoiceTransport.WebRTC, "alternative")),
                        ),
                ).resolve(settings, pinned)
        }
        assertThrows(VoiceSetupException::class.java) {
            updated
                .copy(
                    catalog =
                        VoiceChoicesResp(
                            listOf(alternative),
                            listOf(VoiceChoiceDefault(VoiceTransport.WebRTC, "alternative")),
                        ),
                ).resolve(settings, pinned)
        }
    }

    @Test
    fun `default and saved choices respect the speech transport`() {
        assertEquals(listOf("live", "remote"), choices.compatible(settings).map { it.id })
        assertEquals("live", choices.selection(settings).id)
        val device = settings.copy(voiceMode = VoiceMode.DEVICE)
        assertEquals(listOf("remote", "local"), choices.compatible(device).map { it.id })
        assertEquals("remote", choices.selection(device).id)
        val saved = device.copy(voiceChoices = mapOf(gateway to mapOf("text" to "local")))
        assertEquals("local", choices.selection(saved).id)
        assertEquals("live", choices.selection(saved.copy(voiceMode = VoiceMode.CLOUD)).id)
        val both = settings.copy(voiceChoices = mapOf(gateway to mapOf("text" to "local", "webrtc" to "remote")))
        assertEquals("remote", choices.selection(both).id)
        assertEquals("local", choices.selection(both.copy(voiceMode = VoiceMode.DEVICE)).id)
    }

    @Test
    fun `removed selection and unreadable preferences block a new session`() {
        assertThrows(VoiceSetupException::class.java) {
            choices.selection(settings.copy(voiceChoices = mapOf(gateway to mapOf("webrtc" to "removed"))))
        }
        assertThrows(VoiceSetupException::class.java) { choices.selection(SettingsState()) }
        assertThrows(VoiceSetupException::class.java) {
            choices.selection(settings.copy(loadState = SettingsLoadState.Failed("read failed")))
        }
    }

    @Test
    fun `invalid family defaults are rejected`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString().trimEnd('/')
                val service =
                    Settings(
                        "fixture",
                        apiVersion = 1,
                        webShell = WebShellSettings(1, emptyList(), VoiceGatewaySettings(false, base)),
                    )
                for (defaults in listOf(
                    """[{"transport":"webrtc","id":"missing"}]""",
                    """[{"transport":"text","id":"live"}]""",
                    """[{"transport":"webrtc","id":"live"},{"transport":"webrtc","id":"remote"}]""",
                    """[{"transport":"","id":"live"}]""",
                    """[{"transport":"webrtc","id":""}]""",
                )) {
                    server.enqueue(
                        MockResponse().setBody(
                            """{"choices":[{"id":"live","label":"Gemini Live","transports":["webrtc"]}],""" +
                                """"defaults":$defaults}""",
                        ),
                    )
                    val error =
                        try {
                            discoverVoiceChoices(base, service) { null }
                            null
                        } catch (error: VoiceSetupException) {
                            error
                        }
                    assertEquals("Gateway returned invalid voice choices", error?.message)
                }
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
            ensureVoiceSettingsCurrent(
                captured,
                captured.copy(voiceChoices = mapOf(gateway to mapOf("webrtc" to "remote"))),
            )
        }
        assertThrows(VoiceSetupException::class.java) {
            ensureVoiceSettingsCurrent(
                captured,
                captured.copy(speakingVoices = mapOf(gateway to mapOf("live" to "Kore"))),
            )
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
                """"defaults":[{"transport":"webrtc","id":"live"}]}"""
    }
}
