// Persisted Go Mode service-instance settings backed by DataStore preferences.
package com.fghbuild.gomode.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

data class SettingsState(
    val activeServiceURL: String = "",
    val haloAddress: String? = null,
    val haloAutoConnect: Boolean = false,
    val services: List<ServiceInstance> = emptyList(),
    val activeServiceId: String = "",
    val voiceMode: VoiceMode = VoiceMode.CLOUD,
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
        val VOICE_MODE = stringPreferencesKey("VOICE_MODE")
    }

    val settings: StateFlow<SettingsState> =
        dataStore.data
            .map { prefs ->
                val services = decodeServices(prefs)
                val activeId = prefs[Keys.ACTIVE_SERVICE_ID] ?: services.firstOrNull()?.id ?: ""
                val active = services.firstOrNull { it.id == activeId } ?: services.firstOrNull()
                SettingsState(
                    activeServiceURL = active?.url ?: "",
                    haloAddress = prefs[Keys.HALO_ADDRESS],
                    haloAutoConnect = prefs[Keys.HALO_AUTO_CONNECT] ?: false,
                    services = services,
                    activeServiceId = active?.id ?: "",
                    voiceMode = decodeVoiceMode(prefs[Keys.VOICE_MODE]),
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
            if (services.any { it.id == id }) {
                prefs[Keys.ACTIVE_SERVICE_ID] = id
            }
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

    private fun decodeServices(prefs: Preferences): List<ServiceInstance> =
        prefs[Keys.SERVICES]?.let { encoded ->
            runCatching { json.decodeFromString<List<ServiceInstance>>(encoded) }.getOrNull()
        } ?: emptyList()

    private fun decodeVoiceMode(value: String?): VoiceMode =
        VoiceMode.entries.firstOrNull { it.name == value } ?: VoiceMode.CLOUD

    companion object {
        const val DEFAULT_SERVICE_LABEL = "Service"

        fun normalizeURL(url: String): String = url.trim().trimEnd('/')
    }
}
