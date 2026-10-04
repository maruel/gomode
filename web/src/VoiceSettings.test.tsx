// Tests saved browser voice preferences and platform availability in shared settings.

import { afterEach, beforeEach, describe, it } from "node:test";
import { fireEvent, render, screen } from "@solidjs/testing-library";
import userEvent from "@testing-library/user-event";
import { expect, vi } from "../tests/expect";
import { installBrowserSpeech } from "../tests/browser-speech";
import VoiceSettings from "./VoiceSettings";
import { voiceSession } from "./VoiceSession";

let restore: () => void;
beforeEach(() => {
  localStorage.clear();
  restore = installBrowserSpeech("Chrome desktop");
  voiceSession.setState((s) => ({ ...s, mode: "cloud", languageTag: "en-US", connected: false, connectStatus: null }));
});
afterEach(() => {
  vi.useRealTimers();
  restore();
});

describe("VoiceSettings", () => {
  it("saves browser speech and explains that it is not necessarily offline", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    const browser = screen.getByRole("radio", { name: "Browser speech" });
    expect(screen.getByRole("group", { name: "Voice mode" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Cloud voice" })).toBeChecked();
    await user.click(browser);
    expect(voiceSession.state.mode).toBe("browser");
    expect(localStorage.getItem("gomode.voiceMode")).toBe("browser");
    expect(browser).toBeChecked();
    expect(browser).toHaveAccessibleDescription(/Only text goes to the voice gateway.*cloud service/);
    expect(screen.getByRole("radio", { name: "Cloud voice" })).toHaveAccessibleDescription(/microphone audio/);
    voiceSession.setState((s) => ({ ...s, connected: true }));
    expect(browser).toBeDisabled();
    expect(screen.getByRole("radio", { name: "Cloud voice" })).toBeDisabled();
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
    await user.clear(inputs[1]!);
    await user.type(inputs[1]!, "de-DE");
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

  it("reports invalid language without changing the saved preference and recovers on edit", () => {
    vi.useFakeTimers();
    render(() => <VoiceSettings />);
    const input = screen.getByRole("textbox");
    fireEvent.input(input, { target: { value: "en_US" } });
    vi.advanceTimersByTime(1000);
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(voiceSession.state.languageTag).toBe("en-US");
    fireEvent.input(input, { target: { value: "de-DE" } });
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
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
      expect(screen.getByRole("alert")).toHaveTextContent("Storage unavailable");
      expect(voiceSession.state.languageTag).toBe("en-US");
    } finally {
      save.mockRestore();
    }
  });

  it("restores the selected radio when saving the voice mode fails", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      await user.click(screen.getByRole("radio", { name: "Browser speech" }));
      expect(screen.getByRole("alert")).toHaveTextContent("Storage unavailable");
      expect(screen.getByRole("radio", { name: "Cloud voice" })).toBeChecked();
      expect(screen.getByRole("radio", { name: "Browser speech" })).not.toBeChecked();
      expect(voiceSession.state.mode).toBe("cloud");
    } finally {
      save.mockRestore();
    }
  });

  it("disables browser speech on ordinary Firefox", () => {
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "Firefox/150" });
    render(() => <VoiceSettings />);
    expect(screen.getByRole("radio", { name: "Browser speech" })).toBeDisabled();
    expect(screen.getByText("Browser speech is unavailable in this browser. Use Cloud voice.")).toBeInTheDocument();
  });
});
