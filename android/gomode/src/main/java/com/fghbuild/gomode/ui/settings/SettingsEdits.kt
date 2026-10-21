// Owns native settings drafts, validation, and serialized autosave before navigation.
package com.fghbuild.gomode.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.fghbuild.gomode.data.ServiceInstance
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

@Composable
internal fun rememberSettingsEdits(
    repository: SettingsRepository,
    settings: SettingsState,
): SettingsEdits {
    val scope = rememberCoroutineScope()
    val edits = remember(repository) { SettingsEdits(repository, settings, scope) }
    LaunchedEffect(edits, settings.voiceLanguageTag, settings.loadState) { edits.sync(settings) }
    return edits
}

internal data class ServiceEdit(
    val id: String?,
    val label: String,
    val url: String,
) {
    val normalizedURL: String?
        get() {
            val normalized = SettingsRepository.normalizeURL(url)
            val parsed = runCatching { URI(normalized) }.getOrNull() ?: return null
            return normalized.takeIf {
                parsed.scheme in listOf("http", "https") && !parsed.host.isNullOrBlank()
            }
        }
}

internal class SettingsEdits(
    private val repository: SettingsRepository,
    initial: SettingsState,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var savedLanguage = initial.voiceLanguageTag
    private var initialized = initial.loadState == SettingsLoadState.Ready

    var language by mutableStateOf(initial.voiceLanguageTag)
    var service by mutableStateOf<ServiceEdit?>(
        if (initialized && initial.services.isEmpty()) ServiceEdit(null, "", "") else null,
    )
    var error by mutableStateOf<String?>(null)
        private set

    val normalizedLanguage: String?
        get() = runCatching { SettingsRepository.normalizeVoiceLanguageTag(language) }.getOrNull()

    fun sync(settings: SettingsState) {
        if (!initialized && settings.loadState == SettingsLoadState.Ready) {
            service = if (settings.services.isEmpty()) ServiceEdit(null, "", "") else null
            initialized = true
        }
        if (language == savedLanguage) language = settings.voiceLanguageTag
        savedLanguage = settings.voiceLanguageTag
    }

    fun editService(service: ServiceInstance?) {
        this.service = ServiceEdit(service?.id, service?.label.orEmpty(), service?.url.orEmpty())
        error = null
    }

    fun discardDrafts() {
        service = null
        language = savedLanguage
        error = null
    }

    fun finish(
        voiceEnabled: Boolean,
        action: suspend () -> Unit,
    ) {
        scope.launch {
            if (save(voiceEnabled, commitURL = true)) action()
        }
    }

    suspend fun save(
        voiceEnabled: Boolean,
        commitURL: Boolean,
    ): Boolean =
        persist {
            val draft = service
            val url = draft?.normalizedURL
            if (draft?.id != null) {
                val saved =
                    repository.settings.value.services
                        .firstOrNull { it.id == draft.id }
                val label = draft.label.ifBlank { SettingsRepository.DEFAULT_SERVICE_LABEL }
                val targetURL = if (commitURL && url != null) url else saved?.url
                if (targetURL != null && (saved == null || saved.label != label || saved.url != targetURL)) {
                    repository.updateService(draft.id, label, targetURL)
                }
            }
            val tag = normalizedLanguage
            if (voiceEnabled && tag != null && tag != repository.settings.value.voiceLanguageTag) {
                repository.updateVoiceLanguageTag(tag)
            }
            true
        }

    suspend fun addService(): Boolean =
        persist {
            val draft = service
            val url = draft?.normalizedURL
            if (draft != null && draft.id == null && url != null) {
                val id = repository.addService(draft.label, url)
                service = service?.copy(id = id)
                true
            } else {
                false
            }
        }

    private suspend fun persist(block: suspend () -> Boolean): Boolean =
        mutex.withLock {
            if (repository.settings.value.loadState != SettingsLoadState.Ready) return@withLock false
            sync(repository.settings.value)
            try {
                val saved = block()
                error = null
                saved
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Native persistence boundary: keep the draft and report failed writes.
                error = "Unable to save settings: ${e.message}"
                false
            }
        }
}
