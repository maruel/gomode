// Tests active-service and WebView-origin checks for in-memory auth messages.
package com.fghbuild.gomode.ui.web

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServiceBearerStoreTest {
    private val serviceURL = "https://service.test:8443/mobile"
    private val origin = Uri.parse("https://service.test:8443")

    @Test
    fun acceptsOnlyMainFrameMessagesFromTheActiveService() {
        val store = ServiceBearerStore(serviceURL)
        assertFalse(store.receive(origin, true, serviceURL, token("before-navigation")))
        store.onNavigation(serviceURL)
        assertFalse(store.receive(origin, false, serviceURL, token("iframe")))
        assertFalse(store.receive(Uri.parse("https://evil.test"), true, serviceURL, token("evil")))
        assertFalse(store.receive(origin, true, "https://evil.test", token("redirect")))
        assertNull(store.tokenFor(serviceURL))

        assertTrue(store.receive(origin, true, serviceURL, token("valid")))
        assertEquals("valid", store.tokenFor("https://service.test:8443/api/mcp"))
        assertNull(store.tokenFor("https://evil.test/api/mcp"))
        assertNull(store.tokenFor("https://service.test/api/mcp"))
    }

    @Test
    fun clearsOnLogoutServiceSwitchAndNavigationAway() {
        val store = ServiceBearerStore(serviceURL)
        store.onNavigation(serviceURL)
        assertTrue(store.receive(origin, true, serviceURL, token("first")))
        store.onNavigation("https://service.test:8443/other")
        assertEquals("first", store.tokenFor(serviceURL))
        assertEquals(0L, store.authEpoch.value)
        store.onNavigation("https://evil.test")
        assertNull(store.tokenFor(serviceURL))
        assertEquals(1L, store.authEpoch.value)
        assertFalse(store.receive(origin, true, serviceURL, token("in-flight")))

        store.onNavigation(serviceURL)
        assertTrue(store.receive(origin, true, serviceURL, token("second")))
        assertTrue(store.receive(origin, true, serviceURL, """{"bearerToken":null}"""))
        assertNull(store.tokenFor(serviceURL))

        assertTrue(store.receive(origin, true, serviceURL, token("third")))
        store.deactivate()
        assertNull(store.tokenFor(serviceURL))
        assertFalse(store.receive(origin, true, serviceURL, token("stale")))
    }

    @Test
    fun rejectsMalformedOrUnsafeTokens() {
        val store = ServiceBearerStore(serviceURL)
        store.onNavigation(serviceURL)
        for (payload in listOf("{}", "not-json", """{"bearerToken":23}""", token("a\\nb"), token(""))) {
            assertFalse(store.receive(origin, true, serviceURL, payload))
        }
        assertNull(store.tokenFor(serviceURL))
    }

    @Test
    fun authChangeSignalRequiresTheTrustedMainFrame() {
        val store = ServiceBearerStore(serviceURL)
        val changed = settled(true)
        assertFalse(store.receive(origin, true, serviceURL, changed))
        store.onNavigation(serviceURL)
        assertFalse(store.receive(origin, false, serviceURL, changed))
        assertFalse(store.receive(Uri.parse("https://evil.test"), true, serviceURL, changed))
        assertFalse(store.receive(origin, true, "https://evil.test", changed))
        assertFalse(store.receive(origin, true, serviceURL, """{"authChanged":false}"""))
        assertFalse(store.receive(origin, true, serviceURL, """{"authChanged":true}"""))
        assertFalse(store.receive(origin, true, serviceURL, """{"authChanged":true,"nativeAccess":"true"}"""))
        assertEquals(0L, store.authEpoch.value)

        assertTrue(store.receive(origin, true, serviceURL, changed))
        assertEquals(1L, store.authEpoch.value)
        assertTrue(store.receive(origin, true, serviceURL, changed))
        assertEquals(2L, store.authEpoch.value)
        store.deactivate()
        assertFalse(store.receive(origin, true, serviceURL, changed))
        assertEquals(2L, store.authEpoch.value)
    }

    @Test
    fun acceptedAuthChangeStopsNativeConsumersBeforeReturningToWebView() {
        val store = ServiceBearerStore(serviceURL)
        val invalidations = mutableListOf<Long>()
        val handler = ServiceAuthMessageHandler(store) { invalidations += store.authEpoch.value }
        handler.onNavigation(serviceURL)

        assertFalse(handler.receive(origin, false, serviceURL, settled(true)))
        assertTrue(invalidations.isEmpty())
        assertTrue(handler.receive(origin, true, serviceURL, settled(true)))
        assertEquals(listOf(1L), invalidations)

        handler.onNavigation("https://evil.test")
        assertEquals(listOf(1L, 2L), invalidations)
        handler.onNavigation("https://evil.test/again")
        assertEquals(listOf(1L, 2L), invalidations)
    }

    @Test
    fun bearerReplacementInvalidatesSynchronouslyOnlyWhenItChanges() {
        val store = ServiceBearerStore(serviceURL)
        var invalidations = 0
        val handler = ServiceAuthMessageHandler(store) { invalidations += 1 }
        handler.onNavigation(serviceURL)

        assertTrue(handler.receive(origin, true, serviceURL, token("account-a")))
        assertEquals(1, invalidations)
        assertTrue(handler.receive(origin, true, serviceURL, token("account-a")))
        assertEquals(1, invalidations)
        assertTrue(handler.receive(origin, true, serviceURL, token("account-b")))
        assertEquals(2, invalidations)
        assertTrue(handler.receive(origin, true, serviceURL, """{"bearerToken":null}"""))
        assertEquals(3, invalidations)
    }

    @Test
    fun cookieLogoutPausesNativeAuthUntilSettledIdentityArrives() {
        val store = ServiceBearerStore(serviceURL)
        var invalidations = 0
        val handler = ServiceAuthMessageHandler(store) { invalidations += 1 }
        handler.onNavigation(serviceURL)
        assertFalse(store.authReady.value)

        assertTrue(handler.receive(origin, true, serviceURL, settled(true)))
        assertTrue(store.authReady.value)
        assertEquals(1, invalidations)
        assertTrue(handler.receive(origin, true, serviceURL, """{"authChanging":true}"""))
        assertFalse(store.authReady.value)
        assertEquals(2, invalidations)

        // The host's logout request is pending; no settled message has arrived.
        assertFalse(handler.receive(origin, true, serviceURL, """{"authChanged":false}"""))
        assertFalse(store.authReady.value)
        assertEquals(2, invalidations)

        assertTrue(handler.receive(origin, true, serviceURL, settled(false)))
        assertFalse(store.authReady.value)
        assertEquals(3, invalidations)

        assertTrue(handler.receive(origin, true, serviceURL, settled(true)))
        assertTrue(store.authReady.value)
        assertEquals(4, invalidations)
    }

    @Test
    fun loggedOutBootstrapDoesNotEnableNativeAccess() {
        val store = ServiceBearerStore(serviceURL)
        store.onNavigation(serviceURL)

        assertTrue(store.receive(origin, true, serviceURL, settled(false)))
        assertFalse(store.authReady.value)
        assertNull(store.tokenFor("https://service.test:8443/api/mcp"))
    }

    private fun token(value: String): String = """{"bearerToken":"$value"}"""

    private fun settled(nativeAccess: Boolean): String = """{"authChanged":true,"nativeAccess":$nativeAccess}"""
}
