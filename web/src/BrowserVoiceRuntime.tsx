// Configures and owns the lazily loaded browser voice runtime and localized overlay.

import { createEffect, createSignal, onCleanup, Show, untrack } from "solid-js";
import { configureMcpClient, mcpClient } from "./McpClient";
import { configureVoiceGateway, voiceSession } from "./VoiceSession";
import VoiceOverlay, { defaultVoiceOverlayMessages } from "./VoiceOverlay";
import { notifications } from "./notifications";
import { monitorServiceNotifications } from "./ServiceNotifications";
import type { BrowserVoiceRuntimeProps } from "./BrowserVoiceShell";

export interface BrowserVoiceRuntime {
  session: typeof voiceSession;
  mcp: typeof mcpClient;
}

let releaseRuntime: (() => void) | null = null;

export default function BrowserVoiceRuntime(props: BrowserVoiceRuntimeProps) {
  const [active, setActive] = createSignal(false);
  createEffect(() => {
    const endpoints = props.endpoints;
    const clientName = props.clientName;
    const authorize = props.authorize;
    const headers = new Headers(props.gatewayHeaders);
    untrack(() => {
      // Route transitions may construct a replacement before disposing its predecessor.
      releaseRuntime?.();
      let released = false;
      const release = () => {
        if (released) return;
        released = true;
        setActive(false);
        voiceSession.disconnect();
        notifications.setVoiceActive(false);
        if (releaseRuntime === release) releaseRuntime = null;
      };
      releaseRuntime = release;
      onCleanup(release);
      configureMcpClient(endpoints.mcpEndpoint, clientName);
      const endpoint = endpoints.tokenEndpoint;
      configureVoiceGateway(
        endpoints.gatewayURL,
        endpoint !== null && authorize !== undefined ? () => authorize(endpoint) : null,
        undefined,
        () => headers,
      );
      setActive(true);
    });
  });
  createEffect(() => {
    if (!active() || !voiceSession.state.connected) return;
    const monitor = monitorServiceNotifications((text) => voiceSession.injectText(text), props.onError);
    onCleanup(() => monitor.close());
  });
  return (
    <Show when={active()}>
      {props.children?.({ session: voiceSession, mcp: mcpClient })}
      <VoiceOverlay
        canStart={() => active() && props.canStart()}
        messages={() => props.messages?.(defaultVoiceOverlayMessages) ?? defaultVoiceOverlayMessages}
      />
    </Show>
  );
}
