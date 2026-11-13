// Device speaking-speed dropdown with the web's speed steps and persistence errors.
package com.fghbuild.gomode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.fghbuild.gomode.data.DEVICE_SPEECH_RATES
import com.fghbuild.gomode.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun deviceSpeechRateLabel(rate: Float): String =
    if (rate == 1f) "1× (Normal)" else "${rate.toString().removeSuffix(".0")}×"

@Composable
internal fun DeviceSpeechRateSetting(
    rate: Float,
    repository: SettingsRepository,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember(enabled) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Speaking speed", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled && !saving,
                modifier = Modifier.testTag("gomode-device-speech-rate"),
            ) {
                Text(deviceSpeechRateLabel(rate))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // Menus open a popup window, which does not inherit the screen's flag.
                modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("gomode-device-speech-rates"),
            ) {
                DEVICE_SPEECH_RATES.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(deviceSpeechRateLabel(choice)) },
                        // A trailing check marks the current speed without shifting item labels.
                        trailingIcon = { if (choice == rate) Icon(Icons.Default.Check, contentDescription = null) },
                        modifier =
                            Modifier
                                .semantics { selected = choice == rate }
                                .testTag("gomode-device-speech-rate-$choice"),
                        onClick = {
                            expanded = false
                            scope.launch {
                                saving = true
                                saveError = null
                                try {
                                    repository.updateVoiceSetup { it.copy(deviceSpeechRate = choice) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (
                                    @Suppress("TooGenericExceptionCaught") e: Exception,
                                ) {
                                    // Display failed persistence.
                                    saveError = "Unable to save speaking speed: ${e.message}"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    )
                }
            }
        }
        saveError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("gomode-device-speech-rate-error"),
            )
        }
    }
}
