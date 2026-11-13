// Persisted Go Mode service-instance settings backed by DataStore preferences.
//
// The voice setup has two parts. VOICE_SETUP is device-wide: the setup mode, where recognition and
// speech run, the language model kind, and the device speech speed. These pick the session (WebRTC,
// text, or an on-device engine) before any gateway is discovered; car dials and on-device engines
// need no loaded service. GATEWAY_VOICE_SETUPS holds, per normalized gateway URL, one choice per
// mode and the recognition and synthesis model IDs, so a mode switch restores the other mode's
// selection. LOCAL_LLM_MODEL names the local model variant. SPEAKING_VOICES stays keyed by gateway
// and choice. SavedVoiceSetup edits the three voice setup keys in one write.
//
// The FOLLOW_GATEWAY mode runs gateway recognition and speech with the followed choice, or the
// gateway's WebRTC default. GatewayVoiceChoices resolves it to all-in-one for a native audio choice
// and to separate gateway stages otherwise, the way web/src/VoiceSession.ts derives a missing saved
// setup. Fresh installations and migrated cloud voice use it, because whether a saved cloud choice
// is a native audio model or a composable language model is known only from the gateway catalog.
// A serialized setup without a mode decodes to it. The followed choice has its own field, so an
// explicit all-in-one choice never replaces a followed language model; its serialized name is
// followedChoiceID, and saved setups that name it allInOneChoiceID still decode to it.
// Migration reads the former VOICE_MODE, ON_DEVICE_VOICE_ENGINE, and VOICE_CHOICES keys while
// VOICE_SETUP is absent; the first voice setup write replaces them:
//
// | Former value | Voice setup |
// | --- | --- |
// | CLOUD | Follow the gateway; per gateway, the saved WebRTC choice as the followed choice |
// | DEVICE + REMOTE_GATEWAY | Separate stages, device recognition and speech, the saved text choice |
// | DEVICE + LOCAL_LLM | Separate stages, device recognition and speech, the saved local model |
// | DEVICE + NEEDLE_WHISTLE | Separate stages, device recognition and speech, Whistle + Needle |
//
// The former engine also becomes the separate-stages language model for cloud voice, so choosing
// device speech later restores it.
package com.fghbuild.gomode.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.Locale
import java.util.UUID

@Serializable
data class ServiceInstance(
    val id: String,
    val label: String = "",
    val kind: String = "web",
    val url: String = "",
)

/** VoiceSetupMode selects the gateway's choice, one native audio model, or independently chosen stages. */
@Serializable
enum class VoiceSetupMode {
    /** FOLLOW_GATEWAY runs the followed gateway-speech choice, or the default, with gateway recognition and speech. */
    @SerialName("follow-gateway")
    FOLLOW_GATEWAY,

    /** ALL_IN_ONE runs recognition, reasoning, and speech in one gateway native audio model. */
    @SerialName("all-in-one")
    ALL_IN_ONE,

    /** SEPARATE_STAGES selects recognition, the language model, and speech independently. */
    @SerialName("separate-stages")
    SEPARATE_STAGES,
}

/** SpeechStageLocation names where a recognition or speech stage runs. */
@Serializable
enum class SpeechStageLocation {
    @SerialName("device")
    DEVICE,

    @SerialName("gateway")
    GATEWAY,
}

/** VoiceLanguageModel selects where the language model of separate stages runs. */
@Serializable
enum class VoiceLanguageModel {
    /** GATEWAY runs a gateway choice. */
    @SerialName("gateway")
    GATEWAY,

    /** LOCAL_LLM runs a compact conversational model locally on device. */
    @SerialName("local-llm")
    LOCAL_LLM,

    /** NEEDLE_WHISTLE runs Whistle biased ASR with Needle engram action matching. */
    @SerialName("needle-whistle")
    NEEDLE_WHISTLE,
}

/** Device speech speeds as multiples of the voice's normal rate; web/src/VoiceSession.ts BROWSER_SPEECH_RATES. */
val DEVICE_SPEECH_RATES: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * VoiceSetupPreference is the device-wide voice setup.
 *
 * [recognition], [speech], and [languageModel] are the separate-stages selections; other modes
 * keep them for a later switch back. [deviceSpeechRate] applies whenever the device speaks.
 */
@Serializable
data class VoiceSetupPreference(
    val mode: VoiceSetupMode = VoiceSetupMode.FOLLOW_GATEWAY,
    val recognition: SpeechStageLocation = SpeechStageLocation.DEVICE,
    val speech: SpeechStageLocation = SpeechStageLocation.DEVICE,
    val languageModel: VoiceLanguageModel = VoiceLanguageModel.GATEWAY,
    val deviceSpeechRate: Float = 1f,
) {
    init {
        require(deviceSpeechRate in DEVICE_SPEECH_RATES) { "Choose an available speaking speed" }
    }

    /** The language model sessions run: only separate stages run one on the device. */
    val effectiveLanguageModel: VoiceLanguageModel
        get() =
            when (mode) {
                VoiceSetupMode.FOLLOW_GATEWAY, VoiceSetupMode.ALL_IN_ONE -> VoiceLanguageModel.GATEWAY
                VoiceSetupMode.SEPARATE_STAGES -> languageModel
            }

    /** Where recognition runs; on-device language models require device recognition and speech. */
    val effectiveRecognition: SpeechStageLocation
        get() = effectiveLocation(recognition)

    /** Where speech runs; on-device language models require device recognition and speech. */
    val effectiveSpeech: SpeechStageLocation
        get() = effectiveLocation(speech)

    /** Separate stages with [model] as the language model, keeping the other selections. */
    fun withLanguageModel(model: VoiceLanguageModel): VoiceSetupPreference =
        copy(mode = VoiceSetupMode.SEPARATE_STAGES, languageModel = model)

    /** Separate stages with [stage] at [location], keeping the other selections. */
    fun withStage(
        stage: SpeechStage,
        location: SpeechStageLocation,
    ): VoiceSetupPreference =
        when (stage) {
            SpeechStage.RECOGNITION -> copy(mode = VoiceSetupMode.SEPARATE_STAGES, recognition = location)
            SpeechStage.SYNTHESIS -> copy(mode = VoiceSetupMode.SEPARATE_STAGES, speech = location)
        }

    /** Where [stage] runs; on-device language models require device recognition and speech. */
    fun effectiveLocation(stage: SpeechStage): SpeechStageLocation =
        when (stage) {
            SpeechStage.RECOGNITION -> effectiveRecognition
            SpeechStage.SYNTHESIS -> effectiveSpeech
        }

    /** Both stages run on the device, so turns travel as text or stay on the device. */
    val deviceSpeech: Boolean
        get() = effectiveRecognition == SpeechStageLocation.DEVICE && effectiveSpeech == SpeechStageLocation.DEVICE

    private fun effectiveLocation(location: SpeechStageLocation): SpeechStageLocation =
        when (mode) {
            VoiceSetupMode.FOLLOW_GATEWAY, VoiceSetupMode.ALL_IN_ONE -> {
                SpeechStageLocation.GATEWAY
            }

            VoiceSetupMode.SEPARATE_STAGES -> {
                if (languageModel == VoiceLanguageModel.GATEWAY) location else SpeechStageLocation.DEVICE
            }
        }
}

/** GatewayVoiceSetup remembers one gateway's selections for each setup mode. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GatewayVoiceSetup(
    /** The choice following the gateway runs, of either kind. */
    @SerialName("followedChoiceID") @JsonNames("allInOneChoiceID") val followedChoiceID: String? = null,
    /** The native audio choice of the all-in-one mode. */
    @SerialName("nativeChoiceID") val allInOneChoiceID: String? = null,
    @SerialName("separateStagesChoiceID") val separateStagesChoiceID: String? = null,
    @SerialName("recognitionModelID") val recognitionModelID: String? = null,
    @SerialName("synthesisModelID") val synthesisModelID: String? = null,
) {
    fun choiceID(mode: VoiceSetupMode): String? =
        when (mode) {
            VoiceSetupMode.FOLLOW_GATEWAY -> followedChoiceID
            VoiceSetupMode.ALL_IN_ONE -> allInOneChoiceID
            VoiceSetupMode.SEPARATE_STAGES -> separateStagesChoiceID
        }

    /** Saves [choiceID] as the choice of [mode]. */
    fun withChoice(
        mode: VoiceSetupMode,
        choiceID: String,
    ): GatewayVoiceSetup =
        when (mode) {
            VoiceSetupMode.FOLLOW_GATEWAY -> copy(followedChoiceID = choiceID)
            VoiceSetupMode.ALL_IN_ONE -> copy(allInOneChoiceID = choiceID)
            VoiceSetupMode.SEPARATE_STAGES -> copy(separateStagesChoiceID = choiceID)
        }

    fun modelID(stage: SpeechStage): String? =
        when (stage) {
            SpeechStage.RECOGNITION -> recognitionModelID
            SpeechStage.SYNTHESIS -> synthesisModelID
        }

    fun withModel(
        stage: SpeechStage,
        modelID: String,
    ): GatewayVoiceSetup =
        when (stage) {
            SpeechStage.RECOGNITION -> copy(recognitionModelID = modelID)
            SpeechStage.SYNTHESIS -> copy(synthesisModelID = modelID)
        }
}

/**
 * SavedVoiceSetup is what one voice setup write edits: the device-wide setup, one gateway's
 * selections, and the local model variant.
 */
data class SavedVoiceSetup(
    val preference: VoiceSetupPreference,
    /** Empty when the write names no gateway; such a write cannot change it. */
    val gateway: GatewayVoiceSetup,
    val localLlmModel: LocalLlmModel,
)

/** SpeechStage names a gateway speech model slot. */
enum class SpeechStage {
    RECOGNITION,
    SYNTHESIS,
}

// Each variant owns a separate installed weight file.
enum class LocalLlmModel(
    val label: String,
    val fileName: String,
    // Includes instructions, tool schemas, history, reasoning, and output.
    // LFM3 must match its exported cache shape; a smaller runtime limit does not shrink the graph.
    val maxNumTokens: Int,
    val topK: Int,
    val topP: Double,
    val temperature: Double,
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
    LFM_3_1_7B_64K(
        label = "LFM3 1.7B · 64K",
        fileName = "LFM3-1.7B_int8_64k_fp16.litertlm",
        maxNumTokens = 65_536,
        topK = 50,
        topP = 1.0,
        temperature = 0.1,
    ),
    LFM_3_1_7B_32K(
        label = "LFM3 1.7B · 32K",
        fileName = "LFM3-1.7B_int8_32k_fp16.litertlm",
        maxNumTokens = 32_768,
        topK = 50,
        topP = 1.0,
        temperature = 0.1,
    ),
    ;

    val isLfm3: Boolean get() = this == LFM_3_1_7B_64K || this == LFM_3_1_7B_32K

    private fun matches(raw: String): Boolean {
        val clean = raw.removeSuffix(".litertlm")
        val fileStem = fileName.removeSuffix(".litertlm")
        val lfm3Alias = clean.equals("LFM3 1.7B", ignoreCase = true) || clean.equals("LFM3-1.7B", ignoreCase = true)
        return (this == LFM_3_1_7B_64K && lfm3Alias) ||
            clean.equals(label, ignoreCase = true) ||
            clean.equals(fileStem, ignoreCase = true)
    }

    companion object {
        fun fromRawName(raw: String): LocalLlmModel? = entries.firstOrNull { it.matches(raw) }
    }
}

const val DEFAULT_VOICE_LANGUAGE_TAG = "en-US"

internal const val MAX_CAR_CALLER_SLOT = 99

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
    val voiceSetup: VoiceSetupPreference = VoiceSetupPreference(),
    val voiceLanguageTag: String = DEFAULT_VOICE_LANGUAGE_TAG,
    val showReasoning: Boolean = false,
    val showUsageStats: Boolean = false,
    val localLlmModel: LocalLlmModel = LocalLlmModel.GEMMA_4_E2B,
    val voiceAudioDeviceType: Int? = null,
    /** Saved selections per normalized gateway URL. */
    val gatewayVoiceSetups: Map<String, GatewayVoiceSetup> = emptyMap(),
    val speakingVoices: Map<String, Map<String, String>> = emptyMap(),
    val carCallerSlots: Map<String, Int> = emptyMap(),
    val showServiceOnLockScreen: Boolean = false,
    val loadState: SettingsLoadState = SettingsLoadState.Loading,
)

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val SERVICES = stringPreferencesKey("SERVICES")
        val CAR_CALLER_SLOTS = stringPreferencesKey("CAR_CALLER_SLOTS")
        val SHOW_SERVICE_ON_LOCK_SCREEN = booleanPreferencesKey("SHOW_SERVICE_ON_LOCK_SCREEN")
        val ACTIVE_SERVICE_ID = stringPreferencesKey("ACTIVE_SERVICE_ID")
        val HALO_ADDRESS = stringPreferencesKey("HALO_ADDRESS")
        val HALO_AUTO_CONNECT = booleanPreferencesKey("HALO_AUTO_CONNECT")
        val SPEAKING_VOICES = stringPreferencesKey("SPEAKING_VOICES")
        val VOICE_SETUP = stringPreferencesKey("VOICE_SETUP")
        val GATEWAY_VOICE_SETUPS = stringPreferencesKey("GATEWAY_VOICE_SETUPS")
        val LEGACY_VOICE_CHOICES = stringPreferencesKey("VOICE_CHOICES")
        val LEGACY_VOICE_MODE = stringPreferencesKey("VOICE_MODE")
        val VOICE_LANGUAGE_TAG = stringPreferencesKey("VOICE_LANGUAGE_TAG")
        val SHOW_REASONING = booleanPreferencesKey("SHOW_REASONING")
        val SHOW_USAGE_STATS = booleanPreferencesKey("SHOW_USAGE_STATS")
        val LEGACY_ON_DEVICE_VOICE_ENGINE = stringPreferencesKey("ON_DEVICE_VOICE_ENGINE")
        val LOCAL_LLM_MODEL = stringPreferencesKey("LOCAL_LLM_MODEL")
        val VOICE_AUDIO_DEVICE_TYPE = intPreferencesKey("VOICE_AUDIO_DEVICE_TYPE")
    }

    val settings: StateFlow<SettingsState> =
        dataStore.data
            // Persist assignments before publishing Ready, including for existing installations.
            .onStart { dataStore.edit(::assignCarCallerSlots) }
            .map { prefs ->
                val services = decodeServices(prefs)
                val activeId = prefs[Keys.ACTIVE_SERVICE_ID] ?: services.firstOrNull()?.id ?: ""
                val active = services.firstOrNull { it.id == activeId } ?: services.firstOrNull()
                SettingsState(
                    activeServiceURL = active?.url ?: "",
                    loadState = SettingsLoadState.Ready,
                    voiceSetup = decodeVoiceSetup(prefs),
                    gatewayVoiceSetups = decodeGatewayVoiceSetups(prefs),
                    speakingVoices = decodeSpeakingVoices(prefs),
                    carCallerSlots = decodeCarCallerSlots(prefs),
                    showServiceOnLockScreen = prefs[Keys.SHOW_SERVICE_ON_LOCK_SCREEN] ?: false,
                    haloAddress = prefs[Keys.HALO_ADDRESS],
                    haloAutoConnect = prefs[Keys.HALO_AUTO_CONNECT] ?: false,
                    services = services,
                    activeServiceId = active?.id ?: "",
                    voiceAudioDeviceType = prefs[Keys.VOICE_AUDIO_DEVICE_TYPE],
                    voiceLanguageTag = prefs[Keys.VOICE_LANGUAGE_TAG] ?: DEFAULT_VOICE_LANGUAGE_TAG,
                    showReasoning = prefs[Keys.SHOW_REASONING] ?: false,
                    showUsageStats = prefs[Keys.SHOW_USAGE_STATS] ?: false,
                    localLlmModel = decodeLocalLlmModel(prefs),
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
                    label = serviceLabel(label),
                    url = normalizedURL,
                )
            require(!aliasTaken(services, id, service.label)) { "Another service is named ${service.label}" }
            require(!urlTaken(services, id, normalizedURL)) { "Another service uses $normalizedURL" }
            prefs[Keys.SERVICES] = json.encodeToString(services + service)
            assignCarCallerSlots(prefs)
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
            val alias = serviceLabel(label)
            require(!aliasTaken(services, id, alias)) { "Another service is named $alias" }
            require(!urlTaken(services, id, normalizedURL)) { "Another service uses $normalizedURL" }
            prefs[Keys.SERVICES] =
                json.encodeToString(
                    services.map { service ->
                        if (service.id == id) {
                            service.copy(label = alias, url = normalizedURL)
                        } else {
                            service
                        }
                    },
                )
        }
    }

    // Car caller slots keep the removed ID so a later service cannot inherit its contact.
    // Voice choices stay keyed by gateway, which other services can share.
    suspend fun removeService(id: String) {
        dataStore.edit { prefs ->
            val services = decodeServices(prefs)
            require(services.any { it.id == id }) { "Unknown service ID: $id" }
            prefs[Keys.SERVICES] = json.encodeToString(services.filter { it.id != id })
            if (prefs[Keys.ACTIVE_SERVICE_ID] == id) prefs.remove(Keys.ACTIVE_SERVICE_ID)
        }
    }

    suspend fun switchService(id: String) {
        dataStore.edit { prefs ->
            val services = decodeServices(prefs)
            require(services.any { it.id == id }) { "Unknown service ID: $id" }
            prefs[Keys.ACTIVE_SERVICE_ID] = id
        }
    }

    suspend fun cycleService() {
        dataStore.edit { prefs ->
            val services = decodeServices(prefs)
            if (services.size < 2) return@edit
            val currentId = prefs[Keys.ACTIVE_SERVICE_ID] ?: services.first().id
            val currentIndex = services.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            prefs[Keys.ACTIVE_SERVICE_ID] = services[(currentIndex + 1) % services.size].id
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

    /** Edits the voice setup and, when [gatewayURL] is set, that gateway's selections, in one write. */
    suspend fun updateSavedVoiceSetup(
        gatewayURL: String?,
        transform: (SavedVoiceSetup) -> SavedVoiceSetup,
    ) {
        val gateway = gatewayURL?.let(::normalizeURL)
        require(gateway == null || gateway.isNotEmpty()) { "Voice gateway is unknown" }
        dataStore.edit { prefs -> editSavedVoiceSetup(prefs, gateway, transform) }
    }

    /** Saves [choiceID] as the choice of the setup mode [transform] yields, in one write. */
    suspend fun updateVoiceChoice(
        gatewayURL: String,
        choiceID: String,
        transform: (SavedVoiceSetup) -> SavedVoiceSetup,
    ) {
        require(choiceID.isNotBlank()) { "Select a voice choice" }
        updateSavedVoiceSetup(gatewayURL) { saved ->
            transform(saved).let { it.copy(gateway = it.gateway.withChoice(it.preference.mode, choiceID)) }
        }
    }

    /** Saves one stage's gateway model for composable language models with the setup [transform] yields. */
    suspend fun updateSpeechModel(
        gatewayURL: String,
        stage: SpeechStage,
        modelID: String,
        transform: (SavedVoiceSetup) -> SavedVoiceSetup,
    ) {
        require(modelID.isNotBlank()) { "Select a speech model" }
        updateSavedVoiceSetup(gatewayURL) { saved ->
            transform(saved).let { it.copy(gateway = it.gateway.withModel(stage, modelID)) }
        }
    }

    suspend fun updateShowServiceOnLockScreen(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_SERVICE_ON_LOCK_SCREEN] = enabled }
    }

    suspend fun updateVoiceLanguageTag(tag: String) {
        val normalized = normalizeVoiceLanguageTag(tag)
        dataStore.edit { prefs -> prefs[Keys.VOICE_LANGUAGE_TAG] = normalized }
    }

    suspend fun updateShowReasoning(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_REASONING] = enabled }
    }

    suspend fun updateShowUsageStats(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_USAGE_STATS] = enabled }
    }

    suspend fun updateVoiceAudioDeviceType(type: Int) {
        dataStore.edit { prefs -> prefs[Keys.VOICE_AUDIO_DEVICE_TYPE] = type }
    }

    /** Saves [voiceName] for [choiceID] with the voice setup [transform] yields, in one write. */
    suspend fun updateSpeakingVoice(
        gatewayURL: String,
        choiceID: String,
        voiceName: String,
        transform: (SavedVoiceSetup) -> SavedVoiceSetup,
    ) {
        require(choiceID.isNotBlank() && (voiceName.isEmpty() || voiceName.isNotBlank())) { "Select a speaking voice" }
        val gateway = normalizeURL(gatewayURL)
        require(gateway.isNotEmpty()) { "Voice gateway is unknown" }
        dataStore.edit { prefs ->
            editSavedVoiceSetup(prefs, gateway, transform)
            val saved = decodeSpeakingVoices(prefs)
            val choices = saved[gateway].orEmpty() + (choiceID to voiceName)
            prefs[Keys.SPEAKING_VOICES] = json.encodeToString(saved + (gateway to choices))
        }
    }

    private fun decodeSpeakingVoices(prefs: Preferences): Map<String, Map<String, String>> =
        prefs[Keys.SPEAKING_VOICES]?.let { json.decodeFromString<Map<String, Map<String, String>>>(it) } ?: emptyMap()

    // This app-local registry retains removed IDs so service edits cannot retarget
    // saved contacts. Exhaustion only disables fixed-server contacts; services and
    // the generic Go Mode shortcut remain available.
    private fun assignCarCallerSlots(prefs: MutablePreferences) {
        val slots = decodeCarCallerSlots(prefs).toMutableMap()
        val used = slots.values.toMutableSet()
        for (server in decodeServices(prefs)) {
            if (server.id.isBlank() || server.id in slots) continue
            val slot = (1..MAX_CAR_CALLER_SLOT).firstOrNull { it !in used } ?: break
            slots[server.id] = slot
            used.add(slot)
        }
        prefs[Keys.CAR_CALLER_SLOTS] = json.encodeToString(slots)
    }

    private fun decodeCarCallerSlots(prefs: Preferences): Map<String, Int> {
        val slots = prefs[Keys.CAR_CALLER_SLOTS]?.let { json.decodeFromString<Map<String, Int>>(it) } ?: emptyMap()
        require(
            slots.keys.none(String::isBlank) && slots.values.all { it in 1..MAX_CAR_CALLER_SLOT } &&
                slots.values.toSet().size == slots.size,
        ) { "Invalid saved car contact assignments" }
        return slots
    }

    private fun editSavedVoiceSetup(
        prefs: MutablePreferences,
        gateway: String?,
        transform: (SavedVoiceSetup) -> SavedVoiceSetup,
    ) {
        val gateways = decodeGatewayVoiceSetups(prefs)
        val saved =
            SavedVoiceSetup(
                decodeVoiceSetup(prefs),
                gateway?.let { gateways[it] } ?: GatewayVoiceSetup(),
                decodeLocalLlmModel(prefs),
            )
        val edited = transform(saved)
        require(gateway != null || edited.gateway == saved.gateway) { "Voice gateway is unknown" }
        writeVoiceSetup(
            prefs,
            edited.preference,
            if (gateway == null) gateways else gateways + (gateway to edited.gateway),
        )
        if (edited.localLlmModel != saved.localLlmModel) prefs[Keys.LOCAL_LLM_MODEL] = edited.localLlmModel.name
    }

    // Both voice setup keys are written together, so neither falls back to the former keys alone.
    private fun writeVoiceSetup(
        prefs: MutablePreferences,
        setup: VoiceSetupPreference,
        gateways: Map<String, GatewayVoiceSetup>,
    ) {
        prefs[Keys.VOICE_SETUP] = json.encodeToString(setup)
        prefs[Keys.GATEWAY_VOICE_SETUPS] = json.encodeToString(gateways)
        prefs.remove(Keys.LEGACY_VOICE_MODE)
        prefs.remove(Keys.LEGACY_ON_DEVICE_VOICE_ENGINE)
        prefs.remove(Keys.LEGACY_VOICE_CHOICES)
    }

    private fun decodeVoiceSetup(prefs: Preferences): VoiceSetupPreference =
        prefs[Keys.VOICE_SETUP]?.let { json.decodeFromString<VoiceSetupPreference>(it) }
            ?: legacyVoiceSetup(prefs[Keys.LEGACY_VOICE_MODE], prefs[Keys.LEGACY_ON_DEVICE_VOICE_ENGINE])

    private fun decodeGatewayVoiceSetups(prefs: Preferences): Map<String, GatewayVoiceSetup> {
        val encoded = prefs[Keys.GATEWAY_VOICE_SETUPS] ?: return legacyGatewayVoiceSetups(prefs)
        val setups = json.decodeFromString<Map<String, GatewayVoiceSetup>>(encoded)
        require(
            setups.keys.none(String::isBlank) &&
                setups.values.all { setup ->
                    listOf(
                        setup.followedChoiceID,
                        setup.allInOneChoiceID,
                        setup.separateStagesChoiceID,
                        setup.recognitionModelID,
                        setup.synthesisModelID,
                    ).all { it == null || it.isNotBlank() }
                },
        ) { "Invalid saved voice setup" }
        return setups
    }

    // The former WebRTC family was cloud voice and the text family was device speech.
    private fun legacyGatewayVoiceSetups(prefs: Preferences): Map<String, GatewayVoiceSetup> {
        val encoded = prefs[Keys.LEGACY_VOICE_CHOICES] ?: return emptyMap()
        val parsed = json.parseToJsonElement(encoded)
        // A build with one selection per gateway stored plain strings. It
        // cannot be mapped to a family, so each family uses its advertised default.
        if (parsed is JsonObject && parsed.values.all { it is JsonPrimitive }) return emptyMap()
        return json.decodeFromJsonElement<Map<String, Map<String, String>>>(parsed).mapValues { (_, families) ->
            GatewayVoiceSetup(
                followedChoiceID = families["webrtc"]?.takeIf(String::isNotBlank),
                separateStagesChoiceID = families["text"]?.takeIf(String::isNotBlank),
            )
        }
    }

    private fun decodeLocalLlmModel(prefs: Preferences): LocalLlmModel =
        when (val saved = prefs[Keys.LOCAL_LLM_MODEL]) {
            "LFM_2_5_1_2B", "LFM_2_5_2_6B", "LFM_3_1_7B" -> LocalLlmModel.LFM_3_1_7B_64K
            else -> LocalLlmModel.entries.firstOrNull { it.name == saved }
        } ?: LocalLlmModel.GEMMA_4_E2B

    private fun decodeServices(prefs: Preferences): List<ServiceInstance> =
        prefs[Keys.SERVICES]?.let { encoded ->
            runCatching { json.decodeFromString<List<ServiceInstance>>(encoded) }.getOrNull()
        } ?: emptyList()

    companion object {
        private const val DEFAULT_SERVICE_LABEL = "Service"

        /** Maps the former voice mode and on-device engine values onto the voice setup. */
        internal fun legacyVoiceSetup(
            mode: String?,
            engine: String?,
        ): VoiceSetupPreference {
            val languageModel =
                when (engine) {
                    "LOCAL_LLM" -> VoiceLanguageModel.LOCAL_LLM
                    "NEEDLE_WHISTLE" -> VoiceLanguageModel.NEEDLE_WHISTLE
                    else -> VoiceLanguageModel.GATEWAY
                }
            return VoiceSetupPreference(
                mode = if (mode == "DEVICE") VoiceSetupMode.SEPARATE_STAGES else VoiceSetupMode.FOLLOW_GATEWAY,
                languageModel = languageModel,
            )
        }

        fun normalizeURL(url: String): String = url.trim().trimEnd('/')

        /** serviceLabel returns the alias saved for an entered [label]. */
        fun serviceLabel(label: String): String = label.trim().ifEmpty { DEFAULT_SERVICE_LABEL }

        /**
         * aliasTaken reports whether a service other than [id] already uses [label].
         *
         * Voice server selection and car contacts present services by alias, so aliases are
         * unique regardless of case.
         */
        fun aliasTaken(
            services: List<ServiceInstance>,
            id: String?,
            label: String,
        ): Boolean {
            val alias = serviceLabel(label)
            return services.any { it.id != id && it.label.equals(alias, ignoreCase = true) }
        }

        /** urlTaken reports whether a service other than [id] already uses [url] once normalized. */
        fun urlTaken(
            services: List<ServiceInstance>,
            id: String?,
            url: String,
        ): Boolean {
            val normalized = normalizeURL(url)
            return services.any { it.id != id && it.url == normalized }
        }

        fun normalizeVoiceLanguageTag(tag: String): String {
            val locale = Locale.Builder().setLanguageTag(tag.trim()).build()
            require(
                locale.language.isNotEmpty() && locale.language != "und",
            ) { "Specify a language tag, such as en-US" }
            return locale.toLanguageTag()
        }
    }
}
