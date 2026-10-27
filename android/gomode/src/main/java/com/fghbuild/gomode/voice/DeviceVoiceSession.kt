// Runs a voice session where the device transcribes and speaks and the gateway runs the text LLM.
package com.fghbuild.gomode.voice

import android.content.Context
import android.util.Log
import com.caic.voicegateway.sdk.v1.AssistantTextDelta
import com.caic.voicegateway.sdk.v1.ContextUpdate
import com.caic.voicegateway.sdk.v1.Error
import com.caic.voicegateway.sdk.v1.MessageEnvelope
import com.caic.voicegateway.sdk.v1.MessageKind
import com.caic.voicegateway.sdk.v1.SessionSetup
import com.caic.voicegateway.sdk.v1.ToolCall
import com.caic.voicegateway.sdk.v1.ToolResult
import com.caic.voicegateway.sdk.v1.TurnCancel
import com.caic.voicegateway.sdk.v1.TurnState
import com.caic.voicegateway.sdk.v1.TurnStatus
import com.caic.voicegateway.sdk.v1.UserMessage
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.web.FrontendVoiceTools
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "GoModeDeviceVoice"
private const val TEXT_SESSION_PATH = "/api/voicegateway/v1/voice/text"

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
 * DeviceVoiceSession runs the on-device voice mode: [SpeechRecognizer] transcribes,
 * [TextToSpeech] speaks, and the gateway runs only the text LLM turn and tools.
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
    private val setupProvider: suspend () -> VoiceSetup = {
        prepareVoiceSetup(settingsRepository, settingsClient, bearerTokenFor)
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
    private val attemptGeneration = AtomicLong()
    private val pendingGatewayTools = mutableMapOf<Long, Int>()
    private var awaitingModelTurn = false
    private var pendingUserUtterance: String? = null
    private var listeningGeneration = 0L
    private val voiceModeChime = VoiceModeChime(voiceChimePlayer)

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

    // Keep one-shot capture muted across gaps between streamed speech fragments.
    private var muteOnceActive = false
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
    private val pendingNotifications = ArrayList<String>()
    private val segmenter = SentenceSegmenter()

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
                        if (it.connected && !it.muted && !muteOnceActive) microphoneLevelFromRms(rms) else 0f
                    it.copy(micLevel = level)
                }
            }
        }

    override fun connect(
        preserveTranscript: Boolean,
        conversationID: String?,
    ) {
        thinkingEarcon.setThinking(false)
        _state.update {
            it.copy(turnState = TurnState.Idle, connected = false, listening = false, speaking = false, micLevel = 0f)
        }
        val attempt = invalidateAttempt()
        awaitingModelTurn = false
        connectJob?.cancel()
        connectJob = null
        languageJob?.cancel()
        languageJob = null
        closeWebSocket()
        releaseEngine()
        speech.stopListening()
        speech.stopSpeaking()
        pendingNotifications.clear()
        muteOnceActive = false
        speakerActive = false
        turnIdle = false
        pendingSpeech = 0
        suppressReply = false
        awaitingReply = false
        pendingCancellations = 0
        remoteTurnActive = false
        segmenter.clear()
        otherSetup = null
        if (!preserveTranscript) {
            this.conversationID = conversationID ?: UUID.randomUUID().toString()
            clearTranscript()
        }
        if (!frontendTools.isReady) {
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
            if (engineType == OnDeviceEngine.REMOTE_GATEWAY) voiceConversation?.begin(preserveTranscript)
        } catch (e: VoiceSetupException) {
            setError(e.message ?: "Gateway conversation is unavailable")
            return
        }
        setStatus("Checking speech language…")
        connectJob =
            scope.launch {
                try {
                    val language = speech.checkLanguage()
                    if (!ownsAttempt(attempt)) return@launch
                    if (language != SpeechLanguageState.Ready && language !is SpeechLanguageState.Unverified) {
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
                        preparedLocalModel = localModelOwner.acquire()
                    }
                    val setup =
                        preparedLocalModel?.setup ?: try {
                            voiceConversation?.prepare() ?: setupProvider()
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
                    if (!frontendTools.isReady || voiceConversation?.frontendReadyForConnection() == false) {
                        setError(FRONTEND_NOT_READY)
                        return@launch
                    }
                    foreground.start(setup.serverName)
                    if (preparedLocalModel == null) otherSetup = setup
                    if (callController.isSupported() &&
                        !callController.start(setup.serverName, setup.serverID) { disconnect() }
                    ) {
                        setError("Could not start the voice call")
                        return@launch
                    }
                    if (engineType == OnDeviceEngine.REMOTE_GATEWAY) {
                        openWebSocket(attempt, setup)
                    } else {
                        openLocalEngine(attempt, setup, engineType)
                    }
                } catch (e: VoiceMcpAuthChangedException) {
                    if (ownsAttempt(attempt)) {
                        Log.i(TAG, "Service authentication changed during voice setup", e)
                        disconnect()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    if (ownsAttempt(attempt)) setError("Voice connection failed: ${e.message}")
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
        flushPendingNotifications()
        turnIdle = true
        speak("Ready")
        maybeStartListening()
    }

    private fun openWebSocket(
        attempt: Long,
        setup: VoiceSetup,
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
                            json.encodeToString(
                                SessionSetup.serializer(),
                                gatewaySessionSetup(
                                    voiceToolDeclarations(sessionTools, frontendTools.declarations),
                                    setup.systemInstruction,
                                    setup.serviceContextText,
                                    languageTag,
                                ),
                            ),
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
                            val message =
                                response?.let { gatewayHTTPError(it.code, it.request.url.encodedPath) }
                                    ?: if (t is java.io.IOException) gatewayNetworkError(t).message else t.message
                            setError(message ?: "Voice connection failed. Retry to reconnect.")
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
                            disconnect()
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
                MessageKind.SessionReady -> {
                    Log.i(TAG, "session.ready received")
                    _state.update {
                        it.copy(connectStatus = null, connected = true, error = null)
                    }
                    callController.activate()
                    voiceModeChime.connected()
                    flushPendingNotifications()
                    sendUserMessage("Say exactly one word: Ready")
                    maybeStartListening()
                }

                MessageKind.AssistantTextDelta -> {
                    val msg = json.decodeFromString(AssistantTextDelta.serializer(), text)
                    handleAssistantText(msg.text)
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
                        finalizeAssistantReply()
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
                    stopPlayback()
                    turnIdle = !remoteTurnActive
                    flushAfterRemoteInterruption()
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
                    setError(msg.message)
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

    private fun handleAssistantText(delta: String) {
        if (delta.isEmpty() || suppressReply) return
        turnIdle = false
        _state.update {
            it.copy(transcript = it.transcript.appendChunk(TranscriptSpeaker.ASSISTANT, delta))
        }
        segmenter.push(delta).forEach(::speak)
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
        finishReply()
    }

    // A playback gap between streamed fragments does not finish the reply.
    private fun finishReply() {
        if (!turnIdle || pendingSpeech > 0 || speakerActive || remoteTurnActive || pendingUserUtterance != null) return
        muteOnceActive = false
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
        if (!_state.value.connected || _state.value.muted || muteOnceActive) return
        if (_state.value.listening) return
        val attempt = attemptGeneration.get()
        val recognition = ++listeningGeneration

        fun currentRecognition() = ownsAttempt(attempt) && recognition == listeningGeneration && _state.value.listening
        _state.update { it.copy(listening = true) }
        speech.startListening(
            onResult = { text -> if (currentRecognition()) handleUserUtterance(attempt, text) },
            onPartial = { text ->
                if (currentRecognition()) {
                    if (text.isNotBlank()) interruptReply()
                    _state.update { it.copy(transcript = it.transcript.replaceUserDraft(text, final = false)) }
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
        _state.update { it.copy(listening = false, transcript = it.transcript.withoutUserDraft()) }
        maybeStartListening()
    }

    private fun handleUserUtterance(
        attempt: Long,
        text: String,
    ) {
        if (attempt != attemptGeneration.get()) return
        interruptReply()
        turnIdle = false
        segmenter.clear()
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
        } else if (voiceConversation != null && pendingNotifications.isNotEmpty() && awaitingModelTurn) {
            // Keep the replacement behind canceled model/tool work so its focus
            // update reaches the gateway before the next user message.
            pendingUserUtterance = text
        } else {
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
        _state.update { it.copy(turnState = TurnState.Idle, activeTool = null) }
        if (!activeReply) return
        stopPlayback()
        // Playback may be the last owner holding context after generation finishes.
        // Active model turns and tools keep their existing notification guards.
        flushPendingNotifications()
        if (remote) {
            pendingCancellations++
            send(json.encodeToString(TurnCancel.serializer(), TurnCancel(MessageKind.TurnCancel, "user speech")))
        }
    }

    private fun executeLocalTurn(
        attempt: Long,
        text: String,
        activeEngine: OnDeviceVoiceEngine,
    ) {
        val turn = localTurnGeneration.incrementAndGet()
        suppressReply = false
        scope.launch {
            localTurnLock.withLock {
                fun currentTurn() = ownsAttempt(attempt) && turn == localTurnGeneration.get()
                if (!currentTurn()) return@withLock
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
                    activeEngine.processTurn(
                        input = UserTurnInput(text = text),
                        onDelta = { delta ->
                            lastDelta =
                                scope.launch {
                                    if (currentTurn()) handleAssistantText(delta)
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
                        setError("Voice turn failed: ${e.message}")
                    }
                } finally {
                    if (ownsAttempt(attempt)) {
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
            _state.update { it.copy(activeTool = name) }
            val serverResult =
                executeNativeVoiceTool(
                    name,
                    msg.args,
                    settingsRepository,
                    switchServer = switchServer,
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
                frontendTools.execute(name, args) {
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
            _state.update { it.copy(activeTool = name) }
            val serverResult =
                executeNativeVoiceTool(
                    name,
                    msg.args,
                    settingsRepository,
                    switchServer = switchServer,
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
                frontendTools.execute(name, msg.args as? JsonObject ?: JsonObject(emptyMap())) {
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
        muteOnceActive = false
        speakerActive = false
        turnIdle = false
        pendingSpeech = 0
        suppressReply = false
        awaitingReply = false
        pendingCancellations = 0
        remoteTurnActive = false
        segmenter.clear()
        voiceModeChime.disconnected { }
        callController.endLocal()
        foreground.stop()
        otherSetup = null
        // Preserve transcript so the user can review it after disconnecting.
        _state.value =
            VoiceState(
                transcript =
                    _state.value.transcript
                        .withoutUserDraft()
                        .map { it.copy(final = true) },
            ).withCallAudio(callController.audioState.value)
    }

    override fun setError(message: String) {
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
                error = message,
                micLevel = 0f,
                errorId = it.errorId + 1,
                transcript = it.transcript.withoutUserDraft(),
            )
        }
    }

    override fun setMicrophoneMode(mode: MicrophoneMode) {
        if (mode != MicrophoneMode.MUTE_ONCE) muteOnceActive = false
        _state.update { it.copy(microphoneMode = mode) }
        applyMicrophoneMode()
    }

    private fun applyMicrophoneMode() {
        if (_state.value.microphoneMode == MicrophoneMode.MUTE_ONCE && speakerActive) muteOnceActive = true
        if (_state.value.muted || muteOnceActive) {
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

    override fun injectText(text: String) {
        if (voiceConversation?.isCurrent() == false) {
            disconnect()
            return
        }
        if (speakerActive || (
                voiceConversation != null &&
                    (
                        !_state.value.connected || awaitingModelTurn ||
                            pendingGatewayTools.containsKey(attemptGeneration.get())
                    )
            )
        ) {
            if (voiceConversation != null) pendingNotifications.clear()
            pendingNotifications.add(text)
            return
        }
        val currentEngine = engine
        if (currentEngine != null && currentEngine.engineType != OnDeviceEngine.REMOTE_GATEWAY) {
            val attempt = attemptGeneration.get()
            scope.launch {
                if (ownsAttempt(attempt)) currentEngine.injectContext(text)
            }
        } else {
            sendContextUpdate(text)
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

    private fun flushPendingNotifications() {
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
        if (pendingNotifications.isNotEmpty()) {
            val text = pendingNotifications.joinToString("\n")
            pendingNotifications.clear()
            val currentEngine = engine
            if (currentEngine != null && currentEngine.engineType != OnDeviceEngine.REMOTE_GATEWAY) {
                val attempt = attemptGeneration.get()
                scope.launch {
                    if (ownsAttempt(attempt)) currentEngine.injectContext(text)
                }
            } else {
                sendContextUpdate(text)
            }
        }
        pendingUserUtterance?.let { text ->
            pendingUserUtterance = null
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
        webSocket?.let { sendOn(it, text) }
    }

    private fun sendOn(
        webSocket: WebSocket,
        text: String,
    ) {
        webSocket.send(text)
    }

    private fun sendUserMessage(text: String) {
        remoteTurnActive = true
        turnIdle = false
        send(json.encodeToString(UserMessage.serializer(), gatewayUserMessage(text)))
    }

    private fun sendContextUpdate(text: String) {
        send(json.encodeToString(ContextUpdate.serializer(), gatewayContextUpdate(text)))
    }

    private fun sendToolResult(
        id: String,
        name: String,
        result: JsonElement,
    ) {
        send(json.encodeToString(ToolResult.serializer(), gatewayToolResult(id, name, result)))
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
        pendingUserUtterance = null
        speechGeneration++
        localTurnGeneration.incrementAndGet()
        val attempt = attemptGeneration.incrementAndGet()
        return attempt
    }

    private fun ownsAttempt(attempt: Long): Boolean = attemptGeneration.get() == attempt && isAuthorityCurrent()
}

/** SentenceSegmenter splits streamed assistant text into sentence-sized speech fragments. */
internal class SentenceSegmenter {
    private val pending = StringBuilder()

    fun push(delta: String): List<String> {
        pending.append(delta)
        val fragments = mutableListOf<String>()
        var start = 0
        for (index in 0 until pending.length) {
            val c = pending[index]
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                val fragment = pending.substring(start, index + 1).trim()
                if (fragment.isNotEmpty()) fragments.add(fragment)
                start = index + 1
            }
        }
        if (start > 0) pending.delete(0, start)
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
