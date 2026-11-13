// Fetches a host-issued scoped authorization block for standalone voice gateways.
package com.fghbuild.gomode.service

import com.fghbuild.voicegateway.sdk.v2.ServiceAuthorization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request

class VoiceTokenException(
    message: String,
) : Exception(message)

class VoiceTokenClient {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(
        endpointURL: String,
        cookie: String?,
        bearerToken: String?,
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
                    throw VoiceTokenException("Voice token request failed: HTTP ${response.code}")
                }
                val body = response.body?.string() ?: throw VoiceTokenException("Voice token response is empty")
                json.decodeFromString(ServiceAuthorization.serializer(), body)
            }
        }
}
