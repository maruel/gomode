// Resolves the active service into gateway endpoints, authorization, MCP tools, and voice context.
package com.fghbuild.gomode.voice

import android.webkit.CookieManager
import com.caic.voicegateway.sdk.v1.ServiceAuthorization
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.service.VoiceTokenClient
import com.fghbuild.gomode.service.readAuthoritativeServiceContext
import com.fghbuild.gomode.service.readInitialServiceContext
import com.fghbuild.gomode.service.resolveServiceURL
import com.fghbuild.gomode.service.serviceOrigin
import com.fghbuild.mcp.sdk.v1.ToolDescriptor

/** VoiceSetupException reports a configuration or sign-in problem the user can act on. */
internal class VoiceSetupException(
    message: String,
) : Exception(message)

/** VoiceSetup is the resolved service, gateway, and MCP state for one voice session. */
internal data class VoiceSetup(
    val gatewayEndpointURL: String,
    val service: ServiceAuthorization?,
    val choiceID: String? = null,
    val tokenEndpointURL: String?,
    val gatewayHeaders: Map<String, String>,
    val mcpClient: McpClient,
    val tools: List<ToolDescriptor>,
    val systemInstruction: String,
    val serviceContextText: String,
)

/** Returns a standalone voice setup for offline or direct on-device engine execution. */
internal fun standaloneVoiceSetup(): VoiceSetup =
    VoiceSetup(
        gatewayEndpointURL = "",
        service = null,
        tokenEndpointURL = null,
        gatewayHeaders = emptyMap(),
        mcpClient = McpClient("", "2024-11-05", { null }),
        tools = emptyList(),
        systemInstruction = "You are Go Mode running locally on Pixel 8 Pro.",
        serviceContextText = "",
    )

/**
 * Resolves the active service for a voice session.
 *
 * Throws [VoiceSetupException] when the service cannot support voice, and lets
 * [VoiceMcpAuthChangedException] propagate when credentials change mid-setup.
 */
internal suspend fun prepareVoiceSetup(
    settingsRepository: SettingsRepository,
    settingsClient: ServiceSettingsClient,
    bearerTokenFor: (String) -> String?,
    voiceTokenClient: VoiceTokenClient = VoiceTokenClient(),
): VoiceSetup {
    val settings = settingsRepository.settings.value
    requireLoadedVoiceSettings(settings)
    if (settings.activeServiceURL.isBlank()) {
        throw VoiceSetupException("Service URL is not configured")
    }
    val serviceSettings = settingsClient.fetch(settings.activeServiceURL)
    val voiceGatewayURL = serviceSettings.webShell.voiceGateway.url
    if (voiceGatewayURL.isNullOrBlank()) {
        throw VoiceSetupException("Voice is not available for this service")
    }
    // Single active skill today. SKILL.md frontmatter activation across the
    // toolGroups catalog (progressive disclosure) is future work; see
    // gomode/docs/ANDROID_SHELL.md.
    val group =
        serviceSettings.webShell.toolGroups.firstOrNull()
            ?: throw VoiceSetupException("Voice is not available for this service")
    val mcpEndpointURL = resolveServiceURL(settings.activeServiceURL, group.endpoint)
    val gatewayEndpointURL = resolveServiceURL(settings.activeServiceURL, voiceGatewayURL)
    val externalGateway = serviceOrigin(gatewayEndpointURL) != serviceOrigin(settings.activeServiceURL)
    val tokenEndpoint = serviceSettings.webShell.voiceGateway.tokenEndpoint
    if (externalGateway && tokenEndpoint.isNullOrBlank()) {
        throw VoiceSetupException("External voice gateway requires a token endpoint")
    }
    val tokenEndpointURL =
        if (externalGateway) {
            resolveServiceURL(settings.activeServiceURL, requireNotNull(tokenEndpoint))
        } else {
            null
        }
    if (tokenEndpointURL != null &&
        serviceOrigin(tokenEndpointURL) != serviceOrigin(settings.activeServiceURL)
    ) {
        throw VoiceSetupException("Voice token endpoint must be hosted by the service")
    }
    if (group.authRequired && cookieFor(mcpEndpointURL).isNullOrBlank() &&
        bearerTokenFor(mcpEndpointURL).isNullOrBlank()
    ) {
        throw VoiceSetupException("Sign in to the hosted service before using voice")
    }
    if (!externalGateway && serviceSettings.webShell.voiceGateway.authRequired == true &&
        cookieFor(gatewayEndpointURL).isNullOrBlank() &&
        bearerTokenFor(gatewayEndpointURL).isNullOrBlank()
    ) {
        throw VoiceSetupException("Sign in to the hosted service before using voice")
    }

    val gatewayHeaders = serviceAuthHeaders(gatewayEndpointURL, bearerTokenFor)
    val choiceID =
        if (usesGatewayVoice(settings)) {
            discoverVoiceChoices(settings.activeServiceURL, serviceSettings, bearerTokenFor).selection(settings).id
        } else {
            null
        }
    ensureVoiceSettingsCurrent(settings, settingsRepository.settings.value)

    val mcpCredentials =
        VoiceMcpCredentials(
            cookieFor(mcpEndpointURL),
            bearerTokenFor(mcpEndpointURL),
            { cookieFor(mcpEndpointURL) },
            { bearerTokenFor(mcpEndpointURL) },
        )
    val client =
        McpClient(
            endpointURL = mcpEndpointURL,
            protocolVersion = group.protocolVersion,
            cookieProvider = mcpCredentials::cookieForRequest,
            bearerTokenProvider = mcpCredentials::bearerForRequest,
        )
    val systemInstruction = client.serverInstructions()
    val tools = client.listTools()
    // Android owns this captured session baseline; see the canonical contract in
    // gomode/docs/ANDROID_SHELL.md. A reconnect repeats the read.
    val serviceContextText =
        if (settings.voiceMode == VoiceMode.DEVICE && settings.onDeviceEngine == OnDeviceEngine.LOCAL_LLM) {
            readAuthoritativeServiceContext(client)
        } else {
            readInitialServiceContext(client)
        }
    val service = tokenEndpointURL?.let { voiceTokenClient.fetch(it, cookieFor(it), bearerTokenFor(it)) }
    ensureVoiceSettingsCurrent(settings, settingsRepository.settings.value)
    if (gatewayHeaders != serviceAuthHeaders(gatewayEndpointURL, bearerTokenFor)) {
        throw VoiceSetupException("Service authentication changed. Try connecting again.")
    }
    return VoiceSetup(
        gatewayEndpointURL = gatewayEndpointURL,
        choiceID = choiceID,
        service = service,
        tokenEndpointURL = tokenEndpointURL,
        gatewayHeaders = gatewayHeaders,
        mcpClient = client,
        tools = tools,
        systemInstruction = systemInstruction,
        serviceContextText = serviceContextText,
    )
}

internal fun cookieFor(url: String): String? = CookieManager.getInstance().getCookie(url)

internal fun serviceAuthHeaders(
    url: String,
    bearerTokenFor: (String) -> String?,
): Map<String, String> =
    buildMap {
        cookieFor(url)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        bearerTokenFor(url)?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
    }

// Setup owns a snapshot: later settings must not change the provider of an in-flight attempt.
internal fun ensureVoiceSettingsCurrent(
    captured: SettingsState,
    current: SettingsState,
) {
    if (captured.activeServiceId != current.activeServiceId ||
        captured.activeServiceURL != current.activeServiceURL || captured.voiceMode != current.voiceMode ||
        captured.onDeviceEngine != current.onDeviceEngine || captured.voiceChoices != current.voiceChoices
    ) {
        throw VoiceSetupException("Voice settings changed. Try connecting again.")
    }
}
