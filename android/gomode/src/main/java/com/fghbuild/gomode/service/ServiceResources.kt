// Discovers server-advertised model resources and joins their known texts for the model.
//
// MCP servers mark model resources with descriptor _meta["com.fghbuild.gomode/modelContext"]: true.
// Each server chooses its resource URIs, public shape, language, selected entities, and freshness
// meaning. Unmarked resources stay available only through explicit reads. The client transports
// complete text contents without parsing a domain schema or rewriting fields. A server that marks
// more than MAX_MODEL_RESOURCES resources, or repeats a URI, fails its monitoring plan.
//
// The model receives each resource's text verbatim, with no URI, status, or wrapper. Unread,
// failed, absent, and withdrawn resources contribute nothing. After setup, a refresh sends only the
// resources whose text changed; changes queued during a turn accumulate in order. Native voice
// context carries only the active server's texts, prefixed with a "label [server-id]:" line for
// catalog tools, after an "Active server:" line.
//
// MCP server instructions own domain interpretation and stay unchanged and scoped to their server,
// including instructions obtained through catalog tools. Clients add no behavioral prompt prose;
// voicegateway/policy owns the common policy.
package com.fghbuild.gomode.service

import com.fghbuild.mcp.sdk.v1.ResourceDescriptor
import com.fghbuild.mcp.sdk.v1.ResourcesReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class ServiceMonitoringPlan(
    val modelResources: List<ResourceDescriptor>,
    val notificationResourceURI: String? = null,
) {
    val resourceURIs: List<String>
        get() = modelResources.map { it.uri } + listOfNotNull(notificationResourceURI)
}

data class ServiceMonitoringSnapshot(
    // Null is unread; failure is a fault. Discovery owns absence and retirement.
    val resources: Map<String, Result<ServiceResourceRead?>?>,
) {
    // Known resource texts only; unread, failed, and absent resources contribute nothing.
    val voiceContext: String
        get() =
            modelContextText(
                resources.mapNotNull { (uri, read) ->
                    read?.getOrNull()?.let { runCatching { resourceText(it.result, uri) }.getOrNull() }
                },
            )
}

fun serviceMonitoringPlan(resources: List<ResourceDescriptor>): ServiceMonitoringPlan {
    val models =
        resources.filter { resource ->
            val mark = resource._meta?.get(MODEL_CONTEXT_META_KEY) as? JsonPrimitive
            mark != null && !mark.isString && mark.booleanOrNull == true
        }
    require(models.size <= MAX_MODEL_RESOURCES) { "Too many model context resources" }
    require(
        models.all { it.uri.isNotEmpty() && it.uri.length <= 1024 } &&
            models.map { it.uri }.distinct().size == models.size,
    ) { "Invalid model resource identities" }
    val alerts = resources.firstOrNull { it.uri == GOMODE_NOTIFICATIONS_RESOURCE_URI }
    require(alerts?.mimeType == null || alerts.mimeType == "application/json") { "Invalid notifications resource type" }
    return ServiceMonitoringPlan(modelResources = models, notificationResourceURI = alerts?.uri)
}

@Suppress("TooGenericExceptionCaught") // Model context is advisory; cancellation still propagates.
suspend fun readInitialServiceContext(client: ServiceResourceClient, sessionID: String? = null): String =
    try {
        val plan = serviceMonitoringPlan(client.listResources(sessionID))
        coroutineScope {
            ServiceMonitoringSnapshot(
                plan.modelResources
                    .map { resource ->
                        async {
                            val read =
                                try {
                                    Result.success(
                                        client
                                            .readResource(
                                                resource.uri,
                                                sessionID,
                                            ).also { resourceText(it.result, resource.uri) },
                                    )
                                } catch (
                                    error: CancellationException,
                                ) {
                                    throw error
                                } catch (error: Exception) {
                                    Result.failure(error)
                                }
                            resource.uri to read
                        }
                    }.awaitAll()
                    .toMap(),
            ).voiceContext
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ""
    }

internal fun resourceText(
    readResult: ResourcesReadResult,
    uri: String,
): String {
    val content =
        readResult.contents.orEmpty().firstOrNull { it.uri == uri }
            ?: throw IllegalArgumentException("resource read result is missing $uri")
    return content.text ?: throw IllegalArgumentException("resource $uri is missing text content")
}

internal const val GOMODE_NOTIFICATIONS_RESOURCE_URI = "gomode://notifications"
