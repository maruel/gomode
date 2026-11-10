// Tests browser voice controls, activity status, and connection recovery.

import { afterEach, beforeEach, describe, it } from "node:test";
import { expect, vi } from "../tests/expect";
import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import userEvent from "@testing-library/user-event";
import { createSignal } from "solid-js";
import { deferred } from "../tests/deferred";
import { installBrowserSpeech } from "../tests/browser-speech";

import { TurnStateIdle, TurnStateThinking, TurnStateTranscribing } from "../../sdk/voicegateway/ts/v2/types.gen";
import VoiceOverlay from "./VoiceOverlay";
import { defaultVoiceMessages, type VoiceMessages } from "./VoiceMessages";
import { notifications } from "./notifications";
import { voiceSession } from "./VoiceSession";

const connectMock = vi.spyOn(voiceSession, "connect").mockResolvedValue(undefined as never);
const disconnectMock = vi.spyOn(voiceSession, "disconnect");
const enumerateDevicesMock = vi.spyOn(voiceSession, "enumerateDevices").mockResolvedValue();
const prepareAudioMock = vi.spyOn(voiceSession, "prepareAudio").mockImplementation(() => {});
const setVoiceActiveMock = vi.spyOn(notifications, "setVoiceActive");

vi.spyOn(voiceSession, "loadVoiceChoices").mockResolvedValue();

let restoreBrowserSpeech: () => void;
beforeEach(() => {
  restoreBrowserSpeech = installBrowserSpeech("Chrome desktop");
  Object.defineProperty(navigator, "mediaDevices", {
    configurable: true,
    value: { getUserMedia: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn() },
  });
  vi.clearAllMocks();
  localStorage.clear();
  voiceSession.setState((s) => ({
    ...s,
    connected: false,
    showUsageStats: false,
    connectStatus: null,
    connectPhase: null,
    error: null,
    errorHistory: [],
    microphoneAccess: null,
    microphoneAccessPending: false,
    audioPlaybackError: null,
    micLevelError: null,
    listening: false,
    speaking: false,
    microphoneMode: "listening",
    activeTool: null,
    toolHistory: [],
    turnState: TurnStateIdle,
    transcript: [],
    usage: null,
    transcriptDraft: null,
    languageTag: "en-US",
    speechInput: "gateway",
    speechOutput: "gateway",
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
    speechModels: {
      input: [{ id: "whisper", label: "Whisper" }],
      output: [{ id: "kokoro", label: "Kokoro" }],
    },
    speechModelIDs: { input: "whisper", output: "kokoro" },
    choiceIDs: { text: "chat", webrtc: "chat" },
    choicesStatus: "ready",
    choicesError: null,
    voiceName: null,
    voiceError: null,
    voiceStatus: "idle",
    audioInputs: [],
    audioOutputs: [],
    selectedInputId: "",
    selectedOutputId: "",
    audioSwitching: null,
    audioDevicesError: null,
    audioInputError: null,
    audioOutputError: null,
  }));
});

afterEach(() => {
  vi.useRealTimers();
  restoreBrowserSpeech();
});

describe("VoiceOverlay status", () => {
  it("follows growing drafts and resumes only when the reader returns to the tail", async () => {
    voiceSession.setState((state) => ({
      ...state,
      connected: true,
      transcript: Array.from({ length: 20 }, (_, index) => ({
        kind: "transcript",
        speaker: "assistant",
        text: `Earlier turn ${index}`,
        final: true,
      })),
      transcriptDraft: { id: "a", text: "Opening words" },
    }));
    render(() => <VoiceOverlay />);
    const list = screen.getByRole("region", { name: "Transcript" });
    let height = 1200;
    Object.defineProperties(list, { scrollHeight: { get: () => height }, clientHeight: { value: 200 } });
    voiceSession.setState((state) => ({ ...state, transcriptDraft: { id: "a", text: "A growing hypothesis" } }));
    await Promise.resolve();
    expect(list.scrollTop).toBe(1200);
    list.scrollTop = 30;
    fireEvent.scroll(list);
    height = 1600;
    voiceSession.setState((state) => ({
      ...state,
      transcriptDraft: { id: "a", text: "The latest hypothesis revises earlier words" },
    }));
    await Promise.resolve();
    expect(list.scrollTop).toBe(30);
    list.scrollTop = 1400;
    fireEvent.scroll(list);
    height = 1800;
    voiceSession.setState((state) => ({ ...state, transcriptDraft: { id: "a", text: "Final words at the tail" } }));
    await Promise.resolve();
    expect(list.scrollTop).toBe(1800);
  });

  it("renders a quiet replaceable draft separately from committed history", () => {
    voiceSession.setState((s) => ({ ...s, connected: true, transcriptDraft: { id: "a", text: "I love pears" } }));
    render(() => <VoiceOverlay />);
    const draft = screen.getByTestId("voice-transcript-draft");
    expect(draft).toHaveTextContent("I love pears");
    expect(draft).toHaveAttribute("aria-live", "off");
    voiceSession.setState((s) => ({ ...s, transcriptDraft: { id: "a", text: "I love bananas" } }));
    expect(draft).toHaveTextContent("I love bananas");
    voiceSession.setState((s) => ({
      ...s,
      transcriptDraft: null,
      transcript: [{ kind: "transcript", speaker: "user", text: "I love bananas.", final: true }],
    }));
    expect(screen.queryByTestId("voice-transcript-draft")).toBeNull();
    expect(screen.getByText("I love bananas.")).toBeVisible();
  });

  it("shows a delivery failure with the existing reconnect action", async () => {
    const message =
      "Couldn't deliver the action's result. Reconnect voice. The action may have completed; check before repeating it.";
    voiceSession.setState((state) => ({ ...state, error: message }));
    render(() => <VoiceOverlay />);
    expect(screen.getByRole("alert")).toHaveTextContent(message);
    await userEvent.setup().click(screen.getByRole("button", { name: "Connect voice assistant" }));
    expect(connectMock).toHaveBeenCalledOnce();
  });
  it("offers a gesture retry for a paused level meter while keeping voice connected", async () => {
    const enable = vi.spyOn(voiceSession, "enableMicrophoneMeter").mockImplementation(async () => {
      voiceSession.setState((s) => ({ ...s, micLevelError: null }));
    });
    try {
      voiceSession.setState((s) => ({ ...s, connected: true, micLevelError: "Microphone level meter is paused." }));
      render(() => <VoiceOverlay />);
      await userEvent.setup().click(screen.getByRole("button", { name: "Enable level meter" }));
      expect(enable).toHaveBeenCalledOnce();
      expect(screen.queryByText("Microphone level meter is paused.")).toBeNull();
      expect(screen.getByRole("button", { name: "End voice session" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "End voice session" })).toHaveFocus();
    } finally {
      enable.mockRestore();
    }
  });

  it("keeps previous failures reviewable after a reconnect", () => {
    voiceSession.setState((s) => ({
      ...s,
      connected: true,
      error: null,
      errorHistory: [{ at: 1_760_000_000_000, message: "Voice turn failed (llm): out of memory" }],
    }));
    render(() => <VoiceOverlay />);
    const summary = screen.getByText("Previous errors (1)");
    const details = summary.closest("details");
    expect(details?.open).toBe(false);
    fireEvent.click(summary);
    expect(details?.open).toBe(true);
    const message = screen.getByText("Voice turn failed (llm): out of memory");
    const entry = message.closest("li");
    expect(entry?.querySelector("time")?.getAttribute("datetime")).toBe(new Date(1_760_000_000_000).toISOString());
  });

  it("does not repeat the failure the panel already shows", () => {
    voiceSession.setState((s) => ({
      ...s,
      error: "Voice gateway unreachable.",
      errorHistory: [{ at: 1_760_000_000_000, message: "Voice gateway unreachable." }],
    }));
    render(() => <VoiceOverlay />);
    expect(screen.getByText("Voice gateway unreachable.")).toBeInTheDocument();
    expect(screen.queryByText("Previous errors (1)")).toBeNull();
  });

  it("shows no error history without a retained failure", () => {
    voiceSession.setState((s) => ({ ...s, error: null, errorHistory: [] }));
    render(() => <VoiceOverlay />);
    expect(screen.queryByText(/Previous errors/)).toBeNull();
  });

  it("retains keyboard focus after an access request is denied and prevents duplicate requests", async () => {
    let finish = () => {};
    const request = vi.spyOn(voiceSession, "requestMicrophoneAccess").mockImplementation(() => {
      voiceSession.setState((state) => ({ ...state, microphoneAccessPending: true }));
      return new Promise<void>((resolve) => {
        finish = () => {
          voiceSession.setState((state) => ({ ...state, microphoneAccessPending: false }));
          resolve();
        };
      });
    });
    try {
      voiceSession.setState((state) => ({ ...state, microphoneAccess: "denied" }));
      render(() => <VoiceOverlay />);
      const user = userEvent.setup();
      const enable = screen.getByRole("button", { name: "Enable microphone" });
      enable.focus();
      await user.keyboard("{Enter}");
      expect(enable).toHaveFocus();
      expect(enable).toHaveAttribute("aria-disabled", "true");
      await user.keyboard("{Enter}");
      expect(request).toHaveBeenCalledOnce();
      expect(screen.queryByRole("button", { name: "Connect voice assistant" })).toBeNull();
      finish();
      await waitFor(() => expect(enable).toHaveAttribute("aria-disabled", "false"));
      expect(enable).toHaveFocus();
    } finally {
      finish();
      request.mockRestore();
    }
  });

  it("keeps controls usable while microphone access is denied and offers recovery", async () => {
    const request = vi.spyOn(voiceSession, "requestMicrophoneAccess").mockImplementation(async () => {
      voiceSession.setState((state) => ({ ...state, microphoneAccess: "granted" }));
    });
    voiceSession.setState((state) => ({ ...state, microphoneAccess: "denied" }));
    render(() => <VoiceOverlay />);
    const microphone = screen.getByRole("button", { name: "Enable microphone" });
    expect(microphone.querySelector('[data-icon="micAlert"]')).not.toBeNull();
    expect(microphone).toHaveAccessibleDescription("Allow microphone access in your browser’s site permissions.");
    expect(microphone).toHaveAccessibleName("Enable microphone");
    expect(microphone).toHaveTextContent("");
    expect(screen.getByRole("button", { name: "Voice settings" })).toBeEnabled();
    expect(screen.getByRole("combobox", { name: "Microphone" })).toBeEnabled();
    await userEvent.setup().click(screen.getByRole("button", { name: "Enable microphone" }));
    expect(request).toHaveBeenCalledOnce();
    expect(connectMock).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Enable microphone" })).toBeNull();
    expect(screen.getByRole("button", { name: "Connect voice assistant" })).toHaveFocus();
    request.mockRestore();
  });

  it("uses the microphone warning for missing browser APIs and reveals guidance on click", async () => {
    Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: undefined });
    const request = vi.spyOn(voiceSession, "requestMicrophoneAccess").mockResolvedValue();
    try {
      render(() => <VoiceOverlay />);
      const microphone = screen.getByRole("button", { name: "Enable microphone" });
      expect(microphone.querySelector('[data-icon="micAlert"]')).not.toBeNull();
      expect(microphone).toHaveAccessibleDescription("Microphone requires HTTPS or a supported browser.");
      await userEvent.setup().click(microphone);
      expect(screen.getByRole("status")).toHaveTextContent("Microphone requires HTTPS or a supported browser.");
      expect(request).not.toHaveBeenCalled();
      expect(connectMock).not.toHaveBeenCalled();
      expect(microphone).toHaveFocus();
    } finally {
      request.mockRestore();
    }
  });

  it("hides the transcript without clearing it and restores new text from an icon", async () => {
    voiceSession.setState((state) => ({
      ...state,
      connected: true,
      transcript: [{ kind: "transcript", speaker: "assistant", text: "Review the launch brief.", final: true }],
    }));
    render(() => <VoiceOverlay />);
    const user = userEvent.setup();
    const hide = screen.getByRole("button", { name: "Hide transcript" });
    expect(screen.getByRole("status", { name: "Voice activity" })).toContainElement(hide);
    expect(screen.getByRole("region", { name: "Transcript" })).not.toContainElement(hide);
    expect(hide.querySelector('[data-icon="close"]')).not.toBeNull();
    await user.click(hide);
    expect(screen.queryByText("Review the launch brief.")).toBeNull();
    const show = within(screen.getByRole("status", { name: "Voice activity" })).getByRole("button", {
      name: "Show transcript",
    });
    expect(show).toHaveFocus();
    expect(show.textContent).toBe("");
    expect(show.querySelector('[data-icon="subtitles"]')).not.toBeNull();
    voiceSession.setState((state) => ({
      ...state,
      transcript: [
        ...state.transcript,
        { kind: "transcript", speaker: "user", text: "Find the latest revision", final: false },
      ],
    }));
    expect(screen.queryByText("Find the latest revision")).toBeNull();
    await user.click(show);
    expect(screen.getByText("Review the launch brief.")).toBeInTheDocument();
    expect(screen.getByText("Find the latest revision")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Transcript" })).toHaveFocus();
  });

  it("collapses ended sessions, retains their history, and expands the next session", async () => {
    voiceSession.setState((state) => ({
      ...state,
      connected: true,
      transcript: [{ kind: "transcript", speaker: "assistant", text: "Session reply", final: true }],
    }));
    render(() => <VoiceOverlay />);
    const user = userEvent.setup();
    expect(screen.getByRole("region", { name: "Transcript" })).toBeVisible();
    // Automatic transport recovery remains part of the same session.
    voiceSession.setState((state) => ({ ...state, connected: false, connectStatus: "Reconnecting" }));
    expect(screen.getByRole("region", { name: "Transcript" })).toBeVisible();
    voiceSession.setState((state) => ({ ...state, connected: true, connectStatus: null }));
    await user.click(screen.getByRole("button", { name: "End voice session" }));
    expect(screen.queryByRole("region", { name: "Transcript" })).toBeNull();
    expect(voiceSession.state.transcript).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "Show transcript" }));
    expect(screen.getByText("Session reply")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Hide transcript" }));
    voiceSession.setState((state) => ({ ...state, connectStatus: "Connecting" }));
    expect(screen.getByRole("region", { name: "Transcript" })).toBeVisible();
    screen.getByRole("region", { name: "Transcript" }).focus();
    voiceSession.setState((state) => ({ ...state, connectStatus: null, error: "Connection lost" }));
    expect(screen.queryByRole("region", { name: "Transcript" })).toBeNull();
    expect(screen.getByRole("button", { name: "Show transcript" })).toHaveFocus();
  });

  it("labels reasoning separately from assistant statements", () => {
    voiceSession.setState((state) => ({
      ...state,
      connected: true,
      transcript: [
        { kind: "reasoning", speaker: "assistant", text: "Weigh both dates.", final: true },
        { kind: "transcript", speaker: "assistant", text: "The first date is earlier.", final: true },
      ],
    }));
    render(() => <VoiceOverlay />);
    const reasoning = screen.getByText("Weigh both dates.");
    const statement = screen.getByText("The first date is earlier.");
    expect(reasoning.textContent).toBe("Reasoning: Weigh both dates.");
    expect(statement.textContent).toBe("Assistant: The first date is earlier.");
  });

  it("restores the transcript from the activity row when no audio picker is available", async () => {
    voiceSession.setState((state) => ({
      ...state,
      connected: true,
      speechInput: "client",
      speechOutput: "client",
      transcript: [{ kind: "transcript", speaker: "assistant", text: "Ready to help.", final: true }],
    }));
    render(() => <VoiceOverlay />);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Hide transcript" }));
    const show = within(screen.getByRole("status", { name: "Voice activity" })).getByRole("button", {
      name: "Show transcript",
    });
    expect(show).toHaveFocus();
    await user.click(show);
    expect(screen.getByText("Ready to help.")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Transcript" })).toHaveFocus();
  });

  it("exposes host-owned server switching without resetting the voice conversation", async () => {
    const [next, setNext] = createSignal("Project Atlas");
    const [available, setAvailable] = createSignal(true);
    const select = vi.fn(() => setNext("Field Notes"));
    voiceSession.setState((state) => ({
      ...state,
      connected: true,
      transcript: [{ kind: "transcript", speaker: "user", text: "Find the launch brief", final: true }],
    }));
    render(() => <VoiceOverlay nextServer={available() ? { name: next(), onSelect: select } : undefined} />);
    const controls = screen.getByRole("group", { name: "Voice controls" });
    const button = within(controls).getByRole("button", { name: "Next server: Project Atlas" });
    expect(button.querySelector("[data-icon]")?.getAttribute("data-icon")).toBe("swapHoriz");
    await userEvent.setup().click(button);
    expect(select).toHaveBeenCalledOnce();
    expect(within(controls).getByRole("button", { name: "Next server: Field Notes" })).toBe(button);
    expect(voiceSession.state.connected).toBe(true);
    expect(screen.getByText("Find the launch brief")).toBeInTheDocument();
    expect(disconnectMock).not.toHaveBeenCalled();
    setAvailable(false);
    expect(within(controls).queryByRole("button", { name: /Next server/ })).toBeNull();
  });

  it("scrolls focused controls into view when the single-row toolbar overflows", async () => {
    voiceSession.setState((state) => ({ ...state, connected: true }));
    render(() => <VoiceOverlay nextServer={{ name: "Project Atlas", onSelect: () => {} }} />);
    const controls = screen.getByRole("group", { name: "Voice controls" });
    Object.defineProperties(controls, {
      clientWidth: { value: 152 },
      scrollWidth: { value: 196 },
    });
    const end = within(controls).getByRole("button", { name: "End voice session" });
    const input = within(controls).getByRole("combobox", { name: "Microphone" });
    const scroll = vi.spyOn(end, "scrollIntoView");
    const inputScroll = vi.spyOn(input, "scrollIntoView");
    const user = userEvent.setup();
    for (let index = 0; index < 3; index++) await user.tab();
    expect(input).toHaveFocus();
    expect(inputScroll).toHaveBeenCalledWith({ block: "nearest", inline: "nearest" });
    await user.tab();
    expect(screen.getByRole("button", { name: "Speaker" })).toHaveFocus();
    await user.tab();
    await user.tab();
    expect(end).toHaveFocus();
    expect(scroll).toHaveBeenCalledWith({ block: "nearest", inline: "nearest" });
    inputScroll.mockRestore();
    scroll.mockRestore();
  });

  it("shows label-based device icons while preserving native selectors and names", async () => {
    Object.defineProperty(HTMLMediaElement.prototype, "setSinkId", { configurable: true, value: vi.fn() });
    const select = vi.spyOn(voiceSession, "selectOutputDevice").mockImplementation(async (id) => {
      voiceSession.setState((s) => ({ ...s, selectedOutputId: id }));
    });
    try {
      voiceSession.setState((s) => ({
        ...s,
        connected: true,
        audioInputs: [{ deviceId: "usb-mic", kind: "audioinput", label: "USB headset microphone" }],
        selectedInputId: "usb-mic",
        audioOutputs: [
          { deviceId: "bt", kind: "audiooutput", label: "AirPods Pro" },
          { deviceId: "speakers", kind: "audiooutput", label: "Built-in speakers" },
          { deviceId: "headset", kind: "audiooutput", label: "Wired headphones" },
          { deviceId: "usb", kind: "audiooutput", label: "USB audio" },
          { deviceId: "phone", kind: "audiooutput", label: "Handset" },
          { deviceId: "unknown", kind: "audiooutput", label: "BTS Studio 3000" },
        ],
      }));
      render(() => <VoiceOverlay />);
      const input = screen.getByRole("combobox", { name: "Microphone" });
      expect(input.parentElement?.querySelector("[data-icon]")?.getAttribute("data-icon")).toBe("headphones");
      const output = screen.getByRole("combobox", { name: "Speaker" });
      const user = userEvent.setup();
      for (const [id, name, icon] of [
        ["bt", "AirPods Pro", "bluetooth"],
        ["speakers", "Built-in speakers", "speaker"],
        ["headset", "Wired headphones", "headphones"],
        ["usb", "USB audio", "usb"],
        ["phone", "Handset", "phoneInTalk"],
        ["unknown", "BTS Studio 3000", "volumeUp"],
      ] as const) {
        await user.selectOptions(output, id);
        expect(output).toHaveValue(id);
        expect(screen.getByRole("option", { name })).toBeInTheDocument();
        expect(output.parentElement?.querySelector("[data-icon]")?.getAttribute("data-icon")).toBe(icon);
      }
      voiceSession.setState((s) => ({ ...s, audioOutputs: [], selectedInputId: "missing" }));
      expect(output.parentElement?.querySelector("[data-icon]")?.getAttribute("data-icon")).toBe("volumeUp");
      expect(input.parentElement?.querySelector("[data-icon]")?.getAttribute("data-icon")).toBe("mic");
      voiceSession.setState((s) => ({
        ...s,
        audioOutputs: [{ deviceId: "default", kind: "audiooutput", label: "Default - Bluetooth headset" }],
        selectedOutputId: "",
      }));
      expect(output.parentElement?.querySelector("[data-icon]")?.getAttribute("data-icon")).toBe("bluetooth");
    } finally {
      select.mockRestore();
      Reflect.deleteProperty(HTMLMediaElement.prototype, "setSinkId");
    }
  });

  it("keeps the normal start control available with an abnormal disconnect error", async () => {
    voiceSession.setState((s) => ({ ...s, error: "Voice gateway unavailable (HTTP 502)." }));
    render(() => <VoiceOverlay />);
    expect(screen.getByRole("alert")).toHaveTextContent("Voice gateway unavailable (HTTP 502).");
    await userEvent.setup().click(screen.getByRole("button", { name: "Connect voice assistant" }));
    expect(connectMock).toHaveBeenCalledOnce();
  });

  it("keeps settings and audio selectors mounted across idle, connecting, active, and failure", async () => {
    render(() => <VoiceOverlay nextServer={{ name: "Project Atlas", onSelect: () => {} }} />);
    const settings = screen.getByRole("button", { name: "Voice settings" });
    const next = screen.getByRole("button", { name: "Next server: Project Atlas" });
    const input = screen.getByRole("combobox", { name: "Microphone" });
    const select = vi.spyOn(voiceSession, "selectInputDevice").mockResolvedValue();
    await userEvent.setup().selectOptions(input, "");
    expect(select).toHaveBeenCalledWith("");
    expect(enumerateDevicesMock).toHaveBeenCalledOnce();
    for (const state of [
      { connectStatus: "Connecting…", connected: false, error: null },
      { connectStatus: null, connected: true, error: null },
      { connectStatus: null, connected: false, error: "Speech failed" },
    ]) {
      voiceSession.setState((s) => ({ ...s, ...state }));
      expect(screen.getByRole("button", { name: "Voice settings" })).toBe(settings);
      expect(screen.getByRole("button", { name: "Next server: Project Atlas" })).toBe(next);
      expect(screen.getByRole("combobox", { name: "Microphone" })).toBe(input);
    }
    select.mockRestore();
  });

  it("shows a shared connection and settings error once, including when settings are open", async () => {
    const error = "The selected voice choice is unavailable";
    voiceSession.setState((s) => ({ ...s, error, choicesError: error }));
    render(() => <VoiceOverlay />);
    expect(screen.getAllByText(error).filter((el) => !el.closest("[hidden]"))).toHaveLength(1);
    await userEvent.setup().click(screen.getByRole("button", { name: "Voice settings" }));
    expect(screen.getAllByText(error).filter((el) => !el.closest("[hidden]"))).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Close voice settings" })).toBeInTheDocument();
  });

  it("does not show cloud audio-device errors during browser speech", () => {
    voiceSession.setState((s) => ({
      ...s,
      connected: true,
      speechInput: "client",
      speechOutput: "client",
      audioDevicesError: "Could not list audio devices: permission denied",
    }));
    render(() => <VoiceOverlay />);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("restores both dropdowns to the confirmed route after a failed switch", async () => {
    Object.defineProperty(HTMLMediaElement.prototype, "setSinkId", { configurable: true, value: vi.fn() });
    const input = vi.spyOn(voiceSession, "selectInputDevice").mockImplementation(async () => {
      voiceSession.setState((s) => ({ ...s, audioInputError: "Microphone permission denied" }));
    });
    const output = vi.spyOn(voiceSession, "selectOutputDevice").mockImplementation(async () => {
      voiceSession.setState((s) => ({ ...s, audioOutputError: "Speaker permission denied" }));
    });
    try {
      voiceSession.setState((s) => ({
        ...s,
        connected: true,
        audioInputs: [{ deviceId: "usb", kind: "audioinput", label: "USB headset" }],
        audioOutputs: [{ deviceId: "bt", kind: "audiooutput", label: "Bluetooth headphones" }],
      }));
      render(() => <VoiceOverlay />);
      const user = userEvent.setup();
      await user.selectOptions(screen.getByRole("combobox", { name: "Microphone" }), "usb");
      expect(screen.getByRole("combobox", { name: "Microphone" })).toHaveValue("");
      await user.selectOptions(screen.getByRole("combobox", { name: "Speaker" }), "bt");
      expect(screen.getByRole("combobox", { name: "Speaker" })).toHaveValue("");
      expect(screen.getByRole("alert")).toHaveTextContent("Speaker permission denied");
      expect(screen.getByRole("alert")).toHaveTextContent("Microphone permission denied");
    } finally {
      input.mockRestore();
      output.mockRestore();
      Reflect.deleteProperty(HTMLMediaElement.prototype, "setSinkId");
    }
  });

  it("shows local audio controls with system routing help even when media elements support output selection", async () => {
    Object.defineProperty(HTMLMediaElement.prototype, "setSinkId", { configurable: true, value: vi.fn() });
    try {
      voiceSession.setState((s) => ({ ...s, speechInput: "client", speechOutput: "client" }));
      render(() => <VoiceOverlay />);
      const devices = screen.getByRole("group", { name: "Audio devices" });
      const mic = within(devices).getByRole("button", { name: "Microphone" });
      const speaker = within(devices).getByRole("button", { name: "Speaker" });
      expect(mic.querySelector('[data-icon="mic"]')).not.toBeNull();
      expect(speaker.querySelector('[data-icon="volumeUp"]')).not.toBeNull();
      expect(within(devices).queryByRole("combobox")).toBeNull();
      const user = userEvent.setup();
      mic.focus();
      await user.keyboard("{Enter}");
      expect(mic).toHaveAttribute("aria-expanded", "true");
      expect(mic).toHaveAccessibleDescription("Use your device’s sound settings to change the microphone.");
      await user.click(speaker);
      expect(mic).toHaveAttribute("aria-expanded", "false");
      expect(speaker).toHaveAccessibleDescription("Use your device’s sound settings to change the speaker.");
      await user.keyboard("{Escape}");
      expect(speaker).toHaveAttribute("aria-expanded", "false");
      expect(speaker).toHaveFocus();
      await user.click(mic);
      voiceSession.setState((s) => ({ ...s, speechInput: "gateway", speechOutput: "gateway" }));
      expect(screen.getByRole("combobox", { name: "Microphone" })).toBeInTheDocument();
      expect(screen.getByRole("combobox", { name: "Speaker" })).toBeInTheDocument();
      expect(screen.queryByText("Use your device’s sound settings to change the microphone.")).toBeNull();
    } finally {
      Reflect.deleteProperty(HTMLMediaElement.prototype, "setSinkId");
    }
  });

  it("shows the current microphone and explains system-managed output", async () => {
    voiceSession.setState((s) => ({
      ...s,
      connected: true,
      audioInputs: [{ deviceId: "usb", kind: "audioinput", label: "USB headset" }],
      selectedInputId: "usb",
    }));
    render(() => <VoiceOverlay />);
    expect(screen.getByRole("combobox", { name: "Microphone" })).toHaveValue("usb");
    const speaker = screen.getByRole("button", { name: "Speaker" });
    expect(speaker.querySelector('[data-icon="volumeUp"]')).not.toBeNull();
    await userEvent.setup().click(speaker);
    expect(screen.getByText("Use your device’s sound settings to change the speaker.")).toBeInTheDocument();
    voiceSession.setState((s) => ({ ...s, audioSwitching: "input", audioInputError: "Microphone permission denied" }));
    expect(screen.getByRole("combobox", { name: "Microphone" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("Microphone permission denied");
  });

  it("labels speaker choices and dispatches the selected output", async () => {
    const select = vi.spyOn(voiceSession, "selectOutputDevice").mockResolvedValue();
    Object.defineProperty(HTMLMediaElement.prototype, "setSinkId", { configurable: true, value: vi.fn() });
    try {
      voiceSession.setState((s) => ({
        ...s,
        connected: true,
        audioOutputs: [{ deviceId: "headset", kind: "audiooutput", label: "Bluetooth headphones" }],
      }));
      render(() => <VoiceOverlay />);
      await userEvent.setup().selectOptions(screen.getByRole("combobox", { name: "Speaker" }), "headset");
      expect(select).toHaveBeenCalledWith("headset");
    } finally {
      select.mockRestore();
      Reflect.deleteProperty(HTMLMediaElement.prototype, "setSinkId");
    }
  });

  it("cycles the microphone directly with pointer and keyboard and resets its clock", async () => {
    voiceSession.setState((s) => ({ ...s, connected: true }));
    render(() => <VoiceOverlay />);
    const user = userEvent.setup();
    const microphone = screen.getByRole("button", { name: "Microphone mode: Listening. Next: Mute once" });
    expect(microphone.querySelector('[data-icon="mic"]')).not.toBeNull();
    expect(microphone.querySelector('[data-icon="schedule"]')).toBeNull();
    await user.click(microphone);
    expect(voiceSession.state.microphoneMode).toBe("muteOnce");
    expect(microphone).toHaveAccessibleName("Microphone mode: Mute once. Next: Muted");
    expect(microphone.querySelector('[data-icon="micOff"]')).not.toBeNull();
    expect(microphone.querySelector('[data-icon="schedule"]')).not.toBeNull();
    expect(microphone).toHaveAccessibleDescription("Muted until this reply ends.");
    voiceSession.setState((s) => ({ ...s, speaking: true }));
    expect(microphone).toHaveAccessibleDescription("Muted until this reply ends.");
    await user.click(microphone);
    expect(voiceSession.state.microphoneMode).toBe("muted");
    expect(microphone).toHaveAccessibleName("Microphone mode: Muted. Next: Listening");
    expect(microphone.querySelector('[data-icon="schedule"]')).toBeNull();
    expect(microphone).toHaveAccessibleDescription("Stays muted until you unmute.");
    await user.keyboard("{Enter}");
    expect(voiceSession.state.microphoneMode).toBe("listening");
    expect(microphone.querySelector('[data-icon="mic"]')).not.toBeNull();
    await user.keyboard(" ");
    expect(voiceSession.state.microphoneMode).toBe("muteOnce");
    voiceSession.setState((s) => ({ ...s, microphoneMode: "listening", speaking: false }));
    expect(microphone).toHaveAccessibleName("Microphone mode: Listening. Next: Mute once");
    expect(microphone.querySelector('[data-icon="schedule"]')).toBeNull();
    expect(microphone).toHaveFocus();
    expect(voiceSession.state.selectedOutputId).toBe("");
  });

  it("keeps controls separate from the complete tool activity and subsequent status", () => {
    const tool = "workspace_search_project_documents_by_author_and_revision_with_extended_filters_and_full_metadata";
    voiceSession.setState((s) => ({
      ...s,
      connected: true,
      speaking: true,
      activeTool: tool,
      toolHistory: [
        { sequence: 1, name: "task_list" },
        { sequence: 2, name: tool },
      ],
    }));
    render(() => <VoiceOverlay />);
    const controls = screen.getByRole("group", { name: "Voice controls" });
    const activity = screen.getByRole("status", { name: "Voice activity" });
    expect(within(controls).getByRole("button", { name: /^Microphone mode:/ })).toBeInTheDocument();
    expect(within(controls).getByRole("button", { name: "Voice settings" })).toBeInTheDocument();
    expect(within(controls).getByRole("button", { name: "End voice session" })).toBeInTheDocument();
    expect(controls.contains(activity)).toBe(false);
    expect(controls.compareDocumentPosition(activity) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
    expect(activity).toHaveTextContent("Calling tool:");
    const history = screen.getByRole("region", { name: "Recent tool calls" });
    expect(
      within(history)
        .getAllByRole("listitem")
        .map((row) => row.textContent),
    ).toEqual(["task_list", tool]);
    expect(history).toHaveAttribute("tabindex", "0");
    voiceSession.setState((s) => ({ ...s, activeTool: null, speaking: false }));
    expect(activity).toHaveTextContent("Listening…");
    expect(history).toHaveTextContent(tool);
    voiceSession.setState((s) => ({ ...s, connected: false }));
    expect(screen.getByRole("region", { name: "Recent tool calls" })).toBe(history);
  });

  it("shows gateway work behind speech and ahead of muting", () => {
    voiceSession.setState((s) => ({
      ...s,
      connected: true,
      microphoneMode: "muted",
      turnState: TurnStateTranscribing,
    }));
    render(() => <VoiceOverlay />);
    expect(screen.getByText("Transcribing…")).toBeInTheDocument();

    voiceSession.setState((s) => ({ ...s, turnState: TurnStateThinking }));
    expect(screen.getByText("Thinking…")).toBeInTheDocument();

    voiceSession.setState((s) => ({ ...s, speaking: true }));
    expect(screen.getByText("Speaking…")).toBeInTheDocument();

    voiceSession.setState((s) => ({ ...s, speaking: false, turnState: TurnStateIdle }));
    expect(screen.getByText("Muted", { selector: "span" })).toBeInTheDocument();
  });
});

describe("VoiceOverlay settings", () => {
  it("expands settings from the compact panel and Escape restores focus", async () => {
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    const trigger = screen.getByRole("button", { name: "Voice settings" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    await user.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    const input = screen.getByRole("textbox", { name: "Voice language (e.g. en-US)" });
    await user.click(input);
    fireEvent.keyDown(input, { key: "F4" });
    expect(connectMock).not.toHaveBeenCalled();
    await user.keyboard("{Escape}");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveFocus();
  });

  it("keeps settings expanded and disables preferences while connecting", async () => {
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    await user.click(screen.getByRole("button", { name: "Voice settings" }));
    voiceSession.setState((s) => ({ ...s, connectStatus: "Connecting…", connectPhase: "setup" }));
    expect(screen.getByRole("combobox", { name: "Speech" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Voice language (e.g. en-US)" })).toBeDisabled();
    expect(screen.getByText("End the voice session to change its setup.")).toBeInTheDocument();
  });

  it("allows correcting the language after a failed connection", async () => {
    voiceSession.setState((s) => ({ ...s, error: "invalid voice.language" }));
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    await user.click(screen.getByRole("button", { name: "Voice settings" }));
    const input = screen.getByRole("textbox", { name: "Voice language (e.g. en-US)" });
    await user.click(input);
    vi.useFakeTimers();
    fireEvent.input(input, { target: { value: "en-GB" } });
    vi.advanceTimersByTime(1000);
    vi.useRealTimers();
    expect(voiceSession.state.languageTag).toBe("en-GB");
    expect(input).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.getByRole("button", { name: "Voice settings" })).toHaveFocus();
    expect(screen.queryByRole("region", { name: "Voice settings" })).not.toBeInTheDocument();
  });

  it("uses the edited language when connecting before the debounce expires", async () => {
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    await user.click(screen.getByRole("button", { name: "Voice settings" }));
    await user.click(screen.getByRole("textbox"));
    vi.useFakeTimers();
    fireEvent.input(screen.getByRole("textbox"), { target: { value: "fr-CA" } });
    vi.useRealTimers();
    await user.click(screen.getByRole("button", { name: "Connect voice assistant" }));
    expect(voiceSession.state.languageTag).toBe("fr-CA");
    expect(connectMock).toHaveBeenCalledOnce();
  });

  it("keeps invalid language errors visible after close and prevents using the old language", async () => {
    voiceSession.setState((state) => ({
      ...state,
      transcript: [{ kind: "transcript", speaker: "assistant", text: "Ready", final: true }],
    }));
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    await user.click(screen.getByRole("button", { name: "Voice settings" }));
    await user.click(screen.getByRole("textbox"));
    vi.useFakeTimers();
    fireEvent.input(screen.getByRole("textbox"), { target: { value: "en_US" } });
    vi.useRealTimers();
    await user.click(screen.getByRole("button", { name: "Close voice settings" }));
    expect(screen.queryByRole("region", { name: "Voice settings" })).toBeNull();
    expect(screen.getByRole("alert")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Connect voice assistant" }));
    expect(connectMock).not.toHaveBeenCalled();
    expect(screen.getByRole("textbox")).toHaveFocus();
    await user.clear(screen.getByRole("textbox"));
    await user.type(screen.getByRole("textbox"), "de-DE");
    await user.click(screen.getByRole("button", { name: "Connect voice assistant" }));
    expect(voiceSession.state.languageTag).toBe("de-DE");
    expect(connectMock).toHaveBeenCalledOnce();
  });

  it("keeps an invalid language blocked after a successful voice mode change", async () => {
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    await user.click(screen.getByRole("button", { name: "Voice settings" }));
    await user.type(screen.getByRole("textbox"), "_invalid");
    await user.selectOptions(screen.getByRole("combobox", { name: "Speech" }), "browser");
    expect(screen.getByRole("combobox", { name: "Speech" })).toHaveValue("browser");
    expect(screen.getByRole("textbox")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("alert")).toHaveTextContent("Invalid language tag");
    await user.click(screen.getByRole("button", { name: "Connect voice assistant" }));
    expect(connectMock).not.toHaveBeenCalled();
    expect(screen.getByRole("textbox")).toHaveFocus();
  });

  it("allows connecting with the saved mode after a mode persistence failure", async () => {
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    await user.click(screen.getByRole("button", { name: "Voice settings" }));
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      await user.selectOptions(screen.getByRole("combobox", { name: "Speech" }), "browser");
      expect(screen.getByRole("alert")).toHaveTextContent("Storage unavailable");
      expect(screen.getByRole("textbox")).toHaveAttribute("aria-invalid", "false");
      await user.click(screen.getByRole("button", { name: "Connect voice assistant" }));
      expect(connectMock).toHaveBeenCalledOnce();
      expect(voiceSession.state.speechInput).toBe("gateway");
    } finally {
      save.mockRestore();
    }
  });

  it("closes settings with the close button, restores focus, and finishes pending saves", async () => {
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    const trigger = screen.getByRole("button", { name: "Voice settings" });
    await user.click(trigger);
    vi.useFakeTimers();
    fireEvent.input(screen.getByRole("textbox"), { target: { value: "fr-CA" } });
    fireEvent.click(screen.getByRole("button", { name: "Close voice settings" }));
    expect(screen.queryByRole("region", { name: "Voice settings" })).toBeNull();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveFocus();
    vi.advanceTimersByTime(1000);
    expect(localStorage.getItem("gomode.voiceLanguage")).toBe("fr-CA");
  });
});

describe("VoiceOverlay connection", () => {
  it("does not revive a pending start when its runtime permission is revoked and restored", async () => {
    const [allowed, setAllowed] = createSignal(true);
    render(() => <VoiceOverlay canStart={allowed} />);
    const enumeration = deferred<void>();
    enumerateDevicesMock.mockReturnValueOnce(enumeration.promise);
    fireEvent.click(screen.getByRole("button", { name: "Connect voice assistant" }));
    setAllowed(false);
    setAllowed(true);
    enumeration.resolve();
    await enumeration.promise;
    expect(connectMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Connect voice assistant" }));
    await waitFor(() => expect(connectMock).toHaveBeenCalledOnce());
  });

  it("lets only the latest Connect action continue after device enumeration", async () => {
    render(() => <VoiceOverlay />);
    const first = deferred<void>();
    const second = deferred<void>();
    enumerateDevicesMock.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const button = screen.getByRole("button", { name: "Connect voice assistant" });
    fireEvent.click(button);
    fireEvent.click(button);
    try {
      first.resolve();
      await first.promise;
      expect(connectMock).not.toHaveBeenCalled();
      second.resolve();
      await second.promise;
      expect(connectMock).toHaveBeenCalledOnce();
    } finally {
      first.resolve();
      second.resolve();
      await Promise.all([first.promise, second.promise]);
    }
  });

  it("does not start a changed voice mode from an older pending Connect action", async () => {
    render(() => <VoiceOverlay />);
    const enumeration = deferred<void>();
    enumerateDevicesMock.mockReturnValueOnce(enumeration.promise);
    fireEvent.click(screen.getByRole("button", { name: "Connect voice assistant" }));
    voiceSession.setState((state) => ({ ...state, speechInput: "client", speechOutput: "client" }));
    enumeration.resolve();
    await enumeration.promise;
    expect(connectMock).not.toHaveBeenCalled();
  });

  it("cannot revive a pending Connect action after an explicit cancel", async () => {
    render(() => <VoiceOverlay />);
    const enumeration = deferred<void>();
    enumerateDevicesMock.mockReturnValueOnce(enumeration.promise);
    fireEvent.click(screen.getByRole("button", { name: "Connect voice assistant" }));
    voiceSession.setState((state) => ({ ...state, connectStatus: "Waiting", connectPhase: "waiting" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel connection" }));
    expect(disconnectMock).toHaveBeenCalledOnce();
    enumeration.resolve();
    await enumeration.promise;
    expect(connectMock).not.toHaveBeenCalled();
  });

  it("calls connect() on mic button click", async () => {
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    await user.click(screen.getByRole("button", { name: "Connect voice assistant" }));
    expect(connectMock).toHaveBeenCalledOnce();
  });

  it("unlocks chime audio before asynchronous device enumeration", async () => {
    let finishEnumeration: () => void = () => {
      throw new Error("Device enumeration did not start");
    };
    enumerateDevicesMock.mockResolvedValueOnce();
    enumerateDevicesMock.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishEnumeration = resolve;
        }),
    );
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);

    const click = user.click(screen.getByRole("button", { name: "Connect voice assistant" }));
    await waitFor(() => expect(prepareAudioMock).toHaveBeenCalledOnce());

    expect(enumerateDevicesMock).toHaveBeenCalledTimes(2);
    expect(connectMock).not.toHaveBeenCalled();
    finishEnumeration();
    await click;
    expect(connectMock).toHaveBeenCalledOnce();
  });

  it("starts voice mode when F4 is pressed", async () => {
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    await user.keyboard("{F4}");
    expect(connectMock).toHaveBeenCalledOnce();
  });

  it("starts voice mode with F4 while an editor is focused", async () => {
    const user = userEvent.setup();
    render(() => (
      <>
        <input aria-label="Prompt" />
        <VoiceOverlay />
      </>
    ));
    await user.click(screen.getByRole("textbox", { name: "Prompt" }));
    await user.keyboard("{F4}");
    expect(connectMock).toHaveBeenCalledOnce();
  });

  it("stops voice mode when F4 is pressed while connected", async () => {
    voiceSession.setState((s) => ({ ...s, connected: true }));
    render(() => <VoiceOverlay />);
    fireEvent.keyDown(document, { key: "F4" });
    await waitFor(() => expect(voiceSession.state.connected).toBe(false));
    expect(disconnectMock).toHaveBeenCalledOnce();
    expect(connectMock).not.toHaveBeenCalled();
  });

  it("renders host-provided controls and connection status", () => {
    const messages: VoiceMessages = {
      ...defaultVoiceMessages,
      connect: "Connecter la voix",
      settingUpWebRTC: "Configuration WebRTC…",
      cancelConnection: "Annuler la connexion",
    };
    voiceSession.setState((s) => ({ ...s, connectStatus: "Setting up WebRTC…", connectPhase: "setup", error: null }));
    render(() => <VoiceOverlay messages={() => messages} />);

    expect(screen.getByText("Configuration WebRTC…")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Annuler la connexion" })).toBeInTheDocument();
    voiceSession.setState((s) => ({ ...s, connectStatus: null }));
    expect(screen.getByRole("button", { name: "Connecter la voix" })).toBeInTheDocument();
  });

  it("localizes reconnect status while keeping the server error verbatim", () => {
    const messages: VoiceMessages = {
      ...defaultVoiceMessages,
      connectionFailed: "Connexion vocale échouée",
      reconnecting: "Reconnexion…",
    };
    voiceSession.setState((s) => ({
      ...s,
      connectStatus: "ICE failed; reconnecting…",
      connectPhase: "reconnecting",
      error: null,
    }));
    render(() => <VoiceOverlay messages={() => messages} />);
    expect(screen.getByText("Reconnexion…")).toBeInTheDocument();
    expect(screen.queryByText(/ICE failed/)).toBeNull();

    voiceSession.setState((s) => ({
      ...s,
      connectStatus: null,
      connectPhase: null,
      error: "HTTP 401 Bearer secret-token",
    }));
    expect(screen.queryByText("Connexion vocale échouée")).toBeNull();
    expect(screen.getByText("HTTP 401 Bearer secret-token")).toBeInTheDocument();
  });

  it("keeps detailed transport errors for the default English overlay", () => {
    voiceSession.setState((s) => ({
      ...s,
      connectStatus: null,
      error: "Backend-specific failure Bearer secret-token",
    }));
    render(() => <VoiceOverlay />);
    expect(screen.getByText("Backend-specific failure Bearer secret-token")).toBeInTheDocument();
  });

  it("updates a plain reactive messages prop when the host language changes", () => {
    const [messages, setMessages] = createSignal<VoiceMessages>(defaultVoiceMessages);
    render(() => <VoiceOverlay messages={messages()} />);
    expect(screen.getByRole("button", { name: "Connect voice assistant" })).toBeInTheDocument();

    setMessages({ ...defaultVoiceMessages, connect: "Connecter la voix", voiceAssistant: "Assistant vocal" });
    expect(screen.getByRole("button", { name: "Connecter la voix" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Assistant vocal" })).toBeInTheDocument();
  });
});

describe("VoiceOverlay notifications", () => {
  it("suppresses notifications for the whole active voice mode", async () => {
    render(() => <VoiceOverlay />);
    await waitFor(() => expect(setVoiceActiveMock).toHaveBeenLastCalledWith(false));

    voiceSession.setState((s) => ({ ...s, connectStatus: "Connecting" }));
    await waitFor(() => expect(setVoiceActiveMock).toHaveBeenLastCalledWith(true));

    voiceSession.setState((s) => ({ ...s, connectStatus: null, connected: true }));
    await waitFor(() => expect(setVoiceActiveMock).toHaveBeenLastCalledWith(true));

    voiceSession.setState((s) => ({ ...s, connected: false }));
    await waitFor(() => expect(setVoiceActiveMock).toHaveBeenLastCalledWith(false));
  });

  it("clears notification suppression when unmounted", async () => {
    const view = render(() => <VoiceOverlay />);
    voiceSession.setState((s) => ({ ...s, connected: true }));
    await waitFor(() => expect(setVoiceActiveMock).toHaveBeenLastCalledWith(true));

    view.unmount();
    await waitFor(() => expect(setVoiceActiveMock).toHaveBeenLastCalledWith(false));
  });
});

it("discloses reported usage after disconnect with zero distinct from unavailable", async () => {
  voiceSession.setState((state) => ({
    ...state,
    error: "Gateway offline",
    transcript: [
      { kind: "transcript", speaker: "user", text: "Hello", final: true },
      { kind: "reasoning", speaker: "assistant", text: "Consider the request", final: true, responseID: "a" },
      {
        kind: "transcript",
        speaker: "assistant",
        text: "Ready",
        final: true,
        responseID: "a",
        responseUsage: { inputTokens: 234, outputTokens: 0, cachedTokens: 0 },
      },
      {
        kind: "transcript",
        speaker: "assistant",
        text: "Unknown input",
        final: true,
        responseID: "b",
        responseUsage: { outputTokens: 12 },
      },
    ],
    usage: {
      totals: { inputTokens: 1234, outputTokens: 0, cachedTokens: 128 },
      latest: {
        kind: "session.usage",
        sessionID: "one",
        provider: "test",
        model: "model",
        contextTokens: 2048,
        contextWindow: 8192,
        totals: { inputTokens: 1234, outputTokens: 0, cachedTokens: 128 },
        lastResponse: { inputTokens: 234, outputTokens: 0, cachedTokens: 0 },
      },
    },
  }));
  render(() => <VoiceOverlay />);
  const user = userEvent.setup();
  const settings = screen.getByRole("button", { name: "Voice settings" });
  await user.click(settings);
  const toggle = screen.getByRole("checkbox", { name: "Show usage stats" });
  expect(toggle).not.toBeChecked();
  await user.click(toggle);
  expect(localStorage.getItem("gomode.voiceUsageStats")).toBe("on");
  expect(within(screen.getByRole("region", { name: "Usage" })).getByText("1,234")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Close voice settings" }));
  const table = screen.getByRole("region", { name: "Usage" });
  expect(within(table).getByText("1,234")).toBeVisible();
  expect(within(table).getByText("0")).toBeVisible();
  expect(within(table).getByText("128")).toBeVisible();
  expect(
    within(table).getByRole("img", { name: "Input tokens: 1,234" }).querySelector('[data-icon="arrowUpward"]'),
  ).not.toBeNull();
  expect(
    within(table).getByRole("img", { name: "Cached input tokens: 128" }).querySelector('[data-icon="cached"]'),
  ).not.toBeNull();
  expect(
    within(table).getByRole("img", { name: "Context tokens: 2,048 / 8,192" }).querySelector('[data-icon="dataUsage"]'),
  ).not.toBeNull();
  await user.click(screen.getByRole("button", { name: "Show transcript" }));
  const inputMetric = screen.getByRole("img", { name: "Input tokens: 234" });
  expect(inputMetric).toBeVisible();
  expect(
    within(screen.getByRole("region", { name: "Transcript" })).getByRole("img", { name: "Output tokens: 0" }),
  ).toBeVisible();
  expect(screen.getByRole("img", { name: "Cached input tokens: 0" })).toBeVisible();
  expect(screen.getByRole("img", { name: "Input tokens: Not reported" })).toHaveTextContent("—");
  expect(inputMetric.parentElement?.parentElement).toHaveTextContent("Assistant: Ready");
  inputMetric.focus();
  voiceSession.setState((state) => ({
    ...state,
    transcript: state.transcript.map((entry) =>
      entry.responseID === "a" && entry.kind === "transcript"
        ? { ...entry, text: "Ready now", responseUsage: { inputTokens: 235, outputTokens: 0, cachedTokens: 0 } }
        : entry,
    ),
  }));
  expect(screen.getByRole("img", { name: "Input tokens: 235" })).toBe(inputMetric);
  expect(inputMetric).toHaveFocus();
  expect(screen.getByText("Input tokens: 235")).toBeVisible();
  expect(screen.getByRole("region", { name: "Transcript" })).not.toContainElement(
    screen.getByText("Input tokens: 235"),
  );
  await user.keyboard("{Escape}");
  expect(screen.queryByText("Input tokens: 235")).toBeNull();
  expect(screen.getByText("test · model")).toBeVisible();
  await user.click(settings);
  await user.click(toggle);
  await user.keyboard("{Escape}");
  expect(screen.queryByRole("region", { name: "Usage" })).toBeNull();
  expect(screen.queryByRole("img", { name: "Input tokens: 235" })).toBeNull();
  expect(voiceSession.state.usage?.totals.inputTokens).toBe(1234);
  await user.click(settings);
  expect(toggle).not.toBeChecked();
  await user.click(toggle);
  await user.keyboard("{Escape}");
  expect(within(screen.getByRole("region", { name: "Usage" })).getByText("1,234")).toBeVisible();
  voiceSession.setState((state) => ({ ...state, error: null, usage: null, connected: true }));
  expect(within(screen.getByRole("region", { name: "Usage" })).getByText("Not reported")).toBeVisible();
});

it("observes transcript reflow when the first response arrives after connecting", async () => {
  const observe = vi.spyOn(ResizeObserver.prototype, "observe");
  try {
    voiceSession.setState((state) => ({ ...state, connected: true }));
    render(() => <VoiceOverlay />);
    expect(screen.queryByRole("region", { name: "Transcript" })).toBeNull();
    voiceSession.setState((state) => ({
      ...state,
      transcript: [{ kind: "transcript", speaker: "assistant", text: "Ready", final: true }],
    }));
    const transcript = await screen.findByRole("region", { name: "Transcript" });
    expect(observe).toHaveBeenCalledWith(transcript);
    expect(transcript).toBeVisible();
  } finally {
    observe.mockRestore();
  }
});

it("follows old-row usage layout updates while preserving manual transcript scroll", async () => {
  voiceSession.setState((state) => ({
    ...state,
    connected: true,
    showUsageStats: true,
    transcript: [
      { kind: "transcript", speaker: "assistant", text: "Earlier", final: true, responseID: "a" },
      { kind: "transcript", speaker: "user", text: "Later", final: true },
    ],
  }));
  render(() => <VoiceOverlay />);
  const transcript = screen.getByRole("region", { name: "Transcript" });
  Object.defineProperties(transcript, {
    scrollHeight: { configurable: true, value: 1000 },
    clientHeight: { configurable: true, value: 100 },
  });
  const correctUsage = (inputTokens: number) =>
    voiceSession.setState((state) => ({
      ...state,
      transcript: state.transcript.map((entry) =>
        entry.responseID === "a" ? { ...entry, responseUsage: { inputTokens, outputTokens: 0 } } : entry,
      ),
    }));
  correctUsage(10);
  await waitFor(() => expect(transcript.scrollTop).toBe(1000));
  transcript.scrollTop = 400;
  fireEvent.scroll(transcript);
  correctUsage(11);
  await Promise.resolve();
  expect(transcript.scrollTop).toBe(400);
  transcript.scrollTop = 900;
  fireEvent.scroll(transcript);
  correctUsage(12);
  await waitFor(() => expect(transcript.scrollTop).toBe(1000));
});

it("keeps the close control outside the scrolling transcript when interim rows are recreated", async () => {
  const entry = { kind: "transcript" as const, speaker: "user" as const, text: "Interim", final: false };
  voiceSession.setState((state) => ({ ...state, connected: true, transcript: [entry, entry] }));
  render(() => <VoiceOverlay />);
  const log = screen.getByRole("region", { name: "Transcript" });
  const close = screen.getByRole("button", { name: "Hide transcript" });
  expect(log).not.toContainElement(close);
  close.focus();
  const old = log.querySelectorAll("[class*='transcriptEntry']")[1];
  voiceSession.setState((state) => ({ ...state, transcript: [entry] }));
  voiceSession.setState((state) => ({ ...state, transcript: [entry, entry] }));
  expect(log.querySelectorAll("[class*='transcriptEntry']")[1]).not.toBe(old);
  expect(close).toHaveFocus();
  await userEvent.click(close);
  expect(log).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Show transcript" })).toHaveFocus();
});
