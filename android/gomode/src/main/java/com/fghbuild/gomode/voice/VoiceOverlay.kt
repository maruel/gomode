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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.caic.voicegateway.sdk.v1.TurnState

private const val PULSE_MIN_ALPHA = 0.5f
private const val PULSE_MAX_ALPHA = 1.0f
private const val PULSE_DURATION_MS = 1000
private const val BAR_COUNT = 3
private const val BAR_MIN_HEIGHT = 4f
private const val BAR_MAX_HEIGHT = 20f
private const val BAR_CONTAINER_SIZE = 24
private val TranscriptHeight = 220.dp

@Composable
fun VoicePanel(
    voiceState: VoiceState,
    voiceEnabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectMicrophoneMode: (MicrophoneMode) -> Unit,
    onSelectDevice: (Int) -> Unit,
    onClearTranscript: () -> Unit,
    onOpenSettings: () -> Unit,
    serviceStatusText: String?,
    onCycleApp: () -> Unit,
    canCycleApps: Boolean,
    modifier: Modifier = Modifier,
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
            when {
                voiceState.error != null -> {
                    ErrorPanel(voiceState.error, voiceEnabled, onConnect, onDisconnect, appControls)
                }

                !voiceEnabled -> {
                    IdlePanel(
                        onConnect = onConnect,
                        appControls = appControls,
                        voiceEnabled = false,
                    )
                }

                voiceState.connectStatus != null -> {
                    ConnectingPanel(voiceState.connectStatus, onDisconnect, appControls)
                }

                voiceState.connected -> {
                    ActivePanel(
                        voiceState = voiceState,
                        onDisconnect = onDisconnect,
                        onSelectMicrophoneMode = onSelectMicrophoneMode,
                        onSelectDevice = onSelectDevice,
                        onClearTranscript = onClearTranscript,
                        appControls = appControls,
                    )
                }

                else -> {
                    IdlePanel(
                        onConnect = onConnect,
                        appControls = appControls,
                    )
                }
            }
            ServiceStatusLabel(serviceStatusText)
        }
    }
}

@Composable
private fun ServiceStatusLabel(text: String?) {
    if (text == null) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .testTag("gomode-service-status"),
    )
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
                Icon(Icons.Default.SwapHoriz, contentDescription = "Next app")
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
private fun IdlePanel(
    onConnect: () -> Unit,
    appControls: @Composable () -> Unit,
    voiceEnabled: Boolean = true,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        appControls()
        val disabledAlpha = 0.38f
        val iconAlpha = if (voiceEnabled) 1f else disabledAlpha
        IconButton(
            onClick = onConnect,
            enabled = voiceEnabled,
            modifier =
                Modifier
                    .size(36.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = iconAlpha),
                        shape = CircleShape,
                    ).testTag(if (voiceEnabled) "gomode-voice-connect" else "gomode-voice-disabled"),
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = if (voiceEnabled) "Connect voice assistant" else "Voice unavailable",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = iconAlpha),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ConnectingPanel(
    status: String,
    onDisconnect: () -> Unit,
    appControls: @Composable () -> Unit,
) {
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
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 12.dp)
                .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        appControls()
        Icon(Icons.Default.Mic, contentDescription = null)
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDisconnect) {
            Icon(Icons.Default.Close, contentDescription = "Cancel")
        }
    }
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
private fun ActivePanel(
    voiceState: VoiceState,
    onDisconnect: () -> Unit,
    onSelectMicrophoneMode: (MicrophoneMode) -> Unit,
    onSelectDevice: (Int) -> Unit,
    onClearTranscript: () -> Unit,
    appControls: @Composable () -> Unit,
) {
    var transcriptExpanded by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier.padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MicrophoneModeButton(voiceState.microphoneMode, voiceState.speaking, onSelectMicrophoneMode)
            appControls()
            MicLevelIndicator(micLevel = voiceState.micLevel)

            val activeTool = voiceState.activeTool
            Text(
                text = voiceStatusText(voiceState),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (activeTool != null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = onDisconnect) {
                Icon(
                    Icons.Default.CallEnd,
                    contentDescription = "End voice",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (voiceState.availableDevices.isNotEmpty()) {
            AudioDevicePicker(
                devices = voiceState.availableDevices,
                selectedDeviceId = voiceState.selectedDeviceId,
                switching = voiceState.audioSwitching,
                onSelect = onSelectDevice,
            )
        }
        voiceState.audioError?.let { message ->
            Text(text = message, color = MaterialTheme.colorScheme.error)
        }

        if (voiceState.transcript.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Transcript",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClearTranscript, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Clear transcript",
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(
                    onClick = { transcriptExpanded = !transcriptExpanded },
                    modifier =
                        Modifier
                            .size(32.dp)
                            .testTag("gomode-voice-transcript-toggle"),
                ) {
                    Icon(
                        imageVector = if (transcriptExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (transcriptExpanded) "Hide transcript" else "Show transcript",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        val draft = voiceState.transcript.lastOrNull()?.takeIf { it.speaker == TranscriptSpeaker.USER && !it.final }
        if (!transcriptExpanded && (voiceState.listening || voiceState.muted) && draft != null) {
            Text(
                text = "You: ${draft.text}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().testTag("gomode-voice-recognition-draft"),
            )
        }
        if (transcriptExpanded) {
            TranscriptLog(
                entries = voiceState.transcript,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("gomode-voice-transcript"),
            )
        }
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    voiceEnabled: Boolean,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    appControls: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().testTag("gomode-voice-error-message"),
        )
        Row(
            modifier = Modifier.fillMaxWidth().testTag("gomode-voice-error-actions"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            appControls()
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onReconnect, enabled = voiceEnabled) {
                Icon(Icons.Default.Refresh, contentDescription = "Reconnect")
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioDevicePicker(
    devices: List<AudioDevice>,
    selectedDeviceId: Int?,
    switching: Boolean,
    onSelect: (Int) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        else -> Icons.Default.VolumeUp
    }

@Composable
private fun TranscriptLog(
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
            modifier = modifier.height(TranscriptHeight).nestedScroll(manualScroll),
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
        modifier = Modifier.size(BAR_CONTAINER_SIZE.dp),
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
