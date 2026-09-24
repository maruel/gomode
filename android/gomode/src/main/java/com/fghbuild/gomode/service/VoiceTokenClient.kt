// Fetches a host-issued scoped authorization block for standalone voice gateways.
package com.fghbuild.gomode.service

import com.caic.voicegateway.sdk.v1.ServiceAuthorization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.IOException

class VoiceTokenClient {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(
        endpointURL: String,
        cookie: String?,
        bearerToken: String? = null,
    ): ServiceAuthorization =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(endpointURL)
                    .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
                    .apply { if (!bearerToken.isNullOrBlank()) header("Authorization", "Bearer $bearerToken") }
                    .build()
            credentialedHTTPClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Voice token request failed: HTTP ${response.code}")
                }
                val body = response.body?.string() ?: throw IOException("Voice token response is empty")
                json.decodeFromString(ServiceAuthorization.serializer(), body)
            }
        }
}
