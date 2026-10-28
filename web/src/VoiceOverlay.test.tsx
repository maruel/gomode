// Tests browser voice controls, activity status, and connection recovery.

import { afterEach, beforeEach, describe, it } from "node:test";
import { expect, vi } from "../tests/expect";
import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import userEvent from "@testing-library/user-event";
import { createSignal } from "solid-js";
import { deferred } from "../tests/deferred";
import { installBrowserSpeech } from "../tests/browser-speech";

import { TurnStateIdle, TurnStateThinking, TurnStateTranscribing } from "../../sdk/voicegateway/ts/v1/types.gen";
import VoiceOverlay, { defaultVoiceOverlayMessages, type VoiceOverlayMessages } from "./VoiceOverlay";
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
  vi.clearAllMocks();
  localStorage.removeItem("gomode.voiceLanguage");
  voiceSession.setState((s) => ({
    ...s,
    connected: false,
    connectStatus: null,
    connectPhase: null,
    error: null,
    microphoneAccess: null,
    microphoneAccessPending: false,
    audioPlaybackError: null,
    micLevelError: null,
    listening: false,
    speaking: false,
    microphoneMode: "listening",
    activeTool: null,
    turnState: TurnStateIdle,
    transcript: [],
    languageTag: "en-US",
    mode: "cloud",
    choices: [],
    choiceID: null,
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
      expect(screen.getByRole("button", { name: "Connect voice assistant" })).toBeDisabled();
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
    expect(screen.getByText(/browser’s site permissions/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Voice settings" })).toBeEnabled();
    expect(screen.getByRole("combobox", { name: "Microphone" })).toBeEnabled();
    await userEvent.setup().click(screen.getByRole("button", { name: "Enable microphone" }));
    expect(request).toHaveBeenCalledOnce();
    expect(connectMock).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Enable microphone" })).toBeNull();
    expect(screen.getByRole("button", { name: "Connect voice assistant" })).toHaveFocus();
    request.mockRestore();
  });

  it("hides the transcript without clearing it and restores new text from an icon", async () => {
    voiceSession.setState((state) => ({
      ...state,
      connected: true,
      transcript: [{ speaker: "assistant", text: "Review the launch brief.", final: true }],
    }));
    render(() => <VoiceOverlay />);
    const user = userEvent.setup();
    const hide = screen.getByRole("button", { name: "Hide transcript" });
    expect(screen.getByRole("status", { name: "Voice activity" })).not.toContainElement(hide);
    expect(hide.parentElement).toContainElement(screen.getByText("Review the launch brief."));
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
      transcript: [...state.transcript, { speaker: "user", text: "Find the latest revision", final: false }],
    }));
    expect(screen.queryByText("Find the latest revision")).toBeNull();
    await user.click(show);
    expect(screen.getByText("Review the launch brief.")).toBeInTheDocument();
    expect(screen.getByText("Find the latest revision")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Hide transcript" })).toHaveFocus();
  });

  it("restores the transcript from the activity row when no audio picker is available", async () => {
    voiceSession.setState((state) => ({
      ...state,
      connected: true,
      mode: "browser",
      transcript: [{ speaker: "assistant", text: "Ready to help.", final: true }],
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
    expect(screen.getByRole("button", { name: "Hide transcript" })).toHaveFocus();
  });

  it("exposes host-owned server switching without resetting the voice conversation", async () => {
    const [next, setNext] = createSignal("Project Atlas");
    const [available, setAvailable] = createSignal(true);
    const select = vi.fn(() => setNext("Field Notes"));
    voiceSession.setState((state) => ({
      ...state,
      connected: true,
      transcript: [{ speaker: "user", text: "Find the launch brief", final: true }],
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
      mode: "browser",
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

  it("shows the current microphone and explains system-managed output", () => {
    voiceSession.setState((s) => ({
      ...s,
      connected: true,
      audioInputs: [{ deviceId: "usb", kind: "audioinput", label: "USB headset" }],
      selectedInputId: "usb",
    }));
    render(() => <VoiceOverlay />);
    expect(screen.getByRole("combobox", { name: "Microphone" })).toHaveValue("usb");
    expect(screen.getByText("Use your device’s sound settings to change the speaker.")).toBeInTheDocument();
    expect(
      screen
        .getByText("Use your device’s sound settings to change the speaker.")
        .parentElement?.querySelector("[data-icon]")
        ?.getAttribute("data-icon"),
    ).toBe("volumeUp");
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
    expect(microphone).toHaveAccessibleDescription("Mutes during the next reply, then returns to Listening.");
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
    voiceSession.setState((s) => ({ ...s, connected: true, speaking: true, activeTool: tool }));
    render(() => <VoiceOverlay />);
    const controls = screen.getByRole("group", { name: "Voice controls" });
    const activity = screen.getByRole("status", { name: "Voice activity" });
    expect(within(controls).getByRole("button", { name: /^Microphone mode:/ })).toBeInTheDocument();
    expect(within(controls).getByRole("button", { name: "Voice settings" })).toBeInTheDocument();
    expect(within(controls).getByRole("button", { name: "End voice session" })).toBeInTheDocument();
    expect(controls.contains(activity)).toBe(false);
    expect(controls.compareDocumentPosition(activity) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
    expect(activity).toHaveTextContent(`Calling tool: ${tool}…`);
    voiceSession.setState((s) => ({ ...s, activeTool: null, speaking: false }));
    expect(activity).toHaveTextContent("Listening…");
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
    expect(screen.getByRole("radio", { name: "Cloud voice" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Voice language (e.g. en-US)" })).toBeDisabled();
    expect(screen.getByText("End the voice session before changing voice settings.")).toBeInTheDocument();
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
    await user.click(screen.getByRole("radio", { name: "Browser speech" }));
    expect(screen.getByRole("radio", { name: "Browser speech" })).toBeChecked();
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
      await user.click(screen.getByRole("radio", { name: "Browser speech" }));
      expect(screen.getByRole("alert")).toHaveTextContent("Storage unavailable");
      expect(screen.getByRole("textbox")).toHaveAttribute("aria-invalid", "false");
      await user.click(screen.getByRole("button", { name: "Connect voice assistant" }));
      expect(connectMock).toHaveBeenCalledOnce();
      expect(voiceSession.state.mode).toBe("cloud");
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
    voiceSession.setState((state) => ({ ...state, mode: "browser" }));
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
    const messages: VoiceOverlayMessages = {
      ...defaultVoiceOverlayMessages,
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

  it("localizes reconnect status while keeping the actionable error visible and redacted", () => {
    const messages: VoiceOverlayMessages = {
      ...defaultVoiceOverlayMessages,
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
    expect(screen.getByText("HTTP 401 Bearer [redacted]")).toBeInTheDocument();
    expect(screen.queryByText(/secret-token/)).toBeNull();
  });

  it("keeps detailed transport errors for the default English overlay", () => {
    voiceSession.setState((s) => ({
      ...s,
      connectStatus: null,
      error: "Backend-specific failure Bearer secret-token",
    }));
    render(() => <VoiceOverlay />);
    expect(screen.getByText("Backend-specific failure Bearer [redacted]")).toBeInTheDocument();
  });

  it("updates a plain reactive messages prop when the host language changes", () => {
    const [messages, setMessages] = createSignal<VoiceOverlayMessages>(defaultVoiceOverlayMessages);
    render(() => <VoiceOverlay messages={messages()} />);
    expect(screen.getByRole("button", { name: "Connect voice assistant" })).toBeInTheDocument();

    setMessages({ ...defaultVoiceOverlayMessages, connect: "Connecter la voix", voiceAssistant: "Assistant vocal" });
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
