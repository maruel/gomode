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
});
