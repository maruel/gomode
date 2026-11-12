// Owns retained-service discovery, background alerts, conversation-scoped context streams, and tool routing.
//
// Each retained entry owns its MCP discovery, credential snapshot, retry worker, and resource
// monitor. Sign-in, logout, removal, and failures affect that entry alone. Participants need a
// first MCP tool group and need no voice gateway. Native settings shows each participant's
// readiness and retries a failed connection. Notification IDs stay distinct across services, and
// notification titles identify their source.
//
// The overview has one catalog-wide context budget with source labels; detailed instructions,
// schemas, and resources are read on demand. Gateway and local LLM conversations use the whole
// catalog. Keyword engines keep the selected service's direct tools and context. Hidden
// participants keep monitoring, and shared voice receives their labeled context without duplicate
// native notifications.
package com.fghbuild.gomode.service

import com.fghbuild.gomode.ui.ServiceBootstrapState
import com.fghbuild.gomode.ui.web.FrontendVoiceTools
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.WebShellLoadState
import com.fghbuild.gomode.voice.InvalidMcpResponse
import com.fghbuild.gomode.voice.McpToolResult
import com.fghbuild.gomode.voice.ServiceMcp
import com.fghbuild.gomode.voice.ServiceMcpAuthorization
import com.fghbuild.gomode.voice.ToolCallValidator
import com.fghbuild.gomode.voice.prepareServiceMcp
import com.fghbuild.mcp.sdk.v1.ResourceContent
import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class ServiceReadiness(
    val text: String,
) {
    CONNECTING("Connecting"),
    READY("Ready"),
    RECONNECTING("Reconnecting"),
    UNAVAILABLE("Unavailable"),
    WAITING_FOR_PAGE("Loading page"),
    WAITING_FOR_SIGN_IN("Sign in to connect"),
}

internal data class CatalogService(
    val key: ServiceShellKey,
    val label: String,
    val readiness: ServiceReadiness,
    val error: String? = null,
    val monitoring: ServiceMonitorState = ServiceMonitorState(),
)

internal data class CatalogNotification(
    val source: ServiceShellKey,
    val notification: ServiceNotification,
    val identityGeneration: Long,
)

// Activity/Main-thread owner. Each participant has a cancellable discovery worker and resource monitor.
// Selection is absent: shared gateway conversations use this catalog independently of visible-page focus.
internal class ServiceCatalog(
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val members = linkedMapOf<ServiceShellKey, Participant>()
    private var nextIdentityGeneration = 0L
    private var networkAvailable = true
    private val mutableState = MutableStateFlow<Map<ServiceShellKey, CatalogService>>(emptyMap())
    val state = mutableState.asStateFlow()
    private val mutableNotifications = MutableSharedFlow<CatalogNotification>(extraBufferCapacity = 64)
    val notifications = mutableNotifications.asSharedFlow()

    fun reconcile(
        entries: Map<ServiceShellKey, ServiceShellSession>,
        labels: Map<String, String>,
    ) {
        members.keys.filter { it !in entries }.forEach { members.remove(it)?.close() }
        entries.forEach { (key, entry) ->
            val current = members[key]
            val participant =
                if (current == null || current.entry !== entry) {
                    current?.close()
                    Participant(entry).also {
                        members[key] = it
                        it.start()
                    }
                } else {
                    current
                }
            participant.label = labels[key.serviceID].orEmpty()
        }
        publish()
    }

    fun retry(key: ServiceShellKey) {
        members[key]?.retry?.let { it.value++ }
    }

    // A route change invalidates idle streams too: TCP may stay open on the abandoned network.
    fun networkChanged(available: Boolean) {
        networkAvailable = available
        members.values.forEach { it.retry.value++ }
    }

    // Return the original prepared authority, not credentials recaptured from a page URL.
    fun authorization(key: ServiceShellKey): ServiceMcpAuthorization? = members[key]?.prepared?.mcp?.authorization

    fun isAuthorizationCurrent(
        key: ServiceShellKey,
        authorization: ServiceMcpAuthorization,
    ): Boolean {
        val entry = members[key]?.entry ?: return false
        val settings = (entry.state.value.bootstrap as? ServiceBootstrapState.Ready)?.settings
        val endpoint =
            settings
                ?.webShell
                ?.toolGroups
                ?.firstOrNull()
                ?.let { resolveServiceURL(key.url, it.endpoint) }
        return authorization.isCurrent() && (settings == null || endpoint == authorization.endpointURL)
    }

    fun isReady(key: ServiceShellKey): Boolean {
        val member = members[key] ?: return false
        return member.prepared?.let(member::current) == true
    }

    fun hasContextResources(key: ServiceShellKey): Boolean {
        val member = members[key] ?: return false
        val prepared = member.prepared ?: return false
        return member.current(prepared) && prepared.mcp?.capabilities?.resources != null
    }

    fun notificationForDelivery(notification: CatalogNotification): CatalogNotification? {
        val member = members[notification.source] ?: return null
        val prepared = member.prepared ?: return null
        if (member.identityGeneration != notification.identityGeneration || !member.current(prepared)) return null
        val current =
            state.value[notification.source]
                ?.monitoring
                ?.activeNotifications
                ?.firstOrNull { it.id == notification.notification.id } ?: return null
        return notification.copy(notification = current)
    }

    fun close() {
        members.values.forEach { it.close() }
        members.clear()
        scope.cancel()
        publish()
    }

    // One section per current server with known context, keyed by server. Tool schemas,
    // instructions, and details are discovered on demand.
    fun contextSections(snapshots: Map<ServiceShellKey, ServiceMonitorState>): Map<ServiceShellKey, String> =
        state.value.values
            .mapNotNull { entry ->
                val participant = members[entry.key] ?: return@mapNotNull null
                if (participant.prepared?.let(participant::current) != true) return@mapNotNull null
                val body = snapshots[entry.key]?.voiceContext.orEmpty()
                serverSection(
                    entry.label,
                    entry.key.serviceID,
                    body,
                ).takeIf { it.isNotEmpty() }?.let { entry.key to it }
            }.toMap()

    // The conversation owns one item-only stream per prepared authority. Keep
    // unchanged sources alive when another participant joins or leaves; alerts
    // remain exclusively owned by each participant's background monitor.
    fun monitorVoiceContext(
        scope: CoroutineScope,
        sessionID: String,
    ): StateFlow<Map<ServiceShellKey, ServiceMonitorState>> {
        val snapshots = MutableStateFlow<Map<ServiceShellKey, ServiceMonitorState>>(emptyMap())
        scope.launch {
            val workers = mutableMapOf<ServiceShellKey, Pair<ServiceMcp, Job>>()
            try {
                state
                    .map {
                        members
                            .mapNotNull { (key, participant) ->
                                val prepared = participant.prepared
                                prepared
                                    ?.mcp
                                    ?.takeIf {
                                        participant.current(prepared) && it.capabilities.resources != null
                                    }?.let { key to it }
                            }.toMap()
                    }.distinctUntilChanged()
                    .collect { participants ->
                        for (key in workers.keys.toList()) {
                            if (workers.getValue(key).first.authorization !== participants[key]?.authorization) {
                                workers.remove(key)?.second?.cancelAndJoin()
                                snapshots.update { it - key }
                            }
                        }
                        for ((key, mcp) in participants) {
                            if (key in workers) continue
                            val participant = members[key] ?: continue
                            val authority = participant.prepared ?: continue
                            if (authority.mcp !== mcp) continue
                            val ready =
                                participant.entry.state.value.bootstrap as? ServiceBootstrapState.Ready ?: continue
                            val worker =
                                launch {
                                    val monitor =
                                        ServiceMonitor(
                                            this,
                                            sessionID,
                                            ServiceMonitorPurpose.CONVERSATION_CONTEXT,
                                        ) { _, _ -> mcp.client }
                                    try {
                                        monitor.start(key.url, ready.settings)
                                        monitor.state.collect { value ->
                                            val prepared = participant.prepared
                                            // Tool-schema refresh replaces PreparedService, but it
                                            // does not replace this credential-bound context owner.
                                            if (members[key] !== participant || prepared == null ||
                                                !participant.current(prepared) ||
                                                prepared.identity != authority.identity ||
                                                prepared.mcp?.authorization !== mcp.authorization
                                            ) {
                                                return@collect
                                            }
                                            snapshots.update { it + (key to value) }
                                        }
                                    } finally {
                                        monitor.stop()
                                    }
                                }
                            workers[key] = mcp to worker
                        }
                    }
            } finally {
                workers.values.forEach { it.second.cancel() }
            }
        }
        return snapshots.asStateFlow()
    }

    @Suppress("TooGenericExceptionCaught") // Tool failures are returned to the caller without affecting other services.
    suspend fun execute(
        name: String,
        args: JsonObject,
        sessionID: String? = null,
        locale: String? = null,
        isCurrent: () -> Boolean,
    ): McpToolResult? {
        if (name !in serviceCatalogToolNames) return null
        catalogValidator.validate(name, args)?.let { return catalogError(it) }
        if (!isCurrent()) return catalogError("Voice session ended.")
        if (name == "discover_service" && args["server_id"] == null) {
            return McpToolResult(
                buildJsonObject {
                    put(
                        "servers",
                        JsonArray(
                            state.value.values.map { entry ->
                                buildJsonObject {
                                    put("server_id", entry.key.serviceID)
                                    put("label", entry.label.boundedModelText(256))
                                    put("labelTruncated", entry.label.length > 256)
                                    put("url", entry.key.url)
                                    put("status", entry.readiness.text)
                                    entry.error?.let { put("error", it) }
                                }
                            },
                        ),
                    )
                },
            )
        }
        val id = args.getValue("server_id").jsonPrimitive.content
        val participant =
            members.values.singleOrNull { it.entry.key.serviceID == id }
                ?: return catalogError("Server is unavailable. Discover servers again.")
        var prepared = participant.prepared ?: return catalogError("Server is not ready. ${participant.status.text}.")
        if (!participant.current(prepared) || !isCurrent()) return catalogError("Service authentication changed.")
        if (name == "read_service_resource" && prepared.mcp?.capabilities?.resources == null) {
            return catalogError("Resources are unavailable for this server.")
        }
        val frontend = FrontendTarget(participant.entry.frontendTools, participant.entry.frontendTools.revision)
        val result =
            try {
                // The draft subscription surface has no tool-list notifications. Resolve current schemas on demand.
                prepared = participant.refreshTools(prepared, isCurrent)
                if (!participant.current(prepared) || !isCurrent()) return catalogError("Caller or service changed.")
                when (name) {
                    "discover_service" -> {
                        participant.discover(prepared, sessionID, isCurrent)
                    }

                    "read_service_resource" -> {
                        val uri = args.getValue("uri").jsonPrimitive.content
                        val resource = checkNotNull(prepared.mcp).client.readResource(uri, sessionID).result
                        val contents =
                            resource.contents.orEmpty().map { it.copy(_meta = null) }

                        // TODO: This is the last JSON envelope the model sees: the read result is
                        // wrapped as {"resource": contents} and an oversized one becomes
                        // {"resource": uri, "status": "omitted"}. Every other model-facing path now
                        // sends server text verbatim and drops what does not fit (ModelContext.kt).
                        // Return the resource text itself, and on overflow a plain error result the
                        // model can act on, e.g. "Resource too large; use the server's tools."
                        val body = buildJsonObject { put("resource", Json.encodeToJsonElement(contents)) }
                        val limit = MODEL_CONTEXT_BUDGET
                        McpToolResult(
                            if (body.toString().length <= limit) {
                                body
                            } else {
                                buildJsonObject {
                                    put("resource", uri)
                                    put("status", "omitted")
                                }
                            },
                        )
                    }

                    else -> {
                        participant.dispatch(prepared, args, frontend, sessionID, locale, isCurrent)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                catalogError(e.message ?: "Service request failed.")
            }
        if (!participant.current(prepared) ||
            !isCurrent()
        ) {
            return catalogError("Service identity or connection changed during the request.")
        }
        return McpToolResult(
            buildJsonObject {
                put("server_id", id)
                put("url", participant.entry.key.url)
                put("result", result.structuredContent)
            },
            result.isError,
        )
    }

    private fun publish() {
        mutableState.value =
            members.mapValues { (_, p) ->
                CatalogService(
                    p.entry.key,
                    p.label,
                    p.status,
                    p.error,
                    p.monitoring.copy(
                        activeNotifications = p.monitoring.activeNotifications?.map(p::sourceNotification),
                    ),
                )
            }
    }

    private inner class Participant(
        val entry: ServiceShellSession,
    ) {
        var identityGeneration = 0L
        var label = ""
        var status = ServiceReadiness.CONNECTING
        var error: String? = null
        var monitoring = ServiceMonitorState()
        var prepared: PreparedService? = null
        val retry = MutableStateFlow(0)
        private var job: Job? = null
        private var alertIdentityEpoch = entry.bearerStore.identityEpoch.value
        private var alertsRevoked = false

        fun sourceNotification(notification: ServiceNotification): ServiceNotification {
            val serviceLabel = label.trim().ifBlank { entry.key.url }
            return notification.copy(
                id = Json.encodeToString(listOf(entry.key.serviceID, entry.key.url, notification.id)),
                title = "$serviceLabel: ${notification.title}",
            )
        }

        fun start() {
            job =
                scope.launch {
                    combine(
                        entry.state.map { it.bootstrap to it.load }.distinctUntilChanged(),
                        entry.bearerStore.authReady,
                        entry.bearerStore.authEpoch,
                        entry.bearerStore.token,
                        retry,
                    ) { page, ready, epoch, token, retry ->
                        ParticipantIdentity(
                            page.first,
                            page.second,
                            ready,
                            epoch,
                            token,
                            retry,
                        )
                    }.collectLatest { identity ->
                        identityGeneration = ++nextIdentityGeneration
                        prepared = null
                        val epoch = entry.bearerStore.identityEpoch.value
                        alertsRevoked = alertsRevoked || epoch != alertIdentityEpoch
                        alertIdentityEpoch = epoch
                        monitoring =
                            ServiceMonitorState(
                                activeNotifications = emptyList<ServiceNotification>().takeIf { alertsRevoked },
                            )
                        error = null
                        val ready = identity.bootstrap as? ServiceBootstrapState.Ready
                        when {
                            !networkAvailable -> {
                                status = ServiceReadiness.RECONNECTING
                                error = "Waiting for network"
                                publish()
                            }

                            ready == null -> {
                                status = ServiceReadiness.UNAVAILABLE
                                error =
                                    when (val bootstrap = identity.bootstrap) {
                                        is ServiceBootstrapState.Error -> bootstrap.message
                                        is ServiceBootstrapState.Unvalidated -> bootstrap.reason
                                        is ServiceBootstrapState.Ready -> null
                                    }
                                publish()
                            }

                            identity.load is WebShellLoadState.Failed -> {
                                status = ServiceReadiness.UNAVAILABLE
                                error = identity.load.message
                                publish()
                            }

                            identity.load != WebShellLoadState.Ready -> {
                                status = ServiceReadiness.WAITING_FOR_PAGE
                                publish()
                            }

                            !identity.ready -> {
                                status = ServiceReadiness.WAITING_FOR_SIGN_IN
                                publish()
                            }

                            else -> {
                                connect(identity, ready)
                            }
                        }
                    }
                }
        }

        fun current(value: PreparedService): Boolean =
            networkAvailable && members[entry.key] === this && prepared === value &&
                entry.bearerStore.authReady.value &&
                entry.state.value.load == WebShellLoadState.Ready &&
                value.identity.bootstrap == entry.state.value.bootstrap &&
                value.identity.epoch == entry.bearerStore.authEpoch.value &&
                value.identity.token == entry.bearerStore.token.value &&
                (value.mcp?.authorization?.isCurrent() != false)

        @Suppress("TooGenericExceptionCaught") // Each service independently retries network/discovery/schema failures.
        private suspend fun connect(identity: ParticipantIdentity, ready: ServiceBootstrapState.Ready) {
            var retryMs = 1_000L
            while (true) {
                status = ServiceReadiness.CONNECTING
                publish()
                try {
                    val mcp =
                        if (ready.settings.webShell.toolGroups
                                .orEmpty()
                                .isEmpty()
                        ) {
                            null
                        } else {
                            prepareServiceMcp(entry.key.url, ready.settings, entry.bearerStore::tokenFor)
                        }
                    prepared = PreparedService(identity, mcp, ToolCallValidator(mcp?.tools.orEmpty()))
                    error = null
                    status = ServiceReadiness.READY
                    publish()
                    if (mcp?.capabilities?.resources == null) {
                        alertsRevoked = false
                        monitoring = ServiceMonitorState(activeNotifications = emptyList())
                        publish()
                        awaitCancellation()
                    }
                    coroutineScope {
                        val monitor = ServiceMonitor(this) { _, _ -> mcp.client }
                        try {
                            monitor.start(entry.key.url, ready.settings)
                            monitor.state.collect { value ->
                                if (value.activeNotifications != null) alertsRevoked = false
                                monitoring = if (alertsRevoked) value.copy(activeNotifications = emptyList()) else value
                                error = value.retryReason
                                status =
                                    if (value.retryReason == null) {
                                        ServiceReadiness.READY
                                    } else {
                                        ServiceReadiness.RECONNECTING
                                    }
                                publish()
                                value.notifications.forEach { notification ->
                                    mutableNotifications.emit(
                                        CatalogNotification(
                                            entry.key,
                                            sourceNotification(notification),
                                            identityGeneration,
                                        ),
                                    )
                                }
                            }
                        } finally {
                            monitor.stop()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    prepared = null
                    status = ServiceReadiness.RECONNECTING
                    error = e.message ?: "Service discovery failed."
                    publish()
                    delay(retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(30_000L)
                }
            }
        }

        suspend fun refreshTools(
            value: PreparedService,
            isCurrent: () -> Boolean,
        ): PreparedService {
            check(current(value) && isCurrent()) { "Caller or service changed before tool discovery." }
            val mcp = value.mcp ?: return value
            val tools = mcp.client.listTools()
            check(current(value) && isCurrent()) { "Caller or service changed during tool discovery." }
            if (tools == mcp.tools) return value
            val refreshed = value.copy(mcp = mcp.copy(tools = tools), validator = ToolCallValidator(tools))
            prepared = refreshed
            return refreshed
        }

        suspend fun discover(
            value: PreparedService,
            sessionID: String?,
            isCurrent: () -> Boolean,
        ): McpToolResult {
            check(current(value) && isCurrent()) { "Caller or service changed before resource discovery." }
            val resources =
                if (value.mcp?.capabilities?.resources != null) {
                    checkNotNull(value.mcp).client.listResources(sessionID)
                } else {
                    emptyList()
                }
            check(current(value) && isCurrent()) { "Caller or service changed during resource discovery." }
            val templates =
                if (value.mcp?.capabilities?.resources != null) {
                    checkNotNull(value.mcp).client.listResourceTemplates(sessionID)
                } else {
                    emptyList()
                }
            check(current(value) && isCurrent()) { "Caller or service changed during resource discovery." }
            val instructions =
                value.mcp
                    ?.client
                    ?.serverInstructions(sessionID)
                    .orEmpty()
            val context =
                value.mcp
                    ?.takeIf { it.capabilities.resources != null }
                    ?.client
                    ?.let { readInitialServiceContext(it, sessionID) }
                    .orEmpty()
            check(current(value) && isCurrent()) { "Caller or service changed during context discovery." }
            return McpToolResult(
                buildJsonObject {
                    put("instructions", instructions)
                    put("context", context)
                    put("mcp_tools", Json.encodeToJsonElement(value.mcp?.tools.orEmpty()))
                    put(
                        "frontend_tools",
                        Json.encodeToJsonElement(
                            entry.frontendTools.declarations
                                .takeIf {
                                    entry.isFrontendReady
                                }.orEmpty(),
                        ),
                    )
                    put("resources", Json.encodeToJsonElement(resources))
                    put("resource_templates", Json.encodeToJsonElement(templates))
                },
            )
        }

        suspend fun dispatch(
            value: PreparedService,
            args: JsonObject,
            frontend: FrontendTarget,
            sessionID: String?,
            locale: String?,
            isCurrent: () -> Boolean,
        ): McpToolResult {
            val tool = args.getValue("tool").jsonPrimitive.content
            val arguments = args.getValue("arguments").jsonObject
            return when (args.getValue("kind").jsonPrimitive.content) {
                "mcp" -> {
                    value.validator.validate(tool, arguments)?.let { return catalogError(it) }
                    if (!current(value) ||
                        !isCurrent()
                    ) {
                        return catalogError("Caller or service changed before execution.")
                    }
                    val mcp = value.mcp ?: return catalogError("MCP tools are unavailable for this server.")
                    mcp.client.callTool(tool, arguments, sessionID, locale)
                }

                else -> {
                    if (frontend.owner.revision !=
                        frontend.revision
                    ) {
                        return catalogError("Hosted page changed before execution.")
                    }
                    val declarations = frontend.owner.declarations
                    val validator =
                        ToolCallValidator(
                            declarations.map { ToolDescriptor(name = it.name, inputSchema = it.parameters) },
                        )
                    validator.validate(tool, arguments)?.let { return catalogError(it) }
                    val conversation = sessionID ?: return catalogError("Frontend tools require a voice conversation.")
                    val result =
                        frontend.owner.execute(tool, arguments, conversation) {
                            current(value) && isCurrent() && frontend.owner.revision == frontend.revision
                        }
                            ?: return catalogError("Frontend tool is unavailable on its owning page.")
                    McpToolResult(result, result["error"] != null)
                }
            }
        }

        fun close() {
            prepared = null
            job?.cancel()
        }
    }
}

private data class FrontendTarget(
    val owner: FrontendVoiceTools,
    val revision: Long,
)

private data class ParticipantIdentity(
    val bootstrap: ServiceBootstrapState,
    val load: WebShellLoadState,
    val ready: Boolean,
    val epoch: Long,
    val token: String?,
    val retry: Int,
)

private data class PreparedService(
    val identity: ParticipantIdentity,
    val mcp: ServiceMcp?,
    val validator: ToolCallValidator,
)

private fun catalogError(message: String): McpToolResult =
    McpToolResult(
        buildJsonObject {
            put("error", message)
        },
        true,
    )
