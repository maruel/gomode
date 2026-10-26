// Tests gateway sponsorship, setup/recovery pinning, and synchronous multi-server identity admission.
package com.fghbuild.gomode.voice

import android.net.Uri
import android.webkit.CookieManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.service.ServiceCatalog
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.ui.web.ServiceShellKey
import com.fghbuild.gomode.ui.web.ServiceShellSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class VoiceConversationTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun revokedCallAuthorityCannotStartOrRecoverWithAReadyPage() =
        runTest {
            val repository = SettingsRepository(MemoryPreferences())
            val id = repository.addService("Server", "https://example.test")
            repository.settings.first { it.activeServiceId == id }
            val key = ServiceShellKey(id, "https://example.test")
            val entry = ServiceShellSession(key) {}.also { authenticate(it, "initial") }
            val catalog = ServiceCatalog(backgroundScope)
            var authorized = true
            val conversation =
                VoiceConversation(repository, ServiceSettingsClient(), catalog, { authorized }) {
                    mapOf(key to entry)
                }
            try {
                conversation.begin(false)
                assertTrue(conversation.isCurrent())
                authorized = false
                assertFalse(conversation.isCurrent())
                org.junit.Assert.assertThrows(VoiceSetupException::class.java) { conversation.begin(true) }
                org.junit.Assert.assertThrows(VoiceSetupException::class.java) { conversation.begin(false) }
            } finally {
                conversation.finish()
                entry.close()
                catalog.close()
            }
        }

    @Test
    fun selectionDuringSetupAndRecoveryKeepsSponsorAndResolvedVoice() =
        runTest {
            val choicesStarted = CountDownLatch(1)
            val releaseChoices = CountDownLatch(1)
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.requestUrl?.encodedPath) {
                                "/.well-known/gomode.json" -> {
                                    MockResponse().setBody(
                                        """{"service":"fixture","apiVersion":1,"webShell":{"bridgeVersion":1,
                        "toolGroups":[],"voiceGateway":{"url":"/","required":false,"authRequired":false}}}""",
                                    )
                                }

                                "/api/voicegateway/v1/voice/choices" -> {
                                    choicesStarted.countDown()
                                    check(releaseChoices.await(10, TimeUnit.SECONDS))
                                    MockResponse().setBody(
                                        """{"choices":[{"id":"live","label":"Live","transports":["webrtc"],
                            "voices":["Orus","Kore"],"defaultVoice":"Orus"}],"defaultChoiceID":"live"}""",
                                    )
                                }

                                else -> {
                                    MockResponse().setResponseCode(500)
                                }
                            }
                    }
                server.start()
                val repository = SettingsRepository(MemoryPreferences())
                val destinationID = repository.addService("Tools only", "https://tools.invalid")
                val sponsorID = repository.addService("Gateway sponsor", server.url("/").toString())
                repository.settings.first { it.activeServiceId == sponsorID }
                val entries =
                    repository.settings.value.services.associate { service ->
                        val key = ServiceShellKey(service.id, service.url)
                        key to ServiceShellSession(key) {}.also { authenticate(it, "initial") }
                    }
                val catalog = ServiceCatalog(backgroundScope)
                val owner = VoiceConversation(repository, ServiceSettingsClient(), catalog) { entries }
                try {
                    owner.begin(false)
                    val preparing = async(Dispatchers.IO) { owner.prepare() }
                    assertTrue(choicesStarted.await(5, TimeUnit.SECONDS))
                    repository.switchService(destinationID)
                    repository.settings.first { it.activeServiceId == destinationID }
                    releaseChoices.countDown()
                    val setup = preparing.await()
                    assertEquals(server.url("/").toString().trimEnd('/'), setup.gatewayEndpointURL)
                    assertEquals("live", setup.choiceID)
                    assertEquals("Orus", setup.voiceName)
                    assertTrue(setup.tools.any { it.name == "dispatch_service_tool" })
                    assertTrue(setup.tools.none { it.name == "product_alias" })
                    assertTrue(setup.serviceContextText.contains(destinationID))
                    repository.updateSpeakingVoice(setup.gatewayEndpointURL, "live", "Kore")
                    owner.begin(true)
                    val recovery = owner.prepare()
                    assertEquals("Orus", recovery.voiceName)
                    assertEquals(setup.gatewayEndpointURL, recovery.gatewayEndpointURL)
                    assertEquals(2, server.requestCount)
                } finally {
                    releaseChoices.countDown()
                    owner.finish()
                    entries.values.forEach { it.close() }
                    catalog.close()
                }
            }
        }

    @Test
    fun unavailablePagesAndParticipantLogoutDoNotReplaceGatewayButNewAccountsAreRejectedWithoutYield() =
        runTest {
            val repository = SettingsRepository(MemoryPreferences())
            repository.addService("Participant", "https://participant.invalid")
            val sponsorID = repository.addService("Sponsor", "https://sponsor.invalid")
            repository.settings.first { it.activeServiceId == sponsorID }
            val entries =
                repository.settings.value.services.associate { service ->
                    val key = ServiceShellKey(service.id, service.url)
                    key to ServiceShellSession(key) {}.also { authenticate(it, service.id) }
                }
            val sponsor = entries.values.single { it.key.serviceID == sponsorID }
            val participant = entries.values.single { it !== sponsor }
            val catalog = ServiceCatalog(backgroundScope)
            val owner = VoiceConversation(repository, ServiceSettingsClient(), catalog) { entries }
            try {
                owner.begin(false)
                sponsor.pageUnavailable("Renderer unavailable")
                assertTrue(owner.isCurrent())
                assertEquals(sponsorID, owner.tokenFor(sponsor.key.url))
                participant.bearerStore.receive(
                    Uri.parse(participant.key.url),
                    true,
                    participant.key.url,
                    """{"bearerToken":null}""",
                )
                assertTrue(owner.isCurrent())
                authenticate(participant, "replacement-account")
                assertFalse(owner.isCurrent())
                assertTrue(runCatching { owner.contextText() }.exceptionOrNull() is VoiceSetupException)
                assertTrue(
                    runCatching {
                        owner.execute(
                            "discover_service",
                            JsonObject(emptyMap()),
                            "conversation",
                            "en-US",
                        ) { true }
                    }.exceptionOrNull() is VoiceSetupException,
                )
            } finally {
                owner.finish()
                entries.values.forEach { it.close() }
                catalog.close()
            }
        }

    @Test
    fun returningCookieParticipantMustKeepItsAdmittedIdentityWhileInitialReadinessIsAllowed() =
        runTest {
            val repository = SettingsRepository(MemoryPreferences())
            repository.addService("Participant", "https://cookie-participant.invalid")
            val sponsorID = repository.addService("Sponsor", "https://cookie-sponsor.invalid")
            repository.settings.first { it.activeServiceId == sponsorID }
            val entries =
                repository.settings.value.services.associate { service ->
                    val key = ServiceShellKey(service.id, service.url)
                    key to ServiceShellSession(key) {}
                }
            val sponsor = entries.values.single { it.key.serviceID == sponsorID }
            val participant = entries.values.single { it !== sponsor }
            authenticate(sponsor, "sponsor")
            val catalog = ServiceCatalog(backgroundScope)
            val owner = VoiceConversation(repository, ServiceSettingsClient(), catalog) { entries }
            val cookies = CookieManager.getInstance()

            fun settleParticipant() {
                participant.bearerStore.onNavigation(participant.key.url)
                check(
                    participant.bearerStore.receive(
                        Uri.parse(participant.key.url),
                        true,
                        participant.key.url,
                        """{"authChanged":true,"nativeAccess":true}""",
                    ),
                )
            }

            try {
                owner.begin(false)
                cookies.setCookie(participant.key.url, "session=original; path=/")
                settleParticipant()
                assertTrue(owner.isCurrent())
                owner.contextText() // First readiness admits a participant absent when the call began.
                participant.pageUnavailable("Renderer unavailable")
                assertTrue(owner.isCurrent())
                settleParticipant()
                assertTrue(owner.isCurrent()) // Same-account readiness survives page replacement.
                participant.pageUnavailable("Renderer unavailable")
                cookies.setCookie(participant.key.url, "session=replacement; path=/")
                assertTrue(owner.isCurrent()) // An unavailable participant alone does not end the call.
                settleParticipant()
                assertFalse(owner.isCurrent())
                assertTrue(runCatching { owner.contextText() }.exceptionOrNull() is VoiceSetupException)
                assertTrue(
                    runCatching {
                        owner.execute(
                            "discover_service",
                            JsonObject(emptyMap()),
                            "conversation",
                            "en-US",
                        ) { true }
                    }.exceptionOrNull() is VoiceSetupException,
                )
            } finally {
                owner.finish()
                entries.values.forEach { it.close() }
                catalog.close()
                cookies.setCookie(participant.key.url, "session=; Max-Age=0; path=/")
            }
        }

    @Test
    fun explicitSponsorRevocationEndsAuthorityImmediately() =
        runTest {
            val repository = SettingsRepository(MemoryPreferences())
            val id = repository.addService("Sponsor", "https://revocation.invalid")
            repository.settings.first { it.activeServiceId == id }
            val key = ServiceShellKey(id, repository.settings.value.activeServiceURL)
            val entry = ServiceShellSession(key) {}
            authenticate(entry, "original-account")
            val catalog = ServiceCatalog(backgroundScope)
            val owner = VoiceConversation(repository, ServiceSettingsClient(), catalog) { mapOf(key to entry) }
            try {
                owner.begin(false)
                entry.bearerStore.receive(Uri.parse(key.url), true, key.url, """{"bearerToken":null}""")
                assertFalse(owner.isCurrent())
                assertTrue(runCatching { owner.tokenFor(key.url) }.exceptionOrNull() is VoiceSetupException)
            } finally {
                owner.finish()
                entry.close()
                catalog.close()
            }
        }

    @Test
    fun localConversationRejectsReplacementAccountsBeforeAdmission() =
        runTest {
            val repository = SettingsRepository(MemoryPreferences())
            val a = repository.addService("A", "https://local-a.invalid")
            val b = repository.addService("B", "https://local-b.invalid")
            repository.updateVoiceMode(VoiceMode.DEVICE)
            repository.updateOnDeviceEngine(OnDeviceEngine.LOCAL_LLM)
            repository.settings.first {
                it.onDeviceEngine == OnDeviceEngine.LOCAL_LLM &&
                    it.voiceMode == VoiceMode.DEVICE
            }
            val entries =
                repository.settings.value.services.associate { service ->
                    val key = ServiceShellKey(service.id, service.url)
                    key to ServiceShellSession(key) {}.also { authenticate(it, "initial") }
                }
            val catalog = ServiceCatalog(backgroundScope)
            val owner = VoiceConversation(repository, ServiceSettingsClient(), catalog) { entries }
            try {
                val setup = owner.prepareLocal().setup
                assertEquals(b, setup.serverID)
                assertEquals("B", setup.serverName)
                assertEquals(null, setup.mcpClient)
                assertTrue(setup.tools.any { it.name == "dispatch_service_tool" })
                repository.switchService(a)
                repository.settings.first { it.activeServiceId == a }
                assertTrue(owner.contextText().contains(a))
                repository.switchService(b)
                repository.settings.first { it.activeServiceId == b }
                assertTrue(owner.contextText().contains(b))
                val participant = entries.values.first { it.key.serviceID == b }
                participant.bearerStore.receive(
                    Uri.parse(participant.key.url),
                    true,
                    participant.key.url,
                    """{"bearerToken":null}""",
                )
                assertTrue(owner.isCurrent())
                authenticate(participant, "replacement")
                assertFalse(owner.isCurrent())
                assertTrue(runCatching { owner.contextText() }.exceptionOrNull() is VoiceSetupException)
            } finally {
                owner.finish()
                catalog.close()
                entries.values.forEach { it.close() }
            }
        }

    @Test
    fun finishedLocalPreparationRejectsContextAndToolAdmission() =
        runTest {
            val repository = SettingsRepository(MemoryPreferences())
            repository.updateVoiceMode(VoiceMode.DEVICE)
            repository.updateOnDeviceEngine(OnDeviceEngine.LOCAL_LLM)
            repository.settings.first { it.onDeviceEngine == OnDeviceEngine.LOCAL_LLM }
            val catalog = ServiceCatalog(backgroundScope)
            val owner = VoiceConversation(repository, ServiceSettingsClient(), catalog) { emptyMap() }
            try {
                val prepared = owner.prepareLocal()
                assertTrue(prepared.lifetime.isCurrent)
                owner.finish()
                assertFalse(prepared.lifetime.isCurrent)
                assertFalse(owner.isCurrent())
                assertTrue(runCatching { owner.contextText() }.exceptionOrNull() is VoiceSetupException)
                assertTrue(
                    runCatching {
                        owner.execute("list_services", JsonObject(emptyMap()), "conversation", "en-US") { true }
                    }.exceptionOrNull() is VoiceSetupException,
                )
            } finally {
                owner.finish()
                catalog.close()
            }
        }

    private fun authenticate(
        entry: ServiceShellSession,
        token: String,
    ) {
        entry.bearerStore.onNavigation(entry.key.url)
        check(entry.bearerStore.receive(Uri.parse(entry.key.url), true, entry.key.url, """{"bearerToken":"$token"}"""))
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val writeMutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            writeMutex.withLock { transform(state.value).also { state.value = it } }
    }
}
