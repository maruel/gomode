// Publishes the bridge-v2 preparation contract for generic instrumented hosted frontends.
package com.fghbuild.gomode

import com.fghbuild.gomode.ui.web.ServiceShellSession
import com.fghbuild.gomode.ui.web.WebShellLoadState
import org.json.JSONObject

// Abstract transport fixtures have no live document; bridge ownership is exercised separately.
internal fun completeFixtureFrontend(entry: ServiceShellSession) {
    entry.updateLoad(WebShellLoadState.Ready)
    entry.invalidateIdentity()
    val state = entry.frontendPreparation
    val request = JSONObject().put("frontendDocument", state.document).put("frontendRequest", "fixture")
    val ack = JSONObject(checkNotNull(state.acknowledge(request)))
    state.update(
        JSONObject().put("document", state.document).put("token", ack.getString("frontendToken")).put("ready", true),
    )
}

internal val frontendPreparationFixtureScript =
    """
    (() => {
      const binding = window.goModeFrontendDocument;
      let document = null, token = null, request = 0, ready = true, tools = [];
      function publish() {
        if (token === null) return;
        gomodeFrontendReadiness.postMessage(JSON.stringify({document,token,ready,tools:ready?tools:[]}));
      }
      gomodeAuth.onmessage = event => {
        const value = JSON.parse(event.data);
        if (value.frontendDocument !== document || value.frontendRequest !== String(request)) return;
        token = value.frontendToken;
        publish();
      };
      window.fixtureAuth = encoded => {
        const auth = JSON.parse(encoded), id = String(++request);
        token = null;
        const granting = typeof auth.bearerToken === 'string' || (auth.authChanged && auth.nativeAccess);
        binding.then(value => {
          if (granting && id !== String(request)) return;
          document = value;
          gomodeAuth.postMessage(JSON.stringify({...auth,frontendDocument:document,frontendRequest:id}));
        });
      };
      window.fixtureTools = encoded => { tools = JSON.parse(encoded); publish(); };
      window.fixtureReady = value => { ready = value; publish(); };
    })();
    """.trimIndent()
