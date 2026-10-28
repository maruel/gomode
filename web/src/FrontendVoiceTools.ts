// Owns authenticated frontend preparation, replaceable product scopes, and scoped voice-tool dispatch.

import { createSignal, untrack, type Accessor } from "solid-js";
import type { ToolDeclaration } from "../../sdk/voicegateway/ts/v1/types.gen";

export interface FrontendVoiceToolContext {
  signal: AbortSignal;
  /** Check after awaited work, immediately before committing a host action. */
  isCurrent(): boolean;
}

export interface FrontendVoiceTool {
  declaration: ToolDeclaration;
  execute: (
    args: Record<string, unknown>,
    context: FrontendVoiceToolContext,
  ) => Record<string, unknown> | Promise<Record<string, unknown>>;
}

export interface FrontendVoiceToolsSnapshot {
  declarations: ToolDeclaration[];
  execute(name: string, args: Record<string, unknown>, signal?: AbortSignal): Promise<Record<string, unknown>>;
}

export interface FrontendPreparationScope {
  /** Local readiness works in browsers without waiting for a native acknowledgement. */
  ready: Accessor<boolean>;
  /** Atomically register every intended tool; [] explicitly completes zero-tool registration. */
  setTools(tools: readonly FrontendVoiceTool[]): void;
  /** Report authoritative initial-data completion, including a successful empty result. */
  setDataReady(ready: boolean): void;
  dispose(): void;
}

export interface FrontendPreparation {
  /** Replace product/workspace preparation without replacing authenticated identity. */
  createScope(): FrontendPreparationScope;
  dispose(): void;
}

declare global {
  interface Window {
    dispatchGoModeVoiceTool?: (request: NativeToolInvocation) => void;
    cancelGoModeVoiceTool?: (request: NativeInvocation) => void;
    gomodeFrontendToolResult?: { postMessage(message: string): void };
    goModeFrontendDocument?: Promise<string>;
    gomodeFrontendReadiness?: { postMessage: (message: string) => void };
  }
}

interface AuthBridge {
  onmessage?: ((event: { data: string }) => void) | null;
  postMessage(message: string): void;
}

interface NativeInvocation {
  document: string;
  token: string;
  scope: string;
  invocation: string;
}

interface NativeToolInvocation extends NativeInvocation {
  name: string;
  args: Record<string, unknown>;
}

type ActiveScope = FrontendPreparationScope & { snapshot(): FrontendVoiceToolsSnapshot; binding(): string };
let preparation: FrontendPreparation | null = null;
let activeScope: ActiveScope | null = null;
let nextPreparationRequest = 0;
let nextRegistration = 0;

/** Capture this client when the frontend mounts, before asynchronous authentication. */
export function createFrontendPreparationClient(): {
  publishAuth(auth: Record<string, unknown>): FrontendPreparation;
} {
  const bridge = (window as unknown as { gomodeAuth?: AuthBridge }).gomodeAuth;
  const documentBinding = window.goModeFrontendDocument;
  const readinessBridge = window.gomodeFrontendReadiness;
  const resultBridge = window.gomodeFrontendToolResult;
  return {
    publishAuth(auth) {
      preparation?.dispose();
      const request = String(++nextPreparationRequest);
      const authMessage = { ...auth };
      const grantsAccess =
        (typeof authMessage.bearerToken === "string" && authMessage.bearerToken.length > 0) ||
        (authMessage.authChanged === true && authMessage.nativeAccess === true);
      let document: string | null = null;
      let token: string | null = null;
      let disposed = false;
      let scope: ActiveScope | null = null;
      const publish = () =>
        untrack(() => {
          if (disposed || token === null) return;
          const ready = scope?.ready() === true;
          readinessBridge?.postMessage(
            JSON.stringify({
              document,
              token,
              scope: scope?.binding() ?? "",
              ready,
              tools: ready ? scope?.snapshot().declarations : [],
            }),
          );
        });
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
          reply.frontendDocument !== document ||
          reply.frontendRequest !== request ||
          typeof reply.frontendToken !== "string"
        )
          return;
        token = reply.frontendToken;
        publish();
      };
      const owner: FrontendPreparation = {
        createScope() {
          scope?.dispose();
          const [ready, setReady] = createSignal(false);
          let tools: Map<string, FrontendVoiceTool> | null = null;
          let dataReady = false;
          let released = disposed;
          let registration = String(++nextRegistration);
          let lifetime = new AbortController();
          const pending = new Map<string, AbortController>();
          const invalidate = () => {
            lifetime.abort();
            lifetime = new AbortController();
            registration = String(++nextRegistration);
          };
          const owns = (request: NativeInvocation) =>
            untrack(
              () =>
                activeScope === current &&
                ready() &&
                request.document === document &&
                request.token === token &&
                request.scope === registration,
            );

          const dispatch = (request: NativeToolInvocation) => {
            if (!owns(request) || pending.has(request.invocation)) return;
            const controller = new AbortController();
            pending.set(request.invocation, controller);
            void current
              .snapshot()
              .execute(request.name, request.args, controller.signal)
              .then((result) => {
                if (pending.get(request.invocation) !== controller) return;
                pending.delete(request.invocation);
                if (owns(request))
                  resultBridge?.postMessage(JSON.stringify({ ...request, name: undefined, args: undefined, result }));
              });
          };
          const cancel = (request: NativeInvocation) => {
            if (!owns(request)) return;
            pending.get(request.invocation)?.abort();
            pending.delete(request.invocation);
          };
          const update = () => {
            setReady(!released && grantsAccess && tools !== null && dataReady);
            publish();
          };
          const current: ActiveScope = {
            ready,
            binding: () => registration,
            setTools(values) {
              if (released) return;
              const next = new Map<string, FrontendVoiceTool>();
              for (const tool of values) {
                const name = tool.declaration.name;
                if (name === "hang_up" || next.has(name)) throw new Error(`Voice tool "${name}" is already reserved.`);
                next.set(name, tool);
              }
              invalidate();
              tools = next;
              update();
            },
            setDataReady(value) {
              if (released) return;
              if (dataReady && !value) invalidate();
              dataReady = value;
              update();
            },
            snapshot() {
              const captured = tools;
              const capturedRegistration = registration;
              const capturedLifetime = lifetime.signal;
              return {
                declarations:
                  ready() && captured !== null ? Array.from(captured.values(), (tool) => tool.declaration) : [],
                execute(name, args, signal) {
                  const isCurrent = () => activeScope === current && ready() && capturedRegistration === registration;
                  const tool = isCurrent() ? captured?.get(name) : null;
                  return tool
                    ? runTool(tool, args, [capturedLifetime, signal], isCurrent)
                    : Promise.resolve(unavailableTool(name));
                },
              };
            },
            dispose() {
              if (released) return;
              released = true;
              invalidate();
              pending.forEach((controller) => controller.abort());
              pending.clear();
              setReady(false);
              if (scope === current) scope = null;
              if (activeScope === current) {
                activeScope = null;
                delete window.dispatchGoModeVoiceTool;
                delete window.cancelGoModeVoiceTool;
              }
              publish();
            },
          };
          if (!disposed) {
            scope = current;
            activeScope = current;
            window.dispatchGoModeVoiceTool = dispatch;
            window.cancelGoModeVoiceTool = cancel;
            publish();
          }
          return current;
        },
        dispose() {
          if (disposed) return;
          scope?.dispose();
          disposed = true;
          token = null;
          if (bridge?.onmessage === receive) bridge.onmessage = null;
          if (preparation === owner) preparation = null;
        },
      };
      preparation = owner;
      if (bridge && documentBinding !== undefined) {
        if (grantsAccess) bridge.onmessage = receive;
        void documentBinding.then((boundDocument) => {
          // Revocations remain ordered; retired grants can never post late authentication.
          if (disposed && grantsAccess) return;
          document = boundDocument;
          bridge.postMessage(JSON.stringify({ ...authMessage, frontendDocument: document, frontendRequest: request }));
        });
      }
      return owner;
    },
  };
}

function unavailableTool(name: string): Record<string, unknown> {
  return { error: `Frontend voice tool "${name}" is unavailable.` };
}

/** Capture declarations and dispatch together before handing tools to a conversation. */
export function captureFrontendVoiceTools(): FrontendVoiceToolsSnapshot {
  return activeScope?.snapshot() ?? { declarations: [], execute: async (name) => unavailableTool(name) };
}

export function executeFrontendVoiceTool(
  name: string,
  args: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  return captureFrontendVoiceTools().execute(name, args);
}

// The runner owns bounded completion; host actions cooperate by checking context after awaits.
function runTool(
  tool: FrontendVoiceTool,
  args: Record<string, unknown>,
  signals: (AbortSignal | undefined)[],
  owns: () => boolean,
): Promise<Record<string, unknown>> {
  const controller = new AbortController();
  let settled = false;
  const isCurrent = () => !settled && !controller.signal.aborted && owns();
  return new Promise<Record<string, unknown>>((resolve) => {
    const finish = (result: Record<string, unknown>) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signals.forEach((signal) => signal?.removeEventListener("abort", cancel));
      resolve(result);
    };
    const cancel = () => {
      controller.abort();
      finish({ error: "Frontend voice tool canceled." });
    };
    const timer = setTimeout(() => {
      controller.abort();
      finish({ error: "Frontend voice tool timed out." });
    }, 5_000);
    signals.forEach((signal) => signal?.addEventListener("abort", cancel, { once: true }));
    if (signals.some((signal) => signal?.aborted) || !owns()) {
      cancel();
      return;
    }
    try {
      void Promise.resolve(tool.execute(args, { signal: controller.signal, isCurrent }))
        .then((value) => {
          if (!isCurrent()) {
            cancel();
            return;
          }
          // Serialization validates the same JSON-object contract in browsers and Android.
          const encoded = JSON.stringify(value);
          const result: unknown = encoded === undefined ? null : JSON.parse(encoded);
          if (typeof result !== "object" || result === null || Array.isArray(result)) {
            finish({ error: "Frontend voice tool returned an invalid result." });
          } else finish(result as Record<string, unknown>);
        })
        .catch((error: unknown) =>
          finish({ error: error instanceof Error ? error.message : "Frontend voice tool failed." }),
        );
    } catch (error: unknown) {
      finish({ error: error instanceof Error ? error.message : "Frontend voice tool failed." });
    }
  });
}
