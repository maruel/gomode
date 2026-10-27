// Emulator regressions for conversation authorization scoped to resolved MCP endpoints across page and catalog replacement.
package com.fghbuild.gomode.voice

import android.net.Uri
import android.webkit.CookieManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.completeFixtureFrontend
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceReadiness
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.ServiceBootstrapState
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.WebShellLoadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAuthorizationTest {
    @Test
    fun endpointScopedCookieReplacementIsRejectedBeforeCatalogCollectorsRun() = endpointScopedCookieReplacement(false)

    @Test
    fun endpointScopedCookieReplacementIsRejectedAfterCatalogRepreparation() = endpointScopedCookieReplacement(true)

    @Test
    fun resolvedEndpointReplacementIsRejectedBeforeNewSchemaAdmission() = endpointScopedCookieReplacement(false, true)

    @Test
    fun localEndpointCookieReplacementIsRejectedBeforeCatalogCollectorsRun() =
        endpointScopedCookieReplacement(false, local = true)

    @Test
    fun localEndpointCookieReplacementIsRejectedAfterCatalogRepreparation() =
        endpointScopedCookieReplacement(true, local = true)

    @Test
    fun localResolvedEndpointReplacementIsRejectedBeforeNewSchemaAdmission() =
        endpointScopedCookieReplacement(false, true, local = true)

    @Test
    fun delayedFrontendCannotLeaveContributingMcpIdentityUnpinned() =
        endpointScopedCookieReplacement(false, delayedFrontend = true)

    private fun endpointScopedCookieReplacement(
        waitForRepreparation: Boolean,
        replaceEndpoint: Boolean = false,
        local: Boolean = false,
        delayedFrontend: Boolean = false,
    ) = runTest {
        MockWebServer().use { server ->
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        check(request.requestUrl?.encodedPath?.startsWith("/api/") == true)
                        val rpc = JSONObject(request.body.readUtf8())
                        val result =
                            when (rpc.getString("method")) {
                                "server/discover" -> {
                                    """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}},
                                "serverInfo":{"name":"fixture","version":"1"},"instructions":"Account facts"}"""
                                }

                                "tools/list" -> {
                                    """{"tools":[]}"""
                                }

                                else -> {
                                    error("Unexpected MCP request")
                                }
                            }
                        val complete =
                            JSONObject(result)
                                .put("resultType", "complete")
                                .put("ttlMs", 60_000)
                                .put("cacheScope", "private")
                        return MockResponse().setBody(
                            """{"jsonrpc":"2.0","id":${rpc.get("id")},"result":$complete}""",
                        )
                    }
                }
            server.start()
            val repository = SettingsRepository(MemoryPreferences())
            val participantID = repository.addService("Participant", server.url("/").toString())
            val sponsorID = repository.addService("Sponsor", "https://endpoint-sponsor.invalid")
            if (local) {
                repository.updateVoiceMode(VoiceMode.DEVICE)
                repository.updateOnDeviceEngine(OnDeviceEngine.LOCAL_LLM)
            }
            repository.settings.first {
                it.activeServiceId == sponsorID && (
                    !local ||
                        (it.voiceMode == VoiceMode.DEVICE && it.onDeviceEngine == OnDeviceEngine.LOCAL_LLM)
                )
            }
            val entries =
                repository.settings.value.services.associate { service ->
                    val key = ServiceShellKey(service.id, service.url)
                    key to ServiceShellSession(key) {}
                }
            val sponsor = entries.values.single { it.key.serviceID == sponsorID }
            val participant = entries.values.single { it.key.serviceID == participantID }
            authenticate(sponsor, "sponsor")
            participant.updateBootstrap(
                ServiceBootstrapState.Ready(
                    Json.decodeFromString(
                        com.fghbuild.gomode.sdk.v1.Settings
                            .serializer(),
                        """{"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":2,
                            "toolGroups":[{"name":"fixture","endpoint":"/api/mcp", "protocolVersion":"2026-07-28",
                            "authRequired":true}],"voiceGateway":{"required":false}}}""",
                    ),
                ),
            )
            val cookies = CookieManager.getInstance()
            cookies.setCookie(participant.key.url, "endpoint_account=original; Path=/api")
            assertFalse(cookies.getCookie(participant.key.url).orEmpty().contains("endpoint_account="))
            val catalog = ServiceCatalog(backgroundScope)
            val owner = VoiceConversation(repository, ServiceSettingsClient(), catalog) { entries }

            fun settle() {
                participant.updateLoad(WebShellLoadState.Ready)
                participant.bearerStore.onNavigation(participant.key.url)
                check(
                    participant.bearerStore.receive(
                        Uri.parse(participant.key.url),
                        true,
                        participant.key.url,
                        """{"authChanged":true,"nativeAccess":true}""",
                    ),
                )
                completeFixtureFrontend(participant)
            }

            try {
                settle()
                catalog.reconcile(entries, emptyMap())
                catalog.state.first {
                    val service = it[participant.key]
                    check(service?.error == null) { service?.error.orEmpty() }
                    service?.readiness == ServiceReadiness.READY
                }
                if (delayedFrontend) participant.invalidateIdentity()
                owner.begin(false)
                if (delayedFrontend) {
                    assertFalse(participant.isFrontendReady)
                    assertTrue(owner.contextText().contains(participantID))
                    val discovery =
                        checkNotNull(
                            owner.execute(
                                "discover_service",
                                JsonObject(mapOf("server_id" to JsonPrimitive(participantID))),
                                "conversation",
                                "en-US",
                            ) { true },
                        )
                    assertFalse(discovery.isError)
                    assertTrue(discovery.structuredContent.toString().contains("Account facts"))
                    val requestCount = server.requestCount
                    participant.bearerStore.receive(
                        Uri.parse(participant.key.url),
                        true,
                        participant.key.url,
                        """{"bearerToken":"replacement-account"}""",
                    )
                    completeFixtureFrontend(participant)
                    assertFalse(owner.isCurrent())
                    assertTrue(runCatching { owner.contextText() }.exceptionOrNull() is VoiceSetupException)
                    assertEquals(requestCount, server.requestCount)
                    return@runTest
                }
                val args = JsonObject(mapOf("server_id" to JsonPrimitive(participantID)))
                assertFalse(
                    checkNotNull(owner.execute("discover_service", args, "conversation", "en-US") { true }).isError,
                )
                participant.pageUnavailable("Renderer unavailable")
                catalog.state.first { it[participant.key]?.readiness == ServiceReadiness.UNAVAILABLE }
                if (replaceEndpoint) {
                    val bootstrap = participant.state.value.bootstrap as ServiceBootstrapState.Ready
                    val settings = bootstrap.settings
                    participant.updateBootstrap(
                        ServiceBootstrapState.Ready(
                            settings.copy(
                                webShell =
                                    settings.webShell.copy(
                                        toolGroups =
                                            settings.webShell.toolGroups.map {
                                                it.copy(endpoint = "/api/other")
                                            },
                                    ),
                            ),
                        ),
                    )
                } else {
                    cookies.setCookie(participant.key.url, "endpoint_account=replacement; Path=/api")
                }
                assertTrue(owner.isCurrent())
                val epoch = participant.bearerStore.identityEpoch.value
                settle()
                assertEquals(epoch, participant.bearerStore.identityEpoch.value)
                if (waitForRepreparation) {
                    catalog.state.first {
                        val service = it[participant.key]
                        if (service?.readiness == ServiceReadiness.UNAVAILABLE) return@first false
                        check(service?.error == null) { service?.error.orEmpty() }
                        service?.readiness == ServiceReadiness.READY
                    }
                }
                assertFalse(owner.isCurrent())
                val requestCount = server.requestCount
                assertTrue(runCatching { owner.contextText() }.exceptionOrNull() is VoiceSetupException)
                assertTrue(
                    runCatching { owner.execute("discover_service", args, "conversation", "en-US") { true } }
                        .exceptionOrNull() is VoiceSetupException,
                )
                assertEquals(requestCount, server.requestCount)
            } finally {
                owner.finish()
                catalog.close()
                entries.values.forEach { it.close() }
                cookies.setCookie(participant.key.url, "endpoint_account=; Max-Age=0; Path=/api")
            }
        }
    }

    private fun authenticate(
        entry: ServiceShellSession,
        token: String,
    ) {
        entry.bearerStore.onNavigation(entry.key.url)
        check(entry.bearerStore.receive(Uri.parse(entry.key.url), true, entry.key.url, """{"bearerToken":"$token"}"""))
        completeFixtureFrontend(entry)
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val writeMutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            writeMutex.withLock { transform(state.value).also { state.value = it } }
    }
}
