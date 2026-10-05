// Android Telecom self-managed call controller.
package com.fghbuild.gomode.voice

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.ParcelUuid
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.net.toUri
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "GoModeTelecom"
private const val CALL_DISPLAY_NAME = "Go Mode Voice"
private val callAddress = "gomode://voice".toUri()

/**
 * TelecomCallController registers Go Mode voice sessions with Android Telecom
 * as audio-only self-managed calls. A car or headset hang-up arrives through the
 * callback passed to [start]; Go Mode never infers hang-up from SCO teardown.
 */
internal class TelecomCallController(
    private val context: Context,
) : VoiceCallController {
    private val callsManager = CallsManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controlScope: CallControlScope? = null
    private var addCallJob: Job? = null
    private var endedByTelecom = false
    private val _audioState = MutableStateFlow(CallAudioState())
    override val audioState = _audioState.asStateFlow()
    private val endpointIds = mutableMapOf<ParcelUuid, Int>()
    private var endpoints: List<CallEndpointCompat> = emptyList()

    // Identifies the live call so callbacks from a released call are ignored.
    private var callGeneration = 0L
    private var activeCall = 0L

    init {
        // Registration belongs to app setup, before any call exists.
        if (isSupported()) {
            callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        }
    }

    override fun isSupported(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM)

    override suspend fun start(onTelecomDisconnect: () -> Unit): Boolean {
        if (!isSupported()) return false
        release()
        val generation = ++callGeneration
        activeCall = generation
        val ready = CompletableDeferred<Boolean>()
        addCallJob =
            scope.launch {
                try {
                    callsManager.addCall(
                        callAttributes =
                            CallAttributesCompat(
                                displayName = CALL_DISPLAY_NAME,
                                address = callAddress,
                                direction = CallAttributesCompat.DIRECTION_OUTGOING,
                                callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                                // Voice disconnects when Telecom yields; it never holds.
                                callCapabilities = 0,
                            ),
                        onAnswer = {},
                        onDisconnect = {
                            if (activeCall == generation) {
                                endedByTelecom = true
                                onTelecomDisconnect()
                            }
                        },
                        onSetActive = {},
                        onSetInactive = {
                            // Telecom yields to a cellular or VoIP call; voice disconnects.
                            if (activeCall == generation) onTelecomDisconnect()
                        },
                        block = {
                            if (activeCall == generation) {
                                controlScope = this
                                launch {
                                    combine(availableEndpoints, currentCallEndpoint) { available, current ->
                                        available to current
                                    }.collect { (available, current) ->
                                        if (activeCall != generation) return@collect
                                        endpoints = available
                                        val devices =
                                            available.map { endpoint ->
                                                AudioDevice(
                                                    endpointId(endpoint),
                                                    endpoint.audioDeviceType(),
                                                    endpoint.name.toString(),
                                                )
                                            }
                                        _audioState.update {
                                            it.copy(devices = devices, selectedDeviceId = endpointId(current))
                                        }
                                    }
                                }
                            } else {
                                // The session ended while the call was still being added;
                                // end the orphaned call now that a control scope exists.
                                launch {
                                    disconnect(DisconnectCause(DisconnectCause.LOCAL)).logFailure("disconnect")
                                }
                            }
                            ready.complete(true)
                        },
                    )
                } catch (e: CancellationException) {
                    ready.complete(false)
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Log.w(TAG, "Telecom addCall failed", e)
                    ready.complete(false)
                }
            }
        return ready.await()
    }

    override fun activate() {
        val control = controlScope ?: return
        scope.launch { control.setActive().logFailure("setActive") }
    }

    private fun endpointId(endpoint: CallEndpointCompat): Int =
        endpointIds.getOrPut(endpoint.identifier) { endpointIds.size + 1 }

    override fun selectAudioDevice(deviceId: Int) {
        if (_audioState.value.switching) return
        val control = controlScope ?: return
        val generation = activeCall
        if (generation == 0L) return
        val endpoint = endpoints.firstOrNull { endpointId(it) == deviceId }
        if (endpoint == null) {
            _audioState.update { it.copy(error = "Audio device is no longer available") }
            return
        }
        _audioState.update { it.copy(error = null, switching = true) }
        scope.launch {
            val result = control.requestEndpointChange(endpoint)
            if (activeCall != generation) return@launch
            if (result is CallControlResult.Error) {
                _audioState.update { it.copy(error = "Could not switch audio device (${result.errorCode})") }
            }
            _audioState.update { it.copy(switching = false) }
            // The currentCallEndpoint flow confirms the actual route.
        }
    }

    override fun endLocal() {
        // Mark the call terminal so later callbacks from it are ignored.
        val wasActive = activeCall
        activeCall = 0L
        _audioState.value = CallAudioState()
        // A Telecom-initiated hang-up already ends the call; a second disconnect fails.
        if (endedByTelecom || wasActive == 0L) return
        val control = controlScope ?: return
        scope.launch { control.disconnect(DisconnectCause(DisconnectCause.LOCAL)).logFailure("disconnect") }
    }

    private fun release() {
        activeCall = 0L
        addCallJob?.cancel()
        addCallJob = null
        controlScope = null
        endedByTelecom = false
        endpoints = emptyList()
        endpointIds.clear()
        _audioState.value = CallAudioState()
    }
}

private fun CallControlResult.logFailure(operation: String) {
    if (this is CallControlResult.Error) {
        Log.w(TAG, "Telecom $operation failed: errorCode=$errorCode")
    }
}

private fun CallEndpointCompat.audioDeviceType(): Int =
    when (type) {
        CallEndpointCompat.TYPE_BLUETOOTH -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        CallEndpointCompat.TYPE_EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        CallEndpointCompat.TYPE_SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        CallEndpointCompat.TYPE_WIRED_HEADSET -> AudioDeviceInfo.TYPE_WIRED_HEADSET
        else -> AudioDeviceInfo.TYPE_UNKNOWN
    }
