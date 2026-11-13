// Service discovery client, URL helpers, and compatibility checks for the Go Mode root settings document.
//
// The shell loads the service URL in the WebView first and fetches /.well-known/gomode.json off the
// WebView's critical path. A manifest is unvalidated until it decodes, compatible when its versions
// pass and its skill catalog and gateway metadata decode, and incompatible otherwise. Only a
// compatible manifest enables MCP-backed native features; an incompatible one disables them with a
// visible state while the page keeps loading. The fetch retries after page load, app resume,
// network recovery, and user actions that need native features. Network failures, 5xx, 408, and
// 429 also retry with backoff up to 30 s. Login never depends on discovery.
//
// The shell and the manifest must both use BRIDGE_VERSION; validation rejects other versions before
// native voice starts.
package com.fghbuild.gomode.service

import com.fghbuild.gomode.sdk.v1.ApiClient
import com.fghbuild.gomode.sdk.v1.ApiException
import com.fghbuild.gomode.sdk.v1.Settings
import com.fghbuild.gomode.sdk.v1.ToolGroup
import com.fghbuild.gomode.sdk.v1.VoiceGatewaySettings
import java.net.URI

// Discovery follows redirects; sharing the base client's dispatcher and pool keeps retries from
// each starting an HTTP thread pool.
private val discoveryHTTPClient =
    credentialedHTTPClient
        .newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

class ServiceSettingsClient {
    suspend fun fetch(baseURL: String): Settings {
        try {
            return ApiClient(serviceOrigin(baseURL), httpClient = discoveryHTTPClient).getSettings()
        } catch (e: ApiException) {
            throw ServiceSettingsException("Service settings request failed: HTTP ${e.statusCode}", e.statusCode, e)
        }
    }
}

class ServiceSettingsException(
    message: String,
    val statusCode: Int,
    cause: Throwable,
) : Exception(message, cause) {
    val isTransient: Boolean get() = isTransientHTTPStatus(statusCode)
}

fun serviceOrigin(url: String): String {
    val uri = URI(url.trim())
    require(!uri.scheme.isNullOrBlank()) { "Service URL must include a scheme" }
    require(!uri.rawAuthority.isNullOrBlank()) { "Service URL must include a host" }
    return "${uri.scheme}://${uri.rawAuthority}"
}

fun resolveServiceURL(
    baseURL: String,
    advertisedURL: String,
): String {
    val baseOrigin = URI(serviceOrigin(baseURL).trimEnd('/') + "/")
    return baseOrigin.resolve(advertisedURL).toString().trimEnd('/')
}

object GoModeShellContract {
    const val API_VERSION = 1
    const val BRIDGE_VERSION = 3
}

fun Settings.compatibilityError(): String? =
    validationError() ?: when {
        apiVersion != GoModeShellContract.API_VERSION -> {
            "Unsupported Go Mode service API version $apiVersion. This app supports ${GoModeShellContract.API_VERSION}."
        }

        webShell.bridgeVersion != GoModeShellContract.BRIDGE_VERSION -> {
            "Service requires bridge version ${webShell.bridgeVersion}. " +
                "This app provides ${GoModeShellContract.BRIDGE_VERSION}."
        }

        else -> {
            null
        }
    }

private fun Settings.validationError(): String? {
    if (service.isBlank()) return "Service settings field service is required."
    if (apiVersion <= 0) return "Service settings field apiVersion must be positive."
    if (webShell.bridgeVersion <= 0) return "Service settings field webShell.bridgeVersion must be positive."
    for ((index, group) in webShell.toolGroups.orEmpty().withIndex()) {
        group.validationError(index)?.let { return it }
    }
    return webShell.voiceGateway.validationError()
}

private fun ToolGroup.validationError(index: Int): String? {
    val prefix = "webShell.toolGroups[$index]"
    val skillURL = skillUrl
    return when {
        name.isBlank() -> {
            "Service settings field $prefix.name is required."
        }

        endpoint.isBlank() -> {
            "Service settings field $prefix.endpoint is required."
        }

        !isValidURLReference(endpoint) -> {
            "Service settings field $prefix.endpoint must be an absolute URL or absolute path."
        }

        protocolVersion.isBlank() -> {
            "Service settings field $prefix.protocolVersion is required."
        }

        !skillURL.isNullOrBlank() && !isValidURLReference(skillURL) -> {
            "Service settings field $prefix.skillUrl must be an absolute URL or absolute path."
        }

        else -> {
            null
        }
    }
}

private fun VoiceGatewaySettings.validationError(): String? {
    val gatewayURL = url
    val tokenURL = tokenEndpoint
    return when {
        required && gatewayURL.isNullOrBlank() -> {
            "Service settings field webShell.voiceGateway.url is required when voice is required."
        }

        authRequired == true && gatewayURL.isNullOrBlank() -> {
            "Service settings field webShell.voiceGateway.url is required when voice authentication is required."
        }

        !gatewayURL.isNullOrBlank() && !isValidURLReference(gatewayURL) -> {
            "Service settings field webShell.voiceGateway.url must be an absolute URL or absolute path."
        }

        !tokenURL.isNullOrBlank() && !isValidURLReference(tokenURL) -> {
            "Service settings field webShell.voiceGateway.tokenEndpoint must be an absolute URL or absolute path."
        }

        else -> {
            null
        }
    }
}

private fun isValidURLReference(value: String): Boolean {
    if (value.trim() != value) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    if (uri.isAbsolute) {
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.rawAuthority.isNullOrBlank()
    }
    return value.startsWith("/") && !value.startsWith("//")
}
