// Tests browser manifest availability, secure origins, selected MCP groups, and abortable discovery.

import { afterEach, it } from "node:test";
import { expect, vi } from "../tests/expect";
import { deferred } from "../tests/deferred";
import { discoverBrowserVoice, resolveBrowserVoiceEndpoints } from "./BrowserVoiceManifest";

const originalFetch = globalThis.fetch;
const expected = { service: "example", toolGroup: "items" };
const origin = "https://service.example";
function manifest(url: unknown, tokenEndpoint?: unknown) {
  return {
    apiVersion: 1,
    service: "example",
    webShell: { voiceGateway: { url, tokenEndpoint }, toolGroups: [{ name: "items", endpoint: "/mcp" }] },
  };
}
afterEach(() => {
  globalThis.fetch = originalFetch;
});

it("allows canonical loopback addresses but rejects similarly named remote HTTP domains", () => {
  for (const host of ["127.0.0.1", "127.255.255.255", "localhost", "[::1]"]) {
    expect(resolveBrowserVoiceEndpoints(manifest(`http://${host}:9000`, "/token"), expected, origin)?.gatewayURL).toBe(
      `http://${host}:9000/`,
    );
  }
  for (const host of [
    "127.attacker.example",
    "127.0.0.1.attacker.example",
    "127.0.0.1.example",
    "localhost.attacker.example",
  ]) {
    expect(() => resolveBrowserVoiceEndpoints(manifest(`http://${host}`, "/token"), expected, origin)).toThrow();
  }
});

it("rejects missing or unsupported discovery schema versions before interpreting endpoints", () => {
  for (const apiVersion of [undefined, null, 0, 2, 999, "1"]) {
    expect(() => resolveBrowserVoiceEndpoints({ ...manifest("/"), apiVersion }, expected, origin)).toThrow(/version/i);
    expect(() => resolveBrowserVoiceEndpoints({ ...manifest(undefined), apiVersion }, expected, origin)).toThrow(
      /version/i,
    );
  }
});

it("resolves embedded and externally authorized gateways from the selected tool group", () => {
  expect(resolveBrowserVoiceEndpoints(manifest("/"), expected, origin)).toEqual({
    gatewayURL: `${origin}/`,
    mcpEndpoint: `${origin}/mcp`,
    tokenEndpoint: null,
  });
  expect(resolveBrowserVoiceEndpoints(manifest("https://voice.example", "/voice/token"), expected, origin)).toEqual({
    gatewayURL: "https://voice.example/",
    mcpEndpoint: `${origin}/mcp`,
    tokenEndpoint: `${origin}/voice/token`,
  });
  expect(resolveBrowserVoiceEndpoints(manifest("http://127.0.0.1:9000", "/token"), expected, origin)?.gatewayURL).toBe(
    "http://127.0.0.1:9000/",
  );
  expect(resolveBrowserVoiceEndpoints(manifest(undefined), expected, origin)).toBeNull();
});

it("rejects malformed manifests, ambiguous groups, credential-bearing URLs and remote credential endpoints", () => {
  for (const value of [
    null,
    [],
    {},
    { ...manifest("/"), service: "other" },
    manifest(42),
    { service: "example", webShell: {} },
    { service: "example", webShell: { voiceGateway: { url: "/" }, toolGroups: [] } },
    {
      service: "example",
      webShell: {
        voiceGateway: { url: "/" },
        toolGroups: [
          { name: "items", endpoint: "/a" },
          { name: "items", endpoint: "/b" },
        ],
      },
    },
  ])
    expect(() => resolveBrowserVoiceEndpoints(value, expected, origin)).toThrow();
  for (const url of [
    "http://voice.example",
    "https://u:private@voice.example",
    "https://@voice.example",
    "https://voice.example/path",
    "https://voice.example/path/..",
    "https://voice.example/%2e",
    "https://voice.example/?x=1",
    "https://voice.example/#x",
    "javascript:alert(1)",
    "//voice.example",
  ]) {
    expect(() => resolveBrowserVoiceEndpoints(manifest(url, "/token"), expected, origin)).toThrow();
  }
  expect(() => resolveBrowserVoiceEndpoints(manifest("https://voice.example"), expected, origin)).toThrow();
  for (const endpoint of [
    "https://other.example/token",
    "https://u:p@service.example/token",
    "/token#fragment",
    "/\\other.example/token",
    "//other.example/token",
    "token",
  ]) {
    expect(() => resolveBrowserVoiceEndpoints(manifest("/", endpoint), expected, origin)).toThrow();
    const value = manifest("/");
    value.webShell.toolGroups[0]!.endpoint = endpoint;
    expect(() => resolveBrowserVoiceEndpoints(value, expected, origin)).toThrow();
  }
});

it("distinguishes missing routes/HTML fallbacks from malformed advertised JSON or server failures", async () => {
  const fetch = vi.fn<typeof globalThis.fetch>();
  globalThis.fetch = fetch;
  const controller = new AbortController();
  for (const response of [
    new Response("missing", { status: 404 }),
    new Response("<html/>", { headers: { "Content-Type": "text/html" } }),
  ]) {
    fetch.mockResolvedValueOnce(response);
    expect(await discoverBrowserVoice(expected, controller.signal)).toBeNull();
  }
  fetch.mockResolvedValueOnce(new Response("{", { headers: { "Content-Type": "application/json" } }));
  await expect(discoverBrowserVoice(expected, controller.signal)).rejects.toThrow();
  fetch.mockResolvedValueOnce(new Response("unavailable", { status: 503 }));
  await expect(discoverBrowserVoice(expected, controller.signal)).rejects.toThrow(/HTTP 503/);
  expect(fetch.mock.calls[0]?.[1]).toMatchObject({
    signal: controller.signal,
    credentials: "same-origin",
    cache: "no-store",
    redirect: "error",
  });
});

it("rejects an aborted discovery even when response parsing completes after replacement", async () => {
  const parsed = deferred<unknown>();
  const response = Response.json(manifest("/"));
  vi.spyOn(response, "json").mockReturnValue(parsed.promise);
  globalThis.fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(response);
  const controller = new AbortController();
  const result = discoverBrowserVoice(expected, controller.signal);
  await Promise.resolve();
  controller.abort();
  parsed.resolve(manifest("/"));
  await expect(result).rejects.toThrow();
});
