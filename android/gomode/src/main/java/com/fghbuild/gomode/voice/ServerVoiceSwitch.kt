// Owns a voice-requested server switch until the destination can start a fresh voice session.
package com.fghbuild.gomode.voice

import com.fghbuild.gomode.data.ServiceInstance
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ServerVoiceSwitchRequest(
    val source: SettingsState,
    val target: ServiceInstance,
)

internal class ServerVoiceSwitch(
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val mutablePending = MutableStateFlow<ServerVoiceSwitchRequest?>(null)
    val pending = mutablePending.asStateFlow()

    suspend fun switchServer(id: String) {
        val source = settingsRepository.settings.value
        val target = source.services.firstOrNull { it.id == id } ?: error("Unknown service ID: $id")
        if (source.activeServiceId == id) return
        val request = ServerVoiceSwitchRequest(source, target)
        mutablePending.value = request
        // The old voice session can be disposed as soon as DataStore publishes the new service.
        // The shell owns the write so disposing that session cannot cancel a committed handoff.
        scope
            .async {
                runCatching { settingsRepository.switchService(id) }.also { result ->
                    if (result.isFailure && mutablePending.value === request) cancel()
                }
            }.await()
            .getOrThrow()
    }

    fun cancel() {
        mutablePending.value = null
    }

    // Consume once, only after the destination has its own page, auth, and voice setup ready.
    fun takeReady(
        settings: SettingsState,
        ready: Boolean,
    ): Boolean {
        val request = mutablePending.value ?: return false
        val source = request.source
        val target = request.target
        if (settings.voiceMode != source.voiceMode || settings.onDeviceEngine != source.onDeviceEngine ||
            settings.localLlmModel != source.localLlmModel || settings.voiceLanguageTag != source.voiceLanguageTag ||
            settings.voiceChoices != source.voiceChoices || settings.speakingVoices != source.speakingVoices
        ) {
            cancel()
            return false
        }
        if (settings.activeServiceId == source.activeServiceId &&
            settings.activeServiceURL == source.activeServiceURL
        ) {
            return false
        }
        if (settings.activeServiceId != target.id || settings.activeServiceURL != target.url) {
            cancel()
            return false
        }
        if (!ready) return false
        cancel()
        return true
    }
}
