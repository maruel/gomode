// Saved all-in-one or independently selected voice stages shared by overlay and host settings.

import {
  createEffect,
  createMemo,
  createSignal,
  createUniqueId,
  onCleanup,
  onMount,
  Show,
  For,
  Match,
  Switch,
  on,
  type Accessor,
  type JSX,
} from "solid-js";
import { browserRecognitionAvailable, browserSynthesisAvailable, browserVoiceMatchesLanguage } from "./BrowserSpeech";
import { BROWSER_SPEECH_RATES, voiceSession, type SpeechStage, type VoiceSetupMode } from "./VoiceSession";
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
  /** Rendered at the end of the setup row, such as the host's close button. */
  tabAction?: JSX.Element;
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
  // The overlay grows upward from the viewport bottom, so a message shown during
  // a press moves its target before release and the click misses. Show blur
  // errors after the press, one task later: a touch tap dispatches its
  // compatibility mouse events and click in a single task.
  const [shownLanguageError, setShownLanguageError] = createSignal<string | null>(null);
  let pressing = false;
  let reveal: ReturnType<typeof setTimeout> | undefined;
  const scheduleReveal = () => {
    clearTimeout(reveal);
    reveal = pressing ? undefined : setTimeout(() => setShownLanguageError(languageError()));
  };
  createEffect(() => {
    if (languageError() === null) {
      clearTimeout(reveal);
      setShownLanguageError(null);
    } else scheduleReveal();
  });
  onMount(() => {
    const press = () => {
      pressing = true;
    };
    const release = () => {
      pressing = false;
      if (languageError() !== null && shownLanguageError() !== languageError()) scheduleReveal();
    };
    document.addEventListener("pointerdown", press, true);
    document.addEventListener("pointerup", release, true);
    document.addEventListener("pointercancel", release, true);
    onCleanup(() => {
      clearTimeout(reveal);
      document.removeEventListener("pointerdown", press, true);
      document.removeEventListener("pointerup", release, true);
      document.removeEventListener("pointercancel", release, true);
    });
  });
  // One save failure at a time among setup controls: a later successful save
  // shows that storage works again, so it clears whichever control failed.
  const [setupSaveError, setSetupSaveError] = createSignal<{ owner: SetupControl; message: string } | null>(null);
  const [voiceSaveError, setVoiceSaveError] = createSignal<string | null>(null);
  const [browserVoiceError, setBrowserVoiceError] = createSignal<string | null>(null);
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
        setBrowserVoiceError(null);
      },
    ),
  );
  const saveFailure = (owner: SetupControl) => {
    const failure = setupSaveError();
    return failure?.owner === owner ? failure.message : null;
  };
  const onSetupSave = (owner: SetupControl) => (error: string | null) =>
    setSetupSaveError(error === null ? null : { owner, message: error });
  const catalogError = () => (voiceSession.state.choicesStatus === "error" ? voiceSession.state.choicesError : null);
  const resolutionError = (stage: SpeechStage | null) =>
    voiceSession.state.choicesStatus !== "error" && voiceSession.state.choicesErrorStage === stage
      ? voiceSession.state.choicesError
      : null;
  const speakingVoicesShown = () => voiceSession.speakingVoices.length > 0;
  // Without a speaking-voice picker, the model picker owns the session's voice failure.
  const unplacedVoiceError = () => (speakingVoicesShown() ? null : voiceSession.state.voiceError);
  const modelError = () => saveFailure("model") ?? resolutionError(null) ?? unplacedVoiceError();
  const speakingVoiceError = () => voiceSaveError() ?? voiceSession.state.voiceError;
  const error = () =>
    shownLanguageError() ??
    usageError() ??
    reasoningError() ??
    setupSaveError()?.message ??
    voiceSaveError() ??
    browserVoiceError() ??
    speedSaveError() ??
    voiceSession.state.choicesError ??
    voiceSession.state.voiceError;
  createEffect(() => props.onError?.(error()));
  createEffect(() => props.onLanguageError?.(languageError()));
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
  const setupName = createUniqueId();
  const setupErrorId = createUniqueId();
  const voiceHeadingId = createUniqueId();
  const displayHeadingId = createUniqueId();
  const browserVoiceId = createUniqueId();
  const speedId = createUniqueId();
  const speakingVoiceId = createUniqueId();
  const languageId = createUniqueId();
  const reasoningErrorId = createUniqueId();
  const usageErrorId = createUniqueId();
  const setupRadios: Partial<Record<VoiceSetupMode, HTMLInputElement>> = {};
  const chooseSetup = (mode: VoiceSetupMode) => {
    try {
      voiceSession.selectSetupMode(mode);
      setSetupSaveError(null);
    } catch (error) {
      // A native radio checks itself before change; restore the effective setup.
      for (const [option, radio] of Object.entries(setupRadios))
        radio.checked = option === voiceSession.state.setupMode;
      setSetupSaveError({ owner: "setup", message: error instanceof Error ? error.message : String(error) });
    }
  };
  const retry = () => (
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
  );

  return (
    <div class={styles.settings}>
      <section class={styles.group} aria-labelledby={voiceHeadingId}>
        <h2 id={voiceHeadingId} class={styles.heading}>
          {messages().voiceSection}
        </h2>
        <Show when={active()}>
          <p class={styles.notice}>{messages().endSessionToChange}</p>
        </Show>
        <div class={styles.field}>
          <div class={styles.setupRow}>
            <div
              class={styles.segmented}
              role="radiogroup"
              aria-label={messages().voiceSetup}
              aria-describedby={saveFailure("setup") === null ? undefined : setupErrorId}
            >
              <For each={["all-in-one", "parts"] as const}>
                {(mode) => (
                  <label class={styles.segment}>
                    <input
                      ref={(radio) => {
                        setupRadios[mode] = radio;
                      }}
                      type="radio"
                      name={setupName}
                      value={mode}
                      checked={voiceSession.state.setupMode === mode}
                      disabled={
                        active() ||
                        voiceSession.state.choicesStatus !== "ready" ||
                        (voiceSession.choicesForSetup(mode).length === 0 && voiceSession.state.setupMode !== mode)
                      }
                      onChange={() => chooseSetup(mode)}
                    />
                    <span class={styles.segmentCheck}>
                      <VoiceIcon name="check" />
                    </span>
                    <span class={styles.segmentLabel}>
                      {mode === "all-in-one" ? messages().allInOne : messages().separateStages}
                    </span>
                  </label>
                )}
              </For>
            </div>
            {props.tabAction}
          </div>
          <FieldError id={setupErrorId} message={saveFailure("setup")} />
        </div>
        <Switch>
          <Match when={voiceSession.state.choicesStatus === "loading"}>
            <p class={styles.statusLine} role="status">
              {messages().choicesLoading}
            </p>
          </Match>
          <Match when={catalogError()}>
            {(message) => (
              <div class={styles.statusLine}>
                <p class={styles.error} role="alert">
                  {message()}
                </p>
                {retry()}
              </div>
            )}
          </Match>
          <Match when={voiceSession.state.setupMode === "parts"}>
            <SpeechStageSelect
              stage="input"
              label={messages().recognition}
              browser={messages().browserSpeech}
              supported={recognitionSupported}
              unavailable={messages().choiceUnavailable}
              disabled={active()}
              error={saveFailure("input") ?? resolutionError("input")}
              hint={
                <Show when={voiceSession.state.speechInput === "client"}>
                  <p id={browserDescriptionId} class={styles.hint}>
                    {recognitionSupported ? messages().browserSpeechPrivacy : messages().browserRecognitionUnavailable}
                  </p>
                </Show>
              }
              hintId={voiceSession.state.speechInput === "client" ? browserDescriptionId : undefined}
              onSave={onSetupSave("input")}
            />
            <ChoiceSelect
              label={messages().languageModel}
              disabled={active()}
              unavailable={messages().choiceUnavailable}
              error={modelError()}
              retry={voiceSession.state.voiceStatus === "error" && unplacedVoiceError() !== null ? retry() : undefined}
              onSave={onSetupSave("model")}
            />
            <SpeechStageSelect
              stage="output"
              label={messages().synthesisModel}
              browser={messages().browserSpeech}
              supported={synthesisSupported}
              unavailable={messages().choiceUnavailable}
              disabled={active()}
              error={saveFailure("output") ?? resolutionError("output")}
              hint={
                <Show when={voiceSession.state.speechOutput === "client" && !synthesisSupported}>
                  <p class={styles.hint}>{messages().browserSynthesisUnavailable}</p>
                </Show>
              }
              onSave={onSetupSave("output")}
            />
            <Show when={voiceSession.state.speechOutput === "client" && synthesisSupported}>
              <Field label={messages().speakingVoice} for={browserVoiceId} error={browserVoiceError()}>
                {(errorId) => (
                  <Picker>
                    <select
                      id={browserVoiceId}
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
                          setBrowserVoiceError(null);
                        } catch (err) {
                          event.currentTarget.value = voiceSession.state.browserVoiceURI;
                          setBrowserVoiceError(err instanceof Error ? err.message : String(err));
                        }
                      }}
                      aria-describedby={errorId()}
                    >
                      <Show
                        when={
                          voiceSession.state.browserVoiceURI !== "" &&
                          !matchingBrowserVoices().some(
                            (voice) => voice.voiceURI === voiceSession.state.browserVoiceURI,
                          )
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
                          <option
                            value={voice.voiceURI}
                            selected={voiceSession.state.browserVoiceURI === voice.voiceURI}
                          >
                            {voice.name} ({voice.lang})
                          </option>
                        )}
                      </For>
                    </select>
                  </Picker>
                )}
              </Field>
              <Field label={messages().speakingSpeed} for={speedId} error={speedSaveError()}>
                {(errorId) => (
                  <Picker>
                    <select
                      id={speedId}
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
                      aria-describedby={errorId()}
                    >
                      <For each={BROWSER_SPEECH_RATES}>
                        {(rate) => (
                          <option value={rate}>{rate === 1 ? messages().speakingSpeedNormal : `${rate}×`}</option>
                        )}
                      </For>
                    </select>
                  </Picker>
                )}
              </Field>
            </Show>
          </Match>
          <Match when={true}>
            <ChoiceSelect
              label={messages().gatewayModel}
              disabled={active()}
              unavailable={messages().choiceUnavailable}
              error={modelError()}
              retry={voiceSession.state.voiceStatus === "error" && unplacedVoiceError() !== null ? retry() : undefined}
              onSave={onSetupSave("model")}
            />
          </Match>
        </Switch>
        <Show when={catalogError() === null && voiceSession.state.choicesStatus !== "loading" && speakingVoicesShown()}>
          <Field
            label={messages().speakingVoice}
            for={speakingVoiceId}
            error={speakingVoiceError()}
            retry={voiceSession.state.voiceStatus === "error" ? retry() : undefined}
          >
            {(errorId) => (
              <Picker>
                <select
                  id={speakingVoiceId}
                  disabled={
                    active() ||
                    voiceSession.state.choicesStatus !== "ready" ||
                    voiceSession.state.voiceStatus !== "ready"
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
                  aria-describedby={errorId()}
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
              </Picker>
            )}
          </Field>
        </Show>
        <Field label={messages().voiceLanguage} for={languageId} error={shownLanguageError()}>
          {(errorId) => (
            <input
              id={languageId}
              class={styles.textField}
              type="text"
              disabled={active()}
              value={tag()}
              // The example shows the BCP 47 format once the field is cleared.
              placeholder={messages().voiceLanguageExample}
              onInput={(event) => {
                setTag(event.currentTarget.value);
                setLanguageError(null);
              }}
              // Flush before a close or connect click can use the saved preference.
              onBlur={saveLanguage}
              aria-invalid={shownLanguageError() !== null}
              aria-describedby={errorId()}
              spellcheck={false}
              autocapitalize="none"
            />
          )}
        </Field>
      </section>
      <section class={styles.group} aria-labelledby={displayHeadingId}>
        <h2 id={displayHeadingId} class={styles.heading}>
          {messages().displaySection}
        </h2>
        <div>
          <label class={styles.switchRow}>
            <span>{messages().showReasoning}</span>
            <input
              type="checkbox"
              role="switch"
              class={styles.switch}
              checked={voiceSession.state.showReasoning}
              aria-describedby={reasoningError() === null ? undefined : reasoningErrorId}
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
          </label>
          <FieldError id={reasoningErrorId} message={reasoningError()} />
        </div>
        <div>
          <label class={styles.switchRow}>
            <span>{messages().showUsageStats}</span>
            <input
              type="checkbox"
              role="switch"
              class={styles.switch}
              checked={voiceSession.state.showUsageStats}
              aria-describedby={usageError() === null ? undefined : usageErrorId}
              onChange={(event) => {
                const next = event.currentTarget.checked;
                try {
                  voiceSession.setShowUsageStats(next);
                  setUsageError(null);
                } catch (err) {
                  event.currentTarget.checked = !next;
                  setUsageError(
                    `Could not save the usage preference: ${err instanceof Error ? err.message : String(err)}`,
                  );
                }
              }}
            />
          </label>
          <FieldError id={usageErrorId} message={usageError()} />
        </div>
      </section>
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

type SetupControl = "input" | "model" | "output" | "setup";

/** A stacked label, its control, optional hint, and the error the control owns. */
function Field(props: {
  label: string;
  for: string;
  error: string | null;
  hint?: JSX.Element;
  retry?: JSX.Element;
  /** Receives the error's ID while an error is shown, for the control's aria-describedby. */
  children: (errorId: Accessor<string | undefined>) => JSX.Element;
}) {
  const errorId = createUniqueId();
  return (
    <div class={styles.field}>
      <label class={styles.label} for={props.for}>
        {props.label}
      </label>
      {props.children(() => (props.error === null ? undefined : errorId))}
      {props.hint}
      <FieldError id={errorId} message={props.error} retry={props.retry} />
    </div>
  );
}

function FieldError(props: { id: string; message: string | null; retry?: JSX.Element }) {
  return (
    <Show when={props.message}>
      {(message) => (
        <div class={styles.errorLine}>
          <p id={props.id} class={styles.error} role="alert">
            {message()}
          </p>
          {props.retry}
        </div>
      )}
    </Show>
  );
}

/** An outlined native select with a Material dropdown arrow. */
function Picker(props: { children: JSX.Element }) {
  return (
    <div class={styles.picker}>
      {props.children}
      <span class={styles.pickerArrow}>
        <VoiceIcon name="arrowDropDown" />
      </span>
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
  error: string | null;
  retry: JSX.Element | undefined;
  onSave: (error: string | null) => void;
}) {
  const id = createUniqueId();
  const choices = () => voiceSession.choicesForSetup(voiceSession.state.setupMode);
  const selected = () => voiceSession.state.choiceIDs[voiceSession.transport];
  return (
    <Field label={props.label} for={id} error={props.error} retry={props.retry}>
      {(errorId) => (
        <Picker>
          <select
            id={id}
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
                props.onSave(null);
              } catch (err) {
                event.currentTarget.value = previous;
                props.onSave(err instanceof Error ? err.message : String(err));
              }
            }}
            aria-describedby={errorId()}
          >
            <Show when={!choices().some((choice) => choice.id === selected())}>
              <option value={selected() ?? ""} selected disabled>
                {props.unavailable}
              </option>
            </Show>
            <For each={choices()}>{(choice) => <option value={choice.id}>{choice.label}</option>}</For>
          </select>
        </Picker>
      )}
    </Field>
  );
}

function SpeechStageSelect(props: {
  stage: SpeechStage;
  label: string;
  browser: string;
  supported: boolean;
  disabled: boolean;
  unavailable: string;
  error: string | null;
  hint: JSX.Element;
  hintId?: string;
  onSave: (error: string | null) => void;
}) {
  const id = createUniqueId();
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
    <Field label={props.label} for={id} error={props.error} hint={props.hint}>
      {(errorId) => (
        <Picker>
          <select
            id={id}
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
            aria-describedby={[errorId(), props.hintId].filter(Boolean).join(" ") || undefined}
            onChange={(event) => {
              const value = event.currentTarget.value;
              try {
                voiceSession.selectSpeechStage(
                  props.stage,
                  value === "browser" ? "client" : "gateway",
                  value.startsWith("model:") ? value.slice(6) : null,
                );
                props.onSave(null);
              } catch (error) {
                event.currentTarget.value = selected();
                props.onSave(error instanceof Error ? error.message : String(error));
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
        </Picker>
      )}
    </Field>
  );
}
