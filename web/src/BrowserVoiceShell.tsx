// Renders shared context recovery for hosted voice and lazily mounts the browser runtime.

import { createEffect, createSignal, onCleanup, Show, type Accessor, type Component, type JSX } from "solid-js";
import { useHostMode } from "./HostMode";
import { discoverBrowserVoice, sameOriginVoiceEndpoint, type BrowserVoiceEndpoints } from "./BrowserVoiceManifest";
import type { FrontendPreparationScope } from "./FrontendVoiceTools";
import { defaultVoiceMessages, type VoiceMessages } from "./VoiceMessages";
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

export default function BrowserVoiceShell(props: BrowserVoiceShellProps) {
  const host = useHostMode();
  const [loaded, setLoaded] = createSignal<LoadedRuntime | null>(null);
  const [failed, setFailed] = createSignal(false);
  createEffect(() => {
    setLoaded(null);
    setFailed(false);
    const scope = props.preparation;
    if (!host.browserVoiceEnabled() || !scope.ready()) return;
    const expected = { service: props.service, toolGroup: props.toolGroup };
    const configuration = {
      canStart: () => host.browserVoiceEnabled() && scope.ready(),
      clientName: props.clientName,
      authorize: props.authorize,
      mcpEndpoint: props.mcpEndpoint,
      gatewayHeaders: new Headers(props.gatewayHeaders),
      preparation: scope,
      onError: props.onError,
    };
    const controller = new AbortController();
    let active = true;
    const onError = props.onError;
    async function prepare() {
      const endpoints = await discoverBrowserVoice(expected, controller.signal);
      if (!active || !configuration.canStart() || endpoints === null) return;
      if (endpoints.tokenEndpoint !== null && configuration.authorize === undefined) {
        throw new Error("Host has no voice authorization policy");
      }
      const { mcpEndpoint: decorateEndpoint, ...runtimeConfiguration } = configuration;
      const mcpEndpoint = sameOriginVoiceEndpoint(
        decorateEndpoint?.(endpoints.mcpEndpoint) ?? endpoints.mcpEndpoint,
        window.location.origin,
      );
      const module = await import("./BrowserVoiceRuntime");
      if (active && configuration.canStart())
        setLoaded({
          component: module.default,
          configuration: { ...runtimeConfiguration, endpoints: { ...endpoints, mcpEndpoint } },
        });
    }
    void prepare().catch((error: unknown) => {
      if (!active) return;
      onError(error);
      setFailed(true);
    });
    onCleanup(() => {
      active = false;
      controller.abort();
    });
  });
  return (
    <>
      <Show when={props.preparation.contextPublication().failure}>
        <div role="status" class={styles.contextNotice}>
          <span>{(props.messages?.() ?? defaultVoiceMessages).contextUnavailable}</span>
          <button
            type="button"
            disabled={props.preparation.contextPublication().pending}
            onClick={() => void props.preparation.retryContext()}
          >
            {(props.messages?.() ?? defaultVoiceMessages).contextRetry}
          </button>
        </div>
      </Show>
      <Show when={loaded()} keyed>
        {({ component: Runtime, configuration }) => (
          <Runtime {...configuration} messages={props.messages}>
            {props.children}
          </Runtime>
        )}
      </Show>
      <Show when={failed()}>
        <p role="alert" class={props.errorClass}>
          {props.setupFailed}
        </p>
      </Show>
    </>
  );
}
