// Discovers server-advertised model resources and bounds their independent opaque outcomes.
package com.fghbuild.gomode.service

import com.fghbuild.mcp.sdk.v1.ResourceDescriptor
import com.fghbuild.mcp.sdk.v1.ResourcesReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put

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
    val voiceContext: String
        get() {
            var remaining = MODEL_CONTEXT_BUDGET - 64
            var omitted = 0
            val sections = mutableListOf<String>()
            for ((uri, read) in resources) {
                val value = read?.getOrNull()
                val text =
                    when {
                        read == null -> modelResourceStatus(uri, "unread")
                        read.isFailure -> modelResourceStatus(uri, "error")
                        value == null -> modelResourceStatus(uri, "absent")
                        else -> modelResourceContext(uri, resourceText(value.result, uri), MODEL_RESOURCE_BUDGET)
                    }
                val section = if (text.length + 1 <= remaining) text else modelResourceStatus(uri, "omitted")
                if (section.length + 1 <= remaining) {
                    sections += section
                    remaining -= section.length + 1
                } else {
                    omitted++
                }
            }
            if (omitted > 0) {
                sections +=
                    kotlinx.serialization.json
                        .buildJsonObject { put("omittedResourceCount", omitted) }
                        .toString()
            }
            return sections.joinToString("\n")
        }
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
        "{\"status\":\"error\"}"
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

internal fun unreadServiceContext(resources: Set<String>?): String =
    resources?.joinToString("\n") { uri -> modelResourceStatus(uri, "unread") } ?: "{\"status\":\"unread\"}"
