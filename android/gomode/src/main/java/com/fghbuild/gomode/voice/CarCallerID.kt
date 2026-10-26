// Stable Bluetooth call identifiers and display formatting for car contacts.
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
