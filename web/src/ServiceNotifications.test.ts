// Tests shared browser item snapshots, alert wake-up, quiet baselines, expiry, and monitor isolation.

import { afterEach, beforeEach, it } from "node:test";
import type { Mock } from "@vitest/spy";
import { expect, vi } from "../tests/expect";
import { deferred } from "../tests/deferred";
import {
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
