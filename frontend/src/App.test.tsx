// Tests the visible, tab-owned note tool and retirement of its frontend scope.
import { afterEach, test } from "node:test";
import { cleanup, fireEvent, render, screen } from "@solidjs/testing-library";
import { Route, Router } from "@solidjs/router";
import { expect, vi } from "../../web/tests/expect";
import { HostModeProvider } from "../../web/src/HostMode";
import { captureFrontendVoiceTools, executeFrontendVoiceTool } from "../../web/src/FrontendVoiceTools";
import App from "./App";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

test("test note follows frontend calls, clear, reload ownership, and invalid input", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("", { status: 404 }));
  const view = render(() => (
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
  ));
  const snapshot = captureFrontendVoiceTools();
  expect(snapshot.declarations.map((tool) => tool.name)).toEqual(["set_test_note"]);
  expect(await executeFrontendVoiceTool("set_test_note", { text: "Hello world" })).toEqual({ text: "Hello world" });
  expect(screen.getByRole("status", { name: "Test note" })).toHaveTextContent("Hello world");
  expect(await executeFrontendVoiceTool("set_test_note", { text: 5 })).toHaveProperty("error");
  expect(screen.getByRole("status", { name: "Test note" })).toHaveTextContent("Hello world");
  const unicode = "😀".repeat(2000);
  expect(await executeFrontendVoiceTool("set_test_note", { text: unicode })).toEqual({ text: unicode });
  expect(screen.getByRole("status", { name: "Test note" })).toHaveTextContent(unicode);
  expect(await executeFrontendVoiceTool("set_test_note", { text: unicode + "x" })).toHaveProperty("error");
  expect(screen.getByRole("status", { name: "Test note" })).toHaveTextContent(unicode);
  fireEvent.click(screen.getByRole("button", { name: "Clear note" }));
  expect(screen.getByRole("button", { name: "Clear note" })).toBeDisabled();
  view.unmount();
  expect(await snapshot.execute("set_test_note", { text: "Late note" })).toHaveProperty("error");
});
