// Keeps one native local-model owner across activity replacement and drained voice teardown.
package com.fghbuild.gomode

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.ui.web.ServiceShellOwner
import com.fghbuild.gomode.voice.CarDialVoiceOwner
import com.fghbuild.gomode.voice.LocalModelOwner

internal val Context.goModeSettingsDataStore by preferencesDataStore(name = "gomode_settings")

class GoModeApplication : Application() {
    internal val settingsRepository by lazy { SettingsRepository(goModeSettingsDataStore) }
    internal val localModelOwner by lazy { LocalModelOwner(this) }
    internal val serviceShellOwner by lazy { ServiceShellOwner(this, settingsRepository) }
    internal val carDialVoiceOwner by lazy {
        CarDialVoiceOwner(this, settingsRepository, localModelOwner, serviceShellOwner)
    }

    override fun onTerminate() {
        carDialVoiceOwner.close()
        serviceShellOwner.close()
        localModelOwner.close()
        super.onTerminate()
    }
}
