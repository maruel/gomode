// Owns retryable browser voice setup and shared context recovery without eagerly loading the runtime.

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
          <Runtime {...configuration} messages={props.messages}>
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
