// Owns lazy browser voice setup preparation, scoped context watchers, and the localized overlay.

import { createEffect, createSignal, onCleanup, Show, untrack } from "solid-js";
import { configureMcpClient, mcpClient } from "./McpClient";
import { configureVoiceGateway, voiceSession } from "./VoiceSession";
import VoiceOverlay from "./VoiceOverlay";
import { defaultVoiceMessages } from "./VoiceMessages";
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
  // Hosts subscribe through the runtime so their resource streams use the same
  // conversation as voice setup and tools. Reconnect retains that identity.
  const conversationMcp: typeof mcpClient = {
    serverInstructions: (options) =>
      mcpClient.serverInstructions({ ...options, sessionID: voiceSession.conversationID }),
    resourceCapabilities: (signal, options) =>
      mcpClient.resourceCapabilities(signal, { ...options, sessionID: voiceSession.conversationID }),
    listTools: (options) => mcpClient.listTools({ ...options, sessionID: voiceSession.conversationID }),
    readAdvertisedTextResource: (uri, signal, options) =>
      mcpClient.readAdvertisedTextResource(uri, signal, { ...options, sessionID: voiceSession.conversationID }),
    callTool: (name, args, options) =>
      mcpClient.callTool(name, args, { ...options, sessionID: voiceSession.conversationID }),
    subscribeResources: (options, callback) =>
      mcpClient.subscribeResources({ ...options, sessionID: voiceSession.conversationID }, callback),
  };
  createEffect(() => {
    const endpoints = props.endpoints;
    const clientName = props.clientName;
    const authorize = props.authorize;
    const preparation = props.preparation;
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
        preparation,
      );
      setActive(true);
    });
  });
  createEffect(() => {
    if (!active() || !voiceSession.state.connected) return;
    const monitor = monitorServiceNotifications(
      (text, generate) => (generate ? voiceSession.injectText(text, true) : voiceSession.setServiceContext(text)),
      props.onError,
      { sessionID: voiceSession.conversationID },
    );
    onCleanup(() => monitor.close());
  });
  return (
    <Show when={active()}>
      {props.children?.({ session: voiceSession, mcp: conversationMcp })}
      <VoiceOverlay
        canStart={() => active() && props.canStart()}
        messages={() => props.messages?.() ?? defaultVoiceMessages}
      />
    </Show>
  );
}
