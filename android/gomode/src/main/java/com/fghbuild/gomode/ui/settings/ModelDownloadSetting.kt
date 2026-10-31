// Shows local-model installation, resumable progress, and explicit cancellation in native settings.
package com.fghbuild.gomode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.R
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.voice.ModelDownloadFormat
import com.fghbuild.gomode.voice.ModelDownloadState
import com.fghbuild.gomode.voice.ModelTransferStage
import com.fghbuild.gomode.voice.artifact

@Composable
internal fun ModelDownloadSetting(
    model: LocalLlmModel,
    enabled: Boolean,
) {
    val downloads = (LocalContext.current.applicationContext as GoModeApplication).modelDownloads
    val states by downloads.states.collectAsStateWithLifecycle()
    ModelDownloadControls(
        model,
        checkNotNull(states[model]),
        enabled,
        onDownload = { downloads.download(model) },
        onCancel = { downloads.cancel(model) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelDownloadControls(
    model: LocalLlmModel,
    state: ModelDownloadState,
    enabled: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { ModelDownloadFormat(locale) }
    val size = model.artifact()?.size
    val sizeLabel = size?.let(format::bytes)
    val partial =
        when (state) {
            is ModelDownloadState.Missing -> state.partialBytes
            is ModelDownloadState.Failed -> state.partialBytes
            else -> 0L
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            if (sizeLabel == null) model.label else "${model.label} · $sizeLabel download",
            style = MaterialTheme.typography.bodyMedium,
        )
        val status =
            when (state) {
                ModelDownloadState.Checking -> {
                    "Checking installed model…"
                }

                ModelDownloadState.Unavailable -> {
                    "Download not available yet"
                }

                is ModelDownloadState.Queued -> {
                    if (state.retrying) {
                        "Download interrupted · resuming automatically"
                    } else {
                        "Download queued · waiting to start"
                    }
                }

                ModelDownloadState.Cancelling -> {
                    "Cancelling download…"
                }

                ModelDownloadState.Ready -> {
                    "Installed on this device"
                }

                is ModelDownloadState.Missing -> {
                    if (partial > 0) {
                        "Interrupted download · ${format.bytes(partial)} saved"
                    } else {
                        "Not downloaded"
                    }
                }

                is ModelDownloadState.Failed -> {
                    state.message
                }

                is ModelDownloadState.Downloading -> {
                    val total = checkNotNull(size)
                    val percent = format.percent(state.progress.bytes, total)
                    if (state.progress.stage == ModelTransferStage.CHECKING) {
                        "Checking saved download · $percent"
                    } else {
                        val received = format.bytes(state.progress.bytes)
                        "Downloading · $percent · $received of $sizeLabel"
                    }
                }
            }
        Text(
            status,
            color =
                if (state is ModelDownloadState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            modifier = Modifier.testTag("gomode-model-download-status"),
        )
        if (state is ModelDownloadState.Downloading) {
            LinearProgressIndicator(
                progress = { (state.progress.bytes.toFloat() / checkNotNull(size)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().testTag("gomode-model-download-progress"),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state is ModelDownloadState.Missing || state is ModelDownloadState.Failed) {
                Button(onClick = onDownload, enabled = enabled, modifier = Modifier.testTag("gomode-model-download")) {
                    Text(
                        if (partial > 0) {
                            "Resume download"
                        } else if (state is ModelDownloadState.Failed) {
                            "Retry download"
                        } else {
                            "Download model"
                        },
                    )
                }
                if (partial > 0) {
                    TextButton(
                        onClick = onCancel,
                        enabled = enabled,
                        modifier = Modifier.testTag("gomode-model-download-cancel"),
                    ) {
                        Text("Discard download")
                    }
                }
            }
            if (state is ModelDownloadState.Downloading || state is ModelDownloadState.Queued ||
                state == ModelDownloadState.Cancelling
            ) {
                IconButton(
                    onClick = onCancel,
                    enabled = enabled && state != ModelDownloadState.Cancelling,
                    modifier = Modifier.testTag("gomode-model-download-cancel"),
                ) {
                    Icon(painterResource(R.drawable.ic_cancel), contentDescription = "Cancel download")
                }
            }
        }
    }
}
