// Tests frontend voice tool registration, native publication, errors, and disposal.

import { it } from "node:test";
import { expect, vi } from "../tests/expect";
import {
  createFrontendPreparationClient,
  executeFrontendVoiceTool,
  registerFrontendVoiceTool,
} from "./FrontendVoiceTools";

it("registers browser tools and removes handlers on disposal", () => {
  const execute = vi.fn(() => ({ selected: true }));
  const tool = { declaration: { name: "select_item", description: "Select item", parameters: {} }, execute };
  const unregister = registerFrontendVoiceTool(tool);
  try {
    expect(window.executeGoModeVoiceTool?.("select_item", { id: "one" })).toEqual({ selected: true });
    expect(execute).toHaveBeenCalledWith({ id: "one" });
    expect(() => registerFrontendVoiceTool(tool)).toThrow(/reserved/);
    expect(() => registerFrontendVoiceTool({ ...tool, declaration: { ...tool.declaration, name: "hang_up" } })).toThrow(
      /reserved/,
    );
  } finally {
    unregister();
  }
  expect(window.executeGoModeVoiceTool).toBeUndefined();
  expect(executeFrontendVoiceTool("select_item", {})).toHaveProperty("error");
});

it("waits for its authenticated acknowledgement and explicitly prepares zero tools", async () => {
  const postMessage = vi.fn();
  const messages = vi.fn();
  const auth: { postMessage: typeof postMessage; onmessage?: ((event: { data: string }) => void) | null } = {
    postMessage,
  };
  Object.assign(window, { gomodeAuth: auth });
  window.goModeFrontendDocument = Promise.resolve("document");
  window.gomodeFrontendReadiness = { postMessage: messages };
  const owner = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: true });
  try {
    await Promise.resolve();
    owner.setReady(true);
    expect(messages).not.toHaveBeenCalled();
    const request = JSON.parse(postMessage.mock.calls[0]?.[0] as string) as Record<string, unknown>;
    auth.onmessage?.({ data: JSON.stringify({ ...request, frontendToken: "identity" }) });
    expect(JSON.parse(messages.mock.calls[0]?.[0] as string)).toEqual({
      document: "document",
      token: "identity",
      ready: true,
      tools: [],
    });
    owner.setReady(false);
    expect(JSON.parse(messages.mock.calls[1]?.[0] as string)).toHaveProperty("ready", false);
  } finally {
    owner.dispose();
    Reflect.deleteProperty(window, "gomodeAuth");
    delete window.goModeFrontendDocument;
    delete window.gomodeFrontendReadiness;
  }
});

it("old identity work and old document acknowledgements cannot prepare a replacement", async () => {
  const postMessage = vi.fn();
  const messages = vi.fn();
  const auth: { postMessage: typeof postMessage; onmessage?: ((event: { data: string }) => void) | null } = {
    postMessage,
  };
  Object.assign(window, { gomodeAuth: auth });
  window.goModeFrontendDocument = Promise.resolve("first-document");
  window.gomodeFrontendReadiness = { postMessage: messages };
  const first = createFrontendPreparationClient().publishAuth({ bearerToken: "first" });
  await Promise.resolve();
  const receiveFirst = auth.onmessage;
  const firstRequest = JSON.parse(postMessage.mock.calls[0]?.[0] as string) as Record<string, unknown>;
  window.goModeFrontendDocument = Promise.resolve("second-document");
  const second = createFrontendPreparationClient().publishAuth({ bearerToken: "second" });
  try {
    await Promise.resolve();
    first.setReady(true);
    receiveFirst?.({ data: JSON.stringify({ ...firstRequest, frontendToken: "old" }) });
    auth.onmessage?.({ data: JSON.stringify({ ...firstRequest, frontendToken: "old" }) });
    expect(messages).not.toHaveBeenCalled();
    const secondRequest = JSON.parse(postMessage.mock.calls[1]?.[0] as string) as Record<string, unknown>;
    auth.onmessage?.({ data: JSON.stringify({ ...secondRequest, frontendToken: "new" }) });
    expect(JSON.parse(messages.mock.calls[0]?.[0] as string)).toHaveProperty("ready", false);
    second.setReady(true);
    expect(JSON.parse(messages.mock.calls[1]?.[0] as string)).toHaveProperty("token", "new");
    first.dispose();
    second.setReady(false);
    expect(JSON.parse(messages.mock.calls[2]?.[0] as string)).toHaveProperty("ready", false);
  } finally {
    second.dispose();
    Reflect.deleteProperty(window, "gomodeAuth");
    delete window.goModeFrontendDocument;
    delete window.gomodeFrontendReadiness;
  }
});

it("publishes the complete registered tool snapshot whether data or registration completes first", async () => {
  const postMessage = vi.fn();
  const messages = vi.fn();
  const auth: { postMessage: typeof postMessage; onmessage?: ((event: { data: string }) => void) | null } = {
    postMessage,
  };
  Object.assign(window, { gomodeAuth: auth });
  window.goModeFrontendDocument = Promise.resolve("document");
  window.gomodeFrontendReadiness = { postMessage: messages };
  for (const earlyData of [false, true]) {
    const owner = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: true });
    await Promise.resolve();
    const request = JSON.parse(postMessage.mock.lastCall?.[0] as string) as Record<string, unknown>;
    if (earlyData) owner.setReady(true);
    const unregister = registerFrontendVoiceTool({
      declaration: { name: "select_item", description: "Select", parameters: {} },
      execute: () => ({}),
    });
    try {
      auth.onmessage?.({ data: JSON.stringify({ ...request, frontendToken: "identity" }) });
      if (!earlyData) owner.setReady(true);
      const state = JSON.parse(messages.mock.lastCall?.[0] as string) as { ready: boolean; tools: { name: string }[] };
      expect(state.ready).toBe(true);
      expect(state.tools.map((tool) => tool.name)).toEqual(["select_item"]);
    } finally {
      owner.dispose();
      unregister();
      expect(JSON.parse(messages.mock.lastCall?.[0] as string)).toMatchObject({ ready: false, tools: [] });
    }
  }
  Reflect.deleteProperty(window, "gomodeAuth");
  delete window.goModeFrontendDocument;
  delete window.gomodeFrontendReadiness;
});

it("preserves ordered auth revocation despite synchronous owner disposal or replacement", async () => {
  const postMessage = vi.fn();
  const messages = vi.fn();
  const auth: { postMessage: typeof postMessage; onmessage?: ((event: { data: string }) => void) | null } = {
    postMessage,
  };
  Object.assign(window, { gomodeAuth: auth });
  window.goModeFrontendDocument = Promise.resolve("document");
  window.gomodeFrontendReadiness = { postMessage: messages };
  const old = createFrontendPreparationClient().publishAuth({ bearerToken: "old" });
  await Promise.resolve();
  const oldRequest = JSON.parse(postMessage.mock.lastCall?.[0] as string) as Record<string, unknown>;
  const receiveOld = auth.onmessage;
  createFrontendPreparationClient().publishAuth({ authChanging: true }).dispose();
  createFrontendPreparationClient().publishAuth({ bearerToken: null }).dispose();
  createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: false }).dispose();
  const current = createFrontendPreparationClient().publishAuth({ bearerToken: "current" });
  const receiveCurrent = auth.onmessage;
  current.setReady(true);
  try {
    await Promise.resolve();
    const requests = postMessage.mock.calls.map((call) => JSON.parse(call[0] as string) as Record<string, unknown>);
    expect(
      requests.map((request) => {
        const payload = { ...request };
        delete payload.frontendDocument;
        delete payload.frontendRequest;
        return payload;
      }),
    ).toEqual([
      { bearerToken: "old" },
      { authChanging: true },
      { bearerToken: null },
      { authChanged: true, nativeAccess: false },
      { bearerToken: "current" },
    ]);
    expect(auth.onmessage).toBe(receiveCurrent);
    receiveOld?.({ data: JSON.stringify({ ...oldRequest, frontendToken: "old-token" }) });
    expect(messages).not.toHaveBeenCalled();
    auth.onmessage?.({ data: JSON.stringify({ ...requests.at(-1), frontendToken: "current-token" }) });
    expect(JSON.parse(messages.mock.lastCall?.[0] as string)).toMatchObject({ token: "current-token", ready: true });
    old.dispose();
    expect(auth.onmessage).toBe(receiveCurrent);
  } finally {
    current.dispose();
    Reflect.deleteProperty(window, "gomodeAuth");
    delete window.goModeFrontendDocument;
    delete window.gomodeFrontendReadiness;
  }
});

it("captures the granting identity before asynchronous document ownership resolves", async () => {
  const postMessage = vi.fn();
  let resolveBinding!: (document: string) => void;
  const binding = new Promise<string>((resolve) => {
    resolveBinding = resolve;
  });
  Object.assign(window, { gomodeAuth: { postMessage } });
  window.goModeFrontendDocument = binding;
  const auth = { bearerToken: "captured" };
  const owner = createFrontendPreparationClient().publishAuth(auth);
  auth.bearerToken = "replacement";
  resolveBinding("document");
  await binding;
  expect(JSON.parse(postMessage.mock.lastCall?.[0] as string)).toHaveProperty("bearerToken", "captured");
  owner.dispose();
  Reflect.deleteProperty(window, "gomodeAuth");
  delete window.goModeFrontendDocument;
});

it("disposing while document ownership is unresolved never publishes late authentication", async () => {
  const postMessage = vi.fn();
  let resolveBinding!: (document: string) => void;
  const binding = new Promise<string>((resolve) => {
    resolveBinding = resolve;
  });
  Object.assign(window, { gomodeAuth: { postMessage } });
  window.goModeFrontendDocument = binding;
  const owner = createFrontendPreparationClient().publishAuth({ bearerToken: "first" });
  owner.setReady(true);
  owner.dispose();
  resolveBinding("late-document");
  await binding;
  expect(postMessage).not.toHaveBeenCalled();
  Reflect.deleteProperty(window, "gomodeAuth");
  delete window.goModeFrontendDocument;
});

it("keeps browser-only preparation local when the native bridge is absent", () => {
  const owner = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: true });
  owner.setReady(true);
  owner.setReady(false);
  owner.dispose();
  expect(window.gomodeFrontendReadiness).toBeUndefined();
  expect(window.executeGoModeVoiceTool).toBeUndefined();
});

it("binds delayed authentication and readiness to the frontend captured before async work", async () => {
  const oldMessages = vi.fn();
  const replacementMessages = vi.fn();
  const oldAuth: { postMessage: ReturnType<typeof vi.fn>; onmessage?: ((event: { data: string }) => void) | null } = {
    postMessage: vi.fn(),
  };
  const replacementAuth = { postMessage: vi.fn() };
  Object.assign(window, { gomodeAuth: oldAuth });
  window.goModeFrontendDocument = Promise.resolve("old-page");
  window.gomodeFrontendReadiness = { postMessage: oldMessages };
  const client = createFrontendPreparationClient();
  await Promise.resolve();
  Object.assign(window, { gomodeAuth: replacementAuth });
  window.goModeFrontendDocument = Promise.resolve("replacement-page");
  window.gomodeFrontendReadiness = { postMessage: replacementMessages };
  const owner = client.publishAuth({ bearerToken: "old-auth-result" });
  try {
    await Promise.resolve();
    const request = JSON.parse(oldAuth.postMessage.mock.lastCall?.[0] as string) as Record<string, unknown>;
    expect(request.frontendDocument).toBe("old-page");
    expect(replacementAuth.postMessage).not.toHaveBeenCalled();
    oldAuth.onmessage?.({ data: JSON.stringify({ ...request, frontendToken: "old-token" }) });
    owner.setReady(true);
    expect(JSON.parse(oldMessages.mock.lastCall?.[0] as string)).toMatchObject({ document: "old-page", ready: true });
    expect(replacementMessages).not.toHaveBeenCalled();
  } finally {
    owner.dispose();
    Reflect.deleteProperty(window, "gomodeAuth");
    delete window.goModeFrontendDocument;
    delete window.gomodeFrontendReadiness;
  }
});
