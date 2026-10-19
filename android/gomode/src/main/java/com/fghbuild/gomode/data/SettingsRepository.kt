// Persisted Go Mode service-instance settings backed by DataStore preferences.
package com.fghbuild.gomode.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID

@Serializable
data class ServiceInstance(
    val id: String,
    val label: String = "",
    val kind: String = "web",
    val url: String = "",
)

/** VoiceMode selects where speech recognition and synthesis run. */
enum class VoiceMode {
    /** CLOUD sends microphone audio to the gateway and plays gateway audio. */
    CLOUD,

    /** DEVICE transcribes and speaks on the device and sends only text. */
    DEVICE,
}

/** OnDeviceEngine selects which engine handles turns in on-device voice mode. */
enum class OnDeviceEngine {
    /** LOCAL_LLM runs a compact conversational model locally on device. */
    LOCAL_LLM,

    /** NEEDLE_WHISTLE runs Whistle biased ASR with Needle engram action matching. */
    NEEDLE_WHISTLE,

    /** NEUDECIDE runs direct ONNX speech-to-action inference for sub-100ms tool execution. */
    NEUDECIDE,

    /** REMOTE_GATEWAY delegates turn execution to the voice gateway over WebSocket. */
    REMOTE_GATEWAY,
}

// Each variant owns a separate installed weight file.
enum class LocalLlmModel(
    val label: String,
    val fileName: String,
    val maxNumTokens: Int = 10_240,
    val topK: Int = 64,
    val topP: Double = 0.95,
    val temperature: Double = 1.0,
    val alias: String? = null,
) {
    GEMMA_4_E2B(
        label = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        maxNumTokens = 10_240,
        topK = 64,
        topP = 0.95,
        temperature = 1.0,
    ),
    GEMMA_4_E4B(
        label = "Gemma 4 E4B",
        fileName = "gemma-4-E4B-it.litertlm",
        maxNumTokens = 10_240,
        topK = 64,
        topP = 0.95,
        temperature = 1.0,
    ),
    LFM_2_5_1_2B(
        label = "LFM 2.5 1.2B",
        fileName = "LFM2.5-1.2B-Instruct_int4_16k_fp16.litertlm",
        maxNumTokens = 16_384,
        topK = 50,
        topP = 1.0,
        temperature = 0.1,
        alias = "LFM2.5-1.2B-Instruct",
    ),
    LFM_2_5_2_6B(
        label = "LFM 2.5 2.6B",
        fileName = "LFM2.5-2.6B_int4_16k_fp16.litertlm",
        maxNumTokens = 16_384,
        topK = 50,
        topP = 1.0,
        temperature = 0.1,
        alias = "LFM2.5-2.6B",
    ),
    ;

    fun matches(raw: String): Boolean {
        val clean = raw.removeSuffix(".litertlm")
        val fileStem = fileName.removeSuffix(".litertlm")
        return clean.equals(label, ignoreCase = true) ||
            clean.equals(fileStem, ignoreCase = true) ||
            clean.equals(alias, ignoreCase = true)
    }

    companion object {
        fun fromRawName(raw: String): LocalLlmModel? = entries.firstOrNull { it.matches(raw) }
    }
}

const val DEFAULT_VOICE_LANGUAGE_TAG = "en-US"

sealed interface SettingsLoadState {
    data object Loading : SettingsLoadState

    data object Ready : SettingsLoadState

    data class Failed(
        val message: String,
    ) : SettingsLoadState
}

data class SettingsState(
    val activeServiceURL: String = "",
    val haloAddress: String? = null,
    val haloAutoConnect: Boolean = false,
    val services: List<ServiceInstance> = emptyList(),
    val activeServiceId: String = "",
    val voiceMode: VoiceMode = VoiceMode.CLOUD,
    val voiceLanguageTag: String = DEFAULT_VOICE_LANGUAGE_TAG,
    val onDeviceEngine: OnDeviceEngine = OnDeviceEngine.REMOTE_GATEWAY,
    val localLlmModel: LocalLlmModel = LocalLlmModel.GEMMA_4_E2B,
    val voiceAudioDeviceType: Int? = null,
    val voiceChoices: Map<String, String> = emptyMap(),
    val speakingVoices: Map<String, Map<String, String>> = emptyMap(),
    val loadState: SettingsLoadState = SettingsLoadState.Loading,
)

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val SERVICES = stringPreferencesKey("SERVICES")
        val ACTIVE_SERVICE_ID = stringPreferencesKey("ACTIVE_SERVICE_ID")
        val HALO_ADDRESS = stringPreferencesKey("HALO_ADDRESS")
        val HALO_AUTO_CONNECT = booleanPreferencesKey("HALO_AUTO_CONNECT")
        val SPEAKING_VOICES = stringPreferencesKey("SPEAKING_VOICES")
        val VOICE_CHOICES = stringPreferencesKey("VOICE_CHOICES")
        val VOICE_MODE = stringPreferencesKey("VOICE_MODE")
        val VOICE_LANGUAGE_TAG = stringPreferencesKey("VOICE_LANGUAGE_TAG")
        val ON_DEVICE_VOICE_ENGINE = stringPreferencesKey("ON_DEVICE_VOICE_ENGINE")
        val LOCAL_LLM_MODEL = stringPreferencesKey("LOCAL_LLM_MODEL")
        val VOICE_AUDIO_DEVICE_TYPE = intPreferencesKey("VOICE_AUDIO_DEVICE_TYPE")
    }

    val settings: StateFlow<SettingsState> =
        dataStore.data
            .map { prefs ->
                val services = decodeServices(prefs)
                val activeId = prefs[Keys.ACTIVE_SERVICE_ID] ?: services.firstOrNull()?.id ?: ""
                val active = services.firstOrNull { it.id == activeId } ?: services.firstOrNull()
                SettingsState(
                    activeServiceURL = active?.url ?: "",
                    loadState = SettingsLoadState.Ready,
                    voiceChoices = decodeVoiceChoices(prefs),
                    speakingVoices = decodeSpeakingVoices(prefs),
                    haloAddress = prefs[Keys.HALO_ADDRESS],
                    haloAutoConnect = prefs[Keys.HALO_AUTO_CONNECT] ?: false,
                    services = services,
                    activeServiceId = active?.id ?: "",
                    voiceAudioDeviceType = prefs[Keys.VOICE_AUDIO_DEVICE_TYPE],
                    voiceMode = decodeVoiceMode(prefs[Keys.VOICE_MODE]),
                    voiceLanguageTag = prefs[Keys.VOICE_LANGUAGE_TAG] ?: DEFAULT_VOICE_LANGUAGE_TAG,
                    onDeviceEngine = decodeOnDeviceEngine(prefs[Keys.ON_DEVICE_VOICE_ENGINE]),
                    localLlmModel =
                        LocalLlmModel.entries.firstOrNull { it.name == prefs[Keys.LOCAL_LLM_MODEL] }
                            ?: LocalLlmModel.GEMMA_4_E2B,
                )
            }.catch {
                emit(
                    SettingsState(loadState = SettingsLoadState.Failed("Unable to read saved settings: ${it.message}")),
                )
            }.stateIn(scope, SharingStarted.Eagerly, SettingsState())

    suspend fun addService(
        label: String,
        url: String,
    ): String {
        val normalizedURL = normalizeURL(url)
        val id = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            val services = decodeServices(prefs)
            val service =
                ServiceInstance(
                    id = id,
                    label = label.ifBlank { DEFAULT_SERVICE_LABEL },
                    url = normalizedURL,
                )
            prefs[Keys.SERVICES] = json.encodeToString(services + service)
            prefs[Keys.ACTIVE_SERVICE_ID] = id
        }
        return id
    }

    suspend fun updateService(
        id: String,
        label: String,
        url: String,
    ) {
        val normalizedURL = normalizeURL(url)
        dataStore.edit { prefs ->
            val services = decodeServices(prefs)
            require(services.any { it.id == id }) { "Unknown service ID: $id" }
            prefs[Keys.SERVICES] =
                json.encodeToString(
                    services.map { service ->
                        if (service.id == id) {
                            service.copy(label = label.ifBlank { DEFAULT_SERVICE_LABEL }, url = normalizedURL)
                        } else {
                            service
                        }
                    },
                )
        }
    }

    suspend fun switchService(id: String) {
        dataStore.edit { prefs ->
            val services = decodeServices(prefs)
            require(services.any { it.id == id }) { "Unknown service ID: $id" }
            prefs[Keys.ACTIVE_SERVICE_ID] = id
        }
    }

    suspend fun updateHaloAddress(address: String?) {
        dataStore.edit { prefs ->
            if (address.isNullOrBlank()) {
                prefs.remove(Keys.HALO_ADDRESS)
            } else {
                prefs[Keys.HALO_ADDRESS] = address
            }
        }
    }

    suspend fun updateHaloAutoConnect(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.HALO_AUTO_CONNECT] = enabled
        }
    }

    suspend fun updateVoiceMode(mode: VoiceMode) {
        dataStore.edit { prefs ->
            prefs[Keys.VOICE_MODE] = mode.name
        }
    }

    suspend fun updateVoiceLanguageTag(tag: String) {
        val normalized = normalizeVoiceLanguageTag(tag)
        dataStore.edit { prefs -> prefs[Keys.VOICE_LANGUAGE_TAG] = normalized }
    }

    suspend fun updateOnDeviceEngine(engine: OnDeviceEngine) {
        dataStore.edit { prefs -> prefs[Keys.ON_DEVICE_VOICE_ENGINE] = engine.name }
    }

    suspend fun updateVoiceAudioDeviceType(type: Int) {
        dataStore.edit { prefs -> prefs[Keys.VOICE_AUDIO_DEVICE_TYPE] = type }
    }

    suspend fun updateLocalLlmModel(model: LocalLlmModel) {
        dataStore.edit { prefs -> prefs[Keys.LOCAL_LLM_MODEL] = model.name }
    }

    suspend fun updateVoiceChoice(
        gatewayURL: String,
        choiceID: String,
    ) {
        require(choiceID.isNotBlank()) { "Select a voice choice" }
        dataStore.edit { prefs ->
            prefs[Keys.VOICE_CHOICES] =
                json.encodeToString(decodeVoiceChoices(prefs) + (normalizeURL(gatewayURL) to choiceID))
        }
    }

    suspend fun updateSpeakingVoice(
        gatewayURL: String,
        choiceID: String,
        voiceName: String,
    ) {
        require(choiceID.isNotBlank() && voiceName.isNotBlank()) { "Select a speaking voice" }
        dataStore.edit { prefs ->
            val gateway = normalizeURL(gatewayURL)
            val saved = decodeSpeakingVoices(prefs)
            val choices = saved[gateway].orEmpty() + (choiceID to voiceName)
            prefs[Keys.SPEAKING_VOICES] = json.encodeToString(saved + (gateway to choices))
        }
    }

    private fun decodeSpeakingVoices(prefs: Preferences): Map<String, Map<String, String>> =
        prefs[Keys.SPEAKING_VOICES]?.let { json.decodeFromString<Map<String, Map<String, String>>>(it) } ?: emptyMap()

    private fun decodeVoiceChoices(prefs: Preferences): Map<String, String> =
        prefs[Keys.VOICE_CHOICES]?.let { json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()

    private fun decodeServices(prefs: Preferences): List<ServiceInstance> =
        prefs[Keys.SERVICES]?.let { encoded ->
            runCatching { json.decodeFromString<List<ServiceInstance>>(encoded) }.getOrNull()
        } ?: emptyList()

    private fun decodeVoiceMode(value: String?): VoiceMode =
        VoiceMode.entries.firstOrNull { it.name == value } ?: VoiceMode.CLOUD

    private fun decodeOnDeviceEngine(value: String?): OnDeviceEngine =
        OnDeviceEngine.entries.firstOrNull { it.name == value } ?: OnDeviceEngine.REMOTE_GATEWAY

    companion object {
        const val DEFAULT_SERVICE_LABEL = "Service"

        fun normalizeURL(url: String): String = url.trim().trimEnd('/')

        fun normalizeVoiceLanguageTag(tag: String): String {
            val locale = Locale.Builder().setLanguageTag(tag.trim()).build()
            require(
                locale.language.isNotEmpty() && locale.language != "und",
            ) { "Specify a language tag, such as en-US" }
            return locale.toLanguageTag()
        }
    }
}
