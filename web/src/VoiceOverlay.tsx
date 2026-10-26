// Voice overlay component: persistent bottom panel with microphone and voice controls.

import { createEffect, createSignal, createUniqueId, For, Show, onCleanup, onMount, type Accessor } from "solid-js";

import { TurnStateThinking, TurnStateTranscribing } from "../../sdk/voicegateway/ts/v1/types.gen";
import { bluetoothAudioDeviceLabel, microphoneMuted, voiceSession } from "./VoiceSession";
import type { MicrophoneMode, VoiceState, TranscriptEntry } from "./VoiceSession";
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
  nextServer?: string;
  assistant: string;
  callingTool?: string;
  cancel: string;
  cancelConnection: string;
  hideTranscript?: string;
  showTranscript?: string;
  connect: string;
  connectionFailed: string;
  endSession: string;
  listening: string;
  microphone: string;
  audioDevices?: string;
  microphoneAccessOff?: string;
  enableMicrophone?: string;
  requestingMicrophone?: string;
  enableSound?: string;
  enableMicrophoneMeter?: string;
  microphoneMode?: string;
  microphoneNextState?: string;
  microphoneListening?: string;
  microphoneMuteOnce?: string;
  microphoneFullyMuted?: string;
  microphoneMuteOnceHelp?: string;
  microphoneMuteNextReplyHelp?: string;
  microphoneMutedHelp?: string;
  muted: string;
  reconnecting: string;
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
  voiceActivity?: string;
  voiceAssistant: string;
  voiceControls?: string;
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
  nextServer: "Next server",
  assistant: "Assistant:",
  callingTool: "Calling tool:",
  cancel: "Cancel",
  cancelConnection: "Cancel connection",
  hideTranscript: "Hide transcript",
  showTranscript: "Show transcript",
  connect: "Connect voice assistant",
  connectionFailed: "Voice connection failed",
  endSession: "End voice session",
  listening: "Listening…",
  microphone: "Microphone",
  audioDevices: "Audio devices",
  microphoneAccessOff:
    "Microphone access is off. Allow access in your browser’s site permissions or device privacy settings, then try again.",
  enableMicrophone: "Enable microphone",
  requestingMicrophone: "Requesting access…",
  enableSound: "Enable sound",
  enableMicrophoneMeter: "Enable level meter",
  microphoneMode: "Microphone mode",
  microphoneNextState: "Next",
  microphoneListening: "Listening",
  microphoneMuteOnce: "Mute once",
  microphoneFullyMuted: "Muted",
  microphoneMuteOnceHelp: "Muted until this reply ends.",
  microphoneMuteNextReplyHelp: "Mutes during the next reply, then returns to Listening.",
  microphoneMutedHelp: "Stays muted until you unmute.",
  muted: "Muted",
  reconnecting: "Reconnecting…",
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
  voiceActivity: "Voice activity",
  voiceAssistant: "Voice assistant",
  voiceControls: "Voice controls",
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

export default function VoiceOverlay(props: {
  messages?: VoiceOverlayMessages | Accessor<VoiceOverlayMessages>;
  // The host owns its server catalog, navigation, and voice-context handoff.
  nextServer?: { name: string; onSelect: () => void };
}) {
  const session = voiceSession;
  const messages = () => {
    const value = props.messages;
    return (typeof value === "function" ? value() : value) ?? defaultVoiceOverlayMessages;
  };

  let panelRef: HTMLDivElement | undefined;
  let connectButtonRef: HTMLButtonElement | undefined;
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
  const AppControls = () => (
    <div class={styles.callActions}>
      <SettingsButton />
      <Show when={props.nextServer}>
        {(server) => (
          <button
            type="button"
            class={controls.control}
            title={`${messages().nextServer ?? defaultVoiceOverlayMessages.nextServer}: ${server().name}`}
            aria-label={`${messages().nextServer ?? defaultVoiceOverlayMessages.nextServer}: ${server().name}`}
            onClick={() => server().onSelect()}
          >
            <VoiceIcon name="swapHoriz" />
          </button>
        )}
      </Show>
    </div>
  );
  const [spacerHeight, setSpacerHeight] = createSignal(0);
  onMount(() => {
    onCleanup(session.observeMicrophoneAccess());
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

  const isConnected = () => session.state.connected || session.state.listening || session.state.speaking;
  const isActive = () => isConnected() || session.state.connectStatus !== null;

  // -----------------------------------------------------------------------
  // Event handlers
  // -----------------------------------------------------------------------

  const handleMicClick = async () => {
    if (!isActive() && session.state.microphoneAccessPending) return;
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
    if (session.state.mode === "cloud") void session.enumerateDevices();
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
          <div
            class={styles.callToolbar}
            role="group"
            aria-label={messages().voiceControls ?? defaultVoiceOverlayMessages.voiceControls}
            onFocusIn={(event) => {
              event.target.closest("button, select")?.scrollIntoView({ block: "nearest", inline: "nearest" });
            }}
          >
            <div class={styles.toolbarActions}>
              <AppControls />
              <Show when={session.state.mode === "cloud"}>
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
              </Show>
              <Show when={isConnected()}>
                <MicrophoneControls
                  state={session.state}
                  messages={messages}
                  onSelect={(mode) => session.setMicrophoneMode(mode)}
                />
              </Show>
            </div>
            <div class={styles.callActions}>
              <Show when={isConnected()}>
                <MicLevelBars micLevel={session.state.micLevel} />
              </Show>
              <button
                ref={connectButtonRef}
                type="button"
                class={`${controls.control} ${isActive() ? controls.danger : controls.outlined}`}
                onClick={() => handleMicClick()}
                disabled={!isActive() && session.state.microphoneAccessPending}
                title={isActive() ? messages().endSession : messages().connect}
                aria-label={
                  session.state.connectStatus !== null
                    ? messages().cancelConnection
                    : isConnected()
                      ? messages().endSession
                      : messages().connect
                }
              >
                <VoiceIcon name={isActive() ? "callEnd" : "mic"} />
              </button>
            </div>
          </div>
          <Show when={session.state.microphoneAccess === "denied"}>
            <div role="status" class={styles.accessNotice}>
              <span>{messages().microphoneAccessOff ?? defaultVoiceOverlayMessages.microphoneAccessOff}</span>
              <button
                type="button"
                class={styles.accessAction}
                aria-disabled={session.state.microphoneAccessPending}
                onClick={async () => {
                  if (session.state.microphoneAccessPending) return;
                  await session.requestMicrophoneAccess();
                  if (session.state.microphoneAccess === "granted") connectButtonRef?.focus();
                }}
              >
                {session.state.microphoneAccessPending
                  ? (messages().requestingMicrophone ?? defaultVoiceOverlayMessages.requestingMicrophone)
                  : (messages().enableMicrophone ?? defaultVoiceOverlayMessages.enableMicrophone)}
              </button>
            </div>
          </Show>
          <Show when={session.state.micLevelError}>
            {(error) => (
              <div role="status" class={styles.accessNotice}>
                <span>{error()}</span>
                <button
                  type="button"
                  class={styles.accessAction}
                  onClick={async () => {
                    await session.enableMicrophoneMeter();
                    if (session.state.micLevelError === null) connectButtonRef?.focus();
                  }}
                >
                  {messages().enableMicrophoneMeter ?? defaultVoiceOverlayMessages.enableMicrophoneMeter}
                </button>
              </div>
            )}
          </Show>
          <Show when={session.state.audioPlaybackError}>
            {(error) => (
              <div role="status" class={styles.accessNotice}>
                <span>{error()}</span>
                <button type="button" class={styles.accessAction} onClick={() => session.resumeAudio()}>
                  {messages().enableSound ?? defaultVoiceOverlayMessages.enableSound}
                </button>
              </div>
            )}
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
              <div role="alert" class={`${styles.errorPanel} ${styles.statusError}`}>
                {safeErrorDetail(err) || messages().connectionFailed}
              </div>
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
            <ActivePanel state={session.state} messages={messages} />
          </Show>
          <Show when={session.state.mode === "cloud"}>
            <AudioErrors state={session.state} />
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
            <VoiceSettings
              messages={messages}
              onError={setSettingsError}
              onLanguageError={setLanguageError}
              visible={settingsOpen()}
            />
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
}) {
  return (
    <div class={`${styles.row} ${styles.statusConnecting}`}>
      <VoiceIcon name="mic" />
      <span class={styles.statusText}>
        {props.phase === "setup" && voiceSession.state.mode === "browser"
          ? (props.messages().settingUpBrowserSpeech ?? props.status)
          : localizedStatus(props.status, props.phase, props.messages(), props.hasCustomMessages)}
      </span>
    </div>
  );
}

function MicrophoneControls(props: {
  state: VoiceState;
  messages: Accessor<VoiceOverlayMessages>;
  onSelect: (mode: MicrophoneMode) => void;
}) {
  const microphoneHelpId = createUniqueId();
  const nextMicrophoneMode = (): MicrophoneMode =>
    props.state.microphoneMode === "listening"
      ? "muteOnce"
      : props.state.microphoneMode === "muteOnce"
        ? "muted"
        : "listening";
  const microphoneStateLabel = (mode: MicrophoneMode) => {
    const messages = props.messages();
    return mode === "listening"
      ? (messages.microphoneListening ?? defaultVoiceOverlayMessages.microphoneListening)
      : mode === "muteOnce"
        ? (messages.microphoneMuteOnce ?? defaultVoiceOverlayMessages.microphoneMuteOnce)
        : (messages.microphoneFullyMuted ?? defaultVoiceOverlayMessages.microphoneFullyMuted);
  };
  const microphoneLabel = () => {
    const messages = props.messages();
    return `${messages.microphoneMode ?? defaultVoiceOverlayMessages.microphoneMode}: ${microphoneStateLabel(props.state.microphoneMode)}. ${messages.microphoneNextState ?? defaultVoiceOverlayMessages.microphoneNextState}: ${microphoneStateLabel(nextMicrophoneMode())}`;
  };
  return (
    <>
      <button
        type="button"
        class={`${controls.control} ${styles.microphoneControl}`}
        title={microphoneLabel()}
        aria-label={microphoneLabel()}
        aria-describedby={props.state.microphoneMode === "listening" ? undefined : microphoneHelpId}
        onClick={() => props.onSelect(nextMicrophoneMode())}
      >
        <VoiceIcon name={props.state.microphoneMode === "listening" ? "mic" : "micOff"} />
        <Show when={props.state.microphoneMode === "muteOnce"}>
          <span class={styles.microphoneClock}>
            <VoiceIcon name="schedule" />
          </span>
        </Show>
      </button>
      <Show when={props.state.microphoneMode !== "listening"}>
        <span id={microphoneHelpId} class={styles.microphoneHelp}>
          {props.state.microphoneMode === "muted"
            ? (props.messages().microphoneMutedHelp ?? defaultVoiceOverlayMessages.microphoneMutedHelp)
            : props.state.speaking
              ? (props.messages().microphoneMuteOnceHelp ?? defaultVoiceOverlayMessages.microphoneMuteOnceHelp)
              : (props.messages().microphoneMuteNextReplyHelp ??
                defaultVoiceOverlayMessages.microphoneMuteNextReplyHelp)}
        </span>
      </Show>
    </>
  );
}

function ActivePanel(props: { state: VoiceState; messages: Accessor<VoiceOverlayMessages> }) {
  const [transcriptExpanded, setTranscriptExpanded] = createSignal(true);
  const transcriptId = createUniqueId();
  let transcriptToggleRef: HTMLButtonElement | undefined;
  const TranscriptToggle = () => {
    const label = () =>
      transcriptExpanded()
        ? (props.messages().hideTranscript ?? defaultVoiceOverlayMessages.hideTranscript)
        : (props.messages().showTranscript ?? defaultVoiceOverlayMessages.showTranscript);
    return (
      <button
        ref={transcriptToggleRef}
        type="button"
        class={`${controls.control} ${styles.transcriptToggle}`}
        onClick={() => {
          setTranscriptExpanded(!transcriptExpanded());
          queueMicrotask(() => transcriptToggleRef?.focus());
        }}
        title={label()}
        aria-label={label()}
        aria-expanded={transcriptExpanded()}
        aria-controls={transcriptId}
      >
        <VoiceIcon name={transcriptExpanded() ? "close" : "subtitles"} />
      </button>
    );
  };
  // Gateway work outranks muting: a muted user still waits for the reply.
  const busyText = () => {
    if (props.state.turnState === TurnStateTranscribing) return props.messages().transcribing;
    if (props.state.turnState === TurnStateThinking) return props.messages().thinking;
    return null;
  };

  const statusText = () => {
    if (props.state.activeTool !== null)
      return `${props.messages().callingTool ?? defaultVoiceOverlayMessages.callingTool} ${props.state.activeTool}…`;
    if (props.state.speaking) return props.messages().speaking;
    const busy = busyText();
    if (busy !== null) return busy;
    if (microphoneMuted(props.state)) return props.messages().muted;
    return props.messages().listening;
  };

  const statusClass = () => {
    if (props.state.activeTool !== null) return `${styles.activityText} ${styles.statusTool}`;
    if (!props.state.speaking && busyText() !== null) return `${styles.activityText} ${styles.statusBusy}`;
    return styles.activityText;
  };

  return (
    <>
      <div
        class={styles.callStatus}
        role="status"
        aria-label={props.messages().voiceActivity ?? defaultVoiceOverlayMessages.voiceActivity}
      >
        <span class={statusClass()}>{statusText()}</span>
        <Show when={props.state.transcript.length > 0 && !transcriptExpanded()}>
          <TranscriptToggle />
        </Show>
      </div>
      <div class={styles.transcriptContent}>
        <div class={styles.transcriptBody}>
          <TranscriptLog
            id={transcriptId}
            expanded={transcriptExpanded()}
            transcript={props.state.transcript}
            messages={props.messages}
          />
        </div>
        <Show when={props.state.transcript.length > 0 && transcriptExpanded()}>
          <TranscriptToggle />
        </Show>
      </div>
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
    <div
      class={styles.deviceGroup}
      role="group"
      aria-label={props.messages().audioDevices ?? defaultVoiceOverlayMessages.audioDevices}
    >
      <div class={styles.deviceRow}>
        <div class={styles.deviceField}>
          <span class={`${styles.deviceControl} ${controls.control}`}>
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
          <span class={canSelectOutput ? `${styles.deviceControl} ${controls.control}` : styles.deviceControl}>
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
                <Show
                  when={props.selectedOutputId && !props.outputs.some((d) => d.deviceId === props.selectedOutputId)}
                >
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
  id: string;
  expanded: boolean;
  transcript: TranscriptEntry[];
  messages: Accessor<VoiceOverlayMessages>;
}) {
  let listRef: HTMLDivElement | undefined;

  // Auto-scroll to bottom when transcript changes.
  createEffect(() => {
    const len = props.transcript.length;
    const last = props.transcript[len - 1]?.text;
    void len;
    void last;
    if (props.expanded && listRef) {
      listRef.scrollTop = listRef.scrollHeight;
    }
  });

  return (
    <div id={props.id} hidden={!props.expanded}>
      <Show when={props.expanded}>
        <Show when={props.transcript.length > 0}>
          <div class={styles.transcriptList} ref={listRef}>
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
      </Show>
    </div>
  );
}
