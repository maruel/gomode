// Root Compose surface coordinating service shells, permissions, timed voice startup, and recovery.
package com.fghbuild.gomode.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fghbuild.gomode.GoModeApplication
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
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.ServiceShellState
import com.fghbuild.gomode.ui.web.WebShellLoadState
import com.fghbuild.gomode.ui.web.WebShellScreen
import com.fghbuild.gomode.ui.web.isTrustedPermissionOrigin
import com.fghbuild.gomode.voice.AudioDevice
import com.fghbuild.gomode.voice.CarDialStatus
import com.fghbuild.gomode.voice.DeviceVoiceSession
import com.fghbuild.gomode.voice.LocalModelOwner
import com.fghbuild.gomode.voice.LocalModelSelection
import com.fghbuild.gomode.voice.LocalModelState
import com.fghbuild.gomode.voice.MicrophonePermissionRecovery
import com.fghbuild.gomode.voice.SpeechLanguageRecovery
import com.fghbuild.gomode.voice.TelecomCallController
import com.fghbuild.gomode.voice.VoiceConversation
import com.fghbuild.gomode.voice.VoicePanel
import com.fghbuild.gomode.voice.VoiceService
import com.fghbuild.gomode.voice.VoiceSession
import com.fghbuild.gomode.voice.VoiceSessionController
import com.fghbuild.gomode.voice.VoiceStartupOutcome
import com.fghbuild.gomode.voice.VoiceStartupSource
import com.fghbuild.gomode.voice.VoiceStartupStage
import com.fghbuild.gomode.voice.VoiceStartupTiming
import com.fghbuild.gomode.voice.VoiceState
import com.fghbuild.gomode.voice.usesGatewayVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import com.fghbuild.gomode.sdk.v1.Settings as ServiceSettings

private enum class NativeScreen {
    Settings,
    Halo,
}

internal class PendingMicrophoneVoiceStart(
    private val page: ServiceShellSession,
    private val session: VoiceSessionController,
    val timing: VoiceStartupTiming,
) {
    private val identity = page.bearerStore.identityEpoch.value
    private val document = page.frontendPreparation.document

    fun connectIfCurrent(
        currentPage: ServiceShellSession,
        currentSession: VoiceSessionController,
    ): Boolean {
        if (currentPage !== page || currentSession !== session ||
            page.bearerStore.identityEpoch.value != identity || page.frontendPreparation.document != document
        ) {
            timing.finish(VoiceStartupOutcome.SUPERSEDED)
            return false
        }
        if (page.key.url.isNotBlank() && !page.isFrontendReady) {
            timing.finish(VoiceStartupOutcome.CANCELED)
            return false
        }
        timing.enter(VoiceStartupStage.TRANSPORT)
        session.connect()
        return true
    }
}

// Start observing only after connect(), because idle is expected while permission
// is pending. The captured record seals independently of later UI/session owners.
internal fun observeVoiceStartup(
    scope: CoroutineScope,
    session: VoiceSessionController,
    timing: VoiceStartupTiming,
) {
    scope.launch {
        try {
            val result = session.state.first { it.connected || it.error != null || it.connectStatus == null }
            timing.finish(
                when {
                    result.connected -> VoiceStartupOutcome.CONNECTED
                    result.error != null -> VoiceStartupOutcome.FAILED
                    else -> VoiceStartupOutcome.CANCELED
                },
            )
        } catch (error: CancellationException) {
            timing.finish(VoiceStartupOutcome.CANCELED)
            throw error
        }
    }
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
    shellOwner: ServiceShellOwner,
    localModelOwner: LocalModelOwner,
    notificationDestination: ServiceNotificationDestination? = null,
    onNotificationHandled: (ServiceNotificationDestination) -> Unit = {},
    showWhenLocked: Boolean = false,
) {
    val context = LocalContext.current
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    val keyguardLocked = rememberKeyguardLocked()
    val settingsRepository = shellOwner.settingsRepository
    shellOwner.start()
    val shellSnapshot by shellOwner.state.collectAsStateWithLifecycle()
    val carVoiceOwner = (context.applicationContext as GoModeApplication).carDialVoiceOwner
    val carVoiceState by carVoiceOwner.state.collectAsStateWithLifecycle()
    val carVoiceSession by carVoiceOwner.session.collectAsStateWithLifecycle()
    val carVoiceBusy = carVoiceState.status != CarDialStatus.IDLE && carVoiceState.status != CarDialStatus.FAILED
    val haloController =
        remember(settingsRepository) {
            HaloController(context.applicationContext, settingsRepository)
        }
    DisposableEffect(haloController) {
        onDispose { haloController.close() }
    }

    val settings = shellSnapshot.settings
    val settingsEdits = rememberSettingsEdits(settingsRepository, settings)
    val settingsClient = remember { ServiceSettingsClient() }
    var activeNativeScreen by remember { mutableStateOf<NativeScreen?>(null) }
    LaunchedEffect(carVoiceState.identity, carVoiceState.status) {
        // A warm shell can still be showing Settings when the native call returns to it.
        if (carVoiceBusy) activeNativeScreen = null
    }
    LaunchedEffect(keyguardLocked) {
        if (keyguardLocked) activeNativeScreen = null
    }
    val activeURL = settings.activeServiceURL
    val serviceKeys = settings.services.map { ServiceShellKey(it.id, it.url) }
    val activeKey = ServiceShellKey(settings.activeServiceId, activeURL)
    val serviceSessions = shellSnapshot.entries
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
    val bearerStore = serviceSession.bearerStore
    val bearerToken by key(bearerStore) { bearerStore.token.collectAsStateWithLifecycle() }
    val authEpoch by key(bearerStore) { bearerStore.authEpoch.collectAsStateWithLifecycle() }
    val authReady by key(bearerStore) { bearerStore.authReady.collectAsStateWithLifecycle() }
    val frontendReady by key(serviceSession) { serviceSession.frontendPreparation.ready.collectAsStateWithLifecycle() }
    val shellState by key(serviceSession) { serviceSession.state.collectAsStateWithLifecycle() }
    val bootstrapState = shellState.bootstrap
    val webLoadState = shellState.load

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val serviceCatalog = shellOwner.catalog
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
    DisposableEffect(localModelOwner, localSelection, carVoiceBusy) {
        if (!carVoiceBusy) localModelOwner.select(localSelection)
        onDispose { if (!carVoiceBusy) localModelOwner.clearSelection(localSelection) }
    }
    val localModelState by key(localModelOwner) { localModelOwner.state.collectAsStateWithLifecycle() }
    val voiceCallController =
        remember(context.applicationContext) {
            TelecomCallController(context.applicationContext, onTelecomEnded = {})
        }
    DisposableEffect(voiceCallController) {
        onDispose { voiceCallController.close() }
    }
    val voiceServiceOwner = if (sharedConversation) voiceConversation else bearerStore
    var manualStartup by remember { mutableStateOf<VoiceStartupTiming?>(null) }
    val manualVoiceSession: VoiceSessionController =
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
                        startupTiming = { manualStartup },
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
                        startupTiming = { manualStartup },
                    )
                }
            }
        }
    DisposableEffect(manualVoiceSession) {
        carVoiceOwner.registerManualSession(manualVoiceSession)
        onDispose {
            manualStartup?.finish(VoiceStartupOutcome.CANCELED)
            carVoiceOwner.unregisterManualSession(manualVoiceSession)
            manualVoiceSession.close()
        }
    }
    val voiceSession = carVoiceSession ?: manualVoiceSession
    val deviceVoiceSession = voiceSession as? DeviceVoiceSession
    val speechLanguageState =
        deviceVoiceSession?.let { session ->
            key(session) { session.speechLanguageState.collectAsStateWithLifecycle().value }
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
        if (!sharedConversation && (changed || !authReady)) manualVoiceSession.disconnect()
    }
    val controllerState by key(voiceSession) { voiceSession.state.collectAsStateWithLifecycle() }
    val voiceState =
        when {
            carVoiceBusy && carVoiceSession == null -> {
                controllerState.copy(connectStatus = "Connecting voice…")
            }

            carVoiceState.status == CarDialStatus.FAILED -> {
                controllerState.copy(error = stringResource(carVoiceState.failure.message))
            }

            else -> {
                controllerState
            }
        }
    LaunchedEffect(voiceState.connected) { shellOwner.setVoiceConnected(voiceState.connected) }
    val selectAudioDevice = rememberVoiceAudioSelection(voiceSession, settingsRepository)
    var knownAudioDevices by remember(voiceSession) { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(voiceSession, voiceState.availableDevices) {
        val previousIds = knownAudioDevices
        val id = preferredVoiceAudioDevice(voiceSession.state, settingsRepository.settings, previousIds)
        knownAudioDevices = voiceState.availableDevices.map { it.id }.toSet()
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
    var connectAfterMicPermission by remember { mutableStateOf<PendingMicrophoneVoiceStart?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            connectAfterMicPermission?.timing?.finish(VoiceStartupOutcome.CANCELED)
            connectAfterMicPermission = null
        }
    }
    val activity = LocalActivity.current
    var microphoneDenied by rememberSaveable { mutableStateOf(false) }
    var microphoneNeedsSettings by rememberSaveable { mutableStateOf(false) }
    var microphoneHadExplicitDenial by rememberSaveable { mutableStateOf(false) }
    var microphoneWasGranted by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            microphoneDenied = false
            microphoneNeedsSettings = false
            microphoneHadExplicitDenial = false
        } else if (microphoneWasGranted) {
            voiceSession.disconnect()
            microphoneDenied = true
            microphoneNeedsSettings = microphoneHadExplicitDenial && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        }
        microphoneWasGranted = granted
    }
    val micPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val rationale =
                activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            microphoneWasGranted = granted
            microphoneDenied = !granted
            // A dismissed first prompt also returns false without a rationale. Only a
            // previously observed explicit denial proves that prompting has stopped.
            microphoneNeedsSettings = !granted && microphoneHadExplicitDenial && !rationale
            if (!granted && rationale) microphoneHadExplicitDenial = true
            if (granted) {
                microphoneHadExplicitDenial = false
                if (!carVoiceBusy &&
                    (localSelection != null || bearerStore.authReady.value) &&
                    (activeURL.isBlank() || serviceSession.isFrontendReady)
                ) {
                    val pending = connectAfterMicPermission
                    connectAfterMicPermission = null
                    if (pending?.connectIfCurrent(serviceSession, manualVoiceSession) == true) {
                        observeVoiceStartup(scope, manualVoiceSession, pending.timing)
                    }
                }
            }
            connectAfterMicPermission?.timing?.finish(VoiceStartupOutcome.CANCELED)
            connectAfterMicPermission = null
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
        if (!carVoiceBusy && sharedConversation && gatewayState != null) {
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
        voiceSessionActive = !carVoiceBusy && voiceSessionActive,
        sharedConversation = sharedConversation,
    )
    val configuredVoiceAvailable =
        (bootstrapState as? ServiceBootstrapState.Ready)
            ?.settings
            ?.webShell
            ?.voiceGateway
            ?.url
            ?.isNotBlank() == true
    val frontendVoiceAvailable =
        activeURL.isBlank() ||
            (frontendReady && authReady && webLoadState == WebShellLoadState.Ready)
    val voiceAvailable =
        carVoiceBusy || voiceSessionActive ||
            (
                frontendVoiceAvailable && (
                    (localSelection != null && localModelState == LocalModelState.Ready) ||
                        (authReady && configuredVoiceAvailable && shellRecovery == null)
                )
            )
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
                    onSetNativeScreen = { if (!keyguard.isKeyguardLocked) activeNativeScreen = it },
                    activeNativeScreen = activeNativeScreen.takeUnless { keyguardLocked },
                    voiceConnected = voiceState.connected,
                    showWhenLocked = showWhenLocked,
                    voiceSessionActive = voiceSessionActive,
                    serviceSessions = serviceSessions.values.toList(),
                    prepareAllPages = prepareAllPages,
                    catalogState = catalogState,
                    onRetryService = serviceCatalog::retry,
                    onAuthInvalidated = { entry ->
                        if (!carVoiceBusy) {
                            if (sharedConversation) {
                                if (!voiceConversation.isCurrent()) {
                                    manualVoiceSession.disconnect()
                                    if (localSelection != null) localModelOwner.restartConversation()
                                }
                            } else if (entry === serviceSession ||
                                isTrustedPermissionOrigin(entry.key.url.toUri(), serviceSession.key.url)
                            ) {
                                localModelOwner.clearSelection(localSelection)
                                manualVoiceSession.disconnect()
                            }
                        }
                    },
                )
            }
            shellRecovery?.let { recovery ->
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
                    onOpenSettings = { if (!keyguard.isKeyguardLocked) activeNativeScreen = NativeScreen.Settings },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            (localModelState as? LocalModelState.Failed)?.let { failure ->
                Column(Modifier.fillMaxWidth().padding(12.dp).testTag("gomode-local-model-error")) {
                    Text(failure.message)
                    FilledTonalIconButton(onClick = localModelOwner::retry) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry local model")
                    }
                }
            }
            // Settings owns the full screen; elsewhere, keep failures visible even with the keyboard open.
            if (activeURL.isNotBlank() && activeNativeScreen != NativeScreen.Settings &&
                (voiceState.error != null || (!keyboardOpen && (shellRecovery == null || voiceSessionActive)))
            ) {
                VoicePanel(
                    speechRecovery = {
                        if (deviceVoiceSession != null && speechLanguageState != null) {
                            SpeechLanguageRecovery(
                                state = speechLanguageState,
                                languageTag = settings.voiceLanguageTag,
                                active = voiceSessionActive,
                                onDownload = {
                                    if (!keyguard.isKeyguardLocked) deviceVoiceSession.downloadSpeechLanguage()
                                },
                                onCheck = deviceVoiceSession::checkSpeechLanguage,
                                onSettings = {
                                    if (!keyguard.isKeyguardLocked) activeNativeScreen = NativeScreen.Settings
                                },
                            )
                        }
                    },
                    microphoneRecovery =
                        if (microphoneDenied) {
                            MicrophonePermissionRecovery(
                                onRequest =
                                    if (microphoneNeedsSettings) {
                                        null
                                    } else {
                                        {
                                            if (!keyguard.isKeyguardLocked) {
                                                connectAfterMicPermission?.timing?.finish(VoiceStartupOutcome.CANCELED)
                                                connectAfterMicPermission = null
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    },
                                onOpenSettings = {
                                    if (!keyguard.isKeyguardLocked) {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                "package:${context.packageName}".toUri(),
                                            ),
                                        )
                                    }
                                },
                            )
                        } else {
                            null
                        },
                    voiceState = voiceState,
                    voiceEnabled = voiceAvailable,
                    onConnect = {
                        manualStartup?.finish(VoiceStartupOutcome.SUPERSEDED)
                        connectAfterMicPermission = null
                        val timing = VoiceStartupTiming(VoiceStartupSource.MANUAL)
                        manualStartup = timing
                        activeNativeScreen = null
                        carVoiceOwner.cancel()
                        prepareAllPages = true
                        // Manual starts are enabled only after these prerequisites.
                        // Record checkpoints without adding a second preparation wait.
                        timing.enter(VoiceStartupStage.PAGE)
                        timing.enter(VoiceStartupStage.SIGN_IN)
                        timing.enter(VoiceStartupStage.TOOLS)
                        timing.enter(VoiceStartupStage.FRONTEND)
                        if ((localSelection != null || bearerStore.authReady.value) &&
                            (activeURL.isBlank() || serviceSession.isFrontendReady)
                        ) {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                timing.enter(VoiceStartupStage.TRANSPORT)
                                manualVoiceSession.connect()
                                observeVoiceStartup(scope, manualVoiceSession, timing)
                            } else {
                                timing.enter(VoiceStartupStage.MICROPHONE)
                                connectAfterMicPermission =
                                    PendingMicrophoneVoiceStart(serviceSession, manualVoiceSession, timing)
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            timing.finish(VoiceStartupOutcome.FAILED)
                        }
                    },
                    onDisconnect = {
                        connectAfterMicPermission?.timing?.finish(VoiceStartupOutcome.CANCELED)
                        connectAfterMicPermission = null
                        if (carVoiceState.status != CarDialStatus.IDLE) {
                            carVoiceOwner.cancel()
                        } else {
                            manualStartup?.finish(VoiceStartupOutcome.CANCELED)
                            manualVoiceSession.disconnect()
                        }
                    },
                    onSelectMicrophoneMode = { voiceSession.setMicrophoneMode(it) },
                    onSelectDevice = selectAudioDevice,
                    onOpenSettings = { if (!keyguard.isKeyguardLocked) activeNativeScreen = NativeScreen.Settings },
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

// Both native call controls and the hosted shell persist only confirmed route selections.
@Composable
internal fun rememberVoiceAudioSelection(
    session: VoiceSessionController?,
    settingsRepository: SettingsRepository,
): (Int) -> Unit {
    val scope = rememberCoroutineScope()
    var selectionJob by remember(session) { mutableStateOf<Job?>(null) }
    DisposableEffect(session) {
        onDispose { selectionJob?.cancel() }
    }
    return select@{ id ->
        val current = session ?: return@select
        val device =
            current.state.value.availableDevices
                .firstOrNull { it.id == id } ?: return@select
        selectionJob?.cancel()
        current.selectAudioDevice(id)
        selectionJob =
            scope.launch {
                if (awaitVoiceAudioSelection(current.state, id)) {
                    settingsRepository.updateVoiceAudioDeviceType(device.type)
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
            !it.audioSwitching && (
                it.selectedDeviceId == deviceId || it.audioError != null ||
                    it.availableDevices.none { device -> device.id == deviceId }
            )
        }
    return state.selectedDeviceId == deviceId && state.audioError == null
}

// Restore a saved output type against the currently available pre-call or live endpoints.
internal suspend fun preferredVoiceAudioDevice(
    states: StateFlow<VoiceState>,
    settings: StateFlow<SettingsState>,
    previousIds: Set<Int>,
): Int? {
    val loadedSettings = settings.first { it.loadState == SettingsLoadState.Ready }
    // A list change can arrive while Telecom rejects route requests. Wait once; do not retry routing errors.
    val state = states.first { !it.audioSwitching }
    val preferred =
        state.availableDevices.firstOrNull { it.isBluetooth() && it.id !in previousIds }
            ?: state.availableDevices.firstOrNull { it.type == loadedSettings.voiceAudioDeviceType }
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
    showWhenLocked: Boolean,
    voiceSessionActive: Boolean,
    serviceSessions: List<ServiceShellSession>,
    prepareAllPages: Boolean,
    catalogState: Map<ServiceShellKey, CatalogService>,
    onRetryService: (ServiceShellKey) -> Unit,
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
                        voiceConnected = voiceConnected,
                        showWhenLocked = showWhenLocked,
                        visible = selected,
                        reloadToken = entryState.webReloadToken,
                        serviceSession = entry,
                        onSharedAuthInvalidated = {
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
                    FilledTonalIconButton(onClick = onRetry, modifier = Modifier.testTag("gomode-shell-retry")) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry service")
                    }
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("gomode-shell-open-settings")) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
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
    val keyguardLocked = rememberKeyguardLocked()
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(keyguardLocked, lifecycleState, pendingNotifications, notificationPermissionPending) {
        if (!keyguardLocked && pendingNotifications.isNotEmpty() && !notificationPermissionPending &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        ) {
            notificationPermissionPending = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
                }
            }
        }
    }
}

// A visible service can remain resumed over keyguard; observe unlock independently of resume events.
@Composable
private fun rememberKeyguardLocked(): Boolean {
    val keyguard = LocalContext.current.getSystemService(KeyguardManager::class.java)
    var locked by remember(keyguard) { mutableStateOf(keyguard.isKeyguardLocked) }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(keyguard) {
        val job =
            scope.launch {
                while (isActive) {
                    locked = keyguard.isKeyguardLocked
                    delay(250)
                }
            }
        onPauseOrDispose { job.cancel() }
    }
    return locked
}
