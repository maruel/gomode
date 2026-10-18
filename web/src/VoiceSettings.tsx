// Automatically saved browser voice mode and language controls shared by the overlay and host settings.

import { createEffect, createSignal, createUniqueId, onCleanup, Show, For, on, type Accessor } from "solid-js";
import { browserSpeechAvailable } from "./BrowserSpeech";
import { voiceSession, type VoiceMode } from "./VoiceSession";
import styles from "./VoiceSettings.module.css";

export interface VoiceSettingsMessages {
  browserSpeech: string;
  browserSpeechPrivacy: string;
  browserSpeechUnavailable: string;
  cloudVoice: string;
  cloudVoiceDescription: string;
  endSessionToChange: string;
  voiceChoice: string;
  speakingVoice: string;
  speakingVoiceUnavailable: string;
  choicesLoading: string;
  choicesRetry: string;
  choiceUnavailable: string;
  voiceLanguage: string;
  voiceMode: string;
}

export const defaultVoiceSettingsMessages: VoiceSettingsMessages = {
  browserSpeech: "Browser speech",
  browserSpeechPrivacy:
    "Your browser converts your speech to text and reads replies aloud. Only text goes to the voice gateway. Speech recognition may use your browser vendor's cloud service.",
  browserSpeechUnavailable: "Browser speech is unavailable in this browser. Use Cloud voice.",
  cloudVoice: "Cloud voice",
  cloudVoiceDescription: "The voice gateway receives your microphone audio and sends back spoken replies.",
  endSessionToChange: "End the voice session before changing voice settings.",
  voiceChoice: "Voice choice",
  speakingVoice: "Speaking voice",
  speakingVoiceUnavailable: "Choose an available speaking voice",
  choicesLoading: "Loading voice choices…",
  choicesRetry: "Retry loading voice choices",
  choiceUnavailable: "Choose an available voice choice",
  voiceLanguage: "Voice language (e.g. en-US)",
  voiceMode: "Voice mode",
};

export default function VoiceSettings(props: {
  messages?: Partial<VoiceSettingsMessages> | Accessor<Partial<VoiceSettingsMessages>>;
  onError?: (error: string | null) => void;
  onLanguageError?: (error: string | null) => void;
}) {
  createEffect(
    on(voiceSession.gatewayIdentity, () => {
      void voiceSession.loadVoiceChoices();
    }),
  );
  const messages = () => {
    const value = props.messages;
    return { ...defaultVoiceSettingsMessages, ...(typeof value === "function" ? value() : value) };
  };
  const [tag, setTag] = createSignal(voiceSession.state.languageTag);
  let savedTag = voiceSession.state.languageTag;
  createEffect(() => {
    const next = voiceSession.state.languageTag;
    setTag((draft) => (draft === savedTag ? next : draft));
    savedTag = next;
  });
  const [languageError, setLanguageError] = createSignal<string | null>(null);
  const [modeError, setModeError] = createSignal<string | null>(null);
  const [choiceError, setChoiceError] = createSignal<string | null>(null);
  const [voiceSaveError, setVoiceSaveError] = createSignal<string | null>(null);
  createEffect(
    on(
      () => [voiceSession.gatewayIdentity(), voiceSession.state.choiceID, voiceSession.state.mode],
      () => {
        setVoiceSaveError(null);
      },
    ),
  );
  const error = () =>
    languageError() ??
    modeError() ??
    choiceError() ??
    voiceSaveError() ??
    voiceSession.state.choicesError ??
    voiceSession.state.voiceError;
  createEffect(() => props.onError?.(error()));
  createEffect(() => props.onLanguageError?.(languageError()));
  const errorId = createUniqueId();
  const modeName = createUniqueId();
  const cloudDescriptionId = createUniqueId();
  const browserDescriptionId = createUniqueId();
  const active = () => voiceSession.state.connected || voiceSession.state.connectStatus !== null;
  const supported = browserSpeechAvailable();
  let pendingSave: ReturnType<typeof setTimeout> | null = null;
  const saveLanguage = () => {
    if (pendingSave !== null) {
      clearTimeout(pendingSave);
      pendingSave = null;
    }
    if (active() || tag() === voiceSession.state.languageTag) return;
    try {
      voiceSession.selectLanguage(tag());
      setTag(voiceSession.state.languageTag);
      setLanguageError(null);
    } catch (err) {
      setLanguageError(err instanceof Error ? err.message : String(err));
    }
  };
  createEffect(() => {
    const draft = tag();
    if (active() || draft === voiceSession.state.languageTag) return;
    const timer = setTimeout(saveLanguage, 1000);
    pendingSave = timer;
    onCleanup(() => {
      clearTimeout(timer);
      if (pendingSave === timer) pendingSave = null;
    });
  });
  let cloudRadio: HTMLInputElement | undefined;
  let browserRadio: HTMLInputElement | undefined;
  const selectMode = (mode: VoiceMode) => {
    try {
      voiceSession.selectMode(mode);
      setModeError(null);
    } catch (err) {
      // Native radio selection changes before persistence can fail.
      const selected = voiceSession.state.mode === "cloud" ? cloudRadio : browserRadio;
      if (selected) selected.checked = true;
      setModeError(err instanceof Error ? err.message : String(err));
    }
  };
  return (
    <div class={styles.settings}>
      <Show when={active()}>
        <p>{messages().endSessionToChange}</p>
      </Show>
      <fieldset disabled={active()}>
        <legend>{messages().voiceMode}</legend>
        <label>
          <input
            type="radio"
            name={modeName}
            ref={cloudRadio}
            value="cloud"
            checked={voiceSession.state.mode === "cloud"}
            onChange={() => selectMode("cloud")}
            aria-describedby={cloudDescriptionId}
          />
          {messages().cloudVoice}
        </label>
        <p id={cloudDescriptionId}>{messages().cloudVoiceDescription}</p>
        <label>
          <input
            type="radio"
            name={modeName}
            ref={browserRadio}
            value="browser"
            checked={voiceSession.state.mode === "browser"}
            disabled={!supported}
            onChange={() => selectMode("browser")}
            aria-describedby={browserDescriptionId}
          />
          {messages().browserSpeech}
        </label>
        <p id={browserDescriptionId}>
          {supported ? messages().browserSpeechPrivacy : messages().browserSpeechUnavailable}
        </p>
      </fieldset>
      <label>
        {messages().voiceChoice}
        <select
          disabled={active() || voiceSession.state.choicesStatus !== "ready"}
          value={voiceSession.state.choiceID ?? ""}
          onChange={(event) => {
            try {
              voiceSession.selectChoice(event.currentTarget.value);
              setChoiceError(null);
            } catch (err) {
              event.currentTarget.value = voiceSession.state.choiceID ?? "";
              setChoiceError(err instanceof Error ? err.message : String(err));
            }
          }}
          aria-describedby={error() === null ? undefined : errorId}
        >
          <Show when={!voiceSession.compatibleChoices.some((choice) => choice.id === voiceSession.state.choiceID)}>
            <option value={voiceSession.state.choiceID ?? ""} disabled>
              {messages().choiceUnavailable}
            </option>
          </Show>
          <For each={voiceSession.compatibleChoices}>
            {(choice) => <option value={choice.id}>{choice.label}</option>}
          </For>
        </select>
      </label>
      <Show when={voiceSession.speakingVoices.length > 0}>
        <label>
          {messages().speakingVoice}
          <select
            disabled={
              active() || voiceSession.state.choicesStatus !== "ready" || voiceSession.state.voiceStatus !== "ready"
            }
            value={voiceSession.state.voiceName ?? ""}
            onChange={(event) => {
              try {
                voiceSession.selectSpeakingVoice(event.currentTarget.value);
                setVoiceSaveError(null);
              } catch (err) {
                event.currentTarget.value = voiceSession.state.voiceName ?? "";
                setVoiceSaveError(err instanceof Error ? err.message : String(err));
              }
            }}
            aria-describedby={error() === null ? undefined : errorId}
          >
            <Show when={!voiceSession.speakingVoices.includes(voiceSession.state.voiceName ?? "")}>
              <option value={voiceSession.state.voiceName ?? ""} disabled>
                {messages().speakingVoiceUnavailable}
              </option>
            </Show>
            <For each={voiceSession.speakingVoices}>{(name) => <option value={name}>{name}</option>}</For>
          </select>
        </label>
      </Show>
      <Show when={voiceSession.state.choicesStatus === "loading"}>
        <p role="status">{messages().choicesLoading}</p>
      </Show>
      <Show when={voiceSession.state.choicesStatus === "error" || voiceSession.state.voiceStatus === "error"}>
        <button
          type="button"
          disabled={active()}
          onClick={() => {
            void voiceSession.loadVoiceChoices();
          }}
        >
          {messages().choicesRetry}
        </button>
      </Show>
      <label>
        {messages().voiceLanguage}
        <input
          type="text"
          disabled={active()}
          value={tag()}
          onInput={(event) => {
            setTag(event.currentTarget.value);
            setLanguageError(null);
          }}
          // Flush before a close or connect click can use the saved preference.
          onBlur={saveLanguage}
          aria-invalid={languageError() !== null}
          aria-describedby={languageError() === null ? undefined : errorId}
          spellcheck={false}
          autocapitalize="none"
        />
      </label>
      <p id={errorId} role="alert" class={styles.error}>
        {error()}
      </p>
    </div>
  );
}
