// Keeps native shell owners across Activity replacement without initializing unused owners during shutdown.
package com.fghbuild.gomode

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Configuration
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.gomode.voice.CarDialVoiceOwner
import com.fghbuild.gomode.voice.LocalModelDownloads
import com.fghbuild.gomode.voice.LocalModelLoadDiagnostics
import com.fghbuild.gomode.voice.LocalModelOwner
import com.fghbuild.gomode.voice.VoiceErrorLog

internal val Context.goModeSettingsDataStore by preferencesDataStore(name = "gomode_settings")

class GoModeApplication :
    Application(),
    Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    internal val settingsRepository by lazy { SettingsRepository(goModeSettingsDataStore) }
    private val localModelOwnerDelegate: Lazy<LocalModelOwner> =
        lazy {
            LocalModelOwner(this, installation = modelDownloads.states)
        }
    internal val localModelOwner by localModelOwnerDelegate
    private val modelDownloadsDelegate: Lazy<LocalModelDownloads> =
        lazy {
            LocalModelDownloads(this) { model ->
                if (localModelOwnerDelegate.isInitialized()) localModelOwner.modelInstalled(model)
            }
        }
    internal val modelDownloads by modelDownloadsDelegate
    private val modelLoadDiagnosticsDelegate = lazy { LocalModelLoadDiagnostics(this) }
    internal val modelLoadDiagnostics by modelLoadDiagnosticsDelegate
    private val voiceErrorsDelegate = lazy { VoiceErrorLog(this) }
    internal val voiceErrors by voiceErrorsDelegate

    override fun onCreate() {
        super.onCreate()
        // Recover the last load before settings can initiate another preparation.
        modelLoadDiagnostics
    }

    internal suspend fun removeLocalModel(model: LocalLlmModel) {
        localModelOwner.removeModel(model) { modelDownloads.removeFiles(model) }
    }

    private val serviceShellOwnerDelegate = lazy { ServiceShellOwner(this, settingsRepository) }
    internal val serviceShellOwner by serviceShellOwnerDelegate
    private val carDialVoiceOwnerDelegate =
        lazy {
            CarDialVoiceOwner(this, settingsRepository, localModelOwner, serviceShellOwner)
        }
    internal val carDialVoiceOwner by carDialVoiceOwnerDelegate

    override fun onTerminate() {
        if (modelLoadDiagnosticsDelegate.isInitialized()) modelLoadDiagnostics.close()
        if (voiceErrorsDelegate.isInitialized()) voiceErrors.close()
        if (carDialVoiceOwnerDelegate.isInitialized()) carDialVoiceOwner.close()
        if (serviceShellOwnerDelegate.isInitialized()) serviceShellOwner.close()
        if (modelDownloadsDelegate.isInitialized()) modelDownloads.close()
        if (localModelOwnerDelegate.isInitialized()) localModelOwner.close()
        super.onTerminate()
    }
}
