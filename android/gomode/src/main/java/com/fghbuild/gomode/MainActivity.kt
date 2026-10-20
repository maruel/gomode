// Single activity host for the Go Mode WebView shell.
package com.fghbuild.gomode

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.datastore.preferences.preferencesDataStore
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.ui.GoModeApp
import com.fghbuild.gomode.ui.theme.GoModeTheme

internal val Context.goModeSettingsDataStore by preferencesDataStore(name = "gomode_settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsRepository = SettingsRepository(goModeSettingsDataStore)
        setContent {
            GoModeTheme {
                GoModeApp(
                    settingsRepository = settingsRepository,
                    localModelOwner = (application as GoModeApplication).localModelOwner,
                )
            }
        }
    }
}
