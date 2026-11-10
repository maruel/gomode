// Tests frontend preparation, authenticated scopes, per-invocation sessions, and dispatch retirement.

import { afterEach, beforeEach, it } from "node:test";
import { createSignal } from "solid-js";
import { expect, vi } from "../tests/expect";
import { deferred } from "../tests/deferred";
import {
  captureFrontendVoiceTools,
  FrontendVoiceContextConflict,
  createFrontendPreparationClient,
  executeFrontendVoiceTool,
  type FrontendPreparation,
  type FrontendVoiceToolContext,
} from "./FrontendVoiceTools";

let owner: FrontendPreparation | null = null;
const requests: Record<string, unknown>[] = [];
const readiness: Record<string, unknown>[] = [];
const bridge: { postMessage(message: string): void; onmessage?: ((event: { data: string }) => void) | null } = {
  postMessage(message) {
    requests.push(JSON.parse(message));
  },
};
beforeEach(() => {
  requests.length = 0;
  readiness.length = 0;
  Object.assign(window, { gomodeAuth: bridge });
  window.goModeFrontendDocument = Promise.resolve("document");
  window.gomodeFrontendReadiness = { postMessage: (message) => readiness.push(JSON.parse(message)) };
});
afterEach(() => {
  owner?.dispose();
  owner = null;
  Reflect.deleteProperty(window, "gomodeAuth");
  delete window.goModeFrontendDocument;
  delete window.gomodeFrontendReadiness;
});
function authenticate() {
  owner = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: true });
  return owner;
}
function contextPolicy(prepare: (sessionID: string, signal: AbortSignal) => Promise<void>) {
  const dispose = vi.fn();
  return {
    dispose,
    selection: () => null,
    createPublisher: (sessionID: string) => ({
      publish: (_selection: string | null, _current: () => boolean, signal: AbortSignal) => prepare(sessionID, signal),
      dispose,
    }),
  };
}
function acknowledge(request: Record<string, unknown>, token: string) {
  bridge.onmessage?.({ data: JSON.stringify({ ...request, frontendToken: token }) });
}
const tool = {
  declaration: { name: "select_item", description: "Select", parameters: {} },
  execute: () => ({ selected: true }),
};

it("requires explicit tool registration and initial-data completion in either order, including zero tools", async () => {
  for (const dataFirst of [false, true]) {
    for (const tools of [[], [tool]]) {
      const identity = authenticate();
      const scope = identity.createScope();
      await Promise.resolve();
      acknowledge(requests.at(-1)!, "identity");
      expect(scope.ready()).toBe(false);
      if (dataFirst) scope.setDataReady(true);
      else scope.setTools(tools);
      expect(scope.ready()).toBe(false);
      expect(readiness.at(-1)).toMatchObject({ ready: false, tools: [] });
      if (dataFirst) scope.setTools(tools);
      else scope.setDataReady(true);
      expect(scope.ready()).toBe(true);
      expect(readiness.at(-1)).toMatchObject({ ready: true, tools: tools.map((value) => value.declaration) });
      scope.dispose();
      expect(scope.ready()).toBe(false);
      expect(readiness.at(-1)).toMatchObject({ ready: false, tools: [] });
    }
  }
});

it("ignores equal data readiness while publishing real changes and replacement registrations", async () => {
  const scope = authenticate().createScope();
  scope.setTools([tool]);
  await Promise.resolve();
  acknowledge(requests.at(-1)!, "identity");
  const initial = [...readiness];
  scope.setDataReady(false);
  expect(readiness).toEqual(initial);

  scope.setDataReady(true);
  const prepared = [...readiness];
  const snapshot = captureFrontendVoiceTools();
  scope.setDataReady(true);
  expect(readiness).toEqual(prepared);
  expect(await snapshot.execute("select_item", {}, "conversation")).toEqual({ selected: true });

  scope.setTools([tool]);
  expect(readiness).toHaveLength(prepared.length + 1);
  expect(readiness.at(-1)).toMatchObject({ ready: true });
  expect(await snapshot.execute("select_item", {}, "conversation")).toHaveProperty("error");

  const replacement = captureFrontendVoiceTools();
  scope.setDataReady(false);
  expect(readiness.at(-1)).toMatchObject({ ready: false, tools: [] });
  expect(await replacement.execute("select_item", {}, "conversation")).toHaveProperty("error");
  const retired = [...readiness];
  scope.setDataReady(false);
  expect(readiness).toEqual(retired);
  scope.setDataReady(true);
  expect(readiness).toHaveLength(retired.length + 1);
  expect(readiness.at(-1)).toMatchObject({ ready: true });
});

it("makes browser readiness local while native publication waits for matching auth acknowledgement", async () => {
  const scope = authenticate().createScope();
  scope.setTools([]);
  scope.setDataReady(true);
  expect(scope.ready()).toBe(true);
  expect(readiness).toEqual([]);
  await Promise.resolve();
  const request = requests[0]!;
  acknowledge({ ...request, frontendDocument: "other" }, "wrong-document");
  acknowledge({ ...request, frontendRequest: "retired" }, "wrong-request");
  expect(readiness).toEqual([]);
  acknowledge(request, "identity");
  expect(readiness).toHaveLength(1);
  expect(readiness[0]).toMatchObject({ document: "document", token: "identity", ready: true, tools: [] });
  owner?.dispose();
  delete window.goModeFrontendDocument;
  Reflect.deleteProperty(window, "gomodeAuth");
  const browser = authenticate().createScope();
  browser.setTools([]);
  browser.setDataReady(true);
  expect(browser.ready()).toBe(true);
});

function nativeSession(sessionID: string | null, overrides?: Record<string, unknown>) {
  const bound = readiness.at(-1)!;
  window.gomodeFrontendReadiness?.onmessage?.({
    data: JSON.stringify({
      document: bound.document,
      token: bound.token,
      scope: bound.scope,
      voiceSessionVersion: 1,
      sessionID,
      ...overrides,
    }),
  });
}

it("binds native lifetime and awaited initial publication to authenticated document and product scope", async () => {
  const initial = deferred<void>();
  const prepare = vi.fn((_sessionID: string, _signal: AbortSignal) => initial.promise);
  const context = contextPolicy(prepare);
  const scope = authenticate().createScope(context);
  scope.setTools([]);
  scope.setDataReady(true);
  await Promise.resolve();
  acknowledge({ ...requests[0], voiceSessionVersion: 1 }, "identity");
  expect(scope.nativeVoiceSessionID()).toBeNull();
  for (const mismatch of [
    { document: "foreign" },
    { token: "retired" },
    { scope: "old" },
    { voiceSessionVersion: 2 },
  ]) {
    nativeSession("first", mismatch);
    expect(scope.nativeVoiceSessionID()).toBeNull();
  }
  nativeSession("first");
  expect(scope.nativeVoiceSessionID()).toBe("first");
  await vi.waitFor(() => expect(prepare).toHaveBeenCalledOnce());
  expect(prepare.mock.calls[0]?.[0]).toBe("first");
  expect(readiness.at(-1)?.voiceSessionPrepared).toBeNull();
  nativeSession("first");
  await vi.waitFor(() => expect(prepare).toHaveBeenCalledOnce());
  initial.resolve();
  await Promise.resolve();
  await Promise.resolve();
  await vi.waitFor(() => expect(readiness.at(-1)?.voiceSessionPrepared).toBe("first"));
  scope.setDataReady(false);
  expect(context.dispose).toHaveBeenCalledOnce();
  expect(scope.nativeVoiceSessionID()).toBeNull();
  expect(readiness.at(-1)?.voiceSessionPrepared).toBeNull();
  scope.setDataReady(true);
  nativeSession("second");
  expect(scope.nativeVoiceSessionID()).toBe("second");
  await vi.waitFor(() => expect(prepare).toHaveBeenCalledTimes(2));
  owner?.dispose();
  expect(scope.nativeVoiceSessionID()).toBeNull();
  expect(context.dispose).toHaveBeenCalledTimes(2);
});

it("keeps a replacement native preparation current when the previous publication settles late", async () => {
  const first = deferred<void>();
  const second = deferred<void>();
  const prepare = vi.fn((id: string, _signal: AbortSignal) => (id === "first" ? first.promise : second.promise));
  const scope = authenticate().createScope(contextPolicy(prepare));
  scope.setTools([]);
  scope.setDataReady(true);
  await Promise.resolve();
  acknowledge({ ...requests[0], voiceSessionVersion: 1 }, "identity");
  nativeSession("first");
  await vi.waitFor(() => expect(prepare).toHaveBeenCalledTimes(1));
  nativeSession("second");
  expect(prepare.mock.calls[0]![1].aborted).toBe(true);
  await vi.waitFor(() => expect(prepare).toHaveBeenCalledTimes(2));
  first.resolve();
  await Promise.resolve();
  await Promise.resolve();
  expect(scope.nativeVoiceSessionID()).toBe("second");
  expect(readiness.at(-1)?.voiceSessionPrepared).toBeNull();
  second.resolve();
  await vi.waitFor(() => expect(readiness.at(-1)?.voiceSessionPrepared).toBe("second"));
});

it("requires new preparation when the same native ID returns after registration replacement", async () => {
  const first = deferred<void>();
  const second = deferred<void>();
  const prepare = vi
    .fn<(id: string, signal: AbortSignal) => Promise<void>>()
    .mockImplementationOnce(() => first.promise)
    .mockImplementationOnce(() => second.promise);
  const scope = authenticate().createScope(contextPolicy(prepare));
  scope.setTools([]);
  scope.setDataReady(true);
  await Promise.resolve();
  acknowledge({ ...requests[0], voiceSessionVersion: 1 }, "identity");
  nativeSession("conversation");
  await vi.waitFor(() => expect(prepare).toHaveBeenCalledTimes(1));
  const previousBinding = readiness.at(-1)?.scope;
  scope.setTools([]);
  expect(readiness.at(-1)?.scope).not.toBe(previousBinding);
  expect(prepare.mock.calls[0]![1].aborted).toBe(true);
  nativeSession("conversation");
  await vi.waitFor(() => expect(prepare).toHaveBeenCalledTimes(2));
  first.resolve();
  await Promise.resolve();
  await Promise.resolve();
  expect(readiness.at(-1)?.voiceSessionPrepared).toBeNull();
  second.resolve();
  await vi.waitFor(() => expect(readiness.at(-1)?.voiceSessionPrepared).toBe("conversation"));
});

it("binds captured native dispatch and cancellation to the registration despite reused invocation IDs", async () => {
  const responses: Record<string, unknown>[] = [];
  window.gomodeFrontendToolResult = { postMessage: (value) => responses.push(JSON.parse(value)) };
  try {
    const first = deferred<Record<string, unknown>>();
    const second = deferred<Record<string, unknown>>();
    const contexts: FrontendVoiceToolContext[] = [];
    const execute = vi.fn((_args: Record<string, unknown>, context: FrontendVoiceToolContext) => {
      contexts.push(context);
      return contexts.length === 1 ? first.promise : second.promise;
    });
    const scope = authenticate().createScope();
    scope.setTools([{ ...tool, execute }]);
    scope.setDataReady(true);
    await Promise.resolve();
    acknowledge(requests[0]!, "identity");
    const request = () => ({
      document: "document",
      token: "identity",
      scope: String(readiness.at(-1)?.scope),
      invocation: "reused",
      sessionID: "conversation",
      name: "select_item",
      args: {},
    });
    const oldDispatch = window.dispatchGoModeVoiceTool!;
    const oldCancel = window.cancelGoModeVoiceTool!;
    const previous = request();
    oldDispatch(previous);
    expect(execute).toHaveBeenCalledTimes(1);
    scope.setTools([{ ...tool, execute }]);
    expect(contexts[0]!.signal.aborted).toBe(true);
    const replacement = request();
    oldDispatch(previous);
    expect(execute).toHaveBeenCalledTimes(1);
    window.dispatchGoModeVoiceTool!(replacement);
    expect(execute).toHaveBeenCalledTimes(2);
    oldCancel(previous);
    expect(contexts[1]!.signal.aborted).toBe(false);
    first.resolve({ stale: true });
    await Promise.resolve();
    await Promise.resolve();
    expect(responses).toEqual([]);
    second.resolve({ current: true });
    await vi.waitFor(() => expect(responses).toHaveLength(1));
    expect(responses[0]).toMatchObject({ scope: replacement.scope, invocation: "reused", result: { current: true } });
  } finally {
    delete window.gomodeFrontendToolResult;
  }
});

it("admits negotiated native wire dispatch only for the live conversation ID", async () => {
  const responses: Record<string, unknown>[] = [];
  window.gomodeFrontendToolResult = { postMessage: (value) => responses.push(JSON.parse(value)) };
  const execute = vi.fn((_args: Record<string, unknown>, _context: FrontendVoiceToolContext) => ({ selected: true }));
  const scope = authenticate().createScope();
  scope.setTools([{ ...tool, execute }]);
  scope.setDataReady(true);
  await Promise.resolve();
  acknowledge({ ...requests.at(-1)!, voiceSessionVersion: 1 }, "identity");
  nativeSession("retired-conversation");
  nativeSession("live-conversation");
  const binding = readiness.at(-1)!;
  const dispatch = (invocation: string, sessionID?: string) =>
    window.dispatchGoModeVoiceTool!({
      document: "document",
      token: "identity",
      scope: String(binding.scope),
      invocation,
      ...(sessionID === undefined ? {} : { sessionID }),
      name: "select_item",
      args: {},
    });
  dispatch("missing");
  dispatch("stale", "retired-conversation");
  dispatch("foreign", "another-conversation");
  await Promise.resolve();
  await Promise.resolve();
  expect(execute).not.toHaveBeenCalled();
  expect(responses).toEqual([]);
  dispatch("current", "live-conversation");
  await vi.waitFor(() => expect(responses).toHaveLength(1));
  expect(execute).toHaveBeenCalledOnce();
  expect(execute.mock.calls[0]![1].sessionID).toBe("live-conversation");
  expect(responses[0]).toMatchObject({
    invocation: "current",
    sessionID: "live-conversation",
    result: { selected: true },
  });
  nativeSession(null);
  dispatch("after-retirement", "live-conversation");
  dispatch("missing-after-retirement");
  await Promise.resolve();
  expect(execute).toHaveBeenCalledOnce();
  expect(responses).toHaveLength(1);
  delete window.gomodeFrontendToolResult;
});

it("retires native pending actions when the conversation ends while keeping scope tools prepared", async () => {
  const contexts: FrontendVoiceToolContext[] = [];
  const waiting = deferred<Record<string, unknown>>();
  const scope = authenticate().createScope();
  scope.setTools([
    {
      ...tool,
      execute: (_args, current) => {
        contexts.push(current);
        return waiting.promise;
      },
    },
  ]);
  scope.setDataReady(true);
  await Promise.resolve();
  acknowledge({ ...requests[0], voiceSessionVersion: 1 }, "identity");
  nativeSession("first");
  const result = captureFrontendVoiceTools().execute("select_item", {}, "first");
  const context = contexts[0]!;
  expect(context.isCurrent()).toBe(true);
  nativeSession(null);
  expect(scope.ready()).toBe(true);
  expect(context.isCurrent()).toBe(false);
  expect(context.signal.aborted).toBe(true);
  waiting.resolve({ late: true });
  expect(await result).toHaveProperty("error");
  expect(await captureFrontendVoiceTools().execute("select_item", {}, "first")).toHaveProperty("error");
});

it("acknowledges advisory publication failure but never a cancelled or retired publisher", async () => {
  const warning = vi.spyOn(console, "warn").mockImplementation(() => {});
  const blocked = deferred<void>();
  const prepare = vi
    .fn<(sessionID: string, signal: AbortSignal) => Promise<void>>()
    .mockRejectedValueOnce(new Error("Publication unavailable"))
    .mockImplementationOnce(() => blocked.promise);
  const scope = authenticate().createScope(contextPolicy(prepare));
  scope.setTools([]);
  scope.setDataReady(true);
  await Promise.resolve();
  acknowledge({ ...requests[0], voiceSessionVersion: 1 }, "identity");
  nativeSession("first");
  await Promise.resolve();
  await Promise.resolve();
  await vi.waitFor(() => expect(readiness.at(-1)?.voiceSessionPrepared).toBe("first"));
  expect(warning).toHaveBeenCalledOnce();
  nativeSession("second");
  await vi.waitFor(() => expect(prepare).toHaveBeenCalledTimes(2));
  nativeSession(null);
  const count = readiness.length;
  blocked.resolve();
  await Promise.resolve();
  await Promise.resolve();
  expect(readiness).toHaveLength(count);
  expect(prepare.mock.calls[1]![1].aborted).toBe(true);
  warning.mockRestore();
});

it("replaces workspace scopes without reauthenticating and cannot dispatch a replacement from an old conversation", async () => {
  const identity = authenticate();
  const first = identity.createScope();
  const execute = vi.fn(tool.execute);
  first.setTools([{ ...tool, execute }]);
  first.setDataReady(true);
  await Promise.resolve();
  acknowledge(requests[0]!, "identity");
  const conversation = captureFrontendVoiceTools();
  expect(await conversation.execute("select_item", {}, "conversation")).toEqual({ selected: true });
  const replacement = identity.createScope();
  expect(requests).toHaveLength(1);
  expect(readiness.at(-1)).toMatchObject({ token: "identity", ready: false, tools: [] });
  replacement.setTools([tool]);
  replacement.setDataReady(true);
  first.setDataReady(true);
  first.setTools([tool]);
  first.dispose();
  expect(replacement.ready()).toBe(true);
  expect(await conversation.execute("select_item", {}, "conversation")).toHaveProperty("error");
  expect(execute).toHaveBeenCalledOnce();
  expect(await executeFrontendVoiceTool("select_item", {}, "conversation")).toEqual({ selected: true });
  replacement.setDataReady(false);
  expect(captureFrontendVoiceTools().declarations).toEqual([]);
  expect(await executeFrontendVoiceTool("select_item", {}, "conversation")).toHaveProperty("error");
});

it("keeps all dispatch blocked while incomplete, handles tool errors, and rejects reserved/duplicate registrations", async () => {
  const scope = authenticate().createScope();
  const execute = vi.fn(() => {
    throw new Error("Missing item");
  });
  scope.setTools([{ ...tool, execute }]);
  expect(await executeFrontendVoiceTool("select_item", {}, "conversation")).toHaveProperty("error");
  expect(execute).not.toHaveBeenCalled();
  scope.setDataReady(true);
  expect(await executeFrontendVoiceTool("select_item", {}, "conversation")).toEqual({ error: "Missing item" });
  expect(() => scope.setTools([tool, tool])).toThrow(/reserved/);
  for (const name of ["hang_up", "mute"]) {
    expect(() => scope.setTools([{ ...tool, declaration: { ...tool.declaration, name } }])).toThrow(/reserved/);
  }
  scope.dispose();
  expect(window.dispatchGoModeVoiceTool).toBeUndefined();
});

it("retires identity scopes and ignores stale acknowledgement callbacks or disposed-owner preparation", async () => {
  const first = authenticate();
  const scope = first.createScope();
  scope.setTools([tool]);
  scope.setDataReady(true);
  await Promise.resolve();
  const oldRequest = requests[0]!;
  const oldReceive = bridge.onmessage;
  const current = authenticate();
  oldReceive?.({ data: JSON.stringify({ ...oldRequest, frontendToken: "old" }) });
  acknowledge(oldRequest, "old");
  expect(readiness).toEqual([]);
  expect(scope.ready()).toBe(false);
  const [selection, select] = createSignal<string | null>("selected");
  // The policy evaluates this accessor in its own tracked root; spy on those reads.
  // eslint-disable-next-line solid/reactivity
  const readSelection = vi.fn(() => selection());
  const createPublisher = vi.fn(() => ({ publish: vi.fn(async () => {}), dispose: vi.fn() }));
  const retiredScope = first.createScope({ selection: readSelection, createPublisher });
  retiredScope.setTools([tool]);
  retiredScope.setDataReady(true);
  expect(retiredScope.ready()).toBe(false);
  await expect(retiredScope.prepareSession("retired", new AbortController().signal)).rejects.toHaveProperty(
    "name",
    "AbortError",
  );
  retiredScope.dispose();
  first.dispose();
  select("replacement");
  expect(readSelection).not.toHaveBeenCalled();
  expect(createPublisher).not.toHaveBeenCalled();
  await Promise.resolve();
  acknowledge(requests.at(-1)!, "current");
  const next = current.createScope();
  next.setTools([]);
  next.setDataReady(true);
  first.dispose();
  expect(readiness.at(-1)).toMatchObject({ token: "current", ready: true });
});

it("captures bridges, document binding and granting auth before asynchronous ownership resolves", async () => {
  const binding = deferred<string>();
  window.goModeFrontendDocument = binding.promise;
  const client = createFrontendPreparationClient();
  const replacement = vi.fn();
  Object.assign(window, { gomodeAuth: { postMessage: replacement } });
  window.gomodeFrontendReadiness = { postMessage: replacement };
  window.goModeFrontendDocument = Promise.resolve("replacement");
  const auth = { bearerToken: "captured" };
  owner = client.publishAuth(auth);
  auth.bearerToken = "changed";
  const scope = owner.createScope();
  scope.setTools([]);
  scope.setDataReady(true);
  binding.resolve("document");
  await binding.promise;
  expect(requests[0]).toMatchObject({ bearerToken: "captured", frontendDocument: "document" });
  acknowledge(requests[0]!, "identity");
  expect(readiness.at(-1)).toMatchObject({ token: "identity", ready: true });
  expect(replacement).not.toHaveBeenCalled();
});

it("keeps revocation messages ordered while disposed granting work cannot authenticate late", async () => {
  const binding = deferred<string>();
  window.goModeFrontendDocument = binding.promise;
  const client = createFrontendPreparationClient();
  client.publishAuth({ bearerToken: "old" }).dispose();
  client.publishAuth({ authChanging: true }).dispose();
  client.publishAuth({ bearerToken: null }).dispose();
  owner = client.publishAuth({ authChanged: true, nativeAccess: true });
  binding.resolve("document");
  await binding.promise;
  expect(requests.map(({ frontendDocument: _document, frontendRequest: _request, ...auth }) => auth)).toEqual([
    { authChanging: true },
    { bearerToken: null },
    { authChanged: true, nativeAccess: true },
  ]);
});

it("never grants local readiness for a signed-out identity", async () => {
  owner = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: false });
  const scope = owner.createScope();
  scope.setTools([tool]);
  scope.setDataReady(true);
  expect(scope.ready()).toBe(false);
  expect(await executeFrontendVoiceTool("select_item", {}, "conversation")).toHaveProperty("error");
});

it("awaits a Promise and cancels its captured registration before post-await effects", async () => {
  const scope = authenticate().createScope();
  const gate = deferred<void>();
  let committed = false;
  let canceled = false;
  scope.setTools([
    {
      ...tool,
      execute: async (_args, context) => {
        context.signal.addEventListener("abort", () => {
          canceled = true;
        });
        await gate.promise;
        if (context.isCurrent()) committed = true;
        return { selected: true };
      },
    },
  ]);
  scope.setDataReady(true);
  const pending = captureFrontendVoiceTools().execute("select_item", {}, "conversation");
  scope.setTools([tool]);
  gate.resolve();
  expect(await pending).toHaveProperty("error");
  expect(canceled).toBe(true);
  expect(committed).toBe(false);
});

it("correlates concurrent native Promises and rejects queued registrations before execution", async () => {
  const responses: Record<string, unknown>[] = [];
  window.gomodeFrontendToolResult = { postMessage: (value) => responses.push(JSON.parse(value)) };
  // The client captures the bridge at mount.
  const identity = authenticate();
  const scope = identity.createScope();
  const gates = [deferred<void>(), deferred<void>()];
  const execute = vi.fn(async (args: Record<string, unknown>, _context: FrontendVoiceToolContext) => {
    await gates[Number(args.index)]!.promise;
    return { index: args.index };
  });
  scope.setTools([{ ...tool, execute }]);
  scope.setDataReady(true);
  await Promise.resolve();
  acknowledge(requests.at(-1)!, "identity");
  const binding = readiness.at(-1)!;
  const call = (invocation: string, index: number) => ({
    document: "document",
    token: "identity",
    scope: String(binding.scope),
    invocation,
    sessionID: `conversation-${index}`,
    name: "select_item",
    args: { index },
  });
  window.dispatchGoModeVoiceTool!(call("first", 0));
  window.dispatchGoModeVoiceTool!(call("second", 1));
  window.cancelGoModeVoiceTool!({ ...call("first", 0), sessionID: "conversation-1" });
  expect(execute.mock.calls[0]?.[1].signal.aborted).toBe(false);
  gates[1]!.resolve();
  await vi.waitFor(() => expect(responses).toHaveLength(1));
  expect(responses[0]).toMatchObject({ invocation: "second", sessionID: "conversation-1", result: { index: 1 } });
  expect(execute.mock.calls[0]?.[1]).toMatchObject({ sessionID: "conversation-0" });
  expect(execute.mock.calls[1]?.[1]).toMatchObject({ sessionID: "conversation-1" });
  window.cancelGoModeVoiceTool!(call("first", 0));
  gates[0]!.resolve();
  await Promise.resolve();
  expect(responses).toHaveLength(1);
  const replacementExecute = vi.fn(tool.execute);
  scope.setTools([{ ...tool, execute: replacementExecute }]);
  window.dispatchGoModeVoiceTool!(call("queued", 0));
  expect(execute).toHaveBeenCalledTimes(2);
  expect(replacementExecute).not.toHaveBeenCalled();
  scope.setDataReady(false);
  scope.setDataReady(true);
  window.dispatchGoModeVoiceTool!(call("queued-after-data", 0));
  expect(replacementExecute).not.toHaveBeenCalled();
  delete window.gomodeFrontendToolResult;
});

it("times out cooperative async work and rejects non-object results and rejected Promises", async () => {
  vi.useFakeTimers();
  try {
    const scope = authenticate().createScope();
    const gate = deferred<void>();
    let aborted = false;
    scope.setTools([
      {
        ...tool,
        execute: async (_args, context) => {
          context.signal.addEventListener("abort", () => {
            aborted = true;
          });
          await gate.promise;
          return { late: true };
        },
      },
    ]);
    scope.setDataReady(true);
    const pending = captureFrontendVoiceTools().execute("select_item", {}, "conversation");
    await vi.advanceTimersByTimeAsync(5_000);
    expect(await pending).toEqual({ error: "Frontend voice tool timed out." });
    expect(aborted).toBe(true);
    gate.resolve();
    scope.setTools([
      {
        ...tool,
        execute: async () => {
          throw new Error("Rejected");
        },
      },
    ]);
    expect(await captureFrontendVoiceTools().execute("select_item", {}, "conversation")).toEqual({ error: "Rejected" });
    scope.setTools([{ ...tool, execute: () => [] as unknown as Record<string, unknown> }]);
    expect(await captureFrontendVoiceTools().execute("select_item", {}, "conversation")).toEqual({
      error: "Frontend voice tool returned an invalid result.",
    });
  } finally {
    vi.useRealTimers();
  }
});

it("caller cancellation and data revocation retire work without reauthorizing old snapshots", async () => {
  for (const revoke of ["caller", "data", "identity", "scope"]) {
    const scope = authenticate().createScope();
    const gate = deferred<void>();
    let current = true;
    scope.setTools([
      {
        ...tool,
        execute: async (_args, context) => {
          await gate.promise;
          current = context.isCurrent();
          return { done: true };
        },
      },
    ]);
    scope.setDataReady(true);
    const snapshot = captureFrontendVoiceTools();
    const caller = new AbortController();
    const pending = snapshot.execute("select_item", {}, "conversation", caller.signal);
    if (revoke === "caller") caller.abort();
    if (revoke === "data") {
      scope.setDataReady(false);
      scope.setDataReady(true);
    }
    if (revoke === "identity") authenticate();
    if (revoke === "scope") owner!.createScope();
    expect(await pending).toHaveProperty("error");
    gate.resolve();
    await Promise.resolve();
    expect(current).toBe(false);
    if (revoke !== "caller") expect(await snapshot.execute("select_item", {}, "conversation")).toHaveProperty("error");
  }
});

it("closes invocation liveness after successful completion without aborting unrelated work", async () => {
  const scope = authenticate().createScope();
  let isCurrent = () => false;
  scope.setTools([
    {
      ...tool,
      execute: (_args, context) => {
        isCurrent = context.isCurrent;
        expect(isCurrent()).toBe(true);
        return { done: true };
      },
    },
  ]);
  scope.setDataReady(true);
  expect(await captureFrontendVoiceTools().execute("select_item", {}, "conversation")).toEqual({ done: true });
  expect(isCurrent()).toBe(false);
  expect(scope.ready()).toBe(true);
});

it("keeps conversation identity per invocation and rejects missing IDs before host actions", async () => {
  const scope = authenticate().createScope();
  const execute = vi.fn((_args: Record<string, unknown>, context: FrontendVoiceToolContext) => ({
    sessionID: context.sessionID,
  }));
  scope.setTools([{ ...tool, execute }]);
  scope.setDataReady(true);
  const snapshot = captureFrontendVoiceTools();
  expect(await snapshot.execute("select_item", {}, "first-conversation")).toEqual({ sessionID: "first-conversation" });
  expect(await snapshot.execute("select_item", {}, "second-conversation")).toEqual({
    sessionID: "second-conversation",
  });
  for (const sessionID of [undefined, null, "", " ", "x".repeat(257)]) {
    expect(await Reflect.apply(snapshot.execute, snapshot, ["select_item", {}, sessionID])).toHaveProperty("error");
  }
  expect(execute).toHaveBeenCalledTimes(2);
  await Promise.resolve();
  acknowledge(requests.at(-1)!, "identity");
  const binding = readiness.at(-1)!;
  for (const sessionID of [null, "", " ", "x".repeat(257)]) {
    Reflect.apply(window.dispatchGoModeVoiceTool!, window, [
      {
        document: "document",
        token: "identity",
        scope: String(binding.scope),
        invocation: "forged",
        sessionID,
        name: "select_item",
        args: {},
      },
    ]);
  }
  expect(execute).toHaveBeenCalledTimes(2);
});

it("allows legacy native navigation without inventing conversation focus", async () => {
  const identity = authenticate();
  const scope = identity.createScope();
  const publishFocus = vi.fn();
  const navigate = vi.fn((_args: Record<string, unknown>, context: FrontendVoiceToolContext) => {
    if (context.sessionID !== null && context.isCurrent()) publishFocus(context.sessionID);
    return { navigated: true };
  });
  scope.setTools([{ ...tool, execute: navigate }]);
  scope.setDataReady(true);
  await Promise.resolve();
  acknowledge(requests.at(-1)!, "identity");
  const binding = readiness.at(-1)!;
  window.dispatchGoModeVoiceTool!({
    document: "document",
    token: "identity",
    scope: String(binding.scope),
    invocation: "legacy",
    name: "select_item",
    args: {},
  });
  await vi.waitFor(() => expect(navigate).toHaveBeenCalledOnce());
  expect(navigate.mock.calls[0]?.[1].sessionID).toBeNull();
  expect(publishFocus).not.toHaveBeenCalled();
});

it("shares only pending context work and follows selection supersession before native acknowledgement", async () => {
  const [selection, select] = createSignal<string | null>("first");
  const gates = [deferred<void>(), deferred<void>()];
  const signals: AbortSignal[] = [];
  const publish = vi.fn((_key: string | null, _current: () => boolean, signal: AbortSignal) => {
    signals.push(signal);
    return gates[signals.length - 1]!.promise;
  });
  const dispose = vi.fn();
  const scope = authenticate().createScope({ selection, createPublisher: () => ({ publish, dispose }) });
  scope.setTools([]);
  scope.setDataReady(true);
  await Promise.resolve();
  acknowledge({ ...requests[0], voiceSessionVersion: 1 }, "identity");
  nativeSession("conversation");
  const pending = scope.publishContext("conversation", new AbortController().signal);
  await vi.waitFor(() => expect(publish).toHaveBeenCalledTimes(1));
  select("second");
  expect(signals[0]!.aborted).toBe(true);
  await vi.waitFor(() => expect(publish).toHaveBeenCalledTimes(2));
  gates[0]!.resolve();
  await Promise.resolve();
  expect(readiness.at(-1)?.voiceSessionPrepared).toBeNull();
  gates[1]!.resolve();
  await pending;
  await vi.waitFor(() => expect(readiness.at(-1)?.voiceSessionPrepared).toBe("conversation"));
  nativeSession(null);
  expect(dispose).toHaveBeenCalledOnce();
});

it("retries a settled failed publication for the same selected item", async () => {
  const warning = vi.spyOn(console, "warn").mockImplementation(() => {});
  const publish = vi.fn(async () => {});
  publish.mockRejectedValueOnce(new Error("Publication failed"));
  const scope = authenticate().createScope({
    selection: () => "selected",
    createPublisher: () => ({ publish, dispose() {} }),
  });
  scope.setTools([]);
  scope.setDataReady(true);
  try {
    await expect(scope.prepareSession("conversation", new AbortController().signal)).rejects.toThrow(
      "Publication failed",
    );
    expect(scope.contextPublication()).toEqual({ failure: "request", pending: false });
    await scope.retryContext();
    expect(scope.contextPublication()).toEqual({ failure: null, pending: false });
    expect(publish).toHaveBeenCalledTimes(2);
  } finally {
    warning.mockRestore();
  }
});

it("cancels a browser attempt without retiring its conversation publisher", async () => {
  const gate = deferred<void>();
  const signals: AbortSignal[] = [];
  const publish = vi.fn((_key: string | null, _current: () => boolean, signal: AbortSignal) => {
    signals.push(signal);
    return signals.length === 1 ? gate.promise : Promise.resolve();
  });
  const dispose = vi.fn();
  const createPublisher = vi.fn(() => ({ publish, dispose }));
  const scope = authenticate().createScope({ selection: () => "selected", createPublisher });
  scope.setTools([]);
  scope.setDataReady(true);
  const attempt = new AbortController();
  const pending = scope.prepareSession("conversation", attempt.signal);
  await vi.waitFor(() => expect(publish).toHaveBeenCalledOnce());
  attempt.abort(new DOMException("Caller deadline", "TimeoutError"));
  await expect(pending).rejects.toHaveProperty("name", "AbortError");
  expect(signals[0]!.aborted).toBe(true);
  expect(scope.contextPublication()).toEqual({ failure: null, pending: false });
  await scope.prepareSession("conversation", new AbortController().signal);
  expect(createPublisher).toHaveBeenCalledOnce();
  expect(dispose).not.toHaveBeenCalled();
  scope.retireSession("conversation");
  expect(dispose).toHaveBeenCalledOnce();
  gate.resolve();
});

it("publishes invocation-only context without following later manual selections", async () => {
  const [selection, select] = createSignal<string | null>("selected");
  const publish = vi.fn(async () => {});
  const dispose = vi.fn();
  const createPublisher = vi.fn(() => ({ publish, dispose }));
  const scope = authenticate().createScope({ selection, createPublisher });
  scope.setTools([]);
  scope.setDataReady(true);
  await scope.publishContext("invocation-only", new AbortController().signal);
  expect(createPublisher).toHaveBeenCalledWith("invocation-only", "invocation", expect.any(Function));
  select("manual-change");
  expect(publish).toHaveBeenCalledOnce();
  await scope.publishContext("next-invocation", new AbortController().signal);
  expect(dispose).toHaveBeenCalledOnce();
  expect(publish).toHaveBeenCalledTimes(2);
});

it("bounds a non-cooperating context publication and permits a later same-item retry", async () => {
  vi.useFakeTimers();
  const signals: AbortSignal[] = [];
  const publish = vi.fn((_key: string | null, _current: () => boolean, signal: AbortSignal) => {
    signals.push(signal);
    return signals.length === 1 ? new Promise<void>(() => {}) : Promise.resolve();
  });
  const createPublisher = vi.fn(() => ({ publish, dispose() {} }));
  const scope = authenticate().createScope({ selection: () => "selected", createPublisher });
  scope.setTools([]);
  scope.setDataReady(true);
  try {
    const pending = scope.prepareSession("conversation", new AbortController().signal);
    const rejected = expect(pending).rejects.toHaveProperty("name", "TimeoutError");
    await vi.advanceTimersByTimeAsync(2500);
    await rejected;
    expect(signals[0]!.aborted).toBe(true);
    expect(scope.contextPublication()).toEqual({ failure: "timeout", pending: false });
    await scope.retryContext();
    expect(scope.contextPublication()).toEqual({ failure: null, pending: false });
    expect(publish).toHaveBeenCalledTimes(2);
    expect(createPublisher).toHaveBeenCalledOnce();
  } finally {
    scope.dispose();
    vi.useRealTimers();
  }
});

it("derives current context recovery and isolates late retired-release diagnostics", async () => {
  const warning = vi.spyOn(console, "warn").mockImplementation(() => {});
  const [selection, select] = createSignal<string | null>("first");
  let failRelease!: (error: Error) => void;
  const release = new Promise<void>((_resolve, reject) => {
    failRelease = reject;
  });
  const retry = deferred<void>();
  const publish = vi.fn(async () => {});
  const scope = authenticate().createScope({
    selection,
    createPublisher: (sessionID, _kind, runRelease) => ({
      publish,
      dispose: () => {
        if (sessionID === "first") runRelease(() => release);
      },
    }),
  });
  scope.setTools([]);
  scope.setDataReady(true);
  try {
    await scope.prepareSession("first", new AbortController().signal);
    publish.mockRejectedValueOnce(new FrontendVoiceContextConflict());
    select("second");
    await vi.waitFor(() =>
      expect(scope.contextPublication()).toEqual({ failure: "concurrent-update", pending: false }),
    );
    expect(warning).toHaveBeenCalledTimes(1);
    expect(warning.mock.calls[0]).toEqual([
      "Voice context operation failed",
      { operation: "publish", failure: "concurrent-update" },
    ]);
    publish.mockImplementationOnce(() => retry.promise);
    const pending = scope.retryContext();
    const shared = scope.retryContext();
    expect(scope.contextPublication()).toEqual({ failure: "concurrent-update", pending: true });
    await vi.waitFor(() => expect(publish).toHaveBeenCalledTimes(3));
    scope.retireSession("first");
    expect(scope.contextPublication()).toEqual({ failure: null, pending: false });
    select("third");
    await scope.retryContext();
    expect(publish).toHaveBeenCalledTimes(3);
    await scope.prepareSession("replacement", new AbortController().signal);
    retry.resolve();
    await Promise.all([pending, shared]);
    failRelease(new Error("private request/auth material"));
    await vi.waitFor(() => expect(warning).toHaveBeenCalledTimes(2));
    const diagnostic = warning.mock.calls[1]![1];
    expect(diagnostic).toEqual({ operation: "release", failure: "request" });
    expect(Object.isFrozen(diagnostic)).toBe(true);
    expect(scope.contextPublication()).toEqual({ failure: null, pending: false });
    expect(scope.ready()).toBe(true);
  } finally {
    scope.dispose();
    await Promise.resolve();
    await Promise.resolve();
    warning.mockRestore();
  }
});

it("keeps supersession quiet and bounds detached release without changing retired context", async () => {
  vi.useFakeTimers();
  const warning = vi.spyOn(console, "warn").mockImplementation(() => {});
  const [selection, select] = createSignal<string | null>("first");
  let releaseSignal: AbortSignal | undefined;
  const publish = vi.fn(async () => {});
  const scope = authenticate().createScope({
    selection,
    createPublisher: (_sessionID, _kind, runRelease) => ({
      publish,
      dispose: () =>
        runRelease((signal) => {
          releaseSignal = signal;
          return new Promise<void>(() => {});
        }),
    }),
  });
  scope.setTools([]);
  scope.setDataReady(true);
  try {
    await scope.prepareSession("conversation", new AbortController().signal);
    publish.mockImplementationOnce(() => new Promise<void>(() => {}));
    select("pending");
    await Promise.resolve();
    select("latest");
    await vi.advanceTimersByTimeAsync(0);
    expect(warning).not.toHaveBeenCalled();
    expect(scope.contextPublication()).toEqual({ failure: null, pending: false });
    scope.retireSession("conversation");
    await vi.advanceTimersByTimeAsync(2500);
    expect(releaseSignal?.aborted).toBe(true);
    expect(warning).toHaveBeenCalledExactlyOnceWith("Voice context operation failed", {
      operation: "release",
      failure: "timeout",
    });
    expect(scope.contextPublication()).toEqual({ failure: null, pending: false });
    await scope.retryContext();
    expect(publish).toHaveBeenCalledTimes(3);
  } finally {
    scope.dispose();
    warning.mockRestore();
    vi.useRealTimers();
  }
});
