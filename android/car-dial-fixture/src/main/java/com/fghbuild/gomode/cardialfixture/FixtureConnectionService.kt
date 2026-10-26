// Records connections delivered by real Android Telecom without placing a network call.
package com.fghbuild.gomode.cardialfixture

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import java.util.concurrent.LinkedBlockingQueue

class FixtureConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        connections.offer(request)
        return Connection.createFailedConnection(DisconnectCause(DisconnectCause.LOCAL, "Fixture only"))
    }

    companion object {
        val connections = LinkedBlockingQueue<ConnectionRequest>()
    }
}
