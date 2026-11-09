// Tracks route changes without requiring public Internet validation for local services.
package com.fghbuild.gomode.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread owner; callbacks from a stopped registration cannot revive a call. */
internal class DefaultNetworkMonitor(
    context: Context,
) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val current = MutableStateFlow<Network?>(null)
    val network = current.asStateFlow()

    fun start() {
        if (callback != null) return
        current.value = connectivity.activeNetwork
        val registration =
            object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    // Android delivers link properties after onAvailable; sockets can use the new route now.
                    if (callback === this) current.value = network
                }

                override fun onLost(network: Network) {
                    if (callback === this && current.value == network) current.value = null
                }
            }
        connectivity.registerDefaultNetworkCallback(registration, Handler(Looper.getMainLooper()))
        callback = registration
    }

    override fun close() {
        val registration = callback ?: return
        callback = null
        connectivity.unregisterNetworkCallback(registration)
        current.value = null
    }
}
