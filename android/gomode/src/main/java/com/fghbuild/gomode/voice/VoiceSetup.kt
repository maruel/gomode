// Resolves the active service into gateway endpoints, authorization, MCP tools, and voice context.
package com.fghbuild.gomode.voice

import android.webkit.CookieManager
import com.caic.voicegateway.sdk.v1.ServiceAuthorization
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.service.VoiceTokenClient
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
    val tokenEndpointURL: String?,
    val gatewayHeaders: Map<String, String>,
    val mcpClient: McpClient,
    val tools: List<ToolDescriptor>,
    val systemInstruction: String,
    val serviceContextText: String,
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
    val serviceContextText = readInitialServiceContext(client)
    val service = tokenEndpointURL?.let { voiceTokenClient.fetch(it, cookieFor(it), bearerTokenFor(it)) }
    return VoiceSetup(
        gatewayEndpointURL = gatewayEndpointURL,
        service = service,
        tokenEndpointURL = tokenEndpointURL,
        gatewayHeaders = serviceAuthHeaders(gatewayEndpointURL, bearerTokenFor),
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
