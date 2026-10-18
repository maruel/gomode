// Tests gateway choice discovery, selection, persistence, and settings request ownership.

import { afterEach, beforeEach, describe, it } from "node:test";
import { render, screen, waitFor } from "@solidjs/testing-library";
import userEvent from "@testing-library/user-event";
import { expect, vi } from "../tests/expect";
import { installBrowserSpeech } from "../tests/browser-speech";
import VoiceSettings from "./VoiceSettings";
import { configureVoiceGateway, voiceSession, VoiceSession } from "./VoiceSession";

const metadata = {
  choices: [
    { id: "gemini", label: "Gemini Live", transports: ["webrtc"], voices: ["Aoede", "Orus"], defaultVoice: "Orus" },
    {
      id: "remote",
      label: "Remote LLM",
      transports: ["text", "webrtc"],
      voices: ["Kore", "Leda"],
      defaultVoice: "Kore",
    },
    { id: "local", label: "Local LLM", transports: ["text"] },
  ],
  defaultChoiceID: "gemini",
};
const originalFetch = globalThis.fetch;
let restore: () => void;
beforeEach(() => {
  localStorage.clear();
  restore = installBrowserSpeech("Chrome desktop");
  voiceSession.disconnect();
  voiceSession.setState((s) => ({ ...s, mode: "cloud" }));
  configureVoiceGateway("https://voice.example", null);
  globalThis.fetch = async (input) => {
    if (!String(input).endsWith("/voice/choices")) throw new Error(`Unexpected request ${String(input)}`);
    return new Response(JSON.stringify(metadata));
  };
});
afterEach(() => {
  voiceSession.disconnect();
  globalThis.fetch = originalFetch;
  restore();
});

async function readySelect(): Promise<HTMLElement> {
  const select = screen.getByRole("combobox", { name: "Voice choice" });
  await waitFor(() => expect(select).toBeEnabled());
  return select;
}

describe("voice choices", () => {
  it("filters by transport, saves explicit choices per gateway, and restores them", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    const select = await readySelect();
    expect(select).toHaveValue("gemini");
    expect(screen.queryByRole("option", { name: "Local LLM" })).not.toBeInTheDocument();
    await user.selectOptions(select, "remote");
    expect(localStorage.getItem("gomode.voiceChoice.https://voice.example")).toBe("remote");
    const restored = new VoiceSession();
    await restored.loadVoiceChoices();
    expect(restored.state.choiceID).toBe("remote");
    configureVoiceGateway("https://other.example", null);
    await waitFor(() => expect(select).toHaveValue("gemini"));
    configureVoiceGateway("https://voice.example", null);
    await waitFor(() => expect(select).toHaveValue("remote"));
    await user.click(screen.getByRole("radio", { name: "Browser speech" }));
    expect(screen.queryByRole("option", { name: "Gemini Live" })).not.toBeInTheDocument();
    await user.selectOptions(select, "local");
    expect(localStorage.getItem("gomode.voiceChoice.https://voice.example")).toBe("local");
    voiceSession.setState((s) => ({ ...s, connectStatus: "Connecting…" }));
    expect(select).toBeDisabled();
  });

  it("saves speaking voices per gateway and choice and hides them during browser speech", async () => {
    render(() => <VoiceSettings />);
    await readySelect();
    const user = userEvent.setup();
    const speaking = () => screen.getByRole("combobox", { name: "Speaking voice" });
    expect(speaking()).toHaveValue("Orus");
    await user.selectOptions(speaking(), "Aoede");
    const restored = new VoiceSession();
    await restored.loadVoiceChoices();
    expect(restored.state.voiceName).toBe("Aoede");
    await user.selectOptions(screen.getByRole("combobox", { name: "Voice choice" }), "remote");
    expect(speaking()).toHaveValue("Kore");
    await user.selectOptions(speaking(), "Leda");
    await user.selectOptions(screen.getByRole("combobox", { name: "Voice choice" }), "gemini");
    expect(speaking()).toHaveValue("Aoede");
    configureVoiceGateway("https://other.example", null);
    await readySelect();
    expect(speaking()).toHaveValue("Orus");
    configureVoiceGateway("https://voice.example", null);
    await readySelect();
    expect(speaking()).toHaveValue("Aoede");
    await user.click(screen.getByRole("radio", { name: "Browser speech" }));
    expect(screen.queryByRole("combobox", { name: "Speaking voice" })).not.toBeInTheDocument();
    expect(voiceSession.state.voiceName).toBe("");
    await user.click(screen.getByRole("radio", { name: "Cloud voice" }));
    expect(screen.getByRole("combobox", { name: "Speaking voice" })).toHaveValue("Aoede");
    voiceSession.setState((s) => ({ ...s, connectStatus: "Connecting…" }));
    expect(screen.getByRole("combobox", { name: "Speaking voice" })).toBeDisabled();
    expect(() => voiceSession.selectSpeakingVoice("Orus")).toThrow("End the session");
  });

  it("recovers failed speaking-voice reads without replacing a removed saved voice", async () => {
    const key = 'gomode.speakingVoice.["https://voice.example","gemini"]';
    localStorage.setItem(key, "Retired");
    const read = window.Storage.prototype.getItem;
    const load = vi.spyOn(window.Storage.prototype, "getItem").mockImplementation(function (this: Storage, key) {
      if (key.startsWith("gomode.speakingVoice.")) throw new Error("Voice storage unavailable");
      return read.call(this, key);
    });
    const prepare = vi.spyOn(voiceSession, "prepareAudio").mockImplementation(() => {});
    try {
      render(() => <VoiceSettings />);
      await readySelect();
      expect(screen.getByRole("combobox", { name: "Speaking voice" })).toBeDisabled();
      expect(screen.getByRole("alert")).toHaveTextContent("Voice storage unavailable");
      await voiceSession.connect();
      expect(voiceSession.state.error).toBe("Voice storage unavailable");
      load.mockRestore();
      await userEvent.setup().click(screen.getByRole("button", { name: "Retry loading voice choices" }));
      await waitFor(() => expect(screen.getByRole("combobox", { name: "Speaking voice" })).toBeEnabled());
      const speaking = screen.getByRole("combobox", { name: "Speaking voice" });
      expect(speaking).toHaveValue("Retired");
      expect(screen.getByRole("alert")).toHaveTextContent("saved speaking voice is unavailable");
      expect(localStorage.getItem(key)).toBe("Retired");
      await userEvent.setup().selectOptions(speaking, "Aoede");
      expect(speaking).toHaveValue("Aoede");
      expect(localStorage.getItem(key)).toBe("Aoede");
      expect(screen.getByRole("alert")).toBeEmptyDOMElement();
    } finally {
      load.mockRestore();
      prepare.mockRestore();
    }
  });

  it("keeps the saved speaking voice when persistence fails", async () => {
    render(() => <VoiceSettings />);
    await readySelect();
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Voice write failed");
    });
    try {
      await userEvent.setup().selectOptions(screen.getByRole("combobox", { name: "Speaking voice" }), "Aoede");
      expect(voiceSession.state.voiceName).toBe("Orus");
      expect(screen.getByRole("combobox", { name: "Speaking voice" })).toHaveValue("Orus");
      expect(screen.getByRole("alert")).toHaveTextContent("Voice write failed");
    } finally {
      save.mockRestore();
    }
  });

  it("hides speaking voices when a choice omits or empties the catalog", async () => {
    globalThis.fetch = async () =>
      new Response(
        JSON.stringify({
          choices: [
            { id: "gemini", label: "Gemini Live", transports: ["webrtc"] },
            { id: "other", label: "Other", transports: ["webrtc"], voices: [] },
          ],
          defaultChoiceID: "gemini",
        }),
      );
    render(() => <VoiceSettings />);
    const choice = await readySelect();
    expect(screen.queryByRole("combobox", { name: "Speaking voice" })).not.toBeInTheDocument();
    await userEvent.setup().selectOptions(choice, "other");
    expect(screen.queryByRole("combobox", { name: "Speaking voice" })).not.toBeInTheDocument();
    expect(voiceSession.state.voiceName).toBe("");
  });

  it("rejects invalid gateway speaking-voice catalogs", async () => {
    for (const catalog of [
      { voices: ["Orus", "Orus"], defaultVoice: "Orus" },
      { voices: ["Orus"], defaultVoice: "Retired" },
      { voices: [""], defaultVoice: "" },
      { voices: "Orus", defaultVoice: "Orus" },
      { voices: "", defaultVoice: "" },
    ]) {
      configureVoiceGateway("https://voice.example", null);
      globalThis.fetch = async () =>
        new Response(
          JSON.stringify({
            choices: [{ id: "gemini", label: "Gemini Live", transports: ["webrtc"], ...catalog }],
            defaultChoiceID: "gemini",
          }),
        );
      await voiceSession.loadVoiceChoices();
      expect(voiceSession.state.choicesStatus).toBe("error");
      expect(voiceSession.state.choicesError).toBe("The voice gateway returned invalid choices");
    }
  });

  it("reports a removed saved choice and waits for an explicit replacement", async () => {
    localStorage.setItem("gomode.voiceChoice.https://voice.example", "removed");
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    const select = await readySelect();
    expect(select).toHaveValue("removed");
    expect(screen.getByRole("alert")).toHaveTextContent("selected voice choice is unavailable");
    expect(localStorage.getItem("gomode.voiceChoice.https://voice.example")).toBe("removed");
    await user.selectOptions(select, "remote");
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
    expect(select).toHaveValue("remote");
  });

  it("shows pending discovery, reports failure, and retries", async () => {
    let rejectRequest: (error: Error) => void = () => {
      throw new Error("No discovery request");
    };
    const discovery = new Promise<Response>((_resolve, reject) => {
      rejectRequest = reject;
    });
    globalThis.fetch = () => discovery;
    render(() => <VoiceSettings />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading voice choices");
    expect(screen.getByRole("combobox", { name: "Voice choice" })).toBeDisabled();
    rejectRequest(new Error("Gateway offline"));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Gateway offline"));
    globalThis.fetch = async () => new Response(JSON.stringify(metadata));
    await userEvent.setup().click(screen.getByRole("button", { name: "Retry loading voice choices" }));
    expect(await readySelect()).toHaveValue("gemini");
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
  });

  it("ignores delayed discovery from the gateway replaced during loading", async () => {
    let finishOld: (response: Response) => void = () => {
      throw new Error("No old request");
    };
    const old = new Promise<Response>((resolve) => {
      finishOld = resolve;
    });
    globalThis.fetch = async (input) =>
      String(input).startsWith("https://voice.example/")
        ? old
        : new Response(
            JSON.stringify({
              choices: [{ id: "new", label: "New gateway", transports: ["webrtc"] }],
              defaultChoiceID: "new",
            }),
          );
    render(() => <VoiceSettings />);
    configureVoiceGateway("https://new.example", null);
    const select = await readySelect();
    expect(select).toHaveValue("new");
    finishOld(new Response(JSON.stringify(metadata)));
    await old;
    await vi.waitFor(() => expect(voiceSession.state.choices.map((choice) => choice.id)).toEqual(["new"]));
    expect(select).toHaveValue("new");
    expect(localStorage.getItem("gomode.voiceChoice.https://new.example")).toBeNull();
  });

  it("retries a failed preference read without switching a removed saved choice", async () => {
    localStorage.setItem("gomode.voiceChoice.https://voice.example", "removed");
    voiceSession.selectMode("browser");
    const prepare = vi.spyOn(voiceSession, "prepareAudio").mockImplementation(() => {});
    const read = window.Storage.prototype.getItem;
    const load = vi.spyOn(window.Storage.prototype, "getItem").mockImplementation(function (this: Storage, key) {
      if (key === "gomode.voiceChoice.https://voice.example") throw new Error("Storage unavailable");
      return read.call(this, key);
    });
    try {
      render(() => <VoiceSettings />);
      await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Storage unavailable"));
      expect(screen.getByRole("combobox", { name: "Voice choice" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Retry loading voice choices" })).toBeEnabled();
      await voiceSession.connect();
      expect(voiceSession.state.error).toBe("Storage unavailable");
      load.mockRestore();
      await userEvent.setup().click(screen.getByRole("button", { name: "Retry loading voice choices" }));
      const select = await readySelect();
      expect(select).toHaveValue("removed");
      expect(screen.getByRole("alert")).toHaveTextContent("selected voice choice is unavailable");
      await voiceSession.connect();
      expect(voiceSession.state.connected).toBe(false);
      expect(voiceSession.state.error).toContain("selected voice choice is unavailable");
      expect(localStorage.getItem("gomode.voiceChoice.https://voice.example")).toBe("removed");
      await userEvent.setup().selectOptions(select, "local");
      expect(select).toHaveValue("local");
      expect(screen.getByRole("alert")).toBeEmptyDOMElement();
    } finally {
      load.mockRestore();
      prepare.mockRestore();
    }
  });

  it("restores the selection and reports a failed preference write", async () => {
    render(() => <VoiceSettings />);
    const select = await readySelect();
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      await userEvent.setup().selectOptions(select, "remote");
      expect(select).toHaveValue("gemini");
      expect(voiceSession.state.choiceID).toBe("gemini");
      expect(screen.getByRole("alert")).toHaveTextContent("Storage unavailable");
    } finally {
      save.mockRestore();
    }
  });
});
