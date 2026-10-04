// Tests for the generic voice overlay connection controls.

import { beforeEach, describe, it } from "node:test";
import { expect, vi } from "../tests/expect";
import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import userEvent from "@testing-library/user-event";
import { createSignal } from "solid-js";

import { TurnStateIdle, TurnStateThinking, TurnStateTranscribing } from "../../sdk/voicegateway/ts/v1/types.gen";
import VoiceOverlay, { defaultVoiceOverlayMessages, type VoiceOverlayMessages } from "./VoiceOverlay";
import { notifications } from "./notifications";
import { voiceSession } from "./VoiceSession";

const connectMock = vi.spyOn(voiceSession, "connect").mockResolvedValue(undefined as never);
const disconnectMock = vi.spyOn(voiceSession, "disconnect");
const enumerateDevicesMock = vi.spyOn(voiceSession, "enumerateDevices").mockResolvedValue();
const prepareAudioMock = vi.spyOn(voiceSession, "prepareAudio").mockImplementation(() => {});
const setVoiceActiveMock = vi.spyOn(notifications, "setVoiceActive");

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.removeItem("gomode.voiceLanguage");
  voiceSession.setState((s) => ({
    ...s,
    connected: false,
    connectStatus: null,
    connectPhase: null,
    error: null,
    listening: false,
    speaking: false,
    muted: false,
    activeTool: null,
    turnState: TurnStateIdle,
    transcript: [],
    languageTag: "en-US",
  }));
});

describe("VoiceOverlay status", () => {
  it("shows gateway work behind speech and ahead of muting", () => {
    voiceSession.setState((s) => ({ ...s, connected: true, muted: true, turnState: TurnStateTranscribing }));
    render(() => <VoiceOverlay />);
    expect(screen.getByText("Transcribing…")).toBeInTheDocument();

    voiceSession.setState((s) => ({ ...s, turnState: TurnStateThinking }));
    expect(screen.getByText("Thinking…")).toBeInTheDocument();

    voiceSession.setState((s) => ({ ...s, speaking: true }));
    expect(screen.getByText("Speaking…")).toBeInTheDocument();

    voiceSession.setState((s) => ({ ...s, speaking: false, turnState: TurnStateIdle }));
    expect(screen.getByText("Muted")).toBeInTheDocument();
  });
});

describe("VoiceOverlay language", () => {
  it("allows correcting the language after a failed connection", async () => {
    voiceSession.setState((s) => ({ ...s, error: "invalid voice.language" }));
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    const input = screen.getByRole("textbox", { name: "Voice language (e.g. en-US)" });
    await user.clear(input);
    await user.type(input, "en-GB");
    await user.click(screen.getByRole("button", { name: "Save language" }));
    expect(voiceSession.state.languageTag).toBe("en-GB");
  });

  it("saves a language and rejects invalid input", async () => {
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    const input = screen.getByRole("textbox", { name: "Voice language (e.g. en-US)" });
    await user.clear(input);
    await user.type(input, "fr-CA");
    await user.click(screen.getByRole("button", { name: "Save language" }));
    expect(voiceSession.state.languageTag).toBe("fr-CA");
    expect(localStorage.getItem("gomode.voiceLanguage")).toBe("fr-CA");
    await user.clear(input);
    await user.type(input, "en_US");
    await user.click(screen.getByRole("button", { name: "Save language" }));
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(voiceSession.state.languageTag).toBe("fr-CA");
  });
});

describe("VoiceOverlay connection", () => {
  it("calls connect() on mic button click", async () => {
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);
    await user.click(screen.getByRole("button", { name: /voice/i }));
    expect(connectMock).toHaveBeenCalledOnce();
  });

  it("unlocks chime audio before asynchronous device enumeration", async () => {
    let finishEnumeration: () => void = () => {
      throw new Error("Device enumeration did not start");
    };
    enumerateDevicesMock.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishEnumeration = resolve;
        }),
    );
    const user = userEvent.setup();
    render(() => <VoiceOverlay />);

    const click = user.click(screen.getByRole("button", { name: /voice/i }));
    await waitFor(() => expect(prepareAudioMock).toHaveBeenCalledOnce());

    expect(enumerateDevicesMock).toHaveBeenCalledOnce();
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

  it("uses localized generic text for dynamic errors and reconnect statuses", () => {
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
    expect(screen.getByText("Connexion vocale échouée")).toBeInTheDocument();
    expect(screen.getByText("HTTP 401 Bearer [redacted]")).toBeInTheDocument();
    expect(screen.queryByText(/secret-token/)).toBeNull();
  });

  it("keeps detailed transport errors for the default English overlay", () => {
    voiceSession.setState((s) => ({ ...s, connectStatus: null, error: "Backend-specific failure" }));
    render(() => <VoiceOverlay />);
    expect(screen.getByText("Backend-specific failure")).toBeInTheDocument();
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
