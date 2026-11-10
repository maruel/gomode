// Owns device speech and routes text turns to the selected local engine or voice gateway.
package com.fghbuild.gomode.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.service.DefaultNetworkMonitor
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.web.FrontendVoiceTools
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import com.fghbuild.voicegateway.sdk.v2.AssistantTextDelta
import com.fghbuild.voicegateway.sdk.v2.ContextUpdate
import com.fghbuild.voicegateway.sdk.v2.Error
import com.fghbuild.voicegateway.sdk.v2.MessageEnvelope
import com.fghbuild.voicegateway.sdk.v2.MessageKind
import com.fghbuild.voicegateway.sdk.v2.SessionSetup
import com.fghbuild.voicegateway.sdk.v2.SessionStatus
import com.fghbuild.voicegateway.sdk.v2.SessionUsage
import com.fghbuild.voicegateway.sdk.v2.TokenUsage
import com.fghbuild.voicegateway.sdk.v2.ToolCall
import com.fghbuild.voicegateway.sdk.v2.ToolResult
import com.fghbuild.voicegateway.sdk.v2.TurnCancel
import com.fghbuild.voicegateway.sdk.v2.TurnState
import com.fghbuild.voicegateway.sdk.v2.TurnStatus
import com.fghbuild.voicegateway.sdk.v2.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "GoModeDeviceVoice"
private const val TEXT_SESSION_PATH = "/api/voicegateway/v2/voice/text"
private const val RECOVERY_READINESS_TIMEOUT_MS = 15_000L

private val VoiceState.needsThinkingAudio: Boolean
    get() = connected && turnState == TurnState.Thinking && !speaking && !muted

private val deviceWebSocketClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
}

/** textSessionWebSocketURL maps a gateway base URL to the text session WebSocket URL. */
internal fun textSessionWebSocketURL(
    gatewayEndpointURL: String,
    choiceID: String? = null,
): String {
    val httpURL =
        (gatewayEndpointURL.trimEnd('/') + TEXT_SESSION_PATH)
            .toHttpUrl()
            .newBuilder()
            .apply { choiceID?.let { addQueryParameter("choiceID", it) } }
            .build()
            .toString()
    return when {
        httpURL.startsWith("https://") -> "wss://" + httpURL.removePrefix("https://")
        httpURL.startsWith("http://") -> "ws://" + httpURL.removePrefix("http://")
        else -> error("Unsupported voice gateway URL: $gatewayEndpointURL")
    }
}

/**
 * DeviceVoiceSession owns Android speech recognition, synthesis, and turn orchestration.
 * [OnDeviceEngine.REMOTE_GATEWAY] sends recognized text to the gateway over WebSocket;
 * [OnDeviceEngine.LOCAL_LLM] runs inference on the phone. Tool calls use the same session routing.
 *
 * Microphone capture and recognition stay active during generation and playback.
 * A nonblank recognition hypothesis cancels playback and the current reply.
 */
internal class DeviceVoiceSession(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val settingsClient: ServiceSettingsClient = ServiceSettingsClient(),
    private val bearerTokenFor: (String) -> String? = { null },
    private val frontendTools: FrontendVoiceTools = FrontendVoiceTools(),
    private val isAuthorityCurrent: () -> Boolean = { true },
    voiceConversation: VoiceConversation? = null,
    private val languageTag: String,
    private val localModelOwner: LocalModelOwner = (appContext.applicationContext as GoModeApplication).localModelOwner,
    private val speech: DeviceSpeech = AndroidDeviceSpeech(appContext, languageTag),
    voiceChimePlayer: VoiceChimePlayer = AndroidVoiceChime(),
    private val thinkingEarcon: ThinkingEarcon = AndroidThinkingEarcon(appContext),
    private val callController: VoiceCallController = NoopVoiceCallController(),
    private val switchServer: suspend (String) -> Unit = { id -> settingsRepository.switchService(id) },
    private val startupTiming: () -> VoiceStartupTiming? = { null },
    private val setupProvider: suspend (String) -> VoiceSetup = { sessionID ->
        prepareVoiceSetup(settingsRepository, settingsClient, bearerTokenFor, sessionID = sessionID)
    },
    private val webSocketFactory: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        deviceWebSocketClient.newWebSocket(request, listener)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val engineFactory: (OnDeviceEngine) -> OnDeviceVoiceEngine = { engineType ->
        createOnDeviceEngine(appContext, engineType, ioDispatcher)
    },
) : VoiceSessionController {
    private val sharedConversation = voiceConversation
    private var voiceConversation: VoiceConversation? = null
    private val foreground = VoiceForegroundLease(appContext)
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var toolWork = SupervisorJob()
    private val attemptGeneration = AtomicLong()
    private val pendingGatewayTools = mutableMapOf<Long, Int>()
    private var awaitingModelTurn = false

    // Inference drain ownership survives output cancellation and is transferred
    // synchronously when a replacement turn is queued behind the mutex.
    private var localInferenceOwner = 0L

    private var pendingUserUtterance: String? = null
    private var userSpeechActive = false
    private var listeningGeneration = 0L
    private val voiceModeChime = VoiceModeChime(voiceChimePlayer)
    private val errorLog = (appContext.applicationContext as GoModeApplication).voiceErrors

    private val _state = MutableStateFlow(VoiceState())
    override val state: StateFlow<VoiceState> = _state.asStateFlow()
    val speechLanguageState = speech.languageState
    private var languageJob: Job? = null

    fun checkSpeechLanguage() {
        if (state.value.connected || state.value.connectStatus != null || languageJob?.isActive == true) return
        languageJob = scope.launch { speech.checkLanguage() }
    }

    fun downloadSpeechLanguage() {
        if (state.value.connected || state.value.connectStatus != null || languageJob?.isActive == true) return
        languageJob = scope.launch { speech.downloadLanguage() }
    }

    private val thinkingAudioJob =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state
                .map {
                    it.needsThinkingAudio
                }.distinctUntilChanged()
                .collectLatest { thinking ->
                    thinkingEarcon.setThinking(false)
                    if (thinking) {
                        // Keep quick turns and short pauses between speech fragments silent.
                        delay(200)
                        thinkingEarcon.setThinking(state.value.needsThinkingAudio)
                    }
                }
        }

    private var connectJob: Job? = null
    private val networkMonitor = DefaultNetworkMonitor(appContext)
    private var networkJob: Job? = null
    private var reconnectJob: Job? = null
    private var readinessJob: Job? = null
    private val recoveryPolicy = VoiceRecoveryPolicy(maxAttempts = MAX_RECONNECT_ATTEMPTS)
    private var recoveryContext = ""
    private var pinnedVoice: GatewayVoiceSelection? = null

    // Recovery keeps its captured request instead of acquiring a newer UI owner.
    private var startupTimingOwner: VoiceStartupTiming? = null
    private var webSocket: WebSocket? = null
    private var otherEngine: OnDeviceVoiceEngine? = null
    private var preparedLocalModel: PreparedLocalModel? = null
    private val engine: OnDeviceVoiceEngine?
        get() = preparedLocalModel?.engine ?: otherEngine
    private var otherSetup: VoiceSetup? = null
    private val setup: VoiceSetup?
        get() = preparedLocalModel?.setup ?: otherSetup
    private val mcpClient: McpClient?
        get() = setup?.mcpClient
    private val sessionTools: List<ToolDescriptor>
        get() = setup?.tools.orEmpty()
    private var frontendToolNames: Set<String> = emptySet()
    private var conversationID = UUID.randomUUID().toString()

    // Track reply completion across gaps between streamed speech fragments.
    private var speakerActive = false
    private var turnIdle = false
    private var pendingSpeech = 0
    private var speechGeneration = 0L
    private val localTurnGeneration = AtomicLong()
    private val localTurnLock = Mutex()
    private var suppressReply = false
    private var awaitingReply = false
    private var pendingCancellations = 0
    private var remoteTurnActive = false
    private val pendingNotifications = PendingVoiceContext()
    private val segmenter = SentenceSegmenter()
    private val speechTextFilter = SpeechTextFilter()

    private val audioStateJob =
        scope.launch {
            callController.audioState.collect { audio ->
                _state.update {
                    it.withCallAudio(audio)
                }
            }
        }

    private val microphoneLevelJob =
        scope.launch {
            speech.microphoneRms.collect { rms ->
                _state.update {
                    val level =
                        if (it.connected && !it.muted) microphoneLevelFromRms(rms) else 0f
                    it.copy(micLevel = level)
                }
            }
        }

    override fun connect(
        preserveTranscript: Boolean,
        conversationID: String?,
    ) {
        if (!preserveTranscript) startupTimingOwner = startupTiming()
        val timing = startupTimingOwner
        thinkingEarcon.setThinking(false)
        _state.update {
            it.copy(turnState = TurnState.Idle, connected = false, listening = false, speaking = false, micLevel = 0f)
        }
        val attempt = invalidateAttempt()
        cancelReadinessTimeout()
        reconnectJob?.cancel()
        reconnectJob = null
        awaitingModelTurn = false
        connectJob?.cancel()
        connectJob = null
        languageJob?.cancel()
        languageJob = null
        closeWebSocket()
        releaseEngine()
        speech.stopListening()
        speech.stopSpeaking()
        speakerActive = false
        turnIdle = false
        pendingSpeech = 0
        suppressReply = false
        awaitingReply = false
        pendingCancellations = 0
        remoteTurnActive = false
        segmenter.clear()
        speechTextFilter.clear()
        otherSetup = null
        if (!preserveTranscript) {
            pendingNotifications.clear()
            recoveryPolicy.reset()
            recoveryContext = ""
            pinnedVoice = null
            this.conversationID = conversationID ?: UUID.randomUUID().toString()
            clearTranscript()
            _state.update { it.copy(usage = null, toolHistory = emptyList()) }
        }
        if (!preserveTranscript && !frontendTools.isReady) {
            setError(FRONTEND_NOT_READY)
            return
        }
        if (!speech.isAvailable()) {
            setError("On-device speech recognition is not available")
            return
        }
        val engineType = settingsRepository.settings.value.onDeviceEngine
        voiceConversation =
            sharedConversation.takeIf {
                engineType == OnDeviceEngine.REMOTE_GATEWAY || engineType == OnDeviceEngine.LOCAL_LLM
            }
        try {
            if (engineType == OnDeviceEngine.REMOTE_GATEWAY) {
                voiceConversation?.begin(preserveTranscript, this.conversationID)
                voiceConversation?.let { this.conversationID = it.sessionID }
            }
        } catch (e: VoiceSetupException) {
            setError(e.message ?: "Gateway conversation is unavailable")
            return
        }
        setStatus("Checking speech language…")
        connectJob =
            scope.launch {
                try {
                    timing?.enter(VoiceStartupStage.SPEECH_LANGUAGE)
                    val language = speech.checkLanguage()
                    if (!ownsAttempt(attempt)) return@launch
                    if (language != SpeechLanguageState.Ready && language !is SpeechLanguageState.Unverified) {
                        timing?.finish(VoiceStartupOutcome.FAILED)
                        disconnect()
                        return@launch
                    }
                    setStatus("Connecting…")
                    if (engineType != OnDeviceEngine.LOCAL_LLM) {
                        foreground.start(initialVoiceCallerName(settingsRepository.settings.value))
                    }
                    if (engineType == OnDeviceEngine.LOCAL_LLM) {
                        checkNotNull(voiceConversation) { "Local conversation owner is unavailable" }
                        setStatus("Preparing local model…")
                        timing?.enter(VoiceStartupStage.MODEL)
                        preparedLocalModel = localModelOwner.acquire()
                        this@DeviceVoiceSession.conversationID = checkNotNull(preparedLocalModel).lifetime.sessionID
                    }
                    timing?.enter(VoiceStartupStage.TOOLS)
                    val setup =
                        preparedLocalModel?.setup ?: try {
                            voiceConversation?.prepare(pinnedVoice)
                                ?: setupProvider(this@DeviceVoiceSession.conversationID)
                        } catch (e: VoiceSetupException) {
                            if (engineType == OnDeviceEngine.REMOTE_GATEWAY || engineType == OnDeviceEngine.LOCAL_LLM) {
                                if (ownsAttempt(attempt)) setError(e.message ?: "Voice setup failed")
                                return@launch
                            } else {
                                val selected = settingsRepository.settings.value
                                standaloneVoiceSetup().copy(
                                    serverName = initialVoiceCallerName(selected),
                                    serverID = selected.activeServiceId.takeIf { it.isNotEmpty() },
                                )
                            }
                        }
                    if (!ownsAttempt(attempt)) return@launch
                    if (!preserveTranscript &&
                        (!frontendTools.isReady || voiceConversation?.frontendReadyForConnection() == false)
                    ) {
                        setError(FRONTEND_NOT_READY)
                        return@launch
                    }
                    foreground.start(setup.serverName)
                    if (preparedLocalModel == null) otherSetup = setup
                    if (!callController.isStarted && callController.isSupported() &&
                        !callController.start(setup.serverName, setup.serverID) { disconnect() }
                    ) {
                        setError("Could not start the voice call")
                        return@launch
                    }
                    timing?.enter(VoiceStartupStage.TRANSPORT)
                    if (engineType == OnDeviceEngine.REMOTE_GATEWAY) {
                        pinnedVoice =
                            setup.choiceID?.let {
                                GatewayVoiceSelection(setup.gatewayEndpointURL, it, setup.voiceName)
                            }
                        startNetworkMonitoring()
                        if (preserveTranscript) startReadinessTimeout(attempt)
                        openWebSocket(attempt, setup, timing)
                    } else {
                        openLocalEngine(attempt, setup, engineType)
                    }
                } catch (e: VoiceMcpAuthChangedException) {
                    if (ownsAttempt(attempt)) {
                        Log.i(TAG, "Service authentication changed during voice setup", e)
                        disconnect()
                    }
                } catch (e: TimeoutCancellationException) {
                    if (ownsAttempt(attempt)) timing?.noteTimeout()
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (ownsAttempt(attempt)) {
                        if (preserveTranscript && engineType == OnDeviceEngine.REMOTE_GATEWAY && networkJob != null) {
                            scheduleReconnect("Network connection failed")
                        } else {
                            timing?.finishFailure(e)
                            setError("connection: ${describeFailure(e)}")
                        }
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    if (ownsAttempt(attempt)) {
                        timing?.finishFailure(e)
                        setError("connection: ${describeFailure(e)}")
                    }
                }
            }
    }

    private suspend fun openLocalEngine(
        attempt: Long,
        setup: VoiceSetup,
        engineType: OnDeviceEngine,
    ) {
        check(engineType != OnDeviceEngine.LOCAL_LLM || preparedLocalModel != null)
        val localEngine = preparedLocalModel?.engine ?: withContext(ioDispatcher) { engineFactory(engineType) }
        if (preparedLocalModel == null) otherEngine = localEngine
        val context =
            EngineSessionContext(
                languageTag = languageTag,
                systemInstruction = setup.systemInstruction,
                serviceContextText = setup.serviceContextText,
                tools = sessionTools,
                mcpClient = mcpClient,
            )
        val modelDisplay = localEngine.modelName ?: localEngine.descriptor.name
        setStatus("Loading $modelDisplay…")
        if (preparedLocalModel == null) {
            withContext(ioDispatcher) { localEngine.initialize(context) }
        }
        if (!ownsAttempt(attempt)) return
        voiceConversation?.let { localEngine.injectContext(it.contextText()) }
        frontendToolNames = frontendTools.declarations.map { it.name }.toSet()
        _state.update {
            it.copy(connectStatus = null, connected = true, error = null)
        }
        callController.activate()
        voiceModeChime.connected()
        val announcing = pendingNotifications.hasAlerts()
        flushPendingNotifications()
        if (!ownsAttempt(attempt)) return
        if (!announcing) {
            turnIdle = true
            speak("Ready")
        }
        maybeStartListening()
    }

    private fun openWebSocket(
        attempt: Long,
        setup: VoiceSetup,
        timing: VoiceStartupTiming?,
    ) {
        val request =
            Request
                .Builder()
                .url(textSessionWebSocketURL(setup.gatewayEndpointURL, setup.choiceID))
                .apply { webSocketHeaders(setup).forEach { (name, value) -> header(name, value) } }
                .build()
        val listener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    scope.launch {
                        if (!ownsAttempt(attempt)) {
                            webSocket.close(1000, null)
                            return@launch
                        }
                        this@DeviceVoiceSession.webSocket = webSocket
                        setStatus("Waiting for server…")
                        frontendToolNames = frontendTools.declarations.map { it.name }.toSet()
                        sendOn(
                            webSocket,
                            Json.encodeToString(
                                SessionSetup.serializer(),
                                gatewaySessionSetup(
                                    voiceToolDeclarations(sessionTools, frontendTools.declarations),
                                    setup.systemInstruction,
                                    setup.serviceContextText,
                                    languageTag,
                                    includeReasoning = settingsRepository.settings.value.showReasoning,
                                ),
                            ),
                            VOICE_MESSAGE_DELIVERY_ERROR,
                        )
                    }
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    scope.launch {
                        if (ownsAttempt(attempt) && this@DeviceVoiceSession.webSocket === webSocket) {
                            handleServerMessage(text, attempt, webSocket)
                        }
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    scope.launch {
                        // A pre-open failure arrives before onOpen assigned the socket,
                        // so accept any socket while this attempt owns none.
                        val current = this@DeviceVoiceSession.webSocket
                        if (ownsAttempt(attempt) && (current == null || current === webSocket)) {
                            if (response == null && t is IOException &&
                                (_state.value.connected || recoveryPolicy.attempts > 0)
                            ) {
                                scheduleReconnect("Network connection lost")
                                return@launch
                            }
                            timing?.finishFailure(t)
                            val message =
                                response?.let { gatewayHTTPError(it.code, it.request.url.encodedPath) }
                                    ?: if (t is java.io.IOException) gatewayNetworkError(t).message else t.message
                            setError(message ?: "Voice connection failed")
                        }
                    }
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    scope.launch {
                        if (ownsAttempt(attempt) && this@DeviceVoiceSession.webSocket === webSocket) {
                            if (code in setOf(1001, 1011, 1012, 1013, 1014) &&
                                (_state.value.connected || recoveryPolicy.attempts > 0)
                            ) {
                                scheduleReconnect("Voice gateway restarting")
                            } else {
                                disconnect()
                            }
                        }
                    }
                }
            }
        val socket = webSocketFactory(request, listener)
        if (ownsAttempt(attempt)) {
            webSocket = socket
        }
    }

    private fun webSocketHeaders(setup: VoiceSetup): Map<String, String> =
        buildMap {
            putAll(setup.gatewayHeaders)
            setup.service?.let { service ->
                put("X-Service-Kind", service.kind)
                put("X-Service-Instance", service.instanceID)
                put("X-Service-Origin", service.baseURL)
                put("Authorization", "Bearer ${service.token}")
            }
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
                        scheduleReconnect(if (network == null) "Waiting for network" else "Network changed")
                    }
                }
            }
    }

    private fun stopNetworkMonitoring() {
        cancelReadinessTimeout()
        networkJob?.cancel()
        networkJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        networkMonitor.close()
    }

    private fun scheduleReconnect(reason: String) {
        if (reconnectJob != null) return
        if (!recoveryPolicy.schedule()) {
            setError("Voice connection lost after $MAX_RECONNECT_ATTEMPTS recovery attempts")
            return
        }
        val attempt = invalidateAttempt()
        cancelReadinessTimeout()
        connectJob?.cancel()
        connectJob = null
        val abandoned = webSocket
        webSocket = null
        abandoned?.cancel()
        speech.stopListening()
        speech.stopSpeaking()
        thinkingEarcon.setThinking(false)
        _state.update {
            it.copy(
                connected = false,
                listening = false,
                speaking = false,
                activeTool = null,
                turnState = TurnState.Idle,
                micLevel = 0f,
            )
        }
        setStatus("$reason; reconnecting…")
        reconnectJob =
            scope.launch {
                delay(1_000L)
                networkMonitor.network.filterNotNull().first()
                reconnectJob = null
                if (ownsAttempt(attempt) && recoveryPolicy.beginScheduledRecovery()) {
                    recoveryContext = buildNetworkRecoveryContext(_state.value.transcript)
                    connect(preserveTranscript = true)
                }
            }
    }

    private fun cancelReadinessTimeout() {
        readinessJob?.cancel()
        readinessJob = null
    }

    private fun startReadinessTimeout(attempt: Long) {
        cancelReadinessTimeout()
        // Fresh starts have a UI-owned startup budget. Recovery reuses a completed
        // startup lease, so this transport owns its replacement readiness deadline.
        readinessJob =
            scope.launch {
                delay(RECOVERY_READINESS_TIMEOUT_MS)
                readinessJob = null
                if (ownsAttempt(attempt)) setError("Voice connection timed out waiting for session readiness.")
            }
    }

    @Suppress("TooGenericExceptionCaught") // Error boundary: malformed messages must not crash.
    private suspend fun handleServerMessage(
        text: String,
        attempt: Long,
        origin: WebSocket,
    ) {
        if (!ownsAttempt(attempt) || webSocket !== origin) return
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
                    cancelReadinessTimeout()
                    recoveryPolicy.reset()
                    _state.update {
                        it.copy(connectStatus = null, connected = true, error = null)
                    }
                    callController.activate()
                    voiceModeChime.connected()
                    if (recoveryContext.isNotEmpty()) {
                        send(json.encodeToString(ContextUpdate.serializer(), gatewayContextUpdate(recoveryContext)))
                        recoveryContext = ""
                    }
                    val announcing = pendingNotifications.hasAlerts()
                    flushPendingNotifications()
                    if (!ownsAttempt(attempt) || webSocket !== origin) return
                    if (!announcing) sendUserMessage("Say exactly one word: Ready")
                    maybeStartListening()
                }

                MessageKind.SessionUsage -> {
                    val report = json.decodeFromString(SessionUsage.serializer(), text)
                    _state.update { it.withUsageReport(report) }
                }

                MessageKind.AssistantTextDelta -> {
                    val msg = json.decodeFromString(AssistantTextDelta.serializer(), text)
                    handleAssistantText(msg.text, msg.responseID)
                }

                MessageKind.TurnStatus -> {
                    val msg = json.decodeFromString(TurnStatus.serializer(), text)
                    if (msg.state == TurnState.Thinking && pendingCancellations == 0 &&
                        (!suppressReply || awaitingReply)
                    ) {
                        suppressReply = false
                        awaitingReply = false
                        remoteTurnActive = true
                        turnIdle = false
                    }
                    if (!suppressReply) {
                        if (msg.state != TurnState.Thinking) thinkingEarcon.setThinking(false)
                        _state.update { it.copy(turnState = msg.state) }
                    }
                    if (msg.state == TurnState.Idle && !awaitingReply && pendingCancellations == 0) {
                        if (!pendingGatewayTools.containsKey(attempt)) awaitingModelTurn = false
                        flushPendingNotifications()
                        if (!ownsAttempt(attempt) || webSocket !== origin) return
                        finalizeAssistantReply()
                        segmenter.push(speechTextFilter.finish()).forEach(::speak)
                        speak(segmenter.flush())
                        remoteTurnActive = false
                        turnIdle = true
                        finishReply()
                    }
                }

                MessageKind.ToolCall -> {
                    val msg = json.decodeFromString(ToolCall.serializer(), text)
                    if (suppressReply) {
                        // Retire calls in flight at cancellation without starting side
                        // effects or leaving the gateway waiting for their results.
                        sendToolResult(msg.id, msg.name, errorJson("Turn was interrupted"))
                    } else {
                        handleToolCall(msg, attempt, origin)
                    }
                }

                MessageKind.Interrupted -> {
                    if (pendingCancellations > 0) {
                        pendingCancellations--
                    } else {
                        remoteTurnActive = false
                        awaitingReply = false
                    }
                    finalizeAssistantReply()
                    segmenter.clear()
                    speechTextFilter.clear()
                    stopPlayback()
                    turnIdle = !remoteTurnActive
                    flushAfterRemoteInterruption()
                    if (!ownsAttempt(attempt) || webSocket !== origin) return
                    _state.update {
                        it.copy(
                            speaking = false,
                            activeTool = null,
                            turnState = if (turnIdle && pendingUserUtterance == null) TurnState.Idle else it.turnState,
                        )
                    }
                    finishReply()
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
            if (ownsAttempt(attempt) && webSocket === origin) {
                setError(e.message ?: "Failed to process server message")
            }
        }
    }

    private fun handleAssistantText(
        delta: String,
        responseID: String? = null,
    ) {
        if (delta.isEmpty() || suppressReply) return
        turnIdle = false
        _state.update {
            it.withTranscriptChunk(TranscriptSpeaker.ASSISTANT, delta, responseID = responseID)
        }
        segmenter.push(speechTextFilter.push(delta)).forEach(::speak)
    }

    private fun speak(fragment: String) {
        if (fragment.isBlank() || suppressReply) return
        thinkingEarcon.setThinking(false)
        val attempt = attemptGeneration.get()
        val playback = speechGeneration
        pendingSpeech++
        speakerActive = true
        _state.update { it.copy(speaking = true) }
        applyMicrophoneMode()
        speech.speak(
            fragment,
            onDone = { if (playback == speechGeneration) onSpeechDone(attempt) },
            onError = { message -> if (playback == speechGeneration) reportAttemptError(attempt, message) },
        )
    }

    private fun appendToolDiagnostic(
        name: String,
        message: String,
    ) {
        val diagnostic = TranscriptEntry(TranscriptSpeaker.ASSISTANT, "[$name] $message", final = true)
        _state.update { state ->
            val reply =
                state.transcript.lastOrNull()?.takeIf {
                    it.speaker == TranscriptSpeaker.ASSISTANT && !it.final
                }
            // Keep the streamed reply at the tail so later deltas extend it and scrolling follows it.
            state.copy(
                transcript =
                    if (reply == null) {
                        state.transcript + diagnostic
                    } else {
                        state.transcript.dropLast(1) + diagnostic + reply
                    },
            )
        }
    }

    // Generation owns transcript boundaries; playback can finish between streamed fragments.
    private fun finalizeAssistantReply() {
        _state.update { state ->
            val last = state.transcript.lastOrNull()
            if (last?.speaker != TranscriptSpeaker.ASSISTANT || last.final) {
                state
            } else {
                state.copy(transcript = state.transcript.dropLast(1) + last.copy(final = true))
            }
        }
    }

    // Speech callbacks carry the attempt that started them so a delayed callback
    // from a superseded session cannot change the current one.
    private fun onSpeechDone(attempt: Long) {
        if (attempt != attemptGeneration.get()) return
        pendingSpeech = maxOf(0, pendingSpeech - 1)
        if (pendingSpeech > 0) return
        speakerActive = false
        _state.update { it.copy(speaking = false) }
        flushPendingNotifications()
        if (!ownsAttempt(attempt)) return
        finishReply()
    }

    // A playback gap between streamed fragments does not finish the reply.
    private fun finishReply() {
        if (!turnIdle || pendingSpeech > 0 || speakerActive || remoteTurnActive || pendingUserUtterance != null) return
        _state.update { it.copy(microphoneMode = it.microphoneMode.afterReply()) }
        maybeStartListening()
    }

    private fun reportAttemptError(
        attempt: Long,
        message: String,
    ) {
        if (attempt != attemptGeneration.get()) return
        setError(message)
    }

    private fun maybeStartListening() {
        if (!_state.value.connected || _state.value.muted) return
        if (_state.value.listening) return
        val attempt = attemptGeneration.get()
        val recognition = ++listeningGeneration

        fun currentRecognition() = ownsAttempt(attempt) && recognition == listeningGeneration && _state.value.listening
        _state.update { it.copy(listening = true) }
        speech.startListening(
            onResult = { text -> if (currentRecognition()) handleUserUtterance(attempt, text) },
            onPartial = { text ->
                if (currentRecognition()) {
                    if (text.isNotBlank()) {
                        userSpeechActive = true
                        interruptReply()
                    }
                    if (currentRecognition()) {
                        _state.update { it.copy(transcript = it.transcript.replaceUserDraft(text, final = false)) }
                    }
                }
            },
            onIdle = { if (currentRecognition()) handleListeningIdle(attempt) },
            onError = { message ->
                if (currentRecognition()) {
                    when (speech.languageState.value) {
                        SpeechLanguageState.Missing, SpeechLanguageState.Unsupported -> disconnect()
                        else -> reportAttemptError(attempt, message)
                    }
                }
            },
        )
    }

    private fun handleListeningIdle(attempt: Long) {
        if (attempt != attemptGeneration.get()) return
        userSpeechActive = false
        _state.update { it.copy(listening = false, transcript = it.transcript.withoutUserDraft()) }
        flushPendingNotifications()
        maybeStartListening()
    }

    private fun handleUserUtterance(
        attempt: Long,
        text: String,
    ) {
        if (attempt != attemptGeneration.get()) return
        userSpeechActive = true
        interruptReply()
        userSpeechActive = false
        if (!ownsAttempt(attempt)) return
        turnIdle = false
        segmenter.clear()
        speechTextFilter.clear()
        _state.update {
            it.copy(
                listening = false,
                turnState = TurnState.Thinking,
                transcript = it.transcript.replaceUserDraft(text, final = true),
            )
        }
        val currentEngine = engine
        if (currentEngine != null && currentEngine.engineType != OnDeviceEngine.REMOTE_GATEWAY) {
            executeLocalTurn(attempt, text, currentEngine)
        } else if (pendingNotifications.isNotEmpty() && awaitingModelTurn) {
            // Keep the replacement behind canceled model/tool work so its focus
            // update reaches the gateway before the next user message.
            pendingUserUtterance = text
        } else {
            flushPendingNotifications(allowResponse = false)
            awaitingReply = true
            sendUserMessage(text)
        }
        maybeStartListening()
    }

    private fun stopPlayback() {
        speechGeneration++
        speech.stopSpeaking()
        pendingSpeech = 0
        speakerActive = false
        _state.update { it.copy(speaking = false) }
    }

    private fun interruptReply() {
        val attempt = attemptGeneration.get()
        val remote = engine == null || engine?.engineType == OnDeviceEngine.REMOTE_GATEWAY
        if (suppressReply && (!remote || !remoteTurnActive)) return
        val generating =
            if (remote) {
                remoteTurnActive || awaitingModelTurn || pendingGatewayTools.containsKey(attemptGeneration.get())
            } else {
                !turnIdle
            }
        val activeReply = generating || pendingSpeech > 0 || speakerActive
        suppressReply = true
        awaitingReply = false
        remoteTurnActive = false
        turnIdle = true
        localTurnGeneration.incrementAndGet()
        finalizeAssistantReply()
        segmenter.clear()
        speechTextFilter.clear()
        _state.update { it.copy(turnState = TurnState.Idle, activeTool = null) }
        if (!activeReply) return
        stopPlayback()
        // Playback may be the last owner holding context after generation finishes.
        // Active model turns and tools keep their existing notification guards.
        flushPendingNotifications()
        if (!ownsAttempt(attempt)) return
        if (remote) {
            pendingCancellations++
            send(json.encodeToString(TurnCancel.serializer(), TurnCancel(MessageKind.TurnCancel, "user speech")))
        }
    }

    private fun executeLocalTurn(
        attempt: Long,
        text: String,
        activeEngine: OnDeviceVoiceEngine,
        serviceUpdate: Boolean = false,
    ) {
        val turn = localTurnGeneration.incrementAndGet()
        val responseID = if (activeEngine.engineType == OnDeviceEngine.LOCAL_LLM) UUID.randomUUID().toString() else null
        localInferenceOwner = turn
        suppressReply = false
        awaitingModelTurn = true
        turnIdle = false
        scope.launch {
            localTurnLock.withLock {
                fun currentTurn() = ownsAttempt(attempt) && turn == localTurnGeneration.get()
                if (!currentTurn()) {
                    if (ownsAttempt(attempt) && localInferenceOwner == turn) {
                        awaitingModelTurn = false
                        flushPendingNotifications()
                    }
                    return@withLock
                }
                if (voiceConversation?.isCurrent() == false) {
                    disconnect()
                    return@withLock
                }
                awaitingModelTurn = true
                _state.update { it.copy(turnState = TurnState.Thinking) }
                // Engine callbacks arrive in order; join the last Main delivery before
                // tools or completion so finalization cannot overtake a queued text delta.
                var lastDelta: Job? = null
                try {
                    if (!serviceUpdate) {
                        pendingNotifications.drain()?.let { activeEngine.injectContext(it.text) }
                    }
                    activeEngine.processTurn(
                        input = UserTurnInput(text = text, serviceUpdate = serviceUpdate),
                        onDelta = { delta ->
                            lastDelta =
                                scope.launch {
                                    if (currentTurn()) handleAssistantText(delta, responseID)
                                }
                        },
                        onUsage = { usage ->
                            // Measured work survives a failed or interrupted turn. The attempt
                            // check still prevents delivery into a replacement conversation.
                            withContext(NonCancellable + Dispatchers.Main) {
                                lastDelta?.join()
                                if (ownsAttempt(attempt)) {
                                    val counts =
                                        TokenUsage(
                                            inputTokens = usage.inputTokens,
                                            outputTokens = usage.outputTokens,
                                        )
                                    _state.update {
                                        it.withUsageReport(
                                            SessionUsage(
                                                MessageKind.SessionUsage,
                                                sessionID = checkNotNull(responseID),
                                                provider = "LiteRT-LM",
                                                model = checkNotNull(activeEngine.modelName),
                                                totals = counts,
                                                responseID = responseID,
                                                responseUsage = counts,
                                                contextTokens = usage.contextTokens,
                                                contextWindow = usage.contextWindow,
                                            ),
                                        )
                                    }
                                }
                            }
                        },
                        onToolCall = { toolCall ->
                            lastDelta?.join()
                            withContext(Dispatchers.Main) {
                                if (currentTurn()) {
                                    handleToolCallLocal(toolCall, attempt, turn)
                                } else {
                                    errorJson("Turn was interrupted")
                                }
                            }
                        },
                    )
                    lastDelta?.join()
                    if (currentTurn()) {
                        awaitingModelTurn = false
                        thinkingEarcon.setThinking(false)
                        finalizeAssistantReply()
                        segmenter.push(speechTextFilter.finish()).forEach(::speak)
                        speak(segmenter.flush())
                        turnIdle = true
                        _state.update { it.copy(turnState = TurnState.Idle) }
                        finishReply()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    if (currentTurn()) {
                        setError("llm: ${describeFailure(e)}")
                    }
                } finally {
                    if (ownsAttempt(attempt) && localInferenceOwner == turn) {
                        awaitingModelTurn = false
                        flushPendingNotifications()
                    }
                }
            }
        }
    }

    private suspend fun handleToolCallLocal(
        msg: ToolCall,
        attempt: Long,
        turn: Long,
    ): JsonElement {
        if (!ownsAttempt(attempt)) return errorJson("Voice session ended.")
        return coroutineScope {
            val invocation = currentCoroutineContext().job
            val cancellation = toolWork.invokeOnCompletion { invocation.cancel() }
            try {
                handleToolCallLocalOwned(msg, attempt, turn)
            } finally {
                cancellation.dispose()
            }
        }
    }

    private suspend fun handleToolCallLocalOwned(
        msg: ToolCall,
        attempt: Long,
        turn: Long,
    ): JsonElement {
        fun currentTurn() = ownsAttempt(attempt) && turn == localTurnGeneration.get()
        if (!currentTurn()) return errorJson("Turn was interrupted")
        if (voiceConversation?.isCurrent() == false) {
            disconnect()
            return errorJson("Conversation authorization changed")
        }
        val name = msg.name
        // Pin this call to its conversation before native/frontend dispatch can suspend.
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
                if (!currentTurn()) return errorJson("Turn was interrupted")
                _state.update { it.copy(activeTool = null) }
                return serverResult
            }
            val args = msg.args as? JsonObject ?: JsonObject(emptyMap())
            val conversation = voiceConversation
            if (conversation != null) {
                val result = conversation.execute(name, args, sessionID, languageTag) { currentTurn() }
                if (!currentTurn()) return errorJson("Turn was interrupted")
                _state.update { it.copy(activeTool = null) }
                return result?.structuredContent ?: errorJson("Unknown catalog tool")
            }
            val frontendResult =
                frontendTools.execute(name, args, sessionID) {
                    currentTurn()
                }
            if (frontendResult != null) {
                if (!currentTurn()) return errorJson("Turn was interrupted")
                _state.update { it.copy(activeTool = null) }
                return frontendResult
            }
            val client = mcpClient
            if (client == null) {
                _state.update { it.copy(activeTool = null) }
                return errorJson("No MCP client")
            }
            require(name !in frontendToolNames) { "Frontend voice tool is unavailable: $name" }
            val result = client.callTool(name, args, sessionID, locale = languageTag)
            if (!currentTurn()) return errorJson("Turn was interrupted")
            _state.update { it.copy(activeTool = null) }
            if (result.isError) {
                val errMsg = result.structuredContent["error"]?.jsonPrimitive?.content ?: "Tool error"
                Log.e(TAG, "Tool $name failed: $errMsg")
                appendToolDiagnostic(name, errMsg)
            }
            return result.structuredContent
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (!currentTurn()) return errorJson("Turn was interrupted")
            if (voiceConversation?.isCurrent() == false) {
                disconnect()
                return errorJson("Conversation authorization changed")
            }
            _state.update { it.copy(activeTool = null) }
            val errMsg = e.message ?: "Unknown error"
            Log.e(TAG, "Tool $name threw: $errMsg", e)
            return errorJson(errMsg)
        }
    }

    private suspend fun handleToolCall(
        msg: ToolCall,
        attempt: Long,
        origin: WebSocket,
    ) {
        if (!ownsAttempt(attempt)) return
        coroutineScope {
            val invocation = currentCoroutineContext().job
            val cancellation = toolWork.invokeOnCompletion { invocation.cancel() }
            try {
                handleToolCallOwned(msg, attempt, origin)
            } finally {
                cancellation.dispose()
            }
        }
    }

    private suspend fun handleToolCallOwned(
        msg: ToolCall,
        attempt: Long,
        origin: WebSocket,
    ) {
        if (voiceConversation != null) awaitingModelTurn = true
        pendingGatewayTools[attempt] = (pendingGatewayTools[attempt] ?: 0) + 1
        try {
            dispatchToolCall(msg, attempt, origin)
        } finally {
            val pending = (pendingGatewayTools[attempt] ?: 1) - 1
            if (pending == 0) pendingGatewayTools.remove(attempt) else pendingGatewayTools[attempt] = pending
            if (voiceConversation != null && ownsAttempt(attempt)) flushAfterRemoteInterruption()
        }
    }

    private suspend fun dispatchToolCall(
        msg: ToolCall,
        attempt: Long,
        origin: WebSocket,
    ) {
        if (!ownsAttempt(attempt) || webSocket !== origin) return
        if (voiceConversation?.isCurrent() == false) {
            disconnect()
            return
        }
        val id = msg.id
        val name = msg.name
        // Pin this call to its conversation before native/frontend dispatch can suspend.
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
                if (!ownsAttempt(attempt) || webSocket !== origin) return
                _state.update { it.copy(activeTool = null) }
                sendToolResult(id, name, serverResult)
                return
            }
            val conversation = voiceConversation
            if (conversation != null) {
                val result =
                    conversation.execute(
                        name,
                        msg.args as? JsonObject ?: JsonObject(emptyMap()),
                        sessionID,
                        languageTag,
                    ) {
                        ownsAttempt(attempt) && webSocket === origin
                    }
                if (!ownsAttempt(attempt) || webSocket !== origin) return
                _state.update { it.copy(activeTool = null) }
                sendToolResult(id, name, result?.structuredContent ?: errorJson("Unknown catalog tool"))
                return
            }
            val frontendResult =
                frontendTools.execute(name, msg.args as? JsonObject ?: JsonObject(emptyMap()), sessionID) {
                    ownsAttempt(attempt) && webSocket === origin
                }
            if (frontendResult != null) {
                if (!ownsAttempt(attempt) || webSocket !== origin) return
                _state.update { it.copy(activeTool = null) }
                sendToolResult(id, name, frontendResult)
                return
            }
            val client = mcpClient
            if (client == null) {
                _state.update { it.copy(activeTool = null) }
                sendToolResult(id, name, errorJson("No MCP client"))
                return
            }
            val args = msg.args as? JsonObject ?: JsonObject(emptyMap())
            require(name !in frontendToolNames) { "Frontend voice tool is unavailable: $name" }
            val result = client.callTool(name, args, sessionID, locale = languageTag)
            if (!ownsAttempt(attempt) || webSocket !== origin) return
            _state.update { it.copy(activeTool = null) }
            if (result.isError) {
                val errMsg = result.structuredContent["error"]?.jsonPrimitive?.content ?: "Tool error"
                Log.e(TAG, "Tool $name failed: $errMsg")
                appendToolDiagnostic(name, errMsg)
            }
            sendToolResult(id, name, result.structuredContent)
        } catch (e: VoiceMcpAuthChangedException) {
            if (ownsAttempt(attempt) && webSocket === origin) {
                Log.i(TAG, "Service authentication changed before MCP tool call", e)
                disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (!ownsAttempt(attempt) || webSocket !== origin) return
            _state.update { it.copy(activeTool = null) }
            val errMsg = e.message ?: "Unknown error"
            Log.e(TAG, "Tool $name threw: $errMsg", e)
            sendToolResult(id, name, errorJson(errMsg))
        }
    }

    override fun disconnect() {
        stopNetworkMonitoring()
        recoveryPolicy.reset()
        recoveryContext = ""
        pinnedVoice = null
        languageJob?.cancel()
        languageJob = null
        voiceConversation?.finish()
        thinkingEarcon.setThinking(false)
        invalidateAttempt()
        connectJob?.cancel()
        connectJob = null
        closeWebSocket()
        releaseEngine()
        speech.stopListening()
        speech.stopSpeaking()
        pendingNotifications.clear()
        speakerActive = false
        turnIdle = false
        pendingSpeech = 0
        suppressReply = false
        awaitingReply = false
        pendingCancellations = 0
        remoteTurnActive = false
        segmenter.clear()
        speechTextFilter.clear()
        voiceModeChime.disconnected { }
        callController.endLocal()
        foreground.stop()
        otherSetup = null
        // Preserve transcript so the user can review it after disconnecting.
        _state.value =
            VoiceState(
                usage = _state.value.usage,
                toolHistory = _state.value.toolHistory,
                transcript =
                    _state.value.transcript
                        .withoutUserDraft()
                        .map { it.copy(final = true) },
            ).withCallAudio(callController.audioState.value)
    }

    override fun setError(message: String) {
        stopNetworkMonitoring()
        recordFailure(message)
        voiceConversation?.finish()
        thinkingEarcon.setThinking(false)
        invalidateAttempt()
        connectJob?.cancel()
        connectJob = null
        closeWebSocket()
        releaseEngine()
        speech.stopListening()
        speech.stopSpeaking()
        pendingSpeech = 0
        speakerActive = false
        suppressReply = true
        pendingNotifications.clear()
        segmenter.clear()
        speechTextFilter.clear()
        callController.endLocal()
        foreground.stop()
        otherSetup = null
        Log.e(TAG, "setError: $message")
        _state.update {
            it.copy(
                connectStatus = null,
                connected = false,
                listening = false,
                speaking = false,
                turnState = TurnState.Idle,
                activeTool = null,
                error = message,
                micLevel = 0f,
                errorId = it.errorId + 1,
                transcript = it.transcript.withoutUserDraft(),
            )
        }
    }

    /** Retains a failure next to the engine and phase that produced it. */
    private fun recordFailure(message: String) {
        errorLog.record(
            engine = engineLabel(),
            phase = state.value.failurePhase(),
            message = message,
        )
    }

    /** Names the configured engine even when a failure precedes engine construction. */
    private fun engineLabel(): String {
        engine?.modelName?.let { return it }
        val settings = settingsRepository.settings.value
        return when (settings.onDeviceEngine) {
            OnDeviceEngine.LOCAL_LLM -> settings.localLlmModel.label
            OnDeviceEngine.NEEDLE_WHISTLE, OnDeviceEngine.REMOTE_GATEWAY -> settings.onDeviceEngine.descriptor().name
        }
    }

    override fun setMicrophoneMode(mode: MicrophoneMode) {
        _state.update { it.copy(microphoneMode = mode) }
        applyMicrophoneMode()
    }

    private fun applyMicrophoneMode() {
        if (_state.value.muted) {
            speech.stopListening()
            _state.update { it.copy(listening = false, micLevel = 0f) }
        } else {
            maybeStartListening()
        }
    }

    override fun selectAudioDevice(deviceId: Int) {
        callController.selectAudioDevice(deviceId)
    }

    override fun clearTranscript() {
        _state.update { it.copy(transcript = emptyList()) }
    }

    override fun injectText(
        text: String,
        generate: Boolean,
    ) {
        if (_state.value.error != null) return
        if (voiceConversation?.isCurrent() == false) {
            disconnect()
            return
        }
        if (userSpeechActive || speakerActive || awaitingModelTurn || (
                voiceConversation != null &&
                    (
                        !_state.value.connected || awaitingModelTurn ||
                            pendingGatewayTools.containsKey(attemptGeneration.get())
                    )
            )
        ) {
            if (!pendingNotifications.add(text, generate)) {
                setError("Too many pending service alerts. Reconnect voice.")
            }
            return
        }
        val currentEngine = engine
        if (currentEngine != null && currentEngine.engineType != OnDeviceEngine.REMOTE_GATEWAY) {
            val attempt = attemptGeneration.get()
            if (generate) {
                executeLocalTurn(attempt, text, currentEngine, serviceUpdate = true)
            } else {
                scope.launch {
                    localTurnLock.withLock {
                        if (ownsAttempt(attempt)) currentEngine.injectContext(text)
                    }
                }
            }
        } else {
            sendContextUpdate(text, generate)
        }
    }

    override fun close() {
        microphoneLevelJob.cancel()
        thinkingAudioJob.cancel()
        audioStateJob.cancel()
        disconnect()
        speech.close()
    }

    private fun List<TranscriptEntry>.withoutUserDraft(): List<TranscriptEntry> =
        if (lastOrNull()?.let { it.speaker == TranscriptSpeaker.USER && !it.final } == true) dropLast(1) else this

    private fun List<TranscriptEntry>.replaceUserDraft(
        text: String,
        final: Boolean,
    ): List<TranscriptEntry> =
        withoutUserDraft() +
            if (text.isBlank()) emptyList() else listOf(TranscriptEntry(TranscriptSpeaker.USER, text, final))

    private fun flushPendingNotifications(allowResponse: Boolean = true) {
        if (userSpeechActive || speakerActive || awaitingModelTurn) return
        val attempt = attemptGeneration.get()
        if (voiceConversation?.isCurrent() == false) {
            disconnect()
            return
        }
        if (voiceConversation != null && (
                speakerActive || awaitingModelTurn || !_state.value.connected ||
                    pendingGatewayTools.containsKey(attemptGeneration.get())
            )
        ) {
            return
        }
        val update = pendingNotifications.drain()
        if (update != null) {
            // A queued user utterance already requests the next response. Deliver
            // its service alerts as context before that request, without a competing turn.
            val generate = update.generate && allowResponse && pendingUserUtterance == null
            val currentEngine = engine
            if (currentEngine != null && currentEngine.engineType != OnDeviceEngine.REMOTE_GATEWAY) {
                val attempt = attemptGeneration.get()
                if (generate) {
                    executeLocalTurn(attempt, update.text, currentEngine, serviceUpdate = true)
                } else {
                    scope.launch {
                        localTurnLock.withLock {
                            if (ownsAttempt(attempt)) currentEngine.injectContext(update.text)
                        }
                    }
                }
            } else {
                sendContextUpdate(update.text, generate)
            }
        }
        if (!ownsAttempt(attempt)) return
        pendingUserUtterance?.let { text ->
            pendingUserUtterance = null
            userSpeechActive = false
            awaitingReply = true
            sendUserMessage(text)
        }
    }

    private fun flushAfterRemoteInterruption() {
        if (suppressReply && !remoteTurnActive && !awaitingReply && pendingCancellations == 0 &&
            !pendingGatewayTools.containsKey(attemptGeneration.get())
        ) {
            awaitingModelTurn = false
        }
        flushPendingNotifications()
    }

    private fun setStatus(status: String) {
        Log.i(TAG, status)
        _state.update { it.copy(connectStatus = status, error = null) }
    }

    private fun send(text: String) {
        val socket = webSocket
        if (socket != null) {
            sendOn(socket, text, VOICE_MESSAGE_DELIVERY_ERROR)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Transport boundary: terminate visibly without replaying a failed send.
    private fun sendOn(
        webSocket: WebSocket,
        text: String,
        failureMessage: String,
    ) {
        if (this.webSocket !== webSocket) return
        val accepted =
            try {
                webSocket.send(text)
            } catch (e: RuntimeException) {
                Log.w(TAG, "WebSocket send failed: ${e.javaClass.simpleName}")
                false
            }
        if (!accepted) {
            Log.w(TAG, "Voice message delivery failed: bytes=${text.toByteArray(Charsets.UTF_8).size}")
            setError(failureMessage)
        }
    }

    private fun sendUserMessage(text: String) {
        awaitingModelTurn = true
        remoteTurnActive = true
        turnIdle = false
        send(json.encodeToString(UserMessage.serializer(), gatewayUserMessage(text)))
    }

    private fun sendContextUpdate(
        text: String,
        generate: Boolean = false,
    ) {
        if (generate) {
            remoteTurnActive = true
            turnIdle = false
            awaitingReply = true
            awaitingModelTurn = true
        }
        send(json.encodeToString(ContextUpdate.serializer(), gatewayContextUpdate(text, generate)))
    }

    private fun sendToolResult(
        id: String,
        name: String,
        result: JsonElement,
    ) {
        val socket = webSocket ?: return
        sendOn(
            socket,
            json.encodeToString(ToolResult.serializer(), gatewayToolResult(id, name, result)),
            VOICE_TOOL_RESULT_DELIVERY_ERROR,
        )
    }

    private fun releaseEngine() {
        preparedLocalModel?.let(localModelOwner::release)
        preparedLocalModel = null
        otherEngine?.close()
        otherEngine = null
    }

    private fun closeWebSocket() {
        val current = webSocket
        webSocket = null
        current?.close(1000, null)
    }

    private fun invalidateAttempt(): Long {
        val attempt = attemptGeneration.incrementAndGet()
        toolWork.cancel()
        toolWork = SupervisorJob()
        pendingUserUtterance = null
        userSpeechActive = false
        speechGeneration++
        localTurnGeneration.incrementAndGet()
        return attempt
    }

    private fun ownsAttempt(attempt: Long): Boolean = attemptGeneration.get() == attempt && isAuthorityCurrent()
}

/** SentenceSegmenter splits streamed assistant text into sentence-sized speech fragments. */
internal class SentenceSegmenter {
    private val pending = StringBuilder()

    // Numeric periods need one character of lookahead, including across delta boundaries.
    // Long prose/code without punctuation is emitted in at most 240-character fragments.
    fun push(delta: String): List<String> {
        val fragments = mutableListOf<String>()
        for (c in delta) {
            if (pending.length >= 2 && pending.last() == '.' && pending[pending.length - 2].isDigit() &&
                !c.isDigit()
            ) {
                fragments.add(flush())
            }
            val numericPeriod = c == '.' && pending.lastOrNull()?.isDigit() == true
            pending.append(c)
            if ((c == '.' && !numericPeriod) || c == '!' || c == '?' || c == '\n') {
                val fragment = flush()
                if (fragment.isNotEmpty()) fragments.add(fragment)
            } else if (pending.length >= 240) {
                val space = pending.indexOfLast { it.isWhitespace() }
                val end = if (space > 0) space + 1 else pending.length
                val fragment = pending.substring(0, end).trim()
                if (fragment.isNotEmpty()) fragments.add(fragment)
                pending.delete(0, end)
            }
        }
        return fragments
    }

    fun flush(): String {
        val text = pending.toString().trim()
        pending.clear()
        return text
    }

    fun clear() {
        pending.clear()
    }
}
