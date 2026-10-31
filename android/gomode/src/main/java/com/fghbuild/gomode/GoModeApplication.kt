// Keeps native shell owners across Activity replacement without initializing unused owners during shutdown.
package com.fghbuild.gomode

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Configuration
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.gomode.voice.CarDialVoiceOwner
import com.fghbuild.gomode.voice.LocalModelDownloads
import com.fghbuild.gomode.voice.LocalModelOwner

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
    private val serviceShellOwnerDelegate = lazy { ServiceShellOwner(this, settingsRepository) }
    internal val serviceShellOwner by serviceShellOwnerDelegate
    private val carDialVoiceOwnerDelegate =
        lazy {
            CarDialVoiceOwner(this, settingsRepository, localModelOwner, serviceShellOwner)
        }
    internal val carDialVoiceOwner by carDialVoiceOwnerDelegate

    override fun onTerminate() {
        if (carDialVoiceOwnerDelegate.isInitialized()) carDialVoiceOwner.close()
        if (serviceShellOwnerDelegate.isInitialized()) serviceShellOwner.close()
        if (modelDownloadsDelegate.isInitialized()) modelDownloads.close()
        if (localModelOwnerDelegate.isInitialized()) localModelOwner.close()
        super.onTerminate()
    }
}
