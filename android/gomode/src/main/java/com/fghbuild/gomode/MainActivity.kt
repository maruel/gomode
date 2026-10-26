// Hosts the ordinary shell, authorized car calls, and source-specific notification navigation.
package com.fghbuild.gomode

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.service.ServiceNotificationDestination
import com.fghbuild.gomode.service.ServiceNotificationPublisher
import com.fghbuild.gomode.ui.GoModeApp
import com.fghbuild.gomode.ui.rememberVoiceAudioSelection
import com.fghbuild.gomode.ui.theme.GoModeTheme
import com.fghbuild.gomode.voice.AudioDevicePicker
import com.fghbuild.gomode.voice.CarDialStatus
import com.fghbuild.gomode.voice.CarDialVoiceState
import com.fghbuild.gomode.voice.TranscriptLog
import com.fghbuild.gomode.voice.VoiceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var notificationDestination by mutableStateOf<ServiceNotificationDestination?>(null)
    private val carVoiceOwner get() = (application as GoModeApplication).carDialVoiceOwner
    private val callAttachment = Any()
    private var carRequest: String? = null
    private var lockedCarCall by mutableStateOf(false)
    private var startingCarCall by mutableStateOf(false)
    private var showServiceOnLockScreen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        attachCarRequest(intent.getStringExtra(EXTRA_CAR_REQUEST) ?: savedInstanceState?.getString(EXTRA_CAR_REQUEST))
        if (savedInstanceState?.getBoolean("notificationHandled") != true) {
            notificationDestination = ServiceNotificationPublisher.destination(intent)
        }
        val shellOwner = (application as GoModeApplication).serviceShellOwner
        setContent {
            GoModeTheme {
                when {
                    lockedCarCall && !showServiceOnLockScreen -> {
                        val state by carVoiceOwner.state.collectAsStateWithLifecycle()
                        val session by carVoiceOwner.session.collectAsStateWithLifecycle()
                        val voiceState = session?.state?.collectAsStateWithLifecycle()?.value ?: VoiceState()
                        LockedCarCall(
                            state = state,
                            voiceState = voiceState,
                            onSelectDevice = rememberVoiceAudioSelection(session, shellOwner.settingsRepository),
                            onEnd = { carVoiceOwner.cancel(state.identity) },
                        )
                    }

                    startingCarCall -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    else -> {
                        GoModeApp(
                            shellOwner = shellOwner,
                            showWhenLocked = showServiceOnLockScreen,
                            notificationDestination = notificationDestination,
                            onNotificationHandled = { handled ->
                                if (notificationDestination == handled) {
                                    notificationDestination = null
                                    setIntent(Intent(this, MainActivity::class.java))
                                }
                            },
                            localModelOwner = (application as GoModeApplication).localModelOwner,
                        )
                    }
                }
                SideEffect { window.decorView.visibility = View.VISIBLE }
            }
        }
        lifecycleScope.launch {
            // Revoke the window grant even while this Activity is paused or the display is asleep.
            combine(carVoiceOwner.state, shellOwner.settingsRepository.settings) { _, _ -> Unit }
                .collect { updateCarSurface() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Keyguard dismissal can settle after resume or its unlock broadcast.
                while (isActive) {
                    updateCarSurface()
                    delay(250)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        attachCarRequest(intent.getStringExtra(EXTRA_CAR_REQUEST))
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startCarCall()
        notificationDestination = ServiceNotificationPublisher.destination(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("notificationHandled", notificationDestination == null)
        outState.putString(EXTRA_CAR_REQUEST, carRequest)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        startCarCall()
    }

    override fun onDestroy() {
        carVoiceOwner.detach(carRequest, callAttachment, isChangingConfigurations)
        super.onDestroy()
    }

    private fun attachCarRequest(identity: String?) {
        // MainActivity is exported. The nonce only attaches authority already granted
        // by call redirection in this process; arbitrary intents cannot start voice.
        intent.removeExtra(EXTRA_CAR_REQUEST)
        if (!carVoiceOwner.attach(identity, callAttachment)) return
        carRequest = identity
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val hideHostedContent = locked && !lockedCarCall
        lockedCarCall = locked
        val settings = (application as GoModeApplication).settingsRepository.settings.value
        showServiceOnLockScreen = settings.loadState == SettingsLoadState.Ready && settings.showServiceOnLockScreen
        startingCarCall = true
        // A warm shell may still contain hosted pixels until Compose applies its
        // locked surface. Hide those before making this window eligible above keyguard.
        if (hideHostedContent) window.decorView.visibility = View.INVISIBLE
        setShowWhenLocked(lockedCarCall || showServiceOnLockScreen)
        setTurnScreenOn(lockedCarCall)
    }

    private fun startCarCall() {
        carRequest?.let { carVoiceOwner.start(it, callAttachment) }
        updateCarSurface()
    }

    private fun updateCarSurface() {
        val state = carVoiceOwner.state.value
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val wasAboveKeyguard = lockedCarCall || showServiceOnLockScreen
        val wasShowingService = showServiceOnLockScreen
        if (lockedCarCall && !locked) {
            lockedCarCall = false
            setTurnScreenOn(false)
        }
        val settings = (application as GoModeApplication).settingsRepository.settings.value
        showServiceOnLockScreen = carVoiceOwner.isBusy && settings.loadState == SettingsLoadState.Ready &&
            settings.showServiceOnLockScreen
        if (locked && wasShowingService && !showServiceOnLockScreen) window.decorView.visibility = View.INVISIBLE
        if (state.status == CarDialStatus.IDLE) {
            lockedCarCall = false
            setTurnScreenOn(false)
        }
        updateCarStartup(state)
        val aboveKeyguard = lockedCarCall || showServiceOnLockScreen
        if (wasAboveKeyguard != aboveKeyguard) {
            if (locked && !aboveKeyguard) window.decorView.visibility = View.INVISIBLE
            setShowWhenLocked(aboveKeyguard)
        }
    }

    private fun updateCarStartup(state: CarDialVoiceState) {
        val identity = carRequest ?: return
        if (state.identity != identity || state.status == CarDialStatus.IDLE || state.status == CarDialStatus.FAILED) {
            startingCarCall = false
            if (lockedCarCall && state.status == CarDialStatus.FAILED) return
            lockedCarCall = false
            setTurnScreenOn(false)
            carRequest = null
        } else if ((!lockedCarCall || showServiceOnLockScreen) &&
            carVoiceOwner.releaseToShell(identity, callAttachment)
        ) {
            carRequest = null
            startingCarCall = false
        }
    }

    companion object {
        internal const val EXTRA_CAR_REQUEST = "car_dial_request"
    }
}

// Keep hosted content behind keyguard while exposing the current call and its audio controls.
@Composable
internal fun LockedCarCall(
    state: CarDialVoiceState,
    voiceState: VoiceState,
    onSelectDevice: (Int) -> Unit,
    onEnd: () -> Unit,
) {
    val failed = state.status == CarDialStatus.FAILED
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            ElevatedCard(Modifier.widthIn(max = 360.dp).fillMaxWidth().semantics { testTagsAsResourceId = true }) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Go Mode", style = MaterialTheme.typography.labelLarge)
                    Text(
                        state.serverName,
                        modifier = Modifier.testTag("car_dial_server"),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(
                            when (state.status) {
                                CarDialStatus.CONNECTED -> R.string.car_dial_connected
                                CarDialStatus.FAILED -> state.failure.message
                                else -> R.string.car_dial_connecting
                            },
                        ),
                        modifier = Modifier.testTag("car_dial_status"),
                        textAlign = TextAlign.Center,
                    )
                    if (voiceState.availableDevices.isNotEmpty() || voiceState.transcript.isNotEmpty() ||
                        voiceState.audioError != null
                    ) {
                        Column(
                            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (voiceState.availableDevices.isNotEmpty()) {
                                Text("Audio", style = MaterialTheme.typography.labelLarge)
                                AudioDevicePicker(
                                    devices = voiceState.availableDevices,
                                    selectedDeviceId = voiceState.selectedDeviceId,
                                    switching = voiceState.audioSwitching,
                                    onSelect = onSelectDevice,
                                )
                            }
                            voiceState.audioError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (voiceState.transcript.isNotEmpty()) {
                                Text("Transcript", style = MaterialTheme.typography.labelLarge)
                                TranscriptLog(
                                    entries = voiceState.transcript,
                                    modifier = Modifier.fillMaxWidth().testTag("gomode-voice-transcript"),
                                )
                            }
                        }
                    }
                    Button(
                        onClick = onEnd,
                        modifier = Modifier.fillMaxWidth().testTag("car_dial_end"),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    if (failed) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                            ),
                    ) {
                        Icon(
                            if (failed) Icons.Filled.Close else Icons.Filled.CallEnd,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(stringResource(if (failed) R.string.car_dial_dismiss else R.string.car_dial_end))
                    }
                }
            }
        }
    }
}
