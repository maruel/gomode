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
    { id: "gemini", label: "Gemini Live", transports: ["webrtc"] },
    { id: "remote", label: "Remote LLM", transports: ["text", "webrtc"] },
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
