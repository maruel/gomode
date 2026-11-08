// Owns voice conversation identities, scoped context streams, and gateway authorization.
package com.fghbuild.gomode.voice

import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceMonitoringSnapshot
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.service.VoiceTokenClient
import com.fghbuild.gomode.service.serviceCatalogTools
import com.fghbuild.gomode.service.serviceOrigin
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

internal const val FRONTEND_NOT_READY = "Frontend not ready."

// Give the selected host half a second to supply advisory context; other hosts
// update independently and never extend connection startup behind a slow peer.
private const val INITIAL_CONTEXT_BUDGET_MS = 500L

internal data class VoiceConversationState(
    val sponsor: String,
    val gateway: String = "",
    val choice: String = "",
    val voice: String = "",
    val serviceContext: String? = null,
)

// Shared with the IO preparation owner; finishing authorization revokes retained native prefill too.
internal class VoiceConversationLifetime(
    val sessionID: String =
        UUID
            .randomUUID()
            .toString(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var isCurrent = true
        private set

    fun finish() {
        isCurrent = false
        scope.cancel()
    }
}

internal data class LocalConversationPreparation(
    val setup: VoiceSetup,
    val lifetime: VoiceConversationLifetime,
)

// Main-thread owner. Credential/account checks also run synchronously at every model admission/dispatch boundary.
internal class VoiceConversation(
    private val settingsRepository: SettingsRepository,
    private val settingsClient: ServiceSettingsClient,
    private val catalog: ServiceCatalog,
    private val authorityCurrent: () -> Boolean = { true },
    private val entries: () -> Map<ServiceShellKey, ServiceShellSession>,
) {
    private val mutableState = MutableStateFlow<VoiceConversationState?>(null)
    val state = mutableState.asStateFlow()
    private var conversation: Conversation? = null

    fun begin(
        recovery: Boolean,
        sessionID: String? = null,
    ) {
        if (recovery) {
            if (conversation == null) throw VoiceSetupException("Gateway conversation ended. Connect again.")
            requireCurrent()
            return
        }
        val settings = settingsRepository.settings.value
        val key = ServiceShellKey(settings.activeServiceId, settings.activeServiceURL)
        val sponsor =
            if (usesGatewayVoice(settings)) {
                entries()[key] ?: throw VoiceSetupException("Selected server is unavailable")
            } else {
                null
            }
        if (sponsor != null && !sponsor.bearerStore.authReady.value) {
            throw VoiceSetupException("Sign in before connecting voice")
        }
        if (sponsor != null && !frontendReadyForConnection()) throw VoiceSetupException(FRONTEND_NOT_READY)
        conversation?.lifetime?.finish()
        conversation =
            Conversation(
                settings,
                sponsor,
                sponsor?.bearerStore?.identityEpoch?.value,
                sponsor?.bearerStore?.token?.value,
                VoiceConversationLifetime(
                    sessionID ?: UUID
                        .randomUUID()
                        .toString(),
                ),
            )
        if (sponsor != null) rememberCredentials(key.url)
        admitParticipants()
        requireCurrent()
        mutableState.value =
            VoiceConversationState(settings.services.firstOrNull { it.id == key.serviceID }?.label ?: key.url)
        monitorContext(checkNotNull(conversation))
    }

    private fun monitorContext(current: Conversation) {
        if (current.contextSnapshots != null) return
        val snapshots = catalog.monitorVoiceContext(current.lifetime.scope, current.lifetime.sessionID)
        current.contextSnapshots = snapshots
        current.lifetime.scope.launch {
            snapshots.collect { snapshots ->
                if (conversation === current && isCurrent()) {
                    mutableState.value = mutableState.value?.copy(serviceContext = catalog.contextText(snapshots))
                }
            }
        }
    }

    fun finish() {
        conversation?.lifetime?.finish()
        conversation = null
        mutableState.value = null
    }

    val sessionID: String
        get() = checkNotNull(conversation) { "Voice conversation ended" }.lifetime.sessionID

    fun frontendReadyForConnection(): Boolean {
        val selected = settingsRepository.settings.value
        if (selected.activeServiceURL.isBlank()) return true
        return entries()[ServiceShellKey(selected.activeServiceId, selected.activeServiceURL)]?.isFrontendReady == true
    }

    // Page availability can revoke dispatch without changing the gateway sponsor's authenticated identity.
    fun isCurrent(): Boolean {
        if (!authorityCurrent()) return false
        val current = conversation ?: return false
        if (!current.lifetime.isCurrent) return false
        val sponsor = current.sponsor
        if (sponsor != null && (
                entries()[sponsor.key] !== sponsor ||
                    sponsor.bearerStore.identityEpoch.value != current.sponsorEpoch
            )
        ) {
            return false
        }
        if (current.credentials.any { (endpoint, cookie) -> cookieFor(endpoint) != cookie }) return false
        for ((key, identity) in current.admitted) {
            val entry = entries()[key] ?: continue
            if (entry.bearerStore.authReady.value &&
                (
                    identity.entry !== entry || identity.epoch != entry.bearerStore.identityEpoch.value ||
                        identity.cookie != cookieFor(key.url) ||
                        identity.authorization?.let { !catalog.isAuthorizationCurrent(key, it) } == true
                )
            ) {
                return false
            }
        }
        return true
    }

    private fun requireCurrent() {
        if (!isCurrent()) throw VoiceSetupException("A conversation account changed. Start a new voice conversation.")
    }

    fun tokenFor(endpoint: String): String? {
        requireCurrent()
        val current = conversation ?: return null
        val sponsor = current.sponsor ?: return null
        return current.bearer.takeIf { serviceOrigin(endpoint) == serviceOrigin(sponsor.key.url) }
    }

    suspend fun prepare(pinnedVoice: GatewayVoiceSelection? = null): VoiceSetup {
        val current = conversation ?: throw VoiceSetupException("Gateway conversation ended")
        requireCurrent()
        val cached = current.setup
        val setup =
            if (cached == null) {
                prepareVoiceSetup(
                    settingsRepository,
                    settingsClient,
                    ::tokenFor,
                    pinnedVoice = pinnedVoice,
                    capturedSettings = current.settings,
                    sharedCatalog = catalog,
                    sessionID = current.lifetime.sessionID,
                    ensureCurrent = ::requireCurrent,
                ).also {
                    rememberCredentials(it.gatewayEndpointURL)
                    it.tokenEndpointURL?.let(::rememberCredentials)
                }
            } else {
                val service =
                    cached.tokenEndpointURL?.let { endpoint ->
                        VoiceTokenClient().fetch(endpoint, cookieFor(endpoint), tokenFor(endpoint))
                    }
                cached.copy(service = service)
            }
        if (conversation !== current) throw VoiceSetupException("Gateway conversation was replaced")
        requireCurrent()
        current.setup = setup
        mutableState.value =
            mutableState.value?.copy(
                gateway = setup.gatewayEndpointURL,
                choice = setup.choiceID.orEmpty(),
                voice = setup.voiceName,
            )
        return setup.copy(serviceContextText = initialContextText(current))
    }

    // Each new idle preparation follows an explicit disconnect/model/account boundary, never a focus change.
    suspend fun prepareLocal(): LocalConversationPreparation {
        begin(recovery = false)
        val current = checkNotNull(conversation)
        val settings = current.settings
        var prepared = false
        try {
            val setup =
                standaloneVoiceSetup().copy(
                    serverName =
                        settings.services
                            .firstOrNull {
                                it.id == settings.activeServiceId
                            }?.label ?: "Go Mode",
                    serverID = settings.activeServiceId.takeIf { it.isNotEmpty() },
                    mcpClient = null,
                    tools = nativeVoiceTools(settings) + serviceCatalogTools,
                    systemInstruction =
                        "You are Go Mode. Use the service catalog to discover and call tools on explicit servers. " +
                            "Each server's instructions apply only to that server. Selecting a server changes focus only.",
                    serviceContextText = initialContextText(current),
                )
            prepared = true
            return LocalConversationPreparation(setup, current.lifetime)
        } finally {
            if (!prepared) current.lifetime.finish()
        }
    }

    private suspend fun initialContextText(current: Conversation): String {
        val snapshots = checkNotNull(current.contextSnapshots)
        val selected = ServiceShellKey(current.settings.activeServiceId, current.settings.activeServiceURL)
        if (catalog.hasContextResources(selected) && selected !in snapshots.value) {
            // Other services continue independently. Reconnect reuses this same
            // baseline and stream rather than starting another discovery pass.
            withTimeoutOrNull(INITIAL_CONTEXT_BUDGET_MS) { snapshots.first { selected in it } }
        }
        if (conversation !== current) throw VoiceSetupException("Voice conversation was replaced")
        requireCurrent()
        return contextText()
    }

    fun contextText(): String {
        requireCurrent()
        admitParticipants()
        requireCurrent()
        val settings = settingsRepository.settings.value
        val focus = Json.encodeToString(listOf(settings.activeServiceId.take(128), settings.activeServiceURL.take(512)))
        val context = catalog.contextText(conversation?.contextSnapshots?.value.orEmpty())
        return "Selected server (focus only): $focus\n$context"
    }

    suspend fun execute(
        name: String,
        args: JsonObject,
        sessionID: String,
        locale: String,
        isCurrentAttempt: () -> Boolean,
    ): McpToolResult? {
        requireCurrent()
        admitParticipants()
        requireCurrent()
        val result = catalog.execute(name, args, sessionID, locale) { isCurrentAttempt() && isCurrent() }
        requireCurrent()
        return result
    }

    private fun rememberCredentials(endpoint: String) {
        val current = checkNotNull(conversation)
        current.credentials.putIfAbsent(endpoint, cookieFor(endpoint))
    }

    private fun admitParticipants() {
        val current = conversation ?: return
        entries().forEach { (key, entry) ->
            // Native MCP facts and schemas can contribute while frontend tools are still preparing.
            if (entry.bearerStore.authReady.value) {
                val identity =
                    current.admitted.getOrPut(key) {
                        AdmittedIdentity(entry, entry.bearerStore.identityEpoch.value, cookieFor(key.url))
                    }
                if (identity.authorization == null) identity.authorization = catalog.authorization(key)
            }
        }
    }
}

private data class AdmittedIdentity(
    val entry: ServiceShellSession,
    val epoch: Long,
    val cookie: String?,
    var authorization: ServiceMcpAuthorization? = null,
)

private class Conversation(
    val settings: SettingsState,
    val sponsor: ServiceShellSession?,
    val sponsorEpoch: Long?,
    val bearer: String?,
    val lifetime: VoiceConversationLifetime,
) {
    var contextSnapshots: StateFlow<Map<ServiceShellKey, ServiceMonitoringSnapshot>>? = null
    val admitted = mutableMapOf<ServiceShellKey, AdmittedIdentity>()
    val credentials = mutableMapOf<String, String?>()
    var setup: VoiceSetup? = null
}
