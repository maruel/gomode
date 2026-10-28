// Owns bounded system-dial requests, timed native startup, and call lifetime across Activity replacement.
package com.fghbuild.gomode.voice

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.fghbuild.gomode.R
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.ServiceInstance
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.web.ServiceCallStage
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.gomode.ui.web.isTrustedPermissionOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val STARTUP_TIMEOUT_MS = 60_000L

internal enum class CarDialStatus { IDLE, WAITING, CONNECTING, PREPARING, CONNECTED, FAILED }

// Fixed messages are safe above keyguard; provider errors stay in diagnostic logs.
internal enum class CarDialFailure(
    @get:StringRes val message: Int,
) {
    CALL(R.string.car_dial_failed_call),
    FRONTEND(R.string.car_dial_failed_frontend),
    MICROPHONE(R.string.car_dial_failed_microphone),
    SERVICE(R.string.car_dial_failed_service),
    SETTINGS(R.string.car_dial_failed_settings),
    SIGN_IN(R.string.car_dial_failed_sign_in),
    SPEECH_LANGUAGE(R.string.car_dial_failed_speech_language),
    TOOLS(R.string.car_dial_failed_tools),
    VOICE(R.string.car_dial_failed_voice),
}

internal data class CarDialVoiceState(
    val identity: String? = null,
    val serverName: String = "",
    val status: CarDialStatus = CarDialStatus.IDLE,
    val failure: CarDialFailure = CarDialFailure.VOICE,
)

private class CarDialRequest(
    val server: ServiceInstance,
    val expiresAt: Long = SystemClock.elapsedRealtime() + 10_000,
    val identity: String = UUID.randomUUID().toString(),
) {
    var activity: Any? = null
    var starting = false
}

private class CarDialAttempt(
    val server: ServiceInstance,
) {
    val timing = VoiceStartupTiming(VoiceStartupSource.CAR)
    var failure = CarDialFailure.CALL
    val authority = AtomicBoolean(true)
    var selection: SettingsState? = null
    var page: com.fghbuild.gomode.ui.web.ServiceShellSession? = null
    var pageIdentityEpoch: Long? = null
}

// Mutable owner state is main-thread-only. Captured authority uses an atomic cancellation token
// plus StateFlow snapshots so model callbacks can check it from their IO dispatcher.
// Intent extras carry only a nonce: the authority and server identity remain in this process.
internal class CarDialVoiceOwner(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val localModelOwner: LocalModelOwner,
    private val pages: ServiceShellOwner,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(CarDialVoiceState())
    val state = mutableState.asStateFlow()
    private var request: CarDialRequest? = null
    private var attempt: CarDialAttempt? = null
    private var conversation: VoiceConversation? = null
    private var startup: Job? = null
    private var lifetime: Job? = null
    private var callController: TelecomCallController? = null
    private val mutableSession = MutableStateFlow<VoiceSessionController?>(null)
    val session = mutableSession.asStateFlow()
    private var modelSelection: LocalModelSelection? = null
    private val foreground = VoiceForegroundLease(context)
    private val manualSessions = mutableSetOf<VoiceSessionController>()
    private val hangupReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == VoiceService.ACTION_HANG_UP) cancel()
            }
        }

    init {
        ContextCompat.registerReceiver(
            context,
            hangupReceiver,
            IntentFilter(VoiceService.ACTION_HANG_UP),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    val isBusy: Boolean get() = request != null

    fun registerManualSession(next: VoiceSessionController) {
        manualSessions.add(next)
    }

    fun unregisterManualSession(expected: VoiceSessionController) {
        manualSessions.remove(expected)
    }

    fun request(server: ServiceInstance): String? {
        val manualActive = manualSessions.any { it.state.value.connected || it.state.value.connectStatus != null }
        if (request != null || manualActive || !eligible()) return null
        val next = CarDialRequest(server)
        request = next
        mutableState.value = CarDialVoiceState(next.identity, server.label, CarDialStatus.WAITING)
        lifetime =
            scope.launch {
                while (request === next) {
                    val voice = session.value?.state?.value
                    val selected = attempt?.selection
                    val changed = selected != null && !settingsCurrent(selected)
                    val ended =
                        mutableState.value.status == CarDialStatus.CONNECTED &&
                            voice?.connected == false && voice.connectStatus == null
                    val page = attempt?.page
                    val pageRevoked =
                        attempt?.pageIdentityEpoch?.let { epoch ->
                            page == null || page.bearerStore.identityEpoch.value != epoch
                        } == true
                    if (!eligible() || changed || ended || pageRevoked || conversation?.isCurrent() == false ||
                        (!next.starting && SystemClock.elapsedRealtime() >= next.expiresAt)
                    ) {
                        // CoroutineScope.cancel(String) would cancel only this watcher.
                        this@CarDialVoiceOwner.cancel(next.identity)
                        break
                    }
                    delay(250)
                }
            }
        return next.identity
    }

    fun attach(
        identity: String?,
        activity: Any,
    ): Boolean {
        val current = request ?: return false
        if (current.identity != identity || !eligible() ||
            (!current.starting && SystemClock.elapsedRealtime() >= current.expiresAt)
        ) {
            return false
        }
        current.activity = activity
        return true
    }

    fun detach(
        identity: String?,
        activity: Any,
        changingConfigurations: Boolean,
    ) {
        val current = request ?: return
        if (current.identity != identity || current.activity !== activity) return
        current.activity = null
        if (!changingConfigurations) cancel(current.identity)
    }

    // Once Telecom owns hang-up and the service is selected, the ordinary shell
    // can prepare its page. Closing that screen must not cancel the owned call.
    fun releaseToShell(
        identity: String?,
        activity: Any,
    ): Boolean {
        val current = request ?: return false
        if (current.identity != identity || current.activity !== activity ||
            mutableState.value.status !in setOf(CarDialStatus.PREPARING, CarDialStatus.CONNECTED) || !eligible()
        ) {
            return false
        }
        current.activity = null
        return true
    }

    fun start(
        identity: String?,
        activity: Any,
    ) {
        val current = request ?: return
        if (current.identity != identity || current.activity !== activity || current.starting) return
        if (!eligible() || SystemClock.elapsedRealtime() >= current.expiresAt) {
            cancel(current.identity)
            return
        }
        current.starting = true
        beginAttempt(current, CarDialAttempt(current.server))
    }

    private fun beginAttempt(
        current: CarDialRequest,
        next: CarDialAttempt,
    ) {
        if (request !== current || !eligible()) {
            next.timing.finish(VoiceStartupOutcome.CANCELED)
            return
        }
        // Invalidate source callbacks before its teardown can report an old hang-up.
        attempt?.timing?.finish(VoiceStartupOutcome.SUPERSEDED)
        attempt?.authority?.set(false)
        attempt = next
        mutableState.value = mutableState.value.copy(status = CarDialStatus.CONNECTING)
        // Begin foreground ownership while the native Activity is visibly resumed.
        next.timing.enter(VoiceStartupStage.MICROPHONE)
        try {
            foreground.start(next.server.label)
        } catch (error: SecurityException) {
            Log.w("GoModeCarDial", "Microphone foreground ownership unavailable", error)
            next.failure = CarDialFailure.MICROPHONE
            fail(current, next)
            return
        }
        // The session takes over this foreground lease once its transport is ready.
        session.value?.close()
        mutableSession.value = null
        callController?.close()
        callController = null
        localModelOwner.clearSelection(modelSelection)
        modelSelection = null
        startup?.cancel()
        mutableState.value = CarDialVoiceState(current.identity, next.server.label, CarDialStatus.CONNECTING)
        startup =
            scope.launch {
                try {
                    next.timing.startDeadline(STARTUP_TIMEOUT_MS)
                    withTimeout(STARTUP_TIMEOUT_MS) { connect(current, next) }
                } catch (error: TimeoutCancellationException) {
                    next.timing.finish(VoiceStartupOutcome.TIMED_OUT)
                    Log.w("GoModeCarDial", "Voice setup timed out", error)
                    if (next.page?.isFrontendReady == false) next.failure = CarDialFailure.FRONTEND
                    fail(current, next)
                } catch (error: CancellationException) {
                    next.timing.finish(VoiceStartupOutcome.CANCELED)
                    throw error
                } catch (
                    // This boundary maps setup failures to a native, non-sensitive call status.
                    @Suppress("TooGenericExceptionCaught") error: Exception,
                ) {
                    Log.w("GoModeCarDial", "Voice setup failed", error)
                    next.timing.finishFailure(error)
                    fail(current, next)
                }
            }
    }

    private suspend fun connect(
        current: CarDialRequest,
        next: CarDialAttempt,
    ) {
        checkCurrent(current, next)
        next.timing.enter(VoiceStartupStage.CALL)
        val ended = { if (request === current && attempt === next) cancel(current.identity) }
        val call = TelecomCallController(context, ended)
        callController = call
        check(call.start(next.server.label, next.server.id, ended)) {
            "Could not establish replacement call"
        }
        // The replacement call now owns hang-up before storage, auth, networking or model work.
        next.failure = CarDialFailure.SETTINGS
        next.timing.enter(VoiceStartupStage.SETTINGS)
        withTimeout(1_000) {
            settingsRepository.switchService(next.server.id)
            settingsRepository.settings.first { it.activeServiceId == next.server.id }
        }
        val settings = settingsRepository.settings.value
        next.selection = settings
        checkCurrent(current, next)
        mutableState.value = mutableState.value.copy(status = CarDialStatus.PREPARING)
        val page =
            pages.prepareCall(settings) { stage ->
                next.timing.enter(
                    when (stage) {
                        ServiceCallStage.PAGE -> VoiceStartupStage.PAGE
                        ServiceCallStage.SIGN_IN -> VoiceStartupStage.SIGN_IN
                        ServiceCallStage.TOOLS -> VoiceStartupStage.TOOLS
                        ServiceCallStage.FRONTEND -> VoiceStartupStage.FRONTEND
                    },
                )
                Log.i("GoModeCarDial", "Car voice preparation: $stage")
                next.failure =
                    when (stage) {
                        ServiceCallStage.PAGE -> CarDialFailure.SERVICE
                        ServiceCallStage.SIGN_IN -> CarDialFailure.SIGN_IN
                        ServiceCallStage.TOOLS -> CarDialFailure.TOOLS
                        ServiceCallStage.FRONTEND -> CarDialFailure.FRONTEND
                    }
            }
        checkCurrent(current, next)
        next.page = page
        val authorityEpoch = page.bearerStore.identityEpoch.value
        next.pageIdentityEpoch = authorityEpoch
        val token = page.bearerStore.token.value
        val bearer: (String) -> String? = { url ->
            token.takeIf { isTrustedPermissionOrigin(url.toUri(), next.server.url) }
        }
        val authorityCurrent = {
            next.authority.get() && settingsCurrent(settings) &&
                page.bearerStore.identityEpoch.value == authorityEpoch &&
                (state.value.status == CarDialStatus.CONNECTED || page.isFrontendReady) &&
                // Main-thread dispatch checks eligibility synchronously; IO deltas use the
                // lifetime watcher's cached decision without Binder work on every token.
                (!Looper.getMainLooper().isCurrentThread || eligible())
        }
        next.failure = CarDialFailure.VOICE
        val settingsClient = ServiceSettingsClient()
        val voiceConversation =
            if (allowsFocusChanges(settings)) {
                VoiceConversation(settingsRepository, settingsClient, pages.catalog, authorityCurrent) { pages.entries }
            } else {
                null
            }
        conversation = voiceConversation
        if (settings.voiceMode == VoiceMode.DEVICE && settings.onDeviceEngine == OnDeviceEngine.LOCAL_LLM) {
            val selection =
                LocalModelSelection(
                    languageTag = settings.voiceLanguageTag,
                    model = settings.localLlmModel,
                    prepareSetup = { withContext(Dispatchers.Main) { checkNotNull(voiceConversation).prepareLocal() } },
                    isCurrent = {
                        authorityCurrent()
                    },
                )
            modelSelection = selection
            localModelOwner.select(selection)
        }
        val voice =
            when (settings.voiceMode) {
                VoiceMode.CLOUD -> {
                    VoiceSession(
                        context,
                        settingsRepository,
                        voiceConversation = checkNotNull(voiceConversation),
                        languageTag = settings.voiceLanguageTag,
                        callController = call,
                        switchServer = { id -> settingsRepository.switchService(id) },
                        startupTiming = { next.timing },
                    )
                }

                VoiceMode.DEVICE -> {
                    DeviceVoiceSession(
                        context,
                        settingsRepository,
                        settingsClient,
                        bearerTokenFor = bearer,
                        languageTag = settings.voiceLanguageTag,
                        localModelOwner = localModelOwner,
                        callController = call,
                        frontendTools = page.frontendTools,
                        isAuthorityCurrent = authorityCurrent,
                        voiceConversation = voiceConversation,
                        switchServer = { id -> settingsRepository.switchService(id) },
                        startupTiming = { next.timing },
                    )
                }
            }
        mutableSession.value = voice
        checkCurrent(current, next)
        Log.i("GoModeCarDial", "Starting prepared voice transport")
        next.timing.enter(VoiceStartupStage.TRANSPORT)
        voice.connect()
        val connected = voice.state.first { it.connected || it.error != null || it.connectStatus == null }
        if (!connected.connected) {
            Log.w("GoModeCarDial", "Voice session did not connect: ${connected.error}")
            if (connected.error == FRONTEND_NOT_READY) next.failure = CarDialFailure.FRONTEND
            val language = (voice as? DeviceVoiceSession)?.speechLanguageState?.value
            if (language == SpeechLanguageState.Missing || language == SpeechLanguageState.Pending ||
                language == SpeechLanguageState.Unsupported || language is SpeechLanguageState.Failed
            ) {
                next.failure = CarDialFailure.SPEECH_LANGUAGE
            }
            fail(current, next)
            return
        }
        checkCurrent(current, next)
        pages.setVoiceConnected(true)
        mutableState.value = mutableState.value.copy(status = CarDialStatus.CONNECTED)
        next.timing.finish(VoiceStartupOutcome.CONNECTED)
    }

    private fun allowsFocusChanges(settings: SettingsState): Boolean =
        usesGatewayVoice(settings) || settings.onDeviceEngine == OnDeviceEngine.LOCAL_LLM

    // Shared sessions permit focus changes; keyword engines remain selected-service sessions.
    private fun settingsCurrent(captured: SettingsState): Boolean {
        val current = settingsRepository.settings.value
        return runCatching {
            ensureVoiceSettingsCurrent(
                captured,
                if (allowsFocusChanges(captured)) {
                    current.copy(
                        activeServiceId = captured.activeServiceId,
                        activeServiceURL = captured.activeServiceURL,
                    )
                } else {
                    current
                },
            )
            check(current.services == captured.services)
            check(current.voiceLanguageTag == captured.voiceLanguageTag)
            check(current.localLlmModel == captured.localLlmModel)
        }.isSuccess
    }

    private fun checkCurrent(
        current: CarDialRequest,
        next: CarDialAttempt,
    ) {
        check(request === current && attempt === next && eligible()) { "Call request is no longer current" }
        check(
            settingsRepository.settings.value.services
                .any { it == next.server },
        ) { "Saved server changed" }
    }

    private fun eligible(): Boolean {
        val enabled =
            context.packageManager.getComponentEnabledSetting(
                ComponentName(context, CarDialRedirectionService::class.java),
            )
        return enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
            context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION) &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun fail(
        current: CarDialRequest,
        expected: CarDialAttempt,
    ) {
        if (request !== current || attempt !== expected) return
        val server = expected.server
        val failure = expected.failure
        Log.w("GoModeCarDial", "Car call startup failed: $failure")
        finish(CarDialVoiceState(current.identity, server.label, CarDialStatus.FAILED, failure))
    }

    fun cancel(identity: String? = mutableState.value.identity) {
        val current = request
        if (current == null) {
            if (identity == mutableState.value.identity) mutableState.value = CarDialVoiceState()
            return
        }
        if (identity != current.identity) return
        finish(CarDialVoiceState())
    }

    private fun finish(finalState: CarDialVoiceState) {
        attempt?.timing?.finish(
            if (finalState.status == CarDialStatus.FAILED) VoiceStartupOutcome.FAILED else VoiceStartupOutcome.CANCELED,
        )
        attempt?.authority?.set(false)
        request = null
        attempt = null
        conversation?.finish()
        conversation = null
        startup?.cancel()
        startup = null
        lifetime?.cancel()
        lifetime = null
        localModelOwner.clearSelection(modelSelection)
        modelSelection = null
        session.value?.close()
        mutableSession.value = null
        callController?.close()
        callController = null
        foreground.stop()
        pages.setVoiceConnected(false)
        // IDLE finishes the native Activity immediately. A failed startup must
        // publish FAILED directly so its failure cause remains visible.
        mutableState.value = finalState
    }

    override fun close() {
        cancel()
        context.unregisterReceiver(hangupReceiver)
        scope.cancel()
    }
}
