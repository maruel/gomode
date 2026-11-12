// Owns voice conversation identities, scoped context streams, and gateway authorization.
package com.fghbuild.gomode.voice

import android.util.Log
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceMonitorState
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.service.VoiceTokenClient
import com.fghbuild.gomode.service.boundedModelText
import com.fghbuild.gomode.service.modelContextText
import com.fghbuild.gomode.service.serviceCatalogTools
import com.fghbuild.gomode.service.serviceOrigin
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
private const val CATALOG_CONTEXT_BUDGET = 12_000
private val SELECTED_SECTION = Any()

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
                    // Changing this value is the trigger for hosts to call contextUpdate().
                    mutableState.value =
                        mutableState.value?.copy(
                            serviceContext = catalog.contextSections(snapshots).values.joinToString("\n\n"),
                        )
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
                awaitFrontendContext(current)
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
            awaitFrontendContext(current)
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
                        "",
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
        if (catalog.hasContextResources(selected) && !snapshots.value[selected].contextReady()) {
            // Other services continue independently. Reconnect reuses this same
            // baseline and stream rather than starting another discovery pass.
            withTimeoutOrNull(INITIAL_CONTEXT_BUDGET_MS) { snapshots.first { it[selected].contextReady() } }
        }
        if (conversation !== current) throw VoiceSetupException("Voice conversation was replaced")
        requireCurrent()
        return contextText()
    }

    // Sections the current model session already received, keyed by owner.
    private var sentContext: Map<Any, String> = emptyMap()

    private fun contextSections(): Map<Any, String> {
        requireCurrent()
        admitParticipants()
        requireCurrent()
        val settings = settingsRepository.settings.value
        val label =
            settings.services.firstOrNull { it.id == settings.activeServiceId }?.label ?: settings.activeServiceURL
        val selected = "Selected server: ${label.boundedModelText(256)} [${settings.activeServiceId}]"
        return mapOf<Any, String>(SELECTED_SECTION to selected) +
            catalog.contextSections(conversation?.contextSnapshots?.value.orEmpty())
    }

    /** Full context for a new model session; later calls to contextUpdate() send only changes. */
    fun contextText(): String {
        val sections = contextSections()
        sentContext = sections
        return modelContextText(sections.values, CATALOG_CONTEXT_BUDGET, CATALOG_CONTEXT_BUDGET)
    }

    // TODO: This commits sentContext when called, not when the text reaches the model. Callers
    // queue the result while a turn is active, so later calls diff against undelivered text and
    // PendingVoiceContext accumulates superseded sections. See the TODO on PendingVoiceContext.

    /** Sections changed since the last send, or empty. Removed sections are not model content. */
    fun contextUpdate(): String {
        val sections = contextSections()
        val changed = sections.filter { (key, text) -> sentContext[key] != text }
        sentContext = sections
        return modelContextText(changed.values, CATALOG_CONTEXT_BUDGET, CATALOG_CONTEXT_BUDGET)
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
                        current.lifetime.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            entry.frontendPreparation.setVoiceSession(current.lifetime.sessionID) {
                                conversation === current && isCurrent()
                            }
                            try {
                                awaitCancellation()
                            } finally {
                                entry.frontendPreparation.clearVoiceSession(current.lifetime.sessionID)
                            }
                        }
                        AdmittedIdentity(entry, entry.bearerStore.identityEpoch.value, cookieFor(key.url))
                    }
                if (identity.authorization == null) identity.authorization = catalog.authorization(key)
            }
        }
    }

    private suspend fun awaitFrontendContext(current: Conversation) {
        // Host context is advisory. Bound a stalled publisher without blocking
        // independent voice transport indefinitely; lifetime cancellation still wins.
        val prepared =
            withTimeoutOrNull(5_000) {
                for (identity in current.admitted.values.toList()) {
                    identity.entry.frontendPreparation.awaitVoiceSessionPrepared(current.lifetime.sessionID)
                }
                true
            }
        if (prepared == null) Log.w("GoModeVoice", "Hosted voice context publication timed out")
        requireCurrent()
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
    var contextSnapshots: StateFlow<Map<ServiceShellKey, ServiceMonitorState>>? = null
    val admitted = mutableMapOf<ServiceShellKey, AdmittedIdentity>()
    val credentials = mutableMapOf<String, String?>()
    var setup: VoiceSetup? = null
}

private fun com.fghbuild.gomode.service.ServiceMonitorState?.contextReady(): Boolean =
    this?.resources != null && snapshot?.resources?.values?.all { it != null } == true
