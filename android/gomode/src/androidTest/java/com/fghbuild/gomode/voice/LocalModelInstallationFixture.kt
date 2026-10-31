// Supplies explicit model installation states for native voice lifecycle fixtures.
package com.fghbuild.gomode.voice

import com.fghbuild.gomode.data.LocalLlmModel
import kotlinx.coroutines.flow.MutableStateFlow

internal fun localModelInstallation(
    state: ModelDownloadState,
): MutableStateFlow<Map<LocalLlmModel, ModelDownloadState>> =
    MutableStateFlow(LocalLlmModel.entries.associateWith { state })
