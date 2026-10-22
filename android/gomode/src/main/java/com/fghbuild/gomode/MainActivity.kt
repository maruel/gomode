// Hosts the Go Mode shell and consumes source-specific notification navigation on cold and warm launches.
package com.fghbuild.gomode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.preferencesDataStore
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.service.ServiceNotificationDestination
import com.fghbuild.gomode.service.ServiceNotificationPublisher
import com.fghbuild.gomode.ui.GoModeApp
import com.fghbuild.gomode.ui.theme.GoModeTheme

internal val Context.goModeSettingsDataStore by preferencesDataStore(name = "gomode_settings")

class MainActivity : ComponentActivity() {
    private var notificationDestination by mutableStateOf<ServiceNotificationDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState?.getBoolean("notificationHandled") != true) {
            notificationDestination = ServiceNotificationPublisher.destination(intent)
        }
        val settingsRepository = SettingsRepository(goModeSettingsDataStore)
        setContent {
            GoModeTheme {
                GoModeApp(
                    settingsRepository = settingsRepository,
                    notificationDestination = notificationDestination,
                    onNotificationHandled = { handled ->
                        if (notificationDestination == handled) {
                            notificationDestination = null
                            setIntent(Intent(this, MainActivity::class.java))
                        }
                    },
                    localModelOwner = (application as GoModeApplication).localModelOwner,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationDestination = ServiceNotificationPublisher.destination(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("notificationHandled", notificationDestination == null)
        super.onSaveInstanceState(outState)
    }
}
