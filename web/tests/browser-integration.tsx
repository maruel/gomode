// Exercises the public browser integration as a production-bundled, host-neutral consumer.

import { render } from "solid-js/web";
import { Router } from "@solidjs/router";
import BrowserVoiceShell from "../src/BrowserVoiceShell";
import { HostModeProvider } from "../src/HostMode";
import { createFrontendPreparationClient } from "../src/FrontendVoiceTools";

const identity = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: true });
const scope = identity.createScope();
scope.setTools([]);
scope.setDataReady(true);
const root = document.getElementById("app");
if (root)
  render(
    () => (
      <Router
        root={() => (
          <HostModeProvider>
            <BrowserVoiceShell
              service="example"
              toolGroup="items"
              clientName="example-web"
              preparation={scope}
              setupFailed="Voice setup unavailable."
              onError={(error) => console.error("Voice setup failed", error)}
            />
          </HostModeProvider>
        )}
      />
    ),
    root,
  );
