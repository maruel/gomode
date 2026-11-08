// Full-width bottom shell panel composable: settings, voice controls, status, and transcript display.
package com.fghbuild.gomode.voice

import android.content.res.Configuration
import android.media.AudioDeviceInfo
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Psychology
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.fghbuild.voicegateway.sdk.v2.TokenUsage
import com.fghbuild.voicegateway.sdk.v2.TurnState
import kotlinx.coroutines.launch

private const val PULSE_MIN_ALPHA = 0.5f
private const val PULSE_MAX_ALPHA = 1.0f
private const val PULSE_DURATION_MS = 1000
private const val SERVICE_RECONNECT_REASON_LIMIT = 200
private val CloseTargetSize = 32.dp
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
    showUsageStats: Boolean = false,
) {
    val contentInset =
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) 8.dp else 24.dp
    val showUsage =
        voiceState.connected || voiceState.usage != null ||
            (voiceState.transcript.isNotEmpty() || voiceState.transcriptDraft != null)
    val appControls: @Composable () -> Unit = {
        AppControls(onOpenSettings, onCycleApp, canCycleApps)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
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
                contentInset,
            )
            microphoneRecovery?.let { recovery ->
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = contentInset).testTag("gomode-microphone-recovery"),
                ) {
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = contentInset, vertical = 8.dp),
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
                                .padding(horizontal = contentInset, vertical = 8.dp)
                                .testTag("gomode-voice-error-message"),
                    )
                }

                voiceState.connectStatus != null -> {
                    ConnectingStatus(voiceState.connectStatus, contentInset)
                }
            }
            if (voiceState.connected || (voiceState.transcript.isNotEmpty() || voiceState.transcriptDraft != null) ||
                voiceState.toolHistory.isNotEmpty()
            ) {
                ConversationPanel(voiceState, showUsageStats, contentInset)
            }
            if (showUsage && showUsageStats) {
                UsagePanel(voiceState.usage)
            }
        }
    }
}

@Composable
private fun UsagePanel(usage: VoiceUsage?) {
    val style = MaterialTheme.typography.labelSmall
    val model =
        listOfNotNull(usage?.latest?.provider, usage?.latest?.model)
            .filter(String::isNotBlank)
            .joinToString(" · ")
    val totals = usage?.totals
    val reported =
        totals != null &&
            listOfNotNull(
                totals.inputTokens,
                totals.outputTokens,
                totals.cachedTokens,
                totals.reasoningTokens,
            ).isNotEmpty()
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .testTag("gomode-voice-usage")
            .heightIn(max = 180.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        VoiceTextFlow(
            text =
                androidx.compose.ui.text
                    .AnnotatedString(
                        if (reported) {
                            model
                        } else {
                            listOf(
                                model,
                                "Not reported",
                            ).filter(String::isNotEmpty).joinToString(" · ")
                        },
                    ),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            exclusion = null,
            modifier = Modifier.fillMaxWidth(),
            metrics = {
                if (reported && totals != null) TokenMetrics(totals)
                if (usage?.latest?.contextTokens != null || usage?.latest?.contextWindow != null) {
                    val context = "${formatUsageCount(
                        usage?.latest?.contextTokens,
                    )} / ${formatUsageCount(usage?.latest?.contextWindow)}"
                    UsageMetric("Context tokens: $context", context, Icons.Default.DataUsage)
                }
            },
        )
    }
}

private fun formatUsageCount(value: Long?): String =
    value?.let {
        java.text.NumberFormat
            .getIntegerInstance()
            .format(it)
    } ?: "Not reported"

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
        modifier = Modifier.size(48.dp).testTag("gomode-web-open-settings"),
    ) {
        Box(Modifier.fillMaxSize().padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                modifier = Modifier.size(24.dp),
            )
        }
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
    contentInset: Dp,
) {
    val active = voiceState.connected || voiceState.connectStatus != null
    Row(
        // The settings glyph starts 8dp inside its bounded 48dp touch target.
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = contentInset - 8.dp, end = contentInset, top = 4.dp, bottom = 4.dp)
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
private fun ConnectingStatus(
    status: String,
    contentInset: Dp,
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
    Text(
        text = status,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = contentInset, vertical = 8.dp).alpha(alpha),
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
private fun VoiceCloseButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val viewConfiguration = LocalViewConfiguration.current
    val compactConfiguration =
        remember(viewConfiguration) {
            // Keep implicit touch expansion within the compact close target.
            object : ViewConfiguration by viewConfiguration {
                override val minimumTouchTargetSize = DpSize(CloseTargetSize, CloseTargetSize)
            }
        }
    val closeIconSize =
        with(LocalDensity.current) {
            MaterialTheme.typography.labelSmall.fontSize
                .toDp() * 1.25f
        }
    CompositionLocalProvider(LocalViewConfiguration provides compactConfiguration) {
        IconButton(
            onClick = onClick,
            modifier = modifier.size(CloseTargetSize),
        ) {
            Box(Modifier.fillMaxSize().padding(top = 2.dp), contentAlignment = Alignment.TopCenter) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(closeIconSize),
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
    val modifier = Modifier.focusRequester(focusRequester).testTag("gomode-voice-transcript-toggle")
    if (expanded) {
        VoiceCloseButton(onToggle, "Hide transcript", modifier)
    } else {
        IconButton(onClick = onToggle, modifier = modifier.size(48.dp)) {
            Icon(Icons.Default.Subtitles, contentDescription = "Show transcript", modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ConversationPanel(
    voiceState: VoiceState,
    showUsageStats: Boolean,
    contentInset: Dp,
) {
    var transcriptExpanded by rememberSaveable { mutableStateOf(true) }
    val transcriptFocus = remember { FocusRequester() }
    val logFocus = remember { FocusRequester() }
    var logFocused by remember { mutableStateOf(false) }
    val focusColor = MaterialTheme.colorScheme.primary
    var focusAfterToggle by remember { mutableStateOf(false) }
    val transcriptToggle: @Composable () -> Unit = {
        TranscriptToggle(transcriptExpanded, transcriptFocus) {
            focusAfterToggle = true
            transcriptExpanded = !transcriptExpanded
        }
    }
    LaunchedEffect(transcriptExpanded) {
        if (focusAfterToggle) {
            if ((voiceState.transcript.isNotEmpty() || voiceState.transcriptDraft != null)) {
                if (transcriptExpanded) logFocus.requestFocus() else transcriptFocus.requestFocus()
            }
            focusAfterToggle = false
        }
    }

    Column(
        modifier = Modifier.padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = contentInset).testTag("gomode-voice-activity"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (voiceState.connected && voiceState.error == null && voiceState.connectStatus == null &&
                voiceState.activeTool == null
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
                    modifier =
                        Modifier
                            .then(
                                if (voiceState.toolHistory.isEmpty()) Modifier.weight(1f) else Modifier,
                            ).semantics {
                                liveRegion =
                                    LiveRegionMode.Polite
                            },
                )
            }
            if (voiceState.toolHistory.isNotEmpty()) {
                ToolHistory(voiceState.toolHistory, voiceState.activeTool, Modifier.weight(1f))
            }
            if ((voiceState.transcript.isNotEmpty() || voiceState.transcriptDraft != null) &&
                !transcriptExpanded
            ) {
                transcriptToggle()
            }
        }

        if (transcriptExpanded && (voiceState.transcript.isNotEmpty() || voiceState.transcriptDraft != null)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = contentInset - 8.dp)
                        .padding(8.dp)
                        .testTag("gomode-voice-transcript-content"),
            ) {
                TranscriptLog(
                    entries = voiceState.transcript,
                    showUsageStats = showUsageStats,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                if (logFocused) {
                                    val stroke = 2.dp.toPx()
                                    drawRoundRect(
                                        color = focusColor,
                                        topLeft = Offset(-stroke, -stroke),
                                        size = Size(size.width + stroke * 2, size.height + stroke * 2),
                                        cornerRadius = CornerRadius(8.dp.toPx()),
                                        style = Stroke(stroke),
                                    )
                                }
                            }.onFocusChanged { logFocused = it.isFocused }
                            .semantics { contentDescription = "Transcript" }
                            .focusRequester(logFocus)
                            .focusable()
                            .testTag("gomode-voice-transcript"),
                    close = transcriptToggle,
                    draft = voiceState.transcriptDraft,
                )
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

/** Transcript heading for one entry; reasoning is labelled separately from spoken text. */
internal fun transcriptLabel(entry: TranscriptEntry): String =
    when {
        entry.kind == TranscriptKind.REASONING -> "Reasoning"
        entry.speaker == TranscriptSpeaker.USER -> "You"
        else -> "Assistant"
    }

@Composable
internal fun TranscriptLog(
    entries: List<TranscriptEntry>,
    modifier: Modifier = Modifier,
    showUsageStats: Boolean = false,
    close: (@Composable () -> Unit)? = null,
    draft: com.fghbuild.voicegateway.sdk.v2.TranscriptDraft? = null,
) {
    val listState = rememberLazyListState()
    val leading = if (close != null) minOf(4, entries.size) else 0
    val endIndex = entries.size - leading + (if (leading > 0) 1 else 0) + (if (draft != null) 1 else 0)
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
    LaunchedEffect(listState, endIndex, followingTail, entries.isEmpty(), draft?.text) {
        if (followingTail && (entries.isNotEmpty() || draft != null)) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.let { Triple(it.index, it.offset, it.size) }
            }.collect { listState.scrollToItem(endIndex) }
        }
    }
    LaunchedEffect(entries.isEmpty()) {
        if (entries.isEmpty()) followingTail = true
    }
    if (entries.isNotEmpty() || draft != null) {
        LazyColumn(
            state = listState,
            modifier = modifier.heightIn(max = TranscriptHeight).nestedScroll(manualScroll),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (leading > 0 && close != null) {
                item { FloatingTranscriptStart(entries.take(leading), showUsageStats, close) }
            }
            itemsIndexed(entries.drop(leading)) { _, entry -> TranscriptRow(entry, showUsageStats, null) }
            if (draft != null) {
                item {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "You: ${draft.text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).testTag("gomode-voice-transcript-draft"),
                        )
                        if (entries.isEmpty() && close != null) close()
                    }
                }
            }
            item { Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("gomode-voice-transcript-end")) }
        }
    }
}

// The initial entries share the float; the rest of the conversation remains lazy.
@Composable
private fun FloatingTranscriptStart(
    entries: List<TranscriptEntry>,
    showUsageStats: Boolean,
    close: @Composable () -> Unit,
) {
    SubcomposeLayout { constraints ->
        val button = subcompose("close", close).single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = 4.dp.roundToPx()
        var top = 0
        val positions = mutableListOf<Int>()
        val rows =
            entries.mapIndexed { index, entry ->
                val exclusion =
                    if (top <
                        button.height
                    ) {
                        TextExclusion(button.height - top, button.width + gap)
                    } else {
                        null
                    }
                val row =
                    subcompose(index) { TranscriptRow(entry, showUsageStats, exclusion) }
                        .single()
                        .measure(Constraints.fixedWidth(constraints.maxWidth))
                positions.add(top)
                top += row.height + gap
                row
            }
        layout(constraints.maxWidth, maxOf(button.height, top - gap)) {
            rows.forEachIndexed { index, row -> row.placeRelative(0, positions[index]) }
            button.placeRelative(constraints.maxWidth - button.width, 0)
        }
    }
}

// The viewport is one text line high; standard scroll semantics expose both axes.
@Composable
private fun ToolHistory(
    entries: List<ToolInvocation>,
    activeTool: String?,
    modifier: Modifier = Modifier,
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    var followingTail by remember { mutableStateOf(true) }
    var firstSequence by remember { mutableStateOf(entries.first().sequence) }
    val style = MaterialTheme.typography.bodySmall
    val rowHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    val rowPixels = with(LocalDensity.current) { rowHeight.roundToPx() }
    LaunchedEffect(vertical, rowPixels) {
        var previous = vertical.value
        snapshotFlow { vertical.value to vertical.isScrollInProgress }.collect { (value, scrolling) ->
            if (value != previous) {
                followingTail = value >= vertical.maxValue - 1
                previous = value
            }
            if (!scrolling && !followingTail) {
                val snapped = ((value + rowPixels / 2) / rowPixels) * rowPixels
                if (snapped != value) vertical.scrollTo(snapped)
                followingTail = vertical.value >= vertical.maxValue - 1
            }
        }
    }
    LaunchedEffect(entries, vertical.maxValue) {
        if (followingTail && vertical.maxValue != Int.MAX_VALUE) vertical.scrollTo(vertical.maxValue)
    }
    LaunchedEffect(entries) {
        val evicted = (entries.first().sequence - firstSequence).coerceAtLeast(0)
        firstSequence = entries.first().sequence
        if (followingTail) {
            horizontal.scrollTo(0)
        } else if (evicted > 0) {
            vertical.scrollTo((vertical.value - evicted * rowPixels).coerceAtLeast(0).toInt())
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            if (activeTool != null) Icons.Default.Build else Icons.Default.History,
            contentDescription = activeTool?.let { "Calling tool: $it…" },
            modifier = Modifier.size(rowHeight).semantics { liveRegion = LiveRegionMode.Polite },
            tint =
                if (activeTool !=
                    null
                ) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Column(
            Modifier
                .weight(1f)
                .height(rowHeight)
                .clipToBounds()
                .verticalScroll(vertical)
                .horizontalScroll(horizontal)
                .semantics { contentDescription = "Recent tool calls" }
                .testTag("gomode-voice-tool-history"),
        ) {
            entries.forEachIndexed { index, entry ->
                key(entry.sequence) {
                    Text(
                        entry.name,
                        style = style,
                        softWrap = false,
                        modifier = Modifier.height(rowHeight),
                        color =
                            if (index == entries.lastIndex &&
                                entry.name == activeTool
                            ) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptRow(
    entry: TranscriptEntry,
    showUsageStats: Boolean,
    exclusion: TextExclusion?,
) {
    val reasoning = entry.kind == TranscriptKind.REASONING
    val labelColor =
        when {
            reasoning -> MaterialTheme.colorScheme.onSurfaceVariant
            entry.speaker == TranscriptSpeaker.USER -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.secondary
        }
    val text =
        buildAnnotatedString {
            withStyle(SpanStyle(color = labelColor, fontWeight = FontWeight.SemiBold)) {
                append("${transcriptLabel(entry)}: ")
            }
            append(entry.text)
        }
    val counts =
        entry.responseUsage.takeIf {
            showUsageStats && !reasoning && entry.speaker == TranscriptSpeaker.ASSISTANT
        }
    val assistant = !reasoning && entry.speaker == TranscriptSpeaker.ASSISTANT
    val inset = with(LocalDensity.current) { if (assistant) 1.dp.roundToPx() else 0 }
    val space = exclusion?.copy(height = exclusion.height - inset)
    val rowModifier =
        if (assistant) {
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(3.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp)
        } else {
            Modifier
        }
    VoiceTextFlow(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (reasoning) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        exclusion = space,
        modifier = rowModifier.fillMaxWidth(),
        metrics =
            if (counts != null) {
                { TokenMetrics(counts) }
            } else {
                null
            },
    )
}

@Composable
private fun TokenMetrics(counts: TokenUsage) {
    ResponseMetric("Input", counts.inputTokens, Icons.Default.ArrowUpward)
    ResponseMetric("Output", counts.outputTokens, Icons.Default.ArrowDownward)
    if (counts.cachedTokens != null || counts.reasoningTokens != null) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            counts.cachedTokens?.let { ResponseMetric("Cached input", it, Icons.Default.Cached) }
            counts.reasoningTokens?.let { ResponseMetric("Reasoning", it, Icons.Default.Psychology) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResponseMetric(
    label: String,
    value: Long?,
    icon: ImageVector,
) {
    UsageMetric("$label tokens: ${formatUsageCount(value)}", value?.let { formatUsageCount(it) } ?: "—", icon)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageMetric(
    description: String,
    value: String,
    icon: ImageVector,
) {
    val style = MaterialTheme.typography.labelSmall
    val iconSize = with(LocalDensity.current) { style.fontSize.toDp() * 1.25f }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { RichTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = style.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
