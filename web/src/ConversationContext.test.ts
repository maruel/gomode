// Tests opaque server context, private ordering, and actual MCP event routing.
import { afterEach, it } from "node:test";
import { expect, vi } from "../tests/expect";
import { modelResourceContext } from "./ModelContext";
const conversationContext = (value: string | null | undefined | Error) =>
  modelResourceContext("fixture://context", value, 6000);
import { configureMcpClient, mcpClient, type McpResourceNotification } from "./McpClient";
import { monitorServiceNotifications } from "./ServiceNotifications";
afterEach(() => vi.restoreAllMocks());

it("preserves server-defined multilingual contents and omits whole oversized resources", () => {
  for (const content of ['{"選択文書":null,"観察":["更新"]}', '{"currentTask":{"number":2}}', "Aucune sélection."]) {
    expect(JSON.parse(conversationContext(content))).toEqual({
      resource: "fixture://context",
      status: "known",
      content,
    });
  }
  expect(JSON.parse(conversationContext('"'.repeat(4000)))).toEqual({
    resource: "fixture://context",
    status: "omitted",
  });
  for (const [value, status] of [
    [null, "absent"],
    [undefined, "unread"],
    [new Error("private"), "error"],
  ] as const)
    expect(JSON.parse(conversationContext(value))).toEqual({ resource: "fixture://context", status });
});

it("does not deliver a tool response from a retired login configuration to its replacement", async () => {
  configureMcpClient("/old", "old");
  let resolve: ((value: Response) => void) | undefined;
  vi.spyOn(globalThis, "fetch").mockImplementation(
    () =>
      new Promise<Response>((done) => {
        resolve = done;
      }),
  );
  const old = vi.fn<(value: unknown) => void>();
  const oldObserver = mcpClient.observeContext("conversation", old);
  const request = mcpClient.callTool("read", {}, { sessionID: "conversation" });
  for (let i = 0; i < 5; i++) await Promise.resolve();
  configureMcpClient("/new", "new");
  const replacement = vi.fn<(value: unknown) => void>();
  const observer = mcpClient.observeContext("conversation", replacement);
  try {
    if (!resolve) throw new Error("old request did not reach transport");
    resolve(
      new Response(
        JSON.stringify({
          jsonrpc: "2.0",
          id: 1,
          result: {
            structuredContent: { answer: 1 },
            _meta: {
              "com.fghbuild.gomode/context": {
                uri: "fixture://context",
                epoch: "old",
                version: 1,
                focus: { id: "private" },
                resources: [],
              },
            },
          },
        }),
      ),
    );
    await request;
    expect(old).not.toHaveBeenCalled();
    expect(replacement).not.toHaveBeenCalled();
  } finally {
    oldObserver.close();
    observer.close();
  }
});

it("routes one warm dependency read, rejects stale tools, and never exposes private metadata", async (t) => {
  configureMcpClient("/mcp", "opaque-context");
  let version = 1;
  let epoch = "authoritative";
  let content = '{"選択":null,"_meta":"ordinary domain content"}';
  let toolUpdate: object | undefined;
  let listCalls = 0;
  const reads: string[] = [];
  const calls: string[] = [];
  let requestBytes = 0;
  let responseBytes = 0;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (_url, init) => {
    requestBytes += Buffer.byteLength(String(init?.body));
    const request = JSON.parse(String(init?.body));
    calls.push(request.method);
    const metadata = {
      uri: "fixture://context",
      epoch,
      version,
      dependencies: [{ uris: ["host://watched"], aspects: ["body"] }],
    };
    let result: object;
    if (request.method === "server/discover")
      result = { capabilities: { resources: { subscribe: true, listChanged: true } } };
    else if (request.method === "resources/list") {
      listCalls++;
      result = { resources: [{ uri: "fixture://context", _meta: { "com.fghbuild.gomode/modelContext": true } }] };
    } else if (request.method === "resources/read") {
      reads.push(request.params.uri);
      result = {
        contents: [{ uri: "fixture://context", text: content }],
        _meta: { "com.fghbuild.gomode/context": metadata },
      };
    } else result = { structuredContent: { answer: 1 }, _meta: { "com.fghbuild.gomode/context": toolUpdate } };
    const response = JSON.stringify({ jsonrpc: "2.0", id: request.id, result });
    responseBytes += Buffer.byteLength(response);
    return new Response(response);
  });
  let receive: ((event: McpResourceNotification) => void) | undefined;
  const inject = vi.fn<(text: string, generate: boolean) => void>();
  const errors = vi.fn<(error: unknown) => void>();
  const monitor = monitorServiceNotifications(
    inject,
    errors,
    { sessionID: "same-conversation" },
    (_options, changed) => {
      receive = changed;
      return { close() {} };
    },
  );
  t.after(() => monitor.close());
  const settle = () => new Promise<void>((resolve) => setImmediate(resolve));
  await settle();
  expect(JSON.parse(inject.mock.calls.at(-1)![0].split("\n")[0]!).content).toBe(content);
  reads.length = 0;
  listCalls = 0;
  requestBytes = 0;
  responseBytes = 0;
  inject.mockClear();
  version = 3;
  content = '{"miseÀJour":"dossier hors écran"}';
  receive?.({ uri: "host://unrelated", aspects: ["body"] });
  await settle();
  expect(reads).toEqual([]);
  receive?.({ uri: "host://watched", aspects: ["body"] });
  await settle();
  expect(reads).toEqual(["fixture://context"]);
  expect(listCalls).toBe(0);
  expect(inject).toHaveBeenCalledOnce();
  expect(JSON.parse(inject.mock.calls[0]![0].split("\n")[0]!).content).toBe(content);
  t.diagnostic(
    JSON.stringify({
      boundary: "warm opaque context update",
      requestBytes,
      responseBytes,
      listCalls,
      reads: reads.length,
      quietUpdates: inject.mock.calls.length,
      modelTurns: 0,
      contextBytes: Buffer.byteLength(inject.mock.calls[0]![0]),
    }),
  );
  const count = inject.mock.calls.length;
  toolUpdate = { uri: "fixture://context", epoch, version: 2, dependencies: [], content: "obsolete" };
  await mcpClient.callTool("read", {}, { sessionID: "same-conversation" });
  await settle();
  expect(inject).toHaveBeenCalledTimes(count);
  epoch = "recreated";
  version = 1;
  content = '{"aucuneSélection":true}';
  receive?.({ uri: "fixture://context" });
  await settle();
  const recreated = inject.mock.calls.length;
  toolUpdate = {
    uri: "fixture://context",
    epoch: "authoritative",
    version: 100,
    dependencies: [],
    content: "old incarnation",
  };
  await mcpClient.callTool("read", {}, { sessionID: "same-conversation" });
  await settle();
  expect(inject).toHaveBeenCalledTimes(recreated);
  expect(JSON.parse(inject.mock.calls.at(-1)![0].split("\n")[0]!).content).toBe(content);
  expect(inject.mock.calls.every((call) => !call[1])).toBe(true);
  expect(errors).not.toHaveBeenCalled();
  for (const [text] of inject.mock.calls) {
    expect(text).not.toContain('"epoch"');
    expect(text).not.toContain('"dependencies"');
  }
});

it("faults malformed private authority on context reads while tool and other resource outcomes stay independent", async (t) => {
  configureMcpClient("/mcp", "metadata-faults");
  const publicText = '{"選択":[],"_meta":"ordinary domain content"}';
  let malformed = true;
  let writes = 0;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (_url, init) => {
    const request = JSON.parse(String(init?.body));
    const uri = request.params.uri as string | undefined;
    const metadata = malformed
      ? {
          uri: "fixture://context",
          epoch: "private-sensitive-epoch",
          version: "private-sensitive-version",
          dependencies: [],
        }
      : { uri: "fixture://context", epoch: "verified", version: 1, dependencies: [] };
    let result: object;
    if (request.method === "server/discover") result = { capabilities: { resources: { subscribe: true } } };
    else if (request.method === "resources/list")
      result = {
        resources: ["fixture://context", "fixture://catalog", "gomode://notifications"].map((uri) => ({
          uri,
          _meta: { "com.fghbuild.gomode/modelContext": uri !== "gomode://notifications" },
        })),
      };
    else if (request.method === "tools/call") {
      writes++;
      result = { structuredContent: { saved: true }, _meta: { "com.fghbuild.gomode/context": metadata } };
    } else
      result = {
        contents: [{ uri, text: uri === "gomode://notifications" ? "[]" : publicText }],
        _meta: { "com.fghbuild.gomode/context": metadata },
      };
    return Response.json({ jsonrpc: "2.0", id: request.id, result });
  });
  let receive: ((event: McpResourceNotification) => void) | undefined;
  const inject = vi.fn<(text: string, generate: boolean) => void>();
  const errors = vi.fn<(error: unknown) => void>();
  const monitor = monitorServiceNotifications(inject, errors, { sessionID: "same-session" }, (_options, changed) => {
    receive = changed;
    return { close() {} };
  });
  t.after(() => monitor.close());
  const settle = () => new Promise<void>((resolve) => setImmediate(resolve));
  await settle();
  const sections = inject.mock.calls
    .at(-1)![0]
    .split("\n")
    .map((line) => JSON.parse(line));
  expect(sections[0]).toEqual({ resource: "fixture://context", status: "error" });
  expect(sections[1]).toEqual({ resource: "fixture://catalog", status: "known", content: publicText });
  expect(sections[2].status).toBe("known");
  const result = await mcpClient.callTool("save", {}, { sessionID: "same-session" });
  expect(result.structuredContent).toEqual({ saved: true });
  expect(writes).toBe(1);
  expect(await mcpClient.readTextResource("host://ordinary", undefined, { sessionID: "same-session" })).toBe(
    publicText,
  );
  // Strict authority validation also applies before a monitor exists or a caller supplies a session.
  await expect(mcpClient.readTextResource("fixture://context")).rejects.toThrow();
  malformed = false;
  receive?.({ uri: "fixture://context" });
  await settle();
  const restored = JSON.parse(inject.mock.calls.at(-1)![0].split("\n")[0]!);
  expect(restored).toEqual({ resource: "fixture://context", status: "known", content: publicText });
  expect(errors.mock.calls.length).toBeGreaterThan(0);
  expect(inject.mock.calls.every(([text, generate]) => !generate && !text.includes("private-sensitive"))).toBe(true);
});
