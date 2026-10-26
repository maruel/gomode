// Starts native voice for exact server or last-server dials when the user enables call redirection.
package com.fghbuild.gomode.voice

import android.content.Intent
import android.net.Uri
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.util.Log
import com.fghbuild.gomode.GoModeApplication
import com.fghbuild.gomode.MainActivity
import com.fghbuild.gomode.data.ServiceInstance
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Disabled by default. Native settings explicitly enable this component before asking
// Android to select Go Mode for call redirection; role ownership is checked by the voice owner.
class CarDialRedirectionService : CallRedirectionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onPlaceCall(
        handle: Uri,
        initialPhoneAccount: PhoneAccountHandle,
        allowInteractiveResponse: Boolean,
    ) {
        scope.launch {
            // Android allows five seconds. Local storage gets one second; never wait for a host.
            val settings =
                withTimeoutOrNull(1_000) {
                    (application as GoModeApplication).settingsRepository.settings.first {
                        it.loadState != SettingsLoadState.Loading
                    }
                }
            when (val target = resolveCarDial(handle, settings)) {
                CarDialTarget.Unrecognized -> {
                    placeCallUnmodified()
                }

                CarDialTarget.Unavailable -> {
                    cancelCall()
                }

                is CarDialTarget.Server -> {
                    val server = target.server
                    val owner = (application as GoModeApplication).carDialVoiceOwner
                    val identity = owner.request(server)
                    if (identity != null) {
                        // Accepted race: Android exposes no original-call identity or hang-up
                        // callback here. A user can cancel that dial before this launch and
                        // still see Go Mode start. Launch the native surface while Telecom's
                        // binding grants background Activity eligibility; cancelCall unbinds.
                        // The replacement call is registered before network/auth/model work.
                        try {
                            startActivity(
                                Intent(this@CarDialRedirectionService, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_CAR_REQUEST, identity)
                                    .addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                    ),
                            )
                        } catch (error: android.content.ActivityNotFoundException) {
                            Log.w("GoModeCarDial", "Native call surface unavailable", error)
                            owner.cancel(identity)
                        } catch (error: SecurityException) {
                            Log.w("GoModeCarDial", "Native call surface launch rejected", error)
                            owner.cancel(identity)
                        }
                    }
                    // Cancellation is final: later voice setup must never retry this through a SIM.
                    cancelCall()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

internal sealed interface CarDialTarget {
    data object Unrecognized : CarDialTarget

    data object Unavailable : CarDialTarget

    data class Server(
        val server: ServiceInstance,
    ) : CarDialTarget
}

internal fun resolveCarDial(
    handle: Uri,
    settings: SettingsState?,
): CarDialTarget {
    // Telecom supplies its formatted network number, with post-dial digits already removed:
    // AOSP CallRedirectionProcessorHelper.formatNumberForRedirection. Exactness applies to
    // that callback-visible number; the raw keypad string is unavailable to this service.
    if (handle.scheme != "tel" || handle.fragment != null) return CarDialTarget.Unrecognized
    val raw = handle.schemeSpecificPart
    // Only presentation separators are removable. Letters, extensions, pauses, suffixes,
    // alternate schemes, and control characters must not turn into a saved identifier.
    if (raw.any { it !in "+0123456789 ()-." }) return CarDialTarget.Unrecognized
    val number = raw.filter { it in "+0123456789" }
    val generic = number == GO_MODE_CAR_NUMBER
    if (settings?.loadState != SettingsLoadState.Ready) {
        return if (generic) CarDialTarget.Unavailable else CarDialTarget.Unrecognized
    }
    val matches =
        settings.services.filter {
            it.id.isNotBlank() &&
                if (generic) {
                    it.id == settings.activeServiceId
                } else {
                    settings.carCallerSlots[it.id]?.let(::carCallerNumber) == number
                }
        }
    return when {
        matches.size == 1 -> CarDialTarget.Server(matches.single())
        generic -> CarDialTarget.Unavailable
        else -> CarDialTarget.Unrecognized
    }
}
