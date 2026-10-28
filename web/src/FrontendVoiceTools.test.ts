// Tests explicit tool/data preparation, authenticated ownership, and dispatch isolation across scope replacement.

import { afterEach, beforeEach, it } from "node:test";
import { expect, vi } from "../tests/expect";
import { deferred } from "../tests/deferred";
import {
  captureFrontendVoiceTools,
  createFrontendPreparationClient,
  executeFrontendVoiceTool,
  type FrontendPreparation,
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

it("replaces workspace scopes without reauthenticating and cannot dispatch a replacement from an old conversation", async () => {
  const identity = authenticate();
  const first = identity.createScope();
  const execute = vi.fn(tool.execute);
  first.setTools([{ ...tool, execute }]);
  first.setDataReady(true);
  await Promise.resolve();
  acknowledge(requests[0]!, "identity");
  const conversation = captureFrontendVoiceTools();
  expect(await conversation.execute("select_item", {})).toEqual({ selected: true });
  const replacement = identity.createScope();
  expect(requests).toHaveLength(1);
  expect(readiness.at(-1)).toMatchObject({ token: "identity", ready: false, tools: [] });
  replacement.setTools([tool]);
  replacement.setDataReady(true);
  first.setDataReady(true);
  first.setTools([tool]);
  first.dispose();
  expect(replacement.ready()).toBe(true);
  expect(await conversation.execute("select_item", {})).toHaveProperty("error");
  expect(execute).toHaveBeenCalledOnce();
  expect(await executeFrontendVoiceTool("select_item", {})).toEqual({ selected: true });
  replacement.setDataReady(false);
  expect(captureFrontendVoiceTools().declarations).toEqual([]);
  expect(await executeFrontendVoiceTool("select_item", {})).toHaveProperty("error");
});

it("keeps all dispatch blocked while incomplete, handles tool errors, and rejects reserved/duplicate registrations", async () => {
  const scope = authenticate().createScope();
  const execute = vi.fn(() => {
    throw new Error("Missing item");
  });
  scope.setTools([{ ...tool, execute }]);
  expect(await executeFrontendVoiceTool("select_item", {})).toHaveProperty("error");
  expect(execute).not.toHaveBeenCalled();
  scope.setDataReady(true);
  expect(await executeFrontendVoiceTool("select_item", {})).toEqual({ error: "Missing item" });
  expect(() => scope.setTools([tool, tool])).toThrow(/reserved/);
  expect(() => scope.setTools([{ ...tool, declaration: { ...tool.declaration, name: "hang_up" } }])).toThrow(
    /reserved/,
  );
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
  const retiredScope = first.createScope();
  retiredScope.setTools([tool]);
  retiredScope.setDataReady(true);
  expect(retiredScope.ready()).toBe(false);
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
  expect(await executeFrontendVoiceTool("select_item", {})).toHaveProperty("error");
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
  const pending = captureFrontendVoiceTools().execute("select_item", {});
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
  const execute = vi.fn(async (args: Record<string, unknown>) => {
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
    name: "select_item",
    args: { index },
  });
  window.dispatchGoModeVoiceTool!(call("first", 0));
  window.dispatchGoModeVoiceTool!(call("second", 1));
  gates[1]!.resolve();
  await vi.waitFor(() => expect(responses).toHaveLength(1));
  expect(responses[0]).toMatchObject({ invocation: "second", result: { index: 1 } });
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
    const pending = captureFrontendVoiceTools().execute("select_item", {});
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
    expect(await captureFrontendVoiceTools().execute("select_item", {})).toEqual({ error: "Rejected" });
    scope.setTools([{ ...tool, execute: () => [] as unknown as Record<string, unknown> }]);
    expect(await captureFrontendVoiceTools().execute("select_item", {})).toEqual({
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
    const pending = snapshot.execute("select_item", {}, caller.signal);
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
    if (revoke !== "caller") expect(await snapshot.execute("select_item", {})).toHaveProperty("error");
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
  expect(await captureFrontendVoiceTools().execute("select_item", {})).toEqual({ done: true });
  expect(isCurrent()).toBe(false);
  expect(scope.ready()).toBe(true);
});
