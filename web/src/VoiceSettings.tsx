// Saved browser voice mode and language controls shared by the overlay and host settings.

import { createEffect, createSignal, createUniqueId, Show, type Accessor } from "solid-js";
import { browserSpeechAvailable } from "./BrowserSpeech";
import { voiceSession, type VoiceMode } from "./VoiceSession";
import styles from "./VoiceSettings.module.css";

export interface VoiceSettingsMessages {
  browserSpeech: string;
  browserSpeechPrivacy: string;
  browserSpeechUnavailable: string;
  cloudVoice: string;
  endSessionToChange: string;
  saveLanguage: string;
  voiceLanguage: string;
  voiceMode: string;
}

export const defaultVoiceSettingsMessages: VoiceSettingsMessages = {
  browserSpeech: "Browser speech",
  browserSpeechPrivacy:
    "Your browser transcribes and speaks. Recognition may use your browser vendor's cloud service. The gateway receives text.",
  browserSpeechUnavailable: "Browser speech is unavailable in this browser. Use Cloud voice.",
  cloudVoice: "Cloud voice",
  endSessionToChange: "End the voice session before changing voice settings.",
  saveLanguage: "Save language",
  voiceLanguage: "Voice language (e.g. en-US)",
  voiceMode: "Voice mode",
};

export default function VoiceSettings(props: {
  messages?: Partial<VoiceSettingsMessages> | Accessor<Partial<VoiceSettingsMessages>>;
}) {
  const messages = () => {
    const value = props.messages;
    return { ...defaultVoiceSettingsMessages, ...(typeof value === "function" ? value() : value) };
  };
  const [tag, setTag] = createSignal(voiceSession.state.languageTag);
  let savedTag = voiceSession.state.languageTag;
  let input: HTMLInputElement | undefined;
  createEffect(() => {
    const next = voiceSession.state.languageTag;
    setTag((draft) => (draft === savedTag ? next : draft));
    savedTag = next;
  });
  const [error, setError] = createSignal<string | null>(null);
  const errorId = createUniqueId();
  const active = () => voiceSession.state.connected || voiceSession.state.connectStatus !== null;
  const supported = browserSpeechAvailable();
  return (
    <form
      class={styles.settings}
      onSubmit={(event) => {
        event.preventDefault();
        try {
          voiceSession.selectLanguage(tag());
          setTag(voiceSession.state.languageTag);
          setError(null);
          input?.focus();
        } catch (err) {
          setError(err instanceof Error ? err.message : String(err));
        }
      }}
    >
      <Show when={active()}>
        <p>{messages().endSessionToChange}</p>
      </Show>
      <fieldset disabled={active()}>
        <label>
          {messages().voiceMode}
          <select
            value={voiceSession.state.mode}
            onChange={(event) => {
              try {
                voiceSession.selectMode(event.currentTarget.value as VoiceMode);
                setError(null);
              } catch (err) {
                event.currentTarget.value = voiceSession.state.mode;
                setError(err instanceof Error ? err.message : String(err));
              }
            }}
          >
            <option value="cloud">{messages().cloudVoice}</option>
            <option value="browser" disabled={!supported}>
              {messages().browserSpeech}
            </option>
          </select>
        </label>
        <p>{supported ? messages().browserSpeechPrivacy : messages().browserSpeechUnavailable}</p>
        <label>
          {messages().voiceLanguage}
          <input
            ref={input}
            value={tag()}
            onInput={(event) => {
              setTag(event.currentTarget.value);
              setError(null);
            }}
            aria-invalid={error() !== null}
            aria-describedby={error() === null ? undefined : errorId}
            spellcheck={false}
            autocapitalize="none"
          />
        </label>
        <button type="submit" disabled={tag() === voiceSession.state.languageTag}>
          {messages().saveLanguage}
        </button>
      </fieldset>
      <Show when={error()}>
        {(message) => (
          <p id={errorId} role="alert" class={styles.error}>
            {message()}
          </p>
        )}
      </Show>
    </form>
  );
}
