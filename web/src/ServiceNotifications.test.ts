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
  vi.spyOn(mcpClient, "listModelResources").mockImplementation(async () => ({
    resources: [],
    notifications: snapshot !== null,
  }));
  vi.spyOn(mcpClient, "readTextResource").mockImplementation(async (uri, signal, options) => {
    const value = await read(uri, signal, options);
    if (value === null) throw new Error("fixture absent");
    return value;
  });
  vi.spyOn(mcpClient, "resourceCapabilities").mockResolvedValue({ subscribe: true, listChanged: true });
  read = vi
    .spyOn(mcpClient, "readAdvertisedTextResource")
    .mockImplementation(async (uri) => (uri === "gomode://notifications" ? snapshot : null));
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
it("retires each discovered absence before gated MCP reads while independent quiet context progresses", async (t) => {
  vi.restoreAllMocks();
  const originalFetch = globalThis.fetch;
  t.after(() => {
    globalThis.fetch = originalFetch;
  });
  for (const target of ["fixture://catalog", "fixture://context", "gomode://notifications"]) {
    monitor?.close();
    configureMcpClient("/mcp", "presence-boundary");
    const entered = deferred<void>();
    const release = deferred<void>();
    let present = false;
    let independent = false;
    let unreadable = false;
    let targetReads = 0;
    const published: Array<{ text: string; generate: boolean }> = [];
    const failures: unknown[] = [];
    globalThis.fetch = async (_input, init) => {
      const body = JSON.parse(String(init?.body)) as {
        method: string;
        params: { uri?: string; _meta: { "openai/session": string } };
      };
      expect(body.params._meta["openai/session"]).toBe("presence-conversation");
      let result: unknown;
      if (body.method === "server/discover")
        result = { capabilities: { resources: { subscribe: true, listChanged: true } } };
      else if (body.method === "resources/list")
        result = {
          resources: ["fixture://catalog", "fixture://context", "gomode://notifications"]
            .filter((uri) => uri !== target || present)
            .map((uri) => ({ uri, _meta: { "com.fghbuild.gomode/modelContext": uri !== "gomode://notifications" } })),
        };
      else if (body.method === "resources/read") {
        const uri = body.params.uri;
        if (typeof uri !== "string") throw new Error("Resource read omitted its URI");
        if (uri === target) {
          targetReads++;
          entered.resolve();
          await release.promise;
        }
        const text =
          uri === "fixture://catalog"
            ? JSON.stringify({ items: [{ id: "one", title: independent ? "Independent update" : "Opening" }] })
            : uri === "fixture://context"
              ? JSON.stringify({
                  epoch: "owner",
                  version: independent ? 2 : 1,
                  focus: { id: "one", reference: independent ? "Independent update" : "Opening" },
                  resources: [],
                })
              : JSON.stringify(present && target === uri ? [alert("reappeared")] : []);
        result = { contents: unreadable && uri === target ? [] : [{ uri, text }] };
      } else throw new Error(`Unexpected MCP method: ${String(body.method)}`);
      return new Response(JSON.stringify({ jsonrpc: "2.0", result }), {
        headers: { "Content-Type": "application/json" },
      });
    };
    const source = (_configuration: McpSubscribeOptions, callback: (event: McpResourceNotification) => void) => {
      changed = callback;
      return { close: () => {} };
    };
    monitor = monitorServiceNotifications(
      (text, generate) => published.push({ text, generate }),
      (failure) => failures.push(failure),
      { sessionID: "presence-conversation" },
      source,
    );
    const marker = target === "fixture://catalog" ? '"items"' : '"focus"';
    const quiet = () =>
      published
        .filter((entry) => !entry.generate)
        .map((entry) => entry.text)
        .join("\n\n");
    try {
      await vi.advanceTimersByTimeAsync(0);
      if (target !== "gomode://notifications") expect(quiet()).not.toContain(marker);
      present = true;
      changed({ listChanged: true });
      await entered.promise;
      independent = true;
      changed({ uri: target === "fixture://context" ? "fixture://catalog" : "fixture://context" });
      await vi.advanceTimersByTimeAsync(0);
      expect(published.filter((entry) => !entry.generate).at(-1)!.text).toContain("Independent update");
      expect(targetReads).toBe(1);
      expect(published.every((entry) => !entry.generate)).toBe(true);
      release.resolve();
      await vi.advanceTimersByTimeAsync(0);
      if (target === "gomode://notifications")
        expect(published.find((entry) => entry.generate)?.text).toBe(
          "New notification: Alert reappeared: Body reappeared",
        );
      else expect(quiet()).toContain(marker);
      expect(published.filter((entry) => entry.generate)).toHaveLength(target === "gomode://notifications" ? 1 : 0);
      // An item invalidation already dirties all readers; context coalesces one existing follow-up.
      expect(targetReads).toBe(1);
      t.diagnostic(
        JSON.stringify({ resource: target, targetReads, wakeups: published.filter((entry) => entry.generate).length }),
      );
      expect(failures).toEqual([]);
      // A failed read sends nothing; recovering the same text or alert repeats nothing.
      const sent = published.length;
      unreadable = true;
      changed({ uri: target });
      await vi.advanceTimersByTimeAsync(0);
      expect(failures).toHaveLength(1);
      unreadable = false;
      changed({ uri: target });
      await vi.advanceTimersByTimeAsync(0);
      expect(published).toHaveLength(sent);
    } finally {
      monitor.close();
      release.resolve();
    }
  }
});

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
    const nodes: Array<{ uri: string; _meta?: Record<string, boolean> }> = Array.from({ length: 2000 }, (_, i) => ({
      uri: `mddb://workspaces/workspace/nodes/${i}`,
    }));
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
            resources.unshift({ uri: "fixture://catalog", _meta: { "com.fghbuild.gomode/modelContext": true } });
            if (advertisedAlerts) resources.unshift({ uri: "gomode://notifications" });
          }
          result = { resources, ...(start + 50 < nodes.length ? { nextCursor: String(start + 50) } : {}) };
          break;
        }
        case "resources/read": {
          const uri = body.params.uri;
          expect(["fixture://catalog", "gomode://notifications"]).toContain(uri);
          const text =
            uri === "fixture://catalog"
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
    changed({ uri: "fixture://catalog" });
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

it("sends no baseline, announces each new alert once, and stays quiet on withdrawal", async () => {
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  expect(inject).not.toHaveBeenCalled();
  expect(options.resourceSubscriptions).toEqual(["gomode://notifications"]);
  options.onReady?.();
  await settle();
  expect(inject).not.toHaveBeenCalled();

  snapshot = JSON.stringify([alert("opening"), alert("new")]);
  changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject.mock.calls).toEqual([["New notification: Alert new: Body new", true]]);
  changed({ uri: "gomode://notifications" });
  await settle();
  snapshot = "[]";
  changed({ listChanged: true });
  await settle();
  snapshot = JSON.stringify([alert("new", { body: "Updated text" })]);
  changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject).toHaveBeenCalledOnce();
  expect(error).not.toHaveBeenCalled();
});

it("stays silent on alert withdrawal, empty reads, and read faults", async () => {
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  snapshot = null;
  changed({ listChanged: true });
  await settle();
  snapshot = "[]";
  changed({ listChanged: true });
  await settle();
  read.mockImplementation(async (uri) => {
    if (uri === "gomode://notifications") throw new Error("transport failed");
    return null;
  });
  changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject).not.toHaveBeenCalled();
  expect(error).toHaveBeenCalledOnce();
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
  expect(inject).not.toHaveBeenCalled();
  snapshot = JSON.stringify([alert("one")]);
  await vi.advanceTimersByTimeAsync(2000);
  expect(inject.mock.calls).toEqual([["New notification: Alert one: Body one", true]]);
});

it("announces only live alerts and stays quiet when one expires", async () => {
  snapshot = "[]";
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  snapshot = JSON.stringify([
    alert("expired", { expiresAt: new Date(Date.now() - 1000).toISOString() }),
    alert("live", { expiresAt: new Date(Date.now() + 1000).toISOString() }),
  ]);
  changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject.mock.calls).toEqual([["New notification: Alert live: Body live", true]]);
  await vi.advanceTimersByTimeAsync(1000);
  expect(inject).toHaveBeenCalledOnce();
});

it("coalesces concurrent invalidations and discards pending reads after close", async () => {
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  const pending = deferred<string | null>();
  read.mockReturnValueOnce(pending.promise);
  changed({ uri: "gomode://notifications" });
  changed({ uri: "gomode://notifications" });
  changed({ uri: "gomode://notifications" });
  expect(read).toHaveBeenCalledTimes(2);
  snapshot = JSON.stringify([alert("latest")]);
  pending.resolve("[]");
  await settle();
  expect(read).toHaveBeenCalledTimes(3);
  expect(inject.mock.calls.at(-1)![0]).toContain("Alert latest");

  const retired = deferred<string | null>();
  read.mockReturnValueOnce(retired.promise);
  changed({ uri: "gomode://notifications" });
  monitor.close();
  inject.mockClear();
  retired.resolve(JSON.stringify([alert("retired")]));
  await settle();
  await vi.advanceTimersByTimeAsync(5000);
  expect(inject).not.toHaveBeenCalled();
  expect(close).toHaveBeenCalledOnce();
});

it("watches catalog changes without unadvertised resource subscriptions and bounds large alert text", async () => {
  snapshot = null;
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  expect(options.resourceSubscriptions).toBeUndefined();
  expect(options.resourcesListChanged).toBe(true);
  monitor.close();
  snapshot = "[]";
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  snapshot = JSON.stringify(Array.from({ length: 100 }, (_, i) => alert(String(i), { body: "x".repeat(10000) })));
  changed({ uri: "gomode://notifications" });
  await settle();
  const [text, generate] = inject.mock.calls[0]!;
  expect(generate).toBe(true);
  expect(text.length).toBeLessThanOrEqual(4000);
  expect(text.startsWith("New notification: Alert 0: ")).toBe(true);
});

it("skips unsupported resources and reads static resources without subscribing", async () => {
  vi.mocked(mcpClient.resourceCapabilities).mockResolvedValueOnce(null);
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  expect(read).not.toHaveBeenCalled();
  expect(mcpClient.subscribeResources).not.toHaveBeenCalled();
  monitor.close();
  vi.mocked(mcpClient.resourceCapabilities).mockResolvedValueOnce({});
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  expect(read).toHaveBeenCalled();
  expect(mcpClient.subscribeResources).not.toHaveBeenCalled();
  expect(inject).not.toHaveBeenCalled();
});

it("delivers reference-only changes quietly before waking for a new alert", async () => {
  vi.mocked(mcpClient.listModelResources).mockResolvedValue({
    resources: [{ uri: "fixture://catalog", name: "catalog" }],
    notifications: true,
  });
  let items = JSON.stringify({ items: [{ id: "two", title: "Build", reference: "Task #2", state: "running" }] });
  read.mockImplementation(async (uri) =>
    uri === "fixture://catalog" ? items : uri === "gomode://notifications" ? snapshot : null,
  );
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  expect(options.resourceSubscriptions).toEqual(["fixture://catalog", "gomode://notifications"]);
  inject.mockClear();
  items = JSON.stringify({ items: [{ id: "two", title: "Build", reference: "Task #1", state: "running" }] });
  snapshot = JSON.stringify([alert("new", { itemId: "two" })]);
  changed({ uri: "fixture://catalog" });
  changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject.mock.calls).toEqual([
    [items, false],
    ["New notification: Alert new: Body new [two]", true],
  ]);
  options.onReady?.();
  await settle();
  expect(inject).toHaveBeenCalledTimes(2);
});

it("publishes the whole replacing resource text after removing an item", async () => {
  vi.mocked(mcpClient.listModelResources).mockResolvedValue({
    resources: [{ uri: "fixture://catalog", name: "catalog" }],
    notifications: true,
  });
  let items = JSON.stringify({
    items: [
      { id: "one", title: "Alpha" },
      { id: "two", title: "Beta" },
    ],
  });
  read.mockImplementation(async (uri) =>
    uri === "fixture://catalog" ? items : uri === "gomode://notifications" ? snapshot : null,
  );
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  items = JSON.stringify({ items: [{ id: "two", title: "Beta" }] });
  changed({ uri: "fixture://catalog" });
  await settle();
  expect(inject.mock.calls.at(-1)).toEqual([items, false]);
});

it("preserves supplementary notification title/body text at code-unit bounds", async () => {
  monitor = monitorServiceNotifications(inject, error, {});
  await settle();
  snapshot = JSON.stringify([
    alert("unicode", { title: "t".repeat(255) + "😀suffix", body: "b".repeat(1999) + "𠮷suffix" }),
  ]);
  changed({ uri: "gomode://notifications" });
  await settle();
  const update = inject.mock.calls.find(([, generate]) => generate);
  if (!update) throw new Error("new notification did not wake the conversation");
  expect(update[0]).toBe(`New notification: ${"t".repeat(255)}: ${"b".repeat(1999)}`);
  expect(new TextDecoder().decode(new TextEncoder().encode(update[0]))).toBe(update[0]);
});
