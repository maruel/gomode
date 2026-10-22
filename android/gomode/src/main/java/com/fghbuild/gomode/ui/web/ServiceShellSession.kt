// Owns saved-service authentication, navigation, page generations, bootstrap, load, and frontend tools.
package com.fghbuild.gomode.ui.web

import androidx.core.net.toUri
import com.fghbuild.gomode.service.resolveNotificationURL
import com.fghbuild.gomode.ui.ServiceBootstrapState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ServiceShellKey(
    val serviceID: String,
    val url: String,
)

internal data class ServiceShellState(
    val bootstrap: ServiceBootstrapState = ServiceBootstrapState.Unvalidated(),
    val load: WebShellLoadState = WebShellLoadState.Loading,
    val bootstrapReloadToken: Int = 0,
    val webReloadToken: Int = 0,
    val webViewGeneration: Long = 0,
    val pageRequested: Boolean = false,
    val navigationURL: String? = null,
    val attentionRequired: Boolean = false,
)

// Each saved service owns its entry. Entries do not create isolated cookie profiles:
// services on the same origin share WebView cookies.
internal class ServiceShellSession(
    val key: ServiceShellKey,
) {
    val bearerStore = ServiceBearerStore(key.url)
    val frontendTools = FrontendVoiceTools { active && bearerStore.authReady.value }
    private val mutableState = MutableStateFlow(ServiceShellState())
    val state = mutableState.asStateFlow()
    private var active = true
    private var pageGeneration = 0L
    private var releasePage: (() -> Unit)? = null

    fun ownPage(release: () -> Unit) {
        releasePage = release
    }

    fun requireAttention() {
        mutableState.update { it.copy(attentionRequired = true) }
    }

    fun acknowledgeAttention() {
        mutableState.update { it.copy(attentionRequired = false) }
    }

    fun invalidateSharedIdentity() {
        bearerStore.invalidateIdentity(accountChanged = true)
        frontendTools.invalidate()
        requireAttention()
        updateLoad(WebShellLoadState.Failed("Shared service account changed. Reload this service to sign in again."))
    }

    fun rendererLost() {
        pageUnavailable("Service page stopped. Reload this page to reconnect.", canRetry = true)
    }

    fun pageUnavailable(
        message: String,
        canRetry: Boolean = false,
    ) {
        bearerStore.invalidateIdentity()
        frontendTools.invalidate()
        requireAttention()
        closePage(pageGeneration)
        updateLoad(WebShellLoadState.Failed(message, canRetry = canRetry, recreatePage = true))
        val release = releasePage
        releasePage = null
        release?.invoke()
    }

    fun updateBootstrap(state: ServiceBootstrapState) {
        mutableState.update { it.copy(bootstrap = state) }
    }

    fun updateLoad(state: WebShellLoadState) {
        mutableState.update { it.copy(load = state) }
    }

    fun reloadBootstrap() {
        mutableState.update { it.copy(bootstrapReloadToken = it.bootstrapReloadToken + 1) }
    }

    fun requestPage() {
        if (active) mutableState.update { it.copy(pageRequested = true) }
    }

    fun navigate(url: String) {
        if (!active) return
        val target = requireNotNull(resolveNotificationURL(key.url, url)) { "Destination is outside this service" }
        invalidatePageAuthentication()
        mutableState.update {
            it.copy(navigationURL = target, webReloadToken = it.webReloadToken + 1, pageRequested = true)
        }
    }

    fun consumeNavigationURL(): String? {
        val url = mutableState.value.navigationURL
        mutableState.update { it.copy(navigationURL = null) }
        return url
    }

    fun reloadWeb() {
        if (!active || (mutableState.value.load as? WebShellLoadState.Failed)?.canRetry == false) return
        invalidatePageAuthentication()
        mutableState.update { state ->
            val failure = state.load as? WebShellLoadState.Failed
            if (failure?.canRetry == false) return@update state
            if (failure?.recreatePage == true) {
                state.copy(
                    load = WebShellLoadState.Reconnecting,
                    attentionRequired = false,
                    webViewGeneration = state.webViewGeneration + 1,
                    pageRequested = true,
                )
            } else {
                state.copy(
                    load = WebShellLoadState.Reconnecting,
                    webReloadToken = state.webReloadToken + 1,
                    pageRequested = true,
                )
            }
        }
    }

    // Every replacement document must validate access independently of the account pinned by voice.
    fun invalidatePageAuthentication() {
        bearerStore.invalidateIdentity()
        frontendTools.invalidate()
    }

    fun reloadPage(reload: () -> Unit) {
        if (!active) return
        invalidatePageAuthentication()
        reload()
    }

    fun openPage(): Long {
        frontendTools.clear()
        pageGeneration++
        return pageGeneration
    }

    fun isCurrentPage(generation: Long): Boolean = active && pageGeneration == generation

    fun closePage(generation: Long) {
        if (!isCurrentPage(generation)) return
        pageGeneration++
        frontendTools.clear()
        val release = releasePage
        releasePage = null
        release?.invoke()
    }

    fun invalidateIdentity() {
        frontendTools.invalidate()
    }

    fun close() {
        active = false
        pageGeneration++
        bearerStore.deactivate()
        frontendTools.clear()
        val release = releasePage
        releasePage = null
        release?.invoke()
    }
}

// Activity-owned entries share cookies by origin, but never native authorization or page ownership.
internal class ServiceShellRegistry {
    private val entries = linkedMapOf<ServiceShellKey, ServiceShellSession>()

    fun reconcile(keys: Set<ServiceShellKey>): Map<ServiceShellKey, ServiceShellSession> {
        entries.keys.filter { it !in keys }.forEach { entries.remove(it)?.close() }
        keys.forEach { entries.getOrPut(it) { ServiceShellSession(it) } }
        return entries.toMap()
    }

    fun invalidateSharedOrigin(source: ServiceShellSession) {
        entries.values
            .filter { it !== source && isTrustedPermissionOrigin(it.key.url.toUri(), source.key.url) }
            .forEach { it.invalidateSharedIdentity() }
    }

    fun close() {
        entries.values.forEach { it.close() }
        entries.clear()
    }
}
