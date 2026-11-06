// Tests quiet context replacement, alert retention, and explicit native model wake-up.
package com.fghbuild.gomode.voice

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingVoiceContextTest {
    @Test
    fun quietUpdatesReplaceTheirOverviewWithoutLosingAlerts() {
        val pending = PendingVoiceContext()
        pending.add("Old references", false)
        pending.add("First alert", true)
        pending.add("Current references", false)
        pending.add("Second alert", true)
        assertEquals(
            VoiceContextUpdate("Current references\nFirst alert\nSecond alert", true),
            pending.drain(),
        )
        assertNull(pending.drain())
        pending.add("Quiet references", false)
        assertEquals(VoiceContextUpdate("Quiet references", false), pending.drain())
    }

    @Test
    fun actionableContextRequestsGenerationWithoutInventingUserSpeech() {
        assertEquals(
            """{"kind":"context.update","context":{"text":"Quiet references"}}""",
            Json.encodeToString(gatewayContextUpdate("Quiet references")),
        )
        assertEquals(
            """{"generate":true,"kind":"context.update","context":{"text":"New alert"}}""",
            Json.encodeToString(gatewayContextUpdate("New alert", true)),
        )
    }

    @Test
    fun alertBufferRejectsOverflowWithoutDroppingEarlierOccurrences() {
        val pending = PendingVoiceContext()
        repeat(64) { assertTrue(pending.add("Alert $it", true)) }
        assertFalse(pending.add("Overflow", true))
        assertEquals(
            64,
            pending
                .drain()
                ?.text
                ?.lines()
                ?.size,
        )
    }

    @Test
    fun clearingAConversationDropsItsPendingAlerts() {
        val pending = PendingVoiceContext()
        pending.add("Retired alert", true)
        pending.clear()
        assertNull(pending.drain())
    }
}
