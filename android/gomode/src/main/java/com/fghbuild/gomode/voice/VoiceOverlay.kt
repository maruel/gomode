// Full-width bottom shell panel composable: settings, voice controls, status, and transcript display.
package com.fghbuild.gomode.voice

import android.media.AudioDeviceInfo
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.caic.voicegateway.sdk.v1.TurnState
import kotlinx.coroutines.launch

private const val PULSE_MIN_ALPHA = 0.5f
private const val PULSE_MAX_ALPHA = 1.0f
private const val PULSE_DURATION_MS = 1000
private const val SERVICE_RECONNECT_REASON_LIMIT = 200
private val WHITESPACE = Regex("\\s+")
private const val BAR_COUNT = 3
private const val BAR_MIN_HEIGHT = 4f
private const val BAR_MAX_HEIGHT = 20f
private const val BAR_CONTAINER_SIZE = 24
private val TranscriptHeight = 220.dp

data class MicrophonePermissionRecovery(
    val onRequest: (() -> Unit)?,
    val onOpenSettings: () -> Unit,
)

/**
 * Why service feeds restarted: the failing service, if it has a name, and the transport error that
 * ended the last monitoring attempt. The monitor retries on its own, so this is explanatory only.
 */
data class ServiceReconnectDetail(
    val serviceLabel: String?,
    val reason: String,
)

@Composable
fun VoicePanel(
    voiceState: VoiceState,
    voiceEnabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectMicrophoneMode: (MicrophoneMode) -> Unit,
    onSelectDevice: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    serviceReconnect: ServiceReconnectDetail?,
    onCycleApp: () -> Unit,
    canCycleApps: Boolean,
    modifier: Modifier = Modifier,
    microphoneRecovery: MicrophonePermissionRecovery? = null,
    speechRecovery: (@Composable () -> Unit)? = null,
) {
    val appControls: @Composable () -> Unit = {
        AppControls(onOpenSettings, onCycleApp, canCycleApps)
    }
    Surface(
        modifier = modifier,
        tonalElevation = 4.dp,
    ) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            VoiceToolbar(
                voiceState,
                voiceEnabled,
                onConnect,
                onDisconnect,
                onSelectMicrophoneMode,
                onSelectDevice,
                appControls,
                serviceReconnect,
            )
            microphoneRecovery?.let { recovery ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("gomode-microphone-recovery")) {
                    Text(
                        if (recovery.onRequest == null) {
                            "Microphone access is off. Allow microphone access in app permissions."
                        } else {
                            "Microphone access is off. You can keep using the app without voice."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow {
                        recovery.onRequest?.let { request ->
                            TextButton(onClick = request) { Text("Enable microphone") }
                        }
                        // Keep settings reachable even when denial predates this activity.
                        TextButton(onClick = recovery.onOpenSettings) { Text("Open app settings") }
                    }
                }
            }
            speechRecovery?.invoke()
            voiceState.audioError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when {
                voiceState.error != null -> {
                    Text(
                        text = voiceState.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("gomode-voice-error-message"),
                    )
                }

                voiceState.connectStatus != null -> {
                    ConnectingStatus(voiceState.connectStatus)
                }

                voiceState.connected -> {
                    ActivePanel(voiceState)
                }
            }
        }
    }
}

// Service updates resume on their own. The spinner reports the retry, and a tap reveals which
// service feed restarted and the reason it ended without asking for an action.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceReconnectIndicator(detail: ServiceReconnectDetail) {
    val scope = rememberCoroutineScope()
    val tooltip = rememberTooltipState(isPersistent = true)
    val reason = serviceReconnectReasonText(detail.reason)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        tooltip = {
            RichTooltip(
                title = { Text("Service updates are reconnecting") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            detail.serviceLabel?.let { "Loading service items and notifications from $it." }
                                ?: "Loading service items and notifications.",
                        )
                        Text("Reason: $reason")
                        Text("Retries automatically. No action needed.")
                    }
                },
            )
        },
        state = tooltip,
    ) {
        IconButton(
            onClick = { scope.launch { if (tooltip.isVisible) tooltip.dismiss() else tooltip.show() } },
            modifier =
                Modifier
                    .testTag("gomode-service-status")
                    .semantics {
                        contentDescription = "Service updates are reconnecting"
                        // TalkBack states the cause on focus; the tooltip only repeats it.
                        stateDescription = "Reason: $reason"
                    },
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// Transport and deserialization messages can be long and can quote response payloads, so the
// reason is collapsed to one line and bounded before it reaches the panel or the accessibility tree.
internal fun serviceReconnectReasonText(reason: String): String {
    val collapsed = reason.replace(WHITESPACE, " ").trim()
    if (collapsed.length <= SERVICE_RECONNECT_REASON_LIMIT) return collapsed
    return collapsed.take(SERVICE_RECONNECT_REASON_LIMIT).trimEnd() + "…"
}

@Composable
private fun AppControls(
    onOpenSettings: () -> Unit,
    onCycleApp: () -> Unit,
    canCycleApps: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsButton(onOpenSettings)
        if (canCycleApps) {
            IconButton(
                onClick = onCycleApp,
                modifier = Modifier.testTag("gomode-next-app"),
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = "Next server")
            }
        }
    }
}

@Composable
private fun SettingsButton(onOpenSettings: () -> Unit) {
    IconButton(
        onClick = onOpenSettings,
        modifier = Modifier.testTag("gomode-web-open-settings"),
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = "Settings",
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun VoiceToolbar(
    voiceState: VoiceState,
    voiceEnabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectMicrophoneMode: (MicrophoneMode) -> Unit,
    onSelectDevice: (Int) -> Unit,
    appControls: @Composable () -> Unit,
    serviceReconnect: ServiceReconnectDetail?,
) {
    val active = voiceState.connected || voiceState.connectStatus != null
    Row(
        // A 24dp icon is centered in its 48dp target: put its visible left edge at 16dp.
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
                .testTag("gomode-voice-actions"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            appControls()
            if (voiceState.availableDevices.isNotEmpty()) {
                AudioDevicePicker(
                    devices = voiceState.availableDevices,
                    selectedDeviceId = voiceState.selectedDeviceId,
                    switching = voiceState.audioSwitching || voiceState.connectStatus != null,
                    onSelect = onSelectDevice,
                )
            }
            if (voiceState.connected) {
                MicrophoneModeButton(voiceState.microphoneMode, voiceState.speaking, onSelectMicrophoneMode)
            }
        }
        if (voiceState.connected) MicLevelIndicator(micLevel = voiceState.micLevel)
        if (serviceReconnect != null) ServiceReconnectIndicator(serviceReconnect)
        IconButton(
            onClick = if (active) onDisconnect else onConnect,
            enabled = active || voiceEnabled,
            modifier =
                Modifier.testTag(
                    if (active) {
                        "gomode-voice-disconnect"
                    } else if (voiceEnabled) {
                        "gomode-voice-connect"
                    } else {
                        "gomode-voice-disabled"
                    },
                ),
        ) {
            Icon(
                if (active) Icons.Default.CallEnd else Icons.Default.Mic,
                contentDescription =
                    when {
                        voiceState.connectStatus != null -> "Cancel"
                        voiceState.connected -> "End voice"
                        voiceEnabled -> "Connect voice assistant"
                        else -> "Voice unavailable"
                    },
                tint = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectingStatus(status: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = PULSE_MIN_ALPHA,
        targetValue = PULSE_MAX_ALPHA,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = PULSE_DURATION_MS),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulseAlpha",
    )
    Text(
        text = status,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).alpha(alpha),
    )
}

private fun nextMicrophoneMode(mode: MicrophoneMode): MicrophoneMode =
    when (mode) {
        MicrophoneMode.LISTENING -> MicrophoneMode.MUTE_ONCE
        MicrophoneMode.MUTE_ONCE -> MicrophoneMode.MUTED
        MicrophoneMode.MUTED -> MicrophoneMode.LISTENING
    }

private fun microphoneModeLabel(mode: MicrophoneMode): String =
    when (mode) {
        MicrophoneMode.LISTENING -> "Listening"
        MicrophoneMode.MUTE_ONCE -> "Mute once"
        MicrophoneMode.MUTED -> "Muted"
    }

private fun microphoneModeHelp(
    mode: MicrophoneMode,
    speaking: Boolean,
): String? =
    when (mode) {
        MicrophoneMode.LISTENING -> {
            null
        }

        MicrophoneMode.MUTE_ONCE -> {
            if (speaking) "Muted until this reply ends." else "Mutes during the next reply, then returns to Listening."
        }

        MicrophoneMode.MUTED -> {
            "Stays muted until you unmute."
        }
    }

@Composable
private fun MicrophoneModeButton(
    mode: MicrophoneMode,
    speaking: Boolean,
    onSelect: (MicrophoneMode) -> Unit,
) {
    IconButton(
        onClick = { onSelect(nextMicrophoneMode(mode)) },
        modifier =
            Modifier.testTag("gomode-microphone-mode").semantics {
                contentDescription = "Microphone mode. Next: ${microphoneModeLabel(nextMicrophoneMode(mode))}"
                stateDescription =
                    listOfNotNull(microphoneModeLabel(mode), microphoneModeHelp(mode, speaking)).joinToString(". ")
            },
    ) {
        Box(Modifier.size(32.dp)) {
            Icon(
                if (mode == MicrophoneMode.LISTENING) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center),
            )
            if (mode == MicrophoneMode.MUTE_ONCE) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(14.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .testTag("gomode-microphone-clock"),
                )
            }
        }
    }
}

@Composable
private fun TranscriptToggle(
    expanded: Boolean,
    focusRequester: FocusRequester,
    onToggle: () -> Unit,
) {
    val closeIconSize =
        with(LocalDensity.current) {
            MaterialTheme.typography.labelSmall.fontSize
                .toDp() * 1.25f
        }
    IconButton(
        onClick = onToggle,
        modifier = Modifier.size(48.dp).focusRequester(focusRequester).testTag("gomode-voice-transcript-toggle"),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = if (expanded) Alignment.TopCenter else Alignment.Center,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.Close else Icons.Default.Subtitles,
                contentDescription = if (expanded) "Hide transcript" else "Show transcript",
                modifier =
                    Modifier
                        .padding(
                            top = if (expanded) 2.dp else 0.dp,
                        ).size(if (expanded) closeIconSize else 24.dp),
            )
        }
    }
}

@Composable
private fun ActivePanel(voiceState: VoiceState) {
    var transcriptExpanded by rememberSaveable { mutableStateOf(true) }
    val transcriptFocus = remember { FocusRequester() }
    var focusAfterToggle by remember { mutableStateOf(false) }
    val transcriptToggle: @Composable () -> Unit = {
        TranscriptToggle(transcriptExpanded, transcriptFocus) {
            focusAfterToggle = true
            transcriptExpanded = !transcriptExpanded
        }
    }
    LaunchedEffect(transcriptExpanded) {
        if (focusAfterToggle) {
            if (voiceState.transcript.isNotEmpty()) transcriptFocus.requestFocus()
            focusAfterToggle = false
        }
    }

    Column(
        modifier = Modifier.padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag("gomode-voice-activity"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = voiceStatusText(voiceState),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (voiceState.activeTool != null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (voiceState.transcript.isNotEmpty() && !transcriptExpanded) transcriptToggle()
        }

        if (transcriptExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("gomode-voice-transcript-content"),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TranscriptLog(
                    entries = voiceState.transcript,
                    modifier = Modifier.weight(1f).testTag("gomode-voice-transcript"),
                )
                if (voiceState.transcript.isNotEmpty()) transcriptToggle()
            }
        }
    }
}

@Composable
internal fun AudioDevicePicker(
    devices: List<AudioDevice>,
    selectedDeviceId: Int?,
    switching: Boolean,
    onSelect: (Int) -> Unit,
) {
    val groupColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier =
            Modifier.drawBehind {
                val inset = 4.dp.toPx()
                val height = size.height - inset * 2
                drawRoundRect(
                    color = groupColor,
                    topLeft = Offset(0f, inset),
                    size = Size(size.width, height),
                    cornerRadius = CornerRadius(height / 2),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        devices.forEach { device ->
            val selected = device.id == selectedDeviceId
            OutlinedIconToggleButton(
                modifier = Modifier.testTag("gomode-voice-audio-${device.id}"),
                checked = selected,
                enabled = !switching,
                onCheckedChange = { onSelect(device.id) },
                border =
                    if (selected) {
                        BorderStroke(
                            1.dp,
                            if (switching) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        null
                    },
                colors =
                    IconButtonDefaults.outlinedIconToggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        disabledContainerColor =
                            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    ),
            ) {
                Icon(
                    audioDeviceIcon(device.type),
                    contentDescription = device.name,
                    modifier = Modifier.size(24.dp).testTag("gomode-voice-audio-icon-${device.id}"),
                )
            }
        }
    }
}

internal fun audioDeviceIcon(type: Int): ImageVector =
    when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        -> Icons.Default.Bluetooth

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> Icons.Default.Headphones

        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Icons.Default.PhoneInTalk

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        -> Icons.Default.Speaker

        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        -> Icons.Default.Usb

        else -> Icons.AutoMirrored.Filled.VolumeUp
    }

@Composable
internal fun TranscriptLog(
    entries: List<TranscriptEntry>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var followingTail by remember { mutableStateOf(true) }
    val manualScroll =
        remember(listState) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) followingTail = false
                    return Offset.Zero
                }
            }
        }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, forward) ->
            if (!scrolling && !forward) followingTail = true
        }
    }
    val lastEntryText = entries.lastOrNull()?.text
    LaunchedEffect(entries.size, lastEntryText, followingTail) {
        if (entries.isEmpty()) {
            followingTail = true
        } else if (followingTail) {
            // A bottom sentinel reaches the end even when the last message is taller than the viewport.
            listState.scrollToItem(entries.size)
        }
    }
    if (entries.isNotEmpty()) {
        LazyColumn(
            state = listState,
            modifier = modifier.heightIn(max = TranscriptHeight).nestedScroll(manualScroll),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(entries) { _, entry ->
                val isUser = entry.speaker == TranscriptSpeaker.USER
                val label = if (isUser) "You" else "Assistant"
                val labelColor =
                    if (isUser) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                Text(
                    text =
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = labelColor, fontWeight = FontWeight.SemiBold)) {
                                append("$label: ")
                            }
                            append(entry.text)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("gomode-voice-transcript-end"))
            }
        }
    }
}

@Composable
private fun MicLevelIndicator(micLevel: Float = 0f) {
    val durations = intArrayOf(80, 40, 120)
    Box(
        modifier = Modifier.size(BAR_CONTAINER_SIZE.dp).testTag("gomode-voice-level"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(BAR_COUNT) { index ->
                val target = BAR_MIN_HEIGHT + micLevel * (BAR_MAX_HEIGHT - BAR_MIN_HEIGHT)
                val height by animateFloatAsState(
                    targetValue = target,
                    animationSpec = tween(durationMillis = durations[index]),
                    label = "bar$index",
                )
                Box(
                    modifier =
                        Modifier
                            .width(3.dp)
                            .height(height.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/**
 * Tool execution outranks speech, speech outranks gateway work, and gateway work outranks muting
 * because a muted user still waits for the reply.
 */
internal fun voiceStatusText(state: VoiceState): String =
    when {
        state.activeTool != null -> "Calling tool: ${state.activeTool}…"
        state.speaking -> "Speaking…"
        state.turnState == TurnState.Transcribing -> "Transcribing…"
        state.turnState == TurnState.Thinking -> "Thinking…"
        state.muted -> "Muted"
        else -> "Listening…"
    }
