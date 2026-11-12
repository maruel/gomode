// Stable Bluetooth call identifiers and display formatting for car contacts.
//
// Android's outgoing HFP call list sends a sanitized number without the Telecom display name, so
// each saved server gets a stable numeric caller identifier. The Go Mode shortcut resolves the
// persisted active saved server at dialing time, independently of background page loading; with no
// unique active server or local settings, its dial is canceled without a cellular call. Assignments
// persist before saved services load and survive renaming, URL changes, reordering, and restarts.
// Retired assignments are never recycled, so a saved contact cannot start another server. When all
// 99 slots are allocated, additional servers use the generic caller identity. Assignments are local
// to the app's settings. Android's Contacts app owns the import; Go Mode neither reads contacts nor
// tracks whether one was saved. Standalone voice without a saved server has no contact mapping.
package com.fghbuild.gomode.voice

import android.net.Uri
import androidx.core.net.toUri
import com.fghbuild.gomode.data.MAX_CAR_CALLER_SLOT

// NANPA reserves 555-0100 through 555-0199 as fictitious, non-working numbers:
// https://nanpa.com/numbering/555-line-numbers
// 0100 is the last-server shortcut; persisted server slots own 0101 through 0199.
internal const val GO_MODE_CAR_NUMBER = "+18675550100"

internal fun carCallerNumber(slot: Int): String {
    require(slot in 1..MAX_CAR_CALLER_SLOT) { "Invalid server contact slot" }
    return "+1867555${(100 + slot).toString().padStart(4, '0')}"
}

internal fun voiceCallAddress(
    serverID: String?,
    slots: Map<String, Int>,
): Uri =
    if (serverID == null) {
        "gomode://voice".toUri()
    } else {
        Uri.fromParts("gomode", slots[serverID]?.let(::carCallerNumber) ?: GO_MODE_CAR_NUMBER, null)
    }

internal fun carNumberLabel(number: String): String =
    "${number.take(2)}-${number.substring(2, 5)}-${number.substring(5, 8)}-${number.substring(8)}"
