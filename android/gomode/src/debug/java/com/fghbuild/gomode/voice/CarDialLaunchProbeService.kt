// Reproduces Android's lack of pending-dial cancellation notification in an isolated debug prototype.
package com.fghbuild.gomode.voice

import android.content.Intent
import android.net.Uri
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.data.SettingsLoadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Disabled by default and absent from release builds. The controlled phone-account fixture
// enables this service to measure platform behavior, never as public dial-to-start setup.
class CarDialLaunchProbeService : CallRedirectionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onPlaceCall(
        handle: Uri,
        initialPhoneAccount: PhoneAccountHandle,
        allowInteractiveResponse: Boolean,
    ) {
        scope.launch {
            val settings =
                withTimeoutOrNull(1_000) {
                    (application as GoModeApplication).settingsRepository.settings.first {
                        it.loadState != SettingsLoadState.Loading
                    }
                }
            val target = resolveCarDial(handle, settings)
            if (target == CarDialTarget.Unrecognized) {
                placeCallUnmodified()
                return@launch
            }
            if (target == CarDialTarget.Unavailable) {
                cancelCall()
                return@launch
            }
            // Simulate bounded local processing while still within Android's five-second
            // callback budget. Ending the original dial here does not notify this service.
            delay(PROBE_LOCAL_PROCESSING_DELAY_MS)
            // Telecom permits background activity launch only while this service remains
            // bound. cancelCall() releases that binding, so request the native surface first.
            startActivity(
                Intent(this@CarDialLaunchProbeService, CarDialLaunchProbeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            cancelCall()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val PROBE_LOCAL_PROCESSING_DELAY_MS = 2_000L
    }
}
