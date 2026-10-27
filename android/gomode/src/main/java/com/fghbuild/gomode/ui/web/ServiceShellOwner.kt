// Owns retained service pages and their catalog across calls and Activity replacement.
package com.fghbuild.gomode.ui.web

import android.content.Context
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceReadiness
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.ServiceBootstrapState
import com.fghbuild.gomode.ui.fetchBootstrapState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal enum class ServiceCallStage { PAGE, SIGN_IN, TOOLS, FRONTEND }

internal data class ServiceShellSnapshot(
    val settings: SettingsState,
    val entries: Map<ServiceShellKey, ServiceShellSession> = emptyMap(),
)

internal class ServiceShellOwner(
    private val context: Context,
    val settingsRepository: SettingsRepository,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registry = ServiceShellRegistry()
    val catalog = ServiceCatalog(scope)
    private val workers = mutableMapOf<ServiceShellSession, Job>()
    private val mutableState = MutableStateFlow(ServiceShellSnapshot(settingsRepository.settings.value))
    val state = mutableState.asStateFlow()
    val entries: Map<ServiceShellKey, ServiceShellSession> get() = state.value.entries

    private var settingsJob: Job? = null

    // Cold dial construction must not start page/network work before Telecom ownership.
    fun start() {
        if (settingsJob != null) return
        reconcile(settingsRepository.settings.value)
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                settingsRepository.settings.collect { reconcile(it) }
            }
        settingsJob = job
        job.start()
    }

    private fun reconcile(settings: SettingsState) {
        val keys =
            settings.services.map { ServiceShellKey(it.id, it.url) }.toSet() +
                ServiceShellKey(settings.activeServiceId, settings.activeServiceURL)
        val current = registry.reconcile(keys)
        workers.keys.filter { it !in current.values }.forEach { workers.remove(it)?.cancel() }
        current.values.forEach { entry ->
            workers.getOrPut(entry) { scope.launch { maintain(entry) } }
        }
        catalog.reconcile(current, settings.services.associate { it.id to it.label })
        mutableState.value = ServiceShellSnapshot(settings, current)
    }

    private suspend fun maintain(entry: ServiceShellSession) =
        coroutineScope {
            launch {
                entry.state.map { it.bootstrapReloadToken }.distinctUntilChanged().collectLatest {
                    if (entry.key.url.isNotBlank()) {
                        entry.updateBootstrap(ServiceBootstrapState.Unvalidated())
                        entry.updateBootstrap(fetchBootstrapState(entry.key.url, ServiceSettingsClient()))
                    }
                }
            }
            entry.state
                .map { Triple(it.pageRequested, it.webViewGeneration, it.webReloadToken) }
                .distinctUntilChanged()
                .collect { (requested, _, reload) ->
                    if (requested && entry.key.url.isNotBlank()) entry.page(context).load(reload)
                }
        }

    suspend fun prepareCall(
        settings: SettingsState,
        onStage: (ServiceCallStage) -> Unit,
    ): ServiceShellSession {
        onStage(ServiceCallStage.PAGE)
        start()
        val key = ServiceShellKey(settings.activeServiceId, settings.activeServiceURL)
        val current = state.first { key in it.entries }.entries
        val target = current.getValue(key)
        current.values.filter { it.key.url.isNotBlank() }.forEach { it.requestPage() }
        val ready =
            target.state.first {
                it.load == WebShellLoadState.Ready || it.load is WebShellLoadState.Failed ||
                    it.bootstrap is ServiceBootstrapState.Error
            }
        check(
            ready.load == WebShellLoadState.Ready && ready.bootstrap !is ServiceBootstrapState.Error,
        ) { "Service page is unavailable" }
        onStage(ServiceCallStage.SIGN_IN)
        target.bearerStore.authReady.first { it }
        val identity = target.bearerStore.identityEpoch.value
        while (true) {
            onStage(ServiceCallStage.TOOLS)
            combine(catalog.state, target.bearerStore.identityEpoch) { services, epoch ->
                check(epoch == identity) { "Hosted identity changed during service preparation" }
                services[target.key]?.readiness == ServiceReadiness.READY && catalog.isReady(key)
            }.first { it }
            onStage(ServiceCallStage.FRONTEND)
            target.awaitFrontendReady(identity)
            check(entries[key] === target) { "Saved service changed during preparation" }
            if (target.isFrontendReady && catalog.isReady(key)) return target
        }
    }

    fun setVoiceConnected(connected: Boolean) {
        GoModeHostBridge.setVoiceConnected(connected)
        entries.values.forEach { it.setVoiceConnected(connected) }
    }

    override fun close() {
        scope.cancel()
        catalog.close()
        registry.close()
    }
}
