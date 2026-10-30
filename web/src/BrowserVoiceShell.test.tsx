// Tests prepared browser runtime ownership, native suppression, and discovery replacement races.

import { afterEach, beforeEach, it } from "node:test";
import { cleanup, fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { A, Router } from "@solidjs/router";
import { createSignal } from "solid-js";
import { expect, vi } from "../tests/expect";
import { deferred } from "../tests/deferred";
import BrowserVoiceShell, { type BrowserVoiceShellProps } from "./BrowserVoiceShell";
import { HostModeProvider } from "./HostMode";
import {
  createFrontendPreparationClient,
  type FrontendPreparation,
  type FrontendPreparationScope,
} from "./FrontendVoiceTools";
import { mcpClient } from "./McpClient";
import { voiceSession } from "./VoiceSession";

const originalFetch = globalThis.fetch;
const errors = vi.fn();
let owner: FrontendPreparation;
let scope: FrontendPreparationScope;
const manifest = {
  apiVersion: 1,
  service: "example",
  webShell: { voiceGateway: { url: "/" }, toolGroups: [{ name: "items", endpoint: "/mcp" }] },
};
function mount(
  preparation: () => FrontendPreparationScope,
  options?: Pick<BrowserVoiceShellProps, "authorize" | "mcpEndpoint" | "gatewayHeaders" | "messages">,
) {
  const configuration = options ?? {};
  return render(() => (
    <Router
      root={() => (
        <HostModeProvider>
          <A href="/?goModeHost=1">Native mode</A>
          <BrowserVoiceShell
            service="example"
            toolGroup="items"
            clientName="example-web"
            preparation={preparation()}
            setupFailed="Voice setup unavailable."
            onError={errors}
            {...configuration}
          >
            {() => <div data-testid="voice-runtime">Runtime mounted</div>}
          </BrowserVoiceShell>
        </HostModeProvider>
      )}
    />
  ));
}
beforeEach(() => {
  errors.mockClear();
  window.history.replaceState({}, "", "/");
  owner = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: true });
  scope = owner.createScope();
  globalThis.fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async (url) => {
    if (String(url).endsWith("/gomode.json")) return Response.json(manifest);
    return Response.json({ choices: [], defaultChoiceID: "" });
  });
});
afterEach(() => {
  cleanup();
  owner.dispose();
  delete window.goModeHost;
  globalThis.fetch = originalFetch;
  voiceSession.disconnect();
});

it("owns service notification reads with the connected runtime and rejects retired results", async () => {
  const capabilities = vi.spyOn(mcpClient, "resourceCapabilities").mockResolvedValue({ subscribe: true });
  const read = vi.spyOn(mcpClient, "readAdvertisedTextResource");
  const subscribe = vi.spyOn(mcpClient, "subscribeResources");
  const inject = vi.spyOn(voiceSession, "injectText").mockImplementation(() => {});
  const close = vi.fn();
  const pending = deferred<string | null>();
  read.mockReturnValueOnce(pending.promise);
  subscribe.mockReturnValue({ close });
  try {
    scope.setTools([]);
    scope.setDataReady(true);
    mount(() => scope);
    await screen.findByTestId("voice-runtime");
    expect(read).not.toHaveBeenCalled();
    voiceSession.setState((state) => ({ ...state, connected: true }));
    await waitFor(() => expect(read).toHaveBeenCalledWith("gomode://notifications", expect.any(AbortSignal)));
    scope.setDataReady(false);
    await waitFor(() => expect(screen.queryByTestId("voice-runtime")).toBeNull());
    expect(read.mock.calls[0]![1]?.aborted).toBe(true);
    pending.resolve(JSON.stringify([{ id: "old", title: "Retired alert", body: "Other account" }]));
    await pending.promise;
    expect(inject).not.toHaveBeenCalled();
    expect(subscribe).not.toHaveBeenCalled();
  } finally {
    capabilities.mockRestore();
    read.mockRestore();
    subscribe.mockRestore();
    inject.mockRestore();
  }
});

it("does not discover or mount before explicit readiness, and unmounts/disconnects when revoked", async () => {
  const disconnect = vi.spyOn(voiceSession, "disconnect");
  mount(() => scope);
  expect(globalThis.fetch).not.toHaveBeenCalled();
  scope.setTools([]);
  expect(globalThis.fetch).not.toHaveBeenCalled();
  scope.setDataReady(true);
  await screen.findByTestId("voice-runtime");
  expect(errors).not.toHaveBeenCalled();
  scope.setDataReady(false);
  await waitFor(() => expect(screen.queryByTestId("voice-runtime")).toBeNull());
  expect(disconnect).toHaveBeenCalled();
  disconnect.mockRestore();
});

it("suppresses discovery for native host and query host mode", () => {
  scope.setTools([]);
  scope.setDataReady(true);
  window.goModeHost = { isVoiceConnected: () => true };
  mount(() => scope);
  expect(globalThis.fetch).not.toHaveBeenCalled();
  cleanup();
  delete window.goModeHost;
  window.history.replaceState({}, "", "/?goModeHost=1");
  mount(() => scope);
  expect(globalThis.fetch).not.toHaveBeenCalled();
  expect(screen.queryByTestId("voice-runtime")).toBeNull();
});

it("aborts old discovery and cannot configure old MCP clients after scope replacement", async () => {
  const delayed = deferred<Response>();
  const fetch = vi
    .fn<typeof globalThis.fetch>()
    .mockReturnValueOnce(delayed.promise)
    .mockImplementation(async () => Response.json(manifest));
  globalThis.fetch = fetch;
  scope.setTools([]);
  scope.setDataReady(true);
  const [selected, setSelected] = createSignal(scope);
  // eslint-disable-next-line solid/reactivity -- mount passes this accessor into JSX under the Router.
  mount(selected);
  await waitFor(() => expect(fetch).toHaveBeenCalledOnce());
  const signal = fetch.mock.calls[0]?.[1]?.signal;
  const replacement = owner.createScope();
  setSelected(replacement);
  expect(signal?.aborted).toBe(true);
  replacement.setTools([]);
  replacement.setDataReady(true);
  await screen.findByTestId("voice-runtime");
  delayed.resolve(
    Response.json({
      ...manifest,
      webShell: { ...manifest.webShell, toolGroups: [{ name: "items", endpoint: "/retired-mcp" }] },
    }),
  );
  await Promise.resolve();
  const request = vi
    .fn<typeof globalThis.fetch>()
    .mockResolvedValue(Response.json({ jsonrpc: "2.0", result: { tools: [] } }));
  globalThis.fetch = request;
  await mcpClient.listTools();
  expect(request.mock.calls[0]?.[0]).toBe("/mcp");
  expect(errors).not.toHaveBeenCalled();
});

it("ignores discovery completing after disposal and reports concise host-owned setup errors", async () => {
  const delayed = deferred<Response>();
  globalThis.fetch = vi.fn<typeof globalThis.fetch>().mockReturnValue(delayed.promise);
  scope.setTools([]);
  scope.setDataReady(true);
  const rendered = mount(() => scope);
  rendered.unmount();
  delayed.resolve(Response.json(manifest));
  await Promise.resolve();
  expect(screen.queryByTestId("voice-runtime")).toBeNull();
  globalThis.fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json({ service: "wrong" }));
  mount(() => scope);
  expect(await screen.findByRole("alert")).toHaveTextContent("Voice setup unavailable.");
  expect(errors).toHaveBeenCalledOnce();
});

it("releases an active browser runtime when routing enables host mode", async () => {
  scope.setTools([]);
  scope.setDataReady(true);
  const disconnect = vi.spyOn(voiceSession, "disconnect");
  mount(() => scope);
  await screen.findByTestId("voice-runtime");
  const before = disconnect.mock.calls.length;
  fireEvent.click(screen.getByRole("link", { name: "Native mode" }));
  await waitFor(() => expect(screen.queryByTestId("voice-runtime")).toBeNull());
  expect(disconnect.mock.calls.length).toBeGreaterThan(before);
  disconnect.mockRestore();
});

it("configures scoped embedded MCP and gateway headers while translating the lazy overlay", async () => {
  const requests: Array<{ url: string; headers: Headers }> = [];
  globalThis.fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async (url, init) => {
    requests.push({ url: String(url), headers: new Headers(init?.headers) });
    if (String(url).endsWith("/gomode.json")) return Response.json(manifest);
    if (String(url).startsWith("/mcp")) return Response.json({ jsonrpc: "2.0", result: { tools: [] } });
    return Response.json({ choices: [], defaultChoiceID: "" });
  });
  scope.setTools([]);
  scope.setDataReady(true);
  mount(() => scope, {
    mcpEndpoint: (endpoint) => `${endpoint}?session=original`,
    gatewayHeaders: { "X-Example-Session": "original" },
    messages: (defaults) => ({ ...defaults, connect: "Speak now" }),
  });
  await screen.findByTestId("voice-runtime");
  expect(screen.getByRole("button", { name: "Speak now" })).toBeInTheDocument();
  await mcpClient.listTools();
  expect(requests.some((request) => request.url === "/mcp?session=original")).toBe(true);
  await waitFor(() =>
    expect(
      requests.some(
        (request) => request.url.includes("/voice/choices") && request.headers.get("X-Example-Session") === "original",
      ),
    ).toBe(true),
  );
});

it("configures external gateways without forwarding host headers or exchanging tokens during discovery", async () => {
  const requests: Array<{ url: string; headers: Headers }> = [];
  globalThis.fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async (url, init) => {
    requests.push({ url: String(url), headers: new Headers(init?.headers) });
    if (String(url).endsWith("/gomode.json"))
      return Response.json({
        ...manifest,
        webShell: {
          ...manifest.webShell,
          voiceGateway: { url: "https://voice.example", tokenEndpoint: "/authorize" },
        },
      });
    return Response.json({ choices: [], defaultChoiceID: "" });
  });
  const authorize = vi.fn(async () => ({
    kind: "example",
    instanceID: "test",
    baseURL: window.location.origin,
    token: "scoped",
  }));
  scope.setTools([]);
  scope.setDataReady(true);
  mount(() => scope, { authorize, gatewayHeaders: { "X-Example-Session": "original" } });
  await screen.findByTestId("voice-runtime");
  await waitFor(() => expect(requests.some((request) => request.url.startsWith("https://voice.example/"))).toBe(true));
  expect(authorize).not.toHaveBeenCalled();
  expect(
    requests
      .filter((request) => request.url.startsWith("https://voice.example/"))
      .every((request) => !request.headers.has("X-Example-Session")),
  ).toBe(true);
});

it("rejects a missing external authorization adapter and unsafe host endpoint decoration before runtime mounting", async () => {
  scope.setTools([]);
  scope.setDataReady(true);
  globalThis.fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(
    Response.json({
      ...manifest,
      webShell: {
        ...manifest.webShell,
        voiceGateway: { url: "https://voice.example", tokenEndpoint: "/authorize" },
      },
    }),
  );
  mount(() => scope);
  await screen.findByRole("alert");
  expect(screen.queryByTestId("voice-runtime")).toBeNull();
  cleanup();
  globalThis.fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json(manifest));
  mount(() => scope, { mcpEndpoint: () => "https://other.example/mcp" });
  await screen.findByRole("alert");
  expect(screen.queryByTestId("voice-runtime")).toBeNull();
});

for (const interruption of ["disposal", "replacement", "native mode"]) {
  it(`cannot connect after a click's device enumeration outlives ${interruption}`, async () => {
    const enumeration = deferred<void>();
    const enumerate = vi.spyOn(voiceSession, "enumerateDevices").mockResolvedValue();
    const connect = vi.spyOn(voiceSession, "connect").mockResolvedValue();
    const prepare = vi.spyOn(voiceSession, "prepareAudio").mockImplementation(() => {});
    try {
      scope.setTools([]);
      scope.setDataReady(true);
      const [selected, setSelected] = createSignal(scope);
      // eslint-disable-next-line solid/reactivity -- mount reads the accessor in JSX under the Router.
      mount(selected);
      await screen.findByTestId("voice-runtime");
      enumerate.mockImplementation(() => enumeration.promise);
      fireEvent.click(screen.getByRole("button", { name: "Connect voice assistant" }));
      expect(prepare).toHaveBeenCalledOnce();
      expect(connect).not.toHaveBeenCalled();
      if (interruption === "replacement") {
        const replacement = owner.createScope();
        replacement.setTools([]);
        replacement.setDataReady(true);
        setSelected(replacement);
        await screen.findByTestId("voice-runtime");
      } else {
        if (interruption === "disposal") scope.dispose();
        else fireEvent.click(screen.getByRole("link", { name: "Native mode" }));
        await waitFor(() => expect(screen.queryByTestId("voice-runtime")).toBeNull());
      }
      const fetch = vi.mocked(globalThis.fetch);
      const requests = fetch.mock.calls.length;
      enumeration.resolve();
      await enumeration.promise;
      await Promise.resolve();
      expect(connect).not.toHaveBeenCalled();
      expect(fetch.mock.calls).toHaveLength(requests);
    } finally {
      enumerate.mockRestore();
      connect.mockRestore();
      prepare.mockRestore();
    }
  });
}
