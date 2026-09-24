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
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()
    private val _authEpoch = MutableStateFlow(0L)
    val authEpoch: StateFlow<Long> = _authEpoch.asStateFlow()
    private val _authReady = MutableStateFlow(false)
    val authReady: StateFlow<Boolean> = _authReady.asStateFlow()

    fun deactivate() {
        active = false
        trustedPageActive = false
        _token.value = null
        _authReady.value = false
    }

    fun onNavigation(pageURL: String?) {
        if (!active) return
        val wasTrusted = trustedPageActive
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
            _authEpoch.value += 1
            return true
        }
        if (message.opt("authChanged") == true && !message.has("authChanging") && !message.has("bearerToken") &&
            message.opt("nativeAccess") is Boolean
        ) {
            _token.value = null
            _authReady.value = message.getBoolean("nativeAccess")
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
        _token.value = token
        _authReady.value = token != null
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
        val priorReady = store.authReady.value
        val accepted = store.receive(sourceOrigin, isMainFrame, currentPageURL, payload)
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
