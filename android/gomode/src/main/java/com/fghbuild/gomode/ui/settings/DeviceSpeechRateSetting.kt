// Device speaking-speed dropdown with the web's speed steps and persistence errors.
package com.fghbuild.gomode.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.fghbuild.gomode.data.DEVICE_SPEECH_RATES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun deviceSpeechRateLabel(rate: Float): String =
    if (rate == 1f) "1× (Normal)" else "${rate.toString().removeSuffix(".0")}×"

@Composable
internal fun DeviceSpeechRateSetting(
    rate: Float,
    enabled: Boolean,
    save: suspend (rate: Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    SettingsPicker(
        label = "Speaking speed",
        value = deviceSpeechRateLabel(rate),
        enabled = enabled && !saving,
        tag = "gomode-device-speech-rate",
        menuTag = "gomode-device-speech-rates",
        error = saveError,
        errorTag = "gomode-device-speech-rate-error",
    ) {
        DEVICE_SPEECH_RATES.forEach { choice ->
            Item(deviceSpeechRateLabel(choice), selected = choice == rate, tag = "gomode-device-speech-rate-$choice") {
                scope.launch {
                    saving = true
                    saveError = null
                    try {
                        save(choice)
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
            }
        }
    }
}
