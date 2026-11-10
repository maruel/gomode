// Tests browser MCP requests, opaque result metadata, and subscription ownership.
import { afterEach, describe, it } from "node:test";
import { ok, rejects } from "node:assert/strict";
import { expect } from "../tests/expect";

import type { ResourcesReadParams, SubscriptionsListenParams } from "../../sdk/mcp/ts/v1/types.gen";

import { configureMcpClient, mcpClient } from "./McpClient";

const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe("configureMcpClient", () => {
  it("keeps malformed transport diagnostics out of tool failures", async () => {
    configureMcpClient("/mcp", "host");
    let invocations = 0;
    globalThis.fetch = async () => {
      invocations++;
      return new Response('{"_meta":{"private":"private-envelope-sentinel"},"result":BROKEN}');
    };
    await rejects(mcpClient.callTool("read_item", {}), (failure: unknown) => {
      ok(failure instanceof Error);
      ok(!failure.message.includes("private-envelope-sentinel"));
      ok(failure.cause instanceof Error);
      return true;
    });
    expect(invocations).toBe(1);
  });

  it("preserves intentional public RPC error messages and codes", async () => {
    configureMcpClient("/mcp", "host");
    globalThis.fetch = async () => Response.json({ error: { code: -32602, message: "Choose a valid item" } });
    await rejects(mcpClient.callTool("read_item", {}), (failure: unknown) => {
      ok(failure instanceof Error);
      expect(failure.message).toBe("Choose a valid item");
      expect((failure as Error & { code: number }).code).toBe(-32602);
      return true;
    });
  });

  it("preserves opaque tool metadata separately from native and released result content", async (t) => {
    for (const protocol of ["native", "released"]) {
      for (const format of ["structured", "text"]) {
        for (const isError of [false, true]) {
          await t.test(`${protocol}/${format}/${isError ? "error" : "success"}`, async () => {
            configureMcpClient("/mcp", "host");
            const meta = {
              "example.org/dependencies": { uris: ["host://one"], revision: 7 },
              "other.example/opaque": [null, true, { nested: "private transport value" }],
            };
            globalThis.fetch = async () =>
              Response.json({
                result: {
                  ...(protocol === "native" ? { resultType: "complete" } : {}),
                  content: [{ type: "text", text: "Readable tool message" }],
                  ...(format === "structured" ? { structuredContent: { state: "ready" } } : {}),
                  isError,
                  _meta: meta,
                },
              });
            const result = await mcpClient.callTool("read_item", {});
            expect(result._meta).toEqual(meta);
            expect(result.isError).toBe(isError);
            expect(result.structuredContent).toEqual(
              format === "structured" ? { state: "ready" } : { error: "Readable tool message" },
            );
          });
        }
      }
    }
  });

  it("sends device hints and caller-owned conversation IDs without changing arguments", async (t) => {
    configureMcpClient("/mcp", "host");
    let timezone = "America/Toronto";
    const original = Intl.DateTimeFormat.prototype.resolvedOptions;
    t.mock.method(Intl.DateTimeFormat.prototype, "resolvedOptions", function (this: Intl.DateTimeFormat) {
      return { ...original.call(this), timeZone: timezone };
    });
    t.mock.getter(navigator, "language", () => "fr-CA");
    const params: Array<Record<string, unknown>> = [];
    globalThis.fetch = async (_input, init) => {
      params.push(JSON.parse(String(init?.body)).params);
      return new Response(JSON.stringify({ result: { structuredContent: { result: "ok" } } }));
    };
    await mcpClient.callTool("get_usage", {}, { locale: "en-GB", sessionID: "conversation-one" });
    timezone = "Asia/Kolkata";
    await mcpClient.callTool("get_usage", {}, { locale: "fr-CA", sessionID: "conversation-two" });
    expect(params.map((p) => (p._meta as Record<string, unknown>)["openai/userLocation"])).toEqual([
      { timezone: "America/Toronto" },
      { timezone: "Asia/Kolkata" },
    ]);
    const first = params[0];
    ok(first);
    expect(first.arguments).toEqual({});
    expect(params.map((p) => (p._meta as Record<string, unknown>)["openai/session"])).toEqual([
      "conversation-one",
      "conversation-two",
    ]);
    expect(first._meta).toMatchObject({
      "openai/locale": "en-GB",
      "openai/userAgent": "gomode-web/1.0.0",
    });
    await mcpClient.callTool("get_usage", {});
    const third = params[2];
    ok(third);
    expect(third._meta).not.toHaveProperty("openai/session");
    expect(third._meta).not.toHaveProperty("openai/locale");
  });

  it("sends device timezone when reading an advertised resource", async () => {
    configureMcpClient("/mcp", "host");
    let params: ResourcesReadParams | undefined;
    globalThis.fetch = async (_input, init) => {
      const body = JSON.parse(String(init?.body)) as { method: string; params: ResourcesReadParams };
      if (body.method === "resources/read") params = body.params;
      const result =
        body.method === "resources/list"
          ? { resources: [{ uri: "host://usage" }] }
          : { contents: [{ uri: "host://usage", text: "usage" }] };
      return new Response(JSON.stringify({ result }));
    };
    expect(
      await mcpClient.readAdvertisedTextResource("host://usage", undefined, { sessionID: "conversation-one" }),
    ).toBe("usage");
    expect(params?._meta["openai/userLocation"]).toEqual({
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    });
    expect(params?._meta).not.toHaveProperty("openai/locale");
    expect(params?._meta["openai/session"]).toBe("conversation-one");
  });

  it("keeps instructions and resources scoped to each caller without retaining a session", async () => {
    configureMcpClient("/mcp", "host");
    const requests: Array<{ method: string; params: { _meta: { "openai/session"?: string } } }> = [];
    globalThis.fetch = async (_input, init) => {
      const body = JSON.parse(String(init?.body)) as (typeof requests)[number];
      requests.push(body);
      return Response.json({ result: { instructions: body.params._meta["openai/session"] ?? "sessionless" } });
    };
    expect(await mcpClient.serverInstructions({ sessionID: "conversation-one" })).toBe("conversation-one");
    expect(await mcpClient.serverInstructions({ sessionID: "conversation-two" })).toBe("conversation-two");
    expect(await mcpClient.serverInstructions()).toBe("sessionless");
    expect(requests.map((request) => request.method)).toEqual([
      "server/discover",
      "server/discover",
      "server/discover",
    ]);
  });

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
    expect(JSON.parse(String(init?.body)).params._meta["io.modelcontextprotocol/clientInfo"].name).toBe(
      "mddb-frontend",
    );
  });

  it("distinguishes an unadvertised resource from a malformed advertised text snapshot", async () => {
    configureMcpClient("/mcp", "host");
    let advertised = false;
    let contents: Array<{ uri: string; text?: string }> = [];
    globalThis.fetch = async (_input, init) => {
      const { method } = JSON.parse(String(init?.body)) as { method: string };
      return Response.json({
        result:
          method === "resources/list"
            ? { resources: advertised ? [{ uri: "gomode://notifications" }] : [] }
            : { contents },
      });
    };
    expect(await mcpClient.readAdvertisedTextResource("gomode://notifications")).toBeNull();
    advertised = true;
    for (const invalid of [[], [{ uri: "gomode://notifications" }], [{ uri: "other://resource", text: "[]" }]]) {
      contents = invalid;
      await expect(mcpClient.readAdvertisedTextResource("gomode://notifications")).rejects.toThrow(
        "missing text content",
      );
    }
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
      return new Response(
        JSON.stringify({
          jsonrpc: "2.0",
          id: 1,
          result: method === "server/discover" ? { instructions: "Read nodes" } : { tools: [] },
        }),
      );
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
  it("retires a stalled stream offline and reconnects immediately with the same session", async (t) => {
    configureMcpClient("/mcp", "host");
    let online = true;
    t.mock.getter(navigator, "onLine", () => online);
    const attempts: Array<{ signal: AbortSignal; session: string }> = [];
    globalThis.fetch = async (_input, init) => {
      const body = JSON.parse(String(init?.body)) as { params: SubscriptionsListenParams };
      attempts.push({ signal: init?.signal as AbortSignal, session: String(body.params._meta?.["openai/session"]) });
      return new Response(new ReadableStream());
    };
    const subscription = mcpClient.subscribeResources({ sessionID: "same-call", resourcesListChanged: true }, () => {});
    t.after(() => subscription.close());
    await Promise.resolve();
    await Promise.resolve();
    expect(attempts).toHaveLength(1);
    online = false;
    window.dispatchEvent(new window.Event("offline"));
    expect(attempts[0]?.signal.aborted).toBe(true);
    expect(attempts).toHaveLength(1);
    online = true;
    window.dispatchEvent(new window.Event("online"));
    await Promise.resolve();
    await Promise.resolve();
    expect(attempts.map((attempt) => attempt.session)).toEqual(["same-call", "same-call"]);
    subscription.close();
    window.dispatchEvent(new window.Event("online"));
    expect(attempts).toHaveLength(2);
    expect(attempts[1]?.signal.aborted).toBe(true);
  });

  it("retains its conversation when a subscription reconnects", async (t) => {
    t.mock.timers.enable({ apis: ["setTimeout"] });
    configureMcpClient("/mcp", "host");
    const sessions: Array<string | undefined> = [];
    let ended: () => void = () => {};
    const disconnected = new Promise<void>((resolve) => {
      ended = resolve;
    });
    let ready: () => void = () => {};
    const reconnected = new Promise<void>((resolve) => {
      ready = resolve;
    });
    globalThis.fetch = async (_input, init) => {
      const body = JSON.parse(String(init?.body)) as { params: SubscriptionsListenParams };
      sessions.push(body.params._meta["openai/session"]);
      return new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(
              new TextEncoder().encode(
                'data: {"jsonrpc":"2.0","method":"notifications/subscriptions/acknowledged"}\n\n',
              ),
            );
            controller.close();
          },
        }),
      );
    };
    const subscription = mcpClient.subscribeResources(
      {
        sessionID: "conversation-one",
        resourceSubscriptions: ["fixture://catalog"],
        onError: ended,
        onReady: () => {
          if (sessions.length === 2) ready();
        },
      },
      () => {},
    );
    t.after(() => subscription.close());
    await disconnected;
    t.mock.timers.tick(2000);
    await reconnected;
    subscription.close();
    expect(sessions).toEqual(["conversation-one", "conversation-one"]);
  });

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
              encoder.encode('data: {"jsonrpc":"2.0","method":"notifications/subscriptions/acknowledged"}\n\n'),
            );
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
        sessionID: "conversation-one",
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
    const body = JSON.parse(String(init?.body)) as { params: SubscriptionsListenParams };
    expect(body.params._meta["openai/session"]).toBe("conversation-one");
    expect(body.params._meta["openai/userLocation"]).toEqual({
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    });
    expect(body.params.notifications).toEqual({
      resourceSubscriptions: ["mddb://node/1"],
      resourcesListChanged: true,
    });
  });

  it("waits for the server acknowledgment before reporting a subscription ready", async () => {
    configureMcpClient("/mcp", "host");
    let controller: ReadableStreamDefaultController<Uint8Array> | undefined;
    globalThis.fetch = async () =>
      new Response(
        new ReadableStream({
          start(value) {
            controller = value;
          },
        }),
      );
    let ready = 0;
    let resolveUpdate: (() => void) | undefined;
    let received = new Promise<void>((resolve) => {
      resolveUpdate = resolve;
    });
    const subscription = mcpClient.subscribeResources(
      {
        resourceSubscriptions: ["test://resource"],
        onReady: () => {
          ready++;
        },
      },
      () => resolveUpdate?.(),
    );
    // Await body parsing through a pre-ack update, rather than a scheduler delay.
    await Promise.resolve();
    ok(controller);
    const encoder = new TextEncoder();
    controller.enqueue(
      encoder.encode(
        'data: {"jsonrpc":"2.0","method":"notifications/resources/updated","params":{"uri":"test://resource"}}\n\n',
      ),
    );
    await received;
    expect(ready).toBe(0);
    received = new Promise<void>((resolve) => {
      resolveUpdate = resolve;
    });
    controller.enqueue(
      encoder.encode(
        'data: {"jsonrpc":"2.0","method":"notifications/subscriptions/acknowledged"}\n\ndata: {"jsonrpc":"2.0","method":"notifications/subscriptions/acknowledged"}\n\ndata: {"jsonrpc":"2.0","method":"notifications/resources/updated","params":{"uri":"test://resource"}}\n\n',
      ),
    );
    await received;
    expect(ready).toBe(1);
    subscription.close();
    controller.close();
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
