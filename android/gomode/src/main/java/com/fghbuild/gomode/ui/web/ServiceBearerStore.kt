// Holds hosted-service auth signals in memory for native requests to the active service origin.
package com.fghbuild.gomode.ui.web

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal class ServiceBearerStore(
    private val serviceURL: String,
) {
    private var active = true
    private var trustedPageActive = false
    private var cookieIdentitySettled = false

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()
    private val _authEpoch = MutableStateFlow(0L)
    val authEpoch: StateFlow<Long> = _authEpoch.asStateFlow()
    private val _authReady = MutableStateFlow(false)
    val authReady: StateFlow<Boolean> = _authReady.asStateFlow()

    private val _identityEpoch = MutableStateFlow(0L)
    val identityEpoch: StateFlow<Long> = _identityEpoch.asStateFlow()
    private var lastBearer: String? = null

    fun invalidateIdentity(accountChanged: Boolean = false) {
        if (accountChanged) _identityEpoch.value++
        trustedPageActive = false
        _token.value = null
        _authReady.value = false
        _authEpoch.value += 1
    }

    fun deactivate() {
        lastBearer = null
        active = false
        trustedPageActive = false
        _token.value = null
        _authReady.value = false
    }

    fun onNavigation(pageURL: String?) {
        if (!active) return
        val wasTrusted = trustedPageActive
        cookieIdentitySettled = false
        trustedPageActive = pageURL?.let { trustedPage(it, serviceURL) } == true
        if (!trustedPageActive) {
            _token.value = null
            _authReady.value = false
            if (wasTrusted) _authEpoch.value += 1
        }
    }

    fun receive(
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        currentPageURL: String?,
        payload: String,
    ): Boolean {
        if (!active || !trustedPageActive || !isMainFrame) return false
        if (!isTrustedPermissionOrigin(sourceOrigin, serviceURL)) return false
        if (currentPageURL?.let { trustedPage(it, serviceURL) } != true) return false
        val message = runCatching { JSONObject(payload) }.getOrNull() ?: return false
        if (message.opt("authChanging") == true && !message.has("authChanged") &&
            !message.has("nativeAccess") && !message.has("bearerToken")
        ) {
            _authReady.value = false
            _token.value = null
            cookieIdentitySettled = false
            _identityEpoch.value++
            _authEpoch.value += 1
            return true
        }
        if (message.opt("authChanged") == true && !message.has("authChanging") && !message.has("bearerToken") &&
            message.opt("nativeAccess") is Boolean
        ) {
            val nativeAccess = message.getBoolean("nativeAccess")
            if (cookieIdentitySettled || !nativeAccess) {
                _identityEpoch.value++
            }
            cookieIdentitySettled = true
            _token.value = null
            _authReady.value = nativeAccess
            _authEpoch.value += 1
            return true
        }
        if (!message.has("bearerToken") || message.has("authChanging") || message.has("authChanged") ||
            message.has("nativeAccess")
        ) {
            return false
        }
        val value = message.opt("bearerToken")
        val token =
            when (value) {
                JSONObject.NULL -> {
                    null
                }

                is String -> {
                    value.takeIf {
                        it.isNotBlank() && it.length <= MAX_TOKEN_LENGTH &&
                            it.none(::isUnsafeHeaderChar)
                    }
                        ?: return false
                }

                else -> {
                    return false
                }
            }
        if ((lastBearer != null && token != lastBearer) || (token == null && _authReady.value)) {
            _identityEpoch.value++
        }
        if (token != null) lastBearer = token
        val ready = token != null
        // StateFlow can conflate logout followed by the same token; authority must change before observers run.
        if (_token.value != token || _authReady.value != ready) _authEpoch.value += 1
        _token.value = token
        _authReady.value = ready
        return true
    }

    fun tokenFor(endpointURL: String): String? {
        if (!active || !trustedPageActive || !_authReady.value) return null
        if (!trustedPage(endpointURL, serviceURL)) return null
        return _token.value
    }
}

// Invokes native invalidation in the same WebView callback that accepts an auth change.
internal class ServiceAuthMessageHandler(
    private val store: ServiceBearerStore,
    private val onIdentityChanged: () -> Unit = {},
    private val onInvalidated: () -> Unit,
) {
    fun onNavigation(url: String?) {
        val priorEpoch = store.authEpoch.value
        store.onNavigation(url)
        if (store.authEpoch.value != priorEpoch) onInvalidated()
    }

    fun receive(
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        currentPageURL: String?,
        payload: String,
    ): Boolean {
        val priorEpoch = store.authEpoch.value
        val priorToken = store.token.value
        val priorIdentityEpoch = store.identityEpoch.value
        val priorReady = store.authReady.value
        val accepted = store.receive(sourceOrigin, isMainFrame, currentPageURL, payload)
        if (store.identityEpoch.value != priorIdentityEpoch) {
            onIdentityChanged()
        }
        if (store.authEpoch.value != priorEpoch || store.token.value != priorToken ||
            store.authReady.value != priorReady
        ) {
            onInvalidated()
        }
        return accepted
    }
}

private const val MAX_TOKEN_LENGTH = 8192

private fun isUnsafeHeaderChar(char: Char): Boolean = char == '\r' || char == '\n'

private fun trustedPage(
    pageURL: String,
    serviceURL: String,
): Boolean = runCatching { isTrustedPermissionOrigin(pageURL.toUri(), serviceURL) }.getOrDefault(false)
