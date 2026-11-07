// Tests voice setup selection, atomic persistence, catalog recovery, and settings request ownership.

import { readFileSync } from "node:fs";
import { afterEach, beforeEach, describe, it } from "node:test";
import { render, screen, waitFor } from "@solidjs/testing-library";
import userEvent from "@testing-library/user-event";
import { expect, vi } from "../tests/expect";
import { installBrowserSpeech } from "../tests/browser-speech";
import VoiceSettings from "./VoiceSettings";
import { deferred } from "../tests/deferred";
import { configureVoiceGateway, voiceSession, VoiceSession } from "./VoiceSession";

const metadata = {
  choices: [
    { id: "gemini", label: "Gemini Live", transports: ["webrtc"], voices: ["Aoede", "Orus"] },
    {
      id: "remote",
      label: "Remote LLM",
      transports: ["text", "webrtc"],
      voices: ["Kore", "Leda"],
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
  voiceSession.setState((s) => ({
    ...s,
    setupMode: "parts",
    speechInput: "gateway",
    speechOutput: "gateway",
    choices: [],
    choiceIDs: { text: null, webrtc: null },
    speechModels: { input: [], output: [] },
    speechModelIDs: { input: null, output: null },
  }));
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
  let select: HTMLElement;
  await waitFor(() => {
    expect(voiceSession.state.choicesStatus).toBe("ready");
    select = screen.getByRole("combobox", { name: /^(Model|Language model)$/ });
    expect(select).toBeEnabled();
    expect(select).toHaveProperty("value", voiceSession.state.choiceIDs[voiceSession.transport] ?? "");
  });
  return select!;
}

const independent = {
  choices: [
    {
      id: "first",
      label: "First LLM",
      composable: true,
      transports: ["text", "webrtc"],
      speechModes: [
        { input: "gateway", output: "gateway" },
        { input: "gateway", output: "client" },
        { input: "client", output: "gateway" },
        { input: "client", output: "client" },
      ],
    },
    {
      id: "second",
      label: "Second LLM",
      composable: true,
      transports: ["text", "webrtc"],
      speechModes: [
        { input: "gateway", output: "gateway" },
        { input: "gateway", output: "client" },
        { input: "client", output: "gateway" },
        { input: "client", output: "client" },
      ],
    },
    { id: "bundle", label: "Fixed bundle", composable: false, transports: ["text", "webrtc"] },
    { id: "native", label: "Native audio", composable: false, transports: ["webrtc"], voices: ["Aoede"] },
  ],
  defaults: [
    { transport: "text", id: "first" },
    { transport: "webrtc", id: "bundle" },
  ],
  recognitionModels: [
    { id: "asr-a", label: "Whisper" },
    { id: "asr-b", label: "Parakeet" },
  ],
  synthesisModels: [
    { id: "tts-a", label: "Gemini speech" },
    { id: "tts-b", label: "Kokoro" },
  ],
};

describe("voice choices", () => {
  for (const mode of ["parts", "all-in-one"] as const) {
    it(`restores a non-first ${mode} model after deferred discovery and gateway return`, async () => {
      const choices = [
        ...independent.choices,
        { id: "native-second", label: "Second native", composable: false, transports: ["webrtc"] },
      ];
      localStorage.setItem(
        "gomode.voiceSetup.https://voice.example",
        JSON.stringify({
          mode,
          nativeChoiceID: "native-second",
          partsChoiceID: "second",
          partsInput: "gateway",
          partsOutput: "gateway",
          recognitionModelID: "asr-b",
          synthesisModelID: "tts-b",
        }),
      );
      const first = deferred<Response>();
      const returned = deferred<Response>();
      let calls = 0;
      globalThis.fetch = async (input) => {
        if (String(input).startsWith("https://other.example/"))
          return new Response(JSON.stringify({ ...independent, choices }));
        return ++calls === 1 ? first.promise : returned.promise;
      };
      render(() => <VoiceSettings />);
      await waitFor(() => expect(voiceSession.state.choicesStatus).toBe("loading"));
      // Let Solid mount the saved unavailable option before catalog insertion.
      await new Promise((resolve) => setTimeout(resolve, 0));
      first.resolve(new Response(JSON.stringify({ ...independent, choices })));
      const expected = mode === "parts" ? "second" : "native-second";
      expect(await readySelect()).toHaveValue(expected);
      configureVoiceGateway("https://other.example", null);
      await readySelect();
      configureVoiceGateway("https://voice.example", null);
      await waitFor(() => expect(voiceSession.state.choicesStatus).toBe("loading"));
      await new Promise((resolve) => setTimeout(resolve, 0));
      returned.resolve(new Response(JSON.stringify({ ...independent, choices })));
      const select = await readySelect();
      expect(select).toHaveValue(expected);
      await userEvent.setup().selectOptions(select, mode === "parts" ? "first" : "native");
      expect(select).toHaveValue(mode === "parts" ? "first" : "native");
    });
  }

  it("allows the parts journey in recognition, model, then speech order", async () => {
    globalThis.fetch = async () => new Response(JSON.stringify(metadata));
    render(() => <VoiceSettings />);
    await readySelect();
    const user = userEvent.setup();
    await user.click(screen.getByRole("tab", { name: "Select parts" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Recognition" }), "browser");
    const model = screen.getByRole("combobox", { name: "Language model" });
    await user.selectOptions(model, "local");
    expect(model).toHaveValue("local");
    expect(voiceSession.state.choiceIDs[voiceSession.transport]).toBe("local");
    expect(screen.getByRole("alert")).toHaveTextContent("does not support the selected recognition and speech output");
    await user.selectOptions(screen.getByRole("combobox", { name: "Speech" }), "browser");
    expect(model).toHaveValue("local");
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
    expect(voiceSession.speech).toEqual({ input: "client", output: "client" });
  });

  it("clears a failed tab save after a successful stage change", async () => {
    globalThis.fetch = async () => new Response(JSON.stringify(independent));
    render(() => <VoiceSettings />);
    await readySelect();
    const user = userEvent.setup();
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      await user.click(screen.getByRole("tab", { name: "All-in-one model" }));
    } finally {
      save.mockRestore();
    }
    expect(screen.getByRole("alert")).toHaveTextContent("Storage unavailable");
    await user.selectOptions(screen.getByRole("combobox", { name: "Recognition" }), "model:asr-b");
    expect(voiceSession.state.speechModelIDs.input).toBe("asr-b");
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
  });

  for (const outcome of ["failure", "cancel"] as const) {
    it(`recovers discarded cold settings discovery after connection ${outcome}`, async () => {
      const settings = deferred<Response>();
      const connection = deferred<Response>();
      const retry = deferred<Response>();
      let requests = 0;
      globalThis.fetch = async () => {
        requests++;
        return requests === 1 ? settings.promise : requests === 2 ? connection.promise : retry.promise;
      };
      const audio = vi.spyOn(voiceSession, "prepareAudio").mockImplementation(() => {});
      try {
        render(() => <VoiceSettings />);
        await waitFor(() => expect(requests).toBe(1));
        const connecting = voiceSession.connect();
        await waitFor(() => expect(requests).toBe(2));
        settings.resolve(new Response(JSON.stringify(independent)));
        await waitFor(() => expect(voiceSession.state.choicesStatus).toBe("idle"));
        expect(voiceSession.state.choicesError).toBeNull();
        if (outcome === "cancel") voiceSession.disconnect();
        connection.resolve(
          outcome === "failure" ? new Response(null, { status: 503 }) : new Response(JSON.stringify(independent)),
        );
        await connecting;
        await waitFor(() => expect(requests).toBe(3));
        retry.resolve(new Response(JSON.stringify(independent)));
        expect(await readySelect()).toHaveValue("first");
        expect(screen.getByRole("tab", { name: "All-in-one model" })).toBeEnabled();
        expect(screen.getByRole("combobox", { name: "Recognition" })).toBeEnabled();
        expect(voiceSession.state.choicesError).toBeNull();
        expect(screen.queryByRole("status")).toBeNull();
      } finally {
        audio.mockRestore();
      }
    });
  }

  it("remembers the complete parts setup independently of the native model", async () => {
    globalThis.fetch = async () => new Response(JSON.stringify(independent));
    render(() => <VoiceSettings />);
    const user = userEvent.setup();
    const llm = await readySelect();
    expect(llm).toHaveValue("first");
    expect(screen.queryByRole("option", { name: "Fixed bundle" })).toBeNull();
    const recognition = () => screen.getByRole("combobox", { name: "Recognition" });
    const synthesis = () => screen.getByRole("combobox", { name: "Speech" });
    await user.selectOptions(llm, "second");
    await user.selectOptions(recognition(), "model:asr-b");
    await user.selectOptions(synthesis(), "model:tts-b");
    for (const [input, output] of [
      ["browser", "model:tts-b"],
      ["browser", "browser"],
      ["model:asr-b", "browser"],
      ["model:asr-b", "model:tts-b"],
    ]) {
      await user.selectOptions(recognition(), input!);
      await user.selectOptions(synthesis(), output!);
      expect(llm).toHaveValue("second");
    }
    const parts = localStorage.getItem("gomode.voiceSetup.https://voice.example");
    const saved = JSON.parse(parts!);
    expect(saved.partsChoiceID).toBe("second");
    expect(saved.recognitionModelID).toBe("asr-b");
    expect(saved.synthesisModelID).toBe("tts-b");
    await user.click(screen.getByRole("tab", { name: "All-in-one model" }));
    expect(screen.getByRole("combobox", { name: "Model" })).toHaveValue("native");
    expect(screen.queryByRole("combobox", { name: "Recognition" })).toBeNull();
    await user.selectOptions(screen.getByRole("combobox", { name: "Speaking voice" }), "Aoede");
    await user.click(screen.getByRole("tab", { name: "Select parts" }));
    expect(await readySelect()).toHaveValue("second");
    expect(recognition()).toHaveValue("model:asr-b");
    expect(synthesis()).toHaveValue("model:tts-b");
    configureVoiceGateway("https://other.example", null);
    await waitFor(() => expect(recognition()).toHaveValue("model:asr-a"));
    configureVoiceGateway("https://voice.example", null);
    await waitFor(() => expect(recognition()).toHaveValue("model:asr-b"));
    voiceSession.setState((s) => ({ ...s, connected: true }));
    expect(recognition()).toBeDisabled();
    expect(synthesis()).toBeDisabled();
    expect(screen.getByRole("tab", { name: "All-in-one model" })).toBeDisabled();
  });

  it("retains missing saved stages and restores a selection when persistence fails", async () => {
    localStorage.setItem("gomode.voiceSpeechModel.https://voice.example.input", "removed");
    globalThis.fetch = async () => new Response(JSON.stringify(independent));
    render(() => <VoiceSettings />);
    await readySelect();
    const select = screen.getByRole("combobox", { name: "Recognition" });
    expect(select).toHaveProperty("value", "model:removed");
    expect(screen.getByRole("alert")).toHaveTextContent("selected recognition model is unavailable");
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      await userEvent.setup().selectOptions(select, "model:asr-b");
      expect(select).toHaveProperty("value", "model:removed");
      expect(screen.getByRole("alert")).toHaveTextContent("Storage unavailable");
    } finally {
      save.mockRestore();
    }
    await userEvent.setup().selectOptions(select, "model:asr-b");
    expect(select).toHaveValue("model:asr-b");
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
  });

  it("rejects malformed and duplicate stage catalogs", async () => {
    const session = new VoiceSession();
    try {
      for (const recognitionModels of [
        [
          { id: "a", label: "A" },
          { id: "a", label: "Duplicate" },
        ],
        [{ id: "", label: "Empty" }],
        [null],
        "invalid",
      ]) {
        globalThis.fetch = async () => new Response(JSON.stringify({ ...independent, recognitionModels }));
        await session.loadVoiceChoices();
        expect(session.state.choicesError).toContain("invalid choices");
      }
    } finally {
      session.disconnect();
    }
  });

  const fixtures = JSON.parse(
    readFileSync(new URL("../../testdata/voice-choices/selection.json", import.meta.url), "utf8"),
  ) as {
    selection: Array<{
      name: string;
      catalog: { voices?: unknown };
      savedVoice?: string;
      mode?: "text";
      voice?: string;
      error?: "catalog" | "voice";
    }>;
  };
  for (const row of fixtures.selection) {
    it(`shared voice selection: ${row.name}`, async () => {
      localStorage.setItem("gomode.voiceMode", row.mode === "text" ? "browser" : "cloud");
      if (row.savedVoice !== undefined) {
        localStorage.setItem('gomode.speakingVoice.["https://voice.example","gemini"]', row.savedVoice);
      }
      globalThis.fetch = async () =>
        new Response(
          JSON.stringify({
            choices: [{ id: "gemini", label: "Gemini Live", transports: ["text", "webrtc"], ...row.catalog }],
            defaults: [
              { transport: "webrtc", id: "gemini" },
              { transport: "text", id: "gemini" },
            ],
          }),
        );
      await voiceSession.loadVoiceChoices();
      expect(voiceSession.state.choicesStatus).toBe(row.error === "catalog" ? "error" : "ready");
      if (row.error === "catalog") return;
      if (row.error === "voice") {
        expect(voiceSession.state.voiceError).toContain("speaking voice is unavailable");
      } else {
        expect(voiceSession.state.voiceError).toBeNull();
        expect(voiceSession.state.voiceName).toBe(row.voice);
      }
    });
  }

  it("filters native models from parts and retains a compatible LLM across speech changes", async () => {
    const modes = [
      { input: "gateway", output: "gateway" },
      { input: "gateway", output: "client" },
      { input: "client", output: "gateway" },
      { input: "client", output: "client" },
    ];
    globalThis.fetch = async () =>
      new Response(
        JSON.stringify({
          ...metadata,
          choices: metadata.choices.map((choice) =>
            choice.id === "remote" ? { ...choice, speechModes: modes } : choice,
          ),
        }),
      );
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    await readySelect();
    await user.click(screen.getByRole("tab", { name: "Select parts" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Speech" }), "browser");
    const model = screen.getByRole("combobox", { name: "Language model" });
    expect(model).toHaveValue("remote");
    expect(model.querySelector('option[value="gemini"]')).toBeNull();
    await user.selectOptions(screen.getByRole("combobox", { name: "Speech" }), "gateway");
    expect(model).toHaveValue("remote");
  });

  it("does not infer mixed speech support from legacy transport metadata", async () => {
    await voiceSession.loadVoiceChoices();
    voiceSession.selectSpeechOutput("client");
    expect(voiceSession.compatibleChoices).toEqual([]);
    expect(voiceSession.state.choicesError).toContain("does not support the selected recognition and speech output");
  });

  it("persists the provider-default selection across catalog updates", async () => {
    globalThis.fetch = async () =>
      new Response(
        JSON.stringify({
          choices: [{ id: "gemini", label: "Gemini Live", transports: ["webrtc"], voices: ["Aoede", "Orus"] }],
          defaults: [{ transport: "webrtc", id: "gemini" }],
        }),
      );
    render(() => <VoiceSettings />);
    await readySelect();
    const speaking = screen.getByRole("combobox", { name: "Speaking voice" });
    expect(speaking).toHaveValue("");
    expect(screen.getByRole("option", { name: "Provider default" })).toBeInTheDocument();
    const user = userEvent.setup();
    await user.selectOptions(speaking, "Aoede");
    await user.selectOptions(speaking, "");
    globalThis.fetch = async () => new Response(JSON.stringify(metadata));
    const restored = new VoiceSession();
    await restored.loadVoiceChoices();
    expect(restored.state.voiceName).toBe("");
    expect(restored.state.voiceError).toBeNull();
  });

  it("restores each setup and its parts model after reload and gateway replacement", async () => {
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    expect(await readySelect()).toHaveValue("gemini");
    await user.click(screen.getByRole("tab", { name: "Select parts" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Recognition" }), "browser");
    await user.selectOptions(screen.getByRole("combobox", { name: "Speech" }), "browser");
    await user.selectOptions(screen.getByRole("combobox", { name: "Language model" }), "local");
    const restored = new VoiceSession();
    await restored.loadVoiceChoices();
    expect(restored.state.setupMode).toBe("parts");
    expect(restored.state.choiceIDs.text).toBe("local");
    expect(restored.speech).toEqual({ input: "client", output: "client" });
    await user.click(screen.getByRole("tab", { name: "All-in-one model" }));
    expect(await readySelect()).toHaveValue("gemini");
    await user.click(screen.getByRole("tab", { name: "Select parts" }));
    expect(await readySelect()).toHaveValue("local");
    configureVoiceGateway("https://other.example", null);
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Model" })).toHaveValue("gemini"));
    configureVoiceGateway("https://voice.example", null);
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Language model" })).toHaveValue("local"));
    expect(screen.getAllByRole("combobox", { name: "Language model" })).toHaveLength(1);
    restored.disconnect();
  });

  it("uses the family default for a choice saved by the other family without overwriting it", async () => {
    localStorage.setItem("gomode.voiceChoice.https://voice.example.text", "gemini");
    voiceSession.selectSpeechInput("client");
    voiceSession.selectSpeechOutput("client");
    render(() => <VoiceSettings />);
    expect(await readySelect()).toHaveValue("remote");
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
    expect(localStorage.getItem("gomode.voiceChoice.https://voice.example.text")).toBe("gemini");
  });

  it("offers the model for client speech when the gateway has no audio choice", async () => {
    voiceSession.selectSpeechInput("client");
    voiceSession.selectSpeechOutput("client");
    globalThis.fetch = async () =>
      new Response(
        JSON.stringify({
          choices: [{ id: "local", label: "Local LLM", transports: ["text"] }],
          defaults: [{ transport: "text", id: "local" }],
        }),
      );
    render(() => <VoiceSettings />);
    expect(await readySelect()).toHaveValue("local");
    expect(screen.getAllByRole("combobox", { name: "Language model" })).toHaveLength(1);
  });

  it("saves native, parts-provider and browser voices independently", async () => {
    render(() => <VoiceSettings />);
    await readySelect();
    const user = userEvent.setup();
    const speaking = () => screen.getByRole("combobox", { name: "Speaking voice" });
    await user.selectOptions(speaking(), "Aoede");
    const restored = new VoiceSession();
    await restored.loadVoiceChoices();
    expect(restored.state.voiceName).toBe("Aoede");
    await user.click(screen.getByRole("tab", { name: "Select parts" }));
    expect(speaking()).toHaveValue("");
    await user.selectOptions(speaking(), "Leda");
    await user.click(screen.getByRole("tab", { name: "All-in-one model" }));
    expect(speaking()).toHaveValue("Aoede");
    configureVoiceGateway("https://other.example", null);
    await readySelect();
    expect(speaking()).toHaveValue("");
    configureVoiceGateway("https://voice.example", null);
    await readySelect();
    expect(speaking()).toHaveValue("Aoede");
    await user.click(screen.getByRole("tab", { name: "Select parts" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Recognition" }), "browser");
    await user.selectOptions(screen.getByRole("combobox", { name: "Speech" }), "browser");
    expect(speaking()).toHaveValue("");
    expect(screen.getByRole("option", { name: "Browser default" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Aoede" })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByRole("combobox", { name: "Recognition" }), "gateway");
    await user.selectOptions(screen.getByRole("combobox", { name: "Speech" }), "gateway");
    expect(speaking()).toHaveValue("Leda");
    voiceSession.setState((s) => ({ ...s, connectStatus: "Connecting…" }));
    expect(speaking()).toBeDisabled();
    expect(() => voiceSession.selectSpeakingVoice("Kore")).toThrow("End the session");
    restored.disconnect();
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
      expect(voiceSession.state.voiceName).toBe("");
      expect(screen.getByRole("combobox", { name: "Speaking voice" })).toHaveValue("");
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
    const choice = await readySelect();
    expect(screen.queryByRole("combobox", { name: "Speaking voice" })).not.toBeInTheDocument();
    await userEvent.setup().selectOptions(choice, "other");
    expect(screen.queryByRole("combobox", { name: "Speaking voice" })).not.toBeInTheDocument();
    expect(voiceSession.state.voiceName).toBe("");
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

  it("retains a removed inactive parts choice until the user repairs it", async () => {
    localStorage.setItem("gomode.voiceChoice.https://voice.example.text", "removed");
    const user = userEvent.setup();
    render(() => <VoiceSettings />);
    expect(await readySelect()).toHaveValue("gemini");
    await user.click(screen.getByRole("tab", { name: "Select parts" }));
    expect(await readySelect()).toHaveProperty("value", "removed");
    expect(screen.getByRole("alert")).toHaveTextContent("selected voice choice is unavailable");
    expect(localStorage.getItem("gomode.voiceChoice.https://voice.example.text")).toBe("removed");
    await user.selectOptions(screen.getByRole("combobox", { name: "Language model" }), "remote");
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
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
    expect(screen.getByRole("combobox", { name: "Language model" })).toBeDisabled();
    rejectRequest(new Error("Gateway offline"));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Voice gateway unreachable."));
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
              recognitionModels: [{ id: "fresh-asr", label: "Fresh ASR" }],
              defaults: [{ transport: "webrtc", id: "new" }],
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
    expect(voiceSession.state.speechModels.input.map((model) => model.id)).toEqual(["fresh-asr"]);
    expect(localStorage.getItem("gomode.voiceChoice.https://new.example.webrtc")).toBeNull();
  });

  it("retries a failed preference read without switching a removed saved choice", async () => {
    localStorage.setItem("gomode.voiceChoice.https://voice.example.text", "removed");
    localStorage.setItem("gomode.voiceMode", "browser");
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
      expect(screen.getByRole("combobox", { name: "Language model" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Retry loading voice choices" })).toBeEnabled();
      await voiceSession.connect();
      expect(voiceSession.state.error).toBe("Storage unavailable");
      load.mockRestore();
      await userEvent.setup().click(screen.getByRole("button", { name: "Retry loading voice choices" }));
      const select = await readySelect();
      expect(select).toHaveProperty("value", "removed");
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

  it("defers replacement-gateway settings until the active setup is released", async () => {
    globalThis.fetch = async () => new Response(JSON.stringify(independent));
    const session = new VoiceSession();
    await session.loadVoiceChoices();
    session.selectSpeechStage("output", "client", null);
    session.setState((s) => ({ ...s, connected: true }));
    const before = JSON.stringify({
      speech: session.speech,
      ids: session.state.choiceIDs,
      models: session.state.speechModelIDs,
    });
    configureVoiceGateway("https://replacement.example", null);
    const request = vi.fn(async () => new Response(JSON.stringify(metadata)));
    globalThis.fetch = request;
    await session.loadVoiceChoices();
    expect(request).not.toHaveBeenCalled();
    expect(
      JSON.stringify({ speech: session.speech, ids: session.state.choiceIDs, models: session.state.speechModelIDs }),
    ).toBe(before);
    session.disconnect();
    await session.loadVoiceChoices();
    expect(session.state.setupMode).toBe("all-in-one");
    expect(session.state.choiceIDs.webrtc).toBe("gemini");
    expect(session.speech).toEqual({ input: "gateway", output: "gateway" });
  });

  it("keeps the entire effective setup when saving a mode change fails", async () => {
    render(() => <VoiceSettings />);
    const select = await readySelect();
    const before = JSON.stringify({
      mode: voiceSession.state.setupMode,
      speech: voiceSession.speech,
      ids: voiceSession.state.choiceIDs,
    });
    const stored = localStorage.getItem("gomode.voiceSetup.https://voice.example");
    const save = vi.spyOn(window.Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    try {
      await userEvent.setup().click(screen.getByRole("tab", { name: "Select parts" }));
      expect(select).toHaveValue("gemini");
      expect(
        JSON.stringify({
          mode: voiceSession.state.setupMode,
          speech: voiceSession.speech,
          ids: voiceSession.state.choiceIDs,
        }),
      ).toBe(before);
      expect(localStorage.getItem("gomode.voiceSetup.https://voice.example")).toBe(stored);
      expect(screen.getByRole("alert")).toHaveTextContent("Storage unavailable");
    } finally {
      save.mockRestore();
    }
    await userEvent.setup().click(screen.getByRole("tab", { name: "Select parts" }));
    expect(await readySelect()).toHaveValue("remote");
    expect(screen.getByRole("alert")).toBeEmptyDOMElement();
  });
});
