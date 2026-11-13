// Tests saved browser voice preferences and platform availability in shared settings.

import { afterEach, beforeEach, describe, it } from "node:test";
import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { FakeAudioContext } from "../tests/thinking-audio";
import userEvent from "@testing-library/user-event";
import { expect, vi } from "../tests/expect";
import { fakeSynthesis, installBrowserSpeech } from "../tests/browser-speech";
import VoiceSettings from "./VoiceSettings";
import { VoiceSession, voiceSession } from "./VoiceSession";

vi.spyOn(voiceSession, "loadVoiceChoices").mockResolvedValue();

let restore: () => void;
beforeEach(() => {
  localStorage.clear();
  restore = installBrowserSpeech("Chrome desktop");
  voiceSession.setState((s) => ({
    ...s,
    setupMode: "parts",
    choices: [
      {
        id: "chat",
        label: "Chat",
        composable: true,
        transports: ["text", "webrtc"],
        speechModes: [
          { input: "gateway", output: "gateway" },
          { input: "gateway", output: "client" },
          { input: "client", output: "gateway" },
          { input: "client", output: "client" },
        ],
      },
    ],
    choiceIDs: { text: "chat", webrtc: "chat" },
    speechModels: {
      input: [{ id: "whisper", label: "Whisper" }],
      output: [{ id: "kokoro", label: "Kokoro" }],
    },
    speechModelIDs: { input: "whisper", output: "kokoro" },
    choicesStatus: "ready",
    choicesError: null,
    voiceName: null,
    browserVoiceURI: "",
    browserSpeechRate: 1,
    voiceError: null,
    voiceStatus: "idle",
    speechInput: "gateway",
    speechOutput: "gateway",
    languageTag: "en-US",
    connected: false,
    showUsageStats: false,
    connectStatus: null,
  }));
});
afterEach(() => {
  vi.useRealTimers();
  restore();
  vi.unstubAllGlobals();
});

describe("VoiceSettings", () => {
  it("saves browser speaking speed, restores it after reload, and locks it during a session", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    expect(screen.queryByRole("combobox", { name: "Speaking speed" })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByRole("combobox", { name: "Speech" }), "browser");
    const speed = screen.getByRole("combobox", { name: "Speaking speed" });
    expect(speed).toHaveValue("1");
    await user.selectOptions(speed, "1.5");
    expect(localStorage.getItem("gomode.browserSpeechRate")).toBe("1.5");
    expect(new VoiceSession().state.browserSpeechRate).toBe(1.5);
    voiceSession.setState((s) => ({ ...s, connectStatus: "Connecting…" }));
    expect(speed).toBeDisabled();
    expect(() => voiceSession.selectBrowserSpeechRate(2)).toThrow("End the session");
  });

  it("keeps the saved speaking speed when storage fails", async () => {
    const user = userEvent.setup();
    voiceSession.selectSpeechInput("client");
    voiceSession.selectSpeechOutput("client");
    render(() => <VoiceSettings />);
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      const speed = screen.getByRole("combobox", { name: "Speaking speed" });
      await user.selectOptions(speed, "1.5");
      expect(speed).toHaveValue("1");
      expect(voiceSession.state.browserSpeechRate).toBe(1);
      expect(speed).toHaveAccessibleDescription("Storage unavailable");
    } finally {
      save.mockRestore();
    }
  });

  it("rejects invalid speaking speeds and recovers an invalid stored preference", () => {
    for (const rate of [0, 10, Number.NaN, Infinity])
      expect(() => voiceSession.selectBrowserSpeechRate(rate)).toThrow("speaking speed");
    localStorage.setItem("gomode.browserSpeechRate", "NaN");
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    try {
      expect(new VoiceSession().state.browserSpeechRate).toBe(1);
      expect(warn).toHaveBeenCalledOnce();
    } finally {
      warn.mockRestore();
    }
  });

  it("loads browser voices asynchronously, saves a selection, and retains it across settings mounts", async () => {
    const user = userEvent.setup();
    voiceSession.selectSpeechInput("client");
    voiceSession.selectSpeechOutput("client");
    const view = render(() => <VoiceSettings />);
    const select = screen.getByRole("combobox", { name: "Speaking voice" });
    expect(select).toHaveValue("");
    fakeSynthesis.voices = [{ name: "Samantha", lang: "en-US", voiceURI: "samantha" } as SpeechSynthesisVoice];
    fakeSynthesis.dispatchEvent(new Event("voiceschanged"));
    await user.selectOptions(select, "samantha");
    expect(localStorage.getItem("gomode.browserVoiceURI")).toBe("samantha");
    expect(voiceSession.state.browserVoiceURI).toBe("samantha");
    expect(new VoiceSession().state.browserVoiceURI).toBe("samantha");
    view.unmount();
    render(() => <VoiceSettings />);
    expect(screen.getByRole("combobox", { name: "Speaking voice" })).toHaveValue("samantha");
    fakeSynthesis.voices = [...fakeSynthesis.voices];
    fakeSynthesis.dispatchEvent(new Event("voiceschanged"));
    expect(screen.getByRole("combobox", { name: "Speaking voice" })).toHaveValue("samantha");
    await user.selectOptions(screen.getByRole("combobox", { name: "Speech" }), "model:kokoro");
    await user.selectOptions(screen.getByRole("combobox", { name: "Speech" }), "browser");
    expect(screen.getByRole("combobox", { name: "Speaking voice" })).toHaveValue("samantha");
    voiceSession.setState((s) => ({ ...s, connected: true }));
    expect(screen.getByRole("combobox", { name: "Speaking voice" })).toBeDisabled();
  });

  it("filters browser voices by base language across regions and catalog updates", async () => {
    voiceSession.selectSpeechOutput("client");
    fakeSynthesis.voices = [
      { name: "US English", lang: "en-US", voiceURI: "english-us" },
      { name: "UK English", lang: "en-GB", voiceURI: "english-uk" },
      { name: "French", lang: "fr-FR", voiceURI: "french" },
    ] as SpeechSynthesisVoice[];
    render(() => <VoiceSettings />);
    const user = userEvent.setup();
    const voices = () => screen.getByRole("combobox", { name: "Speaking voice" });
    expect(screen.getByRole("option", { name: "US English (en-US)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "UK English (en-GB)" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "French (fr-FR)" })).toBeNull();
    expect(() => voiceSession.selectBrowserVoice("french")).toThrow("available speaking voice");
    await user.selectOptions(voices(), "english-uk");
    voiceSession.selectLanguage("en-AU");
    expect(voices()).toHaveValue("english-uk");
    fakeSynthesis.voices.push({
      name: "Canadian English",
      lang: "EN-CA",
      voiceURI: "english-ca",
    } as SpeechSynthesisVoice);
    fakeSynthesis.dispatchEvent(new Event("voiceschanged"));
    expect(screen.getByRole("option", { name: "Canadian English (EN-CA)" })).toBeInTheDocument();
    voiceSession.selectLanguage("fr-CA");
    expect(screen.queryByRole("option", { name: "US English (en-US)" })).toBeNull();
    expect(screen.queryByRole("option", { name: "UK English (en-GB)" })).toBeNull();
    expect(screen.getByRole("option", { name: "French (fr-FR)" })).toBeInTheDocument();
    await waitFor(() => expect(voices()).toHaveValue("english-uk"));
    expect(screen.getByRole("option", { name: "Choose an available speaking voice" })).toBeDisabled();
    await user.selectOptions(voices(), "french");
    expect(voiceSession.state.browserVoiceURI).toBe("french");
  });

  it("keeps an unavailable saved browser voice visible and allows returning to the default", async () => {
    const user = userEvent.setup();
    voiceSession.setState((s) => ({ ...s, speechInput: "client", speechOutput: "client", browserVoiceURI: "missing" }));
    render(() => <VoiceSettings />);
    const select = screen.getByRole("combobox", { name: "Speaking voice" });
    expect(select).toHaveValue("missing");
    expect(screen.getByRole("option", { name: "Choose an available speaking voice" })).toBeDisabled();
    await user.selectOptions(select, "");
    expect(voiceSession.state.browserVoiceURI).toBe("");
  });

  it("shows the missing saved selection when a voice-list refresh removes the active option", async () => {
    const user = userEvent.setup();
    voiceSession.selectSpeechInput("client");
    voiceSession.selectSpeechOutput("client");
    const first = { name: "First", lang: "en-US", voiceURI: "first" } as SpeechSynthesisVoice;
    fakeSynthesis.voices = [first, { name: "Selected", lang: "en-US", voiceURI: "selected" } as SpeechSynthesisVoice];
    render(() => <VoiceSettings />);
    const select = screen.getByRole("combobox", { name: "Speaking voice" });
    await user.selectOptions(select, "selected");
    for (const voices of [[first], []]) {
      fakeSynthesis.voices = voices;
      fakeSynthesis.dispatchEvent(new Event("voiceschanged"));
      await waitFor(() => expect(select).toHaveValue("selected"));
      expect(screen.getByRole("option", { name: "Choose an available speaking voice" })).toBeDisabled();
      expect(voiceSession.state.browserVoiceURI).toBe("selected");
    }
  });

  it("restores the browser voice selection if saving fails", async () => {
    const user = userEvent.setup();
    voiceSession.selectSpeechInput("client");
    voiceSession.selectSpeechOutput("client");
    fakeSynthesis.voices = [{ name: "Samantha", lang: "en-US", voiceURI: "samantha" } as SpeechSynthesisVoice];
    render(() => <VoiceSettings />);
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      const select = screen.getByRole("combobox", { name: "Speaking voice" });
      await user.selectOptions(select, "samantha");
      expect(select).toHaveValue("");
      expect(voiceSession.state.browserVoiceURI).toBe("");
      expect(select).toHaveAccessibleDescription("Storage unavailable");
    } finally {
      save.mockRestore();
    }
  });

  it("stops the music preview when credits collapse, settings hide, or a voice session starts", async () => {
    FakeAudioContext.instances = [];
    vi.stubGlobal("AudioContext", FakeAudioContext);
    const user = userEvent.setup();
    const [visible, setVisible] = createSignal(true);
    const view = render(() => <VoiceSettings visible={visible()} />);
    await user.click(screen.getByText("Credits", { exact: true }));
    await user.click(await screen.findByRole("button", { name: "Play thinking music" }));
    expect(screen.getByRole("button", { name: "Stop thinking music" })).toBeEnabled();
    const first = FakeAudioContext.instances[0];
    if (!first) throw new Error("Preview did not create an audio context");
    await user.click(screen.getByText("Credits", { exact: true }));
    await waitFor(() => expect(first.close).toHaveBeenCalledOnce());
    await user.click(screen.getByText("Credits", { exact: true }));
    await user.click(await screen.findByRole("button", { name: "Play thinking music" }));
    setVisible(false);
    expect(screen.getByRole("button", { name: "Play thinking music" })).toBeDisabled();
    setVisible(true);
    await user.click(screen.getByRole("button", { name: "Play thinking music" }));
    voiceSession.setState((s) => ({ ...s, connectStatus: "Connecting…" }));
    expect(screen.getByRole("button", { name: "Play thinking music" })).toBeDisabled();
    voiceSession.setState((s) => ({ ...s, connectStatus: null }));
    expect(screen.getByRole("button", { name: "Play thinking music" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Play thinking music" }));
    const last = FakeAudioContext.instances.at(-1);
    view.unmount();
    expect(last?.close).toHaveBeenCalledOnce();
  });

  it("keeps Play available after a playback failure", async () => {
    vi.stubGlobal(
      "AudioContext",
      class {
        constructor() {
          throw new Error("Unavailable");
        }
      },
    );
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    try {
      const user = userEvent.setup();
      render(() => <VoiceSettings />);
      await user.click(screen.getByText("Credits", { exact: true }));
      await user.click(await screen.findByRole("button", { name: "Play thinking music" }));
      expect(screen.getByText("Could not play thinking music.")).toBeVisible();
      expect(screen.getByRole("button", { name: "Play thinking music" })).toBeEnabled();
    } finally {
      warn.mockRestore();
    }
  });
  it("saves recognition and speech output independently and locks both during sessions", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    const input = screen.getByRole("combobox", { name: "Recognition" });
    const output = screen.getByRole("combobox", { name: "Speech" });
    expect(input).not.toHaveAttribute("aria-describedby");
    expect(screen.queryByText(/vendor's cloud service/)).not.toBeInTheDocument();
    await user.selectOptions(output, "browser");
    expect(voiceSession.speech).toEqual({ input: "gateway", output: "client" });
    expect(JSON.parse(localStorage.getItem(`gomode.voiceSetup.${window.location.origin}`) ?? "null")).toMatchObject({
      partsInput: "gateway",
      partsOutput: "client",
    });
    await user.selectOptions(input, "browser");
    expect(voiceSession.speech).toEqual({ input: "client", output: "client" });
    expect(input).toHaveAccessibleDescription(/cloud service/);
    voiceSession.setState((s) => ({ ...s, connected: true }));
    expect(input).toBeDisabled();
    expect(output).toBeDisabled();
  });

  it("migrates saved browser and cloud modes while preserving independent overrides", () => {
    for (const [mode, location] of [
      ["browser", "client"],
      ["cloud", "gateway"],
    ]) {
      localStorage.setItem("gomode.voiceMode", mode!);
      expect(new VoiceSession().speech).toEqual({ input: location, output: location });
    }
    localStorage.setItem("gomode.voiceMode", "browser");
    localStorage.setItem("gomode.voiceInput", "gateway");
    expect(new VoiceSession().speech).toEqual({ input: "gateway", output: "client" });
  });

  it("explains locked voice controls once while display switches stay editable", () => {
    render(() => <VoiceSettings />);
    const notice = "End the voice session to change voice settings.";
    expect(screen.queryByText(notice)).toBeNull();
    voiceSession.setState((s) => ({ ...s, connected: true }));
    expect(screen.getAllByText(notice)).toHaveLength(1);
    for (const radio of screen.getAllByRole("radio")) expect(radio).toBeDisabled();
    for (const select of screen.getAllByRole("combobox")) expect(select).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Voice language" })).toBeDisabled();
    for (const toggle of screen.getAllByRole("switch")) expect(toggle).toBeEnabled();
    expect(screen.getByRole("heading", { name: "Voice" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Display" })).toBeInTheDocument();
  });

  it("toggles a display switch from anywhere on its row", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    await user.click(screen.getByText("Show usage stats"));
    expect(screen.getByRole("switch", { name: "Show usage stats" })).toBeChecked();
    expect(voiceSession.state.showUsageStats).toBe(true);
  });

  it("shows assistant reasoning only after the operator enables it", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    const toggle = screen.getByRole("switch", { name: "Show assistant reasoning" });
    expect(toggle).not.toBeChecked();
    await user.click(toggle);
    expect(toggle).toBeChecked();
    expect(voiceSession.state.showReasoning).toBe(true);
    expect(localStorage.getItem("gomode.voiceReasoning")).toBe("on");
    voiceSession.setState((s) => ({ ...s, connected: true, connectStatus: null }));
    expect(toggle).toBeEnabled();
    await user.click(toggle);
    expect(voiceSession.state.showReasoning).toBe(false);
  });

  it("saves usage visibility and keeps it editable during a call", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    const toggle = screen.getByRole("switch", { name: "Show usage stats" });
    expect(toggle).not.toBeChecked();
    await user.click(toggle);
    expect(toggle).toBeChecked();
    expect(voiceSession.state.showUsageStats).toBe(true);
    expect(localStorage.getItem("gomode.voiceUsageStats")).toBe("on");
    voiceSession.setState((s) => ({ ...s, connected: true, connectStatus: null }));
    expect(toggle).toBeEnabled();
    await user.click(toggle);
    expect(voiceSession.state.showUsageStats).toBe(false);
  });

  it("follows another settings view's saved language without overwriting a draft", async () => {
    const user = userEvent.setup();
    render(() => (
      <>
        <VoiceSettings />
        <VoiceSettings />
      </>
    ));
    const inputs = screen.getAllByRole("textbox");
    voiceSession.selectLanguage("fr-CA");
    expect(inputs[0]).toHaveValue("fr-CA");
    expect(inputs[1]).toHaveValue("fr-CA");
    const second = inputs[1];
    if (!second) throw new Error("second settings view has no textbox");
    await user.clear(second);
    await user.type(second, "de-DE");
    voiceSession.selectLanguage("en-GB");
    expect(inputs[0]).toHaveValue("en-GB");
    expect(inputs[1]).toHaveValue("de-DE");
  });

  it("saves one second after the last edit and normalizes the language", () => {
    vi.useFakeTimers();
    render(() => <VoiceSettings />);
    const input = screen.getByRole("textbox");
    fireEvent.input(input, { target: { value: "fr" } });
    vi.advanceTimersByTime(900);
    fireEvent.input(input, { target: { value: "fr-ca" } });
    vi.advanceTimersByTime(999);
    expect(voiceSession.state.languageTag).toBe("en-US");
    expect(localStorage.getItem("gomode.voiceLanguage")).toBeNull();
    vi.advanceTimersByTime(1);
    expect(voiceSession.state.languageTag).toBe("fr-CA");
    expect(localStorage.getItem("gomode.voiceLanguage")).toBe("fr-CA");
    expect(input).toHaveValue("fr-CA");
  });

  it("flushes the language on blur before the debounce expires", () => {
    vi.useFakeTimers();
    render(() => <VoiceSettings />);
    const input = screen.getByRole("textbox");
    fireEvent.input(input, { target: { value: "fr-CA" } });
    fireEvent.blur(input);
    expect(voiceSession.state.languageTag).toBe("fr-CA");
    expect(localStorage.getItem("gomode.voiceLanguage")).toBe("fr-CA");
  });

  it("shows a blur language error only after the press that moved focus ends", () => {
    vi.useFakeTimers();
    render(() => <VoiceSettings />);
    const input = screen.getByRole("textbox");
    fireEvent.input(input, { target: { value: "en_US" } });
    fireEvent.pointerDown(document.body);
    fireEvent.blur(input);
    vi.advanceTimersByTime(0);
    expect(screen.queryByRole("alert")).toBeNull();
    expect(input).not.toHaveAttribute("aria-invalid", "true");
    fireEvent.pointerUp(document.body);
    expect(screen.queryByRole("alert")).toBeNull();
    vi.advanceTimersByTime(0);
    expect(input).toHaveAccessibleDescription(/en_US/);
    expect(input).toHaveAttribute("aria-invalid", "true");
  });

  it("reports invalid language without changing the saved preference and recovers on edit", () => {
    vi.useFakeTimers();
    render(() => <VoiceSettings />);
    const input = screen.getByRole("textbox");
    fireEvent.input(input, { target: { value: "en_US" } });
    vi.advanceTimersByTime(1000);
    expect(input).toHaveAccessibleDescription(/en_US/);
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(voiceSession.state.languageTag).toBe("en-US");
    fireEvent.input(input, { target: { value: "de-DE" } });
    expect(screen.queryByRole("alert")).toBeNull();
    vi.advanceTimersByTime(1000);
    expect(localStorage.getItem("gomode.voiceLanguage")).toBe("de-DE");
  });

  it("keeps the draft while an active session prevents saving", () => {
    vi.useFakeTimers();
    render(() => <VoiceSettings />);
    const input = screen.getByRole("textbox");
    fireEvent.input(input, { target: { value: "fr-CA" } });
    voiceSession.setState((s) => ({ ...s, connectStatus: "Connecting…" }));
    vi.advanceTimersByTime(1000);
    expect(input).toBeDisabled();
    expect(voiceSession.state.languageTag).toBe("en-US");
    voiceSession.setState((s) => ({ ...s, connectStatus: null }));
    vi.advanceTimersByTime(1000);
    expect(voiceSession.state.languageTag).toBe("fr-CA");
  });

  it("cancels pending saves when the settings view unmounts", () => {
    vi.useFakeTimers();
    const view = render(() => <VoiceSettings />);
    fireEvent.input(screen.getByRole("textbox"), { target: { value: "fr-CA" } });
    view.unmount();
    vi.advanceTimersByTime(1000);
    expect(voiceSession.state.languageTag).toBe("en-US");
  });

  it("reports storage failures without changing the saved language", () => {
    vi.useFakeTimers();
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      render(() => <VoiceSettings />);
      fireEvent.input(screen.getByRole("textbox"), { target: { value: "fr-CA" } });
      vi.advanceTimersByTime(1000);
      expect(screen.getByRole("textbox")).toHaveAccessibleDescription("Storage unavailable");
      expect(voiceSession.state.languageTag).toBe("en-US");
    } finally {
      save.mockRestore();
    }
  });

  it("restores the reasoning checkbox when saving the preference fails", async () => {
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      const user = userEvent.setup();
      render(() => <VoiceSettings />);
      const toggle = screen.getByRole("switch", { name: "Show assistant reasoning" });
      await user.click(toggle);
      expect(toggle).not.toBeChecked();
      expect(toggle).toHaveAccessibleDescription("Could not save the reasoning preference: Storage unavailable");
      expect(voiceSession.state.showReasoning).toBe(false);
    } finally {
      save.mockRestore();
    }
  });

  it("restores the usage checkbox when saving the preference fails", async () => {
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      const user = userEvent.setup();
      render(() => <VoiceSettings />);
      const toggle = screen.getByRole("switch", { name: "Show usage stats" });
      await user.click(toggle);
      expect(toggle).not.toBeChecked();
      expect(toggle).toHaveAccessibleDescription("Could not save the usage preference: Storage unavailable");
      expect(voiceSession.state.showUsageStats).toBe(false);
    } finally {
      save.mockRestore();
    }
  });

  it("restores the selected output when persistence fails", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      await user.selectOptions(screen.getByRole("combobox", { name: "Speech" }), "browser");
      expect(screen.getByRole("combobox", { name: "Speech" })).toHaveAccessibleDescription("Storage unavailable");
      expect(screen.getByRole("combobox", { name: "Speech" })).toHaveValue("model:kokoro");
      expect(voiceSession.speech).toEqual({ input: "gateway", output: "gateway" });
    } finally {
      save.mockRestore();
    }
  });

  it("allows browser TTS on Firefox without browser recognition", async () => {
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "Firefox/150" });
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    const input = screen.getByRole("combobox", { name: "Recognition" });
    const output = screen.getByRole("combobox", { name: "Speech" });
    expect(input.querySelector('option[value="browser"]')).toBeDisabled();
    expect(output.querySelector('option[value="browser"]')).toBeEnabled();
    await user.selectOptions(output, "browser");
    expect(screen.getByRole("combobox", { name: "Speaking speed" })).toBeInTheDocument();
    expect(voiceSession.speech).toEqual({ input: "gateway", output: "client" });
  });

  it("allows browser recognition when synthesis is unavailable", async () => {
    Reflect.deleteProperty(window, "speechSynthesis");
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    expect(screen.getByRole("combobox", { name: "Speech" }).querySelector('option[value="browser"]')).toBeDisabled();
    await user.selectOptions(screen.getByRole("combobox", { name: "Recognition" }), "browser");
    expect(voiceSession.speech).toEqual({ input: "client", output: "gateway" });
  });
});
