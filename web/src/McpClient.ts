// MCP JSON-RPC client for server guidance, tools, and resources used by browser Go Mode.

import { createApiClient as createMcpApiClient } from "../../sdk/mcp/ts/v1/api.gen";
import { readMcpNotificationStream } from "./McpEvents";

const MCP_PROTOCOL_VERSION = "2026-07-28";
let mcpEndpoint: string | null = null;
let clientName = "gomode-web";
let bearerTokenProvider: (() => string | null | Promise<string | null>) | null = null;

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
  for (const subscription of _subscriptions) subscription.close();
  _subscriptions.clear();
  _toolCache.clear();
}

async function authenticatedFetch(path: string, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers);
  const token = await bearerTokenProvider?.();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return fetch(path, { ...init, headers });
}

function endpoint(): string {
  if (mcpEndpoint === null) throw new Error("Go Mode MCP endpoint has not been configured");
  return mcpEndpoint;
}

type JsonObject = Record<string, unknown>;

function mcpMeta() {
  return {
    "io.modelcontextprotocol/protocolVersion": MCP_PROTOCOL_VERSION,
    "io.modelcontextprotocol/clientInfo": {
      name: clientName,
      version: "1.0.0",
    },
    "io.modelcontextprotocol/clientCapabilities": {},
  };
}

let _idCounter = 0;
let _toolCache = new Map<string, McpToolDescriptor>();
const _subscriptions = new Set<McpResourceSubscription>();

async function mcpRequest(
  method: string,
  params: JsonObject,
  opts: { name?: string; paramHeaders?: Record<string, string> },
): Promise<unknown> {
  const id = ++_idCounter;
  const headers: Record<string, string> = {
    Accept: "application/json, text/event-stream",
    "Content-Type": "application/json",
    "Mcp-Protocol-Version": MCP_PROTOCOL_VERSION,
    "Mcp-Method": method,
    ...opts.paramHeaders,
  };
  if (opts.name !== undefined) headers["Mcp-Name"] = opts.name;

  const resp = await authenticatedFetch(endpoint(), {
    method: "POST",
    headers,
    body: JSON.stringify({
      jsonrpc: "2.0",
      id,
      method,
      params: { ...params, _meta: mcpMeta() },
    }),
  });
  if (!resp.ok) throw new Error(`MCP ${method} returned HTTP ${resp.status}`);
  const rpc = (await resp.json()) as {
    result?: unknown;
    error?: { code: number; message: string };
  };
  if (rpc.error) throw new Error(rpc.error.message);
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

async function mcpServerInstructions(): Promise<string> {
  const api = createMcpApiClient((path, init) => authenticatedFetch(`${endpoint()}${path}`, init));
  return api.serverInstructions();
}

async function mcpListTools(): Promise<McpToolDescriptor[]> {
  const tools: McpToolDescriptor[] = [];
  let cursor: string | undefined;
  do {
    const result = (await mcpRequest("tools/list", cursor === undefined ? {} : { cursor }, {})) as {
      tools: McpToolDescriptor[];
      nextCursor?: string;
    };
    tools.push(...result.tools);
    cursor = result.nextCursor;
  } while (cursor !== undefined && cursor !== "");

  _toolCache = new Map(tools.map((tool) => [tool.name, tool]));
  return tools;
}

/** Read a text resource when its URI is advertised to this scoped client. */
async function mcpReadAdvertisedTextResource(uri: string): Promise<string | null> {
  let cursor: string | undefined;
  let advertised = false;
  do {
    const page = (await mcpRequest("resources/list", cursor === undefined ? {} : { cursor }, {})) as {
      resources: Array<{ uri: string }>;
      nextCursor?: string;
    };
    advertised ||= page.resources.some((resource) => resource.uri === uri);
    cursor = page.nextCursor;
  } while (!advertised && cursor !== undefined && cursor !== "");
  if (!advertised) return null;

  const result = (await mcpRequest("resources/read", { uri }, { name: uri })) as {
    contents: Array<{ uri: string; text?: string }>;
  };
  return result.contents.find((content) => content.uri === uri)?.text ?? null;
}

export interface McpToolResult {
  structuredContent: JsonObject;
  isError?: boolean;
}

async function mcpCallTool(name: string, args: JsonObject): Promise<McpToolResult> {
  const result = (await mcpRequest(
    "tools/call",
    { name, arguments: args },
    { name, paramHeaders: mcpParamHeaders(_toolCache.get(name), args) },
  )) as {
    structuredContent?: JsonObject;
    content?: Array<{ type?: string; text?: string }>;
    isError?: boolean;
  };
  return {
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
  /** Resource URI whose contents may have changed; unset for a list change. */
  uri?: string;
  /** True when the resource list itself may have changed. */
  listChanged?: boolean;
}

/** Host options for an MCP resource subscription. */
export interface McpSubscribeOptions {
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
    retryTimer = setTimeout(() => void run(), MCP_SUBSCRIPTION_RETRY_MS);
  };

  const run = async (): Promise<void> => {
    if (closed) return;
    controller = new AbortController();
    try {
      const resp = await authenticatedFetch(endpoint(), {
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
          params: { notifications: filter, _meta: mcpMeta() },
        }),
        signal: controller.signal,
      });
      if (!resp.ok || resp.body === null) {
        scheduleRetry(new Error(`MCP subscriptions/listen returned HTTP ${resp.status}`));
        return;
      }
      options.onReady?.();
      await readMcpNotificationStream(resp.body, (notification) => {
        if (notification.method === "notifications/resources/list_changed") {
          report({ listChanged: true });
          return;
        }
        if (notification.method === "notifications/resources/updated") {
          const uri = notification.params?.uri;
          report({ uri: typeof uri === "string" ? uri : undefined });
        }
      });
      scheduleRetry(new Error("MCP subscription stream ended"));
    } catch (error) {
      scheduleRetry(error);
    }
  };

  const subscription: McpResourceSubscription = {
    close(): void {
      if (closed) return;
      closed = true;
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
  listTools: mcpListTools,
  readAdvertisedTextResource: mcpReadAdvertisedTextResource,
  callTool: mcpCallTool,
  subscribeResources: subscribeMcpResources,
};
