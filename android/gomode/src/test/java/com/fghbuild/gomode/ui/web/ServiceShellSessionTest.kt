// Tests saved-service shell state ownership, replacement generations, and closed-entry authorization.
package com.fghbuild.gomode.ui.web

import android.net.Uri
import android.webkit.WebView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ServiceShellSessionTest {
    @Test
    fun invalidationClearsPreparedToolsAndRejectsThePreviousIdentitySignal() {
        val invalidate =
            listOf<(ServiceShellSession) -> Unit>(
                { it.invalidateIdentity() },
                { it.invalidateSharedIdentity() },
                { it.invalidatePageAuthentication() },
                { it.pageUnavailable("Unavailable") },
            )
        for (action in invalidate) {
            val session = ServiceShellSession(ServiceShellKey("saved", "https://service.test")) {}
            val view = WebView(RuntimeEnvironment.getApplication())
            view.loadUrl(session.key.url)
            session.frontendTools.register(
                view,
                """[{"name":"select","description":"Select","parameters":{}}]""",
                org.json.JSONObject("""{"document":"document","token":"identity","scope":"scope"}"""),
            )
            val request =
                JSONObject()
                    .put(
                        "frontendDocument",
                        session.frontendPreparation.document,
                    ).put("frontendRequest", "owner")
            val ack = JSONObject(checkNotNull(session.frontendPreparation.acknowledge(request)))
            val ready =
                JSONObject()
                    .put("document", session.frontendPreparation.document)
                    .put("token", ack.getString("frontendToken"))
                    .put("ready", true)
            session.frontendPreparation.update(ready)
            action(session)
            assertTrue(session.frontendTools.declarations.isEmpty())
            assertFalse(session.frontendPreparation.ready.value)
            assertFalse(session.frontendPreparation.accepts(ready))
            session.close()
            view.destroy()
        }
    }

    @Test
    fun serviceIdentityAndURLKeepAuthAndLoadStateIndependent() {
        val first = ServiceShellSession(ServiceShellKey("first", "https://first.test/mobile")) {}
        val second = ServiceShellSession(ServiceShellKey("second", "https://second.test/mobile")) {}
        first.bearerStore.onNavigation(first.key.url)
        assertTrue(
            first.bearerStore.receive(
                Uri.parse("https://first.test"),
                true,
                first.key.url,
                """{"bearerToken":"first-account"}""",
            ),
        )
        first.updateLoad(WebShellLoadState.Ready)
        first.reloadBootstrap()
        assertNull(second.bearerStore.tokenFor(second.key.url))
        assertNull(first.bearerStore.tokenFor(second.key.url))
        assertEquals(WebShellLoadState.Loading, second.state.value.load)
        assertEquals(0, second.state.value.bootstrapReloadToken)
        first.close()
        assertNull(first.bearerStore.tokenFor(first.key.url))
        assertFalse(
            first.bearerStore.receive(
                Uri.parse("https://first.test"),
                true,
                first.key.url,
                """{"bearerToken":"late"}""",
            ),
        )
        second.close()
    }

    @Test
    fun oldPageCannotAffectReplacementAndClosedEntryRejectsAllPages() {
        val session = ServiceShellSession(ServiceShellKey("saved", "https://service.test")) {}
        val old = session.openPage()
        val current = session.openPage()
        assertFalse(session.isCurrentPage(old))
        session.closePage(old)
        assertTrue(session.isCurrentPage(current))
        session.closePage(current)
        assertFalse(session.isCurrentPage(current))
        val finalPage = session.openPage()
        session.close()
        assertFalse(session.isCurrentPage(finalPage))
    }

    @Test
    fun retainedRegistryReusesPagesAndClosesDeletedOrReplacedOwners() {
        val registry = ServiceShellRegistry()
        val a = ServiceShellKey("a", "https://first.test")
        val b = ServiceShellKey("b", "https://second.test")
        val first = registry.reconcile(setOf(a, b)).getValue(a)
        var released = 0
        first.ownPage { released++ }
        val generation = first.openPage()
        assertTrue(registry.reconcile(setOf(b, a)).getValue(a) === first)
        assertTrue(first.isCurrentPage(generation))
        val replacement = a.copy(url = "https://replacement.test")
        registry.reconcile(setOf(b, replacement))
        assertEquals(1, released)
        assertFalse(first.isCurrentPage(generation))
        registry.close()
        assertEquals(1, released)
    }

    @Test
    fun sharedCookieAccountChangesInvalidatePeersSynchronouslyButInitialSignInDoesNot() {
        val registry = ServiceShellRegistry()
        val keys =
            setOf(
                ServiceShellKey("a", "https://shared.test/a"),
                ServiceShellKey("b", "https://shared.test/b"),
                ServiceShellKey("c", "https://other.test"),
            )
        val entries = registry.reconcile(keys).values.toList()
        entries.forEach { entry ->
            entry.bearerStore.onNavigation(entry.key.url)
            entry.bearerStore.receive(Uri.parse(entry.key.url), true, entry.key.url, """{"bearerToken":"account"}""")
        }
        val source = entries.first()
        val peer = entries[1]
        val unrelated = entries[2]
        val handler = ServiceAuthMessageHandler(source.bearerStore, { registry.invalidateSharedOrigin(source) }) {}
        handler.receive(Uri.parse(source.key.url), true, source.key.url, """{"bearerToken":"account"}""")
        assertTrue(peer.bearerStore.authReady.value)
        handler.receive(Uri.parse(source.key.url), true, source.key.url, """{"authChanging":true}""")
        assertFalse(peer.bearerStore.authReady.value)
        assertNull(peer.bearerStore.tokenFor(peer.key.url))
        assertFalse(
            peer.bearerStore.receive(Uri.parse(peer.key.url), true, peer.key.url, """{"bearerToken":"stale"}"""),
        )
        assertTrue(unrelated.bearerStore.authReady.value)
        registry.close()
    }

    @Test
    fun rendererRetryRecreatesOnlyThePageAndPreservesItsIdentityAuthority() {
        val session = ServiceShellSession(ServiceShellKey("a", "https://service.test")) {}
        session.bearerStore.onNavigation(session.key.url)
        session.bearerStore.receive(Uri.parse(session.key.url), true, session.key.url, """{"bearerToken":"account"}""")
        val identity = session.bearerStore.identityEpoch.value
        var released = 0
        val page = session.openPage()
        session.ownPage { released++ }
        session.rendererLost()
        assertEquals(1, released)
        assertFalse(session.isCurrentPage(page))
        assertFalse(session.bearerStore.authReady.value)
        assertTrue((session.state.value.load as WebShellLoadState.Failed).canRetry)
        session.reloadWeb()
        assertEquals(1, session.state.value.webViewGeneration)
        assertTrue(session.state.value.pageRequested)
        assertEquals(WebShellLoadState.Reconnecting, session.state.value.load)
        val replacement = session.openPage()
        session.ownPage { released++ }
        session.closePage(page)
        assertEquals(1, released)
        assertTrue(session.isCurrentPage(replacement))
        session.bearerStore.onNavigation(session.key.url)
        session.bearerStore.receive(Uri.parse(session.key.url), true, session.key.url, """{"bearerToken":"account"}""")
        assertEquals(identity, session.bearerStore.identityEpoch.value)
        session.close()
        assertEquals(2, released)
    }

    @Test
    fun ordinaryRetryRequiresFreshValidationWithoutReplacingAccountAuthority() {
        val session = ServiceShellSession(ServiceShellKey("a", "https://service.test")) {}
        val origin = Uri.parse(session.key.url)
        session.bearerStore.onNavigation(session.key.url)
        session.bearerStore.receive(origin, true, session.key.url, """{"bearerToken":"account"}""")
        val identity = session.bearerStore.identityEpoch.value
        session.updateLoad(WebShellLoadState.Failed("Connection failed"))
        session.reloadWeb()
        assertFalse(session.bearerStore.authReady.value)
        assertNull(session.bearerStore.tokenFor(session.key.url))
        assertEquals(identity, session.bearerStore.identityEpoch.value)
        session.bearerStore.onNavigation(session.key.url)
        session.updateLoad(WebShellLoadState.Ready)
        assertFalse(session.bearerStore.authReady.value)
        session.bearerStore.receive(origin, true, session.key.url, """{"bearerToken":"account"}""")
        assertTrue(session.bearerStore.authReady.value)
        assertEquals(identity, session.bearerStore.identityEpoch.value)
        session.reloadWeb()
        session.bearerStore.onNavigation(session.key.url)
        session.bearerStore.receive(origin, true, session.key.url, """{"bearerToken":"replacement"}""")
        assertTrue(session.bearerStore.identityEpoch.value > identity)
        session.close()
    }

    @Test
    fun unsupportedIsolationAndClosedEntriesCannotAllocateReplacementPages() {
        val session = ServiceShellSession(ServiceShellKey("a", "https://service.test")) {}
        session.pageUnavailable("Update Android System WebView.")
        session.reloadWeb()
        assertEquals(0, session.state.value.webViewGeneration)
        assertFalse(session.state.value.pageRequested)
        session.close()
        session.reloadWeb()
        assertEquals(0, session.state.value.webViewGeneration)
    }

    @Test
    fun initialCookieReadinessFromSameOriginPeerPreservesTheExistingIdentity() {
        val registry = ServiceShellRegistry()
        val firstKey = ServiceShellKey("first", "https://shared.test/first")
        val secondKey = ServiceShellKey("second", "https://shared.test/second")
        val entries = registry.reconcile(setOf(firstKey, secondKey))
        val first = entries.getValue(firstKey)
        val second = entries.getValue(secondKey)
        val settled = """{"authChanged":true,"nativeAccess":true}"""
        val origin = Uri.parse("https://shared.test")
        first.bearerStore.onNavigation(first.key.url)
        val firstHandler = ServiceAuthMessageHandler(first.bearerStore, { registry.invalidateSharedOrigin(first) }) {}
        assertTrue(firstHandler.receive(origin, true, first.key.url, settled))
        val firstEpoch = first.bearerStore.authEpoch.value
        second.bearerStore.onNavigation(second.key.url)
        val secondHandler =
            ServiceAuthMessageHandler(second.bearerStore, { registry.invalidateSharedOrigin(second) }) {}
        assertTrue(secondHandler.receive(origin, true, second.key.url, settled))
        assertTrue(first.bearerStore.authReady.value)
        assertTrue(second.bearerStore.authReady.value)
        assertEquals(firstEpoch, first.bearerStore.authEpoch.value)
        assertTrue(secondHandler.receive(origin, true, second.key.url, """{"authChanging":true}"""))
        assertFalse(first.bearerStore.authReady.value)
        assertFalse(second.bearerStore.authReady.value)
        registry.close()
    }
}
