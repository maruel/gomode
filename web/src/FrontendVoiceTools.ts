// Registers frontend voice tools and publishes explicit, identity-owned native preparation.

import type { ToolDeclaration } from "../../sdk/voicegateway/ts/v1/types.gen";

export interface FrontendVoiceTool {
  declaration: ToolDeclaration;
  execute: (args: Record<string, unknown>) => Record<string, unknown>;
}

declare global {
  interface Window {
    executeGoModeVoiceTool?: typeof executeFrontendVoiceTool;
    goModeFrontendDocument?: Promise<string>;
    gomodeFrontendReadiness?: { postMessage: (message: string) => void };
  }
}

const tools = new Map<string, FrontendVoiceTool>();
let preparation: (FrontendPreparation & { publish(): void }) | null = null;
let nextPreparationRequest = 0;

export interface FrontendPreparation {
  /** Publish true only after tool registration and all required initial data complete. */
  setReady(ready: boolean): void;
  /** Revoke this identity owner; delayed work using it can no longer publish readiness. */
  dispose(): void;
}

interface AuthBridge {
  onmessage?: ((event: { data: string }) => void) | null;
  postMessage(message: string): void;
}

/** Capture this client when the owning frontend mounts, before asynchronous authentication. */
export function createFrontendPreparationClient(): {
  publishAuth(auth: Record<string, unknown>): FrontendPreparation;
} {
  const bridge = (window as unknown as { gomodeAuth?: AuthBridge }).gomodeAuth;
  const documentBinding = window.goModeFrontendDocument;
  const readinessBridge = window.gomodeFrontendReadiness;
  return {
    publishAuth(auth) {
      return publishAuthPreparation(auth, bridge, documentBinding, readinessBridge);
    },
  };
}

/** Each accepted identity owns its acknowledgement and required initial preparation. */
function publishAuthPreparation(
  auth: Record<string, unknown>,
  bridge: AuthBridge | undefined,
  documentBinding: Promise<string> | undefined,
  readinessBridge: Window["gomodeFrontendReadiness"],
): FrontendPreparation {
  preparation?.dispose();
  let frontendDocument: string | null = null;
  const request = String(++nextPreparationRequest);
  const authMessage = { ...auth };
  const grantsAccess =
    (typeof authMessage.bearerToken === "string" && authMessage.bearerToken.length > 0) ||
    (authMessage.authChanged === true && authMessage.nativeAccess === true);
  let token: string | null = null;
  let ready = false;
  let disposed = false;
  const publish = () => {
    if (disposed || token === null) return;
    readinessBridge?.postMessage(
      JSON.stringify({ document: frontendDocument, token, ready, tools: ready ? frontendVoiceToolDeclarations() : [] }),
    );
  };
  const receive = (event: { data: string }) => {
    let value: unknown;
    try {
      value = JSON.parse(event.data);
    } catch (error) {
      console.error("Invalid Go Mode preparation acknowledgement", error);
      return;
    }
    if (disposed || typeof value !== "object" || value === null) return;
    const reply = value as Record<string, unknown>;
    if (
      reply.frontendDocument !== frontendDocument ||
      reply.frontendRequest !== request ||
      typeof reply.frontendToken !== "string"
    )
      return;
    token = reply.frontendToken;
    publish();
  };
  const owner: FrontendPreparation & { publish(): void } = {
    publish,
    setReady(value) {
      if (disposed) return;
      ready = value;
      publish();
    },
    dispose() {
      if (disposed) return;
      ready = false;
      publish();
      disposed = true;
      token = null;
      if (bridge?.onmessage === receive) bridge.onmessage = null;
      if (preparation === owner) preparation = null;
    },
  };
  preparation = owner;
  if (bridge && documentBinding !== undefined) {
    if (grantsAccess) bridge.onmessage = receive;
    void documentBinding.then((document) => {
      if (disposed && grantsAccess) return;
      frontendDocument = document;
      bridge.postMessage(JSON.stringify({ ...authMessage, frontendDocument, frontendRequest: request }));
    });
  }
  return owner;
}

export function frontendVoiceToolDeclarations(): ToolDeclaration[] {
  return Array.from(tools.values(), (tool) => tool.declaration);
}

export function executeFrontendVoiceTool(name: string, args: Record<string, unknown>): Record<string, unknown> {
  const tool = tools.get(name);
  if (!tool) return { error: `Frontend voice tool "${name}" is unavailable.` };
  try {
    return tool.execute(args);
  } catch (error: unknown) {
    return { error: error instanceof Error ? error.message : "Frontend voice tool failed." };
  }
}

// Register before connecting voice. Tools execute synchronously in their owning
// page; native voice transports declarations and results without knowing host UI.
export function registerFrontendVoiceTool(tool: FrontendVoiceTool): () => void {
  const name = tool.declaration.name;
  if (name === "hang_up" || tools.has(name)) throw new Error(`Voice tool "${name}" is already reserved.`);
  tools.set(name, tool);
  window.executeGoModeVoiceTool = executeFrontendVoiceTool;
  publish();
  return () => {
    if (tools.get(name) !== tool) return;
    tools.delete(name);
    publish();
    if (tools.size === 0) delete window.executeGoModeVoiceTool;
  };
}

function publish(): void {
  preparation?.publish();
}
