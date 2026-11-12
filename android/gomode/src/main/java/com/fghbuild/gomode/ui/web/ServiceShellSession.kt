// Owns saved-service authentication, navigation, page generations, bootstrap, load, and frontend tools.
//
// Each saved service has one entry keyed by service identity and URL. The entry owns in-memory
// authentication, bootstrap and load state, reload counters, and its frontend tool registration.
// Entries and their WebViews live in the application: visited services keep their pages across
// selection changes, native settings, and Activity replacement, preparing voice initializes the
// remaining saved pages, and only the selected page attaches to the screen. Deleting a service or
// replacing its URL releases its view, credentials, and pending frontend calls. Page generations
// reject callbacks from replaced or disposed WebViews.
//
// Hidden pages stay authenticated and can publish frontend declarations. Native permission
// requests, file pickers, JavaScript dialogs, external windows, and cross-origin navigation require
// a selected page in a resumed, unlocked shell; otherwise the entry needs attention. Hidden pages
// are paused individually, cannot take focus, mute audio, and stop hosted camera and microphone
// captures, including cloned and removed tracks. Retention requires WebView audio muting and
// document-start capture-guard support; unsupported WebView versions fail closed with an update
// message. Renderer loss invalidates dispatch and releases every affected view, which may cover
// several services. Native settings shows page failures and offers Reload page, which recreates
// only that page. Manual and automatic retries, reload, and full-document navigation clear page
// access and settle pending frontend calls; SPA history changes preserve access. Catalog access
// resumes after page readiness and freshly validated authentication. Recovery does not replay
// pending tools or restore state after process death.
//
// Frontend tools dispatch only through their owning entry while native access is ready. Not-ready
// signals, preparation-owner replacement, identity changes, page replacement, and failed main-frame
// navigation clear declarations and reject pending results. A host without MCP groups can still
// supply frontend tools once its page confirms native access; the shell invents no MCP endpoint.
// Entries share Android WebView cookies, so services on one origin share a cookie identity:
// account-changing signals synchronously invalidate same-origin peers, while a new page's settled
// cookie readiness establishes only its own access.
package com.fghbuild.gomode.ui.web

import android.content.Context
import androidx.core.net.toUri
import com.fghbuild.gomode.service.resolveNotificationURL
import com.fghbuild.gomode.ui.ServiceBootstrapState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val onSharedIdentityChanged: () -> Unit,
) {
    val bearerStore = ServiceBearerStore(key.url)
    val frontendPreparation = FrontendPreparation()
    val frontendTools = FrontendVoiceTools { isFrontendReady }
    private val mutableState = MutableStateFlow(ServiceShellState())
    val state = mutableState.asStateFlow()
    val isFrontendReady: Boolean
        get() =
            active && bearerStore.authReady.value && frontendPreparation.ready.value &&
                state.value.load == WebShellLoadState.Ready
    private var active = true
    private var automaticRetryAvailable = true
    val canRetryAutomatically: Boolean get() = active && automaticRetryAvailable
    private var pageGeneration = 0L
    private var releasePage: (() -> Unit)? = null
    private var webPage: ServiceWebPage? = null
    private var webPageGeneration = -1L

    suspend fun awaitFrontendReady(identity: Long) {
        combine(state, bearerStore.authReady, frontendPreparation.ready, bearerStore.identityEpoch) { _, _, _, epoch ->
            check(epoch == identity) { "Hosted identity changed during frontend preparation" }
            isFrontendReady
        }.first { it }
    }

    fun page(context: Context): ServiceWebPage {
        val generation = state.value.webViewGeneration
        if (webPageGeneration != generation) {
            releasePage?.invoke()
            webPage = ServiceWebPage(context, this)
            webPageGeneration = generation
        }
        return checkNotNull(webPage)
    }

    fun setVoiceConnected(connected: Boolean) {
        webPage?.setVoiceConnected(connected)
    }

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
        frontendPreparation.invalidateIdentity()
        bearerStore.invalidateIdentity(accountChanged = true)
        frontendTools.clear()
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
        frontendPreparation.navigate()
        bearerStore.invalidateIdentity()
        frontendTools.clear()
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
        resetAutomaticRetryBudget()
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

    fun resetAutomaticRetryBudget() {
        automaticRetryAvailable = true
    }

    fun consumeAutomaticRetry(): Boolean {
        if (!canRetryAutomatically) return false
        automaticRetryAvailable = false
        return true
    }

    fun reloadWeb() = requestReload(automatic = false)

    fun retryWebAutomatically() = requestReload(automatic = true)

    private fun requestReload(automatic: Boolean) {
        if (!active || (mutableState.value.load as? WebShellLoadState.Failed)?.canRetry == false) return
        if (automatic) {
            if (!consumeAutomaticRetry()) return
        } else {
            resetAutomaticRetryBudget()
        }
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
        frontendPreparation.navigate()
        bearerStore.invalidateIdentity()
        frontendTools.clear()
    }

    fun reloadPage(reload: () -> Unit) {
        if (!active) return
        invalidatePageAuthentication()
        reload()
    }

    fun openPage(): Long {
        frontendPreparation.navigate()
        frontendTools.clear()
        pageGeneration++
        return pageGeneration
    }

    fun isCurrentPage(generation: Long): Boolean = active && pageGeneration == generation

    fun closePage(generation: Long) {
        if (!isCurrentPage(generation)) return
        frontendPreparation.navigate()
        pageGeneration++
        frontendTools.clear()
        val release = releasePage
        releasePage = null
        release?.invoke()
    }

    fun invalidateIdentity() {
        frontendPreparation.invalidateIdentity()
        frontendTools.clear()
    }

    fun close() {
        frontendPreparation.navigate()
        active = false
        pageGeneration++
        bearerStore.deactivate()
        frontendTools.clear()
        val release = releasePage
        releasePage = null
        release?.invoke()
    }
}

// Application-owned entries share cookies by origin, but never native authorization or page ownership.
internal class ServiceShellRegistry {
    private val entries = linkedMapOf<ServiceShellKey, ServiceShellSession>()

    fun reconcile(keys: Set<ServiceShellKey>): Map<ServiceShellKey, ServiceShellSession> {
        entries.keys.filter { it !in keys }.forEach { entries.remove(it)?.close() }
        keys.forEach { key ->
            entries.getOrPut(key) {
                ServiceShellSession(key) {
                    entries[key]?.let(::invalidateSharedOrigin)
                }
            }
        }
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
