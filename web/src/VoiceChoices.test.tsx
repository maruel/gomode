// Tests gateway choice discovery, per-family selection, persistence, and settings request ownership.

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
  defaults: [
    { transport: "text", id: "remote" },
    { transport: "webrtc", id: "gemini" },
  ],
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

async function readySelect(name: "LLM" | "Model"): Promise<HTMLElement> {
  const select = await screen.findByRole("combobox", { name });
  await waitFor(() => expect(select).toBeEnabled());
  return select;
}

describe("voice choices", () => {
  it("shows each family's default and keeps both selections across mode switches", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    const model = await readySelect("Model");
    const llm = await readySelect("LLM");
    expect(model).toHaveValue("gemini");
    expect(llm).toHaveValue("remote");
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
    await user.selectOptions(llm, "local");
    expect(voiceSession.state.choiceIDs).toEqual({ text: "local", webrtc: "gemini" });
    await user.click(screen.getByRole("radio", { name: "Browser speech" }));
    await readySelect("LLM");
    expect(model).toHaveValue("gemini");
    expect(llm).toHaveValue("local");
    await user.selectOptions(model, "remote");
    expect(voiceSession.state.choiceIDs).toEqual({ text: "local", webrtc: "remote" });
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
  });

  it("saves each family per gateway and restores both", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    await user.selectOptions(await readySelect("Model"), "remote");
    await user.selectOptions(await readySelect("LLM"), "local");
    expect(localStorage.getItem("gomode.voiceChoice.https://voice.example.webrtc")).toBe("remote");
    expect(localStorage.getItem("gomode.voiceChoice.https://voice.example.text")).toBe("local");
    const restored = new VoiceSession();
    await restored.loadVoiceChoices();
    expect(restored.state.choiceIDs).toEqual({ text: "local", webrtc: "remote" });
    configureVoiceGateway("https://other.example", null);
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Model" })).toHaveValue("gemini"));
    expect(screen.getByRole("combobox", { name: "LLM" })).toHaveValue("remote");
    configureVoiceGateway("https://voice.example", null);
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Model" })).toHaveValue("remote"));
    expect(screen.getByRole("combobox", { name: "LLM" })).toHaveValue("local");
  });

  it("uses the family default for a choice saved by the other family without overwriting it", async () => {
    localStorage.setItem("gomode.voiceChoice.https://voice.example.text", "gemini");
    render(() => <VoiceSettings />);
    expect(await readySelect("LLM")).toHaveValue("remote");
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
    expect(localStorage.getItem("gomode.voiceChoice.https://voice.example.text")).toBe("gemini");
  });

  it("hides a family's picker when the gateway offers no choice for it", async () => {
    globalThis.fetch = async () =>
      new Response(
        JSON.stringify({
          choices: [{ id: "local", label: "Local LLM", transports: ["text"] }],
          defaults: [{ transport: "text", id: "local" }],
        }),
      );
    render(() => <VoiceSettings />);
    expect(await readySelect("LLM")).toHaveValue("local");
    expect(screen.queryByRole("combobox", { name: "Model" })).not.toBeInTheDocument();
  });

  it("saves speaking voices per gateway and choice and hides them during browser speech", async () => {
    render(() => <VoiceSettings />);
    await readySelect("Model");
    const user = userEvent.setup();
    const speaking = () => screen.getByRole("combobox", { name: "Speaking voice" });
    expect(speaking()).toHaveValue("Orus");
    await user.selectOptions(speaking(), "Aoede");
    const restored = new VoiceSession();
    await restored.loadVoiceChoices();
    expect(restored.state.voiceName).toBe("Aoede");
    await user.selectOptions(screen.getByRole("combobox", { name: "Model" }), "remote");
    expect(speaking()).toHaveValue("Kore");
    await user.selectOptions(speaking(), "Leda");
    await user.selectOptions(screen.getByRole("combobox", { name: "Model" }), "gemini");
    expect(speaking()).toHaveValue("Aoede");
    configureVoiceGateway("https://other.example", null);
    await readySelect("Model");
    expect(speaking()).toHaveValue("Orus");
    configureVoiceGateway("https://voice.example", null);
    await readySelect("Model");
    expect(speaking()).toHaveValue("Aoede");
    await user.click(screen.getByRole("radio", { name: "Browser speech" }));
    await readySelect("LLM");
    expect(screen.queryByRole("combobox", { name: "Speaking voice" })).not.toBeInTheDocument();
    expect(voiceSession.state.voiceName).toBe("");
    await user.click(screen.getByRole("radio", { name: "Cloud voice" }));
    await readySelect("Model");
    expect(screen.getByRole("combobox", { name: "Model" })).toHaveValue("gemini");
    expect(screen.getByRole("combobox", { name: "Speaking voice" })).toHaveValue("Aoede");
    await user.selectOptions(screen.getByRole("combobox", { name: "Model" }), "remote");
    expect(screen.getByRole("combobox", { name: "Speaking voice" })).toHaveValue("Leda");
    voiceSession.setState((s) => ({ ...s, connectStatus: "Connecting…" }));
    expect(screen.getByRole("combobox", { name: "Speaking voice" })).toBeDisabled();
    expect(() => voiceSession.selectSpeakingVoice("Orus")).toThrow("End the session");
  });

  it("recovers failed speaking-voice reads without replacing a removed saved voice", async () => {
    const key = 'gomode.speakingVoice.["https://voice.example","gemini"]';
    localStorage.setItem(key, "Retired");
    const read = window.Storage.prototype.getItem;
    const load = vi.spyOn(window.Storage.prototype, "getItem").mockImplementation(function (
      this: Storage,
      key: string,
    ) {
      if (key.startsWith("gomode.speakingVoice.")) throw new Error("Voice storage unavailable");
      return read.call(this, key);
    });
    const prepare = vi.spyOn(voiceSession, "prepareAudio").mockImplementation(() => {});
    try {
      render(() => <VoiceSettings />);
      await readySelect("Model");
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
    await readySelect("Model");
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
          defaults: [{ transport: "webrtc", id: "gemini" }],
        }),
      );
    render(() => <VoiceSettings />);
    const choice = await readySelect("Model");
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
            defaults: [{ transport: "webrtc", id: "gemini" }],
          }),
        );
      await voiceSession.loadVoiceChoices();
      expect(voiceSession.state.choicesStatus).toBe("error");
      expect(voiceSession.state.choicesError).toBe("The voice gateway returned invalid choices");
    }
  });

  it("rejects invalid per-family defaults", async () => {
    for (const defaults of [
      [{ transport: "webrtc", id: "missing" }],
      [
        { transport: "webrtc", id: "remote" },
        { transport: "webrtc", id: "gemini" },
      ],
      [{ transport: "text", id: "gemini" }],
      [{ transport: "", id: "gemini" }],
      [{ transport: "webrtc", id: "" }],
    ]) {
      configureVoiceGateway("https://voice.example", null);
      globalThis.fetch = async () => new Response(JSON.stringify({ choices: metadata.choices, defaults }));
      await voiceSession.loadVoiceChoices();
      expect(voiceSession.state.choicesStatus).toBe("error");
      expect(voiceSession.state.choicesError).toBe("The voice gateway returned invalid choices");
    }
  });

  it("blocks only the family whose saved choice was removed", async () => {
    localStorage.setItem("gomode.voiceChoice.https://voice.example.text", "removed");
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    const llm = await readySelect("LLM");
    expect(llm).toHaveValue("removed");
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
    await user.click(screen.getByRole("radio", { name: "Browser speech" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("selected voice choice is unavailable"));
    expect(localStorage.getItem("gomode.voiceChoice.https://voice.example.text")).toBe("removed");
    await user.selectOptions(llm, "remote");
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
    expect(llm).toHaveValue("remote");
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
    expect(screen.queryByRole("combobox", { name: "Model" })).not.toBeInTheDocument();
    rejectRequest(new Error("Gateway offline"));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Voice gateway unreachable."));
    globalThis.fetch = async () => new Response(JSON.stringify(metadata));
    await userEvent.setup().click(screen.getByRole("button", { name: "Retry loading voice choices" }));
    expect(await readySelect("Model")).toHaveValue("gemini");
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
              defaults: [{ transport: "webrtc", id: "new" }],
            }),
          );
    render(() => <VoiceSettings />);
    configureVoiceGateway("https://new.example", null);
    const select = await readySelect("Model");
    expect(select).toHaveValue("new");
    finishOld(new Response(JSON.stringify(metadata)));
    await old;
    await vi.waitFor(() => expect(voiceSession.state.choices.map((choice) => choice.id)).toEqual(["new"]));
    expect(select).toHaveValue("new");
    expect(localStorage.getItem("gomode.voiceChoice.https://new.example.webrtc")).toBeNull();
  });

  it("retries a failed preference read without switching a removed saved choice", async () => {
    localStorage.setItem("gomode.voiceChoice.https://voice.example.text", "removed");
    voiceSession.selectMode("browser");
    const prepare = vi.spyOn(voiceSession, "prepareAudio").mockImplementation(() => {});
    const read = window.Storage.prototype.getItem;
    const load = vi.spyOn(window.Storage.prototype, "getItem").mockImplementation(function (
      this: Storage,
      key: string,
    ) {
      if (key === "gomode.voiceChoice.https://voice.example.text") throw new Error("Storage unavailable");
      return read.call(this, key);
    });
    try {
      render(() => <VoiceSettings />);
      await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Storage unavailable"));
      expect(screen.queryByRole("combobox", { name: "LLM" })).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Retry loading voice choices" })).toBeEnabled();
      await voiceSession.connect();
      expect(voiceSession.state.error).toBe("Storage unavailable");
      load.mockRestore();
      await userEvent.setup().click(screen.getByRole("button", { name: "Retry loading voice choices" }));
      const select = await readySelect("LLM");
      expect(select).toHaveValue("removed");
      expect(screen.getByRole("alert")).toHaveTextContent("selected voice choice is unavailable");
      await voiceSession.connect();
      expect(voiceSession.state.connected).toBe(false);
      expect(voiceSession.state.error).toContain("selected voice choice is unavailable");
      expect(localStorage.getItem("gomode.voiceChoice.https://voice.example.text")).toBe("removed");
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
    const select = await readySelect("Model");
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      await userEvent.setup().selectOptions(select, "remote");
      expect(select).toHaveValue("gemini");
      expect(voiceSession.state.choiceIDs.webrtc).toBe("gemini");
      expect(screen.getByRole("alert")).toHaveTextContent("Storage unavailable");
    } finally {
      save.mockRestore();
    }
  });
});
