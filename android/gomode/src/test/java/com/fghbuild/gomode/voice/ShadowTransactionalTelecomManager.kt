// Accepts transactional Telecom calls, which Robolectric lacks, and records the app's call-control requests.
package com.fghbuild.gomode.voice

import android.os.Build
import android.os.OutcomeReceiver
import android.os.ParcelUuid
import android.os.ResultReceiver
import android.telecom.CallAttributes
import android.telecom.CallControl
import android.telecom.CallControlCallback
import android.telecom.CallEndpoint
import android.telecom.CallEventCallback
import android.telecom.CallException
import android.telecom.TelecomManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTelecomManager
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

// Every request succeeds, like a platform without competing calls. No connection reaches a network.
@Implements(TelecomManager::class)
class ShadowTransactionalTelecomManager : ShadowTelecomManager() {
    // ICallControl method names, such as sendEvent and disconnect, in request order.
    val controlRequests = CopyOnWriteArrayList<String>()

    @Implementation(minSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Suppress("UnusedParameter") // Match the platform API; no fixture call requests app-side transitions.
    fun addCall(
        attributes: CallAttributes,
        executor: Executor,
        receiver: OutcomeReceiver<CallControl, CallException>,
        controlCallback: CallControlCallback,
        eventCallback: CallEventCallback,
    ) {
        // The platform's server interface is hidden; CallControl only forwards to it.
        val serverType = Class.forName("com.android.internal.telecom.ICallControl")
        val server =
            Proxy.newProxyInstance(serverType.classLoader, arrayOf(serverType)) { _, method, arguments ->
                controlRequests += method.name
                arguments
                    ?.filterIsInstance<ResultReceiver>()
                    ?.singleOrNull()
                    ?.send(TELECOM_TRANSACTION_SUCCESS, null)
                null
            }
        val control =
            CallControl::class.java
                .getConstructor(String::class.java, serverType)
                .newInstance(attributes.displayName.toString(), server)
        // Telecom reports the starting route after adding a call; core-telecom waits for it.
        val earpiece = CallEndpoint("Earpiece", CallEndpoint.TYPE_EARPIECE, ParcelUuid(UUID.randomUUID()))
        executor.execute {
            receiver.onResult(control)
            eventCallback.onAvailableCallEndpointsChanged(listOf(earpiece))
            eventCallback.onCallEndpointChanged(earpiece)
        }
    }

    private companion object {
        // Hidden TelecomManager.TELECOM_TRANSACTION_SUCCESS.
        const val TELECOM_TRANSACTION_SUCCESS = 0
    }
}
