// Root Compose surface coordinating autosaved settings, service shells, recovery, and voice controls.
package com.fghbuild.gomode.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.halo.HaloController
import com.fghbuild.gomode.service.CatalogNotification
import com.fghbuild.gomode.service.CatalogService
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceMonitorState
import com.fghbuild.gomode.service.ServiceMonitoringSnapshot
import com.fghbuild.gomode.service.ServiceNotificationDestination
import com.fghbuild.gomode.service.ServiceNotificationPublisher
import com.fghbuild.gomode.service.ServiceReadiness
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.service.ServiceSettingsException
import com.fghbuild.gomode.service.compatibilityError
import com.fghbuild.gomode.service.serviceItemVoiceChanges
import com.fghbuild.gomode.ui.halo.HaloScreen
import com.fghbuild.gomode.ui.settings.SettingsEdits
import com.fghbuild.gomode.ui.settings.SettingsScreen
import com.fghbuild.gomode.ui.settings.rememberSettingsEdits
import com.fghbuild.gomode.ui.web.FrontendVoiceTools
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellRegistry
import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.ServiceShellState
import com.fghbuild.gomode.ui.web.WebShellLoadState
import com.fghbuild.gomode.ui.web.WebShellScreen
import com.fghbuild.gomode.ui.web.isTrustedPermissionOrigin
import com.fghbuild.gomode.voice.AudioDevice
import com.fghbuild.gomode.voice.DeviceVoiceSession
import com.fghbuild.gomode.voice.LocalModelOwner
import com.fghbuild.gomode.voice.LocalModelSelection
import com.fghbuild.gomode.voice.LocalModelState
import com.fghbuild.gomode.voice.TelecomCallController
import com.fghbuild.gomode.voice.VoiceConversation
import com.fghbuild.gomode.voice.VoicePanel
import com.fghbuild.gomode.voice.VoiceService
import com.fghbuild.gomode.voice.VoiceSession
import com.fghbuild.gomode.voice.VoiceSessionController
import com.fghbuild.gomode.voice.VoiceState
import com.fghbuild.gomode.voice.usesGatewayVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    notificationDestination: ServiceNotificationDestination? = null,
    onNotificationHandled: (ServiceNotificationDestination) -> Unit = {},
) {
    val context = LocalContext.current
    val haloController =
        remember(settingsRepository) {
            HaloController(context.applicationContext, settingsRepository)
        }
    DisposableEffect(haloController) {
        onDispose { haloController.close() }
    }

    val settings by key(settingsRepository) { settingsRepository.settings.collectAsStateWithLifecycle() }
    val settingsEdits = rememberSettingsEdits(settingsRepository, settings)
    val settingsClient = remember { ServiceSettingsClient() }
    var activeNativeScreen by remember { mutableStateOf<NativeScreen?>(null) }
    val activeURL = settings.activeServiceURL
    val shellRegistry = remember(settingsRepository) { ServiceShellRegistry() }
    val serviceKeys = settings.services.map { ServiceShellKey(it.id, it.url) }
    val activeKey = ServiceShellKey(settings.activeServiceId, activeURL)
    val serviceSessions = shellRegistry.reconcile((serviceKeys + activeKey).toSet())
    val serviceSession = serviceSessions.getValue(activeKey)
    LaunchedEffect(notificationDestination, settings.loadState, activeKey) {
        val destination = notificationDestination ?: return@LaunchedEffect
        if (settings.loadState != SettingsLoadState.Ready) return@LaunchedEffect
        val entry = serviceSessions[destination.source]
        if (entry != null) {
            activeNativeScreen = null
            if (activeKey != destination.source) {
                settingsRepository.switchService(entry.key.serviceID)
                return@LaunchedEffect
            }
            entry.navigate(destination.url)
        }
        onNotificationHandled(destination)
    }

    var prepareAllPages by remember { mutableStateOf(false) }
    DisposableEffect(shellRegistry) {
        onDispose { shellRegistry.close() }
    }
    val bearerStore = serviceSession.bearerStore
    val bearerToken by key(bearerStore) { bearerStore.token.collectAsStateWithLifecycle() }
    val authEpoch by key(bearerStore) { bearerStore.authEpoch.collectAsStateWithLifecycle() }
    val authReady by key(bearerStore) { bearerStore.authReady.collectAsStateWithLifecycle() }
    val shellState by key(serviceSession) { serviceSession.state.collectAsStateWithLifecycle() }
    val bootstrapState = shellState.bootstrap
    val webLoadState = shellState.load

    serviceSessions.values.forEach { entry ->
        key(entry) {
            val entryState by entry.state.collectAsStateWithLifecycle()
            LaunchedEffect(entry, entryState.bootstrapReloadToken, settingsClient) {
                if (entry.key.url.isBlank()) return@LaunchedEffect
                entry.updateBootstrap(ServiceBootstrapState.Unvalidated())
                entry.updateBootstrap(fetchBootstrapState(entry.key.url, settingsClient))
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val serviceCatalog = remember(scope) { ServiceCatalog(scope) }
    serviceCatalog.reconcile(serviceSessions, settings.services.associate { it.id to it.label })
    DisposableEffect(serviceCatalog) {
        onDispose { serviceCatalog.close() }
    }
    val catalogState by serviceCatalog.state.collectAsStateWithLifecycle()
    val currentEntries by rememberUpdatedState(serviceSessions)
    val voiceConversation =
        remember(settingsRepository, settingsClient, serviceCatalog) {
            VoiceConversation(settingsRepository, settingsClient, serviceCatalog) { currentEntries }
        }
    val gatewayState by key(voiceConversation) { voiceConversation.state.collectAsStateWithLifecycle() }
    val sharedConversation =
        usesGatewayVoice(settings) ||
            (settings.voiceMode == VoiceMode.DEVICE && settings.onDeviceEngine == OnDeviceEngine.LOCAL_LLM)
    val switchVoiceServer: suspend (String) -> Unit =
        remember(settingsRepository) {
            { id ->
                if (!settingsEdits.save(voiceEnabled = false, commitURL = true)) {
                    throw IOException(settingsEdits.error ?: "Unable to save settings")
                }
                activeNativeScreen = null
                settingsRepository.switchService(id)
            }
        }
    val voiceMode = settings.voiceMode
    val localSelection =
        remember(
            voiceConversation,
            voiceMode,
            settings.onDeviceEngine,
            settings.localLlmModel,
            settings.voiceLanguageTag,
        ) {
            if (voiceMode == VoiceMode.DEVICE && settings.onDeviceEngine == OnDeviceEngine.LOCAL_LLM) {
                LocalModelSelection(
                    languageTag = settings.voiceLanguageTag,
                    model = settings.localLlmModel,
                    prepareSetup = { withContext(Dispatchers.Main) { voiceConversation.prepareLocal() } },
                    isCurrent = {
                        val current = settingsRepository.settings.value
                        current.voiceMode == VoiceMode.DEVICE && current.onDeviceEngine == OnDeviceEngine.LOCAL_LLM &&
                            current.voiceLanguageTag == settings.voiceLanguageTag &&
                            current.localLlmModel == settings.localLlmModel
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
    val localModelState by key(localModelOwner) { localModelOwner.state.collectAsStateWithLifecycle() }
    val voiceCallController =
        remember(context.applicationContext) {
            TelecomCallController(context.applicationContext, onTelecomEnded = {})
        }
    val voiceServiceOwner = if (sharedConversation) voiceConversation else bearerStore
    val voiceSession: VoiceSessionController =
        remember(
            settingsRepository,
            voiceServiceOwner,
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
                        bearerTokenFor = if (sharedConversation) voiceConversation::tokenFor else bearerStore::tokenFor,
                        frontendTools = if (sharedConversation) FrontendVoiceTools() else serviceSession.frontendTools,
                        voiceConversation = voiceConversation.takeIf { sharedConversation },
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
                        voiceConversation = voiceConversation,
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
        if (!sharedConversation && (changed || !authReady)) voiceSession.disconnect()
    }
    val voiceState by key(voiceSession) { voiceSession.state.collectAsStateWithLifecycle() }
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
    val serviceMonitorState = catalogState[activeKey]?.monitoring ?: ServiceMonitorState()
    var voiceItemBaseline by remember(serviceSession) { mutableStateOf<ServiceMonitoringSnapshot?>(null) }
    LaunchedEffect(serviceSession, voiceState.connected, serviceMonitorState.snapshot) {
        if (sharedConversation || !voiceState.connected) {
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
    LaunchedEffect(sharedConversation, voiceConversation, gatewayState, catalogState, activeKey, voiceState.connected) {
        if (sharedConversation && gatewayState != null) {
            if (!voiceConversation.isCurrent()) {
                voiceSession.disconnect()
                if (localSelection != null) localModelOwner.restartConversation()
            } else if (voiceSessionActive) {
                voiceSession.injectText(voiceConversation.contextText())
            }
        }
    }
    ServiceNotifications(
        serviceCatalog = serviceCatalog,
        activeKey = activeKey,
        serviceVisible =
            activeNativeScreen == null && activeURL.isNotBlank() && shellRecovery == null &&
                (notificationDestination == null || notificationDestination.source == activeKey),
        configuredServices = serviceKeys.toSet().takeIf { settings.loadState == SettingsLoadState.Ready },
        voiceSessionActive = voiceSessionActive,
        sharedConversation = sharedConversation,
    )
    val configuredVoiceAvailable =
        (bootstrapState as? ServiceBootstrapState.Ready)
            ?.settings
            ?.webShell
            ?.voiceGateway
            ?.url
            ?.isNotBlank() == true
    val voiceAvailable =
        (sharedConversation && voiceSessionActive) ||
            (localSelection != null && localModelState == LocalModelState.Ready) ||
            (authReady && (voiceSessionActive || (configuredVoiceAvailable && shellRecovery == null)))
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0

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
                    settingsEdits = settingsEdits,
                    haloController = haloController,
                    activeURL = activeURL,
                    serviceSession = serviceSession,
                    onSetNativeScreen = { activeNativeScreen = it },
                    activeNativeScreen = activeNativeScreen,
                    voiceConnected = voiceState.connected,
                    voiceSessionActive = voiceSessionActive,
                    serviceSessions = serviceSessions.values.toList(),
                    prepareAllPages = prepareAllPages,
                    catalogState = catalogState,
                    onRetryService = serviceCatalog::retry,
                    onEntryAuthInvalidated = shellRegistry::invalidateSharedOrigin,
                    onAuthInvalidated = { entry ->
                        if (sharedConversation) {
                            if (!voiceConversation.isCurrent()) {
                                voiceSession.disconnect()
                                if (localSelection != null) localModelOwner.restartConversation()
                            }
                        } else if (entry === serviceSession ||
                            isTrustedPermissionOrigin(entry.key.url.toUri(), serviceSession.key.url)
                        ) {
                            localModelOwner.clearSelection(localSelection)
                            voiceSession.disconnect()
                        }
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
                            ShellRecoveryRetryTarget.BOOTSTRAP -> serviceSession.reloadBootstrap()
                            ShellRecoveryRetryTarget.WEB -> serviceSession.reloadWeb()
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
            if (voiceState.error != null ||
                (!keyboardOpen && (shellRecovery == null || voiceSessionActive))
            ) {
                VoicePanel(
                    voiceState = voiceState,
                    voiceEnabled = voiceAvailable,
                    onConnect = {
                        prepareAllPages = true
                        if (localSelection != null || bearerStore.authReady.value) {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                voiceSession.connect()
                            } else {
                                onMicGranted = {
                                    if (localSelection != null || bearerStore.authReady.value) voiceSession.connect()
                                }
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    onDisconnect = {
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
                    onCycleApp = {
                        scope.launch {
                            try {
                                if (!settingsEdits.save(!voiceSessionActive, commitURL = true)) {
                                    snackbarHostState.showSnackbar(settingsEdits.error ?: "Unable to save settings")
                                    return@launch
                                }
                                activeNativeScreen = null
                                settingsRepository.cycleService()
                            } catch (e: IOException) {
                                snackbarHostState.showSnackbar("Unable to switch apps: ${e.message}")
                            }
                        }
                    },
                    canCycleApps = settings.services.size > 1,
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
    settingsEdits: SettingsEdits,
    haloController: HaloController,
    activeURL: String,
    serviceSession: ServiceShellSession,
    activeNativeScreen: NativeScreen?,
    onSetNativeScreen: (NativeScreen?) -> Unit,
    voiceConnected: Boolean,
    voiceSessionActive: Boolean,
    serviceSessions: List<ServiceShellSession>,
    prepareAllPages: Boolean,
    catalogState: Map<ServiceShellKey, CatalogService>,
    onRetryService: (ServiceShellKey) -> Unit,
    onEntryAuthInvalidated: (ServiceShellSession) -> Unit,
    onAuthInvalidated: (ServiceShellSession) -> Unit,
) {
    val entryStates =
        serviceSessions.associateWith { entry ->
            key(entry) { entry.state.collectAsStateWithLifecycle().value }
        }
    val authReadiness =
        serviceSessions.associateWith { entry ->
            key(entry.bearerStore) {
                entry.bearerStore.authReady
                    .collectAsStateWithLifecycle()
                    .value
            }
        }
    val retryTargets =
        serviceSessions.associateWith { entry ->
            serviceRetryTarget(
                entryStates.getValue(entry),
                authReadiness.getValue(entry),
                catalogState[entry.key]?.readiness,
            )
        }
    val servicesNeedingAttention =
        entryStates
            .filterValues { it.attentionRequired }
            .keys
            .map { it.key.serviceID }
            .toSet()
    val bearerStore = serviceSession.bearerStore
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
                edits = settingsEdits,
                voiceSessionActive = voiceSessionActive,
                servicesNeedingAttention = servicesNeedingAttention,
                serviceReadiness = catalogState.values.associate { it.key.serviceID to it.readiness.text },
                serviceErrors =
                    entryStates
                        .mapNotNull { (entry, state) ->
                            ((state.load as? WebShellLoadState.Failed)?.message ?: catalogState[entry.key]?.error)
                                ?.let { entry.key.serviceID to it }
                        }.toMap(),
                serviceRetryLabels =
                    retryTargets
                        .mapNotNull { (entry, target) ->
                            target?.let { entry.key.serviceID to it.label }
                        }.toMap(),
                onRetryService = { id ->
                    serviceSessions.firstOrNull { it.key.serviceID == id }?.let { entry ->
                        when (retryTargets[entry]) {
                            ServiceRetryTarget.BOOTSTRAP -> entry.reloadBootstrap()
                            ServiceRetryTarget.CATALOG -> onRetryService(entry.key)
                            ServiceRetryTarget.PAGE -> entry.reloadWeb()
                            null -> Unit
                        }
                    }
                },
                onDone = { onSetNativeScreen(null) },
                onOpenHalo = { onSetNativeScreen(NativeScreen.Halo) },
                bearerTokenFor = bearerStore::tokenFor,
                bearerToken = key(bearerStore) { bearerStore.token.collectAsStateWithLifecycle().value },
                authEpoch = key(bearerStore) { bearerStore.authEpoch.collectAsStateWithLifecycle().value },
            )
        }

        else -> {
            Unit
        }
    }
    serviceSessions.forEach { entry ->
        key(entry) {
            val entryState by entry.state.collectAsStateWithLifecycle()
            val selected = entry === serviceSession && activeNativeScreen == null && activeURL.isNotBlank()
            LaunchedEffect(entry, selected, prepareAllPages) {
                if (selected || prepareAllPages) entry.requestPage()
            }
            if ((entryState.pageRequested || selected || prepareAllPages) &&
                entry.key.url.isNotBlank() && entryState.bootstrap !is ServiceBootstrapState.Error
            ) {
                key(entryState.webViewGeneration) {
                    WebShellScreen(
                        serviceID = entry.key.serviceID,
                        initialURL = entry.key.url,
                        voiceConnected = voiceConnected,
                        visible = selected,
                        reloadToken = entryState.webReloadToken,
                        serviceSession = entry,
                        onLoadStateChanged = entry::updateLoad,
                        onHostedPageLoaded = {
                            if (entry.state.value.bootstrap is ServiceBootstrapState.Unvalidated) {
                                entry
                                    .reloadBootstrap()
                            }
                        },
                        onSharedAuthInvalidated = {
                            onEntryAuthInvalidated(entry)
                            onAuthInvalidated(entry)
                        },
                        onAuthInvalidated = { onAuthInvalidated(entry) },
                    )
                }
            }
        }
    }
}

internal enum class ServiceRetryTarget(
    val label: String,
) {
    BOOTSTRAP("Retry settings"),
    CATALOG("Retry connection"),
    PAGE("Reload page"),
}

internal fun serviceRetryTarget(
    state: ServiceShellState,
    authReady: Boolean,
    readiness: ServiceReadiness?,
): ServiceRetryTarget? =
    when {
        (state.load as? WebShellLoadState.Failed)?.canRetry == false -> null
        state.bootstrap !is ServiceBootstrapState.Ready -> ServiceRetryTarget.BOOTSTRAP
        state.load != WebShellLoadState.Ready || !authReady -> ServiceRetryTarget.PAGE
        readiness != null && readiness != ServiceReadiness.READY -> ServiceRetryTarget.CATALOG
        else -> null
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
                retryTarget = ShellRecoveryRetryTarget.WEB.takeIf { webLoadState.canRetry },
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

// One delivery gate also covers alerts queued while Android requests permission.
@Composable
internal fun ServiceNotifications(
    serviceCatalog: ServiceCatalog,
    activeKey: ServiceShellKey,
    serviceVisible: Boolean,
    voiceSessionActive: Boolean,
    sharedConversation: Boolean,
    configuredServices: Set<ServiceShellKey>?,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val notificationPublisher = remember(context) { ServiceNotificationPublisher(context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // New intents arrive before resume, even when Compose still shows the prior service.
        val pendingSource = activity?.intent?.let(ServiceNotificationPublisher::destination)?.source
        if (serviceVisible && (pendingSource == null || pendingSource == activeKey)) {
            notificationPublisher.dismissService(activeKey)
        }
    }
    LaunchedEffect(activeKey, serviceVisible) {
        if (serviceVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            notificationPublisher.dismissService(activeKey)
        }
    }
    LaunchedEffect(serviceCatalog, configuredServices) {
        val configured = configuredServices ?: return@LaunchedEffect
        serviceCatalog.state.collect { services ->
            notificationPublisher.reconcile(configured.associateWith { services[it]?.monitoring?.activeNotifications })
        }
    }

    val suppressNotification by rememberUpdatedState<(CatalogNotification) -> Boolean> { notification ->
        val selectedService = notification.source == activeKey
        // Read lifecycle at delivery time, including after the permission dialog closes.
        (serviceVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && selectedService) ||
            (voiceSessionActive && (sharedConversation || selectedService))
    }
    var notificationPermissionPending by remember { mutableStateOf(false) }
    var pendingNotifications by remember { mutableStateOf(emptyList<CatalogNotification>()) }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            // Recheck visibility and voice state for alerts queued before the permission prompt.
            if (granted) {
                pendingNotifications
                    .mapNotNull(serviceCatalog::notificationForDelivery)
                    .filterNot(suppressNotification)
                    .forEach { notificationPublisher.publish(it.source, it.notification) }
            }
            pendingNotifications = emptyList()
            notificationPermissionPending = false
        }
    LaunchedEffect(serviceCatalog) {
        serviceCatalog.notifications.collect { event ->
            val notification = serviceCatalog.notificationForDelivery(event) ?: return@collect
            if (!suppressNotification(notification)) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPublisher.publish(notification.source, notification.notification)
                } else {
                    pendingNotifications = (pendingNotifications + notification).takeLast(64)
                    if (!notificationPermissionPending) {
                        notificationPermissionPending = true
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }
}
