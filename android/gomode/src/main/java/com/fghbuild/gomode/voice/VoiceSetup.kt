// Resolves the active service into gateway endpoints, authorization, MCP tools, and voice context.
package com.fghbuild.gomode.voice

import android.webkit.CookieManager
import com.caic.voicegateway.sdk.v1.ServiceAuthorization
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.service.VoiceTokenClient
import com.fghbuild.gomode.service.readInitialServiceContext
import com.fghbuild.gomode.service.resolveServiceURL
import com.fghbuild.gomode.service.serviceCatalogTools
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
    val voiceName: String = "",
    val tokenEndpointURL: String?,
    val gatewayHeaders: Map<String, String>,
    val mcpClient: McpClient?,
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
    pinnedVoice: GatewayVoiceSelection? = null,
    capturedSettings: SettingsState? = null,
    sharedCatalog: ServiceCatalog? = null,
    ensureCurrent: () -> Unit = {},
): VoiceSetup {
    val settings = capturedSettings ?: settingsRepository.settings.value

    fun requireCurrent() {
        if (capturedSettings == null) ensureVoiceSettingsCurrent(settings, settingsRepository.settings.value)
        ensureCurrent()
    }
    requireCurrent()
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
    val group = serviceSettings.webShell.toolGroups.firstOrNull()
    if (group == null && sharedCatalog == null) throw VoiceSetupException("Voice is not available for this service")
    val mcpEndpointURL = group?.let { resolveServiceURL(settings.activeServiceURL, it.endpoint) }
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
    if (sharedCatalog == null && group?.authRequired == true && mcpEndpointURL != null &&
        cookieFor(mcpEndpointURL).isNullOrBlank() && bearerTokenFor(mcpEndpointURL).isNullOrBlank()
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
    val selection =
        if (usesGatewayVoice(settings)) {
            discoverVoiceChoices(
                settings.activeServiceURL,
                serviceSettings,
                bearerTokenFor,
            ).resolve(settings, pinnedVoice)
        } else {
            null
        }
    requireCurrent()

    val participant =
        if (sharedCatalog ==
            null
        ) {
            prepareServiceMcp(settings.activeServiceURL, serviceSettings, bearerTokenFor)
        } else {
            null
        }
    val client = participant?.client
    val systemInstruction =
        participant?.instructions ?: "You are Go Mode, one conversation across the user's saved servers. " +
            "Use the catalog to discover scoped instructions and tools. Server selection changes focus only."
    val tools = (participant?.tools ?: serviceCatalogTools) + nativeVoiceTools(settings)
    val serviceContextText =
        if (sharedCatalog != null) {
            sharedCatalog.contextText()
        } else {
            readInitialServiceContext(requireNotNull(client))
        }
    val service = tokenEndpointURL?.let { voiceTokenClient.fetch(it, cookieFor(it), bearerTokenFor(it)) }
    requireCurrent()
    if (gatewayHeaders != serviceAuthHeaders(gatewayEndpointURL, bearerTokenFor)) {
        throw VoiceSetupException("Service authentication changed. Try connecting again.")
    }
    return VoiceSetup(
        gatewayEndpointURL = gatewayEndpointURL,
        choiceID = selection?.choiceID,
        voiceName = selection?.voiceName.orEmpty(),
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
        captured.onDeviceEngine != current.onDeviceEngine || captured.voiceChoices != current.voiceChoices ||
        captured.speakingVoices != current.speakingVoices
    ) {
        throw VoiceSetupException("Voice settings changed. Try connecting again.")
    }
}
