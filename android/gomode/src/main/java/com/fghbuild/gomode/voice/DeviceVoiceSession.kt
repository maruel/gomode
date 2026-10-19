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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "GoModeDeviceVoice"
private const val TEXT_SESSION_PATH = "/api/voicegateway/v1/voice/text"

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
 * It is half-duplex. The session listens only after the assistant turn and its
 * speech finish, so the microphone stays closed while the device speaks.
 */
internal class DeviceVoiceSession(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val settingsClient: ServiceSettingsClient = ServiceSettingsClient(),
    private val bearerTokenFor: (String) -> String? = { null },
    private val languageTag: String,
    private val localModelOwner: LocalModelOwner = (appContext.applicationContext as GoModeApplication).localModelOwner,
    private val speech: DeviceSpeech = AndroidDeviceSpeech(appContext, languageTag),
    voiceChimePlayer: VoiceChimePlayer = AndroidVoiceChime(),
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
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val attemptGeneration = AtomicLong()
    private var listeningGeneration = 0L
    private val voiceModeChime = VoiceModeChime(voiceChimePlayer)

    private val _state = MutableStateFlow(VoiceState())
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

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

    private var muted = false
    private var speakerActive = false
    private var turnIdle = false
    private var pendingSpeech = 0
    private val pendingNotifications = ArrayList<String>()
    private val segmenter = SentenceSegmenter()

    private val audioStateJob =
        scope.launch {
            callController.audioState.collect { audio ->
                _state.update {
                    it.copy(
                        availableDevices = audio.devices,
                        selectedDeviceId = audio.selectedDeviceId,
                        audioError = audio.error,
                        audioSwitching = audio.switching,
                    )
                }
            }
        }

    override fun connect(preserveTranscript: Boolean) {
        val attempt = invalidateAttempt()
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
        muted = false
        segmenter.clear()
        otherSetup = null
        if (!preserveTranscript) clearTranscript()
        if (!speech.isAvailable()) {
            setError("On-device speech recognition is not available")
            return
        }
        val engineType = settingsRepository.settings.value.onDeviceEngine
        if (engineType != OnDeviceEngine.LOCAL_LLM) VoiceService.start(appContext)
        setStatus("Connecting…")
        connectJob =
            scope.launch {
                try {
                    if (engineType == OnDeviceEngine.LOCAL_LLM) {
                        setStatus("Preparing local model…")
                        preparedLocalModel = localModelOwner.acquire()
                    }
                    val setup =
                        preparedLocalModel?.setup ?: try {
                            setupProvider()
                        } catch (e: VoiceSetupException) {
                            if (engineType == OnDeviceEngine.REMOTE_GATEWAY || engineType == OnDeviceEngine.LOCAL_LLM) {
                                if (ownsAttempt(attempt)) setError(e.message ?: "Voice setup failed")
                                return@launch
                            } else {
                                standaloneVoiceSetup()
                            }
                        }
                    if (!ownsAttempt(attempt)) return@launch
                    if (engineType == OnDeviceEngine.LOCAL_LLM) VoiceService.start(appContext)
                    if (preparedLocalModel == null) otherSetup = setup
                    if (callController.isSupported() && !callController.start { disconnect() }) {
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
        frontendToolNames = FrontendVoiceTools.declarations.map { it.name }.toSet()
        _state.update {
            it.copy(connectStatus = null, connected = true, error = null)
        }
        callController.activate()
        voiceModeChime.connected()
        flushPendingNotifications()
        turnIdle = true
        speak("Ready")
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
                        frontendToolNames = FrontendVoiceTools.declarations.map { it.name }.toSet()
                        sendOn(
                            webSocket,
                            json.encodeToString(
                                SessionSetup.serializer(),
                                gatewaySessionSetup(
                                    voiceToolDeclarations(sessionTools),
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
                            setError("Voice connection failed: ${t.message}")
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
                }

                MessageKind.AssistantTextDelta -> {
                    val msg = json.decodeFromString(AssistantTextDelta.serializer(), text)
                    handleAssistantText(msg.text)
                }

                MessageKind.TurnStatus -> {
                    val msg = json.decodeFromString(TurnStatus.serializer(), text)
                    _state.update { it.copy(turnState = msg.state) }
                    if (msg.state == TurnState.Idle) {
                        finalizeAssistantReply()
                        speak(segmenter.flush())
                        turnIdle = true
                        maybeStartListening()
                    }
                }

                MessageKind.ToolCall -> {
                    handleToolCall(json.decodeFromString(ToolCall.serializer(), text), attempt, origin)
                }

                MessageKind.Interrupted -> {
                    finalizeAssistantReply()
                    segmenter.clear()
                    speakerActive = false
                    speech.stopSpeaking()
                    pendingSpeech = 0
                    turnIdle = true
                    _state.update { it.copy(speaking = false, activeTool = null) }
                    maybeStartListening()
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
        if (delta.isEmpty()) return
        if (_state.value.listening) speech.stopListening()
        turnIdle = false
        _state.update {
            it.copy(
                listening = false,
                transcript = it.transcript.withoutUserDraft().appendChunk(TranscriptSpeaker.ASSISTANT, delta),
            )
        }
        segmenter.push(delta).forEach(::speak)
    }

    private fun speak(fragment: String) {
        if (fragment.isBlank() || muted) return
        val attempt = attemptGeneration.get()
        pendingSpeech++
        speakerActive = true
        _state.update { it.copy(speaking = true) }
        speech.speak(
            fragment,
            onDone = { onSpeechDone(attempt) },
            onError = { message -> reportAttemptError(attempt, message) },
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
        if (!_state.value.connected || muted) return
        if (!turnIdle) return
        if (pendingSpeech > 0 || speakerActive) return
        if (_state.value.listening) return
        val attempt = attemptGeneration.get()
        val recognition = ++listeningGeneration

        fun currentRecognition() = ownsAttempt(attempt) && recognition == listeningGeneration && _state.value.listening
        _state.update { it.copy(listening = true) }
        speech.startListening(
            onResult = { text -> if (currentRecognition()) handleUserUtterance(attempt, text) },
            onPartial = { text ->
                if (currentRecognition()) {
                    _state.update { it.copy(transcript = it.transcript.replaceUserDraft(text, final = false)) }
                }
            },
            onIdle = { if (currentRecognition()) handleListeningIdle(attempt) },
            onError = { message -> if (currentRecognition()) reportAttemptError(attempt, message) },
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
        speech.stopListening()
        turnIdle = false
        segmenter.clear()
        _state.update {
            it.copy(
                listening = false,
                transcript = it.transcript.replaceUserDraft(text, final = true),
            )
        }
        val currentEngine = engine
        if (currentEngine != null && currentEngine.engineType != OnDeviceEngine.REMOTE_GATEWAY) {
            executeLocalTurn(attempt, text, currentEngine)
        } else {
            sendUserMessage(text)
        }
    }

    private fun executeLocalTurn(
        attempt: Long,
        text: String,
        activeEngine: OnDeviceVoiceEngine,
    ) {
        scope.launch {
            if (!ownsAttempt(attempt)) return@launch
            _state.update { it.copy(turnState = TurnState.Thinking) }
            try {
                activeEngine.processTurn(
                    input = UserTurnInput(text = text),
                    onDelta = { delta ->
                        if (ownsAttempt(attempt)) {
                            handleAssistantText(delta)
                        }
                    },
                    onToolCall = { toolCall ->
                        if (ownsAttempt(attempt)) {
                            handleToolCallLocal(toolCall, attempt)
                        } else {
                            null
                        }
                    },
                )
                if (ownsAttempt(attempt)) {
                    finalizeAssistantReply()
                    speak(segmenter.flush())
                    turnIdle = true
                    _state.update { it.copy(turnState = TurnState.Idle) }
                    maybeStartListening()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                if (ownsAttempt(attempt)) {
                    setError("Voice turn failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleToolCallLocal(
        msg: ToolCall,
        attempt: Long,
    ): JsonElement? {
        if (!ownsAttempt(attempt)) return null
        val name = msg.name
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
                if (!ownsAttempt(attempt)) return null
                _state.update { it.copy(activeTool = null) }
                return serverResult
            }
            val args = msg.args as? JsonObject ?: JsonObject(emptyMap())
            val frontendResult =
                FrontendVoiceTools.execute(name, args) {
                    ownsAttempt(attempt)
                }
            if (frontendResult != null) {
                if (!ownsAttempt(attempt)) return null
                _state.update { it.copy(activeTool = null) }
                return frontendResult
            }
            val client = mcpClient
            if (client == null) {
                _state.update { it.copy(activeTool = null) }
                return errorJson("No MCP client")
            }
            require(name !in frontendToolNames) { "Frontend voice tool is unavailable: $name" }
            val result = client.callTool(name, args)
            if (!ownsAttempt(attempt)) return null
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
            if (!ownsAttempt(attempt)) return null
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
        if (!ownsAttempt(attempt) || webSocket !== origin) return
        val id = msg.id
        val name = msg.name
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
            val frontendResult =
                FrontendVoiceTools.execute(name, msg.args as? JsonObject ?: JsonObject(emptyMap())) {
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
            val result = client.callTool(name, args)
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
        muted = false
        segmenter.clear()
        voiceModeChime.disconnected { }
        callController.endLocal()
        VoiceService.stop(appContext)
        otherSetup = null
        // Preserve transcript so the user can review it after disconnecting.
        _state.value =
            VoiceState(
                transcript =
                    _state.value.transcript
                        .withoutUserDraft()
                        .map { it.copy(final = true) },
            )
    }

    override fun setError(message: String) {
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
        VoiceService.stop(appContext)
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
                errorId = it.errorId + 1,
                transcript = it.transcript.withoutUserDraft(),
            )
        }
    }

    override fun toggleMute() {
        muted = !muted
        if (muted) {
            speech.stopListening()
            _state.update {
                it.copy(muted = true, listening = false, micLevel = 0f)
            }
            return
        }
        _state.update { it.copy(muted = false) }
        maybeStartListening()
    }

    override fun selectAudioDevice(deviceId: Int) {
        callController.selectAudioDevice(deviceId)
    }

    override fun clearTranscript() {
        _state.update { it.copy(transcript = emptyList()) }
    }

    override fun injectText(text: String) {
        if (speakerActive) {
            pendingNotifications.add(text)
            return
        }
        val currentEngine = engine
        if (currentEngine != null && currentEngine.engineType != OnDeviceEngine.REMOTE_GATEWAY) {
            scope.launch { currentEngine.injectContext(text) }
        } else {
            sendContextUpdate(text)
        }
    }

    override fun close() {
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
        if (pendingNotifications.isEmpty()) return
        val text = pendingNotifications.joinToString("\n")
        pendingNotifications.clear()
        val currentEngine = engine
        if (currentEngine != null && currentEngine.engineType != OnDeviceEngine.REMOTE_GATEWAY) {
            scope.launch { currentEngine.injectContext(text) }
        } else {
            sendContextUpdate(text)
        }
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
        val attempt = attemptGeneration.incrementAndGet()
        return attempt
    }

    private fun ownsAttempt(attempt: Long): Boolean = attemptGeneration.get() == attempt
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
