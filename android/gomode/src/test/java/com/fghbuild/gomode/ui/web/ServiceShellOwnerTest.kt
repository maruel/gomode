// Verifies bounded page recovery when an abandoned load fails after a route change.
package com.fghbuild.gomode.ui.web

import android.net.ConnectivityManager
import android.net.LinkProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.fghbuild.gomode.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
class ServiceShellOwnerTest {
    @Test
    fun networkRetriesLateFailuresOncePerRouteAndPreservesHealthyPages() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val context = RuntimeEnvironment.getApplication()
            val owner = ServiceShellOwner(context, SettingsRepository(MemoryPreferences()))
            val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))
            try {
                owner.start()
                runCurrent()
                val entry = owner.entries.values.single()
                entry.updateLoad(WebShellLoadState.Ready)
                val callback = connectivity.networkCallbacks.single()
                callback.onLinkPropertiesChanged(ShadowNetwork.newInstance(41), LinkProperties())
                runCurrent()
                assertEquals(0, entry.state.value.webReloadToken)
                entry.updateLoad(WebShellLoadState.Failed("Connection failed", networkFailure = true))
                runCurrent()
                assertEquals(1, entry.state.value.webReloadToken)
                entry.updateLoad(WebShellLoadState.Failed("Still unavailable", networkFailure = true))
                runCurrent()
                assertEquals(1, entry.state.value.webReloadToken)
                callback.onLinkPropertiesChanged(ShadowNetwork.newInstance(42), LinkProperties())
                runCurrent()
                assertEquals(2, entry.state.value.webReloadToken)
                entry.updateLoad(WebShellLoadState.Failed("Sign in again"))
                callback.onLinkPropertiesChanged(ShadowNetwork.newInstance(43), LinkProperties())
                runCurrent()
                assertEquals(2, entry.state.value.webReloadToken)
            } finally {
                owner.close()
                Dispatchers.resetMain()
            }
            assertTrue(connectivity.networkCallbacks.isEmpty())
        }

    private class MemoryPreferences : DataStore<Preferences> {
        private val current = MutableStateFlow(emptyPreferences())
        override val data = current

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(current.value)
            current.value = next
            return next
        }
    }
}
