// Manages Go Mode voice gateway sessions via WebRTC, service MCP tools, and Android audio routing.
package com.fghbuild.gomode.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.service.DefaultNetworkMonitor
import com.fghbuild.gomode.service.VoiceTokenClient
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import com.fghbuild.voicegateway.sdk.v2.ApiClient
import com.fghbuild.voicegateway.sdk.v2.ContextUpdate
import com.fghbuild.voicegateway.sdk.v2.Error
import com.fghbuild.voicegateway.sdk.v2.MessageEnvelope
import com.fghbuild.voicegateway.sdk.v2.MessageKind
import com.fghbuild.voicegateway.sdk.v2.ReasoningDelta
import com.fghbuild.voicegateway.sdk.v2.SessionSetup
import com.fghbuild.voicegateway.sdk.v2.SessionStatus
import com.fghbuild.voicegateway.sdk.v2.SessionUsage
import com.fghbuild.voicegateway.sdk.v2.Speaker
import com.fghbuild.voicegateway.sdk.v2.TokenUsage
import com.fghbuild.voicegateway.sdk.v2.ToolCall
import com.fghbuild.voicegateway.sdk.v2.ToolDeclaration
import com.fghbuild.voicegateway.sdk.v2.ToolResult
import com.fghbuild.voicegateway.sdk.v2.TranscriptDelta
import com.fghbuild.voicegateway.sdk.v2.TranscriptDraft
import com.fghbuild.voicegateway.sdk.v2.TurnState
import com.fghbuild.voicegateway.sdk.v2.TurnStatus
import com.fghbuild.voicegateway.sdk.v2.UserMessage
import com.fghbuild.voicegateway.sdk.v2.VoiceConfig
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCClientDiagnostics
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCDataChannelState
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCDiagnosticsReq
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCDiagnosticsResp
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCICEConnectionState
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCICEGatheringState
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCOfferReq
import com.fghbuild.voicegateway.sdk.v2.VoiceRTCSignalingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "GoModeVoiceSession"
private const val MIC_LEVEL_POLL_MS = 100L
private const val SETUP_TIMEOUT_MS = 15_000L
private const val ICE_GATHERING_TIMEOUT_MS = 10_000L
private const val ICE_DISCONNECTED_GRACE_MS = 5_000L

// A Wi-Fi to cellular handover can take tens of seconds. DNS, sockets, ICE gathering, and gateway
// answers fail repeatedly until the new route settles, so recovery is bounded by time, not attempts.
internal const val RECOVERY_BUDGET_MS = 120_000L
private const val RECOVERY_INITIAL_DELAY_MS = 1_000L
private const val RECOVERY_MAX_DELAY_MS = 8_000L
internal const val RECOVERY_EXHAUSTED_ERROR = "Voice connection could not be restored within 2 minutes"

/** Conservative data-channel/model-safe bound for a recovery context update. */
internal const val MAX_RECOVERY_CONTEXT_CHARS = 8_000
private val sdpWhitespaceRegex = Regex("\\s+")

private val emptyObjectSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))

private fun normalizedToolSchema(schema: JsonElement?): JsonObject =
    (schema as? JsonObject)?.takeUnless { it.isEmpty() } ?: emptyObjectSchema

/** Combines prepared service/native tools with the current hosted frontend declarations. */
internal fun voiceToolDeclarations(
    tools: List<ToolDescriptor>,
    frontendTools: List<ToolDeclaration> = emptyList(),
): List<ToolDeclaration> =
    buildList {
        require(tools.none { tool -> frontendTools.any { it.name == tool.name } }) {
            "Voice tool conflicts with a frontend voice tool."
        }
        addAll(frontendTools.map { it.copy(parameters = normalizedToolSchema(it.parameters)) })
        tools.forEach { tool ->
            add(
                ToolDeclaration(
                    name = tool.name,
                    description = tool.description.orEmpty(),
                    parameters = normalizedToolSchema(tool.inputSchema),
                ),
            )
        }
    }

internal fun isUsableICECandidate(candidate: String): Boolean {
    val fields = candidate.trim().split(sdpWhitespaceRegex)
    return fields.size >= 8 && fields[2] == "udp" && isUsableIPv4(fields[4])
}

private fun isUsableIPv4(address: String): Boolean {
    if (address.startsWith("169.254.")) return false
    val octets = address.split(".")
    return octets.size == 4 && octets.all { octet -> octet.toIntOrNull()?.let { it in 0..255 } == true }
}

/** Tracks pending and completed network recovery attempts independently from WebRTC callbacks. */
internal fun recoveryDelayMs(state: PeerConnection.IceConnectionState): Long =
    when (state) {
        PeerConnection.IceConnectionState.FAILED -> 0L
        PeerConnection.IceConnectionState.DISCONNECTED -> ICE_DISCONNECTED_GRACE_MS
        else -> error("ICE state $state does not require recovery")
    }

// Recovery lasts from the first loss until a replacement session is ready or RECOVERY_BUDGET_MS
// has passed. A self-healed loss that never started a replacement does not consume the budget.
internal class VoiceRecoveryPolicy {
    private var pending = false
    private var lostAtMillis: Long? = null
    var attempts = 0
        private set

    val recovering: Boolean get() = lostAtMillis != null

    fun schedule(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val lostAt = lostAtMillis ?: now.also { lostAtMillis = it }
        if (now - lostAt >= RECOVERY_BUDGET_MS) return false
        pending = true
        return true
    }

    // Spacing after a failed replacement; doubles up to RECOVERY_MAX_DELAY_MS.
    fun retryDelayMs(): Long =
        (RECOVERY_INITIAL_DELAY_MS shl attempts.coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(RECOVERY_MAX_DELAY_MS)

    fun cancelPending() {
        pending = false
        if (attempts == 0) lostAtMillis = null
    }

    fun beginScheduledRecovery(): Boolean {
        if (!pending) return false
        pending = false
        attempts++
        return true
    }

    fun reset() {
        pending = false
        lostAtMillis = null
        attempts = 0
    }

    private companion object {
        const val MAX_BACKOFF_SHIFT = 3
    }
}

internal class VoiceSession(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val voiceConversation: VoiceConversation,
    private val languageTag: String,
    voiceChimePlayer: VoiceChimePlayer = AndroidVoiceChime(),
    private val callController: VoiceCallController = NoopVoiceCallController(),
    private val switchServer: suspend (String) -> Unit = { id -> settingsRepository.switchService(id) },
    private val startupTiming: () -> VoiceStartupTiming? = { null },
) : VoiceSessionController {
    private val foreground = VoiceForegroundLease(appContext)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    private val voiceTokenClient = VoiceTokenClient()

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var rtcSessionID: String? = null
    private var voiceTokenEndpointURL: String? = null
    private var pinnedVoice: GatewayVoiceSelection? = null
    private var lastOfferSDP = ""
    private var lastAnswerSDP = ""
    private var pcFactory: PeerConnectionFactory? = null
    private var rtcAudioSource: org.webrtc.AudioSource? = null
    private var localAudioTrack: org.webrtc.AudioTrack? = null
    private var micLevelJob: Job? = null
    private var setupTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectJob: Job? = null
    private val networkMonitor = DefaultNetworkMonitor(appContext)
    private var networkJob: Job? = null

    // Recovery keeps its captured request instead of acquiring a newer UI owner.
    private var startupTimingOwner: VoiceStartupTiming? = null
    private var signalingJob: Job? = null
    private val attemptGeneration = AtomicLong()
    private val pendingGatewayTools = mutableMapOf<Long, Int>()
    private var awaitingGatewayTurn = false
    private val recoveryPolicy = VoiceRecoveryPolicy()
    private val voiceModeChime = VoiceModeChime(voiceChimePlayer)
    private val errorLog = (appContext.applicationContext as GoModeApplication).voiceErrors
    private var disconnectAudioAttempt: Long? = null
    private var recoveryContext = ""

    /** True while Telecom owns the call; SCO teardown is then not a hang-up. */
    private var telecomCallActive = false
    private val microphoneMeter = RtcMicrophoneLevel()

    @Volatile
    private var usableICECandidateWaiter: CompletableDeferred<Unit>? = null
    private var conversationID = UUID.randomUUID().toString()
    private var sessionTools: List<ToolDescriptor> = emptyList()
    private var deviceCallback: AudioDeviceCallback? = null
    private var scoReceiver: BroadcastReceiver? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _state = MutableStateFlow(VoiceState())
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var toolWork = SupervisorJob()

    /** True while the model is speaking — injected text is queued and sent after the turn ends. */
    private var speakerActive = false

    /** Text notifications buffered while the model is speaking; flushed on turn end. */
    private val pendingNotifications = PendingVoiceContext()

    private var lastIceConnectionState: String? = null
    private var lastIceGatheringState: String? = null
    private var lastSignalingState: String? = null

    private val audioStateJob =
        if (callController.isSupported()) {
            scope.launch {
                callController.audioState.collect { audio ->
                    _state.update {
                        it.withCallAudio(audio)
                    }
                }
            }
        } else {
            null
        }

    override fun setError(message: String) {
        stopNetworkMonitoring()
        recordFailure(message)
        voiceConversation.finish()
        val attempt = invalidateAttempt()
        awaitingGatewayTurn = false
        val deferAudioRelease = deferDisconnectAudio(attempt)
        reconnectJob?.cancel()
        reconnectJob = null
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        usableICECandidateWaiter?.cancel()
        usableICECandidateWaiter = null
        Log.e(TAG, "setError: $message")
        releaseTransport()
        closePeerConnection()
        rtcSessionID = null
        voiceTokenEndpointURL = null
        sessionTools = emptyList()
        if (!deferAudioRelease) finishDisconnectAudio(attempt)
        callController.endLocal()
        telecomCallActive = false
        foreground.stop()
        _state.update {
            it.copy(
                connectStatus = null,
                connected = false,
                listening = false,
                speaking = false,
                turnState = TurnState.Idle,
                activeTool = null,
                error = message,
                errorId = it.errorId + 1,
            )
        }
    }

    /** Retains a failure next to the engine and phase that produced it. */
    private fun recordFailure(message: String) {
        errorLog.record(
            engine = OnDeviceEngine.REMOTE_GATEWAY.descriptor().name,
            phase = state.value.failurePhase(),
            message = message,
        )
    }

    private fun setStatus(status: String) {
        Log.i(TAG, status)
        _state.update { it.copy(connectStatus = status, error = null) }
    }

    private fun invalidateAttempt(): Long {
        val attempt = attemptGeneration.incrementAndGet()
        toolWork.cancel()
        toolWork = SupervisorJob()
        connectJob?.cancel()
        connectJob = null
        signalingJob?.cancel()
        signalingJob = null
        return attempt
    }

    private fun ownsAttempt(attempt: Long): Boolean = attemptGeneration.get() == attempt

    /** Connect via WebRTC data channel through the configured service voice gateway. */
    @Suppress("TooGenericExceptionCaught") // Error boundary: surface all failures to UI.
    override fun connect(preserveTranscript: Boolean, conversationID: String?) {
        if (!preserveTranscript) startupTimingOwner = startupTiming()
        val timing = startupTimingOwner
        val attempt = invalidateAttempt()
        awaitingGatewayTurn = false
        reconnectJob?.cancel()
        reconnectJob = null
        usableICECandidateWaiter?.cancel()
        usableICECandidateWaiter = null
        disconnectAudioAttempt = null
        releaseTransport()
        clearCommunicationDevice()
        abandonAudioFocus()
        closePeerConnection()
        rtcSessionID = null
        voiceTokenEndpointURL = null
        sessionTools = emptyList()
        lastIceConnectionState = "new"
        lastIceGatheringState = "new"
        lastSignalingState = "new"
        lastOfferSDP = ""
        lastAnswerSDP = ""
        if (!preserveTranscript) {
            this.conversationID = conversationID ?: UUID.randomUUID().toString()
            pinnedVoice = null
            pendingNotifications.clear()
            recoveryPolicy.reset()
            recoveryContext = ""
            clearTranscript()
            _state.update { it.copy(usage = null, toolHistory = emptyList()) }
        }
        try {
            voiceConversation.begin(preserveTranscript, this.conversationID)
            this.conversationID = voiceConversation.sessionID
        } catch (e: VoiceSetupException) {
            setError(e.message ?: "Gateway conversation is unavailable")
            return
        }
        // A recovery connect keeps the self-managed call, and Telecom owns call
        // audio focus for it. Holding our own request would turn Telecom's
        // transient gain into a focus loss that tears the session down.
        if (!telecomCallActive && !callController.isStarted) {
            requestAudioFocus()
        }
        if (!preserveTranscript) foreground.start(initialVoiceCallerName(settingsRepository.settings.value))
        refreshAvailableDevices()
        registerDeviceCallback()
        registerScoReceiver()
        setStatus("Setting up WebRTC…")
        startNetworkMonitoring()

        connectJob =
            scope.launch {
                try {
                    timing?.enter(VoiceStartupStage.TOOLS)
                    val setup =
                        try {
                            voiceConversation.prepare(pinnedVoice)
                        } catch (e: VoiceSetupException) {
                            if (ownsAttempt(attempt)) setError(e.message ?: "Voice setup failed")
                            return@launch
                        }
                    if (!ownsAttempt(attempt)) return@launch
                    if (!preserveTranscript && !voiceConversation.frontendReadyForConnection()) {
                        setError(FRONTEND_NOT_READY)
                        return@launch
                    }
                    foreground.start(setup.serverName)
                    pinnedVoice =
                        GatewayVoiceSelection(
                            setup.gatewayEndpointURL,
                            requireNotNull(setup.choiceID),
                            setup.voiceName,
                        )
                    sessionTools = setup.tools
                    voiceTokenEndpointURL = setup.tokenEndpointURL
                    val systemInstruction = setup.systemInstruction
                    val serviceContextText = setup.serviceContextText
                    val voiceGatewayEndpointURL = setup.gatewayEndpointURL
                    val serviceAuth = setup.service
                    val voiceGatewayClient = ApiClient(voiceGatewayEndpointURL, httpClient = voiceGatewayHTTPClient)
                    val voiceGatewayHeaders = setup.gatewayHeaders
                    // Recovery keeps the Telecom call; only a fresh connect starts one.
                    if (!telecomCallActive && callController.isSupported() &&
                        !callController.start(setup.serverName, setup.serverID) { disconnect() }
                    ) {
                        setError("Could not start the voice call")
                        return@launch
                    }
                    telecomCallActive = callController.isSupported()
                    timing?.enter(VoiceStartupStage.TRANSPORT)
                    if (telecomCallActive) {
                        // The self-managed call owns call audio from here on.
                        abandonAudioFocus()
                    }

                    // Initialize WebRTC factory.
                    if (pcFactory == null) {
                        PeerConnectionFactory.initialize(
                            PeerConnectionFactory.InitializationOptions
                                .builder(appContext)
                                .setEnableInternalTracer(false)
                                .createInitializationOptions(),
                        )
                        pcFactory =
                            PeerConnectionFactory
                                .builder()
                                .setAudioProcessingFactory(RtcMicrophoneProcessing.acquire(state))
                                .createPeerConnectionFactory()
                    }
                    val factory =
                        pcFactory ?: run {
                            setError("WebRTC factory init failed")
                            return@launch
                        }

                    val iceServers =
                        listOf(
                            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                        )
                    val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

                    val pc =
                        factory.createPeerConnection(
                            rtcConfig,
                            createPeerConnectionObserver(attempt, voiceGatewayClient, voiceGatewayHeaders),
                        ) ?: run {
                            setError("Failed to create PeerConnection")
                            return@launch
                        }
                    if (!ownsAttempt(attempt)) {
                        pc.close()
                        pc.dispose()
                        return@launch
                    }
                    peerConnection = pc

                    // Add local audio track (mic → RTP) so the SDP offer
                    // contains an m=audio line required by the backend bridge.
                    val audioConstraints =
                        MediaConstraints().apply {
                            mandatory.add(
                                MediaConstraints.KeyValuePair("googEchoCancellation", "true"),
                            )
                            mandatory.add(
                                MediaConstraints.KeyValuePair("googAutoGainControl", "true"),
                            )
                            mandatory.add(
                                MediaConstraints.KeyValuePair("googNoiseSuppression", "true"),
                            )
                        }
                    val audioSrc = factory.createAudioSource(audioConstraints)
                    rtcAudioSource = audioSrc
                    val micTrack = factory.createAudioTrack("mic-audio", audioSrc)
                    micTrack.setEnabled(!_state.value.muted)
                    localAudioTrack = micTrack
                    pc.addTrack(micTrack)
                    startMicLevelMonitoring(attempt)

                    val dcInit = DataChannel.Init().apply { ordered = true }
                    val dc =
                        pc.createDataChannel("voice-gateway", dcInit) ?: run {
                            setError("Failed to create data channel")
                            return@launch
                        }
                    dataChannel = dc

                    dc.registerObserver(
                        object : DataChannel.Observer {
                            override fun onBufferedAmountChange(amount: Long) = Unit

                            override fun onStateChange() {
                                scope.launch {
                                    if (!ownsAttempt(attempt)) return@launch
                                    Log.d(TAG, "DC state: ${dc.state()}")
                                    if (dc.state() == DataChannel.State.OPEN) {
                                        setStatus("Waiting for server…")
                                        sendSetupMessage(systemInstruction, serviceContextText, setup.voiceName)
                                    }
                                }
                            }

                            override fun onMessage(buffer: DataChannel.Buffer) {
                                if (!ownsAttempt(attempt)) return
                                val data = ByteArray(buffer.data.remaining())
                                buffer.data.get(data)
                                val text = String(data, StandardCharsets.UTF_8)
                                scope.launch {
                                    if (ownsAttempt(attempt) && dataChannel === dc) {
                                        handleServerMessage(text, attempt, dc)
                                    }
                                }
                            }
                        },
                    )

                    // SDP offer/answer exchange.
                    setStatus("Signaling…")
                    pc.createOffer(
                        object : SdpObserver {
                            override fun onCreateSuccess(desc: SessionDescription) {
                                if (!ownsAttempt(attempt)) return
                                signalingJob =
                                    scope.launch {
                                        try {
                                            if (!ownsAttempt(attempt)) return@launch
                                            setLocalDescriptionAndWaitForICE(pc, desc, timing)
                                            if (!ownsAttempt(attempt)) return@launch
                                            val offerSDP =
                                                pc.localDescription?.description
                                                    ?: error("WebRTC local offer SDP unavailable after ICE gathering")
                                            lastOfferSDP = offerSDP
                                            if (!ownsAttempt(attempt)) return@launch
                                            if (!preserveTranscript &&
                                                !voiceConversation.frontendReadyForConnection()
                                            ) {
                                                setError(FRONTEND_NOT_READY)
                                                return@launch
                                            }
                                            val resp =
                                                voiceGatewayClient.voiceRTCOffer(
                                                    VoiceRTCOfferReq(
                                                        sdp = offerSDP,
                                                        service = serviceAuth,
                                                        choiceID = setup.choiceID,
                                                    ),
                                                    headers = voiceGatewayHeaders,
                                                )
                                            if (!ownsAttempt(attempt)) {
                                                withContext(NonCancellable) {
                                                    runCatching {
                                                        voiceGatewayClient.closeVoiceRTC(
                                                            resp.sessionID,
                                                            headers =
                                                                voiceGatewayHeaders +
                                                                    (
                                                                        serviceAuth?.token?.let {
                                                                            mapOf("Authorization" to "Bearer $it")
                                                                        } ?: emptyMap()
                                                                    ),
                                                        )
                                                    }.onFailure {
                                                        Log.w(
                                                            TAG,
                                                            "Could not close stale voice session",
                                                            it,
                                                        )
                                                    }
                                                }
                                                return@launch
                                            }
                                            rtcSessionID = resp.sessionID
                                            lastAnswerSDP = resp.sdp
                                            val answer = SessionDescription(SessionDescription.Type.ANSWER, resp.sdp)
                                            pc.setRemoteDescription(noOpSdpObserver(), answer)
                                            startSetupTimeout(pc, voiceGatewayClient, voiceGatewayHeaders, timing)
                                            Log.i(TAG, "WebRTC signaling complete, session=${resp.sessionID}")
                                        } catch (e: TimeoutCancellationException) {
                                            // ICE gathering on a route that is still settling.
                                            if (ownsAttempt(attempt) && preserveTranscript) {
                                                retryRecovery("Network connection failed")
                                            }
                                            throw e
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: GatewayConnectionException) {
                                            if (ownsAttempt(attempt)) {
                                                if (preserveTranscript && e.transient) {
                                                    retryRecovery("Network connection failed")
                                                } else {
                                                    timing?.finishFailure(e)
                                                    setError(requireNotNull(e.message))
                                                }
                                            }
                                        } catch (e: Exception) {
                                            if (ownsAttempt(attempt)) {
                                                if (preserveTranscript) {
                                                    // A new route can finish ICE gathering without a usable candidate.
                                                    retryRecovery("Network connection failed")
                                                } else {
                                                    timing?.finishFailure(e)
                                                    setError("SDP exchange failed: ${e.message}")
                                                }
                                            }
                                        }
                                    }
                            }

                            override fun onCreateFailure(error: String) {
                                scope.launch {
                                    if (ownsAttempt(attempt)) setError("Create offer failed: $error")
                                }
                            }

                            override fun onSetSuccess() = Unit

                            override fun onSetFailure(p0: String) = Unit
                        },
                        MediaConstraints(),
                    )
                } catch (e: VoiceMcpAuthChangedException) {
                    if (ownsAttempt(attempt)) {
                        Log.i(TAG, "Service authentication changed during voice setup", e)
                        disconnect()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (ownsAttempt(attempt)) {
                        if (preserveTranscript && (e as? GatewayConnectionException)?.transient != false) {
                            retryRecovery("Network connection failed")
                        } else {
                            timing?.finishFailure(e)
                            setError("WebRTC connection failed: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    if (ownsAttempt(attempt)) {
                        timing?.finishFailure(e)
                        setError("WebRTC connection failed: ${e.message}")
                    }
                }
            }
    }

    @Suppress("TooManyFunctions") // PeerConnection.Observer has many required overrides.
    private fun createPeerConnectionObserver(
        attempt: Long,
        gatewayClient: ApiClient,
        headers: Map<String, String>,
    ) = object : PeerConnection.Observer {
        private val network = networkMonitor.network.value

        override fun onIceCandidate(candidate: IceCandidate) {
            if (!ownsAttempt(attempt)) return
            if (isUsableICECandidate(candidate.sdp)) {
                usableICECandidateWaiter?.complete(Unit)
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            scope.launch {
                if (!ownsAttempt(attempt)) return@launch
                lastIceConnectionState = state.name.lowercase()
                Log.d(TAG, "ICE state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    -> {
                        scheduleReconnect("WebRTC ICE $state", recoveryDelayMs(state))
                    }

                    PeerConnection.IceConnectionState.CONNECTED -> {
                        if (network != null && networkMonitor.network.value == network) {
                            recoveryPolicy.cancelPending()
                            reconnectJob?.cancel()
                            reconnectJob = null
                        }
                    }

                    PeerConnection.IceConnectionState.CLOSED -> {
                        setDiagnosticError(
                            pc = peerConnection ?: return@launch,
                            gatewayClient = gatewayClient,
                            headers = headers,
                            fallback = "WebRTC ICE $state",
                        )
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            if (!ownsAttempt(attempt)) return
            lastSignalingState = state.name.lowercase().replace('_', '-')
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (!ownsAttempt(attempt)) return
            lastIceGatheringState = state.name.lowercase()
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(dc: DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(
            receiver: RtpReceiver,
            streams: Array<out MediaStream>,
        ) {
            scope.launch {
                if (!ownsAttempt(attempt)) return@launch
                val track = receiver.track()
                if (track is org.webrtc.AudioTrack) {
                    track.setEnabled(true)
                    Log.i(TAG, "WebRTC remote audio track enabled")
                }
            }
        }
    }

    private fun startMicLevelMonitoring(attempt: Long) {
        micLevelJob?.cancel()
        microphoneMeter.clear()
        micLevelJob =
            scope.launch {
                while (true) {
                    val pc = peerConnection ?: break
                    if (_state.value.muted) {
                        microphoneMeter.clear()
                        _state.update { it.copy(micLevel = 0f) }
                    } else {
                        val result = CompletableDeferred<org.webrtc.RTCStatsReport>()
                        pc.getStats { report -> result.complete(report) }
                        val report = result.await()
                        if (ownsAttempt(attempt) && peerConnection === pc && !_state.value.muted) {
                            _state.update { it.copy(micLevel = microphoneMeter.read(report)) }
                        }
                    }
                    delay(MIC_LEVEL_POLL_MS)
                }
            }
    }

    private fun stopMicLevelMonitoring() {
        micLevelJob?.cancel()
        micLevelJob = null
        microphoneMeter.clear()
        _state.update { it.copy(micLevel = 0f) }
    }

    private fun startNetworkMonitoring() {
        if (networkJob != null) return
        networkMonitor.start()
        networkJob =
            scope.launch {
                var previous = networkMonitor.network.value
                networkMonitor.network.collect { network ->
                    if (network != previous) {
                        previous = network
                        scheduleReconnect(
                            if (network == null) "Waiting for network" else "Network changed",
                            if (network == null) ICE_DISCONNECTED_GRACE_MS else 0L,
                        )
                    }
                }
            }
    }

    private fun stopNetworkMonitoring() {
        networkJob?.cancel()
        networkJob = null
        networkMonitor.close()
    }

    private fun scheduleReconnect(
        reason: String,
        delayMs: Long,
    ) {
        val attempt = attemptGeneration.get()
        if (reconnectJob != null) {
            if (delayMs != 0L) return
            reconnectJob?.cancel()
        }
        if (!recoveryPolicy.schedule()) {
            setError(RECOVERY_EXHAUSTED_ERROR)
            return
        }
        setStatus("$reason; reconnecting…")
        reconnectJob =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMs)
                if (!ownsAttempt(attempt)) return@launch
                val recoveryAttempt = invalidateAttempt()
                setupTimeoutJob?.cancel()
                setupTimeoutJob = null
                releaseTransport()
                closePeerConnection()
                _state.update {
                    it.copy(connected = false, listening = false, speaking = false, activeTool = null, micLevel = 0f)
                }
                networkMonitor.network.filterNotNull().first()
                reconnectJob = null
                if (ownsAttempt(recoveryAttempt) && recoveryPolicy.beginScheduledRecovery()) {
                    speakerActive = false
                    _state.update { it.copy(speaking = false, turnState = TurnState.Idle, transcriptDraft = null) }
                    recoveryContext = buildNetworkRecoveryContext(_state.value.transcript)
                    connect(preserveTranscript = true)
                }
            }
        reconnectJob?.start()
    }

    private fun retryRecovery(reason: String) = scheduleReconnect(reason, recoveryPolicy.retryDelayMs())

    private fun startSetupTimeout(
        pc: PeerConnection,
        gatewayClient: ApiClient,
        headers: Map<String, String>,
        timing: VoiceStartupTiming?,
    ) {
        setupTimeoutJob?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(startupProgressAtMillis = null) }
        setupTimeoutJob =
            scope.launch {
                do {
                    val lastProgress = _state.value.startupProgressAtMillis ?: startedAt
                    val remaining = SETUP_TIMEOUT_MS - (SystemClock.elapsedRealtime() - lastProgress)
                    if (remaining > 0) delay(remaining)
                } while (
                    SystemClock.elapsedRealtime() - (_state.value.startupProgressAtMillis ?: startedAt) <
                    SETUP_TIMEOUT_MS
                )
                if (peerConnection !== pc || _state.value.connected || _state.value.error != null) return@launch
                if (recoveryPolicy.recovering) {
                    retryRecovery("Voice gateway did not respond")
                    return@launch
                }
                setDiagnosticError(
                    pc = pc,
                    gatewayClient = gatewayClient,
                    headers = headers,
                    fallback = "Connection timed out — server did not respond",
                    onFailure = { timing?.finish(VoiceStartupOutcome.TIMED_OUT) },
                )
            }
    }

    private fun clientDiagnostics() =
        VoiceRTCClientDiagnostics(
            iceConnectionState = lastIceConnectionState?.let { VoiceRTCICEConnectionState.Other(it) },
            iceGatheringState = lastIceGatheringState?.let { VoiceRTCICEGatheringState.Other(it) },
            signalingState = lastSignalingState?.let { VoiceRTCSignalingState.Other(it) },
            dataChannelState =
                dataChannel
                    ?.state()
                    ?.name
                    ?.lowercase()
                    ?.let { VoiceRTCDataChannelState.Other(it) },
        )

    private fun setDiagnosticError(
        pc: PeerConnection,
        gatewayClient: ApiClient,
        headers: Map<String, String>,
        fallback: String,
        onFailure: () -> Unit = {},
    ) {
        scope.launch {
            val message = diagnosticErrorMessage(gatewayClient, headers, fallback)
            if (peerConnection === pc && !_state.value.connected && _state.value.error == null) {
                onFailure()
                setError(message)
            }
        }
    }

    private suspend fun diagnosticErrorMessage(
        gatewayClient: ApiClient,
        headers: Map<String, String>,
        fallback: String,
    ): String {
        val sessionID = rtcSessionID ?: return fallback
        return try {
            val authHeaders =
                voiceTokenEndpointURL?.let { endpoint ->
                    val service =
                        voiceTokenClient.fetch(
                            endpoint,
                            cookieFor(endpoint),
                            voiceConversation.tokenFor(endpoint),
                        )
                    headers + ("Authorization" to "Bearer ${service.token}")
                } ?: headers
            val diagnostics =
                gatewayClient.diagnoseVoiceRTC(
                    sessionID,
                    VoiceRTCDiagnosticsReq(client = clientDiagnostics()),
                    headers = authHeaders,
                )
            logVoiceRTCDiagnostics(diagnostics)
            formatVoiceRTCDiagnostics(diagnostics)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Voice RTC diagnostics failed", e)
            fallback
        }
    }

    private suspend fun setLocalDescriptionAndWaitForICE(
        pc: PeerConnection,
        desc: SessionDescription,
        timing: VoiceStartupTiming?,
    ) {
        val candidate = CompletableDeferred<Unit>()
        usableICECandidateWaiter = candidate
        try {
            setLocalDescription(pc, desc)
            waitForUsableICECandidate(pc, candidate)
        } catch (error: TimeoutCancellationException) {
            if (peerConnection === pc) timing?.noteTimeout()
            throw error
        } finally {
            if (usableICECandidateWaiter === candidate) {
                usableICECandidateWaiter = null
            }
        }
    }

    private suspend fun setLocalDescription(
        pc: PeerConnection,
        desc: SessionDescription,
    ) {
        val result = CompletableDeferred<String?>()
        pc.setLocalDescription(
            object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription) = Unit

                override fun onSetSuccess() {
                    result.complete(null)
                }

                override fun onCreateFailure(p0: String) = Unit

                override fun onSetFailure(p0: String) {
                    result.complete(p0)
                }
            },
            desc,
        )
        val error = withTimeout(ICE_GATHERING_TIMEOUT_MS) { result.await() }
        if (error != null) {
            error("Set local description failed: $error")
        }
    }

    // waitForUsableICECandidate does not wait for every configured STUN server.
    // A slow or unreachable STUN request must not delay LAN or Tailscale signaling.
    private suspend fun waitForUsableICECandidate(
        pc: PeerConnection,
        candidate: CompletableDeferred<Unit>,
    ) {
        if (!candidate.isCompleted && pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
            error("WebRTC ICE gathering completed without a usable candidate")
        }
        withTimeout(ICE_GATHERING_TIMEOUT_MS) {
            candidate.await()
        }
        if (peerConnection !== pc) {
            throw CancellationException("WebRTC peer connection changed before a usable ICE candidate was gathered")
        }
    }

    private fun logVoiceRTCDiagnostics(diagnostics: VoiceRTCDiagnosticsResp) {
        Log.w(
            TAG,
            "Voice RTC diagnostics: issue=${diagnostics.issue.value} side=${diagnostics.side.value} " +
                "server=${diagnostics.server} client=${diagnostics.client} " +
                "clientOfferCandidates=${summarizeSDPCandidates(lastOfferSDP)} " +
                "serverAnswerCandidates=${summarizeSDPCandidates(lastAnswerSDP)}",
        )
    }

    private fun noOpSdpObserver() =
        object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription) = Unit

            override fun onSetSuccess() = Unit

            override fun onCreateFailure(p0: String) {
                Log.w(TAG, "SDP failure: $p0")
            }

            override fun onSetFailure(p0: String) {
                Log.w(TAG, "SDP failure: $p0")
            }
        }

    override fun setMicrophoneMode(mode: MicrophoneMode) {
        _state.update { it.copy(microphoneMode = mode) }
        applyMicrophoneMode()
    }

    private fun applyMicrophoneMode() {
        val muted = _state.value.muted
        localAudioTrack?.setEnabled(!muted)
        if (muted) _state.update { it.copy(micLevel = 0f) }
    }

    override fun selectAudioDevice(deviceId: Int) {
        if (callController.isSupported()) {
            callController.selectAudioDevice(deviceId)
        } else {
            applyCommunicationDevice(deviceId)
        }
    }

    private fun releaseTransport() {
        stopMicLevelMonitoring()
        unregisterDeviceCallback()
        unregisterScoReceiver()
        dataChannel?.close()
        dataChannel?.dispose()
        dataChannel = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        rtcAudioSource?.dispose()
        rtcAudioSource = null
    }

    private fun closePeerConnection() {
        val pc = peerConnection
        peerConnection = null
        pc?.close()
        pc?.dispose()
    }

    override fun disconnect() {
        stopNetworkMonitoring()
        voiceConversation.finish()
        pinnedVoice = null
        val attempt = invalidateAttempt()
        awaitingGatewayTurn = false
        val deferAudioRelease = deferDisconnectAudio(attempt)
        reconnectJob?.cancel()
        reconnectJob = null
        recoveryPolicy.reset()
        recoveryContext = ""
        usableICECandidateWaiter?.cancel()
        usableICECandidateWaiter = null
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        releaseTransport()
        if (!deferAudioRelease) finishDisconnectAudio(attempt)
        callController.endLocal()
        telecomCallActive = false
        foreground.stop()
        closePeerConnection()
        rtcSessionID = null
        voiceTokenEndpointURL = null
        sessionTools = emptyList()
        // Preserve transcript so the user can review it after disconnecting.
        _state.value =
            VoiceState(
                transcript =
                    _state.value.transcript.map {
                        it.copy(final = true)
                    },
                usage = _state.value.usage,
                toolHistory = _state.value.toolHistory,
            ).withCallAudio(callController.audioState.value)
    }

    private fun finishDisconnectAudio(attempt: Long) {
        if (!ownsAttempt(attempt)) return
        clearCommunicationDevice()
        abandonAudioFocus()
    }

    private fun deferDisconnectAudio(attempt: Long): Boolean {
        val chimeAlreadyPlaying = disconnectAudioAttempt != null
        disconnectAudioAttempt = attempt
        val chimeStarted = voiceModeChime.disconnected(::finishPendingDisconnectAudio)
        return chimeStarted || chimeAlreadyPlaying
    }

    private fun finishPendingDisconnectAudio() {
        val attempt = disconnectAudioAttempt ?: return
        disconnectAudioAttempt = null
        finishDisconnectAudio(attempt)
    }

    override fun clearTranscript() {
        _state.update { it.copy(transcript = emptyList(), transcriptDraft = null) }
    }

    override fun injectText(
        text: String,
        generate: Boolean,
    ) {
        if (_state.value.error != null) return
        if (!voiceConversation.isCurrent()) {
            disconnect()
            return
        }
        if (speakerActive || awaitingGatewayTurn || _state.value.turnState != TurnState.Idle ||
            !_state.value.connected ||
            pendingGatewayTools.containsKey(attemptGeneration.get())
        ) {
            if (!pendingNotifications.add(text, generate)) {
                setError("Too many pending service alerts. Reconnect voice.")
            }
            return
        }
        sendClientContent(text, generate)
    }

    override fun close() {
        audioStateJob?.cancel()
        disconnect()
        pcFactory?.dispose()
        pcFactory = null
        RtcMicrophoneProcessing.release(state)
    }

    /** Send a message via the WebRTC data channel. */
    private fun send(text: String) {
        val channel = dataChannel
        if (channel != null) {
            sendOnChannel(channel, text, VOICE_MESSAGE_DELIVERY_ERROR)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Transport boundary: terminate visibly without replaying a failed send.
    private fun sendOnChannel(
        dc: DataChannel,
        text: String,
        failureMessage: String,
    ) {
        if (dataChannel !== dc) return
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val accepted =
            try {
                dc.state() == DataChannel.State.OPEN && dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Data channel send failed: ${e.javaClass.simpleName}")
                false
            }
        if (!accepted) {
            Log.w(TAG, "Voice message delivery failed: bytes=${bytes.size}")
            setError(failureMessage)
        }
    }

    private fun sendClientContent(
        text: String,
        generate: Boolean = false,
    ) {
        if (generate) awaitingGatewayTurn = true
        send(json.encodeToString(ContextUpdate.serializer(), gatewayContextUpdate(text, generate)))
    }

    private fun flushPendingNotifications() {
        if (!voiceConversation.isCurrent()) {
            disconnect()
            return
        }
        if (speakerActive || awaitingGatewayTurn || _state.value.turnState != TurnState.Idle ||
            !_state.value.connected ||
            pendingGatewayTools.containsKey(attemptGeneration.get())
        ) {
            return
        }
        val update = pendingNotifications.drain() ?: return
        sendClientContent(update.text, update.generate)
    }

    private fun sendSetupMessage(
        systemInstruction: String,
        serviceContextText: String,
        voiceName: String,
    ) {
        val setup =
            gatewaySessionSetup(
                voiceToolDeclarations(sessionTools),
                systemInstruction,
                serviceContextText,
                languageTag,
                voiceName,
                settingsRepository.settings.value.showReasoning,
            )
        Log.i(TAG, "sending setup message")
        // Omit default-null fields such as an unselected voice.
        send(Json.encodeToString(SessionSetup.serializer(), setup))
    }

    @Suppress("TooGenericExceptionCaught") // Error boundary: malformed messages must not crash.
    private suspend fun handleServerMessage(
        text: String,
        attempt: Long,
        originChannel: DataChannel,
    ) {
        if (!ownsAttempt(attempt) || dataChannel !== originChannel) return
        try {
            val env = json.decodeFromString(MessageEnvelope.serializer(), text)
            when (env.kind) {
                MessageKind.SessionStatus -> {
                    if (!_state.value.connected) {
                        val status = json.decodeFromString(SessionStatus.serializer(), text)
                        _state.update {
                            it.copy(
                                connectStatus = status.message,
                                startupProgressAtMillis = SystemClock.elapsedRealtime(),
                            )
                        }
                    }
                }

                MessageKind.SessionReady -> {
                    Log.i(TAG, "session.ready received")
                    if (reconnectJob == null) recoveryPolicy.reset()
                    setupTimeoutJob?.cancel()
                    setupTimeoutJob = null
                    _state.update {
                        it.copy(
                            connectStatus = null,
                            connected = true,
                            listening = true,
                            error = null,
                        )
                    }
                    callController.activate()
                    voiceModeChime.connected()
                    if (recoveryContext.isNotEmpty()) {
                        send(json.encodeToString(ContextUpdate.serializer(), gatewayContextUpdate(recoveryContext)))
                        recoveryContext = ""
                    }
                    flushPendingNotifications()
                }

                MessageKind.SessionUsage -> {
                    val report = json.decodeFromString(SessionUsage.serializer(), text)
                    _state.update { it.withUsageReport(report) }
                }

                MessageKind.TranscriptDelta -> {
                    handleTranscriptDelta(json.decodeFromString(TranscriptDelta.serializer(), text))
                }

                MessageKind.TranscriptDraft -> {
                    val draft = json.decodeFromString(TranscriptDraft.serializer(), text)
                    _state.update { it.withTranscriptDraft(draft) }
                }

                MessageKind.ReasoningDelta -> {
                    handleReasoningDelta(json.decodeFromString(ReasoningDelta.serializer(), text))
                }

                MessageKind.SpeechStarted -> {
                    speakerActive = true
                    _state.update { it.copy(speaking = true) }
                    applyMicrophoneMode()
                }

                MessageKind.SpeechEnded -> {
                    if (!pendingGatewayTools.containsKey(attempt)) awaitingGatewayTurn = false
                    speakerActive = false
                    flushPendingNotifications()
                    _state.update {
                        it.copy(
                            speaking = false,
                            microphoneMode = it.microphoneMode.afterReply(),
                            transcript = it.transcript.map { e -> e.copy(final = true) },
                        )
                    }
                    applyMicrophoneMode()
                }

                MessageKind.Interrupted -> {
                    if (!pendingGatewayTools.containsKey(attempt)) awaitingGatewayTurn = false
                    speakerActive = false
                    _state.update {
                        it.copy(
                            speaking = false,
                            microphoneMode = it.microphoneMode.afterReply(),
                            activeTool = null,
                            turnState = TurnState.Idle,
                        )
                    }
                    flushPendingNotifications()
                    applyMicrophoneMode()
                }

                MessageKind.TurnStatus -> {
                    val msg = json.decodeFromString(TurnStatus.serializer(), text)
                    _state.update { it.copy(turnState = msg.state) }
                    if (msg.state == TurnState.Idle && !pendingGatewayTools.containsKey(attempt)) {
                        awaitingGatewayTurn = false
                        if (!speakerActive) {
                            _state.update {
                                if (it.microphoneMode == MicrophoneMode.MUTE_ONCE) {
                                    it.copy(microphoneMode = MicrophoneMode.LISTENING)
                                } else {
                                    it
                                }
                            }
                            applyMicrophoneMode()
                        }
                        flushPendingNotifications()
                    }
                }

                MessageKind.ToolCall -> {
                    handleToolCall(json.decodeFromString(ToolCall.serializer(), text), attempt, originChannel)
                }

                MessageKind.Error -> {
                    val msg = json.decodeFromString(Error.serializer(), text)
                    if (msg.recoverable) {
                        Log.w(TAG, "Recoverable gateway error: ${msg.message}")
                        recordFailure(msg.message)
                        _state.update { it.withGatewayError(msg.message) }
                    } else {
                        setError(msg.message)
                    }
                }

                else -> {
                    Log.w(TAG, "Unrecognized server message: ${env.kind}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (ownsAttempt(attempt) && dataChannel === originChannel) {
                setError(e.message ?: "Failed to process server message")
            }
        }
    }

    private fun handleReasoningDelta(msg: ReasoningDelta) {
        // Reasoning is display-only: it is never spoken or replayed as context.
        if (!settingsRepository.settings.value.showReasoning) return
        val chunk = msg.text ?: return
        _state.update {
            it.withTranscriptChunk(TranscriptSpeaker.ASSISTANT, chunk, TranscriptKind.REASONING, msg.responseID)
        }
    }

    private fun handleTranscriptDelta(msg: TranscriptDelta) {
        if (msg.speaker == Speaker.User && !msg.draft_id.isNullOrEmpty()) {
            _state.update { it.withCommittedTranscript(msg) }
            return
        }
        val speaker =
            when (msg.speaker) {
                Speaker.User -> TranscriptSpeaker.USER
                Speaker.Assistant -> TranscriptSpeaker.ASSISTANT
                else -> return
            }
        val chunk = msg.text ?: return
        _state.update { it.withTranscriptChunk(speaker, chunk, responseID = msg.responseID) }
    }

    private suspend fun handleToolCall(
        msg: ToolCall,
        attempt: Long,
        originChannel: DataChannel,
    ) {
        if (!ownsAttempt(attempt)) return
        coroutineScope {
            val invocation = currentCoroutineContext().job
            val cancellation = toolWork.invokeOnCompletion { invocation.cancel() }
            try {
                handleToolCallOwned(msg, attempt, originChannel)
            } finally {
                cancellation.dispose()
            }
        }
    }

    private suspend fun handleToolCallOwned(
        msg: ToolCall,
        attempt: Long,
        originChannel: DataChannel,
    ) {
        awaitingGatewayTurn = true
        pendingGatewayTools[attempt] = (pendingGatewayTools[attempt] ?: 0) + 1
        try {
            dispatchToolCall(msg, attempt, originChannel)
        } finally {
            val pending = (pendingGatewayTools[attempt] ?: 1) - 1
            if (pending == 0) pendingGatewayTools.remove(attempt) else pendingGatewayTools[attempt] = pending
            if (ownsAttempt(attempt)) flushPendingNotifications()
        }
    }

    private suspend fun dispatchToolCall(
        msg: ToolCall,
        attempt: Long,
        originChannel: DataChannel,
    ) {
        if (!ownsAttempt(attempt) || dataChannel !== originChannel) return
        if (!voiceConversation.isCurrent()) {
            disconnect()
            return
        }
        val id = msg.id
        val name = msg.name
        val sessionID = conversationID
        try {
            _state.update { it.withToolCall(name) }
            val serverResult =
                executeNativeVoiceTool(
                    name,
                    msg.args,
                    settingsRepository,
                    switchServer = switchServer,
                    onMute = { setMicrophoneMode(MicrophoneMode.MUTE_ONCE) },
                    onDisconnect = ::disconnect,
                )
            if (serverResult != null) {
                if (!ownsAttempt(attempt) || dataChannel !== originChannel) return
                _state.update { it.copy(activeTool = null) }
                sendToolResult(id, name, serverResult, attempt, originChannel)
                return
            }
            val result =
                voiceConversation.execute(
                    name,
                    msg.args as? JsonObject ?: JsonObject(emptyMap()),
                    sessionID,
                    languageTag,
                ) {
                    ownsAttempt(attempt) && dataChannel === originChannel
                }
            if (!ownsAttempt(attempt) || dataChannel !== originChannel) return
            _state.update { it.copy(activeTool = null) }
            sendToolResult(
                id,
                name,
                result?.structuredContent ?: errorJson("Unknown catalog tool"),
                attempt,
                originChannel,
            )
        } catch (e: VoiceSetupException) {
            if (ownsAttempt(attempt) && dataChannel === originChannel) {
                Log.i(TAG, "Conversation identity changed before catalog tool call", e)
                disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (!ownsAttempt(attempt) || dataChannel !== originChannel) return
            _state.update { it.copy(activeTool = null) }
            val errMsg = e.message ?: "Unknown error"
            Log.e(TAG, "Tool $name threw: $errMsg", e)
            _state.update {
                it.copy(
                    transcript =
                        it.transcript +
                            TranscriptEntry(
                                TranscriptSpeaker.ASSISTANT,
                                "[$name] $errMsg",
                                final = true,
                            ),
                )
            }
            sendToolResult(id, name, errorJson(errMsg), attempt, originChannel)
        }
    }

    private fun sendToolResult(
        id: String,
        name: String,
        result: JsonElement,
        attempt: Long,
        originChannel: DataChannel,
    ) {
        if (!ownsAttempt(attempt) || dataChannel !== originChannel) return
        sendOnChannel(
            originChannel,
            json.encodeToString(ToolResult.serializer(), gatewayToolResult(id, name, result)),
            VOICE_TOOL_RESULT_DELIVERY_ERROR,
        )
    }

    // -----------------------------------------------------------------------
    // Audio device management (transport-agnostic)
    // -----------------------------------------------------------------------

    /** Populate available devices list and auto-select the best device. */
    private fun refreshAvailableDevices() {
        if (callController.isSupported()) return
        val devices =
            audioManager.availableCommunicationDevices.map { info ->
                AudioDevice(id = info.id, type = info.type, name = audioDeviceTypeName(info.type))
            }
        val currentSelected = _state.value.selectedDeviceId
        val autoSelect =
            if (currentSelected != null && devices.any { it.id == currentSelected }) {
                currentSelected
            } else {
                // Priority: BT SCO > USB headset/device > wired headphones > built-in speaker.
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.id
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }?.id
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }?.id
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }?.id
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }?.id
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.id
            }
        _state.update { it.copy(availableDevices = devices) }
        if (autoSelect != null) {
            applyCommunicationDevice(autoSelect)
        }
    }

    private fun applyCommunicationDevice(deviceId: Int) {
        val info =
            audioManager.availableCommunicationDevices.firstOrNull { it.id == deviceId }
        if (info == null) {
            refreshAvailableDevices()
            _state.update { it.copy(audioError = "Audio device is no longer available. Choose another device.") }
            return
        }
        if (audioManager.setCommunicationDevice(info)) {
            _state.update { it.copy(selectedDeviceId = deviceId, audioError = null) }
        } else {
            _state.update { it.copy(audioError = "Could not switch audio device") }
        }
    }

    private fun registerDeviceCallback() {
        val cb =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    refreshAvailableDevices()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    val selectedId = _state.value.selectedDeviceId
                    val lostBt =
                        selectedId != null && removedDevices?.any {
                            it.id == selectedId && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        } == true
                    if (lostBt && !telecomCallActive) {
                        Log.i(TAG, "Selected Bluetooth device removed, disconnecting")
                        disconnect()
                    } else {
                        refreshAvailableDevices()
                    }
                }
            }
        deviceCallback = cb
        audioManager.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
    }

    private fun unregisterDeviceCallback() {
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
    }

    /** Listen for SCO audio link teardown — fired when the car's HFP hang-up disconnects
     *  the audio channel without removing the BT device from the system. */
    private fun registerScoReceiver() {
        var scoWasConnected = false
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                    val state =
                        intent.getIntExtra(
                            AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_ERROR,
                        )
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        scoWasConnected = true
                        return
                    }
                    if (state != AudioManager.SCO_AUDIO_STATE_DISCONNECTED) return
                    // Ignore spurious disconnects fired during HFP negotiation before SCO is up.
                    if (!scoWasConnected) return
                    val selectedId = _state.value.selectedDeviceId ?: return
                    val isBtSco =
                        _state.value.availableDevices.any {
                            it.id == selectedId && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        }
                    if (isBtSco && !telecomCallActive) {
                        Log.i(TAG, "SCO audio disconnected (HFP hang-up), disconnecting")
                        disconnect()
                    }
                }
            }
        scoReceiver = receiver
        appContext.registerReceiver(
            receiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
        )
    }

    private fun unregisterScoReceiver() {
        scoReceiver?.let { appContext.unregisterReceiver(it) }
        scoReceiver = null
    }

    private fun clearCommunicationDevice() {
        if (audioFocusRequest != null || telecomCallActive) audioManager.clearCommunicationDevice()
    }

    /** Request exclusive audio focus so music/podcasts pause while the voice session is active. */
    private fun requestAudioFocus() {
        val request = voiceAudioFocusRequest { disconnect() }
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    companion object {
        fun resolveServiceURL(
            baseURL: String,
            advertisedURL: String,
        ): String =
            com.fghbuild.gomode.service
                .resolveServiceURL(baseURL, advertisedURL)
    }
}

internal fun summarizeSDPCandidates(sdp: String): String {
    val candidates =
        sdp
            .lineSequence()
            .mapNotNull { line ->
                val body = line.trim()
                if (!body.startsWith("a=candidate:")) return@mapNotNull null
                val fields = body.split(sdpWhitespaceRegex)
                if (fields.size < 8) return@mapNotNull null
                "${fields[4]}:${fields[5]} ${fields[7]}"
            }.toList()
    return candidates.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none"
}

internal fun formatVoiceRTCDiagnostics(diagnostics: VoiceRTCDiagnosticsResp): String {
    val side = if (diagnostics.side.value == "none") "unknown" else diagnostics.side.value
    val mappingError =
        diagnostics.server.udpMappingError
            ?.takeIf { it.isNotBlank() }
            ?.let { " — UDP mapping: $it" }
            .orEmpty()
    return "$side: ${diagnostics.issue.value} — ${diagnostics.message}$mappingError"
}

enum class TranscriptSpeaker { USER, ASSISTANT }

enum class TranscriptKind { TRANSCRIPT, REASONING }

data class TranscriptEntry(
    val speaker: TranscriptSpeaker,
    val text: String,
    val final: Boolean = false,
    val kind: TranscriptKind = TranscriptKind.TRANSCRIPT,
    val responseID: String? = null,
    val responseUsage: TokenUsage? = null,
)

/** Appends a gateway-reported turn failure without ending the call. */
internal fun VoiceState.withGatewayError(message: String): VoiceState =
    copy(
        transcript = transcript + TranscriptEntry(TranscriptSpeaker.ASSISTANT, "[Voice] $message", final = true),
    )

data class AudioDevice(
    val id: Int,
    val type: Int,
    val name: String,
)

enum class MicrophoneMode {
    LISTENING,
    MUTE_ONCE,
    MUTED,
    ;

    fun afterReply(): MicrophoneMode = if (this == MUTE_ONCE) LISTENING else this
}

/** An admitted invocation; sequence distinguishes repeated names within one conversation. */
data class ToolInvocation(
    val sequence: Long,
    val name: String,
)

data class VoiceState(
    val usage: VoiceUsage? = null,
    val connectStatus: String? = null,
    /** Latest gateway startup heartbeat, on the elapsed-realtime clock. */
    val startupProgressAtMillis: Long? = null,
    val connected: Boolean = false,
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val microphoneMode: MicrophoneMode = MicrophoneMode.LISTENING,
    val activeTool: String? = null,
    /** Latest 32 admitted tool names, in invocation order across reconnects. */
    val toolHistory: List<ToolInvocation> = emptyList(),
    /** Gateway work before assistant output; idle when the gateway does not report any. */
    val turnState: TurnState = TurnState.Idle,
    val error: String? = null,
    val errorId: Long = 0,
    /** Conversation transcript log; each entry is one speaker turn. */
    val transcript: List<TranscriptEntry> = emptyList(),
    /** Ephemeral gateway hypothesis; excluded from transcript history and recovery. */
    val transcriptDraft: TranscriptDraft? = null,
    /** RMS mic input level, normalized 0..1. */
    val micLevel: Float = 0f,
    /** Available audio input/output devices. */
    val availableDevices: List<AudioDevice> = emptyList(),
    /** Currently selected audio device ID, or null for system default. */
    val selectedDeviceId: Int? = null,
    val audioError: String? = null,
    val audioSwitching: Boolean = false,
) {
    val muted: Boolean
        get() = microphoneMode != MicrophoneMode.LISTENING
}

/** Replace only the current ephemeral hypothesis; stale clears cannot erase newer input. */
internal fun VoiceState.withTranscriptDraft(draft: TranscriptDraft): VoiceState {
    if (draft.id.isEmpty() || draft.text.length > 65536) return this
    if (draft.text.isEmpty()) {
        return if (transcriptDraft?.id == draft.id) copy(transcriptDraft = null) else this
    }
    return copy(transcriptDraft = draft)
}

/** Commit a complete user turn and remove its matching hypothesis in one state update. */
internal fun VoiceState.withCommittedTranscript(message: TranscriptDelta): VoiceState {
    val committed =
        if (message.text.isEmpty()) {
            emptyList()
        } else {
            listOf(TranscriptEntry(TranscriptSpeaker.USER, message.text, final = true))
        }
    return copy(
        transcriptDraft = if (transcriptDraft?.id == message.draft_id) null else transcriptDraft,
        transcript = transcript + committed,
    )
}

/** Replay the newest finalized transcript lines that fit, oldest first, as `speaker: text`. */
internal fun buildNetworkRecoveryContext(transcript: List<TranscriptEntry>): String {
    val lines = ArrayDeque<String>()
    var remaining = MAX_RECOVERY_CONTEXT_CHARS
    for (entry in transcript.asReversed()) {
        if (!entry.final || entry.text.isBlank()) continue
        val line = "${entry.speaker.name.lowercase()}: ${entry.text.trim()}"
        if (line.length + 1 > remaining) break
        lines.addFirst(line)
        remaining -= line.length + 1
    }
    return lines.joinToString("\n")
}

internal fun gatewaySessionSetup(
    tools: List<ToolDeclaration>,
    systemInstruction: String,
    serviceContextText: String,
    languageTag: String,
    voiceName: String = "",
    includeReasoning: Boolean = false,
) = SessionSetup(
    kind = MessageKind.SessionSetup,
    includeReasoning = includeReasoning.takeIf { it },
    voice =
        VoiceConfig(
            name = voiceName.takeIf { it.isNotEmpty() },
            language = languageTag,
        ),
    tools = tools,
    context =
        com.fghbuild.voicegateway.sdk.v2.Context(
            systemInstruction = systemInstruction,
            text = serviceContextText,
        ),
)

internal fun gatewayContextUpdate(
    text: String,
    generate: Boolean = false,
) = ContextUpdate(
    generate = true.takeIf { generate },
    kind = MessageKind.ContextUpdate,
    context =
        com.fghbuild.voicegateway.sdk.v2
            .Context(text = text),
)

internal fun gatewayUserMessage(text: String) =
    UserMessage(
        kind = MessageKind.UserMessage,
        text = text,
    )

internal fun gatewayToolResult(
    id: String,
    name: String,
    result: JsonElement,
) = ToolResult(
    kind = MessageKind.ToolResult,
    id = id,
    name = name,
    result = result,
)

/**
 * Append a transcription chunk to the log.
 * If the last entry is from the same speaker and not yet finalized, concatenate the new
 * chunk onto it (the API streams one word/phrase at a time per message).
 * Otherwise start a new entry.
 */
internal fun List<TranscriptEntry>.appendChunk(
    speaker: TranscriptSpeaker,
    text: String,
    kind: TranscriptKind = TranscriptKind.TRANSCRIPT,
    responseID: String? = null,
    responseUsage: TokenUsage? = null,
): List<TranscriptEntry> {
    val id = responseID.takeIf { speaker == TranscriptSpeaker.ASSISTANT }
    val index =
        if (id != null) {
            indexOfFirst {
                it.speaker == speaker && it.kind == kind && it.responseID == id
            }
        } else {
            lastIndex
        }
    val previous = getOrNull(index)
    val counts = responseUsage.takeIf { kind == TranscriptKind.TRANSCRIPT && id != null }
    return if (previous != null && previous.speaker == speaker && previous.kind == kind &&
        previous.responseID == id && (id != null || !previous.final)
    ) {
        mapIndexed { i, entry ->
            if (i == index) {
                entry.copy(
                    text = entry.text + text,
                    responseUsage = counts ?: entry.responseUsage,
                )
            } else {
                entry
            }
        }
    } else {
        this + TranscriptEntry(speaker, text, kind = kind, responseID = id, responseUsage = counts)
    }
}

internal class VoiceMcpAuthChangedException : IllegalStateException("Hosted service authentication changed")

// Pins MCP requests to the voice session's original credentials and rejects account switches.
internal class VoiceMcpCredentials(
    private val cookie: String?,
    private val bearer: String?,
    private val currentCookie: () -> String?,
    private val currentBearer: () -> String?,
) {
    fun isCurrent(): Boolean = currentCookie() == cookie && currentBearer() == bearer

    fun cookieForRequest(): String? {
        if (!isCurrent()) throw VoiceMcpAuthChangedException()
        return cookie
    }

    fun bearerForRequest(): String? = bearer
}

internal fun errorJson(message: String): JsonElement = JsonObject(mapOf("error" to JsonPrimitive(message)))

/** Audio focus outcomes for a live voice session. */
internal enum class VoiceAudioFocusOutcome {
    KEEP,
    DISCONNECT,
}

/**
 * voiceAudioFocusOutcome maps an audio focus change to the session action.
 *
 * Only a permanent loss ends the session. A transient loss means another party
 * holds audio for a moment, which a self-managed Telecom call does while it
 * starts, so the session must survive it.
 */
internal fun voiceAudioFocusOutcome(change: Int): VoiceAudioFocusOutcome =
    when (change) {
        AudioManager.AUDIOFOCUS_LOSS -> VoiceAudioFocusOutcome.DISCONNECT
        else -> VoiceAudioFocusOutcome.KEEP
    }

/** voiceAudioFocusRequest builds the session's audio focus request. */
internal fun voiceAudioFocusRequest(onDisconnect: () -> Unit): AudioFocusRequest =
    AudioFocusRequest
        .Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        ).setOnAudioFocusChangeListener { change ->
            when (voiceAudioFocusOutcome(change)) {
                VoiceAudioFocusOutcome.DISCONNECT -> {
                    Log.i(TAG, "Audio focus lost (change=$change), disconnecting")
                    onDisconnect()
                }

                VoiceAudioFocusOutcome.KEEP -> {
                    if (change != AudioManager.AUDIOFOCUS_GAIN) {
                        Log.i(TAG, "Audio focus held elsewhere (change=$change), keeping the session")
                    }
                }
            }
        }.build()

@Suppress("CyclomaticComplexMethod") // Simple exhaustive mapping, no logic.
private fun audioDeviceTypeName(type: Int): String =
    when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT A2DP"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
        else -> "Device $type"
    }

/** Record an admitted tool invocation without retaining its arguments or result. */
internal fun VoiceState.withToolCall(name: String): VoiceState =
    copy(
        activeTool = name,
        toolHistory =
            (
                toolHistory +
                    ToolInvocation((toolHistory.lastOrNull()?.sequence ?: 0) + 1, name)
            ).takeLast(32),
    )
