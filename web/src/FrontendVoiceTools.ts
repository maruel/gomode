// Binds authenticated frontend preparation, registration, native sessions, and tool dispatch to their owning records.
//
// Bridge-3 wire contract, as enforced here:
// 1. Capture window.goModeFrontendDocument, gomodeAuth, and
//    gomodeFrontendReadiness at mount; await the document Promise before auth.
// 2. Every gomodeAuth message carries frontendDocument and a fresh
//    frontendRequest: nonblank strings of at most 128 characters.
// 3. Access-granting auth receives {frontendDocument, frontendRequest,
//    frontendToken} on gomodeAuth.onmessage. Revocations receive none and are
//    queued in order, so a disposed owner cannot post a late login.
// 4. gomodeFrontendReadiness receives {document, token, scope, ready, tools};
//    tools is the complete declaration array, including [].
// 5. Auth capability voiceSessionVersion:1 enables authenticated readiness replies
//    {document,token,scope,voiceSessionVersion:1,sessionID:string|null}. A scope
//    publishes voiceSessionPrepared only after its initial host callback settles;
//    ordinary failures warn, while retirement aborts work and suppresses late ACKs.
//    Both ends retain nonblocking behavior for older clients without this capability.
// Native dispatch calls window.dispatchGoModeVoiceTool({document, token, scope,
// invocation, sessionID, name, args}) and cancelGoModeVoiceTool with the same fields;
// completion posts {document, token, scope, invocation, result} to
// gomodeFrontendToolResult. This module ignores another document, identity,
// registration, or a duplicate invocation. Legacy bridge-3 invocations may omit
// sessionID; their host context receives null and must not publish conversation focus.

import { createSignal, untrack, type Accessor } from "solid-js";
import {
  createFrontendVoiceContext,
  type FrontendVoiceContextPolicy,
  type FrontendVoiceContextPublication,
} from "./FrontendVoiceContext";
export { FrontendVoiceContextConflict } from "./FrontendVoiceContext";
export type {
  FrontendVoiceContextPolicy,
  FrontendVoiceContextPublisher,
  FrontendVoiceContextRelease,
  FrontendVoiceContextPublication,
} from "./FrontendVoiceContext";
import type { ToolDeclaration } from "../../sdk/voicegateway/ts/v2/types.gen";

export interface FrontendVoiceToolContext {
  /** MCP openai/session identity; null for legacy native invocations that omit it. */
  readonly sessionID: string | null;
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
  execute(
    name: string,
    args: Record<string, unknown>,
    sessionID: string,
    signal?: AbortSignal,
  ): Promise<Record<string, unknown>>;
}

export interface FrontendSessionPreparation {
  prepareSession(sessionID: string, signal: AbortSignal): Promise<void>;
  retireSession(sessionID: string): void;
}

export interface FrontendPreparationScope extends FrontendSessionPreparation {
  contextPublication: Accessor<FrontendVoiceContextPublication>;
  retryContext(): Promise<void>;
  /** Publish the selected context for an explicit tool without inventing a legacy manual-selection lifetime. */
  publishContext(sessionID: string, signal: AbortSignal): Promise<void>;
  /** Local readiness works in browsers without waiting for a native acknowledgement. */
  ready: Accessor<boolean>;
  /** Authenticated native conversation lifetime; absent on older shells and after retirement. */
  nativeVoiceSessionID: Accessor<string | null>;
  /** Atomically register every intended tool; [] explicitly completes zero-tool registration. */
  setTools(tools: readonly FrontendVoiceTool[]): void;
  // Report authoritative initial-data completion; repeated equal values have no effect.
  setDataReady(ready: boolean): void;
  dispose(): void;
}

export interface FrontendPreparation {
  /** Replace preparation; optionally await initial native focus publication before voice setup. */
  createScope(context?: FrontendVoiceContextPolicy): FrontendPreparationScope;
  dispose(): void;
}

declare global {
  interface Window {
    dispatchGoModeVoiceTool?: (request: NativeToolInvocation) => void;
    cancelGoModeVoiceTool?: (request: NativeInvocation) => void;
    gomodeFrontendToolResult?: { postMessage(message: string): void };
    goModeFrontendDocument?: Promise<string>;
    gomodeFrontendReadiness?: AuthBridge;
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
  sessionID?: string;
}

interface NativeToolInvocation extends NativeInvocation {
  name: string;
  args: Record<string, unknown>;
}

type ActiveScope = FrontendPreparationScope & {
  snapshot(): FrontendVoiceToolsSnapshot;
  binding(): string;
  preparedNativeSessionID(): string | null;
  receiveVoiceSession(sessionID: string | null): void;
};
interface FrontendAuthentication {
  request: string;
  message: Record<string, unknown>;
  grantsAccess: boolean;
  document: string | null;
  grant: { token: string; voiceSessionCapable: boolean } | null;
}

interface ToolRegistration {
  id: string;
  tools: Map<string, FrontendVoiceTool> | null;
  lifetime: AbortController;
  pending: Map<string, { controller: AbortController; sessionID: string | null }>;
}

interface NativePreparation {
  sessionID: string;
  lifetime: AbortController;
  state: "pending" | "settled";
}

function createRegistration(tools: Map<string, FrontendVoiceTool> | null): ToolRegistration {
  return { id: String(++nextRegistration), tools, lifetime: new AbortController(), pending: new Map() };
}

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
      const message = { ...auth };
      const authentication: FrontendAuthentication = {
        request: String(++nextPreparationRequest),
        message,
        grantsAccess:
          (typeof message.bearerToken === "string" && message.bearerToken.length > 0) ||
          (message.authChanged === true && message.nativeAccess === true),
        document: null,
        grant: null,
      };
      let disposed = false;
      let scope: ActiveScope | null = null;
      const publish = () =>
        untrack(() => {
          if (disposed || authentication.grant === null) return;
          const ready = scope?.ready() === true;
          readinessBridge?.postMessage(
            JSON.stringify({
              document: authentication.document,
              token: authentication.grant.token,
              scope: scope?.binding() ?? "",
              ready,
              tools: ready ? scope?.snapshot().declarations : [],
              ...(authentication.grant.voiceSessionCapable
                ? { voiceSessionVersion: 1, voiceSessionPrepared: scope?.preparedNativeSessionID() ?? null }
                : {}),
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
          reply.frontendDocument !== authentication.document ||
          reply.frontendRequest !== authentication.request ||
          typeof reply.frontendToken !== "string"
        )
          return;
        authentication.grant = { token: reply.frontendToken, voiceSessionCapable: reply.voiceSessionVersion === 1 };
        publish();
      };
      const receiveVoiceSession = (event: { data: string }) => {
        let value: unknown;
        try {
          value = JSON.parse(event.data);
        } catch (error) {
          console.error("Invalid Go Mode voice lifetime", error);
          return;
        }
        if (
          disposed ||
          !authentication.grant?.voiceSessionCapable ||
          typeof value !== "object" ||
          value === null ||
          !scope
        )
          return;
        const reply = value as Record<string, unknown>;
        if (
          reply.voiceSessionVersion !== 1 ||
          reply.document !== authentication.document ||
          reply.token !== authentication.grant.token ||
          reply.scope !== scope.binding() ||
          !scope.ready()
        )
          return;
        scope.receiveVoiceSession(validSessionID(reply.sessionID) ? reply.sessionID : null);
      };
      const owner: FrontendPreparation = {
        createScope(context) {
          scope?.dispose();
          const [ready, setReady] = createSignal(false);
          const [nativePreparation, setNativePreparation] = createSignal<NativePreparation | null>(null);
          const voiceContext = disposed || context === undefined ? null : createFrontendVoiceContext(context, ready);
          let registration = createRegistration(null);
          let dataReady = false;
          let released = disposed;
          const invalidate = (tools: Map<string, FrontendVoiceTool> | null) => {
            registration.lifetime.abort();
            registration.pending.forEach(({ controller }) => controller.abort());
            registration.pending.clear();
            current.receiveVoiceSession(null);
            voiceContext?.reset();
            registration = createRegistration(tools);
          };
          const owns = (bound: ToolRegistration, request: NativeInvocation) =>
            untrack(
              () =>
                activeScope === current &&
                registration === bound &&
                authentication.grant !== null &&
                (request.sessionID === undefined || validSessionID(request.sessionID)) &&
                ready() &&
                (!authentication.grant.voiceSessionCapable ||
                  request.sessionID === (nativePreparation()?.sessionID ?? null)) &&
                request.document === authentication.document &&
                request.token === authentication.grant.token &&
                request.scope === bound.id,
            );

          const captureExecution = () => {
            const bound = registration;
            const native = untrack(nativePreparation);
            return (name: string, args: Record<string, unknown>, sessionID: string | null, signal?: AbortSignal) => {
              const isCurrent = () =>
                activeScope === current &&
                ready() &&
                bound === registration &&
                native === untrack(nativePreparation) &&
                (!authentication.grant?.voiceSessionCapable || sessionID === native?.sessionID);
              const tool = isCurrent() ? bound.tools?.get(name) : null;
              return tool
                ? runTool(tool, args, sessionID, [bound.lifetime.signal, native?.lifetime.signal, signal], isCurrent)
                : Promise.resolve(unavailableTool(name));
            };
          };
          const dispatch = (request: NativeToolInvocation) => {
            const bound = registration;
            if (!owns(bound, request) || bound.pending.has(request.invocation)) return;
            const controller = new AbortController();
            const invocation = { controller, sessionID: request.sessionID ?? null };
            bound.pending.set(request.invocation, invocation);
            // Only omitted legacy native wire IDs may reach the nullable internal runner.
            void captureExecution()(request.name, request.args, request.sessionID ?? null, controller.signal).then(
              (result) => {
                if (bound.pending.get(request.invocation) !== invocation) return;
                bound.pending.delete(request.invocation);
                if (owns(bound, request))
                  resultBridge?.postMessage(JSON.stringify({ ...request, name: undefined, args: undefined, result }));
              },
            );
          };
          const cancel = (request: NativeInvocation) => {
            const bound = registration;
            if (!owns(bound, request)) return;
            const invocation = bound.pending.get(request.invocation);
            if (!invocation || (request.sessionID !== undefined && invocation.sessionID !== request.sessionID)) return;
            invocation.controller.abort();
            bound.pending.delete(request.invocation);
          };
          const update = () => {
            setReady(!released && authentication.grantsAccess && registration.tools !== null && dataReady);
            publish();
          };
          const current: ActiveScope = {
            ready,
            nativeVoiceSessionID: () => nativePreparation()?.sessionID ?? null,
            preparedNativeSessionID: () => {
              const native = untrack(nativePreparation);
              return native?.state === "settled" ? native.sessionID : null;
            },
            contextPublication: voiceContext?.contextPublication ?? (() => ({ failure: null, pending: false })),
            async retryContext() {
              if (!released && untrack(ready)) await voiceContext?.retryContext();
            },
            async prepareSession(sessionID, signal) {
              if (released || !untrack(ready) || signal.aborted || !validSessionID(sessionID))
                throw new DOMException("Frontend preparation retired.", "AbortError");
              await voiceContext?.prepareSession(sessionID, signal);
            },
            async publishContext(sessionID, signal) {
              if (released || !untrack(ready) || signal.aborted || !validSessionID(sessionID))
                throw new DOMException("Frontend preparation retired.", "AbortError");
              await voiceContext?.publishContext(sessionID, signal);
            },
            retireSession(sessionID) {
              voiceContext?.retireSession(sessionID);
            },
            receiveVoiceSession(sessionID) {
              const previous = untrack(nativePreparation);
              if ((previous?.sessionID ?? null) === sessionID) return;
              previous?.lifetime.abort();
              if (previous) voiceContext?.retireSession(previous.sessionID);
              const native: NativePreparation | null =
                sessionID === null
                  ? null
                  : {
                      sessionID,
                      lifetime: new AbortController(),
                      state: "pending",
                    };
              setNativePreparation(native);
              for (const [id, invocation] of registration.pending) {
                if (invocation.sessionID !== sessionID) {
                  invocation.controller.abort();
                  registration.pending.delete(id);
                }
              }
              if (native === null) return;
              void (async () => {
                try {
                  await current.prepareSession(native.sessionID, native.lifetime.signal);
                } catch {
                  // The shared context owner reports advisory outcomes once.
                }
                if (
                  native !== untrack(nativePreparation) ||
                  native.lifetime.signal.aborted ||
                  released ||
                  !untrack(ready)
                )
                  return;
                native.state = "settled";
                publish();
              })();
            },
            binding: () => registration.id,
            setTools(values) {
              if (released) return;
              const next = new Map<string, FrontendVoiceTool>();
              for (const tool of values) {
                const name = tool.declaration.name;
                if (name === "hang_up" || name === "mute" || next.has(name))
                  throw new Error(`Voice tool "${name}" is already reserved.`);
                next.set(name, tool);
              }
              invalidate(next);
              update();
            },
            setDataReady(value) {
              if (released || dataReady === value) return;
              if (dataReady && !value) invalidate(registration.tools);
              dataReady = value;
              update();
            },
            snapshot() {
              const captured = registration.tools;
              const execute = captureExecution();
              return {
                declarations:
                  ready() && captured !== null ? Array.from(captured.values(), (tool) => tool.declaration) : [],
                execute(name, args, sessionID, signal) {
                  if (!validSessionID(sessionID))
                    return Promise.resolve({ error: "Voice conversation is unavailable." });
                  return execute(name, args, sessionID, signal);
                },
              };
            },
            dispose() {
              if (released) return;
              released = true;
              invalidate(null);
              voiceContext?.dispose();
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
          authentication.grant = null;
          if (bridge?.onmessage === receive) bridge.onmessage = null;
          if (readinessBridge?.onmessage === receiveVoiceSession) readinessBridge.onmessage = null;
          if (preparation === owner) preparation = null;
        },
      };
      preparation = owner;
      if (bridge && documentBinding !== undefined) {
        if (authentication.grantsAccess) {
          bridge.onmessage = receive;
          if (readinessBridge) readinessBridge.onmessage = receiveVoiceSession;
        }
        void documentBinding.then((boundDocument) => {
          // Revocations remain ordered; retired grants can never post late authentication.
          if (disposed && authentication.grantsAccess) return;
          authentication.document = boundDocument;
          bridge.postMessage(
            JSON.stringify({
              ...authentication.message,
              frontendDocument: authentication.document,
              frontendRequest: authentication.request,
            }),
          );
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
  return activeScope?.snapshot() ?? { declarations: [], execute: (name) => Promise.resolve(unavailableTool(name)) };
}

export function executeFrontendVoiceTool(
  name: string,
  args: Record<string, unknown>,
  sessionID: string,
): Promise<Record<string, unknown>> {
  return captureFrontendVoiceTools().execute(name, args, sessionID);
}

// The runner owns bounded completion; host actions cooperate by checking context after awaits.
function runTool(
  tool: FrontendVoiceTool,
  args: Record<string, unknown>,
  sessionID: string | null,
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
      void Promise.resolve(tool.execute(args, { sessionID, signal: controller.signal, isCurrent }))
        .then((value) => {
          if (!isCurrent()) {
            cancel();
            return;
          }
          // Serialization validates the same JSON-object contract in browsers and Android.
          const encoded = JSON.stringify(value);
          const result: unknown = JSON.parse(encoded);
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

function validSessionID(value: unknown): value is string {
  return typeof value === "string" && value.trim() !== "" && value.length <= 256;
}
