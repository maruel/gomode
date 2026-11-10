// Tests opaque resource independence, stream recovery, and conversation retirement.

import { afterEach, beforeEach, it } from "node:test";
import { expect, vi } from "../tests/expect";
import { deferred } from "../tests/deferred";
import { mcpClient, type McpResourceNotification, type McpSubscribeOptions } from "./McpClient";
import { monitorServiceNotifications, type ServiceNotificationMonitor } from "./ServiceNotifications";

let monitor: ServiceNotificationMonitor | null = null;
let context: string | null;
let items: string | null;
let alerts: string | null;
let configuration: McpSubscribeOptions;
let changed: (event: McpResourceNotification) => void;
const inject = vi.fn<(text: string, generate: boolean) => void>();
const error = vi.fn<(error: unknown) => void>();
const close = vi.fn<() => void>();
function focused(id: string, version: number) {
  return JSON.stringify({ documentCourant: { id }, révision: version });
}
function latestContext() {
  const text = inject.mock.calls.at(-1)?.[0];
  return (
    text
      ?.split("\n")
      .map((line) => JSON.parse(line) as { resource: string; content?: string })
      .find((part) => part.resource === "fixture://context")?.content ?? text
  );
}
async function settle() {
  for (let i = 0; i < 16; i++) await Promise.resolve();
}
beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  context = focused("offscreen", 1);
  items = JSON.stringify({ items: [{ id: "visible", title: "Build" }] });
  alerts = null;
  vi.spyOn(mcpClient, "listModelResources").mockImplementation(async () => ({
    resources: [
      ...(items === null ? [] : [{ uri: "fixture://catalog", name: "catalog" }]),
      ...(context === null ? [] : [{ uri: "fixture://context", name: "context" }]),
    ],
    notifications: alerts !== null,
  }));
  vi.spyOn(mcpClient, "resourceCapabilities").mockResolvedValue({ subscribe: true, listChanged: true });
  vi.spyOn(mcpClient, "readAdvertisedTextResource").mockImplementation(async (uri) =>
    uri === "fixture://context" ? context : uri === "fixture://catalog" ? items : alerts,
  );
  vi.spyOn(mcpClient, "readTextResource").mockImplementation(async (uri, signal, options) => {
    const value = await mcpClient.readAdvertisedTextResource(uri, signal, options);
    if (value === null) throw new Error("fixture resource not advertised");
    return value;
  });
  vi.spyOn(mcpClient, "subscribeResources").mockImplementation((options, callback) => {
    configuration = options;
    changed = callback;
    return { close };
  });
});
afterEach(() => {
  monitor?.close();
  monitor = null;
  vi.restoreAllMocks();
  vi.useRealTimers();
});

it("keeps explicit offscreen focus independent of bounded items on one stream", async () => {
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  expect(latestContext()).toContain('"id":"offscreen"');
  expect(configuration.resourceSubscriptions).toEqual(["fixture://catalog", "fixture://context"]);
  vi.mocked(mcpClient.readAdvertisedTextResource).mockClear();
  context = focused("second", 2);
  changed({ uri: "fixture://context" });
  await settle();
  expect(vi.mocked(mcpClient.readAdvertisedTextResource).mock.calls.map((call) => call[0])).toEqual([
    "fixture://context",
  ]);
  expect(latestContext()).toContain('"id":"second"');
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Build");
  items = JSON.stringify({ items: [] });
  changed({ uri: "fixture://catalog" });
  await settle();
  expect(latestContext()).toContain('"id":"second"');
  expect(inject.mock.calls.every((call) => !call[1])).toBe(true);
  expect(error).not.toHaveBeenCalled();
});

it("reconciles focus publication and reconnect through authoritative context reads", async () => {
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  context = focused("published", 2);
  monitor.refreshContext();
  await settle();
  expect(latestContext()).toContain('"id":"published"');
  context = focused("reconnected", 3);
  configuration.onReady?.();
  await settle();
  expect(latestContext()).toContain('"id":"reconnected"');
  context = null;
  changed({ listChanged: true });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain('"resource":"fixture://context","status":"absent"');
  expect(configuration.resourceSubscriptions).toEqual(["fixture://catalog"]);
});

it("cancels a retired context read before its replacement and rejects its late result", async () => {
  const pending = deferred<string | null>();
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "fixture://context" ? pending.promise : null,
  );
  monitor = monitorServiceNotifications(inject, error, { sessionID: "old" });
  await settle();
  const signal = vi
    .mocked(mcpClient.readAdvertisedTextResource)
    .mock.calls.find((call) => call[0] === "fixture://context")?.[1];
  monitor.close();
  expect(signal?.aborted).toBe(true);
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "fixture://context" ? focused("new", 1) : null,
  );
  monitor = monitorServiceNotifications(inject, error, { sessionID: "new" });
  await settle();
  const count = inject.mock.calls.length;
  pending.resolve(focused("retired", 1));
  await settle();
  expect(inject).toHaveBeenCalledTimes(count);
  expect(latestContext()).toContain('"id":"new"');
  expect(inject.mock.calls.at(-1)?.[0]).not.toContain("retired");
});

it("preserves verified context through malformed reads and retries without blocking alerts", async () => {
  alerts = "[]";
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) => {
    if (uri === "fixture://context") throw new Error("advisory read failed");
    return uri === "fixture://catalog" ? items : alerts;
  });
  alerts = JSON.stringify([{ id: "new", title: "Review", body: "Required" }]);
  changed({ uri: "fixture://context" });
  changed({ uri: "gomode://notifications" });
  await settle();
  expect(error).toHaveBeenCalledOnce();
  const faultSections = inject.mock.calls
    .filter(([, generate]) => !generate)
    .flatMap(([text]) => text.split("\n").map((line) => JSON.parse(line) as { resource: string; status: string }));
  expect(
    faultSections.find((section) => section.resource === "fixture://context" && section.status === "error"),
  ).toEqual({ resource: "fixture://context", status: "error" });
  expect(faultSections.some((section) => section.resource === "fixture://context" && section.status === "absent")).toBe(
    false,
  );
  expect(inject.mock.calls.filter(([, generate]) => generate)).toHaveLength(1);
  context = focused("recovered", 2);
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "fixture://context" ? context : uri === "fixture://catalog" ? items : alerts,
  );
  await vi.advanceTimersByTimeAsync(2000);
  expect(latestContext()).toContain('"id":"recovered"');
  expect(inject.mock.calls.filter((call) => call[1])).toHaveLength(1);
  monitor.close();
  const reads = vi.mocked(mcpClient.readAdvertisedTextResource).mock.calls.length;
  await vi.advanceTimersByTimeAsync(4000);
  expect(mcpClient.readAdvertisedTextResource).toHaveBeenCalledTimes(reads);
});

it("publishes alerts while an independent context read remains pending", async () => {
  alerts = "[]";
  monitor = monitorServiceNotifications(inject, error);
  await settle();
  const pending = deferred<string | null>();
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "fixture://context" ? pending.promise : uri === "fixture://catalog" ? items : alerts,
  );
  changed({ uri: "fixture://context" });
  await settle();
  alerts = JSON.stringify([{ id: "new", title: "Attention", body: "Required" }]);
  changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Attention");
  expect(inject.mock.calls.at(-1)?.[1]).toBe(true);
  monitor.close();
  pending.resolve(focused("late", 1));
  await settle();
  expect(latestContext()).not.toContain('"id":"late"');
});
