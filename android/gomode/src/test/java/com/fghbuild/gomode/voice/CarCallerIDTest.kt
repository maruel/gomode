// Tests reserved car contact numbers and Android HFP address sanitization.
package com.fghbuild.gomode.voice

import android.telephony.PhoneNumberUtils
import com.fghbuild.gomode.data.ServiceInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CarCallerIDTest {
    @Test
    fun contactNumberMatchesBluetoothAndKeepsTheSavedSlotAfterRenaming() {
        val server = ServiceInstance("saved", "Home", url = "https://home.example")
        val renamed = server.copy(label = "Work", url = "https://work.example")
        assertEquals("+18675550101", carCallerNumber(1))
        assertEquals(
            "+18675550101",
            PhoneNumberUtils.stripSeparators(voiceCallAddress(renamed.id, mapOf(server.id to 1)).schemeSpecificPart),
        )
    }

    @Test
    fun genericAndServerSlotsOccupyTheReservedRangeWithoutCollisions() {
        // Durable external contact identities, including both boundaries of the range.
        assertEquals("+18675550100", GO_MODE_CAR_NUMBER)
        assertEquals("+18675550101", carCallerNumber(1))
        assertEquals("+18675550199", carCallerNumber(99))
        assertEquals(100, ((1..99).map(::carCallerNumber) + GO_MODE_CAR_NUMBER).toSet().size)
        assertThrows(IllegalArgumentException::class.java) { carCallerNumber(0) }
        assertThrows(IllegalArgumentException::class.java) { carCallerNumber(100) }
    }

    @Test
    fun unallocatedServerUsesGenericCallerIdentityAndStandaloneCallHasNoServerContact() {
        assertEquals(GO_MODE_CAR_NUMBER, voiceCallAddress("unallocated", emptyMap()).schemeSpecificPart)
        assertEquals("gomode://voice", voiceCallAddress(null, emptyMap()).toString())
    }
}
