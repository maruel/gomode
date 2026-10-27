// Completes an explicit authenticated frontend preparation in native unit fixtures.
package com.fghbuild.gomode.ui.web

import org.json.JSONObject

internal fun completeFrontendPreparation(entry: ServiceShellSession) {
    entry.invalidateIdentity()
    val state = entry.frontendPreparation
    val request = JSONObject().put("frontendDocument", state.document).put("frontendRequest", "fixture")
    val ack = JSONObject(checkNotNull(state.acknowledge(request)))
    state.update(
        JSONObject().put("document", state.document).put("token", ack.getString("frontendToken")).put("ready", true),
    )
}
