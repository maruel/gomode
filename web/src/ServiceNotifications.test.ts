// Tests shared browser context and measures catalog traffic, burst delivery, idle work, and recovery.

import { afterEach, beforeEach, it } from "node:test";
import type { Mock } from "@vitest/spy";
import { expect, vi } from "../tests/expect";
import { deferred } from "../tests/deferred";
import {
  configureMcpClient,
  mcpClient,
  type McpResourceNotification,
  type McpResourceSubscription,
  type McpSubscribeOptions,
} from "./McpClient";
import { monitorServiceNotifications } from "./ServiceNotifications";

let snapshot: string | null;
let monitor: McpResourceSubscription;
let changed: (event: McpResourceNotification) => void;
let options: McpSubscribeOptions;
const inject = vi.fn<(text: string, generate: boolean) => void>();
const error = vi.fn<(error: unknown) => void>();
const close = vi.fn<() => void>();
let read: Mock<typeof mcpClient.readAdvertisedTextResource>;

function alert(id: string, fields?: Record<string, unknown>) {
  return { id, title: `Alert ${id}`, body: `Body ${id}`, ...fields };
}

// The monitor's read continuations settle without wall-clock waits.
async function settle() {
  for (let i = 0; i < 10; i++) await Promise.resolve();
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  snapshot = JSON.stringify([alert("opening")]);
  vi.spyOn(mcpClient, "resourceCapabilities").mockResolvedValue({ subscribe: true, listChanged: true });
  read = vi
    .spyOn(mcpClient, "readAdvertisedTextResource")
    .mockImplementation(async (uri) => (uri === "gomode://items" ? null : snapshot));
  vi.spyOn(mcpClient, "subscribeResources").mockImplementation((configuration, callback) => {
    options = configuration;
    changed = callback;
    return { close };
  });
});
afterEach(() => {
  monitor?.close();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

// This workload deliberately uses the real resource discovery/read client.
// Only notification delivery is driven directly, so it reports RPC work rather
// than timing an in-memory formatter. Diagnostics are a before/after baseline,
// not assertions preserving today's redundant requests. Optimization budgets:
// warm target changes require no catalog walk and at most one target read;
// idle clients perform no reads; repeated invalidations never repeat speech.
// Run with node's tap reporter to retain every workload diagnostic.
it("measures 2,000-node focus changes, missing resources, bursts, idle, and reconnect traffic", async (t) => {
  vi.restoreAllMocks();
  const originalFetch = globalThis.fetch;
  t.after(() => {
    globalThis.fetch = originalFetch;
  });
  for (const advertisedAlerts of [false, true]) {
    monitor?.close();
    configureMcpClient("/mcp", "update-workload");
    let itemTitle = "Opening page";
    let alerts: ReturnType<typeof alert>[] = [];
    const nodes = Array.from({ length: 2000 }, (_, i) => ({ uri: `mddb://workspaces/workspace/nodes/${i}` }));
    let calls: Record<string, number> = {};
    let requestBytes = 0;
    let responseBytes = 0;
    let descriptors = 0;
    const published: Array<{ text: string; generate: boolean }> = [];
    const failures: unknown[] = [];
    globalThis.fetch = async (_input, init) => {
      const encoded = String(init?.body);
      requestBytes += new TextEncoder().encode(encoded).byteLength;
      const body = JSON.parse(encoded) as {
        method: string;
        params: { cursor?: string; uri?: string; _meta: { "openai/session"?: string } };
      };
      expect(body.params._meta["openai/session"]).toBe("workload-conversation");
      calls[body.method] = (calls[body.method] ?? 0) + 1;
      let result: unknown;
      switch (body.method) {
        case "server/discover":
          result = { capabilities: { resources: { subscribe: true, listChanged: true } } };
          break;
        case "resources/list": {
          const start = Number(body.params.cursor ?? "0");
          const resources = nodes.slice(start, start + 50);
          descriptors += resources.length;
          if (start === 0) {
            resources.unshift({ uri: "gomode://items" });
            if (advertisedAlerts) resources.unshift({ uri: "gomode://notifications" });
          }
          result = { resources, ...(start + 50 < nodes.length ? { nextCursor: String(start + 50) } : {}) };
          break;
        }
        case "resources/read": {
          const uri = body.params.uri;
          expect(["gomode://items", "gomode://notifications"]).toContain(uri);
          const text =
            uri === "gomode://items"
              ? JSON.stringify({ items: [{ id: "selected", title: itemTitle }] })
              : JSON.stringify(alerts);
          result = { contents: [{ uri, text }] };
          break;
        }
        default:
          throw new Error(`Unexpected workload method: ${body.method}`);
      }
      const response = JSON.stringify({ jsonrpc: "2.0", result });
      responseBytes += new TextEncoder().encode(response).byteLength;
      return new Response(response, { headers: { "Content-Type": "application/json" } });
    };
    vi.spyOn(mcpClient, "subscribeResources").mockImplementation((configuration, callback) => {
      options = configuration;
      changed = callback;
      return { close: () => {} };
    });
    const measure = (phase: string) => {
      t.diagnostic(
        JSON.stringify({
          workload: advertisedAlerts ? "items-and-alerts" : "missing-alerts",
          phase,
          calls,
          nodeDescriptors: descriptors,
          requestBytes,
          responseBytes,
          contextBytes: published.reduce((sum, entry) => sum + new TextEncoder().encode(entry.text).byteLength, 0),
          quietUpdates: published.filter((entry) => !entry.generate).length,
          wakeups: published.filter((entry) => entry.generate).length,
        }),
      );
      calls = {};
      requestBytes = 0;
      responseBytes = 0;
      descriptors = 0;
      published.length = 0;
    };
    monitor = monitorServiceNotifications(
      (text, generate) => published.push({ text, generate }),
      (failure) => failures.push(failure),
      { sessionID: "workload-conversation" },
    );
    await vi.advanceTimersByTimeAsync(0);
    expect(published.at(-1)?.text).toContain("Opening page");
    expect(published.every((entry) => !entry.generate)).toBe(true);
    options.onReady?.();
    await vi.advanceTimersByTimeAsync(0);
    measure("setup-and-acknowledgment");

    await vi.advanceTimersByTimeAsync(60_000);
    expect(calls).toEqual({});
    expect(published).toEqual([]);
    measure("idle-minute");

    itemTitle = "New selected page";
    changed({ uri: "gomode://items" });
    await vi.advanceTimersByTimeAsync(0);
    expect(published.at(-1)?.text).toContain(itemTitle);
    expect(published.every((entry) => !entry.generate)).toBe(true);
    measure("focus-change");

    if (advertisedAlerts) alerts = Array.from({ length: 20 }, (_, i) => alert(`burst-${i}`));
    for (let i = 0; i < 20; i++) changed({ uri: "gomode://notifications" });
    await vi.advanceTimersByTimeAsync(0);
    expect(published.filter((entry) => entry.generate)).toHaveLength(advertisedAlerts ? 1 : 0);
    if (advertisedAlerts) expect(published.find((entry) => entry.generate)?.text).toContain("burst-19");
    measure("twenty-invalidations");

    options.onReady?.();
    await vi.advanceTimersByTimeAsync(0);
    expect(published.filter((entry) => entry.generate)).toEqual([]);
    expect(failures).toEqual([]);
    measure("reconnect");
    monitor.close();
    vi.restoreAllMocks();
  }
});

it("provides a quiet baseline, announces new occurrences once, and replaces withdrawn alert context", async () => {
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  expect(inject).toHaveBeenCalledOnce();
  expect(inject.mock.calls[0]![1]).toBe(false);
  expect(inject.mock.calls[0]![0]).toContain("quiet context");
  expect(inject.mock.calls[0]![0]).toContain('"title":"Alert opening"');
  expect(options.resourceSubscriptions).toEqual(["gomode://notifications"]);
  options.onReady?.();
  await settle();
  expect(inject).toHaveBeenCalledOnce();

  snapshot = JSON.stringify([alert("opening"), alert("new")]);
  changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject.mock.calls[1]![1]).toBe(false);
  expect(inject.mock.calls[2]![0]).toContain('"new":true,"title":"Alert new"');
  expect(inject.mock.calls[2]![1]).toBe(true);
  changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject).toHaveBeenCalledTimes(3);

  snapshot = "[]";
  changed({ listChanged: true });
  await settle();
  expect(inject.mock.calls[3]![0]).toContain("No actionable notifications");
  snapshot = JSON.stringify([alert("new", { body: "Updated text" })]);
  changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject.mock.calls[4]![0]).toContain('"new":false');
  expect(inject.mock.calls[4]![0]).toContain("Updated text");
  expect(error).not.toHaveBeenCalled();
});

it("rejects an entire malformed snapshot without consuming its occurrence IDs, then recovers", async () => {
  snapshot = "[]";
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  for (const invalid of [
    [alert("one"), alert("one")],
    [alert("one"), { id: "bad", title: "Incomplete" }],
    [alert("one", { expiresAt: "invalid" })],
    [alert("one", { url: 3 })],
    { notifications: [] },
  ]) {
    snapshot = JSON.stringify(invalid);
    changed({ uri: "gomode://notifications" });
    await settle();
  }
  expect(error).toHaveBeenCalledTimes(5);
  expect(inject).toHaveBeenCalledOnce();
  snapshot = JSON.stringify([alert("one")]);
  await vi.advanceTimersByTimeAsync(2000);
  expect(inject.mock.calls[2]![0]).toContain('"new":true');
});

it("filters expired alerts and removes context when a live alert expires", async () => {
  snapshot = JSON.stringify([
    alert("expired", { expiresAt: new Date(Date.now() - 1000).toISOString() }),
    alert("live", { expiresAt: new Date(Date.now() + 1000).toISOString() }),
  ]);
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  expect(inject.mock.calls[0]![0]).toContain("Alert live");
  expect(inject.mock.calls[0]![0]).not.toContain("Alert expired");
  await vi.advanceTimersByTimeAsync(1000);
  expect(inject.mock.calls[1]![0]).toContain("No actionable notifications");
});

it("coalesces concurrent invalidations and discards pending reads after close", async () => {
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  const pending = deferred<string | null>();
  read.mockResolvedValueOnce(null).mockReturnValueOnce(pending.promise);
  changed({ uri: "gomode://notifications" });
  changed({ uri: "gomode://notifications" });
  changed({ uri: "gomode://notifications" });
  expect(read).toHaveBeenCalledTimes(4);
  snapshot = JSON.stringify([alert("latest")]);
  pending.resolve("[]");
  await settle();
  expect(read).toHaveBeenCalledTimes(6);
  expect(inject.mock.calls.at(-1)![0]).toContain("Alert latest");

  const retired = deferred<string | null>();
  read.mockResolvedValueOnce(null).mockReturnValueOnce(retired.promise);
  changed({ uri: "gomode://notifications" });
  monitor.close();
  inject.mockClear();
  retired.resolve(JSON.stringify([alert("retired")]));
  await settle();
  await vi.advanceTimersByTimeAsync(5000);
  expect(inject).not.toHaveBeenCalled();
  expect(close).toHaveBeenCalledOnce();
});

it("watches catalog changes without unadvertised resource subscriptions and bounds large alert context", async () => {
  snapshot = null;
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  expect(options.resourceSubscriptions).toBeUndefined();
  expect(options.resourcesListChanged).toBe(true);
  monitor.close();
  inject.mockClear();
  snapshot = JSON.stringify(Array.from({ length: 100 }, (_, i) => alert(String(i), { body: "x".repeat(10000) })));
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  expect(inject.mock.calls[0]![0].length).toBeLessThanOrEqual(4000);
  expect(inject.mock.calls[0]![0]).toContain("omitted");
});

it("skips unsupported resources and reads static resources without subscribing", async () => {
  vi.mocked(mcpClient.resourceCapabilities).mockResolvedValueOnce(null);
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  expect(read).not.toHaveBeenCalled();
  expect(mcpClient.subscribeResources).not.toHaveBeenCalled();
  expect(inject.mock.calls).toEqual([["No active service item.", false]]);
  monitor.close();
  inject.mockClear();
  vi.mocked(mcpClient.resourceCapabilities).mockResolvedValueOnce({});
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  expect(inject).toHaveBeenCalledOnce();
  expect(mcpClient.subscribeResources).not.toHaveBeenCalled();
});

it("delivers reference-only changes quietly before waking for a new alert", async () => {
  let items = JSON.stringify({ items: [{ id: "two", title: "Build", reference: "Task #2", state: "running" }] });
  read.mockImplementation(async (uri) => (uri === "gomode://items" ? items : snapshot));
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  expect(options.resourceSubscriptions).toEqual(["gomode://items", "gomode://notifications"]);
  inject.mockClear();
  items = JSON.stringify({ items: [{ id: "two", title: "Build", reference: "Task #1", state: "running" }] });
  snapshot = JSON.stringify([alert("new", { itemId: "two" })]);
  changed({ uri: "gomode://items" });
  await settle();
  expect(inject.mock.calls).toEqual([
    [expect.stringContaining("- Task #1: Build [two] (running)"), false],
    [expect.stringContaining('"new":true,"title":"Alert new"'), true],
  ]);
  expect(inject.mock.calls[0]![0]).toContain("quiet context");
  expect(inject.mock.calls[1]![0]).not.toContain("replaces the previous");
  expect(inject.mock.calls[1]![0]).toContain('"id":"new","itemId":"two"');
  options.onReady?.();
  await settle();
  expect(inject).toHaveBeenCalledTimes(2);
});

it("publishes an explicitly replacing overview after removing an item", async () => {
  let items = JSON.stringify({
    items: [
      { id: "one", title: "Alpha" },
      { id: "two", title: "Beta" },
    ],
  });
  read.mockImplementation(async (uri) => (uri === "gomode://items" ? items : snapshot));
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  items = JSON.stringify({ items: [{ id: "two", title: "Beta" }] });
  changed({ uri: "gomode://items" });
  await settle();
  expect(inject.mock.calls.at(-1)![0]).toContain("replaces the previous visible item snapshot");
  expect(inject.mock.calls.at(-1)![0]).not.toContain("Alpha");
});
