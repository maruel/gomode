// Emulator coverage for pre-call Telecom audio selection and route retention after hang-up.
package com.fghbuild.gomode.voice

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TelecomAudioSelectionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun speakerCanBeChosenBeforeStartingAndRemainsAvailableAfterHangup() =
        runTest {
            withContext(Dispatchers.Default) {
                val context = composeRule.activity
                val controller = withContext(Dispatchers.Main) { TelecomCallController(context, onTelecomEnded = {}) }
                try {
                    val before =
                        withTimeout(15_000) {
                            controller.audioState.first {
                                it.devices.any { device ->
                                    device.type ==
                                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                                }
                            }
                        }
                    val speaker = before.devices.first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    withContext(Dispatchers.Main) { controller.selectAudioDevice(speaker.id) }
                    assertEquals(speaker.id, controller.audioState.value.selectedDeviceId)
                    val audio = context.getSystemService(AudioManager::class.java)
                    assertEquals(AudioManager.MODE_NORMAL, audio.mode)
                    val started = withContext(Dispatchers.Main) { controller.start("Audio selection", null) {} }
                    assertTrue(started)
                    withContext(Dispatchers.Main) { controller.activate() }
                    withTimeout(15_000) {
                        while (audio.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) delay(50)
                    }
                    withTimeout(15_000) {
                        controller.audioState.first { it.selectedDeviceId == speaker.id }
                    }
                    withContext(Dispatchers.Main) { controller.endLocal() }
                    assertEquals(before.devices, controller.audioState.value.devices)
                    assertEquals(speaker.id, controller.audioState.value.selectedDeviceId)
                    withTimeout(15_000) {
                        while (audio.mode != AudioManager.MODE_NORMAL) delay(50)
                    }
                } finally {
                    withContext(Dispatchers.Main) { controller.close() }
                }
            }
        }

    @Test
    fun closingTheOwnerCompletesAnActiveCallDisconnect() =
        runTest {
            withContext(Dispatchers.Default) {
                for (endFirst in listOf(false, true)) {
                    val context = composeRule.activity
                    val audio = context.getSystemService(AudioManager::class.java)
                    val controller =
                        withContext(Dispatchers.Main) { TelecomCallController(context, onTelecomEnded = {}) }
                    try {
                        withTimeout(15_000) { controller.audioState.first { it.devices.isNotEmpty() } }
                        assertTrue(withContext(Dispatchers.Main) { controller.start("Audio selection", null) {} })
                        withContext(Dispatchers.Main) { controller.activate() }
                        withTimeout(15_000) {
                            while (audio.mode != AudioManager.MODE_IN_COMMUNICATION) delay(50)
                        }
                        withContext(Dispatchers.Main) {
                            if (endFirst) controller.endLocal()
                            controller.close()
                        }
                        withTimeout(15_000) {
                            while (audio.mode != AudioManager.MODE_NORMAL) delay(50)
                        }
                        withTimeout(15_000) {
                            controller.audioState.first { it.devices.isEmpty() }
                        }
                    } finally {
                        withContext(Dispatchers.Main) { controller.close() }
                    }
                    assertTrue(
                        controller.audioState.value.devices
                            .isEmpty(),
                    )
                }
            }
        }
}
