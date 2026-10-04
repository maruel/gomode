// Tests saved browser voice preferences and platform availability in shared settings.

import { afterEach, beforeEach, describe, it } from "node:test";
import { render, screen } from "@solidjs/testing-library";
import userEvent from "@testing-library/user-event";
import { expect } from "../tests/expect";
import { installBrowserSpeech } from "../tests/browser-speech";
import VoiceSettings from "./VoiceSettings";
import { voiceSession } from "./VoiceSession";

let restore: () => void;
beforeEach(() => {
  localStorage.clear();
  restore = installBrowserSpeech("Chrome desktop");
  voiceSession.setState((s) => ({ ...s, mode: "cloud", languageTag: "en-US", connected: false, connectStatus: null }));
});
afterEach(() => restore());

describe("VoiceSettings", () => {
  it("saves browser speech and explains that it is not necessarily offline", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    await user.selectOptions(screen.getByRole("combobox", { name: "Voice mode" }), "browser");
    expect(voiceSession.state.mode).toBe("browser");
    expect(localStorage.getItem("gomode.voiceMode")).toBe("browser");
    expect(screen.getByText(/Recognition may use your browser vendor's cloud service/)).toBeInTheDocument();
    voiceSession.setState((s) => ({ ...s, connected: true }));
    expect(screen.getByRole("combobox", { name: "Voice mode" })).toBeDisabled();
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

  it("disables browser speech on ordinary Firefox", () => {
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "Firefox/150" });
    render(() => <VoiceSettings />);
    expect(screen.getByRole("option", { name: "Browser speech" })).toBeDisabled();
    expect(screen.getByText("Browser speech is unavailable in this browser. Use Cloud voice.")).toBeInTheDocument();
  });
});
