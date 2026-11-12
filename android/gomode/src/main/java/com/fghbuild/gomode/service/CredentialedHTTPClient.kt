// Provides an HTTP client that keeps manually supplied service credentials on the original request.
package com.fghbuild.gomode.service

import okhttp3.OkHttpClient

internal val credentialedHTTPClient: OkHttpClient =
    OkHttpClient
        .Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

// A proxy or server restarting answers 5xx; 408 and 429 ask the client to retry later.
internal fun isTransientHTTPStatus(status: Int): Boolean = status == 408 || status == 429 || status in 500..599
