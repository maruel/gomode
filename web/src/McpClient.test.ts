// Tests host-configured MCP routing and identity in the reusable browser client.
import { afterEach, describe, it } from "node:test";
import { expect } from "../tests/expect";

import { configureMcpClient, mcpClient } from "./McpClient";

const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe("configureMcpClient", () => {
  it("routes tool requests to the selected same-origin host endpoint", async () => {
    configureMcpClient("/api/v1/gomode/mcp", "mddb-frontend");
    let url = "";
    let init: RequestInit | undefined;
    globalThis.fetch = async (input, options) => {
      url = String(input);
      init = options;
      return new Response(JSON.stringify({ jsonrpc: "2.0", id: 1, result: { tools: [] } }), {
        headers: { "Content-Type": "application/json" },
      });
    };

    expect(await mcpClient.listTools()).toEqual([]);
    expect(url).toBe("/api/v1/gomode/mcp");
    expect(new Headers(init?.headers).get("Mcp-Method")).toBe("tools/list");
    expect(JSON.parse(String(init?.body)).params._meta["io.modelcontextprotocol/clientInfo"].name).toBe("mddb-frontend");
  });

  it("accepts an absolute endpoint on the frontend origin", async () => {
    configureMcpClient(`${window.location.origin}/api/v1/gomode/mcp`, "host");
    let url = "";
    globalThis.fetch = async (input) => {
      url = String(input);
      return new Response(JSON.stringify({ jsonrpc: "2.0", id: 2, result: { tools: [] } }), {
        headers: { "Content-Type": "application/json" },
      });
    };

    expect(await mcpClient.listTools()).toEqual([]);
    expect(url).toBe("/api/v1/gomode/mcp");
  });

  it("rejects cross-origin endpoint values", () => {
    expect(() => configureMcpClient("https://example.com/mcp", "host")).toThrow();
    expect(() => configureMcpClient("//example.com/mcp", "host")).toThrow();
    expect(() => configureMcpClient("/\\example.com/mcp", "host")).toThrow();
    expect(() => configureMcpClient("relative/mcp", "host")).toThrow();
  });

  it("adds the current bearer token to discovery and JSON-RPC requests", async () => {
    let token = "first";
    configureMcpClient("/api/v1/gomode/mcp", "mddb-frontend", () => token);
    const methods: string[] = [];
    const authorizations: string[] = [];
    globalThis.fetch = async (_input, init) => {
      methods.push(new Headers(init?.headers).get("Mcp-Method") ?? "");
      authorizations.push(new Headers(init?.headers).get("Authorization") ?? "");
      const method = JSON.parse(String(init?.body)).method as string;
      return new Response(JSON.stringify({ jsonrpc: "2.0", id: 1, result: method === "server/discover" ? { instructions: "Read nodes" } : { tools: [] } }));
    };

    expect(await mcpClient.serverInstructions()).toBe("Read nodes");
    token = "second";
    expect(await mcpClient.listTools()).toEqual([]);
    expect(methods).toEqual(["server/discover", "tools/list"]);
    expect(authorizations).toEqual(["Bearer first", "Bearer second"]);
  });

  it("surfaces HTTP authorization failures before reading a JSON-RPC result", async () => {
    configureMcpClient("/api/v1/gomode/mcp", "mddb-frontend", () => "expired");
    globalThis.fetch = async () => new Response("Unauthorized", { status: 401 });

    await expect(mcpClient.listTools()).rejects.toThrow("MCP tools/list returned HTTP 401");
  });
});

describe("subscribeResources", () => {
  it("streams resource notifications to the host", async () => {
    configureMcpClient("/api/v1/gomode/mcp", "mddb-frontend");
    let init: RequestInit | undefined;
    const encoder = new TextEncoder();
    globalThis.fetch = async (_input, options) => {
      init = options;
      return new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(encoder.encode(": keepalive\n\n"));
            controller.enqueue(
              encoder.encode(
                'data: {"jsonrpc":"2.0","method":"notifications/resources/updated","params":{"uri":"mddb://node/1"}}\n\n',
              ),
            );
            controller.close();
          },
        }),
        { status: 200 },
      );
    };

    let ready = false;
    let resolveUpdate: (notification: { uri?: string; listChanged?: boolean }) => void = () => {};
    const received = new Promise<{ uri?: string; listChanged?: boolean }>((resolve) => {
      resolveUpdate = resolve;
    });
    const subscription = mcpClient.subscribeResources(
      {
        resourceSubscriptions: ["mddb://node/1"],
        resourcesListChanged: true,
        onReady: () => {
          ready = true;
        },
      },
      resolveUpdate,
    );

    expect(await received).toEqual({ uri: "mddb://node/1" });
    subscription.close();
    expect(ready).toBe(true);
    const headers = new Headers(init?.headers);
    expect(headers.get("Mcp-Method")).toBe("subscriptions/listen");
    expect(headers.get("Accept")).toBe("text/event-stream");
    const body = JSON.parse(String(init?.body));
    expect(body.params.notifications).toEqual({
      resourceSubscriptions: ["mddb://node/1"],
      resourcesListChanged: true,
    });
  });

  it("closes active subscriptions when the host is reconfigured", async () => {
    configureMcpClient("/api/v1/gomode/mcp", "first");
    let captured: AbortSignal | null | undefined;
    globalThis.fetch = async (_input, options) => {
      captured = options?.signal;
      return new Response(new ReadableStream({ start() {} }), { status: 200 });
    };
    mcpClient.subscribeResources({ resourcesListChanged: true }, () => {});
    // Let the subscription reach its fetch before reconfiguring.
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(captured?.aborted).toBe(false);
    configureMcpClient("/api/v1/gomode/mcp", "second");
    expect(captured?.aborted).toBe(true);
  });
});
