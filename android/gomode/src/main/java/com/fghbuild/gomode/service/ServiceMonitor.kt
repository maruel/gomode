// Owns independent advertised model resources or background alerts with cancellable stream lifetimes.
package com.fghbuild.gomode.service

import com.fghbuild.gomode.sdk.v1.Settings
import com.fghbuild.mcp.sdk.v1.CacheScope
import com.fghbuild.mcp.sdk.v1.JSONPresence
import com.fghbuild.mcp.sdk.v1.JSONRPCNotification
import com.fghbuild.mcp.sdk.v1.NotificationMethod
import com.fghbuild.mcp.sdk.v1.ResourceContent
import com.fghbuild.mcp.sdk.v1.ResourceDescriptor
import com.fghbuild.mcp.sdk.v1.ResourcesCapability
import com.fghbuild.mcp.sdk.v1.ResourcesReadResult
import com.fghbuild.mcp.sdk.v1.ResultType
import com.fghbuild.mcp.sdk.v1.ServerDiscoverResult
import com.fghbuild.mcp.sdk.v1.SubscriptionFilter
import com.fghbuild.mcp.sdk.v1.SubscriptionsInitialStateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

interface ServiceResourceClient {
    suspend fun serverDiscover(sessionID: String? = null): ServerDiscoverResult

    suspend fun listResources(sessionID: String? = null): List<ResourceDescriptor>

    suspend fun readResource(
        uri: String,
        sessionID: String? = null,
    ): ServiceResourceRead

    fun listenSubscriptions(
        notifications: SubscriptionFilter,
        sessionID: String? = null,
    ): Flow<JSONRPCNotification>
}

data class ServiceNotification(
    val id: String,
    val title: String,
    val text: String,
    val itemId: String? = null,
    val url: String? = null,
    val expiresAt: Instant? = null,
)

data class ServiceMonitorState(
    val snapshot: ServiceMonitoringSnapshot? = null,
    // The actual successful discovery outcome, independent of read/stream progress.
    val resources: Set<String>? = null,
    val notifications: List<ServiceNotification> = emptyList(),
    val activeNotifications: List<ServiceNotification>? = null,
    // Why the monitor will retry, cleared by the next successful attempt. A planned stream end and
    // a transport failure both land here because neither is a permanent service failure.
    val retryReason: String? = null,
) {
    val voiceContext: String
        get() =
            if (retryReason != null) {
                resources?.joinToString("\n") { modelResourceStatus(it, "error") } ?: "{\"status\":\"error\"}"
            } else {
                snapshot?.voiceContext ?: unreadServiceContext(resources)
            }
}

enum class ServiceMonitorPurpose {
    BACKGROUND_ALERTS,
    CONVERSATION_CONTEXT,
}

class ServiceMonitor(
    private val scope: CoroutineScope,
    private val sessionID: String? = null,
    private val purpose: ServiceMonitorPurpose = ServiceMonitorPurpose.BACKGROUND_ALERTS,
    private val clientFactory: (endpointURL: String, protocolVersion: String) -> ServiceResourceClient,
) {
    private val _state = MutableStateFlow(ServiceMonitorState())
    val state: StateFlow<ServiceMonitorState> = _state.asStateFlow()

    private var job: Job? = null

    // Last verified ordering survives advisory faults; no content cache or second lifetime.
    private val contextOrdering = mutableMapOf<String, Pair<String, Long>>()
    internal val observedNotificationIDs = mutableSetOf<String>()

    fun start(
        serviceURL: String,
        settings: Settings,
    ) {
        job?.cancel()
        observedNotificationIDs.clear()
        contextOrdering.clear()
        _state.value = ServiceMonitorState()
        job =
            scope.launch {
                run(serviceURL, settings)
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        observedNotificationIDs.clear()
        _state.value = ServiceMonitorState()
    }

    @Suppress("TooGenericExceptionCaught") // Native monitoring must retry transient service and network failures.
    private suspend fun run(serviceURL: String, settings: Settings) {
        val group =
            settings.webShell.toolGroups
                .orEmpty()
                .firstOrNull() ?: run {
                _state.value = ServiceMonitorState()
                return
            }
        val endpointURL = resolveServiceURL(serviceURL, group.endpoint)
        val client = clientFactory(endpointURL, group.protocolVersion)
        var retryDelayMs = INITIAL_RETRY_DELAY_MS
        while (true) {
            try {
                when (val result = monitorOnce(client)) {
                    MonitorRunResult.Static,
                    -> {
                        return
                    }

                    MonitorRunResult.Reconfigure -> {
                        continue
                    }

                    is MonitorRunResult.Retry -> {
                        _state.value = ServiceMonitorState(retryReason = "MCP subscription stream ended")
                        retryDelayMs = awaitRetry(retryDelayMs, delivered = result.delivered)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ServiceMonitorState(retryReason = e.message ?: "Service monitoring failed")
                retryDelayMs = awaitRetry(retryDelayMs, delivered = false)
            }
        }
    }

    // Only a stream that delivered updates restarts the backoff. A server that accepts and closes
    // streams immediately keeps escalating, so a flapping endpoint cannot spin at the base delay.
    private suspend fun awaitRetry(
        previousDelayMs: Long,
        delivered: Boolean,
    ): Long {
        val delayMs = if (delivered) INITIAL_RETRY_DELAY_MS else previousDelayMs
        delay(delayMs)
        return nextRetryDelay(delayMs)
    }

    private suspend fun monitorOnce(client: ServiceResourceClient): MonitorRunResult =
        coroutineScope {
            val plan = monitoringPlan(client.listResources(sessionID))
            acceptDiscovery(plan)
            val capability = client.serverDiscover(sessionID).capabilities.resources
            val filter = subscriptionFilter(plan, capability)
            val reads = mutableMapOf<String, Job>()
            val dirty = mutableMapOf<String, Boolean>()
            val retryWakeups = mutableMapOf<String, CompletableDeferred<Unit>>()

            fun refresh(
                uri: String,
                publishEvents: Boolean,
            ) {
                dirty[uri] = dirty[uri] == true || publishEvents
                retryWakeups[uri]?.complete(Unit)
                if (reads[uri]?.isActive == true) return
                reads[uri] =
                    launch {
                        var retryDelayMs = INITIAL_RETRY_DELAY_MS
                        do {
                            val publish = dirty.remove(uri) == true
                            if (refreshResource(client, plan, uri, publish)) {
                                retryDelayMs = INITIAL_RETRY_DELAY_MS
                            } else {
                                dirty[uri] = dirty[uri] == true || publish
                                val wakeup = CompletableDeferred<Unit>()
                                retryWakeups[uri] = wakeup
                                try {
                                    withTimeoutOrNull(retryDelayMs) { wakeup.await() }
                                } finally {
                                    if (retryWakeups[uri] === wakeup) retryWakeups.remove(uri)
                                }
                                retryDelayMs = nextRetryDelay(retryDelayMs)
                            }
                        } while (dirty.containsKey(uri))
                    }
            }
            plan.resourceURIs.forEach { refresh(it, publishEvents = false) }
            if (filter == null) {
                reads.values.toList().joinAll()
                return@coroutineScope MonitorRunResult.Static
            }
            val initial = InitialStateWindow()
            var delivered = false
            var reconfigure = false
            try {
                client
                    .listenSubscriptions(filter, sessionID)
                    .takeWhile { notification ->
                        delivered = true
                        if (notification.method == NotificationMethod.ResourcesListChanged) {
                            val next = monitoringPlan(client.listResources(sessionID))
                            if (next != plan) {
                                reconfigure = true
                                return@takeWhile false
                            }
                            return@takeWhile true
                        }
                        val initialState = notification.initialStatePayload()
                        if (initialState != null) {
                            if (initialState.uri == plan.notificationResourceURI &&
                                initial.recordInitialState(initialState.uri)
                            ) {
                                // The delivered baseline supersedes every earlier read. A queued
                                // announcing invalidation still owns a fresh read after that baseline.
                                reads.remove(initialState.uri)?.cancel()
                                val pending = dirty.remove(initialState.uri) == true
                                applyDeliveredSnapshot(plan, initialState.contents.orEmpty())
                                if (pending) refresh(initialState.uri, publishEvents = true)
                            }
                        } else if (notification.invalidatesResource(plan)) {
                            val uri = notification.resourceUri()
                            if (uri == plan.notificationResourceURI &&
                                initial.consumeLegacyUpdate(uri)
                            ) {
                                return@takeWhile true
                            }
                            if (uri != null) refresh(uri, publishEvents = true)
                        }
                        true
                    }.collect { }
            } finally {
                reads.values.forEach { it.cancel() }
                reads.values.toList().joinAll()
            }
            if (reconfigure) MonitorRunResult.Reconfigure else MonitorRunResult.Retry(delivered)
        }

    private fun acceptDiscovery(plan: ServiceMonitoringPlan) {
        val advertised = plan.modelResources.map { it.uri }.toSet()
        contextOrdering.keys.retainAll(advertised)
        _state.update { state ->
            val previous = state.snapshot?.resources.orEmpty()
            state.copy(
                resources = advertised,
                notifications = emptyList(),
                activeNotifications =
                    if (plan.notificationResourceURI ==
                        null
                    ) {
                        emptyList()
                    } else {
                        state.activeNotifications
                    },
                snapshot =
                    ServiceMonitoringSnapshot(
                        (advertised + (state.resources.orEmpty() - advertised)).associateWith { uri ->
                            if (uri in advertised) {
                                previous[uri].takeIf { uri in state.resources.orEmpty() }
                            } else {
                                Result.success(null)
                            }
                        },
                    ),
            )
        }
    }

    private fun monitoringPlan(resources: List<ResourceDescriptor>): ServiceMonitoringPlan {
        val plan = serviceMonitoringPlan(resources)
        return when (purpose) {
            ServiceMonitorPurpose.BACKGROUND_ALERTS -> plan.copy(modelResources = emptyList())
            ServiceMonitorPurpose.CONVERSATION_CONTEXT -> plan.copy(notificationResourceURI = null)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Each model resource fails independently; cancellation propagates.
    private suspend fun refreshResource(
        client: ServiceResourceClient,
        plan: ServiceMonitoringPlan,
        uri: String,
        publishEvents: Boolean,
    ): Boolean {
        val read =
            try {
                Result.success(client.readResource(uri, sessionID).also { resourceText(it.result, uri) })
            } catch (
                error: CancellationException,
            ) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (uri == plan.notificationResourceURI) {
            val active = readServiceNotifications(mapOf(uri to read.getOrThrow()), plan)
            val unseen = active.filter { it.id !in observedNotificationIDs }
            observedNotificationIDs.addAll(active.map { it.id })
            _state.update { state ->
                state.copy(
                    notifications = unseen.takeIf { publishEvents }.orEmpty(),
                    activeNotifications = active,
                    retryReason = null,
                )
            }
        } else {
            _state.update { state ->
                val previous = state.snapshot?.resources.orEmpty()
                state.copy(
                    snapshot = ServiceMonitoringSnapshot(previous + (uri to newerResource(uri, previous[uri], read))),
                    notifications = emptyList(),
                    retryReason = null,
                )
            }
        }
        return read.isSuccess
    }

    private fun newerResource(
        uri: String,
        previous: Result<ServiceResourceRead?>?,
        next: Result<ServiceResourceRead>,
    ): Result<ServiceResourceRead?> {
        if (next.isFailure) return next
        val fresh = next.getOrThrow().context ?: return next
        val ordering = contextOrdering[uri]
        if (ordering?.first == fresh.epoch &&
            ordering.second > fresh.version
        ) {
            return previous
                ?: Result.failure(IllegalStateException("Obsolete model resource response"))
        }
        contextOrdering[uri] = fresh.epoch to fresh.version
        return next
    }

    private fun applyDeliveredSnapshot(
        plan: ServiceMonitoringPlan,
        contents: List<ResourceContent>,
    ) {
        val uri = plan.notificationResourceURI ?: return
        val active =
            readServiceNotifications(
                mapOf(
                    uri to
                        ServiceResourceRead(
                            uri,
                            ResourcesReadResult(
                                resultType = ResultType.Complete,
                                contents = contents,
                                ttlMs = 0,
                                cacheScope = CacheScope.Private,
                            ),
                        ),
                ),
                plan,
            )
        observedNotificationIDs.addAll(active.map { it.id })
        _state.update { state -> state.copy(activeNotifications = active, notifications = emptyList()) }
    }
}

private fun readServiceNotifications(
    readResults: Map<String, ServiceResourceRead>,
    plan: ServiceMonitoringPlan,
): List<ServiceNotification> {
    val uri = plan.notificationResourceURI ?: return emptyList()
    val readResult = readResults[uri]?.result ?: throw IllegalArgumentException("resource read result is missing $uri")
    val content =
        readResult.contents.orEmpty().firstOrNull { it.uri == uri }
            ?: throw IllegalArgumentException("resource read result is missing $uri")
    val text = content.text ?: throw IllegalArgumentException("resource $uri is missing text content")
    val events =
        Json.parseToJsonElement(text) as? JsonArray
            ?: throw IllegalArgumentException("resource $uri must be a JSON array")
    val parsed =
        events.mapIndexed { index, element ->
            val event =
                element as? JsonObject
                    ?: throw IllegalArgumentException("resource $uri item $index must be an object")
            val id = event.requiredNotificationString("id", uri, index)
            ServiceNotification(
                id = id,
                title = event.requiredNotificationString("title", uri, index),
                text = event.requiredNotificationString("body", uri, index),
                itemId = event.optionalNotificationString("itemId", uri, index),
                url = event.optionalNotificationString("url", uri, index),
                expiresAt =
                    event.optionalNotificationString("expiresAt", uri, index)?.let {
                        Instant.parse(it).also { timestamp ->
                            timestamp.toEpochMilli()
                        }
                    },
            )
        }
    require(parsed.map { it.id }.distinct().size == parsed.size) { "resource $uri has duplicate notification IDs" }
    val now = Instant.now()
    return parsed.filter { it.expiresAt?.isAfter(now) != false }
}

private fun JsonObject.optionalNotificationString(
    field: String,
    uri: String,
    index: Int,
): String? {
    if (field !in this) return null
    return requiredNotificationString(field, uri, index)
}

private fun JsonObject.requiredNotificationString(
    field: String,
    uri: String,
    index: Int,
): String =
    (this[field] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf {
            it.isString
        }?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("resource $uri item $index is missing $field")

private fun nextRetryDelay(delayMs: Long): Long = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)

private fun subscriptionFilter(
    plan: ServiceMonitoringPlan,
    capability: ResourcesCapability?,
): SubscriptionFilter? {
    val resourceSubscriptions = plan.resourceURIs.takeIf { capability?.subscribe == true && it.isNotEmpty() }
    val resourcesListChanged = capability?.listChanged?.takeIf { it }
    if (resourceSubscriptions == null && resourcesListChanged == null) return null
    return SubscriptionFilter(
        resourcesListChanged = resourcesListChanged,
        resourceSubscriptions = resourceSubscriptions,
    )
}

// Each URI accepts one delivered baseline per stream and consumes its paired
// legacy invalidation. Later payload duplicates cannot replace fresher reads.
private class InitialStateWindow {
    private val delivered = mutableSetOf<String>()
    private val consumedUpdates = mutableSetOf<String>()

    fun recordInitialState(uri: String): Boolean = delivered.add(uri)

    fun consumeLegacyUpdate(uri: String?): Boolean {
        if (uri == null || uri !in delivered) return false
        return consumedUpdates.add(uri)
    }
}

private fun JSONRPCNotification.resourceUri(): String? =
    ((params as? JSONPresence.Value)?.value as? JsonObject)?.get("uri")?.jsonPrimitive?.contentOrNull

private fun JSONRPCNotification.initialStatePayload(): SubscriptionsInitialStateParams? {
    if (method != NotificationMethod.SubscriptionsInitialState) return null
    val params = (this.params as? JSONPresence.Value)?.value as? JsonObject ?: return null
    return try {
        mcpJson.decodeFromJsonElement(params)
    } catch (_: SerializationException) {
        null
    }
}

private fun JSONRPCNotification.invalidatesResource(plan: ServiceMonitoringPlan): Boolean {
    if (method != NotificationMethod.ResourcesUpdated) return false
    return resourceUri() in plan.resourceURIs
}

private sealed interface MonitorRunResult {
    data object Reconfigure : MonitorRunResult

    data object Static : MonitorRunResult

    data class Retry(
        val delivered: Boolean,
    ) : MonitorRunResult
}

private val mcpJson = Json { ignoreUnknownKeys = true }

private const val INITIAL_RETRY_DELAY_MS = 1_000L
private const val MAX_RETRY_DELAY_MS = 30_000L
