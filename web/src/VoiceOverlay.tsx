// Voice overlay component: persistent bottom panel with mic button and voice controls.

import {
  createEffect,
  createSignal,
  createUniqueId,
  For,
  Show,
  onCleanup,
  onMount,
  type Accessor,
  type JSX,
} from "solid-js";

import { TurnStateThinking, TurnStateTranscribing } from "../../sdk/voicegateway/ts/v1/types.gen";
import { bluetoothAudioDeviceLabel, voiceSession } from "./VoiceSession";
import type { VoiceState, TranscriptEntry } from "./VoiceSession";
import { notifications } from "./notifications";
import VoiceSettings, { defaultVoiceSettingsMessages, type VoiceSettingsMessages } from "./VoiceSettings";
import VoiceIcon from "./VoiceIcon";
import controls from "./VoiceControls.module.css";
import styles from "./VoiceOverlay.module.css";

type AudioDeviceIcon = "bluetooth" | "headphones" | "mic" | "phoneInTalk" | "speaker" | "usb" | "volumeUp";

// Device labels are hints; unknown devices retain a generic input/output icon.
function audioDeviceIcon(label: string, direction: "input" | "output"): AudioDeviceIcon {
  if (bluetoothAudioDeviceLabel(label)) return "bluetooth";
  if (/\b(headsets?|headphones?|earbuds?)\b/i.test(label)) return "headphones";
  if (/\b(earpiece|handset)\b/i.test(label)) return "phoneInTalk";
  if (/\busb\b/i.test(label)) return "usb";
  if (/\bspeakers?\b/i.test(label)) return "speaker";
  return direction === "input" ? "mic" : "volumeUp";
}

/** Bar transition durations (ms): center reacts fastest, outer bars lag. */
const BAR_DURATIONS = [80, 40, 120];
const BAR_MIN_H = 3;
const BAR_MAX_H = 20;

/** Host-provided text for the browser voice controls. */
export interface VoiceOverlayMessages extends Partial<VoiceSettingsMessages> {
  voiceSettings?: string;
  closeVoiceSettings?: string;
  settingUpBrowserSpeech?: string;
  assistant: string;
  cancel: string;
  cancelConnection: string;
  clearTranscript: string;
  connect: string;
  connectionFailed: string;
  dismiss?: string;
  endSession: string;
  listening: string;
  microphone: string;
  mute: string;
  muted: string;
  reconnecting: string;
  retry: string;
  signaling: string;
  speaker: string;
  systemDefault?: string;
  systemAudioSettings?: string;
  unavailableDevice?: string;
  speaking: string;
  thinking: string;
  transcribing: string;
  transcript: string;
  transcriptPlaceholder: string;
  unmute: string;
  voiceAssistant: string;
  voiceLanguage?: string;
  waitingForServer: string;
  settingUpWebRTC: string;
  you: string;
}

export const defaultVoiceOverlayMessages: VoiceOverlayMessages = {
  ...defaultVoiceSettingsMessages,
  voiceSettings: "Voice settings",
  closeVoiceSettings: "Close voice settings",
  settingUpBrowserSpeech: "Setting up browser speech…",
  assistant: "Assistant:",
  cancel: "Cancel",
  cancelConnection: "Cancel connection",
  clearTranscript: "Clear transcript",
  connect: "Connect voice assistant",
  connectionFailed: "Voice connection failed",
  dismiss: "Dismiss",
  endSession: "End voice session",
  listening: "Listening…",
  microphone: "Microphone",
  mute: "Mute",
  muted: "Muted",
  reconnecting: "Reconnecting…",
  retry: "Retry",
  signaling: "Signaling…",
  speaker: "Speaker",
  systemDefault: "System default",
  systemAudioSettings: "Use your device’s sound settings to change the speaker.",
  unavailableDevice: "Device unavailable",
  speaking: "Speaking…",
  thinking: "Thinking…",
  transcribing: "Transcribing…",
  transcript: "Transcript",
  transcriptPlaceholder: "Transcript will appear here…",
  unmute: "Unmute",
  voiceAssistant: "Voice assistant",
  voiceLanguage: "Voice language (e.g. en-US)",
  waitingForServer: "Waiting for server…",
  settingUpWebRTC: "Setting up WebRTC…",
  you: "You:",
};

function localizedStatus(
  status: string,
  phase: VoiceState["connectPhase"],
  messages: VoiceOverlayMessages,
  hasCustomMessages: boolean,
): string {
  if (!hasCustomMessages) return status;
  switch (phase) {
    case "setup":
      return messages.settingUpWebRTC;
    case "waiting":
      return messages.waitingForServer;
    case "signaling":
      return messages.signaling;
    case "reconnecting":
    case null:
      return messages.reconnecting;
  }
}

function safeErrorDetail(error: string): string {
  return (
    error
      .replace(/\bBearer\s+\S+/gi, "Bearer [redacted]")
      .replace(/([?&](?:access_token|token|api_key|password)=)[^&#\s]+/gi, "$1[redacted]")
      // eslint-disable-next-line no-control-regex -- Control characters are deliberately removed from errors.
      .replace(/[\u0000-\u001f\u007f]+/g, " ")
      .trim()
      .slice(0, 500)
  );
}

export default function VoiceOverlay(props: { messages?: VoiceOverlayMessages | Accessor<VoiceOverlayMessages> }) {
  const session = voiceSession;
  const messages = () => {
    const value = props.messages;
    return (typeof value === "function" ? value() : value) ?? defaultVoiceOverlayMessages;
  };

  let panelRef: HTMLDivElement | undefined;
  let connectButton: HTMLButtonElement | undefined;
  let settingsTrigger: HTMLButtonElement | null = null;
  const [settingsOpen, setSettingsOpen] = createSignal(false);
  const [settingsError, setSettingsError] = createSignal<string | null>(null);
  const [languageError, setLanguageError] = createSignal<string | null>(null);
  const settingsId = createUniqueId();
  const closeSettings = () => {
    setSettingsOpen(false);
    if (settingsTrigger?.isConnected) settingsTrigger.focus();
    else panelRef?.querySelector<HTMLButtonElement>("button[aria-controls]")?.focus();
  };
  const SettingsButton = () => (
    <button
      type="button"
      class={controls.control}
      aria-label={messages().voiceSettings ?? "Voice settings"}
      aria-expanded={settingsOpen()}
      aria-controls={settingsId}
      onClick={(event) => {
        settingsTrigger = event.currentTarget;
        setSettingsOpen(!settingsOpen());
      }}
    >
      <VoiceIcon name="settings" />
    </button>
  );
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
      if (languageError() !== null) {
        setSettingsOpen(true);
        panelRef?.querySelector<HTMLInputElement>('input[type="text"]')?.focus();
        return;
      }
      // Browser audio must be unlocked synchronously while the click or key event
      // still carries user activation. Device enumeration yields before connect().
      session.prepareAudio();
      if (session.state.mode === "cloud") await session.enumerateDevices();
      void session.connect();
    }
  };

  createEffect(() => {
    if (settingsOpen() && session.state.mode === "cloud") void session.enumerateDevices();
  });

  onMount(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (
        settingsOpen() &&
        event.key === "Escape" &&
        !document.querySelector("dialog[open]") &&
        panelRef?.contains(event.target as Node)
      ) {
        event.preventDefault();
        event.stopPropagation();
        closeSettings();
        return;
      }
      if (
        settingsOpen() ||
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
    const refreshDevices = () => {
      if (session.state.mode === "cloud") void session.enumerateDevices();
    };
    navigator.mediaDevices?.addEventListener?.("devicechange", refreshDevices);
    onCleanup(() => navigator.mediaDevices?.removeEventListener?.("devicechange", refreshDevices));
  });

  // -----------------------------------------------------------------------
  // Render
  // -----------------------------------------------------------------------

  return (
    <>
      <div class={styles.spacer} style={{ "--spacer-height": `${spacerHeight()}px` }} aria-hidden="true" />
      <div
        class={styles.panel}
        ref={panelRef}
        role="region"
        aria-label={messages().voiceAssistant}
        data-testid="voice-overlay"
      >
        <div class={styles.panelInner}>
          {/* Idle state: mic button right-aligned */}
          <Show when={!isActive()}>
            <div class={styles.rowEnd}>
              <SettingsButton />
              <button
                ref={connectButton}
                type="button"
                class={`${controls.control} ${controls.outlined}`}
                onClick={() => handleMicClick()}
                title={messages().connect}
                aria-label={messages().connect}
              >
                <VoiceIcon name="mic" />
              </button>
            </div>
          </Show>

          <Show
            when={
              session.state.error !== null &&
              !(settingsOpen() && settingsError() === session.state.error) &&
              session.state.error
            }
            keyed
          >
            {(err) => (
              <ErrorPanel
                error={err}
                messages={messages}
                settingsButton={SettingsButton}
                onRetry={() => handleMicClick()}
                onDismiss={() => {
                  session.dismissError();
                  queueMicrotask(() => connectButton?.focus());
                }}
              />
            )}
          </Show>
          <Show
            when={session.state.error === null && session.state.connectStatus !== null && session.state.connectStatus}
            keyed
          >
            {(status) => (
              <ConnectingPanel
                status={status}
                phase={session.state.connectPhase}
                messages={messages}
                hasCustomMessages={props.messages !== undefined}
                onDisconnect={() => session.disconnect()}
                settingsButton={SettingsButton}
              />
            )}
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
              settingsButton={SettingsButton}
              onToggleMute={() => session.toggleMute()}
              onSelectInput={(id) => session.selectInputDevice(id)}
              onSelectOutput={(id) => session.selectOutputDevice(id)}
              onClearTranscript={() => session.clearTranscript()}
            />
          </Show>
          <Show when={!settingsOpen() && settingsError() !== session.state.error && settingsError()}>
            {(error) => (
              <p role="alert" class={styles.statusError}>
                {error()}
              </p>
            )}
          </Show>
          {/* Keep drafts and pending language saves alive when settings close. */}
          <section
            id={settingsId}
            hidden={!settingsOpen()}
            class={styles.settingsSection}
            aria-label={messages().voiceSettings ?? "Voice settings"}
          >
            <div class={styles.settingsHeader}>
              <h2>{messages().voiceSettings ?? "Voice settings"}</h2>
              <button
                type="button"
                class={controls.control}
                aria-label={messages().closeVoiceSettings ?? "Close voice settings"}
                onClick={closeSettings}
              >
                <VoiceIcon name="close" />
              </button>
            </div>
            <VoiceSettings messages={messages} onError={setSettingsError} onLanguageError={setLanguageError} />
            <Show when={session.state.mode === "cloud" && !session.state.connected}>
              <AudioDevicePicker
                inputs={session.state.audioInputs}
                outputs={session.state.audioOutputs}
                selectedInputId={session.state.selectedInputId}
                selectedOutputId={session.state.selectedOutputId}
                messages={messages}
                switching={session.state.audioSwitching !== null || session.state.connectStatus !== null}
                onSelectInput={(id) => session.selectInputDevice(id)}
                onSelectOutput={(id) => session.selectOutputDevice(id)}
              />
              <AudioErrors state={session.state} />
            </Show>
          </section>
        </div>
      </div>
    </>
  );
}

// Sub-panels

function ConnectingPanel(props: {
  status: string;
  phase: VoiceState["connectPhase"];
  messages: Accessor<VoiceOverlayMessages>;
  hasCustomMessages: boolean;
  onDisconnect: () => void;
  settingsButton: () => JSX.Element;
}) {
  return (
    <div class={`${styles.row} ${styles.statusConnecting}`}>
      {props.settingsButton()}
      <VoiceIcon name="mic" />
      <span class={styles.statusText}>
        {props.phase === "setup" && voiceSession.state.mode === "browser"
          ? (props.messages().settingUpBrowserSpeech ?? props.status)
          : localizedStatus(props.status, props.phase, props.messages(), props.hasCustomMessages)}
      </span>
      <button
        type="button"
        class={controls.control}
        onClick={() => props.onDisconnect()}
        title={props.messages().cancel}
        aria-label={props.messages().cancelConnection}
      >
        <VoiceIcon name="close" />
      </button>
    </div>
  );
}

function ErrorPanel(props: {
  error: string;
  messages: Accessor<VoiceOverlayMessages>;
  onRetry: () => void;
  onDismiss: () => void;
  settingsButton: () => JSX.Element;
}) {
  return (
    <div class={styles.errorPanel}>
      <div role="alert" class={styles.statusError}>
        {safeErrorDetail(props.error) || props.messages().connectionFailed}
      </div>
      <div class={styles.errorActions}>
        {props.settingsButton()}
        <button
          type="button"
          class={controls.control}
          onClick={() => props.onRetry()}
          title={props.messages().retry}
          aria-label={props.messages().retry}
        >
          <VoiceIcon name="refresh" />
        </button>
        <button
          type="button"
          class={controls.control}
          onClick={() => props.onDismiss()}
          title={props.messages().dismiss ?? "Dismiss"}
          aria-label={props.messages().dismiss ?? "Dismiss"}
        >
          <VoiceIcon name="close" />
        </button>
      </div>
    </div>
  );
}

function ActivePanel(props: {
  state: VoiceState;
  messages: Accessor<VoiceOverlayMessages>;
  onDisconnect: () => void;
  onToggleMute: () => void;
  onSelectInput: (id: string) => Promise<void>;
  onSelectOutput: (id: string) => Promise<void>;
  onClearTranscript: () => void;
  settingsButton: () => JSX.Element;
}) {
  // Gateway work outranks muting: a muted user still waits for the reply.
  const busyText = () => {
    if (props.state.turnState === TurnStateTranscribing) return props.messages().transcribing;
    if (props.state.turnState === TurnStateThinking) return props.messages().thinking;
    return null;
  };

  const statusText = () => {
    if (props.state.activeTool !== null) return props.state.activeTool;
    if (props.state.speaking) return props.messages().speaking;
    const busy = busyText();
    if (busy !== null) return busy;
    if (props.state.muted) return props.messages().muted;
    return props.messages().listening;
  };

  const statusClass = () => {
    if (props.state.activeTool !== null) return `${styles.statusText} ${styles.statusTool}`;
    if (!props.state.speaking && busyText() !== null) return `${styles.statusText} ${styles.statusBusy}`;
    return styles.statusText;
  };

  return (
    <>
      <div class={styles.callToolbar}>
        <div class={styles.callStatus}>
          <MicLevelBars micLevel={props.state.micLevel} />
          <span class={statusClass()}>{statusText()}</span>
        </div>
        <div class={styles.callActions}>
          {props.settingsButton()}
          <button
            type="button"
            class={`${controls.control}${props.state.muted ? " " + controls.muted : ""}`}
            onClick={() => props.onToggleMute()}
            title={props.state.muted ? props.messages().unmute : props.messages().mute}
            aria-label={props.state.muted ? props.messages().unmute : props.messages().mute}
          >
            <Show when={props.state.muted} fallback={<VoiceIcon name="mic" />}>
              <VoiceIcon name="micOff" />
            </Show>
          </button>
          <button
            type="button"
            class={`${controls.control} ${controls.danger}`}
            onClick={() => props.onDisconnect()}
            title={props.messages().endSession}
            aria-label={props.messages().endSession}
          >
            <VoiceIcon name="callEnd" />
          </button>
        </div>
      </div>
      {props.state.mode === "cloud" && (
        <AudioDevicePicker
          inputs={props.state.audioInputs}
          outputs={props.state.audioOutputs}
          selectedInputId={props.state.selectedInputId}
          selectedOutputId={props.state.selectedOutputId}
          messages={props.messages}
          onSelectInput={props.onSelectInput}
          onSelectOutput={props.onSelectOutput}
          switching={props.state.audioSwitching !== null}
        />
      )}
      <Show when={props.state.mode === "cloud"}>
        <AudioErrors state={props.state} />
      </Show>
      <TranscriptLog
        transcript={props.state.transcript}
        messages={props.messages}
        onClear={() => props.onClearTranscript()}
      />
    </>
  );
}

// Audio device picker

function AudioErrors(props: { state: VoiceState }) {
  const message = () =>
    [props.state.audioDevicesError, props.state.audioInputError, props.state.audioOutputError]
      .filter((error) => error !== null)
      .join(" ");
  return (
    <Show when={message()}>
      {(error) => (
        <p role="alert" class={styles.audioError}>
          {error()}
        </p>
      )}
    </Show>
  );
}

function AudioDevicePicker(props: {
  inputs: Array<{ deviceId: string; label: string }>;
  outputs: Array<{ deviceId: string; label: string }>;
  selectedInputId: string;
  selectedOutputId: string;
  messages: Accessor<VoiceOverlayMessages>;
  onSelectInput: (id: string) => Promise<void>;
  onSelectOutput: (id: string) => Promise<void>;
  switching: boolean;
}) {
  const canSelectOutput = "setSinkId" in HTMLMediaElement.prototype;
  return (
    <div class={styles.deviceRow}>
      <div class={styles.deviceField}>
        <span class={`${styles.deviceControl} ${controls.control} ${controls.outlined}`}>
          <VoiceIcon
            name={audioDeviceIcon(
              props.inputs.find((d) => d.deviceId === (props.selectedInputId || "default"))?.label ?? "",
              "input",
            )}
          />
          <select
            class={styles.deviceSelect}
            value={props.selectedInputId}
            onChange={async (e) => {
              const select = e.currentTarget;
              await props.onSelectInput(select.value);
              select.value = props.selectedInputId;
            }}
            title={`${props.messages().microphone}: ${
              props.inputs.find((d) => d.deviceId === (props.selectedInputId || "default"))?.label ??
              (props.selectedInputId
                ? (props.messages().unavailableDevice ?? defaultVoiceOverlayMessages.unavailableDevice)
                : (props.messages().systemDefault ?? defaultVoiceOverlayMessages.systemDefault))
            }`}
            aria-label={props.messages().microphone}
            disabled={props.switching}
          >
            <option value="">{props.messages().systemDefault ?? defaultVoiceOverlayMessages.systemDefault}</option>
            <Show when={props.selectedInputId && !props.inputs.some((d) => d.deviceId === props.selectedInputId)}>
              <option value={props.selectedInputId}>
                {props.messages().unavailableDevice ?? defaultVoiceOverlayMessages.unavailableDevice}
              </option>
            </Show>
            <For each={props.inputs.filter((d) => d.deviceId !== "" && d.deviceId !== "default")}>
              {(d) => <option value={d.deviceId}>{d.label}</option>}
            </For>
          </select>
        </span>
      </div>
      <div class={styles.deviceField}>
        <span
          class={
            canSelectOutput ? `${styles.deviceControl} ${controls.control} ${controls.outlined}` : styles.deviceControl
          }
        >
          <VoiceIcon
            name={
              canSelectOutput
                ? audioDeviceIcon(
                    props.outputs.find((d) => d.deviceId === (props.selectedOutputId || "default"))?.label ?? "",
                    "output",
                  )
                : "volumeUp"
            }
          />
          <Show
            when={canSelectOutput}
            fallback={
              <span class={styles.deviceHint}>
                {props.messages().systemAudioSettings ?? defaultVoiceOverlayMessages.systemAudioSettings}
              </span>
            }
          >
            <select
              class={styles.deviceSelect}
              value={props.selectedOutputId}
              onChange={async (e) => {
                const select = e.currentTarget;
                await props.onSelectOutput(select.value);
                select.value = props.selectedOutputId;
              }}
              title={`${props.messages().speaker}: ${
                props.outputs.find((d) => d.deviceId === (props.selectedOutputId || "default"))?.label ??
                (props.selectedOutputId
                  ? (props.messages().unavailableDevice ?? defaultVoiceOverlayMessages.unavailableDevice)
                  : (props.messages().systemDefault ?? defaultVoiceOverlayMessages.systemDefault))
              }`}
              aria-label={props.messages().speaker}
              disabled={props.switching}
            >
              <option value="">{props.messages().systemDefault ?? defaultVoiceOverlayMessages.systemDefault}</option>
              <Show when={props.selectedOutputId && !props.outputs.some((d) => d.deviceId === props.selectedOutputId)}>
                <option value={props.selectedOutputId}>
                  {props.messages().unavailableDevice ?? defaultVoiceOverlayMessages.unavailableDevice}
                </option>
              </Show>
              <For each={props.outputs.filter((d) => d.deviceId !== "" && d.deviceId !== "default")}>
                {(d) => <option value={d.deviceId}>{d.label}</option>}
              </For>
            </select>
          </Show>
        </span>
      </div>
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

function TranscriptLog(props: {
  transcript: TranscriptEntry[];
  messages: Accessor<VoiceOverlayMessages>;
  onClear: () => void;
}) {
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
