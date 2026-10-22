// WebView shell with host state, origin-scoped auth, and frontend voice tools.
package com.fghbuild.gomode.ui.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fghbuild.gomode.R
import com.fghbuild.gomode.service.serviceOrigin
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebShellScreen(
    serviceID: String,
    initialURL: String,
    voiceConnected: Boolean,
    visible: Boolean = true,
    reloadToken: Int,
    serviceSession: ServiceShellSession,
    onLoadStateChanged: (WebShellLoadState) -> Unit,
    onHostedPageLoaded: () -> Unit = {},
    onAuthInvalidated: () -> Unit,
    onSharedAuthInvalidated: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentVisible by rememberUpdatedState(visible)
    val hostBridge = remember(serviceSession) { GoModeHostBridge() }
    hostBridge.setPageVisible(visible)
    var rendererLost by remember(serviceSession) { mutableStateOf(false) }
    val bearerStore = serviceSession.bearerStore
    val frontendTools = serviceSession.frontendTools
    val pageGeneration = remember(serviceSession) { serviceSession.openPage() }
    val hostURL = remember(initialURL) { goModeHostURL(initialURL) }
    var loading by remember(hostURL) { mutableStateOf(true) }
    var loadFailed by remember(hostURL) { mutableStateOf(false) }
    var automaticRetryState by remember(hostURL) { mutableStateOf(AutomaticTimeoutRetryState()) }
    var recoveryLoadInProgress by remember(hostURL) { mutableStateOf(false) }
    var appliedReloadToken by remember(hostURL) { mutableStateOf<Int?>(null) }
    var fileChooserCallback by remember(hostURL) { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val currentHostURL by rememberUpdatedState(hostURL)
    val currentServiceID by rememberUpdatedState(serviceID)
    val currentOnLoadStateChanged by rememberUpdatedState(onLoadStateChanged)
    val currentOnHostedPageLoaded by rememberUpdatedState(onHostedPageLoaded)
    val currentOnAuthInvalidated = rememberUpdatedState(onAuthInvalidated)
    val currentOnSharedAuthInvalidated = rememberUpdatedState(onSharedAuthInvalidated)
    val authHandler =
        remember(serviceSession) {
            ServiceAuthMessageHandler(bearerStore, { currentOnSharedAuthInvalidated.value() }) {
                serviceSession.invalidateIdentity()
                currentOnAuthInvalidated.value()
            }
        }
    val mediaPermissions = remember(context) { WebMediaPermissions(context) }
    val mediaPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            mediaPermissions.onPermissionsResult()
        }
    val fileChooserLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = ActivityResultContracts.GetMultipleContents().parseResult(result.resultCode, result.data)
            fileChooserCallback?.onReceiveValue(uris.toTypedArray())
            fileChooserCallback = null
        }
    val webView =
        remember(context, serviceID, hostURL) {
            WebView(context).apply {
                id = R.id.web_shell
                // WebView uses native layout params for CSS viewport sizing, even inside AndroidView.
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true
                settings.setSupportMultipleWindows(true)
                enableWebAuthentication()
                addJavascriptInterface(hostBridge, "goModeHost")
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(this, HIDDEN_MEDIA_GUARD, setOf(serviceOrigin(initialURL)))
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(
                        this,
                        "gomodeVoiceTools",
                        setOf(serviceOrigin(initialURL)),
                    ) { view, message, sourceOrigin, isMainFrame, _ ->
                        if (serviceSession.isCurrentPage(pageGeneration) &&
                            acceptsFrontendVoiceTools(sourceOrigin, isMainFrame, view.url, initialURL)
                        ) {
                            message.data?.let { payload ->
                                runCatching { frontendTools.register(view, payload) }
                                    .onFailure {
                                        frontendTools.clear(view)
                                        Log.e(TAG, "Invalid frontend voice tools", it)
                                    }
                            }
                        }
                    }
                    WebViewCompat.addWebMessageListener(
                        this,
                        "gomodeAuth",
                        setOf(serviceOrigin(initialURL)),
                    ) { view, message, sourceOrigin, isMainFrame, _ ->
                        if (!serviceSession.isCurrentPage(pageGeneration)) return@addWebMessageListener
                        message.data?.let { payload ->
                            authHandler.receive(sourceOrigin, isMainFrame, view.url, payload)
                        }
                    }
                } else {
                    Log.w(TAG, "Installed Android WebView does not support service auth messages.")
                }
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            if (!serviceSession.isCurrentPage(pageGeneration)) return true
                            if (!currentVisible && !isTrustedPermissionOrigin(request.url, initialURL)) {
                                serviceSession.requireAttention()
                                return true
                            }
                            if (request.isForMainFrame) {
                                serviceSession.invalidatePageAuthentication()
                                authHandler.onNavigation(request.url.toString())
                            }
                            view.loadUrl(request.url.toString())
                            return true
                        }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail,
                        ): Boolean {
                            if (!serviceSession.isCurrentPage(pageGeneration)) return true
                            rendererLost = true
                            serviceSession.rendererLost()
                            currentOnAuthInvalidated.value()
                            return true
                        }

                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            if (!serviceSession.isCurrentPage(pageGeneration)) return

                            if (!currentVisible &&
                                url?.let { isTrustedPermissionOrigin(it.toUri(), initialURL) } != true
                            ) {
                                serviceSession.requireAttention()
                                view.stopLoading()
                            }
                            serviceSession.invalidatePageAuthentication()
                            authHandler.onNavigation(url)
                            mediaPermissions.onNavigation()
                            frontendTools.clear(view)

                            automaticRetryState = automaticTimeoutRetryStateOnPageStarted(automaticRetryState)
                            loadFailed = false
                            loading = true
                            currentOnLoadStateChanged(
                                if (recoveryLoadInProgress) {
                                    WebShellLoadState.Reconnecting
                                } else {
                                    WebShellLoadState.Loading
                                },
                            )
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            if (!serviceSession.isCurrentPage(pageGeneration)) return

                            if (!loadFailed) {
                                automaticRetryState = automaticRetryState.copy(attempts = 0)
                                recoveryLoadInProgress = false
                                currentOnLoadStateChanged(WebShellLoadState.Ready)
                            }
                            loading = automaticRetryState.pending
                            currentOnHostedPageLoaded()
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (!request.isForMainFrame || !serviceSession.isCurrentPage(pageGeneration)) {
                                return
                            }

                            loadFailed = true
                            if (shouldAutomaticallyRetryWebLoadError(error.errorCode, automaticRetryState.attempts)) {
                                automaticRetryState =
                                    automaticRetryState.copy(
                                        attempts = automaticRetryState.attempts + 1,
                                        pending = true,
                                    )
                                recoveryLoadInProgress = true
                                loading = true
                                currentOnLoadStateChanged(WebShellLoadState.Reconnecting)
                            } else {
                                val message = webLoadErrorMessage(error.errorCode)
                                recoveryLoadInProgress = false
                                loading = false
                                currentOnLoadStateChanged(WebShellLoadState.Failed(message))
                            }
                        }
                    }
                val hostView = this
                webChromeClient =
                    object : WebChromeClient() {
                        override fun onJsAlert(
                            view: WebView,
                            url: String,
                            message: String,
                            result: JsResult,
                        ): Boolean = rejectHiddenDialog(result)

                        override fun onJsConfirm(
                            view: WebView,
                            url: String,
                            message: String,
                            result: JsResult,
                        ): Boolean = rejectHiddenDialog(result)

                        override fun onJsBeforeUnload(
                            view: WebView,
                            url: String,
                            message: String,
                            result: JsResult,
                        ): Boolean = rejectHiddenDialog(result)

                        override fun onJsPrompt(
                            view: WebView,
                            url: String,
                            message: String,
                            defaultValue: String,
                            result: JsPromptResult,
                        ): Boolean = rejectHiddenDialog(result)

                        private fun rejectHiddenDialog(result: JsResult): Boolean {
                            if (currentVisible) return false
                            serviceSession.requireAttention()
                            result.cancel()
                            return true
                        }

                        override fun onPermissionRequest(request: PermissionRequest) {
                            if (!currentVisible) {
                                serviceSession.requireAttention()
                                request.deny()
                                return
                            }
                            val missing =
                                mediaPermissions.request(request, hostURL) {
                                    currentVisible && currentHostURL == hostURL && currentServiceID == serviceID &&
                                        hostView.url?.toUri()?.let { isTrustedPermissionOrigin(it, hostURL) } == true
                                }
                            if (missing.isNotEmpty()) {
                                mediaPermissionLauncher.launch(missing)
                            }
                        }

                        override fun onPermissionRequestCanceled(request: PermissionRequest) {
                            mediaPermissions.cancel(request)
                        }

                        override fun onShowFileChooser(
                            webView: WebView,
                            filePathCallback: ValueCallback<Array<Uri>>,
                            fileChooserParams: FileChooserParams,
                        ): Boolean {
                            if (!currentVisible) {
                                serviceSession.requireAttention()
                                filePathCallback.onReceiveValue(emptyArray())
                                return true
                            }
                            fileChooserCallback?.onReceiveValue(emptyArray())
                            fileChooserCallback = filePathCallback
                            fileChooserLauncher.launch(
                                webFileChooserIntent(
                                    fileChooserParams.acceptTypes,
                                    fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
                                ),
                            )
                            return true
                        }

                        override fun onCreateWindow(
                            view: WebView,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message,
                        ): Boolean {
                            if (!currentVisible) {
                                serviceSession.requireAttention()
                                return false
                            }
                            return openNewWindowInExternalBrowser(view, resultMsg) { uri ->
                                if (currentVisible) openExternalBrowser(context, uri) else false
                            }
                        }
                    }
            }
        }

    LaunchedEffect(visible, webView) {
        if (rendererLost) return@LaunchedEffect
        webView.isFocusable = visible
        webView.isFocusableInTouchMode = visible
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            WebViewCompat.setAudioMuted(webView, !visible)
        } else if (!visible) {
            rendererLost = true
            serviceSession.pageUnavailable("This WebView cannot isolate hidden servers. Update Android System WebView.")
            return@LaunchedEffect
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(
                webView.settings,
                if (visible) {
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
                } else {
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE
                },
            )
        }
        webView.evaluateJavascript("window.dispatchEvent(new Event('gomodevisibilitychange'))", null)
        if (visible) {
            serviceSession.acknowledgeAttention()
            webView.onResume()
        } else {
            mediaPermissions.onNavigation()
            fileChooserCallback?.onReceiveValue(emptyArray())
            fileChooserCallback = null
            webView.clearFocus()
            webView.onPause()
        }
    }

    LaunchedEffect(webView, reloadToken) {
        if (rendererLost) return@LaunchedEffect
        val state = serviceSession.state.value
        // The initial load may see a navigation queued before its caller recomposes.
        // Apply that command's token once, together with its destination.
        val token = if (state.navigationURL != null) state.webReloadToken else reloadToken
        if (token == appliedReloadToken) return@LaunchedEffect
        val initialLoad = appliedReloadToken == null
        appliedReloadToken = token
        val destination = serviceSession.consumeNavigationURL()?.let(::goModeHostURL)
        if (initialLoad) {
            webView.loadUrl(destination ?: hostURL)
        } else {
            automaticRetryState = AutomaticTimeoutRetryState()
            recoveryLoadInProgress = true
            loadFailed = false
            loading = true
            currentOnLoadStateChanged(WebShellLoadState.Reconnecting)
            serviceSession.reloadPage {
                if (destination == null) webView.reload() else webView.loadUrl(destination)
            }
        }
    }

    LaunchedEffect(voiceConnected, webView) {
        if (rendererLost) return@LaunchedEffect
        GoModeHostBridge.setVoiceConnected(voiceConnected)
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('gomodevoicechange'))",
            null,
        )
    }

    LaunchedEffect(hostURL, automaticRetryState.pending) {
        if (rendererLost || !automaticRetryState.pending) return@LaunchedEffect
        delay(AUTOMATIC_TIMEOUT_RETRY_DELAY_MILLIS)
        if (automaticRetryState.pending) {
            automaticRetryState = automaticRetryState.copy(pending = false, inProgress = true)
            serviceSession.reloadPage(webView::reload)
        }
    }

    // The hosted frontend owns in-page routes, so Android Back must ask the SPA
    // router to move before falling back to WebView's document-level history.
    BackHandler(enabled = visible && !rendererLost) {
        webView.evaluateJavascript(
            """
            const before = window.location.pathname;
            if (before !== "/") {
              window.history.back();
              window.setTimeout(() => {
                if (window.location.pathname === before) {
                  // Some SPA transitions do not leave a WebView history entry.
                  // Force the root route and notify routers listening for popstate.
                  window.history.pushState(null, "", "/");
                  window.dispatchEvent(new PopStateEvent("popstate"));
                }
              }, 100);
              true;
            } else {
              false;
            }
            """.trimIndent(),
        ) { handled ->
            // If the SPA was already at its root, use normal WebView back
            // navigation for any full-page loads that may be in history.
            if (handled != "true" && webView.canGoBack()) {
                webView.goBack()
            }
        }
    }

    DisposableEffect(webView) {
        var destroyed = false
        val release = {
            if (!destroyed) {
                destroyed = true
                mediaPermissions.onNavigation()
                fileChooserCallback?.onReceiveValue(emptyArray())
                fileChooserCallback = null
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
        serviceSession.ownPage(release)
        onDispose {
            mediaPermissions.onNavigation()
            if (serviceSession.isCurrentPage(pageGeneration)) {
                authHandler.onNavigation(null)
                serviceSession.closePage(pageGeneration)
            }
            release()
        }
    }

    if (!visible || rendererLost) return

    Box(Modifier.fillMaxSize().statusBarsPadding().testTag("gomode-web-shell")) {
        key(serviceID, hostURL) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).testTag("gomode-web-loading"),
            )
        }
    }
}

internal fun webFileChooserIntent(
    acceptTypes: Array<String>,
    allowMultiple: Boolean,
): Intent {
    val mimeTypes =
        acceptTypes
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map { type ->
                if (type.startsWith('.')) {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1)) ?: "*/*"
                } else {
                    type
                }
            }.distinct()
    return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        // Always use the document browser, including for images stored in Screenshots or Downloads.
        type = "*/*"
        if (mimeTypes.isNotEmpty() && "*/*" !in mimeTypes) {
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        }
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
    }
}

private fun WebView.enableWebAuthentication() {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
        WebSettingsCompat.setWebAuthenticationSupport(
            settings,
            WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP,
        )
    } else {
        Log.w(TAG, "Installed Android WebView does not support WebAuthn.")
    }
}

internal fun acceptsFrontendVoiceTools(
    sourceOrigin: Uri,
    isMainFrame: Boolean,
    pageURL: String?,
    serviceURL: String,
): Boolean =
    isMainFrame && isTrustedPermissionOrigin(sourceOrigin, serviceURL) &&
        pageURL?.let { isTrustedPermissionOrigin(it.toUri(), serviceURL) } == true

internal fun isTrustedPermissionOrigin(
    origin: Uri,
    serviceURL: String,
): Boolean {
    val trusted = serviceURL.toUri()
    val scheme = origin.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    if (scheme != trusted.scheme?.lowercase()) return false
    if (origin.userInfo != null || trusted.userInfo != null) return false
    if (origin.host.isNullOrBlank() || !origin.host.equals(trusted.host, ignoreCase = true)) return false
    val defaultPort = if (scheme == "https") 443 else 80
    val originPort = origin.port.takeIf { it != -1 } ?: defaultPort
    val trustedPort = trusted.port.takeIf { it != -1 } ?: defaultPort
    return originPort == trustedPort
}

internal sealed interface WebShellLoadState {
    data object Loading : WebShellLoadState

    data object Reconnecting : WebShellLoadState

    data object Ready : WebShellLoadState

    data class Failed(
        val message: String,
        val canRetry: Boolean = true,
        val recreatePage: Boolean = false,
    ) : WebShellLoadState
}

internal data class AutomaticTimeoutRetryState(
    val attempts: Int = 0,
    val pending: Boolean = false,
    val inProgress: Boolean = false,
)

internal fun automaticTimeoutRetryStateOnPageStarted(state: AutomaticTimeoutRetryState): AutomaticTimeoutRetryState =
    if (state.inProgress) {
        state.copy(inProgress = false)
    } else {
        AutomaticTimeoutRetryState()
    }

internal fun shouldAutomaticallyRetryWebLoadError(
    errorCode: Int,
    retryAttempts: Int,
): Boolean = errorCode == WebViewClient.ERROR_TIMEOUT && retryAttempts == 0

internal fun webLoadErrorMessage(errorCode: Int): String =
    if (errorCode == WebViewClient.ERROR_TIMEOUT) {
        "The service took too long to respond. Check your network connection, then retry."
    } else {
        "Could not connect to the service. Check your network connection and service address, then retry."
    }

internal fun openNewWindowInExternalBrowser(
    parentView: WebView,
    resultMsg: Message,
    openExternalUri: (Uri) -> Boolean,
): Boolean {
    val hitTestResult = parentView.hitTestResult
    newWindowRequestUriOrNull(hitTestResult.type, hitTestResult.extra)?.let { uri ->
        openExternalUri(uri)
        return false
    }

    val transport = resultMsg.obj as? WebView.WebViewTransport
    if (transport == null) {
        Log.w(TAG, "New window request missing WebView transport.")
        return false
    }

    val popupView =
        WebView(parentView.context).apply {
            webViewClient =
                object : WebViewClient() {
                    private var openedExternalWindow = false

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        openOnce(view, request.url)
                        return true
                    }

                    override fun onPageStarted(
                        view: WebView,
                        url: String?,
                        favicon: Bitmap?,
                    ) {
                        url?.toUri()?.let { openOnce(view, it) }
                    }

                    private fun openOnce(
                        view: WebView,
                        uri: Uri,
                    ) {
                        if (openedExternalWindow) return
                        openedExternalWindow = true
                        openExternalUri(uri)
                        view.destroy()
                    }
                }
        }

    transport.webView = popupView
    resultMsg.sendToTarget()
    return true
}

internal fun newWindowRequestUriOrNull(
    hitTestType: Int,
    extra: String?,
): Uri? {
    if (hitTestType != WebView.HitTestResult.SRC_ANCHOR_TYPE &&
        hitTestType != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
    ) {
        return null
    }
    if (extra.isNullOrBlank()) return null

    return extra.toUri().takeIf { !it.scheme.isNullOrBlank() }
}

internal fun openExternalBrowser(
    context: Context,
    uri: Uri,
): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
        context.startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        Log.w(TAG, "No browser can open URL: $uri", error)
        false
    }
}

private fun goModeHostURL(url: String): String =
    url
        .toUri()
        .buildUpon()
        .appendQueryParameter("goModeHost", "1")
        .build()
        .toString()

// Stop existing captures when a page is hidden, including requests resolving after selection changed.
// Native permission callbacks separately reject hidden requests before any Android UI is launched.
private val HIDDEN_MEDIA_GUARD =
    """
    (() => {
      const media = navigator.mediaDevices;
      if (!media || !media.getUserMedia) return;
      const tracks = new Set();
      const ownersKey = Symbol.for('gomode.capture.owners');
      const owner = {
        own(track) {
          if (!track[ownersKey]) Object.defineProperty(track, ownersKey, {value:new Set()});
          if (track[ownersKey].has(owner)) return;
          track[ownersKey].add(owner);
          tracks.add(track);
          track.addEventListener('ended', () => tracks.delete(track), {once:true});
          if (!goModeHost.isPageVisible()) track.stop();
        },
        release(track) { tracks.delete(track); }
      };
      const stop = MediaStreamTrack.prototype.stop;
      MediaStreamTrack.prototype.stop = function() {
        stop.call(this);
        this[ownersKey]?.forEach(item => item.release(this));
      };
      const cloneTrack = MediaStreamTrack.prototype.clone;
      MediaStreamTrack.prototype.clone = function() {
        const clone = cloneTrack.call(this);
        this[ownersKey]?.forEach(item => item.own(clone));
        return clone;
      };
      const cloneStream = MediaStream.prototype.clone;
      MediaStream.prototype.clone = function() {
        const clone = cloneStream.call(this);
        const owners = new Set(this.getTracks().flatMap(track => [...(track[ownersKey] || [])]));
        owners.forEach(item => clone.getTracks().forEach(track => item.own(track)));
        return clone;
      };
      const original = media.getUserMedia.bind(media);
      media.getUserMedia = async constraints => {
        if (!goModeHost.isPageVisible()) throw new DOMException('Select this server first', 'NotAllowedError');
        const stream = await original(constraints);
        stream.getTracks().forEach(track => owner.own(track));
        if (!goModeHost.isPageVisible()) throw new DOMException('Server is hidden', 'NotAllowedError');
        return stream;
      };
      const updateVisibility = () => {
        if (!goModeHost.isPageVisible()) {
          tracks.forEach(track => track.stop());
          tracks.clear();
        }
        document.querySelectorAll('iframe').forEach(frame => {
          frame.contentWindow?.postMessage('gomodevisibilitychange', '*');
        });
      };
      window.addEventListener('gomodevisibilitychange', updateVisibility);
      window.addEventListener('message', event => {
        if (event.data === 'gomodevisibilitychange') updateVisibility();
      });
    })();
    """.trimIndent()

private const val AUTOMATIC_TIMEOUT_RETRY_DELAY_MILLIS = 1_000L
private const val TAG = "GoModeWebShell"

internal class GoModeHostBridge {
    @Volatile
    private var pageVisible = true

    fun setPageVisible(visible: Boolean) {
        pageVisible = visible
    }

    @JavascriptInterface
    fun isPageVisible(): Boolean = pageVisible

    @JavascriptInterface
    @Suppress("FunctionOnlyReturningConstant")
    fun shellVersion(): String = "1"

    @JavascriptInterface
    fun isVoiceConnected(): Boolean = voiceConnected

    companion object {
        @Volatile
        private var voiceConnected = false

        fun setVoiceConnected(connected: Boolean) {
            voiceConnected = connected
        }
    }
}
