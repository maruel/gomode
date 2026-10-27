// Verifies document and identity challenges revoke delayed frontend preparation.
package com.fghbuild.gomode.ui.web

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FrontendPreparationTest {
    @Test
    fun explicitReadyAndNotReadyDoNotDependOnToolCount() {
        val state = FrontendPreparation()
        val request = JSONObject().put("frontendDocument", state.document).put("frontendRequest", "identity")
        val reply = JSONObject(checkNotNull(state.acknowledge(request)))
        val signal = JSONObject().put("document", state.document).put("token", reply.getString("frontendToken"))
        assertFalse(state.ready.value)
        signal.put("ready", true)
        assertTrue(state.accepts(signal))
        state.update(signal)
        assertTrue(state.ready.value)
        signal.put("ready", false)
        state.update(signal)
        assertFalse(state.ready.value)
    }

    @Test
    fun reloadingSamePageAndReplacingIdentityRejectPriorSignals() {
        val state = FrontendPreparation()
        val request = JSONObject().put("frontendDocument", state.document).put("frontendRequest", "first")
        val reply = JSONObject(checkNotNull(state.acknowledge(request)))
        val old =
            JSONObject()
                .put(
                    "document",
                    state.document,
                ).put("token", reply.getString("frontendToken"))
                .put("ready", true)
        state.update(old)
        state.invalidateIdentity()
        assertFalse(state.ready.value)
        assertFalse(state.accepts(old))
        val replacement = JSONObject(checkNotNull(state.acknowledge(request.put("frontendRequest", "second"))))
        assertNotEquals(reply.getString("frontendToken"), replacement.getString("frontendToken"))
        assertFalse(state.accepts(old))
        state.navigate()
        assertFalse(state.accepts(old))
        assertNull(state.acknowledge(request))
    }

    @Test
    fun authOwnershipRejectsMalformedRequestsBeforeAcknowledging() {
        val state = FrontendPreparation()
        val request = JSONObject().put("frontendDocument", state.document)
        for (invalid in listOf(JSONObject.NULL, "", " ", "x".repeat(129), 12, true)) {
            request.put("frontendRequest", invalid)
            assertFalse(state.acceptsAuth(request))
            assertNull(state.acknowledge(request))
        }
        request.put("frontendRequest", "valid")
        assertTrue(state.acceptsAuth(request))
        request.put("frontendDocument", "old-document")
        assertFalse(state.acceptsAuth(request))
    }

    @Test
    fun malformedAndUnboundSignalsCannotPrepareFrontend() {
        val state = FrontendPreparation()
        assertFalse(state.accepts(JSONObject().put("document", state.document).put("ready", true)))
        assertNull(state.acknowledge(JSONObject().put("frontendRequest", "foreign")))
        assertNull(state.acknowledge(JSONObject().put("frontendDocument", state.document)))
        val request = JSONObject().put("frontendDocument", state.document).put("frontendRequest", "identity")
        val reply = JSONObject(checkNotNull(state.acknowledge(request)))
        assertFalse(
            state.accepts(
                JSONObject()
                    .put(
                        "document",
                        state.document,
                    ).put("token", reply.getString("frontendToken"))
                    .put("ready", "true"),
            ),
        )
        assertFalse(state.ready.value)
    }
}
