// Provides an HTTP client that keeps manually supplied service credentials on the original request.
package com.fghbuild.gomode.service

import okhttp3.OkHttpClient

internal val credentialedHTTPClient: OkHttpClient =
    OkHttpClient
        .Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
