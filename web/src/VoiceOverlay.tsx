// Voice overlay component: persistent bottom panel with mic button and voice controls.

import { createEffect, createSignal, For, Show, onCleanup, onMount, type Accessor, type JSX } from "solid-js";

import { voiceSession } from "./VoiceSession";
import type { VoiceState, TranscriptEntry } from "./VoiceSession";
import { notifications } from "./notifications";
import styles from "./VoiceOverlay.module.css";

type IconProps = JSX.SvgSVGAttributes<SVGSVGElement>;

// Material Symbols path data, Google LLC, Apache License 2.0; see NOTICE.
function MicIcon(props: IconProps) {
  return (
    <svg {...props} viewBox="0 -960 960 960" fill="currentColor" aria-hidden="true">
      <path d="M408-453.92q-29-30.91-29-75.08v-251q0-41.67 29.44-70.83Q437.88-880 479.94-880t71.56 29.17Q581-821.67 581-780v251q0 44.17-29 75.08Q523-423 480-423t-72-30.92ZM480-651Zm-30 531v-136q-106-11-178-89t-72-184h60q0 91 64.29 153t155.5 62q91.21 0 155.71-62Q700-438 700-529h60q0 106-72 184t-178 89v136h-60Zm59.5-376.5Q521-510 521-529v-251q0-17-11.79-28.5T480-820q-17.42 0-29.21 11.5T439-780v251q0 19 11.5 32.5T480-483q18 0 29.5-13.5Z" />
    </svg>
  );
}

function MicOffIcon(props: IconProps) {
  return (
    <svg {...props} viewBox="0 -960 960 960" fill="currentColor" aria-hidden="true">
      <path d="m686-361-43-43q21-26 31-58.5t10-66.5h60q0 46-15 89t-43 79ZM461-586Zm97 97-53-52v-238q0-17-12-29t-29-12q-17 0-29 12t-12 29v155l-60-60v-95q0-42 29.5-71.5T464-880q42 0 71.5 29.5T565-779v250q0 8-1.5 20t-5.5 20ZM434-120v-136q-106-11-178-89t-72-184h60q0 91 64.5 153T464-314q38 0 73-12.5t64-34.5l43 43q-31 26-69 41.5T494-256v136h-60Zm397 65L36-850l38-38L869-93l-38 38Z" />
    </svg>
  );
}

function CallEndIcon(props: IconProps) {
  return (
    <svg {...props} viewBox="0 -960 960 960" fill="currentColor" aria-hidden="true">
      <path d="m136-306-94-94q-9-9-8.5-21t8.5-22q82-96 197-146.5T480-640q126 0 241 50.5T918-443q8 10 8.5 22t-8.5 21l-94 94q-8 8-23 9t-24-6l-114-85q-6-4.5-9-10.5t-3-13.5v-139q-42-16-85.5-22.5T480-580q-42 0-85.5 6.5T309-551v139q0 7.5-3 13.5t-9 10.5l-114.19 85.28q-11.81 8.72-24.5 7.8-12.68-.93-22.31-11.08Zm108-220q-38 19-73 45.5T104-424l57 59 83-61v-100Zm467-4v98l87 66 58-58q-32-33-68.5-58.5T711-530Zm-467 4Zm467-4Z" />
    </svg>
  );
}

function CloseIcon(props: IconProps) {
  return (
    <svg {...props} viewBox="0 -960 960 960" fill="currentColor" aria-hidden="true">
      <path d="m249-207-42-42 231-231-231-231 42-42 231 231 231-231 42 42-231 231 231 231-42 42-231-231-231 231Z" />
    </svg>
  );
}

/** Bar transition durations (ms): center reacts fastest, outer bars lag. */
const BAR_DURATIONS = [80, 40, 120];
const BAR_MIN_H = 3;
const BAR_MAX_H = 20;

/** Host-provided text for the browser voice controls. */
export interface VoiceOverlayMessages {
  assistant: string;
  cancel: string;
  cancelConnection: string;
  clearTranscript: string;
  connect: string;
  connectionFailed: string;
  endSession: string;
  listening: string;
  microphone: string;
  mute: string;
  muted: string;
  reconnecting: string;
  retry: string;
  signaling: string;
  speaker: string;
  speaking: string;
  transcript: string;
  transcriptPlaceholder: string;
  unmute: string;
  voiceAssistant: string;
  waitingForServer: string;
  settingUpWebRTC: string;
  you: string;
}

export const defaultVoiceOverlayMessages: VoiceOverlayMessages = {
  assistant: "Assistant:",
  cancel: "Cancel",
  cancelConnection: "Cancel connection",
  clearTranscript: "Clear transcript",
  connect: "Connect voice assistant",
  connectionFailed: "Voice connection failed",
  endSession: "End voice session",
  listening: "Listening…",
  microphone: "Microphone",
  mute: "Mute",
  muted: "Muted",
  reconnecting: "Reconnecting…",
  retry: "Retry",
  signaling: "Signaling…",
  speaker: "Speaker",
  speaking: "Speaking…",
  transcript: "Transcript",
  transcriptPlaceholder: "Transcript will appear here…",
  unmute: "Unmute",
  voiceAssistant: "Voice assistant",
  waitingForServer: "Waiting for server…",
  settingUpWebRTC: "Setting up WebRTC…",
  you: "You:",
};

function localizedStatus(status: string, phase: VoiceState["connectPhase"], messages: VoiceOverlayMessages, hasCustomMessages: boolean): string {
  if (!hasCustomMessages) return status;
  switch (phase) {
    case "setup":
      return messages.settingUpWebRTC;
    case "waiting":
      return messages.waitingForServer;
    case "signaling":
      return messages.signaling;
    case "reconnecting":
      return messages.reconnecting;
    default:
      return messages.reconnecting;
  }
}

function safeErrorDetail(error: string): string {
  return error
    .replace(/\bBearer\s+\S+/gi, "Bearer [redacted]")
    .replace(/([?&](?:access_token|token|api_key|password)=)[^&#\s]+/gi, "$1[redacted]")
    .replace(/[\u0000-\u001f\u007f]+/g, " ")
    .trim()
    .slice(0, 500);
}

export default function VoiceOverlay(props: { messages?: VoiceOverlayMessages | Accessor<VoiceOverlayMessages> }) {
  const session = voiceSession;
  const messages = () => {
    const value = props.messages;
    return (typeof value === "function" ? value() : value) ?? defaultVoiceOverlayMessages;
  };

  let panelRef: HTMLDivElement | undefined; // eslint-disable-line no-unassigned-vars -- assigned by SolidJS ref
  const [spacerHeight, setSpacerHeight] = createSignal(0);
  onMount(() => {
    const observer = new ResizeObserver(([entry]) => {
      if (!entry) return;
      setSpacerHeight(entry.borderBoxSize[0]?.blockSize ?? entry.contentRect.height);
    });
    if (panelRef) observer.observe(panelRef);
    onCleanup(() => observer.disconnect());
  });

  // Suppress browser notifications while voice mode is active.
  createEffect(() =>
    notifications.setVoiceActive(
      session.state.connected ||
        session.state.connectStatus !== null ||
        session.state.listening ||
        session.state.speaking,
    ),
  );
  onCleanup(() => notifications.setVoiceActive(false));

  // No onCleanup disconnect — the singleton voice session survives component remounts.
  // Only explicit user action or page unload (beforeunload handler) disconnects.

  // -----------------------------------------------------------------------
  // Panel state
  // -----------------------------------------------------------------------

  const isActive = () =>
    session.state.connected || session.state.connectStatus !== null || session.state.error !== null;

  // -----------------------------------------------------------------------
  // Event handlers
  // -----------------------------------------------------------------------

  const handleMicClick = async () => {
    if (session.state.connected || session.state.connectStatus !== null) {
      session.disconnect();
    } else {
      await session.enumerateDevices();
      void session.connect();
    }
  };

  onMount(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (
        event.defaultPrevented ||
        event.repeat ||
        event.ctrlKey ||
        event.metaKey ||
        event.altKey ||
        event.key !== "F4" ||
        document.querySelector("dialog[open]")
      )
        return;

      event.preventDefault();
      void handleMicClick();
    };

    document.addEventListener("keydown", onKeyDown);
    onCleanup(() => document.removeEventListener("keydown", onKeyDown));
  });

  // -----------------------------------------------------------------------
  // Render
  // -----------------------------------------------------------------------

  return (
    <>
      <div class={styles.spacer} style={{ "--spacer-height": `${spacerHeight()}px` }} aria-hidden="true" />
      <div class={styles.panel} ref={panelRef} role="region" aria-label={messages().voiceAssistant} data-testid="voice-overlay">
        <div class={styles.panelInner}>
          {/* Idle state: mic button right-aligned */}
          <Show when={!isActive()}>
            <div class={styles.rowEnd}>
              <button
                type="button"
                class={styles.micButton}
                onClick={() => handleMicClick()}
                title={messages().connect}
                aria-label={messages().connect}
              >
                <MicIcon width="1.1em" height="1.1em" />
              </button>
            </div>
          </Show>

          <Show when={session.state.error !== null && session.state.error} keyed>
            {(err) => <ErrorPanel error={err} messages={messages} hasCustomMessages={props.messages !== undefined} onRetry={() => handleMicClick()} />}
          </Show>
          <Show
            when={session.state.error === null && session.state.connectStatus !== null && session.state.connectStatus}
            keyed
          >
            {(status) => <ConnectingPanel status={status} phase={session.state.connectPhase} messages={messages} hasCustomMessages={props.messages !== undefined} onDisconnect={() => session.disconnect()} />}
          </Show>
          <Show
            when={
              session.state.error === null &&
              session.state.connectStatus === null &&
              (session.state.connected || session.state.listening || session.state.speaking)
            }
          >
            <ActivePanel
              state={session.state}
              messages={messages}
              onDisconnect={() => session.disconnect()}
              onToggleMute={() => session.toggleMute()}
              onSelectInput={(id) => {
                void session.selectInputDevice(id);
              }}
              onSelectOutput={(id) => {
                session.selectOutputDevice(id);
              }}
              onClearTranscript={() => session.clearTranscript()}
            />
          </Show>
        </div>
      </div>
    </>
  );
}

// Sub-panels

function ConnectingPanel(props: { status: string; phase: VoiceState["connectPhase"]; messages: Accessor<VoiceOverlayMessages>; hasCustomMessages: boolean; onDisconnect: () => void }) {
  return (
    <div class={`${styles.row} ${styles.statusConnecting}`}>
      <MicIcon width="1.1em" height="1.1em" />
      <span class={styles.statusText}>{localizedStatus(props.status, props.phase, props.messages(), props.hasCustomMessages)}</span>
      <button
        type="button"
        class={styles.iconButton}
        onClick={() => props.onDisconnect()}
        title={props.messages().cancel}
        aria-label={props.messages().cancelConnection}
      >
        <CloseIcon width="1.1em" height="1.1em" />
      </button>
    </div>
  );
}

function ErrorPanel(props: { error: string; messages: Accessor<VoiceOverlayMessages>; hasCustomMessages: boolean; onRetry: () => void }) {
  return (
    <div class={styles.row}>
      <MicIcon width="1.1em" height="1.1em" class={styles.micIconError} />
      <span class={styles.statusError}>
        {props.hasCustomMessages ? props.messages().connectionFailed : props.error}
        {props.hasCustomMessages && <small class={styles.errorDetail}>{safeErrorDetail(props.error)}</small>}
      </span>
      <button
        type="button"
        class={`${styles.actionButton} ${styles.actionButtonPrimary}`}
        onClick={() => props.onRetry()}
      >
        {props.messages().retry}
      </button>
    </div>
  );
}

function ActivePanel(props: {
  state: VoiceState;
  messages: Accessor<VoiceOverlayMessages>;
  onDisconnect: () => void;
  onToggleMute: () => void;
  onSelectInput: (id: string) => void;
  onSelectOutput: (id: string) => void;
  onClearTranscript: () => void;
}) {
  const statusText = () => {
    if (props.state.activeTool !== null) return props.state.activeTool;
    if (props.state.muted && !props.state.speaking) return props.messages().muted;
    if (props.state.speaking) return props.messages().speaking;
    return props.messages().listening;
  };

  const statusClass = () =>
    props.state.activeTool !== null ? `${styles.statusText} ${styles.statusTool}` : styles.statusText;

  return (
    <>
      <div class={styles.rowSpaced}>
        <MicLevelBars micLevel={props.state.micLevel} />
        <span class={statusClass()}>{statusText()}</span>
        <button
          type="button"
          class={`${styles.iconButton}${props.state.muted ? " " + styles.iconButtonMuted : ""}`}
          onClick={() => props.onToggleMute()}
          title={props.state.muted ? props.messages().unmute : props.messages().mute}
          aria-label={props.state.muted ? props.messages().unmute : props.messages().mute}
        >
          <Show when={props.state.muted} fallback={<MicIcon width="1.1em" height="1.1em" />}>
            <MicOffIcon width="1.1em" height="1.1em" />
          </Show>
        </button>
        <button
          type="button"
          class={`${styles.iconButton} ${styles.iconButtonEnd}`}
          onClick={() => props.onDisconnect()}
          title={props.messages().endSession}
          aria-label={props.messages().endSession}
        >
          <CallEndIcon width="1.1em" height="1.1em" />
        </button>
      </div>
      {(props.state.audioInputs.length > 1 || props.state.audioOutputs.length > 1) && (
        <AudioDevicePicker
          inputs={props.state.audioInputs}
          outputs={props.state.audioOutputs}
          selectedInputId={props.state.selectedInputId}
          selectedOutputId={props.state.selectedOutputId}
          messages={props.messages}
          onSelectInput={props.onSelectInput}
          onSelectOutput={props.onSelectOutput}
        />
      )}
      <TranscriptLog transcript={props.state.transcript} messages={props.messages} onClear={() => props.onClearTranscript()} />
    </>
  );
}

// Audio device picker

function AudioDevicePicker(props: {
  inputs: Array<{ deviceId: string; label: string }>;
  outputs: Array<{ deviceId: string; label: string }>;
  selectedInputId: string;
  selectedOutputId: string;
  messages: Accessor<VoiceOverlayMessages>;
  onSelectInput: (id: string) => void;
  onSelectOutput: (id: string) => void;
}) {
  return (
    <div class={styles.deviceRow}>
      {props.inputs.length > 1 && (
        <select
          class={styles.deviceSelect}
          value={props.selectedInputId}
          onChange={(e) => props.onSelectInput(e.currentTarget.value)}
          aria-label={props.messages().microphone}
        >
          <For each={props.inputs}>{(d) => <option value={d.deviceId}>🎤 {d.label}</option>}</For>
        </select>
      )}
      {props.outputs.length > 1 && (
        <select
          class={styles.deviceSelect}
          value={props.selectedOutputId}
          onChange={(e) => props.onSelectOutput(e.currentTarget.value)}
          aria-label={props.messages().speaker}
        >
          <For each={props.outputs}>{(d) => <option value={d.deviceId}>🔊 {d.label}</option>}</For>
        </select>
      )}
    </div>
  );
}

// Mic level bars

function MicLevelBars(barProps: { micLevel: number }) {
  return (
    <div class={styles.micBars} aria-hidden="true">
      <For each={BAR_DURATIONS}>
        {(duration) => {
          const height = () => BAR_MIN_H + barProps.micLevel * (BAR_MAX_H - BAR_MIN_H);
          return (
            <div
              class={styles.micBar}
              style={{ "--mic-bar-height": `${height()}px`, "--mic-bar-duration": `${duration}ms` }}
            />
          );
        }}
      </For>
    </div>
  );
}

// Transcript log

function TranscriptLog(props: { transcript: TranscriptEntry[]; messages: Accessor<VoiceOverlayMessages>; onClear: () => void }) {
  let listRef: HTMLDivElement | undefined;

  // Auto-scroll to bottom when transcript changes.
  createEffect(() => {
    const len = props.transcript.length;
    const last = props.transcript[len - 1]?.text;
    void len;
    void last;
    if (listRef) {
      listRef.scrollTop = listRef.scrollHeight;
    }
  });

  return (
    <>
      <Show when={props.transcript.length > 0}>
        <div class={styles.rowLabel}>
          <span class={styles.transcriptLabel}>{props.messages().transcript}</span>
          <button
            type="button"
            class={styles.clearButton}
            onClick={() => props.onClear()}
            title={props.messages().clearTranscript}
            aria-label={props.messages().clearTranscript}
          >
            ×
          </button>
        </div>
        <div
          class={styles.transcriptList}
          ref={(el) => {
            listRef = el;
          }}
        >
          <For each={props.transcript}>
            {(entry) => (
              <div class={styles.transcriptEntry}>
                <span class={entry.speaker === "user" ? styles.transcriptLabelUser : styles.transcriptLabelAssistant}>
                  {entry.speaker === "user" ? props.messages().you : props.messages().assistant}
                </span>
                {entry.text}
              </div>
            )}
          </For>
        </div>
      </Show>
      <Show when={props.transcript.length === 0}>
        <p class={styles.transcriptPlaceholder}>{props.messages().transcriptPlaceholder}</p>
      </Show>
    </>
  );
}
