// MCP JSON-RPC client for server guidance, tools, and resources used by browser Go Mode.

import type {
  MetaObject,
  RequestMeta,
  ResourcesCapability,
  ResourceDescriptor,
  ServerDiscoverResult,
} from "../../sdk/mcp/ts/v1/types.gen";
import { readMcpNotificationStream } from "./McpEvents";
import { NetworkRequestError, observeNetworkChanges } from "./NetworkChanges";
import {
  MODEL_CONTEXT_META_KEY,
  MAX_MODEL_RESOURCES,
  parseContextUpdate,
  type ContextSnapshot,
} from "./ConversationContext";

const MCP_PROTOCOL_VERSION = "2026-07-28";
const CLIENT_VERSION = "1.0.0";
let mcpEndpoint: string | null = null;
let clientName = "gomode-web";
let bearerTokenProvider: (() => string | null | Promise<string | null>) | null = null;
let configuration = 0;
const contextObservers = new Set<{
  sessionID: string;
  receive: (
    uri: string,
    context: ContextSnapshot | Error | undefined,
    authoritative: boolean,
    publicText: string | undefined,
  ) => void;
}>();

/** Configure the MCP endpoint advertised by the current Go Mode host. */
export function configureMcpClient(
  endpoint: string,
  name: string,
  tokenProvider?: () => string | null | Promise<string | null>,
): void {
  if (
    endpoint.includes("\\") ||
    endpoint.startsWith("//") ||
    (!endpoint.startsWith("/") && !/^https?:\/\//i.test(endpoint))
  ) {
    throw new Error("MCP endpoint must be a same-origin absolute path or URL");
  }
  const url = new URL(endpoint, window.location.origin);
  if (url.origin !== window.location.origin || url.username !== "" || url.password !== "" || url.hash !== "") {
    throw new Error("MCP endpoint must be a same-origin absolute path or URL");
  }
  // A path keeps host cookies under fetch's default same-origin credential policy.
  mcpEndpoint = `${url.pathname}${url.search}`;
  clientName = name;
  bearerTokenProvider = tokenProvider ?? null;
  configuration++;
  contextObservers.clear();
  for (const subscription of _subscriptions) subscription.close();
  _subscriptions.clear();
  _toolCache.clear();
}

async function authenticatedFetch(path: string, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers);
  const token = await bearerTokenProvider?.();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  try {
    return await fetch(path, { ...init, headers });
  } catch (error) {
    if (error instanceof TypeError || (error instanceof DOMException && error.name === "TimeoutError"))
      throw new NetworkRequestError(error.message, { cause: error });
    throw error;
  }
}

function configuredEndpoint(): string {
  if (mcpEndpoint === null) throw new Error("Go Mode MCP endpoint has not been configured");
  return mcpEndpoint;
}

type JsonObject = Record<string, unknown>;

function mcpMeta(options?: McpToolCallOptions): RequestMeta {
  return {
    "io.modelcontextprotocol/protocolVersion": MCP_PROTOCOL_VERSION,
    "io.modelcontextprotocol/clientInfo": {
      name: clientName,
      version: CLIENT_VERSION,
    },
    "io.modelcontextprotocol/clientCapabilities": {},
    "openai/locale": options?.locale,
    "openai/session": options?.sessionID,
    "openai/userAgent": `gomode-web/${CLIENT_VERSION}`,
    "openai/userLocation": { timezone: Intl.DateTimeFormat().resolvedOptions().timeZone },
  };
}

let _idCounter = 0;
let _toolCache = new Map<string, McpToolDescriptor>();
const _subscriptions = new Set<McpResourceSubscription>();

async function mcpRequest(
  method: string,
  params: JsonObject,
  opts: McpToolCallOptions & { name?: string; paramHeaders?: Record<string, string>; signal?: AbortSignal },
): Promise<unknown> {
  const captured = configuration;
  const id = ++_idCounter;
  const headers: Record<string, string> = {
    Accept: "application/json, text/event-stream",
    "Content-Type": "application/json",
    "Mcp-Protocol-Version": MCP_PROTOCOL_VERSION,
    "Mcp-Method": method,
    ...opts.paramHeaders,
  };
  if (opts.name !== undefined) headers["Mcp-Name"] = opts.name;

  const meta = mcpMeta(opts);

  const resp = await authenticatedFetch(configuredEndpoint(), {
    method: "POST",
    headers,
    body: JSON.stringify({
      jsonrpc: "2.0",
      id,
      method,
      params: { ...params, _meta: meta },
    }),
    signal: opts.signal,
  });
  if (!resp.ok) throw new Error(`MCP ${method} returned HTTP ${resp.status}`);
  let rpc: { result?: unknown; error?: { code: number; message: string } };
  try {
    const decoded: unknown = await resp.json();
    if (typeof decoded !== "object" || decoded === null || Array.isArray(decoded)) {
      throw new TypeError("Invalid MCP envelope");
    }
    const error: unknown = "error" in decoded ? decoded.error : undefined;
    if (error !== undefined) {
      if (
        typeof error !== "object" ||
        error === null ||
        !("code" in error) ||
        typeof error.code !== "number" ||
        !("message" in error) ||
        typeof error.message !== "string"
      ) {
        throw new TypeError("Invalid MCP error");
      }
    } else if (!("result" in decoded)) {
      throw new TypeError("Missing MCP result");
    }
    rpc = decoded;
  } catch (cause) {
    if (opts.signal?.aborted || (cause instanceof Error && cause.name === "AbortError")) throw cause;
    console.warn("Invalid MCP response", cause);
    throw new Error("Service returned an invalid MCP response. The request outcome is unknown.", { cause });
  }
  if (opts.signal?.aborted) throw new DOMException("Resource request retired", "AbortError");
  if (rpc.error) throw Object.assign(new Error(rpc.error.message), { code: rpc.error.code });
  if (typeof rpc.result === "object" && rpc.result !== null) {
    const result = rpc.result as { _meta?: Record<string, unknown>; contents?: Array<{ uri?: string; text?: string }> };
    const rawContext = result._meta?.["com.fghbuild.gomode/context"];
    const requestedURI = method === "resources/read" ? (params as { uri?: string }).uri : undefined;
    const authoritative =
      requestedURI !== undefined &&
      (rawContext === undefined ||
        (typeof rawContext === "object" &&
          rawContext !== null &&
          (rawContext as { uri?: unknown }).uri === requestedURI));
    const uri = authoritative
      ? requestedURI
      : typeof rawContext === "object" && rawContext !== null
        ? (rawContext as { uri?: unknown }).uri
        : undefined;
    const text = authoritative ? result.contents?.find((part) => part.uri === requestedURI)?.text : undefined;
    let context: ContextSnapshot | Error | undefined;
    if (rawContext !== undefined) {
      try {
        if (authoritative && text === undefined) throw new Error("Context resource is missing text content");
        const value = authoritative ? { ...(rawContext as Record<string, unknown>), content: text } : rawContext;
        context = parseContextUpdate(value);
      } catch (cause) {
        const error = cause instanceof Error ? cause : new Error("Invalid context metadata", { cause });
        // Only this resource's own authoritative reconciliation can fail its
        // read. A tool may already have committed; its acknowledgment is advisory.
        if (authoritative) throw error;
        context = error;
      }
    }
    if (captured === configuration && opts.sessionID !== undefined && (context !== undefined || text !== undefined)) {
      for (const observer of contextObservers) {
        if (observer.sessionID === opts.sessionID)
          if (typeof uri === "string") observer.receive(uri, context, authoritative, text);
      }
    }
  }
  return rpc.result;
}

export interface McpToolAnnotations {
  title?: string;
  readOnlyHint?: boolean;
  destructiveHint?: boolean;
  idempotentHint?: boolean;
  openWorldHint?: boolean;
}

export interface McpToolDescriptor {
  name: string;
  title?: string;
  description: string;
  inputSchema: JsonObject;
  outputSchema?: JsonObject;
  annotations?: McpToolAnnotations;
}

async function mcpServerInstructions(options?: McpToolCallOptions): Promise<string> {
  const discovery = (await mcpRequest("server/discover", {}, { ...options })) as ServerDiscoverResult;
  return discovery.instructions ?? "";
}

async function mcpResourceCapabilities(
  signal?: AbortSignal,
  options?: McpToolCallOptions,
): Promise<ResourcesCapability | null> {
  const discovery = (await mcpRequest("server/discover", {}, { ...options, signal })) as ServerDiscoverResult;
  return discovery.capabilities.resources ?? null;
}

async function mcpListTools(options?: McpToolCallOptions): Promise<McpToolDescriptor[]> {
  const tools: McpToolDescriptor[] = [];
  let cursor: string | undefined;
  do {
    const result = (await mcpRequest("tools/list", cursor === undefined ? {} : { cursor }, { ...options })) as {
      tools: McpToolDescriptor[];
      nextCursor?: string;
    };
    tools.push(...result.tools);
    cursor = result.nextCursor;
  } while (cursor !== undefined && cursor !== "");

  _toolCache = new Map(tools.map((tool) => [tool.name, tool]));
  return tools;
}

/** Discover only explicitly advertised model context; unrelated resources remain on demand. */
async function mcpListModelResources(
  signal?: AbortSignal,
  options?: McpToolCallOptions,
): Promise<{ resources: ResourceDescriptor[]; notifications: boolean }> {
  const resources: ResourceDescriptor[] = [];
  let notifications = false;
  const cursors = new Set<string>();
  let cursor: string | undefined;
  do {
    const page = (await mcpRequest("resources/list", cursor === undefined ? {} : { cursor }, {
      ...options,
      signal,
    })) as { resources: ResourceDescriptor[]; nextCursor?: string };
    for (const resource of page.resources) {
      notifications ||= resource.uri === "gomode://notifications";
      if (resource._meta?.[MODEL_CONTEXT_META_KEY] !== true) continue;
      if (
        typeof resource.uri !== "string" ||
        !resource.uri ||
        resource.uri.length > 1024 ||
        resources.some((existing) => existing.uri === resource.uri)
      )
        throw new Error("Invalid model resource identity");
      if (resources.length === MAX_MODEL_RESOURCES) throw new Error("Too many model context resources");
      resources.push(resource);
    }
    cursor = page.nextCursor;
    if (cursor) {
      if (cursors.has(cursor)) throw new Error("Repeated resource cursor");
      cursors.add(cursor);
    }
  } while (cursor);

  return { resources, notifications };
}

/** Read a text resource when its URI is advertised to this scoped client. */
async function mcpReadAdvertisedTextResource(
  uri: string,
  signal?: AbortSignal,
  options?: McpToolCallOptions,
  onAdvertised?: () => void,
): Promise<string | null> {
  let cursor: string | undefined;
  let advertised = false;
  do {
    const page = (await mcpRequest("resources/list", cursor === undefined ? {} : { cursor }, {
      ...options,
      signal,
    })) as {
      resources: Array<{ uri: string }>;
      nextCursor?: string;
    };
    advertised ||= page.resources.some((resource) => resource.uri === uri);
    cursor = page.nextCursor;
  } while (!advertised && cursor !== undefined && cursor !== "");
  if (!advertised) return null;

  onAdvertised?.();
  return mcpReadTextResource(uri, signal, options);
}

/** Read an already advertised identity without enumerating unrelated descriptors. */
async function mcpReadTextResource(uri: string, signal?: AbortSignal, options?: McpToolCallOptions): Promise<string> {
  const result = (await mcpRequest("resources/read", { uri }, { ...options, name: uri, signal })) as {
    contents: Array<{ uri: string; text?: string }>;
  };
  const text = result.contents.find((content) => content.uri === uri)?.text;
  if (typeof text !== "string") throw new Error(`MCP resource ${uri} is missing text content`);
  return text;
}

export interface McpToolResult {
  structuredContent: JsonObject;
  isError?: boolean;
  /** Opaque transport metadata, separate from model-visible tool content. */
  _meta?: MetaObject;
}

export interface McpToolCallOptions {
  /** BCP 47 locale for this call, supplied by the voice conversation. */
  locale?: string;
  /** Device-owned identifier shared by calls in the same conversation. */
  sessionID?: string;
}

async function mcpCallTool(name: string, args: JsonObject, opts?: McpToolCallOptions): Promise<McpToolResult> {
  const result = (await mcpRequest(
    "tools/call",
    { name, arguments: args },
    { ...opts, name, paramHeaders: mcpParamHeaders(_toolCache.get(name), args) },
  )) as {
    _meta?: MetaObject;
    structuredContent?: JsonObject;
    content?: Array<{ type?: string; text?: string }>;
    isError?: boolean;
  };
  return {
    _meta: result._meta,
    structuredContent: result.structuredContent ?? textContentAsStructuredError(result.content),
    isError: result.isError,
  };
}

function textContentAsStructuredError(content: Array<{ type?: string; text?: string }> | undefined): JsonObject {
  const text = content?.find((block) => block.type === "text")?.text;
  return text === undefined ? {} : { error: text };
}

function mcpParamHeaders(tool: McpToolDescriptor | undefined, args: JsonObject): Record<string, string> {
  if (tool === undefined) return {};
  const headers: Record<string, string> = {};
  collectMcpParamHeaders(tool.inputSchema, args, headers);
  return headers;
}

function collectMcpParamHeaders(schema: JsonObject, args: unknown, headers: Record<string, string>): void {
  const headerName = schema["x-mcp-header"];
  if (typeof headerName === "string" && args !== null && args !== undefined) {
    // Preserve JavaScript coercion for existing MCP header schemas.
    // eslint-disable-next-line @typescript-eslint/no-base-to-string
    headers[`Mcp-Param-${headerName}`] = encodeMcpHeaderValue(String(args));
  }

  const properties = schema.properties;
  if (!isJsonObject(properties) || !isJsonObject(args)) return;
  for (const [key, child] of Object.entries(properties)) {
    if (!isJsonObject(child)) continue;
    collectMcpParamHeaders(child, args[key], headers);
  }
}

function encodeMcpHeaderValue(value: string): string {
  if (isPlainMcpHeaderValue(value)) return value;
  const bytes = new TextEncoder().encode(value);
  const binary = Array.from(bytes, (byte) => String.fromCharCode(byte)).join("");
  return `=?base64?${btoa(binary)}?=`;
}

function isPlainMcpHeaderValue(value: string): boolean {
  if (value.startsWith("=?base64?") && value.endsWith("?=")) return false;
  if (value.trim() !== value) return false;
  return /^[\x20\x21-\x7e]*$/.test(value);
}

function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** A change reported by an MCP resource subscription. */
export interface McpResourceNotification {
  /** Optional typed host-feed effects; standard MCP URI invalidations omit this. */
  aspects?: readonly string[];
  /** Resource URI whose contents may have changed; unset for a list change. */
  uri?: string;
  /** True when the resource list itself may have changed. */
  listChanged?: boolean;
}

/** Host options for an MCP resource subscription. */
export interface McpSubscribeOptions extends McpToolCallOptions {
  /** Resource URIs to watch for content changes. */
  resourceSubscriptions?: string[];
  /** Also report resource list changes. */
  resourcesListChanged?: boolean;
  /** Called once after the server acknowledges the subscription. */
  onReady?: () => void;
  /** Called when the stream fails; the client retries until closed. */
  onError?: (error: unknown) => void;
}

/** A live MCP resource subscription. Close it to stop listening. */
export interface McpResourceSubscription {
  close(): void;
}

/** Delay before retrying a dropped subscription stream. */
const MCP_SUBSCRIPTION_RETRY_MS = 2000;

/**
 * Subscribes to resource changes so a host learns when a document it cares about
 * changes, instead of discovering it by overwriting a concurrent edit. The
 * server holds the stream open; a dropped stream is retried until close().
 */
function subscribeMcpResources(
  options: McpSubscribeOptions,
  onNotification: (notification: McpResourceNotification) => void,
): McpResourceSubscription {
  const filter: JsonObject = {};
  if (options.resourceSubscriptions !== undefined && options.resourceSubscriptions.length > 0) {
    filter.resourceSubscriptions = options.resourceSubscriptions;
  }
  if (options.resourcesListChanged) filter.resourcesListChanged = true;

  let closed = false;
  let controller: AbortController | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | undefined;

  const report = (notification: McpResourceNotification): void => {
    try {
      onNotification(notification);
    } catch {
      // A host callback must not tear down the stream.
    }
  };

  const scheduleRetry = (error: unknown): void => {
    if (closed) return;
    options.onError?.(error);
    if (!navigator.onLine) return;
    retryTimer = setTimeout(() => void run(), MCP_SUBSCRIPTION_RETRY_MS);
  };

  const run = async (): Promise<void> => {
    if (closed || !navigator.onLine) return;
    const attempt = new AbortController();
    controller = attempt;
    const current = (): boolean => !closed && controller === attempt;
    try {
      const resp = await authenticatedFetch(configuredEndpoint(), {
        method: "POST",
        headers: {
          Accept: "text/event-stream",
          "Content-Type": "application/json",
          "Mcp-Protocol-Version": MCP_PROTOCOL_VERSION,
          "Mcp-Method": "subscriptions/listen",
        },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: ++_idCounter,
          method: "subscriptions/listen",
          params: { notifications: filter, _meta: mcpMeta(options) },
        }),
        signal: attempt.signal,
      });
      if (!current()) {
        await resp.body?.cancel();
        return;
      }
      if (!resp.ok || resp.body === null) {
        scheduleRetry(new Error(`MCP subscriptions/listen returned HTTP ${resp.status}`));
        return;
      }
      let acknowledged = false;
      await readMcpNotificationStream(resp.body, (notification) => {
        if (!current()) return;
        if (notification.method === "notifications/subscriptions/acknowledged") {
          if (!acknowledged) {
            acknowledged = true;
            options.onReady?.();
          }
          return;
        }
        if (notification.method === "notifications/resources/list_changed") {
          report({ listChanged: true });
          return;
        }
        if (notification.method === "notifications/resources/updated") {
          const uri = notification.params?.uri;
          report({ uri: typeof uri === "string" ? uri : undefined });
        }
      });
      if (current()) scheduleRetry(new Error("MCP subscription stream ended"));
    } catch (error) {
      if (current()) scheduleRetry(error);
    }
  };

  const stopObservingNetwork = observeNetworkChanges(() => {
    if (retryTimer !== undefined) clearTimeout(retryTimer);
    retryTimer = undefined;
    const previous = controller;
    controller = null;
    previous?.abort();
    if (!closed) void run();
  });

  const subscription: McpResourceSubscription = {
    close(): void {
      if (closed) return;
      closed = true;
      stopObservingNetwork();
      _subscriptions.delete(subscription);
      if (retryTimer !== undefined) clearTimeout(retryTimer);
      controller?.abort();
    },
  };
  _subscriptions.add(subscription);
  void run();
  return subscription;
}

/** The MCP operations VoiceSession uses, as one object so tests can spy on it. */
export const mcpClient = {
  serverInstructions: mcpServerInstructions,
  resourceCapabilities: mcpResourceCapabilities,
  listTools: mcpListTools,
  listModelResources: mcpListModelResources,
  readAdvertisedTextResource: mcpReadAdvertisedTextResource,
  readTextResource: mcpReadTextResource,
  callTool: mcpCallTool,
  subscribeResources: subscribeMcpResources,
  observeContext(
    sessionID: string,
    receive: (
      uri: string,
      context: ContextSnapshot | Error | undefined,
      authoritative: boolean,
      publicText: string | undefined,
    ) => void,
  ): McpResourceSubscription {
    const observer = { sessionID, receive };
    contextObservers.add(observer);
    return { close: () => contextObservers.delete(observer) };
  },
};
