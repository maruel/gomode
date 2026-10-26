// Tests browser-speech voice sessions over the authenticated text gateway transport.

import { afterEach, beforeEach, describe, it } from "node:test";
import { ok } from "node:assert/strict";
import { expect, vi } from "../tests/expect";
import { FakeMicrophoneContext, microphoneFrames } from "../tests/microphone-audio";
import { FakeRecognition, fakeSynthesis, installBrowserSpeech } from "../tests/browser-speech";
import { configureVoiceGateway, VoiceSession } from "./VoiceSession";
import { mcpClient } from "./McpClient";

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  readyState = 0;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent<unknown>) => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  constructor(
    readonly url: URL,
    readonly protocols: string[],
  ) {
    FakeWebSocket.instances.push(this);
  }
  open(): void {
    this.readyState = 1;
    this.onopen?.();
  }
  send(text: string): void {
    this.sent.push(text);
  }
  close(): void {
    this.readyState = 3;
  }
  message(value: unknown): void {
    this.onmessage?.(new window.MessageEvent("message", { data: JSON.stringify(value) }));
  }
}

const originalFetch = globalThis.fetch;
const chime = { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() };
const earcon = { prepare: vi.fn(), setThinking: vi.fn() };
const callTool = vi.spyOn(mcpClient, "callTool");
let restore: () => void;
let session: VoiceSession;
let requests: Array<{ url: string; authorization: string | null; body: unknown }>;

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  restore = installBrowserSpeech("Chrome desktop");
  vi.stubGlobal("WebSocket", FakeWebSocket);
  FakeWebSocket.instances = [];
  FakeMicrophoneContext.instances = [];
  vi.stubGlobal("AudioContext", FakeMicrophoneContext);
  microphoneFrames();
  Object.defineProperty(navigator, "mediaDevices", {
    configurable: true,
    value: {
      getUserMedia: vi.fn(async () => {
        const track = { enabled: true, stop: vi.fn() };
        return { getTracks: () => [track], getAudioTracks: () => [track] };
      }),
    },
  });
  requests = [];
  globalThis.fetch = async (input, init) => {
    if (String(input).endsWith("/voice/choices"))
      return new Response(
        JSON.stringify({
          choices: [{ id: "remote", label: "Remote LLM", transports: ["text"] }],
          defaultChoiceID: "remote",
        }),
      );
    requests.push({
      url: String(input),
      authorization: new Headers(init?.headers).get("Authorization"),
      body: JSON.parse(String(init?.body)),
    });
    return new Response(JSON.stringify({ ticket: "one-time-ticket" }));
  };
  vi.spyOn(mcpClient, "serverInstructions").mockResolvedValue("Host instructions");
  vi.spyOn(mcpClient, "listTools").mockResolvedValue([]);
  vi.spyOn(mcpClient, "readAdvertisedTextResource").mockResolvedValue(null);
  configureVoiceGateway("https://gateway.example", null);
  session = new VoiceSession(chime, earcon);
  session.selectMode("browser");
});
afterEach(() => {
  session.disconnect();
  restore();
  globalThis.fetch = originalFetch;
  vi.useRealTimers();
});

it("explains a text gateway connection failure and releases the session", async () => {
  await session.connect();
  const socket = FakeWebSocket.instances[0];
  ok(socket);
  socket.onerror?.();
  expect(session.state.error).toBe("Voice gateway unreachable.");
  expect(session.state.connected).toBe(false);
  expect(socket.readyState).toBe(3);
});

async function readySocket(): Promise<FakeWebSocket> {
  await session.connect();
  const socket = FakeWebSocket.instances[0];
  if (!socket) throw new Error("text socket was not created");
  socket.open();
  socket.message({ kind: "session.ready" });
  return socket;
}

function finishTurn(socket: FakeWebSocket, text: string): void {
  socket.message({ kind: "assistant.text.delta", text });
  socket.message({ kind: "turn.status", state: "idle" });
}

describe("browser voice sessions", () => {
  it("meters quiet speech and silence, resets for mute and replies, and releases capture", async () => {
    const poll = microphoneFrames();
    const socket = await readySocket();
    socket.message({ kind: "turn.status", state: "idle" });
    const context = FakeMicrophoneContext.instances[0];
    ok(context);
    const stream = await vi.mocked(navigator.mediaDevices.getUserMedia).mock.results[0]!.value;
    ok(stream);
    poll();
    expect(session.state.micLevel).toBeGreaterThan(0.3);
    context.sample = 0;
    poll();
    expect(session.state.micLevel).toBe(0);
    context.sample = 0.01;
    session.setMicrophoneMode("muted");
    poll();
    expect(session.state.micLevel).toBe(0);
    expect(stream.getAudioTracks()[0]!.enabled).toBe(false);
    FakeRecognition.instances.at(-1)?.end();
    session.setMicrophoneMode("listening");
    poll();
    expect(session.state.micLevel).toBeGreaterThan(0.3);
    FakeRecognition.instances.at(-1)?.result([["Hello", true]]);
    FakeRecognition.instances.at(-1)?.end();
    expect(session.state.micLevel).toBe(0);
    finishTurn(socket, "Hello back");
    poll();
    expect(session.state.micLevel).toBe(0);
    fakeSynthesis.complete();
    poll();
    expect(session.state.micLevel).toBeGreaterThan(0.3);
    session.disconnect();
    poll();
    expect(session.state.micLevel).toBe(0);
    expect(stream.getTracks()[0]!.stop).toHaveBeenCalledOnce();
    expect(context.close).toHaveBeenCalledOnce();
  });

  it("reports denied meter capture through microphone permission recovery", async () => {
    vi.mocked(navigator.mediaDevices.getUserMedia).mockRejectedValueOnce(new DOMException("Denied", "NotAllowedError"));
    await session.connect();
    expect(session.state.microphoneAccess).toBe("denied");
    expect(FakeWebSocket.instances).toHaveLength(0);
  });

  it("releases a microphone acquired after cancellation", async () => {
    let finish!: (stream: MediaStream) => void;
    vi.mocked(navigator.mediaDevices.getUserMedia).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const connecting = session.connect();
    await vi.waitFor(() => expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledOnce());
    session.disconnect();
    const stop = vi.fn();
    finish({ getTracks: () => [{ stop }] } as unknown as MediaStream);
    await connecting;
    expect(stop).toHaveBeenCalledOnce();
    expect(FakeWebSocket.instances).toHaveLength(0);
  });

  it("plays thinking feedback through tool waits and stops for turn end, mute, interruption and teardown", async () => {
    const socket = await readySocket();
    expect(earcon.prepare).toHaveBeenCalledOnce();
    socket.message({ kind: "turn.status", state: "thinking" });
    expect(earcon.setThinking).toHaveBeenLastCalledWith(true);
    // Streaming text is buffered until idle; a long generation still needs feedback.
    socket.message({ kind: "assistant.text.delta", text: "Working on it." });
    expect(earcon.setThinking).toHaveBeenLastCalledWith(true);
    let completeTool: (value: Awaited<ReturnType<typeof mcpClient.callTool>>) => void = () => {
      throw new Error("tool not started");
    };
    callTool.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          completeTool = resolve;
        }),
    );
    socket.message({ kind: "tool.call", id: "slow", name: "example", args: {} });
    expect(earcon.setThinking).toHaveBeenLastCalledWith(true);
    session.setMicrophoneMode("muted");
    expect(earcon.setThinking).toHaveBeenLastCalledWith(false);
    session.setMicrophoneMode("listening");
    expect(earcon.setThinking).toHaveBeenLastCalledWith(true);
    completeTool({ structuredContent: {}, isError: false });
    await Promise.resolve();
    socket.message({ kind: "turn.status", state: "idle" });
    expect(earcon.setThinking).toHaveBeenLastCalledWith(false);
    expect(session.state.speaking).toBe(true);
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
    expect(earcon.setThinking).toHaveBeenLastCalledWith(false);
    socket.message({ kind: "turn.status", state: "thinking" });
    expect(earcon.setThinking).toHaveBeenLastCalledWith(true);
    socket.message({ kind: "interrupted", source: "user" });
    expect(earcon.setThinking).toHaveBeenLastCalledWith(false);
    socket.message({ kind: "turn.status", state: "thinking" });
    expect(earcon.setThinking).toHaveBeenLastCalledWith(true);
    session.disconnect();
    expect(earcon.setThinking).toHaveBeenLastCalledWith(false);
    socket.message({ kind: "turn.status", state: "thinking" });
    expect(earcon.setThinking).toHaveBeenLastCalledWith(false);
  });

  it("retains full microphone mute when retrying after a gateway error", async () => {
    const socket = await readySocket();
    session.setMicrophoneMode("muted");
    socket.onerror?.();
    expect(session.state.microphoneMode).toBe("muted");
    await session.connect();
    const replacement = FakeWebSocket.instances.at(-1);
    ok(replacement);
    replacement.open();
    replacement.message({ kind: "session.ready" });
    finishTurn(replacement, "Still audible after retry");
    expect(session.state.speaking).toBe(true);
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(false);
    expect(session.state.microphoneMode).toBe("muted");
    session.setMicrophoneMode("listening");
    expect(session.state.listening).toBe(true);
  });

  it("stops thinking feedback on connection errors and reconnects", async () => {
    const socket = await readySocket();
    socket.message({ kind: "turn.status", state: "thinking" });
    expect(earcon.setThinking).toHaveBeenLastCalledWith(true);
    socket.onerror?.();
    expect(earcon.setThinking).toHaveBeenLastCalledWith(false);
    await session.connect();
    const replacement = FakeWebSocket.instances[1];
    ok(replacement);
    replacement.open();
    replacement.message({ kind: "session.ready" });
    replacement.message({ kind: "turn.status", state: "thinking" });
    expect(earcon.setThinking).toHaveBeenLastCalledWith(true);
    await session.connect();
    expect(earcon.setThinking).toHaveBeenLastCalledWith(false);
  });
  it("uses a text choice and one-shot Android recognition when an audio-only choice was saved", async () => {
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "Chrome Android" });
    localStorage.setItem("gomode.voiceChoice.https://gateway.example", "gemini");
    const gatewayFetch = globalThis.fetch;
    globalThis.fetch = async (input, init) =>
      String(input).endsWith("/voice/choices")
        ? new Response(
            JSON.stringify({
              choices: [
                { id: "gemini", label: "Gemini Live", transports: ["webrtc"] },
                { id: "remote", label: "Remote LLM", transports: ["text"] },
              ],
              defaultChoiceID: "gemini",
            }),
          )
        : gatewayFetch(input, init);
    const socket = await readySocket();
    expect(requests[0]?.body).toEqual({ choiceID: "remote" });
    finishTurn(socket, "Ready");
    fakeSynthesis.complete();
    const recognition = FakeRecognition.instances[0];
    expect(recognition?.continuous).toBe(false);
    recognition?.result([["I", false]]);
    recognition?.result([["I need help", true]]);
    recognition?.end();
    expect(JSON.parse(socket.sent.at(-1) ?? "{}")).toEqual({ kind: "user.message", text: "I need help" });
    finishTurn(socket, "Okay.");
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
    expect(FakeRecognition.instances).toHaveLength(2);
  });

  it("uses a one-time subprotocol ticket and sends selected language without WebRTC audio", async () => {
    configureVoiceGateway("https://gateway.example", async () => ({
      kind: "web",
      instanceID: "one",
      baseURL: "https://host.example",
      token: "scoped-token",
    }));
    session.selectLanguage("fr-CA");
    const socket = await readySocket();
    expect(requests[0]?.body).toEqual({
      choiceID: "remote",
      service: { kind: "web", instanceID: "one", baseURL: "https://host.example", token: "scoped-token" },
    });
    expect(socket.url.toString()).toBe("wss://gateway.example/api/voicegateway/v1/voice/text/browser");
    expect(socket.protocols).toEqual(["gomode.text.v1", "gomode.ticket.one-time-ticket"]);
    expect(JSON.parse(socket.sent[0] ?? "{}").voice.language).toBe("fr-CA");
    expect(JSON.parse(socket.sent[0] ?? "{}").voice.name).toBe("");
    expect(new VoiceSession(chime).state.mode).toBe("browser");
    expect(localStorage.getItem("gomode.voiceLanguage")).toBe("fr-CA");
  });

  it("binds an explicitly saved local choice to the text ticket", async () => {
    const gatewayFetch = globalThis.fetch;
    globalThis.fetch = async (input, init) =>
      String(input).endsWith("/voice/choices")
        ? new Response(
            JSON.stringify({
              choices: [
                { id: "remote", label: "Remote LLM", transports: ["text"] },
                { id: "local", label: "Local LLM", transports: ["text"] },
              ],
              defaultChoiceID: "remote",
            }),
          )
        : gatewayFetch(input, init);
    await session.loadVoiceChoices();
    session.selectChoice("local");
    await readySocket();
    expect(requests[0]?.body).toEqual({ choiceID: "local" });
    expect(localStorage.getItem("gomode.voiceChoice.https://gateway.example")).toBe("local");
  });

  it("authenticates same-origin ticket issuance with the host bearer provider", async () => {
    configureVoiceGateway("/", null, () => "host-bearer");
    await readySocket();
    expect(requests[0]?.authorization).toBe("Bearer host-bearer");
    expect(requests[0]?.body).toEqual({ choiceID: "remote" });
  });

  it("listens only after the assistant finishes speaking and keeps one copy of user text", async () => {
    const socket = await readySocket();
    expect(FakeRecognition.instances).toHaveLength(0);
    finishTurn(socket, "Ready");
    expect(session.state.speaking).toBe(true);
    expect(FakeRecognition.instances).toHaveLength(0);
    expect(fakeSynthesis.spoken[0]?.lang).toBe("en-US");
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
    const recognition = FakeRecognition.instances[0];
    recognition?.result([["I", false]]);
    recognition?.result([["I need help", false]]);
    expect(session.state.transcript.at(-1)?.text).toBe("I need help");
    recognition?.result([["I need help", true]]);
    recognition?.end();
    socket.message({ kind: "transcript.delta", speaker: "user", text: "I need help" });
    expect(session.state.transcript.filter((entry) => entry.speaker === "user")).toEqual([
      { speaker: "user", text: "I need help", final: true },
    ]);
    expect(JSON.parse(socket.sent.at(-1) ?? "{}")).toEqual({ kind: "user.message", text: "I need help" });
    expect(session.state.listening).toBe(false);
    finishTurn(socket, "Okay.");
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
    expect(FakeRecognition.instances).toHaveLength(2);
  });

  it("returns mute once to listening after playback and speaks even while fully muted", async () => {
    const socket = await readySocket();
    session.setMicrophoneMode("muteOnce");
    finishTurn(socket, "First reply");
    expect(session.state.listening).toBe(false);
    expect(session.state.speaking).toBe(true);
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
    expect(session.state.microphoneMode).toBe("listening");
    session.setMicrophoneMode("muteOnce");
    finishTurn(socket, "Second reply");
    FakeRecognition.instances.at(-1)?.end();
    expect(session.state.listening).toBe(false);
    socket.message({ kind: "interrupted", source: "user" });
    expect(session.state.listening).toBe(false);
    expect(session.state.microphoneMode).toBe("muteOnce");
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
    expect(session.state.microphoneMode).toBe("listening");
    session.setMicrophoneMode("muted");
    FakeRecognition.instances.at(-1)?.end();
    finishTurn(socket, "Still audible");
    expect(session.state.speaking).toBe(true);
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(false);
    expect(session.state.microphoneMode).toBe("muted");
    session.setMicrophoneMode("listening");
    expect(session.state.listening).toBe(true);
  });

  it("mutes recognition and unmuting resumes only while the assistant is idle", async () => {
    const socket = await readySocket();
    finishTurn(socket, "Ready");
    session.setMicrophoneMode("muted");
    fakeSynthesis.complete();
    expect(FakeRecognition.instances).toHaveLength(0);
    session.setMicrophoneMode("listening");
    expect(session.state.listening).toBe(true);
    expect(FakeRecognition.instances).toHaveLength(1);
  });

  it("hang_up closes speech and ignores late transport or speech callbacks", async () => {
    const socket = await readySocket();
    finishTurn(socket, "Ready");
    socket.message({ kind: "tool.call", id: "hangup", name: "hang_up", args: {} });
    expect(session.state.connected).toBe(false);
    expect(socket.readyState).toBe(3);
    fakeSynthesis.complete();
    socket.message({ kind: "assistant.text.delta", text: "stale" });
    expect(FakeRecognition.instances).toHaveLength(0);
    expect(session.state.transcript.some((entry) => entry.text === "stale")).toBe(false);
  });

  it("keeps a text session and late tool result alive after a recoverable gateway error", async () => {
    let finish: (value: Awaited<ReturnType<typeof mcpClient.callTool>>) => void = () => {
      throw new Error("tool not started");
    };
    callTool.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    session.selectLanguage("fr-CA");
    const socket = await readySocket();
    socket.message({ kind: "tool.call", id: "pending", name: "items_list", args: {} });
    expect(callTool.mock.calls[0]?.[2]?.sessionID).toMatch(/^[0-9a-f]{32}$/);
    expect(callTool.mock.calls[0]?.[2]?.locale).toBe("fr-CA");
    socket.message({ kind: "error", message: "OutOfOrder support is unavailable", recoverable: true });
    expect(session.state.connected).toBe(true);
    expect(session.state.activeTool).toBe("items_list");
    expect(session.state.transcript.at(-1)?.text).toBe("[Voice] OutOfOrder support is unavailable");
    expect(socket.readyState).toBe(1);
    finish({ isError: false, structuredContent: { items: ["late"] } });
    await Promise.resolve();
    expect(socket.sent).toContain(
      JSON.stringify({ kind: "tool.result", id: "pending", name: "items_list", result: { items: ["late"] } }),
    );
    expect(session.state.activeTool).toBeNull();
  });

  it("does not send late tool results after disconnect", async () => {
    let finish: (value: Awaited<ReturnType<typeof mcpClient.callTool>>) => void = () => {
      throw new Error("tool not started");
    };
    callTool.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const socket = await readySocket();
    socket.message({ kind: "tool.call", id: "one", name: "items_list", args: {} });
    session.disconnect();
    finish({ isError: false, structuredContent: { items: [] } });
    await Promise.resolve();
    expect(socket.sent.some((value) => value.includes("tool.result"))).toBe(false);
  });

  it("does not open a socket when ticket issuance completes after cancellation", async () => {
    let finish: (value: Response) => void = () => {
      throw new Error("ticket not requested");
    };
    const gatewayFetch = globalThis.fetch;
    let ticketRequested = false;
    globalThis.fetch = (input, init) =>
      String(input).endsWith("/voice/choices")
        ? gatewayFetch(input, init)
        : new Promise((resolve) => {
            ticketRequested = true;
            finish = resolve;
          });
    const connect = session.connect();
    await vi.waitFor(() => expect(ticketRequested).toBe(true));
    session.disconnect();
    finish(new Response(JSON.stringify({ ticket: "late" })));
    await connect;
    expect(FakeWebSocket.instances).toHaveLength(0);
  });

  it("suppresses chimes on iOS and bounds text setup time", async () => {
    vi.useFakeTimers();
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "iPhone Safari" });
    const socket = await readySocket();
    expect(chime.prepare).not.toHaveBeenCalled();
    expect(earcon.prepare).not.toHaveBeenCalled();
    socket.message({ kind: "turn.status", state: "thinking" });
    expect(earcon.setThinking).toHaveBeenLastCalledWith(false);
    expect(chime.playConnected).not.toHaveBeenCalled();
    session.disconnect();
    expect(chime.playDisconnected).not.toHaveBeenCalled();
    await session.connect();
    vi.advanceTimersByTime(15000);
    expect(session.state.error).toContain("timed out");
  });
});
