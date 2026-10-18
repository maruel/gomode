// Discovers gateway voice choices and resolves an explicitly pinned, transport-compatible selection.
package com.fghbuild.gomode.voice

import com.caic.voicegateway.sdk.v1.ApiClient
import com.caic.voicegateway.sdk.v1.VoiceChoice
import com.caic.voicegateway.sdk.v1.VoiceChoicesResp
import com.caic.voicegateway.sdk.v1.VoiceTransport
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.sdk.v1.Settings
import com.fghbuild.gomode.service.credentialedHTTPClient
import com.fghbuild.gomode.service.resolveServiceURL
import com.fghbuild.gomode.service.serviceOrigin

internal data class GatewayVoiceChoices(
    val gatewayURL: String,
    val catalog: VoiceChoicesResp,
) {
    fun compatible(settings: SettingsState): List<VoiceChoice> =
        catalog.choices.filter { voiceTransport(settings) in it.transports }

    fun selection(settings: SettingsState): VoiceChoice {
        requireLoadedVoiceSettings(settings)
        val saved = settings.voiceChoices[gatewayURL]
        if (saved != null && catalog.choices.none { it.id == saved }) {
            throw VoiceSetupException("Saved voice choice is unavailable. Select a voice choice in Settings.")
        }
        val compatible = compatible(settings)
        return compatible.firstOrNull { it.id == saved }
            ?: compatible.firstOrNull { it.id == catalog.defaultChoiceID }
            ?: compatible.firstOrNull()
            ?: throw VoiceSetupException("No gateway voice choices support this voice mode")
    }
}

internal fun usesGatewayVoice(settings: SettingsState): Boolean =
    settings.voiceMode == VoiceMode.CLOUD || settings.onDeviceEngine == OnDeviceEngine.REMOTE_GATEWAY

private fun voiceTransport(settings: SettingsState): VoiceTransport =
    if (settings.voiceMode == VoiceMode.CLOUD) VoiceTransport.WebRTC else VoiceTransport.Text

internal suspend fun discoverVoiceChoices(
    serviceURL: String,
    serviceSettings: Settings,
    bearerTokenFor: (String) -> String?,
): GatewayVoiceChoices {
    val advertised = serviceSettings.webShell.voiceGateway.url
    if (advertised.isNullOrBlank()) throw VoiceSetupException("Voice is not available for this service")
    val gatewayURL = resolveServiceURL(serviceURL, advertised)
    // External gateway metadata is public: never forward service credentials to it.
    val headers =
        if (serviceOrigin(serviceURL) == serviceOrigin(gatewayURL)) {
            serviceAuthHeaders(gatewayURL, bearerTokenFor)
        } else {
            emptyMap()
        }
    val catalog = ApiClient(gatewayURL, httpClient = credentialedHTTPClient).voiceChoices(headers)
    if (catalog.choices.any { it.id.isBlank() || it.label.isBlank() } ||
        catalog.choices
            .map { it.id }
            .toSet()
            .size != catalog.choices.size ||
        catalog.choices.none { it.id == catalog.defaultChoiceID }
    ) {
        throw VoiceSetupException("Gateway returned invalid voice choices")
    }
    return GatewayVoiceChoices(gatewayURL, catalog)
}

internal fun requireLoadedVoiceSettings(settings: SettingsState) {
    when (val state = settings.loadState) {
        SettingsLoadState.Loading -> throw VoiceSetupException("Saved settings are still loading")
        SettingsLoadState.Ready -> Unit
        is SettingsLoadState.Failed -> throw VoiceSetupException(state.message)
    }
}
