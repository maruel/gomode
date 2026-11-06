// Automatically saved browser voice mode and language controls shared by the overlay and host settings.

import { createEffect, createSignal, createUniqueId, onCleanup, Show, For, on, type Accessor } from "solid-js";
import { browserSpeechAvailable } from "./BrowserSpeech";
import { BROWSER_SPEECH_RATES, voiceSession, type VoiceMode } from "./VoiceSession";
import type { VoiceTransport } from "../../sdk/voicegateway/ts/v2/types.gen";
import { WebAudioThinkingEarcon } from "./ThinkingEarcon";
import VoiceIcon from "./VoiceIcon";
import controls from "./VoiceControls.module.css";
import styles from "./VoiceSettings.module.css";

export interface VoiceSettingsMessages {
  browserSpeech: string;
  browserSpeechPrivacy: string;
  browserSpeechLlm: string;
  browserSpeechUnavailable: string;
  cloudVoice: string;
  cloudVoiceDescription: string;
  cloudVoiceModel: string;
  endSessionToChange: string;
  speakingVoice: string;
  speakingSpeed: string;
  speakingSpeedNormal: string;
  speakingVoiceDefault: string;
  browserVoiceDefault: string;
  speakingVoiceUnavailable: string;
  choicesLoading: string;
  choicesRetry: string;
  choiceUnavailable: string;
  credits: string;
  musicComposer: string;
  musicArranger: string;
  thinkingMusic: string;
  playMusic: string;
  stopMusic: string;
  playMusicLabel: string;
  stopMusicLabel: string;
  musicPlaybackError: string;
  showReasoning: string;
  showUsageStats: string;
  voiceLanguage: string;
  voiceMode: string;
}

export const defaultVoiceSettingsMessages: VoiceSettingsMessages = {
  browserSpeech: "Browser speech",
  browserSpeechPrivacy:
    "Your browser converts your speech to text and reads replies aloud. Only text goes to the voice gateway. Speech recognition may use your browser vendor's cloud service.",
  browserSpeechLlm: "LLM",
  browserSpeechUnavailable: "Browser speech is unavailable in this browser. Use Cloud voice.",
  cloudVoice: "Cloud voice",
  cloudVoiceDescription: "The voice gateway receives your microphone audio and sends back spoken replies.",
  cloudVoiceModel: "Model",
  endSessionToChange:
    "End the voice session to change voice mode, speaking voice or speed, gateway model, or language.",
  speakingVoice: "Speaking voice",
  speakingSpeed: "Speaking speed",
  speakingSpeedNormal: "1× (Normal)",
  speakingVoiceDefault: "Provider default",
  browserVoiceDefault: "Browser default",
  speakingVoiceUnavailable: "Choose an available speaking voice",
  choicesLoading: "Loading voice choices…",
  choicesRetry: "Retry loading voice choices",
  choiceUnavailable: "Choose an available gateway model",
  credits: "Credits",
  musicComposer: "Composed by Hans Zimmer.",
  musicArranger: "Arranged for Solo-Piano by Philipp A. G. Grzywaczyk.",
  thinkingMusic: "Thinking music",
  playMusic: "Play",
  stopMusic: "Stop",
  playMusicLabel: "Play thinking music",
  stopMusicLabel: "Stop thinking music",
  musicPlaybackError: "Could not play thinking music.",
  showReasoning: "Show assistant reasoning",
  showUsageStats: "Show usage stats",
  voiceLanguage: "Voice language (e.g. en-US)",
  voiceMode: "Voice mode",
};

export default function VoiceSettings(props: {
  messages?: Partial<VoiceSettingsMessages> | Accessor<Partial<VoiceSettingsMessages>>;
  onError?: (error: string | null) => void;
  onLanguageError?: (error: string | null) => void;
  visible?: boolean;
}) {
  const [creditsOpen, setCreditsOpen] = createSignal(false);
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
  const [speedSaveError, setSpeedSaveError] = createSignal<string | null>(null);
  const [reasoningError, setReasoningError] = createSignal<string | null>(null);
  const [usageError, setUsageError] = createSignal<string | null>(null);
  createEffect(
    on(
      () => [
        voiceSession.gatewayIdentity(),
        voiceSession.state.choiceIDs.text,
        voiceSession.state.choiceIDs.webrtc,
        voiceSession.state.mode,
      ],
      () => {
        setVoiceSaveError(null);
      },
    ),
  );
  const error = () =>
    languageError() ??
    modeError() ??
    usageError() ??
    reasoningError() ??
    choiceError() ??
    voiceSaveError() ??
    speedSaveError() ??
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
  const [browserVoices, setBrowserVoices] = createSignal<SpeechSynthesisVoice[]>([]);
  createEffect(() => {
    if (voiceSession.state.mode !== "browser" || props.visible === false || !supported) return;
    const synthesis = window.speechSynthesis;
    const refresh = () =>
      setBrowserVoices(
        [...synthesis.getVoices()].sort(
          (a, b) =>
            a.lang.localeCompare(b.lang) || a.name.localeCompare(b.name) || a.voiceURI.localeCompare(b.voiceURI),
        ),
      );
    synthesis.addEventListener("voiceschanged", refresh);
    refresh();
    onCleanup(() => synthesis.removeEventListener("voiceschanged", refresh));
  });
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
        <div class={styles.family}>
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
          <ChoiceSelect
            transport="webrtc"
            label={messages().cloudVoiceModel}
            unavailable={messages().choiceUnavailable}
            disabled={active()}
            errorId={error() === null ? undefined : errorId}
            onError={setChoiceError}
          />
        </div>
        <div class={styles.family}>
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
          <ChoiceSelect
            transport="text"
            label={messages().browserSpeechLlm}
            unavailable={messages().choiceUnavailable}
            disabled={active()}
            errorId={error() === null ? undefined : errorId}
            onError={setChoiceError}
          />
        </div>
      </fieldset>
      <Show when={voiceSession.state.mode === "browser" && supported}>
        <label>
          {messages().speakingVoice}
          <select
            ref={(select) => {
              createEffect(() => {
                browserVoices();
                const selected = voiceSession.state.browserVoiceURI;
                let cancelled = false;
                // Removing the selected option can reset native selection
                // after the unavailable placeholder has already been inserted.
                queueMicrotask(() => {
                  if (!cancelled) select.value = selected;
                });
                onCleanup(() => {
                  cancelled = true;
                });
              });
            }}
            disabled={active()}
            value={voiceSession.state.browserVoiceURI}
            onChange={(event) => {
              try {
                voiceSession.selectBrowserVoice(event.currentTarget.value);
                setVoiceSaveError(null);
              } catch (err) {
                event.currentTarget.value = voiceSession.state.browserVoiceURI;
                setVoiceSaveError(err instanceof Error ? err.message : String(err));
              }
            }}
            aria-describedby={error() === null ? undefined : errorId}
          >
            <Show
              when={
                voiceSession.state.browserVoiceURI !== "" &&
                !browserVoices().some((voice) => voice.voiceURI === voiceSession.state.browserVoiceURI)
              }
            >
              <option value={voiceSession.state.browserVoiceURI} selected disabled>
                {messages().speakingVoiceUnavailable}
              </option>
            </Show>
            <option value="" selected={voiceSession.state.browserVoiceURI === ""}>
              {messages().browserVoiceDefault}
            </option>
            <For each={browserVoices()}>
              {(voice) => (
                <option value={voice.voiceURI} selected={voiceSession.state.browserVoiceURI === voice.voiceURI}>
                  {voice.name} ({voice.lang})
                </option>
              )}
            </For>
          </select>
        </label>
      </Show>
      <Show when={voiceSession.state.mode === "browser" && supported}>
        <label>
          {messages().speakingSpeed}
          <select
            disabled={active()}
            value={voiceSession.state.browserSpeechRate}
            onChange={(event) => {
              try {
                voiceSession.selectBrowserSpeechRate(Number(event.currentTarget.value));
                setSpeedSaveError(null);
              } catch (err) {
                event.currentTarget.value = String(voiceSession.state.browserSpeechRate);
                setSpeedSaveError(err instanceof Error ? err.message : String(err));
              }
            }}
            aria-describedby={error() === null ? undefined : errorId}
          >
            <For each={BROWSER_SPEECH_RATES}>
              {(rate) => <option value={rate}>{rate === 1 ? messages().speakingSpeedNormal : `${rate}×`}</option>}
            </For>
          </select>
        </label>
      </Show>
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
            <Show
              when={
                voiceSession.state.voiceName !== null &&
                voiceSession.state.voiceName !== "" &&
                !voiceSession.speakingVoices.includes(voiceSession.state.voiceName ?? "")
              }
            >
              <option value={voiceSession.state.voiceName ?? ""} disabled>
                {messages().speakingVoiceUnavailable}
              </option>
            </Show>
            <option value="">{messages().speakingVoiceDefault}</option>
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
          class={`${controls.control} ${styles.retry}`}
          aria-label={messages().choicesRetry}
          title={messages().choicesRetry}
          disabled={active()}
          onClick={() => {
            void voiceSession.loadVoiceChoices();
          }}
        >
          <VoiceIcon name="refresh" />
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
      <label>
        <input
          type="checkbox"
          checked={voiceSession.state.showReasoning}
          onChange={(event) => {
            const next = event.currentTarget.checked;
            try {
              voiceSession.setShowReasoning(next);
              setReasoningError(null);
            } catch (err) {
              event.currentTarget.checked = !next;
              setReasoningError(
                `Could not save the reasoning preference: ${err instanceof Error ? err.message : String(err)}`,
              );
            }
          }}
        />
        {messages().showReasoning}
      </label>
      <label>
        <input
          type="checkbox"
          checked={voiceSession.state.showUsageStats}
          onChange={(event) => {
            const next = event.currentTarget.checked;
            try {
              voiceSession.setShowUsageStats(next);
              setUsageError(null);
            } catch (err) {
              event.currentTarget.checked = !next;
              setUsageError(`Could not save the usage preference: ${err instanceof Error ? err.message : String(err)}`);
            }
          }}
        />
        {messages().showUsageStats}
      </label>
      <p id={errorId} role="alert" class={styles.error}>
        {error()}
      </p>
      <details class={styles.credits} onToggle={(event) => setCreditsOpen(event.currentTarget.open)}>
        <summary>{messages().credits}</summary>
        <Show when={creditsOpen()}>
          <ThinkingMusicPreview active={active()} visible={props.visible !== false} messages={messages} />
        </Show>
        <p>{messages().musicComposer}</p>
        <p>{messages().musicArranger}</p>
      </details>
    </div>
  );
}

function ThinkingMusicPreview(props: { active: boolean; visible: boolean; messages: Accessor<VoiceSettingsMessages> }) {
  const [playing, setPlaying] = createSignal(false);
  const [error, setError] = createSignal(false);
  const preview = new WebAudioThinkingEarcon({
    onPlaybackChange: (playing, error) => {
      setPlaying(playing);
      setError(error !== null);
    },
  });
  createEffect(() => {
    if (props.active || !props.visible) preview.setThinking(false);
  });
  onCleanup(() => preview.dispose());
  return (
    <>
      <div class={styles.preview}>
        <span>{props.messages().thinkingMusic}</span>
        <button
          type="button"
          class={`${controls.control} ${controls.outlined}`}
          disabled={props.active || !props.visible}
          aria-label={playing() ? props.messages().stopMusicLabel : props.messages().playMusicLabel}
          title={playing() ? props.messages().stopMusicLabel : props.messages().playMusicLabel}
          onClick={() => {
            if (playing()) preview.setThinking(false);
            else {
              preview.prepare();
              preview.setThinking(true);
            }
          }}
        >
          <VoiceIcon name={playing() ? "stop" : "playArrow"} />
        </button>
      </div>
      <Show when={error()}>
        <p class={styles.previewError} role="alert">
          {props.messages().musicPlaybackError}
        </p>
      </Show>
    </>
  );
}

function ChoiceSelect(props: {
  transport: VoiceTransport;
  label: string;
  unavailable: string;
  disabled: boolean;
  errorId: string | undefined;
  onError: (error: string | null) => void;
}) {
  const choices = () => voiceSession.choicesFor(props.transport);
  const selected = () => voiceSession.state.choiceIDs[props.transport];
  return (
    <Show when={choices().length > 0}>
      <label>
        {props.label}
        <select
          disabled={props.disabled || voiceSession.state.choicesStatus !== "ready"}
          value={selected() ?? ""}
          onChange={(event) => {
            const previous = selected() ?? "";
            try {
              voiceSession.selectChoice(props.transport, event.currentTarget.value);
              props.onError(null);
            } catch (err) {
              event.currentTarget.value = previous;
              props.onError(err instanceof Error ? err.message : String(err));
            }
          }}
          aria-describedby={props.errorId}
        >
          <Show when={!choices().some((choice) => choice.id === selected())}>
            <option value={selected() ?? ""} disabled>
              {props.unavailable}
            </option>
          </Show>
          <For each={choices()}>{(choice) => <option value={choice.id}>{choice.label}</option>}</For>
        </select>
      </label>
    </Show>
  );
}
