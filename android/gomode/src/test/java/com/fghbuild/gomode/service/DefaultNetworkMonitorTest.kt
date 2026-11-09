// Verifies default route changes, local networks, registration cleanup, and retired callback isolation.
package com.fghbuild.gomode.service

import android.net.ConnectivityManager
import android.net.LinkProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
class DefaultNetworkMonitorTest {
    @Test
    fun routeChangesWaitForLinkPropertiesAndIgnoreRetiredCallbacks() {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        val monitor = DefaultNetworkMonitor(context)
        monitor.start()
        monitor.start()
        val callback = connectivity.networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(41)
        val cellular = ShadowNetwork.newInstance(42)
        callback.onLinkPropertiesChanged(wifi, LinkProperties())
        callback.onAvailable(cellular)
        assertEquals(wifi, monitor.network.value)
        // Local gateways work even when a network lacks public Internet validation.
        callback.onLinkPropertiesChanged(cellular, LinkProperties())
        callback.onLost(wifi)
        assertEquals(cellular, monitor.network.value)
        callback.onLost(cellular)
        assertNull(monitor.network.value)
        callback.onLinkPropertiesChanged(wifi, LinkProperties())
        assertEquals(wifi, monitor.network.value)
        monitor.close()
        assertTrue(connectivity.networkCallbacks.isEmpty())
        callback.onLinkPropertiesChanged(cellular, LinkProperties())
        assertNull(monitor.network.value)
        monitor.start()
        val replacement = connectivity.networkCallbacks.single()
        replacement.onLinkPropertiesChanged(wifi, LinkProperties())
        callback.onLost(wifi)
        assertEquals(wifi, monitor.network.value)
        monitor.close()
    }
}
