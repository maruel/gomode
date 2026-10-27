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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.fghbuild.gomode.ui.ServiceBootstrapState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun WebShellScreen(
    voiceConnected: Boolean,
    visible: Boolean = true,
    showWhenLocked: Boolean = false,
    reloadToken: Int,
    serviceSession: ServiceShellSession,
    onAuthInvalidated: () -> Unit,
    onSharedAuthInvalidated: () -> Unit = {},
) {
    val context = LocalContext.current
    val page =
        remember(serviceSession) {
            serviceSession.page(context)
        }
    val attachment = remember(page, context) { Any() }
    val mediaPermissions = remember(context) { WebMediaPermissions(context) }
    val mediaPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            mediaPermissions.onPermissionsResult()
        }
    val fileChooserLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = ActivityResultContracts.GetMultipleContents().parseResult(result.resultCode, result.data)
            page.completeFileChooser(attachment, uris.toTypedArray())
        }
    androidx.compose.runtime.SideEffect {
        page.attach(
            attachment,
            context,
            mediaPermissions,
            onAuthInvalidated,
            onSharedAuthInvalidated,
            { mediaPermissionLauncher.launch(it) },
            { fileChooserLauncher.launch(it) },
        )
    }
    DisposableEffect(page, context) {
        onDispose { page.detach(attachment) }
    }
    val visibilityScope = rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleResumeEffect(page, visible, showWhenLocked) {
        val visibilityJob =
            visibilityScope.launch {
                // Keyguard dismissal can settle after the Activity resumes.
                while (isActive && visible) {
                    page.setHostVisible(
                        attachment,
                        visible &&
                            (
                                showWhenLocked ||
                                    !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                            ),
                    )
                    delay(250)
                }
            }
        onPauseOrDispose {
            visibilityJob.cancel()
            page.setHostVisible(attachment, false)
        }
    }
    LaunchedEffect(page, reloadToken) { page.load(reloadToken) }
    LaunchedEffect(page, voiceConnected) { page.setVoiceConnected(voiceConnected) }
    BackHandler(enabled = visible && !page.rendererLost) { page.goBack() }
    if (!visible || page.rendererLost) return
    Box(Modifier.fillMaxSize().statusBarsPadding().testTag("gomode-web-shell")) {
        AndroidView(
            factory = { page.webView.apply { (parent as? ViewGroup)?.removeView(this) } },
            modifier = Modifier.fillMaxSize(),
        )
        if (page.loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).testTag("gomode-web-loading"))
        }
    }
}

// One actual page survives Activity replacement. Only an attached visible shell can
// launch native prompts; background car tools use the same document without pixels.
@SuppressLint("SetJavaScriptEnabled")
internal class ServiceWebPage(
    context: Context,
    private val serviceSession: ServiceShellSession,
) {
    private val applicationContext = context.applicationContext
    private val pageContext = android.content.MutableContextWrapper(applicationContext)
    private val context: Context get() = pageContext.baseContext
    private val scope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
        )
    private val initialURL = serviceSession.key.url
    private val hostURL = goModeHostURL(initialURL)
    private val hostBridge = GoModeHostBridge()
    private val bearerStore = serviceSession.bearerStore
    private val frontendTools = serviceSession.frontendTools
    private val pageGeneration = serviceSession.openPage()
    private var closed = false
    private var visibility: Boolean? = null
    private val currentVisible get() = visibility == true
    private val canLaunchNativeUI get() =
        currentVisible &&
            !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
    private var nativeInteractionAllowed = false
    var rendererLost by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    private var loadFailed = false
    private var automaticRetryState = AutomaticTimeoutRetryState()
        set(value) {
            field = value
            if (value.pending) scheduleRetry()
        }
    private var recoveryLoadInProgress = false
    private var appliedReloadToken: Int? = null
    var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var mediaPermissions = WebMediaPermissions(applicationContext)
    private var attachment: Any? = null
    private var onAuthInvalidated: () -> Unit = {}
    private var onSharedAuthInvalidated: () -> Unit = {}
    private var launchMediaPermissions: (Array<String>) -> Unit = { mediaPermissions.onNavigation() }
    private var launchFileChooser: (
        Intent,
    ) -> Unit = {
        fileChooserCallback?.onReceiveValue(emptyArray())
        fileChooserCallback =
            null
    }
    private val authHandler =
        ServiceAuthMessageHandler(bearerStore, {
            serviceSession.onSharedIdentityChanged()
            onSharedAuthInvalidated()
        }) {
            serviceSession.invalidateIdentity()
            onAuthInvalidated()
        }
    val webView =
        WebView(pageContext).apply {
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
                WebViewCompat.addDocumentStartJavaScript(
                    this,
                    """
                    if (window === window.top) {
                      Object.defineProperty(window, 'goModeFrontendDocument', {value: new Promise(resolve => {
                        gomodeFrontendDocument.onmessage = event => resolve(JSON.parse(event.data).document);
                        gomodeFrontendDocument.postMessage('begin');
                      })});
                    }
                    """.trimIndent(),
                    setOf(serviceOrigin(initialURL)),
                )
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(
                    this,
                    "gomodeFrontendDocument",
                    setOf(serviceOrigin(initialURL)),
                ) { view, message, sourceOrigin, isMainFrame, reply ->
                    if (message.data == "begin" && serviceSession.isCurrentPage(pageGeneration) &&
                        acceptsFrontendVoiceTools(sourceOrigin, isMainFrame, view.url, initialURL)
                    ) {
                        // This document-start message, unlike onPageStarted, cannot
                        // arrive after this document's auth/preparation messages.
                        serviceSession.invalidatePageAuthentication()
                        authHandler.onNavigation(view.url)
                        frontendTools.clear(view)
                        reply.postMessage(
                            org.json
                                .JSONObject()
                                .put(
                                    "document",
                                    serviceSession.frontendPreparation.document,
                                ).toString(),
                        )
                    }
                }
                WebViewCompat.addWebMessageListener(
                    this,
                    "gomodeFrontendReadiness",
                    setOf(serviceOrigin(initialURL)),
                ) { view, message, sourceOrigin, isMainFrame, _ ->
                    if (serviceSession.isCurrentPage(pageGeneration) &&
                        acceptsFrontendVoiceTools(sourceOrigin, isMainFrame, view.url, initialURL) &&
                        bearerStore.authReady.value
                    ) {
                        runCatching {
                            val payload = org.json.JSONObject(message.data ?: return@runCatching)
                            if (!serviceSession.frontendPreparation.accepts(payload)) return@runCatching
                            // The ready signal carries the complete tool snapshot, including [].
                            val tools = payload.getJSONArray("tools")
                            val ready = payload.getBoolean("ready")
                            require(ready || tools.length() == 0) { "Unprepared frontend must not publish tools" }
                            if (ready) frontendTools.register(view, tools.toString()) else frontendTools.clear(view)
                            serviceSession.frontendPreparation.update(payload)
                        }.onFailure {
                            serviceSession.frontendPreparation.invalidateIdentity()
                            frontendTools.clear(view)
                            Log.e(TAG, "Invalid frontend preparation", it)
                        }
                    }
                }
                WebViewCompat.addWebMessageListener(
                    this,
                    "gomodeAuth",
                    setOf(serviceOrigin(initialURL)),
                ) { view, message, sourceOrigin, isMainFrame, reply ->
                    if (!serviceSession.isCurrentPage(pageGeneration)) return@addWebMessageListener
                    message.data?.let { payload ->
                        val parsed = runCatching { org.json.JSONObject(payload) }.getOrNull()
                        if (parsed == null || !serviceSession.frontendPreparation.acceptsAuth(parsed)) {
                            return@let
                        }
                        if (authHandler.receive(sourceOrigin, isMainFrame, view.url, payload) &&
                            bearerStore.authReady.value
                        ) {
                            // A new preparation owner also revokes tools when its
                            // bearer is unchanged and auth has no transition callback.
                            frontendTools.clear(view)
                            serviceSession.frontendPreparation.acknowledge(parsed)?.let(reply::postMessage)
                        }
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
                        if (!canLaunchNativeUI && !isTrustedPermissionOrigin(request.url, initialURL)) {
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
                        onAuthInvalidated()
                        return true
                    }

                    override fun onPageStarted(
                        view: WebView,
                        url: String?,
                        favicon: Bitmap?,
                    ) {
                        if (!serviceSession.isCurrentPage(pageGeneration)) return

                        // Untrusted documents have no document-start handshake. Use
                        // the current URL so a delayed callback cannot revoke a
                        // replacement trusted document's accepted identity.
                        val currentURL = view.url
                        if (currentURL?.let { isTrustedPermissionOrigin(it.toUri(), initialURL) } != true) {
                            serviceSession.invalidatePageAuthentication()
                            authHandler.onNavigation(currentURL)
                            frontendTools.clear(view)
                        }

                        if (!canLaunchNativeUI &&
                            url?.let { isTrustedPermissionOrigin(it.toUri(), initialURL) } != true
                        ) {
                            serviceSession.requireAttention()
                            view.stopLoading()
                        }
                        mediaPermissions.onNavigation()

                        automaticRetryState = automaticTimeoutRetryStateOnPageStarted(automaticRetryState)
                        loadFailed = false
                        loading = true
                        serviceSession.updateLoad(
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

                        val currentURL = view.url
                        if (currentURL?.let { isTrustedPermissionOrigin(it.toUri(), initialURL) } != true) {
                            serviceSession.invalidatePageAuthentication()
                            authHandler.onNavigation(currentURL)
                            frontendTools.clear(view)
                        }

                        if (!loadFailed) {
                            automaticRetryState = automaticRetryState.copy(attempts = 0)
                            recoveryLoadInProgress = false
                            serviceSession.updateLoad(WebShellLoadState.Ready)
                        }
                        loading = automaticRetryState.pending
                        if (serviceSession.state.value.bootstrap is ServiceBootstrapState.Unvalidated) {
                            serviceSession.reloadBootstrap()
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (!request.isForMainFrame || !serviceSession.isCurrentPage(pageGeneration)) {
                            return
                        }

                        serviceSession.invalidatePageAuthentication()
                        authHandler.onNavigation(null)
                        frontendTools.clear(view)

                        loadFailed = true
                        if (shouldAutomaticallyRetryWebLoadError(error.errorCode, automaticRetryState.attempts)) {
                            automaticRetryState =
                                automaticRetryState.copy(
                                    attempts = automaticRetryState.attempts + 1,
                                    pending = true,
                                )
                            recoveryLoadInProgress = true
                            loading = true
                            serviceSession.updateLoad(WebShellLoadState.Reconnecting)
                        } else {
                            val message = webLoadErrorMessage(error.errorCode)
                            recoveryLoadInProgress = false
                            loading = false
                            serviceSession.updateLoad(WebShellLoadState.Failed(message))
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView,
                        handler: android.webkit.SslErrorHandler,
                        error: android.net.http.SslError,
                    ) {
                        handler.cancel()
                        if (!serviceSession.isCurrentPage(pageGeneration) ||
                            (view.url != null && error.url != view.url)
                        ) {
                            return
                        }
                        serviceSession.invalidatePageAuthentication()
                        authHandler.onNavigation(null)
                        frontendTools.clear(view)
                        loadFailed = true
                        automaticRetryState = automaticRetryState.copy(pending = false)
                        recoveryLoadInProgress = false
                        loading = false
                        serviceSession.updateLoad(WebShellLoadState.Failed("Service page certificate is invalid."))
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
                        if (canLaunchNativeUI) return false
                        serviceSession.requireAttention()
                        result.cancel()
                        return true
                    }

                    override fun onPermissionRequest(request: PermissionRequest) {
                        if (!canLaunchNativeUI) {
                            serviceSession.requireAttention()
                            request.deny()
                            return
                        }
                        val permissionOwner = attachment
                        val missing =
                            mediaPermissions.request(request, hostURL) {
                                attachment === permissionOwner && canLaunchNativeUI &&
                                    serviceSession.isCurrentPage(pageGeneration) &&
                                    hostView.url?.toUri()?.let { isTrustedPermissionOrigin(it, hostURL) } == true
                            }
                        if (missing.isNotEmpty()) {
                            launchMediaPermissions(missing)
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
                        if (!canLaunchNativeUI) {
                            serviceSession.requireAttention()
                            filePathCallback.onReceiveValue(emptyArray())
                            return true
                        }
                        fileChooserCallback?.onReceiveValue(emptyArray())
                        fileChooserCallback = filePathCallback
                        launchFileChooser(
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
                        if (!canLaunchNativeUI) {
                            serviceSession.requireAttention()
                            return false
                        }
                        return openNewWindowInExternalBrowser(view, resultMsg) { uri ->
                            if (canLaunchNativeUI) openExternalBrowser(context, uri) else false
                        }
                    }
                }
        }

    init {
        serviceSession.ownPage(::close)
        setVisible(false)
    }

    fun attach(
        owner: Any,
        context: Context,
        hostMediaPermissions: WebMediaPermissions,
        authInvalidated: () -> Unit,
        sharedAuthInvalidated: () -> Unit,
        mediaLauncher: (Array<String>) -> Unit,
        fileLauncher: (Intent) -> Unit,
    ) {
        if (attachment !== owner) setVisible(false)
        if (mediaPermissions !== hostMediaPermissions) mediaPermissions.onNavigation()
        attachment = owner
        mediaPermissions = hostMediaPermissions
        pageContext.baseContext = context
        onAuthInvalidated = authInvalidated
        onSharedAuthInvalidated = sharedAuthInvalidated
        launchMediaPermissions = mediaLauncher
        launchFileChooser = fileLauncher
    }

    fun detach(expectedOwner: Any) {
        if (attachment !== expectedOwner) return
        setVisible(false)
        (webView.parent as? ViewGroup)?.removeView(webView)
        pageContext.baseContext = applicationContext
        attachment = null
        mediaPermissions = WebMediaPermissions(applicationContext)
        onAuthInvalidated = {}
        onSharedAuthInvalidated = {}
        launchMediaPermissions = { mediaPermissions.onNavigation() }
        launchFileChooser = {
            fileChooserCallback?.onReceiveValue(emptyArray())
            fileChooserCallback = null
        }
    }

    fun completeFileChooser(
        owner: Any,
        uris: Array<Uri>,
    ) {
        if (attachment !== owner) return
        fileChooserCallback?.onReceiveValue(if (canLaunchNativeUI) uris else emptyArray())
        fileChooserCallback = null
    }

    fun setHostVisible(
        owner: Any,
        visible: Boolean,
    ) {
        if (attachment === owner) setVisible(visible)
    }

    private fun setVisible(visible: Boolean) {
        val nativeAllowed =
            visible && !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        if (closed || rendererLost || (visibility == visible && nativeInteractionAllowed == nativeAllowed)) return
        visibility = visible
        nativeInteractionAllowed = nativeAllowed
        hostBridge.setPageVisible(visible)
        hostBridge.setMediaAllowed(nativeAllowed)
        webView.isFocusable = visible
        webView.isFocusableInTouchMode = visible
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            WebViewCompat.setAudioMuted(webView, !visible)
        } else if (!visible) {
            rendererLost = true
            serviceSession.pageUnavailable("This WebView cannot isolate hidden servers. Update Android System WebView.")
            return
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(
                webView.settings,
                if (nativeAllowed) {
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
                } else {
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE
                },
            )
        }
        webView.evaluateJavascript("window.dispatchEvent(new Event('gomodevisibilitychange'))", null)
        if (!nativeAllowed) {
            mediaPermissions.onNavigation()
            fileChooserCallback?.onReceiveValue(emptyArray())
            fileChooserCallback = null
        }
        if (visible) {
            serviceSession.acknowledgeAttention()
            webView.onResume()
        } else {
            webView.clearFocus()
            webView.onPause()
        }
    }

    fun load(reloadToken: Int) {
        if (closed || rendererLost) return
        val state = serviceSession.state.value
        // The initial load may see a navigation queued before its caller recomposes.
        // Apply that command's token once, together with its destination.
        val token = if (state.navigationURL != null) state.webReloadToken else reloadToken
        if (token == appliedReloadToken) return
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
            serviceSession.updateLoad(WebShellLoadState.Reconnecting)
            serviceSession.reloadPage {
                if (destination == null) webView.reload() else webView.loadUrl(destination)
            }
        }
    }

    fun setVoiceConnected(connected: Boolean) {
        if (closed || rendererLost) return
        GoModeHostBridge.setVoiceConnected(connected)
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('gomodevoicechange'))", null)
    }

    private fun scheduleRetry() {
        scope.launch {
            if (rendererLost || !automaticRetryState.pending) return@launch
            delay(AUTOMATIC_TIMEOUT_RETRY_DELAY_MILLIS)
            if (automaticRetryState.pending) {
                automaticRetryState = automaticRetryState.copy(pending = false, inProgress = true)
                serviceSession.reloadPage(webView::reload)
            }
        }
    }

    fun goBack() {
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

    fun close() {
        closed = true
        mediaPermissions.onNavigation()
        fileChooserCallback?.onReceiveValue(emptyArray())
        fileChooserCallback = null
        scope.cancel()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
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

// Stop captures when a page is hidden or the phone locks, including requests resolving after that change.
// Showing service content over keyguard does not authorize capture or native permission prompts.
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
          if (!goModeHost.isMediaAllowed()) track.stop();
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
        if (!goModeHost.isMediaAllowed()) throw new DOMException('Unlock and select this server to capture media', 'NotAllowedError');
        const stream = await original(constraints);
        stream.getTracks().forEach(track => owner.own(track));
        if (!goModeHost.isMediaAllowed()) throw new DOMException('Media capture is unavailable', 'NotAllowedError');
        return stream;
      };
      const updateVisibility = () => {
        if (!goModeHost.isMediaAllowed()) {
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

    @Volatile
    private var mediaAllowed = false

    fun setMediaAllowed(allowed: Boolean) {
        mediaAllowed = allowed
    }

    @JavascriptInterface
    fun isMediaAllowed(): Boolean = mediaAllowed

    fun setPageVisible(visible: Boolean) {
        pageVisible = visible
    }

    @JavascriptInterface
    fun isPageVisible(): Boolean = pageVisible

    @JavascriptInterface
    @Suppress("FunctionOnlyReturningConstant")
    fun shellVersion(): String = "2"

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
