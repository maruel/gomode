// Mount the standalone voice test application with shared browser host detection.
import { Route, Router } from "@solidjs/router";
import { render } from "solid-js/web";
import { HostModeProvider } from "../../web/src/HostMode";
import App from "./App";

const root = document.getElementById("root");
if (root === null) throw new Error("Missing application root");
render(
  () => (
    <Router>
      <Route
        path="/"
        component={() => (
          <HostModeProvider>
            <App />
          </HostModeProvider>
        )}
      />
    </Router>
  ),
  root,
);
