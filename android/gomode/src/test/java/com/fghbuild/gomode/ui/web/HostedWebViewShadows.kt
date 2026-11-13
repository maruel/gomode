// Robolectric WebView shadows that advertise the page-isolation features hosted service pages require.
package com.fghbuild.gomode.ui.web

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

// Robolectric has no Chromium provider; advertise only the page-isolation capabilities.
@Suppress("UtilityClassWithPublicConstructor") // Robolectric constructs shadow instances.
@Implements(WebViewFeature::class)
class HostedWebViewFeatures {
    companion object {
        @JvmStatic
        @Implementation
        fun isFeatureSupported(feature: String): Boolean =
            feature == WebViewFeature.MUTE_AUDIO || feature == WebViewFeature.DOCUMENT_START_SCRIPT
    }
}

@Suppress("UtilityClassWithPublicConstructor") // Robolectric constructs shadow instances.
@Implements(WebViewCompat::class)
class HostedWebViewCompat {
    companion object {
        @JvmStatic
        @Implementation
        @Suppress("UnusedParameter") // Match the platform API; Robolectric has no media renderer.
        fun setAudioMuted(
            view: WebView,
            muted: Boolean,
        ) = Unit

        @JvmStatic
        @Implementation
        @Suppress("UnusedParameter") // Match the platform API; the test invokes page callbacks directly.
        fun addDocumentStartJavaScript(
            view: WebView,
            script: String,
            origins: Set<String>,
        ): ScriptHandler = ScriptHandler {}
    }
}
