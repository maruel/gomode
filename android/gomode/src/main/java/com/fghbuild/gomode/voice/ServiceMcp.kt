// Prepares a service's MCP tools independently of its voice gateway configuration.
package com.fghbuild.gomode.voice

import com.fghbuild.gomode.sdk.v1.Settings
import com.fghbuild.gomode.service.resolveServiceURL
import com.fghbuild.mcp.sdk.v1.Capabilities
import com.fghbuild.mcp.sdk.v1.ToolDescriptor

internal data class ServiceMcp(
    val client: McpClient,
    val instructions: String,
    val capabilities: Capabilities,
    val tools: List<ToolDescriptor>,
    val authorization: ServiceMcpAuthorization,
)

// Retained across catalog preparation so a conversation checks the authority it actually admitted.
internal class ServiceMcpAuthorization(
    val endpointURL: String,
    private val credentials: VoiceMcpCredentials,
) {
    fun isCurrent(): Boolean = credentials.isCurrent()
}

internal suspend fun prepareServiceMcp(
    serviceURL: String,
    settings: Settings,
    bearerTokenFor: (String) -> String?,
): ServiceMcp {
    // First-group activation is shared with voice setup; progressive skill activation is separate work.
    val group =
        settings.webShell.toolGroups.firstOrNull()
            ?: throw VoiceSetupException("Service does not provide MCP tools")
    val endpoint = resolveServiceURL(serviceURL, group.endpoint)
    if (group.authRequired && cookieFor(endpoint).isNullOrBlank() && bearerTokenFor(endpoint).isNullOrBlank()) {
        throw VoiceSetupException("Sign in to the hosted service before using voice")
    }
    val credentials =
        VoiceMcpCredentials(
            cookieFor(endpoint),
            bearerTokenFor(endpoint),
            { cookieFor(endpoint) },
            { bearerTokenFor(endpoint) },
        )
    val client =
        McpClient(endpoint, group.protocolVersion, credentials::cookieForRequest, credentials::bearerForRequest)
    val discovery = client.serverDiscover()
    val tools = client.listTools()
    require(tools.none { it.name in nativeVoiceToolNames }) {
        "MCP tool conflicts with a reserved native voice tool."
    }
    return ServiceMcp(
        client,
        discovery.instructions.orEmpty(),
        discovery.capabilities,
        tools,
        ServiceMcpAuthorization(endpoint, credentials),
    )
}
