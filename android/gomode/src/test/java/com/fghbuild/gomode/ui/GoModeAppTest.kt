// Unit tests for Go Mode shell recovery and voice account changes.
package com.fghbuild.gomode.ui

import com.fghbuild.gomode.data.OnDeviceEngine
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.VoiceMode
import com.fghbuild.gomode.ui.web.WebShellLoadState
import com.fghbuild.gomode.voice.AudioDevice
import com.fghbuild.gomode.voice.VoiceState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoModeAppTest {
    @Test
    fun bluetoothWinsAtStartupAndOnArrivalButAllowsAManualSpeakerChoice() =
        runTest {
            val speaker = AudioDevice(1, 2, "Speaker")
            val bluetooth = AudioDevice(2, 7, "Car")
            val states =
                MutableStateFlow(
                    VoiceState(
                        connected = true,
                        availableDevices = listOf(speaker, bluetooth),
                        selectedDeviceId = 1,
                    ),
                )
            assertEquals(2, preferredVoiceAudioDevice(states, audioSettings(2), emptySet()))
            assertEquals(2, preferredVoiceAudioDevice(states, audioSettings(2), setOf(1)))
            assertNull(preferredVoiceAudioDevice(states, audioSettings(2), setOf(1, 2)))
            states.value = states.value.copy(availableDevices = listOf(speaker, AudioDevice(3, 26, "LE headset")))
            assertEquals(3, preferredVoiceAudioDevice(states, audioSettings(2), setOf(1, 2)))
        }

    @Test
    fun savedOutputUsesCurrentCallIdsAndLeavesUnavailableRoutesAlone() =
        runTest {
            val states =
                MutableStateFlow(
                    VoiceState(
                        connected = true,
                        availableDevices = listOf(AudioDevice(41, 2, "Speaker"), AudioDevice(42, 1, "Earpiece")),
                        selectedDeviceId = 41,
                    ),
                )
            assertEquals(42, preferredVoiceAudioDevice(states, audioSettings(1), emptySet()))
            assertNull(preferredVoiceAudioDevice(states, audioSettings(2), emptySet()))
            assertNull(preferredVoiceAudioDevice(states, audioSettings(7), emptySet()))
            assertNull(preferredVoiceAudioDevice(states, audioSettings(null), emptySet()))
            states.value = states.value.copy(connected = false)
            assertEquals(42, preferredVoiceAudioDevice(states, audioSettings(1), emptySet()))
            states.value =
                states.value.copy(connected = true, availableDevices = listOf(AudioDevice(99, 1, "Earpiece")))
            assertEquals(99, preferredVoiceAudioDevice(states, audioSettings(1), emptySet()))
        }

    @Test
    fun restoredEndpointWaitsForAnOutstandingSwitch() =
        runTest {
            val states =
                MutableStateFlow(
                    VoiceState(connected = true, audioSwitching = true, selectedDeviceId = 41),
                )
            val selection = async { preferredVoiceAudioDevice(states, audioSettings(1), emptySet()) }
            yield()
            assertFalse(selection.isCompleted)
            states.value = states.value.copy(availableDevices = listOf(AudioDevice(99, 1, "Earpiece")))
            yield()
            assertFalse(selection.isCompleted)
            states.value = states.value.copy(audioSwitching = false, audioError = "Could not switch audio device")
            assertEquals(99, selection.await())
        }

    @Test
    fun failedExplicitSwitchDoesNotBecomeASavedPreference() =
        runTest {
            val states =
                MutableStateFlow(
                    VoiceState(connected = true, audioSwitching = true, selectedDeviceId = 41),
                )
            val confirmation = async { awaitVoiceAudioSelection(states, 42) }
            yield()
            assertFalse(confirmation.isCompleted)
            states.value = states.value.copy(audioSwitching = false, audioError = "Could not switch audio device")
            assertFalse(confirmation.await())
            assertEquals("Could not switch audio device", states.value.audioError)
            states.value = states.value.copy(selectedDeviceId = 42, audioError = null)
            assertTrue(awaitVoiceAudioSelection(states, 42))
            assertNull(
                preferredVoiceAudioDevice(
                    MutableStateFlow(states.value.copy(availableDevices = listOf(AudioDevice(42, 1, "Earpiece")))),
                    audioSettings(1),
                    emptySet(),
                ),
            )
            assertTrue(awaitVoiceAudioSelection(MutableStateFlow(states.value.copy(connected = false)), 42))
        }

    @Test
    fun savedAudioPreferenceWaitsForSettingsWhenEndpointsArriveFirst() =
        runTest {
            val states =
                MutableStateFlow(
                    VoiceState(
                        availableDevices = listOf(AudioDevice(41, 2, "Speaker"), AudioDevice(42, 1, "Earpiece")),
                        selectedDeviceId = 41,
                    ),
                )
            val settings = MutableStateFlow(SettingsState())
            val selection = async { preferredVoiceAudioDevice(states, settings, emptySet()) }
            yield()
            assertFalse(selection.isCompleted)
            settings.value = SettingsState(voiceAudioDeviceType = 1, loadState = SettingsLoadState.Ready)
            assertEquals(42, selection.await())
            states.value = states.value.copy(selectedDeviceId = 42)
            settings.value = settings.value.copy(voiceAudioDeviceType = 2)
            assertEquals(41, preferredVoiceAudioDevice(states, settings, setOf(41, 42)))
        }

    private fun audioSettings(type: Int?) =
        MutableStateFlow(
            SettingsState(voiceAudioDeviceType = type, loadState = SettingsLoadState.Ready),
        )

    @Test
    fun accountSwitchInvalidatesVoiceIdentity() {
        val identity = VoiceAuthIdentity()

        assertFalse(identity.update("account-a", 0))
        assertFalse(identity.update("account-a", 0))
        assertTrue(identity.update("account-b", 0))
        assertFalse(identity.update("account-b", 0))
        assertTrue(identity.update(null, 0))
        assertTrue(identity.update("account-c", 0))
        assertTrue(identity.update("account-c", 1))
        assertFalse(identity.update("account-c", 1))
    }

    @Test
    fun cookieOnlyAccountChangeInvalidatesVoiceIdentity() {
        val identity = VoiceAuthIdentity()
        assertFalse(identity.update(null, 0))
        assertTrue(identity.update(null, 1))
        assertTrue(identity.update(null, 2))
    }

    @Test
    fun bootstrapFailureTakesPriorityOverWebFailure() {
        val recovery =
            shellRecoveryState(
                bootstrapError = "Unsupported service version.",
                webLoadState = WebShellLoadState.Failed("The service took too long to respond."),
            )

        assertEquals(
            ShellRecoveryState(
                title = "Could not use service",
                message = "Unsupported service version.",
                retryTarget = ShellRecoveryRetryTarget.BOOTSTRAP,
            ),
            recovery,
        )
    }

    @Test
    fun completedManifestFailureOffersRetryEvenWhenTheHostedPageLoaded() {
        val recovery =
            shellRecoveryState(
                bootstrapError = null,
                webLoadState = WebShellLoadState.Ready,
                bootstrapFailure = "Could not fetch service settings: offline",
            )
        assertEquals(ShellRecoveryRetryTarget.BOOTSTRAP, recovery?.retryTarget)
        assertEquals("Could not fetch service settings: offline", recovery?.message)
    }

    @Test
    fun webFailureOffersServiceRetryAndMarksVoiceUnavailable() {
        val recovery =
            shellRecoveryState(
                bootstrapError = null,
                webLoadState = WebShellLoadState.Failed("The service took too long to respond."),
            )

        assertEquals(
            ShellRecoveryState(
                title = "Could not load service",
                message = "The service took too long to respond.",
                retryTarget = ShellRecoveryRetryTarget.WEB,
            ),
            recovery,
        )
    }

    @Test
    fun webReconnectSuppressesCompetingActions() {
        val recovery =
            shellRecoveryState(
                bootstrapError = null,
                webLoadState = WebShellLoadState.Reconnecting,
            )

        assertEquals(
            ShellRecoveryState(
                title = "Reconnecting to service",
                message = "Voice will be available when the service reconnects.",
                retryTarget = null,
            ),
            recovery,
        )
    }

    @Test
    fun unsupportedWebViewRequiresUpdateInsteadOfOfferingAnIneffectiveReload() {
        val recovery =
            shellRecoveryState(
                bootstrapError = null,
                webLoadState = WebShellLoadState.Failed("Update Android System WebView.", canRetry = false),
            )
        assertEquals("Update Android System WebView.", recovery?.message)
        assertNull(recovery?.retryTarget)
    }

    @Test
    fun readyServiceAndWebViewNeedNoRecoveryStrip() {
        assertNull(shellRecoveryState(bootstrapError = null, webLoadState = WebShellLoadState.Ready))
    }
}
