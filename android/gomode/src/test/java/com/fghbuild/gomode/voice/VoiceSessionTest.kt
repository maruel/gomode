// Unit tests for Go Mode voice session diagnostics and service URL resolution.
package com.fghbuild.gomode.voice

import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.SpeechStageLocation
import com.fghbuild.gomode.data.SpeechStageLocation.DEVICE
import com.fghbuild.gomode.data.SpeechStageLocation.GATEWAY
import com.fghbuild.gomode.service.credentialedHTTPClient
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import com.fghbuild.voicegateway.sdk.v2.ApiClient
import com.fghbuild.voicegateway.sdk.v2.SessionSetup
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCConnectivityIssue
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCConnectivitySide
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCDiagnosticsResp
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCOfferReq
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCServerDiagnostics
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class VoiceSessionTest {
    @Test
    fun endingAClientTurnFinalizesItsTextAndKeepsOnlyADeviceRecognitionDraftRevisable() {
        val transcript =
            listOf(
                TranscriptEntry(TranscriptSpeaker.USER, "How is it?"),
                TranscriptEntry(TranscriptSpeaker.ASSISTANT, "Ready."),
                TranscriptEntry(TranscriptSpeaker.USER, "Wai"),
            )
        assertEquals(listOf(true, true, false), transcript.withFinalTurnText(keepUserDraft = true).map { it.final })
        assertEquals(listOf(true, true, true), transcript.withFinalTurnText(keepUserDraft = false).map { it.final })
    }

    @Test
    fun gatewayClientDoesNotForwardCookieAcrossRedirects() =
        runBlocking {
            val service = MockWebServer()
            val other = MockWebServer()
            service.start()
            other.start()
            try {
                service.enqueue(
                    MockResponse()
                        .setResponseCode(307)
                        .setHeader("Location", other.url("/stolen")),
                )
                other.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                val client = ApiClient(service.url("/").toString(), httpClient = credentialedHTTPClient)

                assertTrue(
                    runCatching {
                        client.voiceRTCOffer(
                            VoiceRTCOfferReq(sdp = "offer"),
                            headers = mapOf("Cookie" to "session=secret"),
                        )
                    }.isFailure,
                )

                assertEquals("session=secret", service.takeRequest().getHeader("Cookie"))
                assertEquals(null, other.takeRequest(200, TimeUnit.MILLISECONDS))
            } finally {
                service.shutdown()
                other.shutdown()
            }
        }

    @Test
    fun voiceToolDeclarationsPreserveNativeAndServiceCommands() {
        val tools =
            voiceToolDeclarations(
                nativeVoiceTools(SettingsState()) +
                    ToolDescriptor(name = "tasks_list", description = "List tasks", inputSchema = null),
            )

        assertEquals(listOf("hang_up", "mute", "tasks_list"), tools.map { it.name })
        assertTrue(tools.first().description.contains("End the current voice conversation"))
        assertTrue(
            tools.all {
                requireNotNull(it.parameters)
                    .jsonObject["type"]
                    ?.jsonPrimitive
                    ?.content == "object"
            },
        )
    }

    @Test
    fun sessionSetupPreservesServerInstructionsForGatewayPolicy() {
        val setup =
            gatewaySessionSetup(
                tools = emptyList(),
                systemInstruction = "system prompt",
                serviceContextText = "Current service items:\n- Build (running)",
                languageTag = "en-US",
            )
        val instruction = requireNotNull(setup.context.systemInstruction)

        assertEquals("system prompt", instruction)
        assertEquals("en-US", setup.voice.language)
        assertEquals("Current service items:\n- Build (running)", setup.context.text)
    }

    @Test
    fun sessionSetupLeavesCommonPolicyToGatewayWithoutServerInstructions() {
        val setup =
            gatewaySessionSetup(
                tools = emptyList(),
                systemInstruction = "",
                serviceContextText = "",
                languageTag = "en-US",
            )
        val instruction = requireNotNull(setup.context.systemInstruction)

        assertEquals("", instruction)
    }

    @Test
    fun sessionSetupSerializesSelectedVoiceAndOmitsProviderDefault() {
        val selected =
            gatewaySessionSetup(
                emptyList(),
                "",
                "",
                "fr-CA",
                includeReasoning = true,
                voice = selection(voice = "Kore"),
            )
        val payload = Json.parseToJsonElement(Json.encodeToString(SessionSetup.serializer(), selected)).jsonObject
        assertEquals("true", payload.getValue("includeReasoning").jsonPrimitive.content)
        assertEquals(
            "Kore",
            payload
                .getValue("voice")
                .jsonObject
                .getValue("name")
                .jsonPrimitive.content,
        )
        assertEquals("fr-CA", selected.voice.language)
        val providerDefault = gatewaySessionSetup(emptyList(), "", "", "en-US", voice = selection())
        val defaultPayload = Json.parseToJsonElement(Json.encodeToString(SessionSetup.serializer(), providerDefault))
        assertFalse(defaultPayload.jsonObject.containsKey("includeReasoning"))
        assertFalse(
            defaultPayload.jsonObject
                .getValue("voice")
                .jsonObject
                .containsKey("name"),
        )
    }

    // The gateway rejects a model ID for a client-owned stage, so each frame names only gateway models.
    @Test
    fun webRtcSessionSetupNamesStageOwnersAndOnlyGatewayModels() {
        val cases =
            listOf(
                selection(GATEWAY, DEVICE, recognitionModelID = "asr") to
                    """{"input":"gateway","output":"client"}|asr|""",
                selection(DEVICE, GATEWAY, synthesisModelID = "tts") to
                    """{"input":"client","output":"gateway"}||tts""",
                selection(GATEWAY, GATEWAY, "asr", "tts") to """{"input":"gateway","output":"gateway"}|asr|tts""",
                selection(GATEWAY, GATEWAY) to """{"input":"gateway","output":"gateway"}||""",
            )
        for ((voice, expected) in cases) {
            val payload =
                Json
                    .parseToJsonElement(
                        Json.encodeToString(
                            SessionSetup.serializer(),
                            gatewaySessionSetup(emptyList(), "", "", "en-US", voice = voice),
                        ),
                    ).jsonObject
            val actual =
                listOf(
                    payload.getValue("speech").toString(),
                    payload["recognitionModelID"]?.jsonPrimitive?.content.orEmpty(),
                    payload["synthesisModelID"]?.jsonPrimitive?.content.orEmpty(),
                ).joinToString("|")
            assertEquals(voice.toString(), expected, actual)
        }
        // Text sessions leave both stages to the client by transport default.
        val text = Json.encodeToString(SessionSetup.serializer(), gatewaySessionSetup(emptyList(), "", "", "en-US"))
        val textPayload = Json.parseToJsonElement(text).jsonObject
        assertFalse(listOf("speech", "recognitionModelID", "synthesisModelID").any(textPayload::containsKey))
    }

    private fun selection(
        recognition: SpeechStageLocation = GATEWAY,
        speech: SpeechStageLocation = GATEWAY,
        recognitionModelID: String? = null,
        synthesisModelID: String? = null,
        voice: String = "",
    ) = GatewayVoiceSelection(
        "https://gateway.example",
        "choice",
        voice,
        recognition,
        speech,
        recognitionModelID,
        synthesisModelID,
    )

    @Test
    fun sessionSetupSendsSelectedLanguage() {
        val setup = gatewaySessionSetup(emptyList(), "", "", "fr-CA")
        assertEquals("fr-CA", setup.voice.language)
    }

    @Test
    fun usableICECandidateAcceptsLANAndTailscaleIPv4UDP() {
        assertTrue(isUsableICECandidate("candidate:1 1 udp 2130706431 192.168.1.64 57033 typ host"))
        assertTrue(isUsableICECandidate("candidate:2 1 udp 2121998079 100.99.136.28 57469 typ host"))
    }

    @Test
    fun usableICECandidateRejectsLinkLocalIPv4AndNonUDPCandidates() {
        assertTrue(!isUsableICECandidate("candidate:1 1 udp 2122260223 169.254.58.144 58981 typ host"))
        assertTrue(!isUsableICECandidate("candidate:2 1 tcp 1518149375 192.168.1.64 9 typ host tcptype active"))
    }

    @Test
    fun describeICECandidateReportsProtocolTypeAndFamilyWithoutAddress() {
        assertEquals(
            "udp host ipv4 usable=true",
            describeICECandidate("candidate:1 1 udp 2130706431 192.168.1.64 57033 typ host"),
        )
        assertEquals(
            "udp host mdns usable=false",
            describeICECandidate("candidate:2 1 udp 2130706431 0f1e2d3c.local 57033 typ host"),
        )
        assertEquals(
            "udp host ipv4-link-local usable=false",
            describeICECandidate("candidate:3 1 udp 2122260223 169.254.58.144 58981 typ host"),
        )
        assertEquals(
            "udp srflx ipv6 usable=false",
            describeICECandidate("candidate:4 1 udp 1686052607 2001:db8::1 9 typ srflx raddr :: rport 0"),
        )
    }

    @Test
    fun summarizeSDPCandidatesReportsCandidateHostPortAndType() {
        val sdp =
            "v=0\r\n" +
                "a=candidate:1 1 udp 2130706431 70.51.33.231 42602 typ srflx " +
                "raddr 192.168.1.123 rport 42602\r\n" +
                "a=candidate:2 1 udp 2130706431 192.168.1.123 42602 typ host\r\n"

        assertEquals(
            "70.51.33.231:42602 srflx, 192.168.1.123:42602 host",
            summarizeSDPCandidates(sdp),
        )
    }

    @Test
    fun summarizeSDPCandidatesReportsNoneWithoutCandidates() {
        assertEquals("none", summarizeSDPCandidates("v=0\r\n"))
    }

    @Test
    fun formatVoiceRTCDiagnosticsSurfacesUDPMappingError() {
        val message =
            formatVoiceRTCDiagnostics(
                VoiceRTCDiagnosticsResp(
                    sessionID = "voice-session",
                    issue = VoiceRTCConnectivityIssue.UDPUnreachable,
                    side = VoiceRTCConnectivitySide.Network,
                    message = "server is waiting for a WebRTC data channel",
                    server =
                        VoiceRTCServerDiagnostics(
                            sessionFound = true,
                            udpMappingError = "refresh UPnP UDP mapping 40000 -> 3478: timeout",
                        ),
                ),
            )

        assertTrue(message.contains("UDP mapping: refresh UPnP UDP mapping 40000 -> 3478: timeout"))
    }

    @Test
    fun recoveryDelayGivesDisconnectedIceAGraceAndRecoversFailedIceImmediately() {
        assertEquals(5_000L, recoveryDelayMs(org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED))
        assertEquals(0L, recoveryDelayMs(org.webrtc.PeerConnection.IceConnectionState.FAILED))
    }

    @Test
    fun recoveryContextPreservesFinalTranscriptAndBoundsMessage() {
        val context =
            buildNetworkRecoveryContext(
                listOf(
                    TranscriptEntry(TranscriptSpeaker.USER, "first", final = true),
                    TranscriptEntry(TranscriptSpeaker.ASSISTANT, "second", final = true),
                    TranscriptEntry(TranscriptSpeaker.USER, "partial", final = false),
                ),
            )

        assertEquals("user: first\nassistant: second", context)
    }

    @Test
    fun resolveServiceURLUsesConfiguredServiceOriginForRelativePaths() {
        assertEquals(
            "http://10.0.2.2:2242/service/mcp",
            VoiceSession.resolveServiceURL(
                baseURL = "http://10.0.2.2:2242/mobile/screen/123",
                advertisedURL = "/service/mcp",
            ),
        )
    }

    @Test
    fun resolveServiceURLKeepsAbsoluteGatewayURL() {
        assertEquals(
            "https://voice.example.test",
            VoiceSession.resolveServiceURL(
                baseURL = "http://10.0.2.2:2242",
                advertisedURL = "https://voice.example.test/",
            ),
        )
    }
}
