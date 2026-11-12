// Saved all-in-one or independently selected voice stages shared by overlay and host settings.

import {
  createEffect,
  createMemo,
  createSignal,
  createUniqueId,
  onCleanup,
  Show,
  For,
  on,
  type Accessor,
} from "solid-js";
import { browserRecognitionAvailable, browserSynthesisAvailable, browserVoiceMatchesLanguage } from "./BrowserSpeech";
import { BROWSER_SPEECH_RATES, voiceSession, type VoiceSetupMode } from "./VoiceSession";
import { WebAudioThinkingEarcon } from "./ThinkingEarcon";
import { defaultVoiceSettingsMessages, type VoiceSettingsMessages } from "./VoiceMessages";
import VoiceIcon from "./VoiceIcon";
import controls from "./VoiceControls.module.css";
import styles from "./VoiceSettings.module.css";

export default function VoiceSettings(props: {
  messages?: Partial<VoiceSettingsMessages> | Accessor<Partial<VoiceSettingsMessages>>;
  onError?: (error: string | null) => void;
  onLanguageError?: (error: string | null) => void;
  visible?: boolean;
}) {
  const [creditsOpen, setCreditsOpen] = createSignal(false);
  let loadedGateway: number | undefined;
  createEffect(
    on(
      () =>
        [
          voiceSession.gatewayIdentity(),
          voiceSession.state.connected || voiceSession.state.connectStatus !== null,
          voiceSession.state.choicesStatus === "idle",
        ] as const,
      ([gateway, active, idle]) => {
        if (active || (gateway === loadedGateway && !idle)) return;
        loadedGateway = gateway;
        void voiceSession.loadVoiceChoices();
      },
    ),
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
        voiceSession.state.speechInput,
        voiceSession.state.speechOutput,
      ],
      () => {
        setVoiceSaveError(null);
      },
    ),
  );
  const error = () =>
    languageError() ??
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
  const browserDescriptionId = createUniqueId();
  const active = () => voiceSession.state.connected || voiceSession.state.connectStatus !== null;
  const recognitionSupported = browserRecognitionAvailable();
  const synthesisSupported = browserSynthesisAvailable();
  const [browserVoices, setBrowserVoices] = createSignal<SpeechSynthesisVoice[]>([]);
  createEffect(() => {
    if (voiceSession.state.speechOutput !== "client" || props.visible === false || !synthesisSupported) return;
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
  const matchingBrowserVoices = createMemo(() =>
    browserVoices().filter((voice) => browserVoiceMatchesLanguage(voice, voiceSession.state.languageTag)),
  );
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
  const setupId = createUniqueId();
  const chooseSetup = (mode: VoiceSetupMode): boolean => {
    try {
      voiceSession.selectSetupMode(mode);
      setChoiceError(null);
      return true;
    } catch (error) {
      setChoiceError(error instanceof Error ? error.message : String(error));
      return false;
    }
  };

  return (
    <div class={styles.settings}>
      <Show when={active()}>
        <p>{messages().endSessionToChange}</p>
      </Show>
      <div class={styles.setupTabs} role="tablist" aria-label={messages().voiceSetup}>
        <For each={["all-in-one", "parts"] as const}>
          {(mode) => (
            <button
              type="button"
              role="tab"
              id={`${setupId}-${mode}`}
              aria-selected={voiceSession.state.setupMode === mode}
              aria-controls={`${setupId}-panel`}
              tabIndex={voiceSession.state.setupMode === mode ? 0 : -1}
              disabled={
                active() ||
                voiceSession.state.choicesStatus !== "ready" ||
                (voiceSession.choicesForSetup(mode).length === 0 && voiceSession.state.setupMode !== mode)
              }
              onClick={() => chooseSetup(mode)}
              onKeyDown={(event) => {
                if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
                event.preventDefault();
                const tabs = Array.from(
                  event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]') ?? [],
                );
                const target =
                  event.key === "Home" ? tabs[0] : event.key === "End" ? tabs[1] : tabs[mode === "parts" ? 0 : 1];
                if (target && !target.disabled && chooseSetup(target === tabs[0] ? "all-in-one" : "parts"))
                  target.focus();
              }}
            >
              {mode === "all-in-one" ? messages().allInOne : messages().selectParts}
            </button>
          )}
        </For>
      </div>
      <div
        class={styles.setupPanel}
        role="tabpanel"
        id={`${setupId}-panel`}
        aria-labelledby={`${setupId}-${voiceSession.state.setupMode}`}
      >
        <Show
          when={voiceSession.state.setupMode === "parts"}
          fallback={
            <ChoiceSelect
              label={messages().gatewayModel}
              disabled={active()}
              unavailable={messages().choiceUnavailable}
              errorId={error() === null ? undefined : errorId}
              onError={setChoiceError}
            />
          }
        >
          <SpeechStageSelect
            stage="input"
            label={messages().recognition}
            browser={messages().browserSpeech}
            supported={recognitionSupported}
            unavailable={messages().choiceUnavailable}
            disabled={active()}
            errorId={error() === null ? undefined : errorId}
            descriptionId={voiceSession.state.speechInput === "client" ? browserDescriptionId : undefined}
            onError={setChoiceError}
          />
          <Show when={voiceSession.state.speechInput === "client"}>
            <p id={browserDescriptionId}>
              {recognitionSupported ? messages().browserSpeechPrivacy : messages().browserRecognitionUnavailable}
            </p>
          </Show>
          <ChoiceSelect
            label={messages().languageModel}
            disabled={active()}
            unavailable={messages().choiceUnavailable}
            errorId={error() === null ? undefined : errorId}
            onError={setChoiceError}
          />
          <SpeechStageSelect
            stage="output"
            label={messages().synthesisModel}
            browser={messages().browserSpeech}
            supported={synthesisSupported}
            unavailable={messages().choiceUnavailable}
            disabled={active()}
            errorId={error() === null ? undefined : errorId}
            onError={setChoiceError}
          />
          <Show when={voiceSession.state.speechOutput === "client" && !synthesisSupported}>
            <p>{messages().browserSynthesisUnavailable}</p>
          </Show>
          <Show when={voiceSession.state.speechOutput === "client" && synthesisSupported}>
            <label class={styles.field}>
              {messages().speakingVoice}
              <select
                ref={(select) => {
                  createEffect(() => {
                    matchingBrowserVoices();
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
                    !matchingBrowserVoices().some((voice) => voice.voiceURI === voiceSession.state.browserVoiceURI)
                  }
                >
                  <option value={voiceSession.state.browserVoiceURI} selected disabled>
                    {messages().speakingVoiceUnavailable}
                  </option>
                </Show>
                <option value="" selected={voiceSession.state.browserVoiceURI === ""}>
                  {messages().browserVoiceDefault}
                </option>
                <For each={matchingBrowserVoices()}>
                  {(voice) => (
                    <option value={voice.voiceURI} selected={voiceSession.state.browserVoiceURI === voice.voiceURI}>
                      {voice.name} ({voice.lang})
                    </option>
                  )}
                </For>
              </select>
            </label>
          </Show>
          <Show when={voiceSession.state.speechOutput === "client" && synthesisSupported}>
            <label class={styles.field}>
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
        </Show>
        <Show when={voiceSession.speakingVoices.length > 0}>
          <label class={styles.field}>
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
      </div>
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
      <label class={styles.field}>
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
      <details class={styles.credits} onToggle={(event) => setCreditsOpen(event.currentTarget.open)}>
        <summary>{messages().credits}</summary>
        <Show when={creditsOpen()}>
          <ThinkingMusicPreview active={active()} visible={props.visible !== false} messages={messages} />
        </Show>
        <p>{messages().musicComposer}</p>
        <p>{messages().musicArranger}</p>
      </details>
      {/* Last, so a message appearing on blur moves no control below it. */}
      <p id={errorId} role="alert" class={styles.error}>
        {error()}
      </p>
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
  label: string;
  unavailable: string;
  disabled: boolean;
  errorId: string | undefined;
  onError: (error: string | null) => void;
}) {
  const choices = () => voiceSession.choicesForSetup(voiceSession.state.setupMode);
  const selected = () => voiceSession.state.choiceIDs[voiceSession.transport];
  return (
    <label class={styles.field}>
      {props.label}
      <select
        ref={(select) => {
          createEffect(() => {
            const value = selected() ?? "";
            choices();
            let cancelled = false;
            // Replacing an unavailable option with a late catalog resets the
            // native select even when the saved model ID itself is unchanged.
            queueMicrotask(() => {
              if (!cancelled) select.value = value;
            });
            onCleanup(() => {
              cancelled = true;
            });
          });
        }}
        disabled={props.disabled || voiceSession.state.choicesStatus !== "ready"}
        value={selected() ?? ""}
        onChange={(event) => {
          const previous = selected() ?? "";
          try {
            voiceSession.selectChoice(event.currentTarget.value);
            props.onError(null);
          } catch (err) {
            event.currentTarget.value = previous;
            props.onError(err instanceof Error ? err.message : String(err));
          }
        }}
        aria-describedby={props.errorId}
      >
        <Show when={!choices().some((choice) => choice.id === selected())}>
          <option value={selected() ?? ""} selected disabled>
            {props.unavailable}
          </option>
        </Show>
        <For each={choices()}>{(choice) => <option value={choice.id}>{choice.label}</option>}</For>
      </select>
    </label>
  );
}

function SpeechStageSelect(props: {
  stage: "input" | "output";
  label: string;
  browser: string;
  supported: boolean;
  disabled: boolean;
  unavailable: string;
  errorId: string | undefined;
  descriptionId?: string;
  onError: (error: string | null) => void;
}) {
  const models = () => voiceSession.state.speechModels[props.stage];
  const selected = () =>
    voiceSession.speech[props.stage] === "client"
      ? "browser"
      : voiceSession.composableChoice
        ? `model:${voiceSession.state.speechModelIDs[props.stage] ?? ""}`
        : "gateway";
  const fixed = () =>
    voiceSession.state.choices.find((choice) => choice.id === voiceSession.state.choiceIDs[voiceSession.transport]);
  return (
    <label class={styles.field}>
      {props.label}
      <select
        ref={(select) => {
          createEffect(() => {
            const value = selected();
            models();
            fixed();
            let cancelled = false;
            // A late catalog can insert the selected unavailable option after
            // the browser has already reset the select's value.
            queueMicrotask(() => {
              if (!cancelled) select.value = value;
            });
            onCleanup(() => {
              cancelled = true;
            });
          });
        }}
        disabled={props.disabled || voiceSession.state.choicesStatus === "loading"}
        value={selected()}
        aria-describedby={[props.errorId, props.descriptionId].filter(Boolean).join(" ") || undefined}
        onChange={(event) => {
          const value = event.currentTarget.value;
          try {
            voiceSession.selectSpeechStage(
              props.stage,
              value === "browser" ? "client" : "gateway",
              value.startsWith("model:") ? value.slice(6) : null,
            );
            props.onError(null);
          } catch (error) {
            event.currentTarget.value = selected();
            props.onError(error instanceof Error ? error.message : String(error));
          }
        }}
      >
        <Show when={!voiceSession.composableChoice && fixed()?.transports.includes("webrtc") ? fixed() : null}>
          {(choice) => <option value="gateway">{choice().label}</option>}
        </Show>
        <Show
          when={
            selected() !== "browser" &&
            !models().some((model) => `model:${model.id}` === selected()) &&
            (voiceSession.composableChoice || !fixed()?.transports.includes("webrtc"))
          }
        >
          <option value={selected()} selected disabled>
            {props.unavailable}
          </option>
        </Show>
        <option value="browser" disabled={!props.supported}>
          {props.browser}
        </option>
        <For each={models()}>{(model) => <option value={`model:${model.id}`}>{model.label}</option>}</For>
      </select>
    </label>
  );
}
