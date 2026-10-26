// Imports the last-server and all saved-server Bluetooth contacts together.
package com.fghbuild.gomode.ui.settings

import android.content.ActivityNotFoundException
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.voice.carContacts
import com.fghbuild.gomode.voice.carContactsIntent
import com.fghbuild.gomode.voice.carNumberLabel
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
internal fun CarCallerIDSetting(
    settings: SettingsState,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Car contacts", style = MaterialTheme.typography.titleMedium)
    for (contact in carContacts(settings)) {
        Text("${contact.name}: ${carNumberLabel(contact.number)}")
    }
    Button(
        enabled = enabled && !preparing && settings.loadState == SettingsLoadState.Ready,
        modifier = Modifier.testTag("gomode-car-create-contacts"),
        onClick = {
            preparing = true
            error = null
            scope.launch {
                try {
                    context.startActivity(carContactsIntent(context, settings))
                } catch (_: ActivityNotFoundException) {
                    error = "Install a Contacts app to import contacts."
                } catch (_: IOException) {
                    error = "Could not prepare contacts."
                } finally {
                    preparing = false
                }
            }
        },
    ) { Text("Create contacts") }
    error?.let { Text(it, modifier = Modifier.testTag("gomode-car-caller-error")) }
}
