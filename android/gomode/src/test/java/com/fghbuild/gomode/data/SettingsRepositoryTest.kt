// Unit tests for Go Mode service-instance settings.
package com.fghbuild.gomode.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    // Deadlock guard around inherently-async DataStore reads. StateFlow makes these waits
    // eventually correct, so this only fires on a real hang; keep generous headroom so a
    // cold first read on a saturated CI runner does not flake.
    private val settleTimeoutMs = 10_000L

    // In-memory DataStore for unit tests. This class exercises SettingsRepository logic,
    // not androidx file persistence. The real PreferenceDataStoreFactory reads a temp file
    // and runs a background update loop on top of a file watcher; in CI that loop has
    // repeatedly stopped emitting (the edit completed, yet the data flow produced nothing
    // for the full 10s guard), which flakes these tests nondeterministically.
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val current = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            while (true) {
                val before = current.value
                val after = transform(before)
                if (current.compareAndSet(before, after)) {
                    return after
                }
            }
        }
    }

    private fun createRepo(): SettingsRepository = SettingsRepository(InMemoryPreferencesDataStore())

    // Awaits the first settings state matching [predicate]. On timeout, surfaces what we were
    // waiting for and the last observed StateFlow value so a CI flake is diagnosable.
    private suspend fun SettingsRepository.awaitSettings(
        waitingFor: String,
        predicate: (SettingsState) -> Boolean = { true },
    ): SettingsState =
        try {
            withTimeout(settleTimeoutMs) { settings.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out after ${settleTimeoutMs}ms awaiting $waitingFor; last state = ${settings.value}",
                e,
            )
        }

    @Test
    fun `voice choice survives recreation and stays isolated per gateway`() =
        runBlocking {
            val store = InMemoryPreferencesDataStore()
            val repository = SettingsRepository(store)
            repository.updateVoiceChoice("https://first.example/", "live")
            repository.updateVoiceChoice("https://second.example", "local")
            val restored =
                SettingsRepository(store).awaitSettings("gateway choices restored") {
                    it.voiceChoices.size ==
                        2
                }
            assertEquals("live", restored.voiceChoices["https://first.example"])
            assertEquals("local", restored.voiceChoices["https://second.example"])
        }

    @Test
    fun `unreadable voice choice preferences surface an error instead of a default`() =
        runBlocking {
            val store = InMemoryPreferencesDataStore()
            store.edit { it[stringPreferencesKey("VOICE_CHOICES")] = "invalid JSON" }
            val state =
                SettingsRepository(store).awaitSettings("saved selection read failure") {
                    it.loadState is SettingsLoadState.Failed
                }
            assertTrue(state.loadState is SettingsLoadState.Failed)
            assertTrue((state.loadState as SettingsLoadState.Failed).message.contains("Unable to read saved settings"))
        }

    @Test
    fun `speaking voices restore by gateway and choice with existing preferences intact`() =
        runBlocking {
            val store = InMemoryPreferencesDataStore()
            store.edit { it[stringPreferencesKey("VOICE_CHOICES")] = """{"https://first.example":"live"}""" }
            val repository = SettingsRepository(store)
            repository.awaitSettings("existing preferences") { it.loadState == SettingsLoadState.Ready }
            assertTrue(
                repository.settings.value.speakingVoices
                    .isEmpty(),
            )
            repository.updateSpeakingVoice("https://first.example/", "live", "Kore")
            repository.updateSpeakingVoice("https://first.example", "other", "Orus")
            repository.updateSpeakingVoice("https://second.example", "live", "Puck")
            val restored = SettingsRepository(store).awaitSettings("restored voices") { it.speakingVoices.size == 2 }
            assertEquals("Kore", restored.speakingVoices["https://first.example"]?.get("live"))
            assertEquals("Orus", restored.speakingVoices["https://first.example"]?.get("other"))
            assertEquals("Puck", restored.speakingVoices["https://second.example"]?.get("live"))
            assertEquals("live", restored.voiceChoices["https://first.example"])
        }

    @Test
    fun `unreadable speaking voice does not silently choose a default`() =
        runBlocking {
            val store = InMemoryPreferencesDataStore()
            store.edit { it[stringPreferencesKey("SPEAKING_VOICES")] = "invalid JSON" }
            val state =
                SettingsRepository(
                    store,
                ).awaitSettings("read error") { it.loadState is SettingsLoadState.Failed }
            assertTrue(state.loadState is SettingsLoadState.Failed)
        }

    @Test
    fun `initial settings have no active service`() =
        runBlocking {
            val repo = createRepo()
            val state = repo.awaitSettings("any initial state")

            assertTrue(state.services.isEmpty())
            assertEquals("", state.activeServiceURL)
            assertEquals("", state.activeServiceId)
            assertNull(state.haloAddress)
            assertFalse(state.haloAutoConnect)
        }

    @Test
    fun `Gemma choice defaults to E2B and persists across repository recreation`() =
        runBlocking {
            val store = InMemoryPreferencesDataStore()
            val repo = SettingsRepository(store)
            assertEquals(LocalLlmModel.GEMMA_4_E2B, repo.settings.value.localLlmModel)
            repo.updateLocalLlmModel(LocalLlmModel.GEMMA_4_E4B)
            repo.awaitSettings("E4B selected") { it.localLlmModel == LocalLlmModel.GEMMA_4_E4B }
            val restored = SettingsRepository(store)
            restored.awaitSettings("E4B restored") { it.localLlmModel == LocalLlmModel.GEMMA_4_E4B }
            restored.updateLocalLlmModel(LocalLlmModel.LFM_2_5_1_2B)
            val lfm12Restored = SettingsRepository(store)
            lfm12Restored.awaitSettings("LFM 1.2B restored") { it.localLlmModel == LocalLlmModel.LFM_2_5_1_2B }
            lfm12Restored.updateLocalLlmModel(LocalLlmModel.LFM_2_5_2_6B)
            val lfmRestored = SettingsRepository(store)
            lfmRestored.awaitSettings("LFM restored") { it.localLlmModel == LocalLlmModel.LFM_2_5_2_6B }
            store.edit { it[stringPreferencesKey("LOCAL_LLM_MODEL")] = "unknown" }
            assertEquals(
                LocalLlmModel.GEMMA_4_E2B,
                lfmRestored
                    .awaitSettings("invalid model defaults") { it.localLlmModel == LocalLlmModel.GEMMA_4_E2B }
                    .localLlmModel,
            )
        }

    @Test
    fun `audio output choice defaults to automatic and survives repository recreation`() =
        runBlocking {
            val store = InMemoryPreferencesDataStore()
            val repo = SettingsRepository(store)
            assertNull(repo.awaitSettings("default output").voiceAudioDeviceType)
            // Android's stable speaker and earpiece type values, not a session's transient device IDs.
            repo.updateVoiceAudioDeviceType(2)
            repo.awaitSettings("speaker saved") { it.voiceAudioDeviceType == 2 }
            val restored = SettingsRepository(store)
            restored.awaitSettings("speaker restored") { it.voiceAudioDeviceType == 2 }
            restored.updateVoiceAudioDeviceType(1)
            val reopened = SettingsRepository(store).awaitSettings("earpiece restored") { it.voiceAudioDeviceType == 1 }
            assertEquals(1, reopened.voiceAudioDeviceType)
        }

    @Test
    fun `addService creates active web service`() =
        runBlocking {
            val repo = createRepo()
            val id = repo.addService(label = "Local", url = "http://localhost:2242/")
            val state = repo.awaitSettings("activeServiceURL non-blank") { it.activeServiceURL.isNotBlank() }

            assertEquals(id, state.activeServiceId)
            assertEquals("Local", state.services.single().label)
            assertEquals("web", state.services.single().kind)
            assertEquals("http://localhost:2242", state.activeServiceURL)
        }

    @Test
    fun `updateService edits existing service without switching active service`() =
        runBlocking {
            val repo = createRepo()
            val firstId = repo.addService(label = "Local", url = "http://localhost:2242")
            val secondId = repo.addService(label = "Remote", url = "https://remote.example.com")
            repo.updateService(id = firstId, label = "Home", url = "https://example.com/")
            val state =
                repo.awaitSettings("first service updated") {
                    it.services.firstOrNull()?.url == "https://example.com"
                }

            assertEquals(secondId, state.activeServiceId)
            assertEquals("https://remote.example.com", state.activeServiceURL)
            assertEquals(listOf(firstId, secondId), state.services.map { it.id })
            assertEquals("Home", state.services.first().label)
        }

    @Test
    fun `switchService rejects unknown service id`() =
        runBlocking {
            val repo = createRepo()
            val id = repo.addService(label = "Local", url = "http://localhost:2242")
            assertTrue(runCatching { repo.switchService("missing") }.exceptionOrNull() is IllegalArgumentException)
            val state = repo.awaitSettings("activeServiceId == $id") { it.activeServiceId == id }

            assertEquals(id, state.activeServiceId)
        }

    @Test
    fun `updateHaloAddress stores and clears address`() =
        runBlocking {
            val repo = createRepo()
            repo.updateHaloAddress("AA:BB:CC:DD:EE:FF")
            val stored = repo.awaitSettings("haloAddress != null") { it.haloAddress != null }

            assertEquals("AA:BB:CC:DD:EE:FF", stored.haloAddress)

            repo.updateHaloAddress(null)
            val cleared = repo.awaitSettings("haloAddress == null") { it.haloAddress == null }

            assertNull(cleared.haloAddress)
        }

    @Test
    fun `updateHaloAutoConnect toggles flag`() =
        runBlocking {
            val repo = createRepo()
            repo.updateHaloAutoConnect(true)
            val state = repo.awaitSettings("haloAutoConnect == true") { it.haloAutoConnect }

            assertTrue(state.haloAutoConnect)
        }

    @Test
    fun `normalizeURL trims whitespace and trailing slash`() {
        assertEquals("http://localhost:2242", SettingsRepository.normalizeURL(" http://localhost:2242/ "))
    }

    @Test
    fun `voice language defaults to US English and survives repository recreation`() =
        runBlocking {
            val store = InMemoryPreferencesDataStore()
            val repo = SettingsRepository(store)
            assertEquals("en-US", repo.awaitSettings("default language").voiceLanguageTag)
            repo.updateVoiceLanguageTag(" fr-ca ")
            assertEquals(
                "fr-CA",
                repo.awaitSettings("saved language") { it.voiceLanguageTag == "fr-CA" }.voiceLanguageTag,
            )
            val reopened = SettingsRepository(store)
            assertEquals(
                "fr-CA",
                reopened.awaitSettings("reopened language") { it.voiceLanguageTag == "fr-CA" }.voiceLanguageTag,
            )
        }

    @Test
    fun `voice language rejects invalid and unspecified tags`() {
        listOf("", "und", "en_US").forEach { tag ->
            assertTrue(runCatching { SettingsRepository.normalizeVoiceLanguageTag(tag) }.isFailure)
        }
    }

    @Test
    fun `voice mode defaults to cloud and persists device`() =
        runBlocking {
            val repo = createRepo()
            val initial = repo.awaitSettings("default voice mode")
            assertEquals(VoiceMode.CLOUD, initial.voiceMode)

            repo.updateVoiceMode(VoiceMode.DEVICE)
            val updated = repo.awaitSettings("device voice mode") { it.voiceMode == VoiceMode.DEVICE }

            assertEquals(VoiceMode.DEVICE, updated.voiceMode)
        }

    @Test
    fun `on-device voice engine defaults to remote gateway and persists engine changes`() =
        runBlocking {
            val store = InMemoryPreferencesDataStore()
            val repo = SettingsRepository(store)
            val initial = repo.awaitSettings("default engine")
            assertEquals(OnDeviceEngine.REMOTE_GATEWAY, initial.onDeviceEngine)

            repo.updateOnDeviceEngine(OnDeviceEngine.NEUDECIDE)
            val neudecide =
                repo.awaitSettings("neudecide engine") {
                    it.onDeviceEngine == OnDeviceEngine.NEUDECIDE
                }
            assertEquals(OnDeviceEngine.NEUDECIDE, neudecide.onDeviceEngine)

            val reopened = SettingsRepository(store)
            val reopenedState =
                reopened.awaitSettings("reopened engine") {
                    it.onDeviceEngine == OnDeviceEngine.NEUDECIDE
                }
            assertEquals(OnDeviceEngine.NEUDECIDE, reopenedState.onDeviceEngine)

            repo.updateOnDeviceEngine(OnDeviceEngine.LOCAL_LLM)
            val localLlm =
                repo.awaitSettings("local llm engine") {
                    it.onDeviceEngine == OnDeviceEngine.LOCAL_LLM
                }
            assertEquals(OnDeviceEngine.LOCAL_LLM, localLlm.onDeviceEngine)

            repo.updateOnDeviceEngine(OnDeviceEngine.NEEDLE_WHISTLE)
            val needle =
                repo.awaitSettings("needle whistle engine") {
                    it.onDeviceEngine == OnDeviceEngine.NEEDLE_WHISTLE
                }
            assertEquals(OnDeviceEngine.NEEDLE_WHISTLE, needle.onDeviceEngine)
        }

    @Test
    fun `local llm model matches labels file names and aliases`() {
        assertEquals(LocalLlmModel.GEMMA_4_E2B, LocalLlmModel.fromRawName("Gemma 4 E2B"))
        assertEquals(LocalLlmModel.GEMMA_4_E2B, LocalLlmModel.fromRawName("gemma-4-E2B-it.litertlm"))
        assertEquals(LocalLlmModel.GEMMA_4_E2B, LocalLlmModel.fromRawName("gemma-4-E2B-it"))
        assertEquals(LocalLlmModel.GEMMA_4_E4B, LocalLlmModel.fromRawName("Gemma 4 E4B"))
        assertEquals(LocalLlmModel.GEMMA_4_E4B, LocalLlmModel.fromRawName("gemma-4-E4B-it.litertlm"))
        assertEquals(LocalLlmModel.GEMMA_4_E4B, LocalLlmModel.fromRawName("gemma-4-E4B-it"))
        assertEquals(LocalLlmModel.LFM_2_5_1_2B, LocalLlmModel.fromRawName("LFM 2.5 1.2B"))
        assertEquals(
            LocalLlmModel.LFM_2_5_1_2B,
            LocalLlmModel.fromRawName("LFM2.5-1.2B-Instruct_int4_16k_fp16.litertlm"),
        )
        assertEquals(
            LocalLlmModel.LFM_2_5_1_2B,
            LocalLlmModel.fromRawName("LFM2.5-1.2B-Instruct_int4_16k_fp16"),
        )
        assertEquals(LocalLlmModel.LFM_2_5_1_2B, LocalLlmModel.fromRawName("LFM2.5-1.2B-Instruct"))
        assertEquals(LocalLlmModel.LFM_2_5_2_6B, LocalLlmModel.fromRawName("LFM 2.5 2.6B"))
        assertEquals(
            LocalLlmModel.LFM_2_5_2_6B,
            LocalLlmModel.fromRawName("LFM2.5-2.6B_int4_16k_fp16.litertlm"),
        )
        assertEquals(
            LocalLlmModel.LFM_2_5_2_6B,
            LocalLlmModel.fromRawName("LFM2.5-2.6B_int4_16k_fp16"),
        )
        assertEquals(LocalLlmModel.LFM_2_5_2_6B, LocalLlmModel.fromRawName("LFM2.5-2.6B"))
        assertNull(LocalLlmModel.fromRawName("unknown-model"))
        assertNull(LocalLlmModel.fromRawName("lfm"))
        assertNull(LocalLlmModel.fromRawName("lfm2.5-1.2b"))
        assertNull(LocalLlmModel.fromRawName("gemma-4-e2b"))
    }
}
