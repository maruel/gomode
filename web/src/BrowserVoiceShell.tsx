// Owns retryable browser voice setup and shared context recovery without eagerly loading the runtime.
//
// BrowserVoiceShell performs abortable manifest discovery only in browser mode and only when local
// preparation is ready. Hosts supply the expected service and MCP tool-group names, the client
// name, and the preparation scope. A missing discovery route or an HTML SPA fallback means
// unavailable voice; malformed JSON, unsupported discovery versions, and invalid endpoint contracts
// are setup errors. The browser supports discovery API version 1. Gateway origins require HTTPS,
// with HTTP localhost and canonical loopback addresses allowed for local development; MCP and token
// endpoints must be same-origin. External gateways require a same-origin token endpoint and an
// authorize adapter.
//
// authorize(endpoint) owns the host's token-exchange method, headers, response validation, and
// session policy. The optional mcpEndpoint decorator binds a validated endpoint to captured host
// authority, and its result is validated again. gatewayHeaders are captured for configuration and
// sent only to same-origin gateway signaling. Adapters capture the owning host session instead of
// looking up a replacement identity after an await.
//
// The runtime and overlay load after discovery, and ownership is rechecked after the import.
// Replacement, disposal, or a mode change aborts discovery and prevents late configuration. The
// active runtime owns voice-session cleanup and notification suppression, and a pending Connect
// action keeps its originating runtime and user intent. The render-prop child receives {session,
// mcp} for host-owned context and document subscriptions; the child cleans them up. Before setup
// reads instructions, tools, or items, the preparation scope's prepareSession publishes the host's
// selected workspace or item through the FrontendVoiceContextPolicy given to createScope. Recovery
// reuses it with the same session ID, ordinary publication failures are advisory, and abort or
// retirement cancels setup. Message catalogs in VoiceMessages contain only labels, so hosts load
// them with their language dictionaries independently of the lazy runtime.

import {
  batch,
  createEffect,
  createSignal,
  onCleanup,
  Show,
  untrack,
  type Accessor,
  type Component,
  type JSX,
} from "solid-js";
import { useHostMode } from "./HostMode";
import { loadBrowserVoiceRuntime } from "./BrowserVoiceSetup";
import { discoverBrowserVoice, sameOriginVoiceEndpoint, type BrowserVoiceEndpoints } from "./BrowserVoiceManifest";
import type { FrontendPreparationScope } from "./FrontendVoiceTools";
import { defaultVoiceMessages, type VoiceMessages } from "./VoiceMessages";
import VoiceIcon from "./VoiceIcon";
import styles from "./BrowserVoiceShell.module.css";
import type { ServiceAuthorization } from "../../sdk/voicegateway/ts/v2/types.gen";
import type { BrowserVoiceRuntime } from "./BrowserVoiceRuntime";
import type { McpResourceNotification } from "./McpClient";

export interface BrowserVoiceShellProps {
  service: string;
  toolGroup: string;
  clientName: string;
  preparation: FrontendPreparationScope;
  /** The host captures its session, HTTP method, and headers in this policy callback. */
  authorize?: (endpoint: string) => Promise<ServiceAuthorization>;
  /** Decorate the validated MCP endpoint with captured host session authority if needed. */
  mcpEndpoint?: (endpoint: string) => string;
  gatewayHeaders?: HeadersInit;
  /** Reuse a host-owned screen event feed instead of opening an MCP stream. */
  resourceEvents?: Accessor<readonly McpResourceNotification[] | null>;
  /** Read the host’s current localized voice catalog. */
  messages?: Accessor<VoiceMessages>;
  setupFailed: string;
  errorClass?: string;
  onError: (error: unknown) => void;
  children?: (runtime: BrowserVoiceRuntime) => JSX.Element;
}

export interface BrowserVoiceRuntimeProps {
  canStart: () => boolean;
  endpoints: BrowserVoiceEndpoints;
  clientName: string;
  authorize?: BrowserVoiceShellProps["authorize"];
  gatewayHeaders?: HeadersInit;
  resourceEvents?: BrowserVoiceShellProps["resourceEvents"];
  preparation: FrontendPreparationScope;
  messages?: BrowserVoiceShellProps["messages"];
  onError: BrowserVoiceShellProps["onError"];
  children?: BrowserVoiceShellProps["children"];
}

type LoadedRuntime = {
  component: Component<BrowserVoiceRuntimeProps>;
  configuration: Omit<BrowserVoiceRuntimeProps, "messages" | "children">;
};

interface SetupAttempt {
  controller: AbortController;
  retry(): void;
}

type SetupState =
  | { kind: "inactive" }
  | { kind: "loading" | "retrying" | "failed"; attempt: SetupAttempt }
  | { kind: "ready"; attempt: SetupAttempt; runtime: LoadedRuntime };

export default function BrowserVoiceShell(props: BrowserVoiceShellProps) {
  const host = useHostMode();
  const [setup, setSetup] = createSignal<SetupState>({ kind: "inactive" });
  const retire = () => {
    const previous = untrack(setup);
    if (previous.kind !== "inactive") previous.attempt.controller.abort();
    setSetup({ kind: "inactive" });
  };
  createEffect(() => {
    const scope = props.preparation;
    if (!host.browserVoiceEnabled() || !scope.ready()) return;
    const expected = { service: props.service, toolGroup: props.toolGroup };
    const configuration = {
      clientName: props.clientName,
      authorize: props.authorize,
      mcpEndpoint: props.mcpEndpoint,
      gatewayHeaders: new Headers(props.gatewayHeaders),
      preparation: scope,
      onError: props.onError,
    };
    function start(retrying: boolean) {
      const attempt: SetupAttempt = { controller: new AbortController(), retry: () => start(true) };
      const current = () => {
        const value = untrack(setup);
        return (
          value.kind !== "inactive" &&
          value.attempt === attempt &&
          !attempt.controller.signal.aborted &&
          host.browserVoiceEnabled() &&
          scope.ready()
        );
      };
      batch(() => {
        retire();
        setSetup({ kind: retrying ? "retrying" : "loading", attempt });
      });
      async function prepare() {
        const endpoints = await discoverBrowserVoice(expected, attempt.controller.signal);
        if (!current()) return;
        if (endpoints === null) {
          retire();
          return;
        }
        if (endpoints.tokenEndpoint !== null && configuration.authorize === undefined) {
          throw new Error("Host has no voice authorization policy");
        }
        const { mcpEndpoint: decorateEndpoint, ...runtimeConfiguration } = configuration;
        const mcpEndpoint = sameOriginVoiceEndpoint(
          decorateEndpoint?.(endpoints.mcpEndpoint) ?? endpoints.mcpEndpoint,
          window.location.origin,
        );
        const module = await loadBrowserVoiceRuntime();
        if (current())
          setSetup({
            kind: "ready",
            attempt,
            runtime: {
              component: module.default,
              configuration: { ...runtimeConfiguration, canStart: current, endpoints: { ...endpoints, mcpEndpoint } },
            },
          });
      }
      void prepare().catch((error: unknown) => {
        if (!current()) return;
        setSetup({ kind: "failed", attempt });
        configuration.onError(error);
      });
    }
    start(false);
    onCleanup(retire);
  });
  const retry = () => {
    const value = untrack(setup);
    if (value.kind === "failed") value.attempt.retry();
  };
  const runtime = () => {
    const value = setup();
    return value.kind === "ready" ? value.runtime : null;
  };
  return (
    <>
      <Show when={props.preparation.contextPublication().failure}>
        <div role="status" class={styles.notice}>
          <span>{(props.messages?.() ?? defaultVoiceMessages).contextUnavailable}</span>
          <button
            type="button"
            aria-label={(props.messages?.() ?? defaultVoiceMessages).retry}
            title={(props.messages?.() ?? defaultVoiceMessages).retry}
            disabled={props.preparation.contextPublication().pending}
            onClick={() => void props.preparation.retryContext()}
          >
            <VoiceIcon name="refresh" />
          </button>
        </div>
      </Show>
      <Show when={runtime()} keyed>
        {({ component: Runtime, configuration }) => (
          <Runtime {...configuration} resourceEvents={props.resourceEvents} messages={props.messages}>
            {props.children}
          </Runtime>
        )}
      </Show>
      <Show when={setup().kind === "failed" || setup().kind === "retrying"}>
        <div role="alert" class={`${styles.notice} ${props.errorClass ?? ""}`}>
          <span>{props.setupFailed}</span>
          <button
            type="button"
            aria-label={(props.messages?.() ?? defaultVoiceMessages).retry}
            title={(props.messages?.() ?? defaultVoiceMessages).retry}
            aria-disabled={setup().kind === "retrying"}
            onClick={retry}
          >
            <VoiceIcon name="refresh" />
          </button>
        </div>
      </Show>
    </>
  );
}
