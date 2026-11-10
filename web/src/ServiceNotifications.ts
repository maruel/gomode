// Monitors advertised opaque model resources and separate actionable alerts for one conversation.

import { boundedModelText, modelResourceContext, modelContextText } from "./ModelContext";
import {
  mcpClient,
  type McpResourceSubscription,
  type McpToolCallOptions,
  type McpResourceNotification,
  type McpSubscribeOptions,
} from "./McpClient";
import type { ContextSnapshot } from "./ConversationContext";
import type { ResourcesCapability } from "../../sdk/mcp/ts/v1/types.gen";

const RESOURCE_URI = "gomode://notifications";
const MAX_CONTEXT_CHARS = 4000;
const MAX_OBSERVED_IDS = 4096;

interface ServiceNotification {
  id: string;
  itemId?: string;
  title: string;
  body: string;
  expiresAt: number | null;
}

export type ServiceResourceSource = (
  options: McpSubscribeOptions,
  receive: (event: McpResourceNotification) => void,
) => McpResourceSubscription;
export interface ServiceNotificationMonitor extends McpResourceSubscription {
  refreshContext(): void;
}

interface WatchedResource {
  controller: AbortController;
  value: string | undefined | Error;
  context: ContextSnapshot | null;
  refreshing: boolean;
  dirty: boolean;
  retry: ReturnType<typeof setTimeout> | null;
}

/** Own this monitor with the conversation; replacing its identity retires every resource. */
export function monitorServiceNotifications(
  injectText: (text: string, generate: boolean) => void,
  onError: (error: unknown) => void,
  options?: McpToolCallOptions,
  source?: ServiceResourceSource,
): ServiceNotificationMonitor {
  let closed = false;
  let subscription: McpResourceSubscription | null = null;
  let subscriptionKey: string | null = null;
  let capabilities: ResourcesCapability | null | undefined;
  let discoveryRunning = false;
  let discoveryDirty = false;
  let discoveryRetry: ReturnType<typeof setTimeout> | null = null;
  let queued = false;
  let previousContext: string | null = null;
  let alerts: ServiceNotification[] | undefined;
  let alertsAvailable: boolean | undefined;
  let alertError = false;
  let alertBaseline = true;
  let alertRunning = false;
  let alertDirty = false;
  let alertTimer: ReturnType<typeof setTimeout> | null = null;
  const resources = new Map<string, WatchedResource>();
  const retired = new Set<string>();
  const observed = new Set<string>();
  const unseen = new Set<string>();
  const controller = new AbortController();

  function publish() {
    if (closed || queued) return;
    queued = true;
    queueMicrotask(() => {
      queued = false;
      if (closed || capabilities === undefined) return;
      try {
        const uris = capabilities?.subscribe ? [...resources.keys(), ...(alertsAvailable ? [RESOURCE_URI] : [])] : [];
        const key = JSON.stringify(uris);
        if (subscription !== null && key !== subscriptionKey) {
          subscription.close();
          subscription = null;
        }
        if (subscription === null && (uris.length > 0 || capabilities?.listChanged)) {
          subscriptionKey = key;
          subscription = (source ?? mcpClient.subscribeResources)(
            {
              ...options,
              resourceSubscriptions: uris.length > 0 ? uris : undefined,
              resourcesListChanged: capabilities?.listChanged,
              onReady: () => {
                for (const [uri, record] of resources) void refresh(uri, record);
                void refreshAlerts();
              },
              onError,
            },
            (event) => {
              if (event.listChanged) {
                void discover();
                return;
              }
              if (event.uri === RESOURCE_URI) void refreshAlerts();
              for (const [uri, record] of resources) {
                if (
                  event.uri === uri ||
                  record.context?.dependencies.some(
                    (dependency) =>
                      dependency.uris.includes(event.uri ?? "") &&
                      (event.aspects === undefined ||
                        dependency.aspects === undefined ||
                        dependency.aspects.some((aspect) => event.aspects?.includes(aspect))),
                  )
                )
                  void refresh(uri, record);
              }
            },
          );
        }
        if (resources.size === 0 && alerts === undefined && capabilities !== null) return;
        const liveAlerts = (alerts ?? []).filter((alert) => alert.expiresAt === null || alert.expiresAt > Date.now());
        const alertContext = alertError
          ? modelResourceContext(RESOURCE_URI, new Error(), MAX_CONTEXT_CHARS)
          : alertsAvailable === false
            ? modelResourceContext(RESOURCE_URI, null, MAX_CONTEXT_CHARS)
            : alerts === undefined
              ? modelResourceContext(RESOURCE_URI, undefined, MAX_CONTEXT_CHARS)
              : notificationContext(liveAlerts, new Set());
        const context = [
          modelContextText([
            ...[...retired].map((uri) => [uri, null] as const),
            ...[...resources].map(([uri, record]) => [uri, record.value] as const),
          ]),
          alertContext,
        ]
          .filter(Boolean)
          .join("\n");
        if (context !== previousContext) {
          injectText(context, false);
          previousContext = context;
        }
        if (closed) return;
        const fresh = liveAlerts.filter((alert) => unseen.has(alert.id));
        if (fresh.length) injectText(notificationContext(fresh, unseen), true);
        unseen.clear();
      } catch (error) {
        if (!closed) onError(error);
      }
    });
  }

  async function refresh(uri: string, record: WatchedResource) {
    if (closed || resources.get(uri) !== record) return;
    record.dirty = true;
    if (record.retry !== null) clearTimeout(record.retry);
    record.retry = null;
    if (record.refreshing) return;
    record.refreshing = true;
    try {
      while (record.dirty && !closed && resources.get(uri) === record) {
        record.dirty = false;
        try {
          const text = await mcpClient.readTextResource(uri, record.controller.signal, options);
          if (closed || resources.get(uri) !== record) return;
          record.value = record.context?.content ?? text;
        } catch (error) {
          if (closed || resources.get(uri) !== record) return;
          record.value = new Error("Model resource read failed");
          onError(error);
          record.retry = setTimeout(() => void refresh(uri, record), 2000);
          break;
        } finally {
          publish();
        }
      }
    } finally {
      record.refreshing = false;
    }
  }

  const observation =
    options?.sessionID === undefined
      ? null
      : mcpClient.observeContext(options.sessionID, (uri, value, authoritative, text) => {
          const record = resources.get(uri);
          if (closed || record === undefined) return;
          if (value instanceof Error) {
            onError(value);
            return;
          }
          if (value === undefined) {
            if (authoritative && text !== undefined) {
              record.context = null;
              record.value = text;
              publish();
            }
            return;
          }
          if (!authoritative && (record.context === null || record.context.epoch !== value.epoch)) {
            void refresh(uri, record);
            return;
          }
          if (record.context?.epoch === value.epoch && record.context.version > value.version) return;
          record.context = value;
          record.value = value.content;
          publish();
        });

  async function refreshAlerts() {
    alertDirty = true;
    if (closed || alertRunning) return;
    alertRunning = true;
    if (alertTimer !== null) clearTimeout(alertTimer);
    try {
      while (alertDirty && !closed) {
        alertDirty = false;
        try {
          const text = alertsAvailable
            ? await mcpClient.readTextResource(RESOURCE_URI, controller.signal, options)
            : null;
          if (closed) return;
          alertsAvailable = text !== null;
          const next = text === null ? [] : parseNotifications(text);
          const fresh = next.filter((alert) => unseen.has(alert.id) || (!alertBaseline && !observed.has(alert.id)));
          unseen.clear();
          for (const alert of fresh) unseen.add(alert.id);
          alerts = next;
          alertBaseline = false;
          alertError = false;
          for (const alert of next) {
            observed.delete(alert.id);
            observed.add(alert.id);
            if (observed.size > MAX_OBSERVED_IDS) {
              const oldest = observed.values().next().value;
              if (oldest !== undefined) observed.delete(oldest);
            }
          }
          const expiry = Math.min(...next.flatMap((alert) => (alert.expiresAt === null ? [] : [alert.expiresAt])));
          if (Number.isFinite(expiry))
            alertTimer = setTimeout(
              () => {
                publish();
                void refreshAlerts();
              },
              Math.min(Math.max(0, expiry - Date.now()), 2 ** 31 - 1),
            );
        } catch (error) {
          if (closed) return;
          alertError = true;
          onError(error);
          alertTimer = setTimeout(() => void refreshAlerts(), 2000);
          break;
        } finally {
          publish();
        }
      }
    } finally {
      alertRunning = false;
    }
  }

  async function discover() {
    discoveryDirty = true;
    if (closed || discoveryRunning) return;
    discoveryRunning = true;
    if (discoveryRetry !== null) clearTimeout(discoveryRetry);
    try {
      while (discoveryDirty && !closed) {
        discoveryDirty = false;
        capabilities = await mcpClient.resourceCapabilities(controller.signal, options);
        const advertised =
          capabilities === null
            ? { resources: [], notifications: false }
            : await mcpClient.listModelResources(controller.signal, options);
        if (closed) return;
        const uris = new Set(advertised.resources.map((resource) => resource.uri));
        retired.clear();
        for (const [uri, record] of resources) {
          if (uris.has(uri)) continue;
          if (record.retry !== null) clearTimeout(record.retry);
          record.controller.abort();
          resources.delete(uri);
          retired.add(uri);
        }
        for (const uri of uris) {
          if (resources.has(uri)) continue;
          resources.set(uri, {
            controller: new AbortController(),
            value: undefined,
            context: null,
            refreshing: false,
            dirty: false,
            retry: null,
          });
        }
        if (advertised.notifications && alertsAvailable === false) alerts = undefined;
        alertsAvailable = advertised.notifications;
        publish();
        for (const [uri, record] of resources) void refresh(uri, record);
        if (capabilities === null) {
          alertsAvailable = false;
          alerts = [];
        } else void refreshAlerts();
      }
    } catch (error) {
      if (!closed) {
        onError(error);
        discoveryRetry = setTimeout(() => void discover(), 2000);
      }
    } finally {
      discoveryRunning = false;
    }
  }
  void discover();
  return {
    refreshContext() {
      for (const [uri, record] of resources) void refresh(uri, record);
    },
    close() {
      if (closed) return;
      closed = true;
      controller.abort();
      if (discoveryRetry !== null) clearTimeout(discoveryRetry);
      if (alertTimer !== null) clearTimeout(alertTimer);
      for (const record of resources.values()) {
        record.controller.abort();
        if (record.retry !== null) clearTimeout(record.retry);
      }
      observation?.close();
      subscription?.close();
      resources.clear();
      retired.clear();
      observed.clear();
      unseen.clear();
    },
  };
}

function parseNotifications(text: string): ServiceNotification[] {
  if (text.length > 1024 * 1024) throw new Error(`${RESOURCE_URI} exceeds the notification snapshot limit`);
  const value: unknown = JSON.parse(text);
  if (!Array.isArray(value)) throw new Error(`${RESOURCE_URI} must be an array`);
  if (value.length > MAX_OBSERVED_IDS) throw new Error(`${RESOURCE_URI} has too many notifications`);
  const ids = new Set<string>();
  const alerts = value.map((entry: unknown, index) => {
    if (typeof entry !== "object" || entry === null || Array.isArray(entry)) {
      throw new Error(`${RESOURCE_URI} item ${index} must be an object`);
    }
    const item = entry as Record<string, unknown>;
    const string = (field: string): string => {
      const result = item[field];
      if (typeof result !== "string" || result.trim() === "") {
        throw new Error(`${RESOURCE_URI} item ${index} is missing ${field}`);
      }
      return result;
    };
    const id = string("id");
    if (ids.has(id)) throw new Error(`${RESOURCE_URI} has duplicate notification IDs`);
    ids.add(id);
    for (const field of ["itemId", "url"]) if (field in item) string(field);
    const expiresAt = "expiresAt" in item ? Date.parse(string("expiresAt")) : null;
    if (expiresAt !== null && !Number.isFinite(expiresAt)) {
      throw new Error(`${RESOURCE_URI} item ${index} has invalid expiresAt`);
    }
    return {
      id,
      ...(typeof item.itemId === "string" ? { itemId: item.itemId } : {}),
      title: string("title"),
      body: string("body"),
      expiresAt,
    };
  });
  const now = Date.now();
  return alerts.filter((alert) => alert.expiresAt === null || alert.expiresAt > now);
}

function notificationContext(alerts: ServiceNotification[], unseen: Set<string>): string {
  const notifications: object[] = [];
  const context = { resource: RESOURCE_URI, status: "known", data: { notifications, omittedCount: alerts.length } };
  // Selection and wake ownership stay in the monitor; new occurrences have budget priority.
  const ordered = [...alerts].sort((a, b) => Number(unseen.has(b.id)) - Number(unseen.has(a.id)));
  for (const alert of ordered) {
    notifications.push({
      new: unseen.has(alert.id),
      title: boundedModelText(alert.title, 256),
      body: boundedModelText(alert.body, 2000),
      truncated: alert.title.length > 256 || alert.body.length > 2000,
      id: alert.id.length <= 512 ? alert.id : undefined,
      itemId: alert.itemId !== undefined && alert.itemId.length <= 512 ? alert.itemId : undefined,
    });
    context.data.omittedCount--;
    if (JSON.stringify(context).length > MAX_CONTEXT_CHARS) {
      notifications.pop();
      context.data.omittedCount++;
      break;
    }
  }
  return JSON.stringify(context);
}
