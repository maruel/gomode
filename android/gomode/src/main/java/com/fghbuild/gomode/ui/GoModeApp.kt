// Root Compose surface that bootstraps service settings and coordinates shell recovery with voice controls.
package com.fghbuild.gomode.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.webkit.CookieManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.halo.HaloController
import com.fghbuild.gomode.service.ServiceMonitor
import com.fghbuild.gomode.service.ServiceMonitoringSnapshot
import com.fghbuild.gomode.service.ServiceNotification
import com.fghbuild.gomode.service.ServiceNotificationPublisher
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.service.ServiceSettingsException
import com.fghbuild.gomode.service.compatibilityError
import com.fghbuild.gomode.service.serviceItemVoiceChanges
import com.fghbuild.gomode.ui.halo.HaloScreen
import com.fghbuild.gomode.ui.settings.SettingsScreen
import com.fghbuild.gomode.ui.web.ServiceBearerStore
import com.fghbuild.gomode.ui.web.WebShellLoadState
import com.fghbuild.gomode.ui.web.WebShellScreen
import com.fghbuild.gomode.voice.AudioDevice
import com.fghbuild.gomode.voice.DeviceVoiceSession
import com.fghbuild.gomode.voice.LocalModelOwner
import com.fghbuild.gomode.voice.LocalModelSelection
import com.fghbuild.gomode.voice.LocalModelState
import com.fghbuild.gomode.voice.McpClient
import com.fghbuild.gomode.voice.ServerVoiceSwitch
import com.fghbuild.gomode.voice.TelecomCallController
import com.fghbuild.gomode.voice.VoicePanel
import com.fghbuild.gomode.voice.VoiceService
import com.fghbuild.gomode.voice.VoiceSession
import com.fghbuild.gomode.voice.VoiceSessionController
import com.fghbuild.gomode.voice.VoiceState
import com.fghbuild.gomode.voice.prepareVoiceSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import com.fghbuild.gomode.sdk.v1.Settings as ServiceSettings

private enum class NativeScreen {
    Settings,
    Halo,
}

internal sealed interface ServiceBootstrapState {
    data class Unvalidated(
        val reason: String? = null,
    ) : ServiceBootstrapState

    data class Ready(
        val settings: ServiceSettings,
    ) : ServiceBootstrapState

    data class Error(
        val message: String,
    ) : ServiceBootstrapState
}

@Composable
internal fun GoModeApp(
    settingsRepository: SettingsRepository,
    localModelOwner: LocalModelOwner,
) {
    val context = LocalContext.current
    val haloController =
        remember(settingsRepository) {
            HaloController(context.applicationContext, settingsRepository)
        }
    DisposableEffect(haloController) {
        onDispose { haloController.close() }
    }

    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val settingsClient = remember { ServiceSettingsClient() }
    var activeNativeScreen by remember { mutableStateOf<NativeScreen?>(null) }
    val activeURL = settings.activeServiceURL
    val bearerStore = remember(settings.activeServiceId, activeURL) { ServiceBearerStore(activeURL) }
    DisposableEffect(bearerStore) {
        onDispose { bearerStore.deactivate() }
    }
    val bearerToken by bearerStore.token.collectAsStateWithLifecycle()
    val authEpoch by bearerStore.authEpoch.collectAsStateWithLifecycle()
    val authReady by bearerStore.authReady.collectAsStateWithLifecycle()
    var reloadToken by remember(activeURL) { mutableStateOf(0) }
    var bootstrapState by remember(activeURL) {
        mutableStateOf<ServiceBootstrapState>(ServiceBootstrapState.Unvalidated())
    }
    var webLoadState by remember(settings.activeServiceId, activeURL) {
        mutableStateOf<WebShellLoadState>(WebShellLoadState.Loading)
    }
    var webReloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(activeURL, reloadToken, settingsClient) {
        if (activeURL.isBlank()) return@LaunchedEffect
        bootstrapState = ServiceBootstrapState.Unvalidated()
        bootstrapState = fetchBootstrapState(activeURL, settingsClient)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val serverVoiceSwitch = remember(settingsRepository, scope) { ServerVoiceSwitch(settingsRepository, scope) }
    val pendingServerSwitch = serverVoiceSwitch.pending.collectAsStateWithLifecycle().value
    val switchVoiceServer: suspend (String) -> Unit =
        remember(serverVoiceSwitch, settings.activeServiceId, activeURL) {
            { id ->
                val current = settingsRepository.settings.value
                check(current.activeServiceId == settings.activeServiceId && current.activeServiceURL == activeURL) {
                    "Active server changed before the voice command could switch servers"
                }
                activeNativeScreen = null
                serverVoiceSwitch.switchServer(id)
            }
        }
    val voiceMode = settings.voiceMode
    val localSelection =
        remember(
            settings.activeServiceId,
            activeURL,
            voiceMode,
            settings.onDeviceEngine,
            settings.localLlmModel,
            settings.voiceLanguageTag,
            bearerStore,
            bearerToken,
            authEpoch,
            authReady,
            bootstrapState,
        ) {
            if (shouldPrepareLocalModel(settings, authReady) && bootstrapState is ServiceBootstrapState.Ready) {
                LocalModelSelection(
                    languageTag = settings.voiceLanguageTag,
                    model = settings.localLlmModel,
                    prepareSetup = { prepareVoiceSetup(settingsRepository, settingsClient, bearerStore::tokenFor) },
                    isCurrent = {
                        val current = settingsRepository.settings.value
                        shouldPrepareLocalModel(current, bearerStore.authReady.value) &&
                            current.activeServiceId == settings.activeServiceId &&
                            current.activeServiceURL == activeURL &&
                            current.voiceLanguageTag == settings.voiceLanguageTag &&
                            current.localLlmModel == settings.localLlmModel &&
                            bearerStore.authEpoch.value == authEpoch && bearerStore.token.value == bearerToken
                    },
                )
            } else {
                null
            }
        }
    DisposableEffect(localModelOwner, localSelection) {
        localModelOwner.select(localSelection)
        onDispose { localModelOwner.clearSelection(localSelection) }
    }
    val localModelState by localModelOwner.state.collectAsStateWithLifecycle()
    val voiceCallController =
        remember(context.applicationContext, serverVoiceSwitch) {
            TelecomCallController(context.applicationContext, onTelecomEnded = serverVoiceSwitch::cancel)
        }
    val voiceSession: VoiceSessionController =
        remember(
            settingsRepository,
            bearerStore,
            switchVoiceServer,
            voiceMode,
            settings.voiceLanguageTag,
            voiceCallController,
            settings.onDeviceEngine,
            settings.localLlmModel,
        ) {
            when (voiceMode) {
                VoiceMode.DEVICE -> {
                    DeviceVoiceSession(
                        context.applicationContext,
                        settingsRepository,
                        settingsClient,
                        bearerTokenFor = bearerStore::tokenFor,
                        languageTag = settings.voiceLanguageTag,
                        localModelOwner = localModelOwner,
                        callController = voiceCallController,
                        switchServer = switchVoiceServer,
                    )
                }

                VoiceMode.CLOUD -> {
                    VoiceSession(
                        context.applicationContext,
                        settingsRepository,
                        settingsClient,
                        bearerTokenFor = bearerStore::tokenFor,
                        languageTag = settings.voiceLanguageTag,
                        callController = voiceCallController,
                        switchServer = switchVoiceServer,
                    )
                }
            }
        }
    DisposableEffect(voiceSession) {
        onDispose { voiceSession.close() }
    }
    DisposableEffect(voiceSession) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action == VoiceService.ACTION_HANG_UP) {
                        serverVoiceSwitch.cancel()
                        voiceSession.disconnect()
                    }
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(VoiceService.ACTION_HANG_UP),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    // Replacing bearerStore creates a fresh session; disposal closes the previous service's session.
    val voiceAuthIdentity = remember(bearerStore) { VoiceAuthIdentity() }
    LaunchedEffect(bearerToken, authEpoch, authReady, voiceSession) {
        val changed = voiceAuthIdentity.update(bearerToken, authEpoch)
        if (changed || !authReady) voiceSession.disconnect()
    }
    val voiceState by voiceSession.state.collectAsStateWithLifecycle()
    var audioSelectionJob by remember(voiceSession) { mutableStateOf<Job?>(null) }
    var knownAudioDevices by remember(voiceSession) { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(voiceSession, voiceState.connected, voiceState.availableDevices) {
        val previousIds = knownAudioDevices
        val id = preferredVoiceAudioDevice(voiceSession.state, settings.voiceAudioDeviceType, previousIds)
        knownAudioDevices = if (voiceState.connected) voiceState.availableDevices.map { it.id }.toSet() else emptySet()
        if (id != null) voiceSession.selectAudioDevice(id)
        val bluetooth =
            voiceState.availableDevices.firstOrNull {
                it.isBluetooth() && it.id !in previousIds && (id == null || it.id == id)
            }
        if (bluetooth != null && awaitVoiceAudioSelection(voiceSession.state, bluetooth.id)) {
            settingsRepository.updateVoiceAudioDeviceType(bluetooth.type)
        }
    }
    val serviceMonitor =
        remember(scope, bearerStore) {
            ServiceMonitor(scope = scope) { endpointURL, protocolVersion ->
                McpClient(
                    endpointURL = endpointURL,
                    protocolVersion = protocolVersion,
                    cookieProvider = { CookieManager.getInstance().getCookie(endpointURL) },
                    bearerTokenProvider = { bearerStore.tokenFor(endpointURL) },
                )
            }
        }
    DisposableEffect(serviceMonitor) {
        onDispose { serviceMonitor.stop() }
    }
    val serviceMonitorState by serviceMonitor.state.collectAsStateWithLifecycle()
    var voiceItemBaseline by remember(activeURL) { mutableStateOf<ServiceMonitoringSnapshot?>(null) }
    LaunchedEffect(activeURL, voiceState.connected, serviceMonitorState.snapshot) {
        if (!voiceState.connected) {
            voiceItemBaseline = null
            return@LaunchedEffect
        }
        val snapshot = serviceMonitorState.snapshot ?: return@LaunchedEffect
        voiceItemBaseline?.let { previous ->
            serviceItemVoiceChanges(previous, snapshot)?.let(voiceSession::injectText)
        }
        voiceItemBaseline = snapshot
    }
    var onMicGranted by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                onMicGranted?.invoke()
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar("Microphone permission is required for voice mode")
                }
            }
            onMicGranted = null
        }
    val shellRecovery =
        shellRecoveryState(
            bootstrapError = (bootstrapState as? ServiceBootstrapState.Error)?.message,
            bootstrapFailure = (bootstrapState as? ServiceBootstrapState.Unvalidated)?.reason,
            webLoadState = webLoadState,
        ).takeIf { activeNativeScreen == null && activeURL.isNotBlank() }
    val voiceSessionActive =
        voiceState.connected || voiceState.connectStatus != null ||
            voiceState.listening || voiceState.speaking
    val notificationPublisher = remember(context) { ServiceNotificationPublisher(context.applicationContext) }
    var pendingNotifications by remember { mutableStateOf(emptyList<ServiceNotification>()) }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            // Voice mode already announced these events; do not post duplicate native alerts.
            if (granted && !voiceSessionActive) pendingNotifications.forEach(notificationPublisher::publish)
            pendingNotifications = emptyList()
        }
    val configuredVoiceAvailable =
        (bootstrapState as? ServiceBootstrapState.Ready)
            ?.settings
            ?.webShell
            ?.voiceGateway
            ?.url
            ?.isNotBlank() == true
    val voiceAvailable = authReady && (voiceSessionActive || (configuredVoiceAvailable && shellRecovery == null))
    LaunchedEffect(
        settings,
        pendingServerSwitch,
        bearerToken,
        authEpoch,
        authReady,
        bootstrapState,
        webLoadState,
        localModelState,
        voiceSession,
    ) {
        if (pendingServerSwitch == null ||
            pendingServerSwitch !== serverVoiceSwitch.pending.value
        ) {
            return@LaunchedEffect
        }
        val current = settingsRepository.settings.value
        val ready =
            current == settings && bearerToken == bearerStore.token.value &&
                authEpoch == bearerStore.authEpoch.value && authReady && configuredVoiceAvailable &&
                webLoadState == WebShellLoadState.Ready &&
                (!shouldPrepareLocalModel(settings, authReady) || localModelState == LocalModelState.Ready)
        if (serverVoiceSwitch.takeReady(settings, ready)) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                voiceSession.connect()
            } else {
                scope.launch { snackbarHostState.showSnackbar("Microphone permission is required to reconnect voice") }
            }
        } else if (settings.activeServiceId == pendingServerSwitch.target.id &&
            (
                bootstrapState is ServiceBootstrapState.Error ||
                    (bootstrapState as? ServiceBootstrapState.Unvalidated)?.reason != null ||
                    (bootstrapState is ServiceBootstrapState.Ready && !configuredVoiceAvailable)
            )
        ) {
            serverVoiceSwitch.cancel()
            scope.launch { snackbarHostState.showSnackbar("Voice is unavailable on the selected server") }
        }
    }
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(
        settings.activeServiceId,
        activeURL,
        bootstrapState,
        bearerToken,
        authEpoch,
        authReady,
        serviceMonitor,
    ) {
        val ready = bootstrapState as? ServiceBootstrapState.Ready
        if (activeURL.isBlank() || ready == null || !authReady) {
            serviceMonitor.stop()
            return@LaunchedEffect
        }
        serviceMonitor.start(activeURL, ready.settings)
    }

    LaunchedEffect(serviceMonitorState.notifications) {
        val notifications = serviceMonitorState.notifications
        // Voice mode already announced these events; do not post duplicate native alerts.
        if (notifications.isEmpty() || voiceSessionActive) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            notifications.forEach(notificationPublisher::publish)
        } else {
            pendingNotifications = notifications
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar {
                    Text(
                        text = data.visuals.message,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { padding ->
        // Apply the IME inset once to the shell. Keep hosted content and native
        // recovery/error controls above the keyboard without duplicate footer insets.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                GoModeContent(
                    settings = settings,
                    settingsRepository = settingsRepository,
                    haloController = haloController,
                    activeURL = activeURL,
                    bearerStore = bearerStore,
                    bootstrapState = bootstrapState,
                    onSetNativeScreen = { activeNativeScreen = it },
                    onSelectService = serverVoiceSwitch::cancel,
                    activeNativeScreen = activeNativeScreen,
                    voiceConnected = voiceState.connected,
                    voiceSessionActive = voiceSessionActive,
                    webReloadToken = webReloadToken,
                    onWebLoadStateChanged = { webLoadState = it },
                    onHostedPageLoaded = {
                        if (bootstrapState is ServiceBootstrapState.Unvalidated) {
                            reloadToken += 1
                        }
                    },
                    onAuthInvalidated = {
                        localModelOwner.clearSelection(localSelection)
                        voiceSession.disconnect()
                        serviceMonitor.stop()
                    },
                )
            }
            // Show recovery controls after the user dismisses the voice failure.
            shellRecovery?.takeIf { voiceState.error == null }?.let { recovery ->
                ShellRecoveryStrip(
                    recovery = recovery,
                    voiceSessionActive = voiceSessionActive,
                    onRetry = {
                        when (recovery.retryTarget) {
                            ShellRecoveryRetryTarget.BOOTSTRAP -> reloadToken += 1
                            ShellRecoveryRetryTarget.WEB -> webReloadToken += 1
                            null -> Unit
                        }
                    },
                    onOpenSettings = { activeNativeScreen = NativeScreen.Settings },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            (localModelState as? LocalModelState.Failed)?.let { failure ->
                Column(Modifier.fillMaxWidth().padding(12.dp).testTag("gomode-local-model-error")) {
                    Text(failure.message)
                    Button(onClick = localModelOwner::retry) { Text("Retry local model") }
                }
            }
            // Keep failures visible even when the keyboard or shell recovery hides idle controls.
            if (pendingServerSwitch != null || voiceState.error != null ||
                (!keyboardOpen && (shellRecovery == null || voiceSessionActive))
            ) {
                VoicePanel(
                    voiceState =
                        if (pendingServerSwitch != null) {
                            voiceState.copy(
                                error = null,
                                connectStatus =
                                    if (authReady) {
                                        "Reconnecting voice to ${pendingServerSwitch.target.label}…"
                                    } else {
                                        "Waiting for ${pendingServerSwitch.target.label} sign-in to reconnect voice…"
                                    },
                            )
                        } else {
                            voiceState
                        },
                    voiceEnabled = pendingServerSwitch != null || voiceAvailable,
                    onConnect = {
                        serverVoiceSwitch.cancel()
                        if (bearerStore.authReady.value) {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                voiceSession.connect()
                            } else {
                                onMicGranted = {
                                    if (bearerStore.authReady.value) voiceSession.connect()
                                }
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    onDisconnect = {
                        serverVoiceSwitch.cancel()
                        voiceSession.disconnect()
                    },
                    onToggleMute = { voiceSession.toggleMute() },
                    onSelectDevice = { id ->
                        val device = voiceState.availableDevices.firstOrNull { it.id == id }
                        if (device != null) {
                            audioSelectionJob?.cancel()
                            voiceSession.selectAudioDevice(id)
                            audioSelectionJob =
                                scope.launch {
                                    if (awaitVoiceAudioSelection(voiceSession.state, id)) {
                                        settingsRepository.updateVoiceAudioDeviceType(device.type)
                                    }
                                }
                        }
                    },
                    onClearTranscript = { voiceSession.clearTranscript() },
                    onOpenSettings = { activeNativeScreen = NativeScreen.Settings },
                    serviceStatusText = serviceMonitorState.error?.let { "Service updates are reconnecting." },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// Save only confirmed user choices. A failed request leaves both the saved preference and route error intact.
internal suspend fun awaitVoiceAudioSelection(
    states: StateFlow<VoiceState>,
    deviceId: Int,
): Boolean {
    val state =
        states.first {
            !it.connected || (!it.audioSwitching && (it.selectedDeviceId == deviceId || it.audioError != null))
        }
    return state.connected && state.selectedDeviceId == deviceId && state.audioError == null
}

// Device IDs belong to a call. Restore the saved output type only when it is available.
internal suspend fun preferredVoiceAudioDevice(
    states: StateFlow<VoiceState>,
    type: Int?,
    previousIds: Set<Int>,
): Int? {
    // A list change can arrive while Telecom rejects route requests. Wait once; do not retry routing errors.
    val state = states.first { !it.audioSwitching }
    if (!state.connected) return null
    val preferred =
        state.availableDevices.firstOrNull { it.isBluetooth() && it.id !in previousIds }
            ?: state.availableDevices.firstOrNull { it.type == type }
    return preferred?.id?.takeIf { it != state.selectedDeviceId }
}

private fun AudioDevice.isBluetooth(): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER

internal fun shouldPrepareLocalModel(
    settings: SettingsState,
    authReady: Boolean,
): Boolean =
    authReady && settings.activeServiceURL.isNotBlank() &&
        settings.voiceMode == VoiceMode.DEVICE && settings.onDeviceEngine == OnDeviceEngine.LOCAL_LLM

internal suspend fun fetchBootstrapState(
    activeURL: String,
    settingsClient: ServiceSettingsClient,
): ServiceBootstrapState =
    try {
        val serviceSettings = settingsClient.fetch(activeURL)
        val compatibilityError = serviceSettings.compatibilityError()
        if (compatibilityError == null) {
            ServiceBootstrapState.Ready(serviceSettings)
        } else {
            ServiceBootstrapState.Error(compatibilityError)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SerializationException) {
        ServiceBootstrapState.Unvalidated(e.message ?: "Could not parse service settings.")
    } catch (e: IllegalArgumentException) {
        ServiceBootstrapState.Error("Invalid service URL: ${e.message.orEmpty()}")
    } catch (e: ServiceSettingsException) {
        ServiceBootstrapState.Unvalidated(e.message ?: "Could not fetch service settings.")
    } catch (e: IOException) {
        ServiceBootstrapState.Unvalidated(e.message ?: "Could not fetch service settings.")
    }

@Composable
private fun GoModeContent(
    settings: SettingsState,
    settingsRepository: SettingsRepository,
    haloController: HaloController,
    activeURL: String,
    bearerStore: ServiceBearerStore,
    bootstrapState: ServiceBootstrapState,
    activeNativeScreen: NativeScreen?,
    onSetNativeScreen: (NativeScreen?) -> Unit,
    onSelectService: () -> Unit,
    voiceConnected: Boolean,
    voiceSessionActive: Boolean,
    webReloadToken: Int,
    onWebLoadStateChanged: (WebShellLoadState) -> Unit,
    onHostedPageLoaded: () -> Unit,
    onAuthInvalidated: () -> Unit,
) {
    when {
        activeNativeScreen == NativeScreen.Halo -> {
            HaloScreen(
                haloController = haloController,
                onNavigateBack = { onSetNativeScreen(NativeScreen.Settings) },
            )
        }

        activeURL.isBlank() || activeNativeScreen == NativeScreen.Settings -> {
            SettingsScreen(
                settings = settings,
                settingsRepository = settingsRepository,
                voiceSessionActive = voiceSessionActive,
                onDone = { onSetNativeScreen(null) },
                onOpenHalo = { onSetNativeScreen(NativeScreen.Halo) },
                onSelectService = onSelectService,
                bearerTokenFor = bearerStore::tokenFor,
                bearerToken = bearerStore.token.collectAsStateWithLifecycle().value,
                authEpoch = bearerStore.authEpoch.collectAsStateWithLifecycle().value,
            )
        }

        else -> {
            when (val state = bootstrapState) {
                is ServiceBootstrapState.Error -> {
                    Box(Modifier.fillMaxSize().testTag("gomode-service-bootstrap"))
                }

                is ServiceBootstrapState.Unvalidated,
                is ServiceBootstrapState.Ready,
                -> {
                    // Keep non-error states in one Compose branch. Otherwise the WebView is disposed
                    // when bootstrap validation completes, which can strand in-flight JavaScript callbacks.
                    WebShellScreen(
                        serviceID = settings.activeServiceId,
                        initialURL = activeURL,
                        voiceConnected = voiceConnected,
                        reloadToken = webReloadToken,
                        bearerStore = bearerStore,
                        onLoadStateChanged = onWebLoadStateChanged,
                        onHostedPageLoaded = onHostedPageLoaded,
                        onAuthInvalidated = onAuthInvalidated,
                    )
                }
            }
        }
    }
}

// Tracks the identity used by a voice session so account changes invalidate that session.
internal class VoiceAuthIdentity {
    private var initialized = false
    private var token: String? = null
    private var epoch = 0L

    fun update(
        currentToken: String?,
        currentEpoch: Long,
    ): Boolean {
        val changed = initialized && (token != currentToken || epoch != currentEpoch)
        token = currentToken
        epoch = currentEpoch
        initialized = true
        return changed
    }
}

internal enum class ShellRecoveryRetryTarget {
    BOOTSTRAP,
    WEB,
}

internal data class ShellRecoveryState(
    val title: String,
    val message: String,
    val retryTarget: ShellRecoveryRetryTarget?,
)

internal fun shellRecoveryState(
    bootstrapError: String?,
    webLoadState: WebShellLoadState,
    bootstrapFailure: String? = null,
): ShellRecoveryState? =
    when {
        bootstrapError != null -> {
            ShellRecoveryState(
                title = "Could not use service",
                message = bootstrapError,
                retryTarget = ShellRecoveryRetryTarget.BOOTSTRAP,
            )
        }

        bootstrapFailure != null -> {
            ShellRecoveryState(
                title = "Could not load voice settings",
                message = bootstrapFailure,
                retryTarget = ShellRecoveryRetryTarget.BOOTSTRAP,
            )
        }

        webLoadState is WebShellLoadState.Reconnecting -> {
            ShellRecoveryState(
                title = "Reconnecting to service",
                message = "Voice will be available when the service reconnects.",
                retryTarget = null,
            )
        }

        webLoadState is WebShellLoadState.Failed -> {
            ShellRecoveryState(
                title = "Could not load service",
                message = webLoadState.message,
                retryTarget = ShellRecoveryRetryTarget.WEB,
            )
        }

        else -> {
            null
        }
    }

@Composable
private fun ShellRecoveryStrip(
    recovery: ShellRecoveryState,
    voiceSessionActive: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.testTag("gomode-shell-recovery"), tonalElevation = 4.dp) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(recovery.title, style = MaterialTheme.typography.titleMedium)
            Text(recovery.message, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (voiceSessionActive) {
                    "Voice remains connected."
                } else {
                    "Voice is unavailable until the service reconnects."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                recovery.retryTarget?.let {
                    Button(onClick = onRetry, modifier = Modifier.testTag("gomode-shell-retry")) {
                        Text("Retry service")
                    }
                }
                Button(onClick = onOpenSettings, modifier = Modifier.testTag("gomode-shell-open-settings")) {
                    Text("Settings")
                }
            }
        }
    }
}
