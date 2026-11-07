// Tests shared item/focus subscriptions, advisory clears, independent alert failures, and conversation retirement.

import { afterEach, beforeEach, it } from "node:test";
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
let monitor: McpResourceSubscription | null = null;
let subscriptions: Array<{
  options: McpSubscribeOptions;
  changed: (event: McpResourceNotification) => void;
  close: () => void;
}>;
const inject = vi.fn<(text: string, generate: boolean) => void>();
const error = vi.fn<(error: unknown) => void>();

function focused(id: string) {
  return JSON.stringify({ items: [{ id: "one", title: "Build" }], activeTaskId: id });
}

async function settle() {
  for (let i = 0; i < 12; i++) await Promise.resolve();
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  snapshot = focused("one");
  subscriptions = [];
  vi.spyOn(mcpClient, "resourceCapabilities").mockResolvedValue({ subscribe: true, listChanged: true });
  vi.spyOn(mcpClient, "readAdvertisedTextResource").mockImplementation(async (uri) =>
    uri === "gomode://items" ? snapshot : null,
  );
  vi.spyOn(mcpClient, "subscribeResources").mockImplementation((options, changed) => {
    const close = vi.fn();
    subscriptions.push({ options, changed, close });
    return { close };
  });
});

afterEach(() => {
  monitor?.close();
  monitor = null;
  vi.restoreAllMocks();
  vi.useRealTimers();
});

it("updates items and focus without alerts, using one stream, and clears ambiguous focus", async () => {
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Active service item: one.");
  snapshot = JSON.stringify({ items: [{ id: "one", title: "Changed title" }], activeTaskId: "one" });
  subscriptions[0]!.changed({ uri: "gomode://items" });
  await settle();
  expect(inject).toHaveBeenCalledTimes(2);
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Changed title");
  expect(inject.mock.calls.every((call) => call[1] === false)).toBe(true);
  snapshot = focused("two");
  subscriptions[0]!.changed({ uri: "gomode://items" });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Active service item: two.");
  snapshot = JSON.stringify({ items: [{ id: "one", title: "Build", active: true }], activeTaskId: "two" });
  subscriptions[0]!.changed({ uri: "gomode://items" });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("No active service item.");
  snapshot = focused("two");
  subscriptions[0]!.changed({ uri: "gomode://items" });
  await settle();
  snapshot = JSON.stringify({ items: [{ id: "one", title: "Build" }], activeTaskId: [] });
  subscriptions[0]!.changed({ uri: "gomode://items" });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("No active service item.");
  expect(error).not.toHaveBeenCalled();
  expect(mcpClient.readAdvertisedTextResource).toHaveBeenCalledWith("gomode://items", expect.any(AbortSignal), {
    sessionID: "conversation",
  });
  expect(subscriptions[0]!.options).toMatchObject({
    sessionID: "conversation",
    resourceSubscriptions: ["gomode://items"],
  });
});

it("refreshes on reconnect and releases the item watcher when the resource is withdrawn", async () => {
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  snapshot = focused("two");
  subscriptions[0]!.options.onReady?.();
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Active service item: two.");
  snapshot = null;
  subscriptions[0]!.changed({ listChanged: true });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("No active service item.");
  expect(subscriptions[0]!.close).toHaveBeenCalledOnce();
  expect(subscriptions[1]!.options.resourceSubscriptions).toBeUndefined();
  expect(subscriptions[1]!.options.resourcesListChanged).toBe(true);
  snapshot = focused("three");
  subscriptions[1]!.changed({ listChanged: true });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Active service item: three.");
  expect(subscriptions[1]!.close).toHaveBeenCalledOnce();
  expect(subscriptions[2]!.options).toMatchObject({
    sessionID: "conversation",
    resourceSubscriptions: ["gomode://items"],
  });
});

it("never publishes a retired conversation's pending read into its replacement", async () => {
  const pending = deferred<string | null>();
  vi.mocked(mcpClient.readAdvertisedTextResource).mockReturnValueOnce(pending.promise);
  monitor = monitorServiceNotifications(inject, error, { sessionID: "old" });
  await settle();
  const signal = vi.mocked(mcpClient.readAdvertisedTextResource).mock.calls[0]![1];
  monitor.close();
  expect(signal?.aborted).toBe(true);
  expect(subscriptions.every((subscription) => vi.mocked(subscription.close).mock.calls.length === 1)).toBe(true);
  expect(inject).not.toHaveBeenCalled();
  subscriptions = [];
  snapshot = focused("new-item");
  monitor = monitorServiceNotifications(inject, error, { sessionID: "new" });
  await settle();
  pending.resolve(focused("old-item"));
  await settle();
  expect(inject).toHaveBeenCalledOnce();
  expect(inject.mock.calls[0]?.[0]).toContain("Active service item: new-item.");
  expect(inject.mock.calls[0]?.[0]).not.toContain("old-item");
  expect(subscriptions).toHaveLength(1);
  expect(subscriptions[0]!.options.sessionID).toBe("new");
});

it("retries transient reads and cancels retry and subscriptions when closed", async () => {
  vi.mocked(mcpClient.readAdvertisedTextResource).mockRejectedValueOnce(new Error("Unavailable"));
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  expect(error).toHaveBeenCalledOnce();
  expect(inject).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(2000);
  expect(inject).toHaveBeenCalledOnce();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Active service item: one.");
  monitor.close();
  expect(subscriptions.at(-1)!.close).toHaveBeenCalledOnce();
  const reads = vi.mocked(mcpClient.readAdvertisedTextResource).mock.calls.length;
  subscriptions[0]!.options.onReady?.();
  await vi.advanceTimersByTimeAsync(4000);
  expect(mcpClient.readAdvertisedTextResource).toHaveBeenCalledTimes(reads);
});

it("clears setup focus withdrawn before the connected watcher starts", async () => {
  snapshot = null;
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  expect(inject.mock.calls[0]?.[0]).toContain("No active service item.");
  subscriptions[0]!.options.onReady?.();
  await settle();
  expect(inject).toHaveBeenCalledOnce();
});

it("clears setup focus when resources are no longer supported", async () => {
  vi.mocked(mcpClient.resourceCapabilities).mockResolvedValue(null);
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  expect(inject.mock.calls[0]?.[0]).toContain("No active service item.");
  expect(mcpClient.readAdvertisedTextResource).not.toHaveBeenCalled();
  expect(subscriptions).toHaveLength(0);
});

it("keeps focus and alert progress independent when either resource is malformed", async () => {
  let alerts = "[]";
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "gomode://items" ? snapshot : alerts,
  );
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  expect(subscriptions).toHaveLength(1);
  expect(subscriptions[0]!.options.resourceSubscriptions).toEqual(["gomode://items", "gomode://notifications"]);
  snapshot = focused("two");
  alerts = "malformed";
  subscriptions[0]!.changed({ uri: "gomode://items" });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Active service item: two.");
  expect(inject.mock.calls.at(-1)?.[1]).toBe(false);
  expect(error).toHaveBeenCalledOnce();

  snapshot = "malformed";
  alerts = JSON.stringify([{ id: "alert", title: "Action required", body: "Review" }]);
  subscriptions[0]!.changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject.mock.calls.at(-2)?.[0]).toContain("No active service item.");
  expect(inject.mock.calls.at(-2)?.[0]).not.toContain("Active service item: two.");
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Action required");
  expect(inject.mock.calls.at(-1)?.[1]).toBe(true);
  snapshot = focused("three");
  subscriptions[0]!.changed({ uri: "gomode://items" });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Active service item: three.");
  expect(inject.mock.calls.filter((call) => call[1])).toHaveLength(1);
  vi.mocked(mcpClient.readAdvertisedTextResource)
    .mockResolvedValueOnce(focused("four"))
    .mockRejectedValueOnce(new Error("Alert resource unavailable"));
  subscriptions[0]!.changed({ uri: "gomode://items" });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Active service item: four.");
  expect(inject.mock.calls.at(-1)?.[1]).toBe(false);
  vi.mocked(mcpClient.readAdvertisedTextResource)
    .mockRejectedValueOnce(new Error("Item resource unavailable"))
    .mockResolvedValueOnce(JSON.stringify([{ id: "next", title: "New alert", body: "Review" }]));
  subscriptions[0]!.changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject.mock.calls.at(-2)?.[0]).toContain("Active service item: four.");
  expect(inject.mock.calls.at(-1)?.[0]).toContain("New alert");
  expect(inject.mock.calls.at(-1)?.[1]).toBe(true);
});

it("does not wake a model after quiet context synchronously retires the conversation", async () => {
  let alerts = "[]";
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "gomode://items" ? snapshot : alerts,
  );
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  inject.mockClear();
  inject.mockImplementationOnce(() => monitor?.close());
  alerts = JSON.stringify([{ id: "new", title: "New alert", body: "Review" }]);
  subscriptions[0]!.changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject).toHaveBeenCalledOnce();
  expect(inject.mock.calls[0]?.[1]).toBe(false);
});

it("keeps refreshing focus while an alert read is still pending", async () => {
  const pending = deferred<string | null>();
  let alerts: Promise<string | null> = Promise.resolve("[]");
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "gomode://items" ? snapshot : alerts,
  );
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  alerts = pending.promise;
  subscriptions[0]!.changed({ uri: "gomode://notifications" });
  await settle();
  snapshot = focused("two");
  subscriptions[0]!.changed({ uri: "gomode://items" });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Active service item: two.");
  monitor.close();
  pending.resolve("[]");
  await settle();
});

it("announces new alerts while an item read is still pending", async () => {
  const pending = deferred<string | null>();
  let items: Promise<string | null> = Promise.resolve(focused("one"));
  let alerts = "[]";
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "gomode://items" ? items : alerts,
  );
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  items = pending.promise;
  subscriptions[0]!.changed({ uri: "gomode://items" });
  await settle();
  alerts = JSON.stringify([{ id: "new", title: "New alert", body: "Review" }]);
  subscriptions[0]!.changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject.mock.calls.at(-2)?.[0]).toContain("Active service item: one.");
  expect(inject.mock.calls.at(-1)?.[0]).toContain("New alert");
  expect(inject.mock.calls.at(-1)?.[1]).toBe(true);
  monitor.close();
  pending.resolve(focused("retired"));
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).not.toContain("retired");
});

it("opens one item stream when the unadvertised-alert lookup finishes first", async () => {
  const initial = deferred<string | null>();
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "gomode://items" ? initial.promise : null,
  );
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  expect(subscriptions).toHaveLength(0);
  expect(inject).not.toHaveBeenCalled();
  initial.resolve(focused("one"));
  await settle();
  expect(subscriptions).toHaveLength(1);
  expect(subscriptions[0]!.options.resourceSubscriptions).toEqual(["gomode://items"]);
});

it("waits for advertised alerts before opening an alerts-only stream", async () => {
  const initial = deferred<string | null>();
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "gomode://notifications" ? initial.promise : null,
  );
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  expect(subscriptions).toHaveLength(0);
  expect(inject).toHaveBeenCalledOnce();
  expect(inject.mock.calls[0]?.[0]).not.toContain("No actionable notifications");
  initial.resolve(JSON.stringify([{ id: "existing", title: "Existing alert", body: "Review" }]));
  await settle();
  expect(subscriptions).toHaveLength(1);
  expect(subscriptions[0]!.options.resourceSubscriptions).toEqual(["gomode://notifications"]);
  expect(inject.mock.calls.at(-1)?.[0]).toContain("Existing alert");
  expect(inject.mock.calls.every((call) => !call[1])).toBe(true);
});

it("preserves unknown setup items while publishing independently available alerts", async () => {
  const initial = deferred<string | null>();
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "gomode://items"
      ? initial.promise
      : JSON.stringify([{ id: "existing", title: "Existing alert", body: "Review" }]),
  );
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  expect(inject).toHaveBeenCalledOnce();
  expect(inject.mock.calls[0]?.[0]).toContain("Existing alert");
  expect(inject.mock.calls[0]?.[0]).not.toContain("No visible service items");
  expect(inject.mock.calls[0]?.[0]).not.toContain("No active service item");
  expect(inject.mock.calls[0]?.[0]).not.toContain("absent items are no longer visible");
  initial.resolve(null);
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("No visible service items");
  expect(inject.mock.calls.at(-1)?.[0]).toContain("No active service item");
});

it("preserves unknown setup alerts while publishing independently available items", async () => {
  const initial = deferred<string | null>();
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "gomode://items" ? focused("one") : initial.promise,
  );
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  expect(inject).toHaveBeenCalledOnce();
  expect(inject.mock.calls[0]?.[0]).toContain("Active service item: one.");
  expect(inject.mock.calls[0]?.[0]).not.toContain("No actionable notifications");
  expect(inject.mock.calls[0]?.[0]).not.toContain("replaces the previous notification snapshot");
  initial.resolve(null);
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("No actionable notifications");
});

it("clears verified empty or withdrawn alerts even while the initial item read is pending", async () => {
  const initial = deferred<string | null>();
  let alerts: string | null = "[]";
  vi.mocked(mcpClient.readAdvertisedTextResource).mockImplementation(async (uri) =>
    uri === "gomode://items" ? initial.promise : alerts,
  );
  monitor = monitorServiceNotifications(inject, error, { sessionID: "conversation" });
  await settle();
  expect(inject).toHaveBeenCalledOnce();
  expect(inject.mock.calls[0]?.[0]).toContain("No actionable notifications");
  expect(inject.mock.calls[0]?.[0]).not.toContain("No visible service items");
  alerts = JSON.stringify([{ id: "new", title: "New alert", body: "Review" }]);
  subscriptions[0]!.changed({ uri: "gomode://notifications" });
  await settle();
  expect(inject.mock.calls.at(-1)?.[1]).toBe(true);
  alerts = null;
  subscriptions[0]!.changed({ listChanged: true });
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("No actionable notifications");
  expect(inject.mock.calls.at(-1)?.[1]).toBe(false);
  expect(subscriptions.at(-1)?.options.resourceSubscriptions).toBeUndefined();
  monitor.close();
  initial.resolve(focused("retired"));
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).not.toContain("retired");
});
