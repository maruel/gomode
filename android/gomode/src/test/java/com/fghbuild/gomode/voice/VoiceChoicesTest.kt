// Tests gateway setup resolution with the web rules, credential boundaries, removed selections, and setup ownership.
package com.fghbuild.gomode.voice

import android.webkit.CookieManager
import com.fghbuild.gomode.data.GatewayVoiceSetup
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.SavedVoiceSetup
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.SpeechStageLocation
import com.fghbuild.gomode.data.VoiceLanguageModel
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.data.VoiceSetupPreference
import com.fghbuild.gomode.sdk.v1.Settings
import com.fghbuild.gomode.sdk.v1.VoiceGatewaySettings
import com.fghbuild.gomode.sdk.v1.WebShellSettings
import com.fghbuild.voicegateway.sdk.v2.ApiClient
import com.fghbuild.voicegateway.sdk.v2.SpeechConfig
import com.fghbuild.voicegateway.sdk.v2.SpeechLocation
import com.fghbuild.voicegateway.sdk.v2.SpeechModel
import com.fghbuild.voicegateway.sdk.v2.VoiceChoice
import com.fghbuild.voicegateway.sdk.v2.VoiceChoiceDefault
import com.fghbuild.voicegateway.sdk.v2.VoiceChoicesResp
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCDiagnosticsReq
import com.fghbuild.voicegateway.sdk.v2.VoiceTransport
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
    private val allInOne = VoiceSetupPreference(mode = VoiceSetupMode.ALL_IN_ONE)
    private val deviceStages = VoiceSetupPreference(mode = VoiceSetupMode.SEPARATE_STAGES)
    private val gatewayStages =
        VoiceSetupPreference(mode = VoiceSetupMode.SEPARATE_STAGES, recognition = GATEWAY, speech = GATEWAY)

    // A composable language model beside a fixed text preset and a native audio model.
    private val composable =
        GatewayVoiceChoices(
            gateway,
            VoiceChoicesResp(
                listOf(
                    VoiceChoice("live", "Gemini Live", listOf(VoiceTransport.WebRTC)),
                    VoiceChoice("preset", "Preset", listOf(VoiceTransport.Text)),
                    VoiceChoice(
                        "llm",
                        "Language model",
                        listOf(VoiceTransport.Text, VoiceTransport.WebRTC),
                        speechModes =
                            listOf(
                                SpeechConfig(SpeechLocation.Client, SpeechLocation.Client),
                                SpeechConfig(SpeechLocation.Client, SpeechLocation.Gateway),
                                SpeechConfig(SpeechLocation.Gateway, SpeechLocation.Client),
                                SpeechConfig(SpeechLocation.Gateway, SpeechLocation.Gateway),
                            ),
                        composable = true,
                    ),
                ),
                listOf(VoiceChoiceDefault(VoiceTransport.WebRTC, "live")),
                recognitionModels = listOf(SpeechModel("whisper", "Whisper"), SpeechModel("parakeet", "Parakeet")),
                synthesisModels = listOf(SpeechModel("kokoro", "Kokoro")),
            ),
        )

    private fun SettingsState.with(setup: VoiceSetupPreference) = copy(voiceSetup = setup)

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
                    val setup =
                        if (row["mode"]?.jsonPrimitive?.content == "text") {
                            VoiceSetupPreference(mode = VoiceSetupMode.SEPARATE_STAGES)
                        } else {
                            VoiceSetupPreference()
                        }
                    val preferences = if (saved == null) emptyMap() else mapOf(gateway to mapOf("live" to saved))
                    catalog
                        .resolve(
                            settings.copy(voiceSetup = setup, speakingVoices = preferences),
                            pinned = null,
                        ).voiceName
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
        val pinned = original.resolve(saved, pinned = null)
        assertThrows(VoiceSetupException::class.java) {
            original.copy(gatewayURL = "https://other.example").resolve(settings, pinned)
        }
        val oldChoice = requireNotNull(original.catalog.choices).single()
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
        assertEquals("alternative", updated.resolve(settings, pinned = null).choiceID)
        assertEquals("", updated.resolve(settings, pinned = null).voiceName)
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
    fun `modes list native models or language models and transports follow the stage owners`() {
        assertEquals(listOf("live"), choices.setupChoices(VoiceSetupKind.ALL_IN_ONE).map { it.id })
        assertEquals(listOf("remote", "local"), choices.setupChoices(VoiceSetupKind.SEPARATE_STAGES).map { it.id })
        assertEquals(
            listOf("remote"),
            choices.compatibleChoices(VoiceSetupKind.SEPARATE_STAGES, GATEWAY, GATEWAY).map { it.id },
        )
        assertEquals(VoiceTransport.Text, transportFor(DEVICE, DEVICE))
        assertEquals(VoiceTransport.WebRTC, transportFor(DEVICE, GATEWAY))
        assertEquals(VoiceTransport.WebRTC, transportFor(GATEWAY, DEVICE))
        // Composable language models replace fixed text presets in separate stages.
        assertEquals(listOf("llm"), composable.setupChoices(VoiceSetupKind.SEPARATE_STAGES).map { it.id })
    }

    @Test
    fun `a mode switch restores the other mode's selection`() {
        val saved =
            settings.copy(
                gatewayVoiceSetups = mapOf(gateway to GatewayVoiceSetup(separateStagesChoiceID = "local")),
            )
        assertEquals("live", choices.resolveSetup(saved.with(allInOne)).choice.id)
        val device = choices.resolveSetup(saved.with(deviceStages))
        assertEquals("local", device.choice.id)
        assertEquals(VoiceTransport.Text, device.transport)
        assertEquals("live", choices.resolveSetup(saved.with(allInOne)).choice.id)
        // Without a saved selection, each mode uses its transport's advertised default.
        assertEquals("remote", choices.resolveSetup(settings.with(deviceStages)).choice.id)
    }

    @Test
    fun `following the gateway, as migrated cloud voice, resolves the kind of the saved gateway speech choice`() {
        val native = choices.resolveSetup(settings)
        assertEquals("live", native.choice.id)
        val indivisible =
            choices.resolveSetup(
                settings.copy(gatewayVoiceSetups = mapOf(gateway to GatewayVoiceSetup(followedChoiceID = "remote"))),
            )
        assertEquals(GATEWAY, indivisible.speech)
        assertEquals("remote", indivisible.choice.id)
        val llm =
            composable.resolveSetup(
                settings.copy(gatewayVoiceSetups = mapOf(gateway to GatewayVoiceSetup(followedChoiceID = "llm"))),
            )
        assertEquals(GATEWAY, llm.recognition)
        assertEquals("llm", llm.choice.id)
    }

    @Test
    fun `following the gateway becomes the explicit setup it resolves to and keeps the other mode's selections`() {
        val device = deviceStages.copy(deviceSpeechRate = 1.5f)

        fun saved(gatewaySetup: GatewayVoiceSetup) =
            SavedVoiceSetup(device.copy(mode = VoiceSetupMode.FOLLOW_GATEWAY), gatewaySetup, LocalLlmModel.GEMMA_4_E4B)

        // A followed language model shows as separate gateway stages and pins itself there.
        val stages = saved(GatewayVoiceSetup(followedChoiceID = "llm", allInOneChoiceID = "live"))
        val separate = composable.explicit(stages)
        assertEquals(
            gatewayStages.copy(deviceSpeechRate = 1.5f),
            separate.preference,
        )
        assertEquals(
            GatewayVoiceSetup(followedChoiceID = "llm", allInOneChoiceID = "live", separateStagesChoiceID = "llm"),
            separate.gateway,
        )
        assertEquals(LocalLlmModel.GEMMA_4_E4B, separate.localLlmModel)
        assertEquals("llm", composable.resolveSetup(separate.preference, separate.gateway).choice.id)

        // The gateway's WebRTC default decides a setup that followed nothing yet; the stages stay saved.
        val native = composable.explicit(saved(GatewayVoiceSetup(separateStagesChoiceID = "preset")))
        assertEquals(device.copy(mode = VoiceSetupMode.ALL_IN_ONE), native.preference)
        assertEquals(
            GatewayVoiceSetup(allInOneChoiceID = "live", separateStagesChoiceID = "preset"),
            native.gateway,
        )

        // A removed followed choice becomes the explicit choice, so explicit resolution reports it.
        val removed = composable.explicit(saved(GatewayVoiceSetup(followedChoiceID = "gone")))
        assertEquals(VoiceSetupMode.SEPARATE_STAGES, removed.preference.mode)
        assertEquals("gone", removed.gateway.separateStagesChoiceID)
        val unavailable =
            assertThrows(VoiceSetupException::class.java) {
                composable.resolveSetup(removed.preference, removed.gateway)
            }
        assertEquals("Saved voice choice is unavailable. Select a voice choice in Settings.", unavailable.message)

        val explicit = SavedVoiceSetup(gatewayStages, GatewayVoiceSetup(), LocalLlmModel.GEMMA_4_E2B)
        assertEquals(explicit, composable.explicit(explicit))
    }

    @Test
    fun `a removed saved choice blocks a new session but another mode's choice falls back`() {
        for (setup in listOf(VoiceSetupPreference(), allInOne, deviceStages)) {
            val error =
                assertThrows(VoiceSetupException::class.java) {
                    choices.resolve(
                        settings.with(setup).copy(
                            gatewayVoiceSetups =
                                mapOf(
                                    gateway to
                                        GatewayVoiceSetup(
                                            followedChoiceID = "removed",
                                            allInOneChoiceID = "removed",
                                            separateStagesChoiceID = "removed",
                                        ),
                                ),
                        ),
                        pinned = null,
                    )
                }
            assertEquals("Saved voice choice is unavailable. Select a voice choice in Settings.", error.message)
        }
        val otherMode =
            settings.with(deviceStages).copy(
                gatewayVoiceSetups = mapOf(gateway to GatewayVoiceSetup(separateStagesChoiceID = "live")),
            )
        assertEquals("remote", choices.resolveSetup(otherMode).choice.id)
        assertThrows(VoiceSetupException::class.java) { choices.resolveSetup(SettingsState()) }
        assertThrows(VoiceSetupException::class.java) {
            choices.resolveSetup(settings.copy(loadState = SettingsLoadState.Failed("read failed")))
        }
    }

    @Test
    fun `a choice without the selected stage owners is unsupported`() {
        val textOnly =
            composable.copy(
                catalog =
                    composable.catalog.copy(
                        choices =
                            composable.catalog.choices.orEmpty().map {
                                it.copy(
                                    speechModes = listOf(SpeechConfig(SpeechLocation.Client, SpeechLocation.Client)),
                                )
                            },
                    ),
            )
        val error =
            assertThrows(VoiceSetupException::class.java) {
                textOnly.resolveSetup(
                    settings.with(gatewayStages).copy(
                        gatewayVoiceSetups = mapOf(gateway to GatewayVoiceSetup(separateStagesChoiceID = "llm")),
                    ),
                )
            }
        assertEquals(
            "This model does not support the selected recognition and speech. Choose another in Settings.",
            error.message,
        )
        val empty =
            assertThrows(VoiceSetupException::class.java) {
                choices.resolveSetup(
                    settings.with(
                        allInOne.copy(mode = VoiceSetupMode.SEPARATE_STAGES, recognition = DEVICE, speech = GATEWAY),
                    ),
                )
            }
        assertEquals(
            "The voice gateway has no model for this setup. Choose another setup in Settings.",
            empty.message,
        )
    }

    @Test
    fun `composable gateway stages resolve saved, first, or missing speech models`() {
        val resolved = composable.resolveSetup(settings.with(gatewayStages))
        assertEquals("whisper", resolved.recognitionModelID)
        assertEquals("kokoro", resolved.synthesisModelID)
        val saved =
            settings.with(gatewayStages).copy(
                gatewayVoiceSetups =
                    mapOf(gateway to GatewayVoiceSetup(recognitionModelID = "parakeet", synthesisModelID = "removed")),
            )
        val removed = assertThrows(VoiceSetupException::class.java) { composable.resolveSetup(saved) }
        assertEquals(
            "The selected speech model is unavailable. Choose another speech model in Settings.",
            removed.message,
        )
        val unlisted =
            composable.copy(catalog = composable.catalog.copy(recognitionModels = null))
        val missing =
            assertThrows(VoiceSetupException::class.java) { unlisted.resolveSetup(settings.with(gatewayStages)) }
        assertEquals(
            "The gateway has no recognition models. Choose Local for recognition, or an all-in-one model in Settings.",
            missing.message,
        )
        // Device-owned stages need no gateway model, and the text transport carries them.
        val device = unlisted.resolveSetup(settings.with(deviceStages))
        assertNull(device.recognitionModelID)
        assertNull(device.synthesisModelID)
        assertEquals("llm", unlisted.resolve(settings.with(deviceStages), pinned = null).choiceID)
    }

    @Test
    fun `mixed stage owners and saved gateway models pin the session setup across recovery`() {
        val mixed =
            settings.with(gatewayStages.copy(speech = DEVICE)).copy(
                gatewayVoiceSetups = mapOf(gateway to GatewayVoiceSetup(recognitionModelID = "parakeet")),
            )
        val pinned = composable.resolve(mixed, pinned = null)
        assertEquals(
            GatewayVoiceSelection(
                gateway,
                "llm",
                "",
                GATEWAY,
                DEVICE,
                recognitionModelID = "parakeet",
                synthesisModelID = null,
            ),
            pinned,
        )
        assertEquals(VoiceTransport.WebRTC, pinned.transport)
        val deviceRecognition =
            composable.resolve(
                settings.with(gatewayStages.copy(recognition = DEVICE)),
                pinned = null,
            )
        assertEquals(DEVICE to GATEWAY, deviceRecognition.recognition to deviceRecognition.speech)
        assertEquals(null to "kokoro", deviceRecognition.recognitionModelID to deviceRecognition.synthesisModelID)
        // Recovery keeps the pinned owners and models even after the saved setup changes.
        assertEquals(pinned, composable.resolve(settings.with(gatewayStages), pinned))
        val withdrawnModel =
            composable.copy(catalog = composable.catalog.copy(recognitionModels = listOf(SpeechModel("whisper", "W"))))
        assertEquals(
            "Session speech model is unavailable. Start a new voice session.",
            assertThrows(VoiceSetupException::class.java) { withdrawnModel.resolve(mixed, pinned) }.message,
        )
        val withdrawnMode =
            composable.copy(
                catalog =
                    composable.catalog.copy(
                        choices =
                            composable.catalog.choices.orEmpty().map {
                                it.copy(speechModes = it.speechModes?.filter { mode -> mode.input == mode.output })
                            },
                    ),
            )
        assertEquals(
            "Session voice choice is unavailable. Start a new voice session.",
            assertThrows(VoiceSetupException::class.java) { withdrawnMode.resolve(mixed, pinned) }.message,
        )
        // An indivisible language model with gateway speech still connects over WebRTC.
        val indivisible =
            settings.with(gatewayStages).copy(
                gatewayVoiceSetups = mapOf(gateway to GatewayVoiceSetup(separateStagesChoiceID = "remote")),
            )
        assertEquals("remote", choices.resolve(indivisible, pinned = null).choiceID)
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
        VoiceLanguageModel.entries.filter { it != VoiceLanguageModel.GATEWAY }.forEach {
            val device = settings.with(deviceStages.copy(languageModel = it))
            assertFalse(usesGatewayVoice(device))
            assertTrue(device.voiceSetup.deviceSpeech)
            // The other modes keep the language model for a later switch back.
            assertTrue(usesGatewayVoice(settings.with(device.voiceSetup.copy(mode = VoiceSetupMode.ALL_IN_ONE))))
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
                captured.copy(gatewayVoiceSetups = mapOf(gateway to GatewayVoiceSetup(allInOneChoiceID = "remote"))),
            )
        }
        assertThrows(VoiceSetupException::class.java) {
            ensureVoiceSettingsCurrent(
                captured,
                captured.copy(speakingVoices = mapOf(gateway to mapOf("live" to "Kore"))),
            )
        }
        assertThrows(VoiceSetupException::class.java) {
            ensureVoiceSettingsCurrent(captured, captured.with(deviceStages))
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
                assertEquals("/api/voicegateway/v2/voice/choices", embedded.path)
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
            "wss://gateway.example/api/voicegateway/v2/voice/text?choiceID=local%26other%3Dvalue",
            textSessionWebSocketURL(gateway, "local&other=value"),
        )
    }

    companion object {
        private val DEVICE = SpeechStageLocation.DEVICE
        private val GATEWAY = SpeechStageLocation.GATEWAY
        private const val CATALOG =
            """{"choices":[{"id":"live","label":"Gemini Live","transports":["webrtc"]}],""" +
                """"defaults":[{"transport":"webrtc","id":"live"}]}"""
    }
}
