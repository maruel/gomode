// Exercises opt-in model discovery, resource retirement, and advisory offscreen-read reconciliation through MCP.
import { afterEach, it } from "node:test";
import { expect, vi } from "../tests/expect";
import { deferred } from "../tests/deferred";
import { configureMcpClient, mcpClient, type McpResourceNotification } from "./McpClient";
import { monitorServiceNotifications } from "./ServiceNotifications";

afterEach(() => vi.restoreAllMocks());
const settle = () => new Promise<void>((resolve) => setImmediate(resolve));

it("ignores unmarked resources and rejects duplicate, excessive, or cyclic discovery", async () => {
  configureMcpClient("/mcp", "discovery");
  const mark = { "com.fghbuild.gomode/modelContext": true };
  let pages: object[] = [
    {
      resources: [
        { uri: "library://manual", name: "manual" },
        { uri: "library://active", name: "active", _meta: mark },
      ],
    },
  ];
  vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
    Response.json({ jsonrpc: "2.0", result: pages.shift() }),
  );
  expect((await mcpClient.listModelResources()).resources.map((resource) => resource.uri)).toEqual([
    "library://active",
  ]);
  for (const resources of [
    [
      { uri: "library://a", _meta: mark },
      { uri: "library://a", _meta: mark },
    ],
    ...[null, 42, {}, ""].map((uri) => [{ uri, _meta: mark }]),
    Array.from({ length: 17 }, (_, i) => ({ uri: `library://${i}`, _meta: mark })),
  ]) {
    pages = [{ resources }];
    await expect(mcpClient.listModelResources()).rejects.toThrow();
  }
  pages = [
    { resources: [], nextCursor: "repeat" },
    { resources: [], nextCursor: "repeat" },
  ];
  await expect(mcpClient.listModelResources()).rejects.toThrow("Repeated resource cursor");
});

it("retiring an advertised identity aborts its read and rejects late authority after reappearance", async (t) => {
  configureMcpClient("/mcp", "retirement");
  const pending = deferred<Response>();
  const signals: Array<AbortSignal | null | undefined> = [];
  let marked = true;
  let block = false;
  let epoch = "new";
  vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) => {
    const request = JSON.parse(String(init?.body));
    const result =
      request.method === "server/discover"
        ? { capabilities: { resources: { subscribe: true, listChanged: true } } }
        : request.method === "resources/list"
          ? {
              resources: [
                { uri: "library://active", name: "active", _meta: { "com.fghbuild.gomode/modelContext": marked } },
              ],
            }
          : {
              contents: [{ uri: "library://active", text: epoch }],
              _meta: {
                "com.fghbuild.gomode/context": { uri: "library://active", epoch, version: 1, dependencies: [] },
              },
            };
    if (request.method === "resources/read" && block) {
      signals.push(init?.signal);
      return pending.promise;
    }
    return Response.json({ jsonrpc: "2.0", result });
  });
  let changed: ((event: McpResourceNotification) => void) | undefined;
  const inject = vi.fn<(text: string, generate: boolean) => void>();
  const error = vi.fn();
  const monitor = monitorServiceNotifications(inject, error, { sessionID: "same" }, (_options, receive) => {
    changed = receive;
    return { close() {} };
  });
  t.after(() => monitor.close());
  await settle();
  block = true;
  changed?.({ uri: "library://active" });
  await settle();
  marked = false;
  changed?.({ listChanged: true });
  await settle();
  expect(signals[0]?.aborted).toBe(true);
  expect(inject.mock.calls.at(-1)?.[0]).toContain('"status":"absent"');
  marked = true;
  block = false;
  epoch = "replacement";
  changed?.({ listChanged: true });
  await settle();
  const count = inject.mock.calls.length;
  pending.resolve(
    Response.json({
      jsonrpc: "2.0",
      result: {
        contents: [{ uri: "library://active", text: "retired" }],
        _meta: {
          "com.fghbuild.gomode/context": { uri: "library://active", epoch: "retired", version: 999, dependencies: [] },
        },
      },
    }),
  );
  await settle();
  expect(inject).toHaveBeenCalledTimes(count);
  expect(inject.mock.calls.at(-1)?.[0]).toContain("replacement");
  expect(error).not.toHaveBeenCalled();
});

it("offscreen reads reconcile an admitted model resource without establishing a new epoch", async (t) => {
  configureMcpClient("/mcp", "offscreen");
  let content = "opening";
  let epoch = "server";
  vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) => {
    const request = JSON.parse(String(init?.body));
    const uri = request.params.uri;
    const result =
      request.method === "server/discover"
        ? { capabilities: { resources: { subscribe: true } } }
        : request.method === "resources/list"
          ? {
              resources: [
                { uri: "library://active", name: "active", _meta: { "com.fghbuild.gomode/modelContext": true } },
              ],
            }
          : {
              contents: [{ uri, text: uri === "library://active" ? "opening" : "offscreen document" }],
              _meta: {
                "com.fghbuild.gomode/context": {
                  uri: "library://active",
                  epoch,
                  version: 2,
                  dependencies: [],
                  content,
                },
              },
            };
    return Response.json({ jsonrpc: "2.0", result });
  });
  const inject = vi.fn<(text: string, generate: boolean) => void>();
  const monitor = monitorServiceNotifications(
    inject,
    () => {},
    { sessionID: "conversation" },
    () => ({ close() {} }),
  );
  t.after(() => monitor.close());
  await settle();
  content = "watched offscreen document";
  expect(await mcpClient.readTextResource("library://document/42", undefined, { sessionID: "conversation" })).toBe(
    "offscreen document",
  );
  await settle();
  expect(inject.mock.calls.at(-1)?.[0]).toContain("watched offscreen document");
  epoch = "unverified";
  content = "wrong epoch";
  await mcpClient.readTextResource("library://document/42", undefined, { sessionID: "conversation" });
  await settle();
  expect(inject.mock.calls.every(([text]) => !text.includes("wrong epoch"))).toBe(true);
});
