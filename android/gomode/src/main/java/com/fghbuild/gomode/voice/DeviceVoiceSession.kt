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
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.web.FrontendVoiceTools
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
internal fun textSessionWebSocketURL(gatewayEndpointURL: String): String {
    val httpURL = gatewayEndpointURL.trimEnd('/') + TEXT_SESSION_PATH
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
    private val speech: DeviceSpeech = AndroidDeviceSpeech(appContext, languageTag),
    voiceChimePlayer: VoiceChimePlayer = AndroidVoiceChime(),
    private val callController: VoiceCallController = NoopVoiceCallController(),
    private val setupProvider: suspend () -> VoiceSetup = {
        prepareVoiceSetup(settingsRepository, settingsClient, bearerTokenFor)
    },
    private val webSocketFactory: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        deviceWebSocketClient.newWebSocket(request, listener)
    },
) : VoiceSessionController {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val attemptGeneration = AtomicLong()
    private val voiceModeChime = VoiceModeChime(voiceChimePlayer)

    private val _state = MutableStateFlow(VoiceState())
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var connectJob: Job? = null
    private var webSocket: WebSocket? = null
    private var mcpClient: McpClient? = null
    private var frontendToolNames: Set<String> = emptySet()
    private var mcpTools: List<ToolDescriptor> = emptyList()

    private var muted = false
    private var speakerActive = false
    private var turnIdle = false
    private var pendingSpeech = 0
    private val pendingNotifications = ArrayList<String>()
    private val segmenter = SentenceSegmenter()

    override fun connect(preserveTranscript: Boolean) {
        val attempt = invalidateAttempt()
        connectJob?.cancel()
        connectJob = null
        closeWebSocket()
        speech.stopListening()
        speech.stopSpeaking()
        pendingNotifications.clear()
        speakerActive = false
        turnIdle = false
        pendingSpeech = 0
        muted = false
        segmenter.clear()
        mcpClient = null
        mcpTools = emptyList()
        if (!preserveTranscript) clearTranscript()
        if (!speech.isAvailable()) {
            setError("On-device speech recognition is not available")
            return
        }
        VoiceService.start(appContext)
        setStatus("Connecting…")
        connectJob =
            scope.launch {
                try {
                    val setup =
                        try {
                            setupProvider()
                        } catch (e: VoiceSetupException) {
                            if (ownsAttempt(attempt)) setError(e.message ?: "Voice setup failed")
                            return@launch
                        }
                    if (!ownsAttempt(attempt)) return@launch
                    mcpClient = setup.mcpClient
                    mcpTools = setup.tools
                    if (callController.isSupported() && !callController.start { disconnect() }) {
                        setError("Could not start the voice call")
                        return@launch
                    }
                    openWebSocket(attempt, setup)
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

    private fun openWebSocket(
        attempt: Long,
        setup: VoiceSetup,
    ) {
        val request =
            Request
                .Builder()
                .url(textSessionWebSocketURL(setup.gatewayEndpointURL))
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
                                    voiceToolDeclarations(mcpTools),
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
                        speak(segmenter.flush())
                        turnIdle = true
                        maybeStartListening()
                    }
                }

                MessageKind.ToolCall -> {
                    handleToolCall(json.decodeFromString(ToolCall.serializer(), text), attempt, origin)
                }

                MessageKind.Interrupted -> {
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
        _state.update { it.copy(transcript = it.transcript.appendChunk(TranscriptSpeaker.ASSISTANT, delta)) }
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

    // Speech callbacks carry the attempt that started them so a delayed callback
    // from a superseded session cannot change the current one.
    private fun onSpeechDone(attempt: Long) {
        if (attempt != attemptGeneration.get()) return
        pendingSpeech = maxOf(0, pendingSpeech - 1)
        if (pendingSpeech > 0) return
        speakerActive = false
        _state.update {
            it.copy(
                speaking = false,
                transcript = it.transcript.map { entry -> entry.copy(final = true) },
            )
        }
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
        _state.update { it.copy(listening = true) }
        speech.startListening(
            onResult = { text -> handleUserUtterance(attempt, text) },
            onIdle = { handleListeningIdle(attempt) },
            onError = { message -> reportAttemptError(attempt, message) },
        )
    }

    private fun handleListeningIdle(attempt: Long) {
        if (attempt != attemptGeneration.get()) return
        _state.update { it.copy(listening = false) }
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
                transcript = it.transcript + TranscriptEntry(TranscriptSpeaker.USER, text, final = true),
            )
        }
        sendUserMessage(text)
    }

    private suspend fun handleToolCall(
        msg: ToolCall,
        attempt: Long,
        origin: WebSocket,
    ) {
        if (!ownsAttempt(attempt) || webSocket !== origin) return
        val id = msg.id
        val name = msg.name
        if (name == HANG_UP_TOOL_NAME) {
            Log.i(TAG, "Voice hang-up requested")
            disconnect()
            return
        }
        try {
            _state.update { it.copy(activeTool = name) }
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
                _state.update {
                    it.copy(
                        transcript =
                            it.transcript +
                                TranscriptEntry(TranscriptSpeaker.ASSISTANT, "[$name] $errMsg", final = true),
                    )
                }
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
        mcpClient = null
        mcpTools = emptyList()
        // Preserve transcript so the user can review it after disconnecting.
        _state.value = VoiceState(transcript = _state.value.transcript.map { it.copy(final = true) })
    }

    override fun setError(message: String) {
        invalidateAttempt()
        connectJob?.cancel()
        connectJob = null
        closeWebSocket()
        speech.stopListening()
        speech.stopSpeaking()
        pendingSpeech = 0
        speakerActive = false
        callController.endLocal()
        VoiceService.stop(appContext)
        mcpClient = null
        mcpTools = emptyList()
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
            )
        }
    }

    override fun toggleMute() {
        muted = !muted
        if (muted) {
            speech.stopListening()
            _state.update { it.copy(muted = true, listening = false, micLevel = 0f) }
            return
        }
        _state.update { it.copy(muted = false) }
        maybeStartListening()
    }

    override fun selectAudioDevice(deviceId: Int) {
        // Device mode uses the platform default routing for recognition and speech.
    }

    override fun clearTranscript() {
        _state.update { it.copy(transcript = emptyList()) }
    }

    override fun injectText(text: String) {
        if (speakerActive) {
            pendingNotifications.add(text)
            return
        }
        sendContextUpdate(text)
    }

    override fun close() {
        disconnect()
        speech.close()
    }

    private fun flushPendingNotifications() {
        if (pendingNotifications.isEmpty()) return
        val text = pendingNotifications.joinToString("\n")
        pendingNotifications.clear()
        sendContextUpdate(text)
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
