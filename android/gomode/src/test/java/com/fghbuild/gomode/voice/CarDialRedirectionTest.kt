// Verifies exact caller-identifier matching without converting ordinary dialing into Go Mode calls.
package com.fghbuild.gomode.voice

import android.net.Uri
import com.fghbuild.gomode.data.ServiceInstance
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CarDialRedirectionTest {
    private val server = ServiceInstance("00000000-0000-0000-0000-000000000001", "Home")

    @Test
    fun onlyPresentationSeparatorsCanBeRemovedFromAnExactSavedIdentity() {
        assertEquals(
            CarDialTarget.Server(server),
            resolveCarDial(Uri.fromParts("tel", "+1 (867) 555-01.01", null), settings()),
        )
        listOf(
            "+186755501010",
            "+18675550101,1",
            "+18675550101;ext=1",
            "+186755501A1",
            "++18675550101",
            "5550123",
            "18675550101",
        ).forEach { number ->
            assertEquals(
                number,
                CarDialTarget.Unrecognized,
                resolveCarDial(Uri.fromParts("tel", number, null), settings()),
            )
        }
        assertEquals(
            CarDialTarget.Unrecognized,
            resolveCarDial(Uri.fromParts("gomode", "+18675550101", null), settings()),
        )
        assertEquals(
            CarDialTarget.Unrecognized,
            resolveCarDial(Uri.fromParts("tel", "+18675550101", "extra"), settings()),
        )
    }

    @Test
    fun removedAndAmbiguousServersDoNotIntercept() {
        val handle = Uri.fromParts("tel", "+18675550101", null)
        assertEquals(CarDialTarget.Unrecognized, resolveCarDial(handle, settings().copy(services = emptyList())))
        assertEquals(
            CarDialTarget.Unrecognized,
            resolveCarDial(handle, settings().copy(services = listOf(server, server.copy(label = "Duplicate")))),
        )
    }

    @Test
    fun genericNumberResolvesTheLatestSelectedSavedServerWhileSpecificNumbersStayFixed() {
        val other = ServiceInstance("other", "Other")
        val first = settings().copy(services = listOf(server, other))
        val second = first.copy(activeServiceId = other.id)
        val handle = Uri.fromParts("tel", "+1 (867) 555-01.00", null)
        assertEquals(CarDialTarget.Server(server), resolveCarDial(handle, first))
        assertEquals(CarDialTarget.Server(other), resolveCarDial(handle, second))
        assertEquals(
            CarDialTarget.Server(server),
            resolveCarDial(Uri.fromParts("tel", carCallerNumber(1), null), second),
        )
    }

    @Test
    fun genericNumberWithoutOneAvailableActiveServerRemainsRecognized() {
        val handle = Uri.fromParts("tel", GO_MODE_CAR_NUMBER, null)
        val ready = settings()
        listOf(
            null,
            ready.copy(loadState = SettingsLoadState.Loading),
            ready.copy(loadState = SettingsLoadState.Failed("Unavailable")),
            ready.copy(activeServiceId = ""),
            ready.copy(activeServiceId = "removed"),
            ready.copy(services = emptyList()),
            ready.copy(services = listOf(server, server.copy(label = "Duplicate"))),
        ).forEach { assertEquals(CarDialTarget.Unavailable, resolveCarDial(handle, it)) }
        assertEquals(CarDialTarget.Unrecognized, resolveCarDial(Uri.fromParts("tel", "5550123", null), null))
    }

    @Test
    fun genericNumberRejectsAlternateSchemesFragmentsAndExtraDialCharacters() {
        listOf(
            "${GO_MODE_CAR_NUMBER}9",
            "1$GO_MODE_CAR_NUMBER",
            "18675550100",
            "++18675550100",
            "$GO_MODE_CAR_NUMBER,1",
            "$GO_MODE_CAR_NUMBER;ext=1",
            "${GO_MODE_CAR_NUMBER}A",
            "$GO_MODE_CAR_NUMBER\n",
        ).forEach { number ->
            assertEquals(
                number,
                CarDialTarget.Unrecognized,
                resolveCarDial(Uri.fromParts("tel", number, null), settings()),
            )
        }
        assertEquals(
            CarDialTarget.Unrecognized,
            resolveCarDial(Uri.fromParts("gomode", GO_MODE_CAR_NUMBER, null), settings()),
        )
        assertEquals(
            CarDialTarget.Unrecognized,
            resolveCarDial(Uri.fromParts("tel", GO_MODE_CAR_NUMBER, "extra"), settings()),
        )
    }

    private fun settings() =
        SettingsState(
            services = listOf(server),
            activeServiceId = server.id,
            carCallerSlots = mapOf(server.id to 1),
            loadState = SettingsLoadState.Ready,
        )
}
