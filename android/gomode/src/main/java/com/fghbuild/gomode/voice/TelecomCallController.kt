// Android Telecom self-managed call controller.
package com.fghbuild.gomode.voice

import android.content.Context
import android.content.pm.PackageManager
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.net.toUri
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    override fun endLocal() {
        // Mark the call terminal so later callbacks from it are ignored.
        val wasActive = activeCall
        activeCall = 0L
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
    }
}

private fun CallControlResult.logFailure(operation: String) {
    if (this is CallControlResult.Error) {
        Log.w(TAG, "Telecom $operation failed: errorCode=$errorCode")
    }
}
