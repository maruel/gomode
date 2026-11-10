// Tests browser-speech voice sessions over the authenticated text gateway transport.

import { afterEach, beforeEach, describe, it } from "node:test";
import { ok } from "node:assert/strict";
import { expect, vi } from "../tests/expect";
import { FakeMicrophoneContext, microphoneFrames } from "../tests/microphone-audio";
import { FakeRecognition, fakeSynthesis, installBrowserSpeech } from "../tests/browser-speech";
import { configureVoiceGateway, VoiceSession } from "./VoiceSession";
import { deferred } from "../tests/deferred";
import { resolveBrowserVoiceEndpoints } from "./BrowserVoiceManifest";
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
          choices: [{ id: "remote", label: "Remote LLM", composable: true, transports: ["text"] }],
          defaults: [{ transport: "text", id: "remote" }],
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
  session.selectSpeechInput("client");
  session.selectSpeechOutput("client");
});
afterEach(() => {
  session.disconnect();
  restore();
  globalThis.fetch = originalFetch;
  vi.useRealTimers();
});

it("recovers a browser-speech call when the network returns", async (t) => {
  vi.useFakeTimers();
  let online = true;
  t.mock.getter(navigator, "onLine", () => online);
  await session.connect();
  const socket = FakeWebSocket.instances[0];
  ok(socket);
  socket.open();
  socket.message({ kind: "session.ready" });
  online = false;
  window.dispatchEvent(new window.Event("offline"));
  await vi.advanceTimersByTimeAsync(60_000);
  expect(FakeWebSocket.instances).toHaveLength(1);
  expect(session.state.error).toBeNull();
  session.injectText("Alert received during handoff", true);
  const preparing = deferred<string>();
  vi.mocked(mcpClient.serverInstructions).mockReturnValueOnce(preparing.promise);
  online = true;
  window.dispatchEvent(new window.Event("online"));
  await vi.advanceTimersByTimeAsync(0);
  session.injectText("Alert received while preparing the new connection", true);
  preparing.resolve("Host instructions");
  await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(2));
  expect(socket.readyState).toBe(3);
  FakeWebSocket.instances[1]?.open();
  FakeWebSocket.instances[1]?.message({ kind: "session.ready" });
  expect(session.state.connected).toBe(true);
  expect(FakeWebSocket.instances[1]?.sent.some((text) => text.includes("Alert received during handoff"))).toBe(true);
  expect(FakeWebSocket.instances[1]?.sent.some((text) => text.includes("Alert received while preparing"))).toBe(true);
});

it("keeps a gateway policy rejection terminal instead of reconnecting", async () => {
  vi.useFakeTimers();
  const socket = await readySocket();
  socket.onclose?.(new window.CloseEvent("close", { code: 1008 }));
  await vi.advanceTimersByTimeAsync(60_000);
  expect(session.state.error).toBe("Text voice connection closed (1008)");
  expect(FakeWebSocket.instances).toHaveLength(1);
});

it("keeps an HTTP authentication rejection terminal even when the browser reports offline", async (t) => {
  vi.useFakeTimers();
  t.mock.getter(navigator, "onLine", () => false);
  globalThis.fetch = async () => new Response(null, { status: 401 });
  await session.connect();
  await vi.advanceTimersByTimeAsync(60_000);
  expect(session.state.error).toBe("Voice authorization failed (HTTP 401).");
  expect(FakeWebSocket.instances).toHaveLength(0);
});

it("retries a failed gateway fetch but preserves a programming error during recovery", async () => {
  vi.useFakeTimers();
  const socket = await readySocket();
  const fetch = globalThis.fetch;
  let requests = 0;
  globalThis.fetch = async (input, init) => {
    if (++requests === 1) throw new TypeError("Failed to fetch");
    return fetch(input, init);
  };
  socket.onerror?.();
  await vi.advanceTimersByTimeAsync(2_000);
  await vi.waitFor(() => expect(FakeWebSocket.instances).toHaveLength(2));
  const replacement = FakeWebSocket.instances[1];
  ok(replacement);
  replacement.open();
  replacement.message({ kind: "session.ready" });
  expect(session.state.error).toBeNull();
  vi.mocked(mcpClient.serverInstructions).mockImplementationOnce(() => {
    throw new TypeError("Broken application invariant");
  });
  replacement.onerror?.();
  await vi.advanceTimersByTimeAsync(60_000);
  expect(session.state.error).toBe("Broken application invariant");
  expect(FakeWebSocket.instances).toHaveLength(2);
});

it("awaits host focus publication before browser-speech setup reads", async () => {
  const gate = deferred<void>();
  const prepare = vi.fn(async (_sessionID: string, _signal: AbortSignal) => gate.promise);
  configureVoiceGateway("https://gateway.example", null, undefined, undefined, {
    prepareSession: prepare,
    retireSession: vi.fn(),
  });
  const pending = session.connect();
  await vi.waitFor(() => expect(prepare).toHaveBeenCalledOnce());
  expect(mcpClient.serverInstructions).not.toHaveBeenCalled();
  expect(mcpClient.listTools).not.toHaveBeenCalled();
  expect(mcpClient.readAdvertisedTextResource).not.toHaveBeenCalled();
  gate.resolve();
  await pending;
  expect(mcpClient.readAdvertisedTextResource).toHaveBeenCalledWith("gomode://items", undefined, {
    sessionID: session.conversationID,
  });
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

it("shows model startup progress and times out when progress stops", async () => {
  vi.useFakeTimers();
  await session.connect();
  const socket = FakeWebSocket.instances[0];
  ok(socket);
  socket.open();
  for (let i = 0; i < 5; i++) {
    vi.advanceTimersByTime(10000);
    socket.message({ kind: "session.status", message: "Loading local stack…" });
    expect(session.state.connectStatus).toBe("Loading local stack…");
    expect(session.state.error).toBeNull();
    expect(session.state.connected).toBe(false);
  }
  vi.advanceTimersByTime(15000);
  expect(session.state.error).toContain("timed out");
});

it("clears startup progress only when ready and ignores late progress", async () => {
  const socket = await readySocket();
  socket.message({ kind: "session.status", message: "Loading local stack…" });
  expect(session.state.connectStatus).toBeNull();
  expect(session.state.connected).toBe(true);
});

async function readySocket(): Promise<FakeWebSocket> {
  await session.connect();
  const socket = FakeWebSocket.instances.at(-1);
  if (!socket) throw new Error("text socket was not created");
  socket.open();
  socket.message({ kind: "session.ready" });
  return socket;
}

it("attaches text-gateway counts by identity before and after output without merging adjacent responses", async () => {
  const socket = await readySocket();
  const report = (id: string, tokens: number) =>
    socket.message({
      kind: "session.usage",
      sessionID: "one",
      provider: "test",
      model: "model",
      totals: { inputTokens: 100 },
      responseID: id,
      responseUsage: { inputTokens: tokens, outputTokens: 0 },
    });
  report("a", 10);
  socket.message({ kind: "assistant.text.delta", responseID: "a", text: "First." });
  socket.message({ kind: "turn.status", state: "idle" });
  socket.message({ kind: "assistant.text.delta", responseID: "b", text: "Second." });
  report("b", 20);
  report("a", 11);
  expect(session.state.transcript).toHaveLength(2);
  expect(session.state.transcript[0]).toMatchObject({
    responseID: "a",
    responseUsage: { inputTokens: 11 },
    text: "First.",
  });
  expect(session.state.transcript[1]).toMatchObject({
    responseID: "b",
    responseUsage: { inputTokens: 20 },
    text: "Second.",
  });
});

it("ends text voice visibly when a tool result cannot be sent", async () => {
  const socket = await readySocket();
  callTool.mockResolvedValue({ structuredContent: { value: "private result" } });
  socket.message({ kind: "turn.status", state: "thinking" });
  const send = vi.spyOn(socket, "send").mockImplementation(() => {
    throw new Error("private result");
  });
  socket.message({ kind: "tool.call", id: "delivery", name: "save", args: {} });
  await vi.waitFor(() =>
    expect(session.state.error).toBe(
      "Couldn't deliver the action's result. Reconnect voice. The action may have completed; check before repeating it.",
    ),
  );
  expect(socket.readyState).toBe(3);
  expect(session.state.connected).toBe(false);
  expect(session.state.activeTool).toBeNull();
  expect(earcon.setThinking).toHaveBeenLastCalledWith(false);
  expect(send).toHaveBeenCalledOnce();
  expect(callTool).toHaveBeenCalledOnce();
});

function finishTurn(socket: FakeWebSocket, text: string): void {
  socket.message({ kind: "assistant.text.delta", text });
  socket.message({ kind: "turn.status", state: "idle" });
}

describe("provider reasoning", () => {
  it("drops reasoning while the display setting is off", async () => {
    const socket = await readySocket();
    expect(session.state.showReasoning).toBe(false);
    expect(JSON.parse(socket.sent[0] ?? "{}")).not.toHaveProperty("includeReasoning");
    socket.message({ kind: "reasoning.delta", text: "Weigh both dates." });
    expect(session.state.transcript).toHaveLength(0);
    finishTurn(socket, "The first date is earlier.");
    expect(session.state.transcript).toEqual([
      { kind: "transcript", speaker: "assistant", text: "The first date is earlier.", final: true },
    ]);
  });

  it("merges reasoning into its own entry without speaking it", async () => {
    session.setShowReasoning(true);
    const socket = await readySocket();
    expect(JSON.parse(socket.sent[0] ?? "{}")).toHaveProperty("includeReasoning", true);
    socket.message({ kind: "reasoning.delta", text: "Weigh both dates. " });
    socket.message({ kind: "reasoning.delta", text: "Compare them." });
    expect(session.state.transcript).toEqual([
      { kind: "reasoning", speaker: "assistant", text: "Weigh both dates. Compare them.", final: false },
    ]);
    expect(fakeSynthesis.spoken).toHaveLength(0);
    finishTurn(socket, "The first date is earlier.");
    expect(session.state.transcript).toEqual([
      { kind: "reasoning", speaker: "assistant", text: "Weigh both dates. Compare them.", final: true },
      { kind: "transcript", speaker: "assistant", text: "The first date is earlier.", final: true },
    ]);
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(["The first date is earlier."]);
  });
});

describe("browser voice sessions", () => {
  it("keeps alerts behind unfinished recognition and supplies context before its final user request", async () => {
    const socket = await readySocket();
    socket.message({ kind: "turn.status", state: "idle" });
    const recognition = FakeRecognition.instances.at(-1);
    ok(recognition);
    recognition.result([["Please", false]]);
    const before = socket.sent.length;
    session.injectText("Task needs attention", true);
    socket.message({ kind: "interrupted", source: "user" });
    socket.message({ kind: "turn.status", state: "idle" });
    expect(socket.sent).toHaveLength(before);
    recognition.result([["Please continue", true]]);
    recognition.end();
    expect(socket.sent.slice(-2).map((text) => JSON.parse(text))).toEqual([
      { kind: "context.update", context: { text: "Task needs attention" } },
      { kind: "user.message", text: "Please continue" },
    ]);
  });

  it("announces queued alerts when recognition ends without a final utterance", async () => {
    const socket = await readySocket();
    socket.message({ kind: "turn.status", state: "idle" });
    const recognition = FakeRecognition.instances.at(-1);
    ok(recognition);
    recognition.result([["Unfinished", false]]);
    session.injectText("Task needs attention", true);
    const before = socket.sent.length;
    recognition.end();
    await Promise.resolve();
    expect(socket.sent).toHaveLength(before + 1);
    expect(JSON.parse(socket.sent.at(-1) ?? "{}")).toEqual({
      kind: "context.update",
      context: { text: "Task needs attention" },
      generate: true,
    });
  });

  it("interrupts playback on interim speech and discards in-flight output until cancellation is acknowledged", async () => {
    const socket = await readySocket();
    socket.message({ kind: "assistant.text.delta", text: "First. Queued. **unfinished" });
    const old = fakeSynthesis.spoken[0];
    ok(old);
    expect(session.state.listening).toBe(true);
    const recognition = FakeRecognition.instances.at(-1);
    ok(recognition);
    recognition.result([["Actually", false]]);
    expect(fakeSynthesis.cancel).toHaveBeenCalledOnce();
    expect(session.state.speaking).toBe(false);
    expect(session.state.listening).toBe(true);
    expect(socket.sent.at(-1)).toBe(JSON.stringify({ kind: "turn.cancel" }));
    recognition.result([["Actually, stop", false]]);
    expect(socket.sent.filter((text) => JSON.parse(text).kind === "turn.cancel")).toHaveLength(1);
    socket.message({ kind: "assistant.text.delta", text: "Stale. " });
    socket.message({ kind: "turn.status", state: "idle" });
    expect(fakeSynthesis.spoken).toHaveLength(1);
    recognition.result([["Actually, stop", true]]);
    recognition.end();
    expect(JSON.parse(socket.sent.at(-1) ?? "{}")).toEqual({ kind: "user.message", text: "Actually, stop" });
    socket.message({ kind: "interrupted", source: "user" });
    socket.message({ kind: "turn.status", state: "idle" });
    expect(session.state.listening).toBe(false);
    socket.message({ kind: "turn.status", state: "thinking" });
    finishTurn(socket, "Okay.");
    old.onend?.call(old, new Event("end") as SpeechSynthesisEvent);
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(["First.", "Okay."]);
    expect(session.state.transcript.map((entry) => entry.text)).toEqual([
      "First. Queued. **unfinished",
      "Actually, stop",
      "Okay.",
    ]);
    expect(session.state.error).toBeNull();
  });

  it("can interrupt queued playback after generation has finished and preserves interim text across the acknowledgement", async () => {
    const socket = await readySocket();
    finishTurn(socket, "First. Queued.");
    const recognition = FakeRecognition.instances.at(-1);
    ok(recognition);
    recognition.result([["Change", false]]);
    socket.message({ kind: "interrupted", source: "user" });
    socket.message({ kind: "turn.status", state: "idle" });
    expect(session.state.transcript.at(-1)).toMatchObject({ speaker: "user", text: "Change", final: false });
    recognition.result([["Change direction", true]]);
    recognition.end();
    expect(session.state.transcript.filter((entry) => entry.speaker === "user")).toEqual([
      { kind: "transcript", speaker: "user", text: "Change direction", final: true },
    ]);
    expect(fakeSynthesis.cancel).toHaveBeenCalledOnce();
    expect(fakeSynthesis.spoken).toHaveLength(1);
  });

  for (const isError of [false, true]) {
    it(`delivers a ${isError ? "failed" : "successful"} tool result during interruption without duplicating user text`, async () => {
      const socket = await readySocket();
      socket.message({ kind: "assistant.text.delta", text: "Checking. Queued. " });
      const recognition = FakeRecognition.instances.at(-1);
      ok(recognition);
      recognition.result([["Wait", false]]);
      const result = isError ? { error: "Save failed" } : { saved: true };
      callTool.mockResolvedValueOnce({ isError, structuredContent: result });
      socket.message({ kind: "tool.call", id: "dispatched", name: "save", args: { value: 3 } });
      expect(callTool).toHaveBeenCalledOnce();
      await Promise.resolve();
      expect(socket.sent).toContain(JSON.stringify({ kind: "tool.result", id: "dispatched", name: "save", result }));
      socket.message({ kind: "interrupted", source: "user" });
      socket.message({ kind: "turn.status", state: "idle" });
      expect(session.state.transcript.filter((entry) => entry.speaker === "user")).toEqual([
        { kind: "transcript", speaker: "user", text: "Wait", final: false },
      ]);
      recognition.result([["Wait, change", false]]);
      recognition.result([["Wait, change that", true]]);
      recognition.end();
      expect(session.state.transcript.filter((entry) => entry.speaker === "user")).toEqual([
        { kind: "transcript", speaker: "user", text: "Wait, change that", final: true },
      ]);
      if (isError) expect(session.state.transcript.at(-1)?.text).toBe("[save] Save failed");
      socket.message({ kind: "turn.status", state: "thinking" });
      finishTurn(socket, "Okay.");
      fakeSynthesis.complete();
      expect(session.state.listening).toBe(true);
      expect(session.state.activeTool).toBeNull();
      expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(["Checking.", "Okay."]);
    });
  }

  it("does not start playback if enabling recognition tears down the session", async () => {
    const socket = await readySocket();
    vi.spyOn(FakeRecognition.prototype, "start").mockImplementationOnce(() => {
      throw new Error("Recognition unavailable");
    });
    socket.message({ kind: "assistant.text.delta", text: "Hello. " });
    await Promise.resolve();
    expect(session.state.connected).toBe(false);
    expect(session.state.error).toBe("Recognition unavailable");
    expect(fakeSynthesis.spoken).toHaveLength(0);
  });

  it("restarts recognition during playback and accepts Android's one-shot interruption", async () => {
    vi.useFakeTimers();
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "Chrome Android" });
    const socket = await readySocket();
    socket.message({ kind: "assistant.text.delta", text: "First. Queued. " });
    const original = FakeRecognition.instances.at(-1);
    ok(original);
    original.end();
    vi.advanceTimersByTime(250);
    const recognition = FakeRecognition.instances.at(-1);
    ok(recognition);
    expect(recognition).not.toBe(original);
    expect(session.state.speaking).toBe(true);
    expect(session.state.listening).toBe(true);
    recognition.result([["Stop", true]]);
    expect(session.state.speaking).toBe(false);
    expect(session.state.listening).toBe(false);
    expect(socket.sent.slice(-2).map((text) => JSON.parse(text))).toEqual([
      { kind: "turn.cancel" },
      { kind: "user.message", text: "Stop" },
    ]);
  });

  it("releases a pending interruption on disconnect and ignores the old acknowledgement after reconnect", async () => {
    const socket = await readySocket();
    finishTurn(socket, "First reply.");
    FakeRecognition.instances.at(-1)?.result([["Stop", false]]);
    session.disconnect();
    const replacement = await readySocket();
    socket.message({ kind: "interrupted", source: "user" });
    finishTurn(replacement, "Fresh reply.");
    expect(session.state.connected).toBe(true);
    expect(session.state.speaking).toBe(true);
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Fresh reply.");
  });

  it("uses the saved browser voice for gateway replies and prevents changes during the session", async () => {
    const selected = { name: "Selected", lang: "en-US", voiceURI: "selected" } as SpeechSynthesisVoice;
    fakeSynthesis.voices = [selected];
    session.selectBrowserVoice("selected");
    session.selectBrowserSpeechRate(1.5);
    const socket = await readySocket();
    socket.message({ kind: "assistant.text.delta", text: "Hello. " });
    expect(fakeSynthesis.spoken[0]?.voice).toBe(selected);
    expect(fakeSynthesis.spoken[0]?.rate).toBe(1.5);
    socket.message({ kind: "assistant.text.delta", text: "Next. " });
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken[1]?.rate).toBe(1.5);
    expect(() => session.selectBrowserVoice("")).toThrow("End the session");
  });

  it("streams ordered sentences before idle, preserves decimals and finalizes text before playback drains", async () => {
    const socket = await readySocket();
    socket.message({ kind: "turn.status", state: "thinking" });
    session.setMicrophoneMode("muteOnce");
    const chunks = ["The value is 3.", "14. ", '"Next?', '" ', "Final tail"];
    socket.message({ kind: "assistant.text.delta", text: chunks[0] });
    expect(fakeSynthesis.spoken).toHaveLength(0);
    socket.message({ kind: "assistant.text.delta", text: chunks[1] });
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(["The value is 3.14."]);
    expect(session.state.transcript.at(-1)?.final).toBe(false);
    expect(earcon.setThinking).toHaveBeenLastCalledWith(false);
    for (const text of chunks.slice(2)) socket.message({ kind: "assistant.text.delta", text });
    expect(fakeSynthesis.spoken).toHaveLength(1);
    socket.message({ kind: "turn.status", state: "idle" });
    expect(session.state.transcript.at(-1)).toEqual({
      kind: "transcript",
      speaker: "assistant",
      text: chunks.join(""),
      final: true,
    });
    expect(session.state.listening).toBe(false);
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe('"Next?"');
    expect(session.state.microphoneMode).toBe("muteOnce");
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Final tail");
    expect(session.state.listening).toBe(false);
    fakeSynthesis.complete();
    expect(session.state.microphoneMode).toBe("listening");
    expect(session.state.listening).toBe(true);
  });

  it("queues an alert behind the submitted greeting before the gateway reports thinking", async () => {
    const socket = await readySocket();
    session.injectText("Build is waiting", true);
    expect(socket.sent.some((text) => text.includes("Build is waiting"))).toBe(false);
    socket.message({ kind: "turn.status", state: "idle" });
    expect(JSON.parse(socket.sent.at(-1) ?? "{}")).toEqual({
      kind: "context.update",
      context: { text: "Build is waiting" },
      generate: true,
    });
  });

  it("keeps capture through playback gaps, pauses for tool waits, and buffers notifications until playback drains", async () => {
    const socket = await readySocket();
    socket.message({ kind: "turn.status", state: "thinking" });
    session.injectText("Notification", true);
    expect(socket.sent.some((text) => text.includes("Notification"))).toBe(false);
    socket.message({ kind: "assistant.text.delta", text: "One. " });
    fakeSynthesis.complete();
    expect(session.state.speaking).toBe(false);
    expect(session.state.listening).toBe(true);
    expect(FakeRecognition.instances).toHaveLength(1);
    expect(earcon.setThinking).toHaveBeenLastCalledWith(false);
    const stream = await vi.mocked(navigator.mediaDevices.getUserMedia).mock.results[0]!.value;
    expect(stream.getAudioTracks()[0]!.enabled).toBe(true);
    let finish!: (value: Awaited<ReturnType<typeof mcpClient.callTool>>) => void;
    callTool.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    socket.message({ kind: "tool.call", id: "wait", name: "items_list", args: {} });
    socket.message({ kind: "turn.status", state: "thinking" });
    expect(session.state.listening).toBe(false);
    FakeRecognition.instances.at(-1)?.end();
    expect(socket.sent.some((text) => text.includes("Notification"))).toBe(false);
    finish({ structuredContent: {}, isError: false });
    await Promise.resolve();
    socket.message({ kind: "assistant.text.delta", text: "Two. " });
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Two.");
    socket.message({ kind: "turn.status", state: "idle" });
    expect(socket.sent.some((text) => text.includes("Notification"))).toBe(false);
    fakeSynthesis.complete();
    expect(socket.sent.at(-1)).toBe(
      JSON.stringify({ kind: "context.update", context: { text: "Notification" }, generate: true }),
    );
    expect(session.state.listening).toBe(true);
  });

  it("cancels fragments and isolates stale synthesis callbacks from a replacement reply", async () => {
    const socket = await readySocket();
    socket.message({ kind: "assistant.text.delta", text: "Old. Queued. **unfinished" });
    const old = fakeSynthesis.spoken[0];
    ok(old);
    socket.message({ kind: "interrupted", source: "user" });
    expect(fakeSynthesis.cancel).toHaveBeenCalledOnce();
    expect(session.state.speaking).toBe(false);
    socket.message({ kind: "assistant.text.delta", text: "Fresh. Next. " });
    const fresh = fakeSynthesis.spoken.at(-1);
    ok(fresh);
    old.onend?.call(old, new Event("end") as SpeechSynthesisEvent);
    old.onerror?.call(old, { error: "synthesis-failed" } as SpeechSynthesisErrorEvent);
    expect(session.state.error).toBeNull();
    expect(session.state.speaking).toBe(true);
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(["Old.", "Fresh."]);
    socket.message({ kind: "turn.status", state: "idle" });
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Next.");
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
    expect(session.state.transcript.map((entry) => entry.text)).toEqual(["Old. Queued. **unfinished", "Fresh. Next. "]);
  });

  it("releases queued speech on synthesis failure and ignores its late callbacks after reconnect", async () => {
    const socket = await readySocket();
    socket.message({ kind: "assistant.text.delta", text: "First. Second. " });
    const old = fakeSynthesis.spoken[0];
    ok(old);
    old.onerror?.call(old, { error: "audio-busy" } as SpeechSynthesisErrorEvent);
    expect(session.state.error).toContain("audio-busy");
    expect(session.state.connected).toBe(false);
    expect(socket.readyState).toBe(3);
    finishTurn(await readySocket(), "Replacement");
    old.onend?.call(old, new Event("end") as SpeechSynthesisEvent);
    expect(session.state.speaking).toBe(true);
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
  });

  it("surfaces synchronous synthesis failures during queue advancement and resets the next reply", async () => {
    const socket = await readySocket();
    socket.message({ kind: "assistant.text.delta", text: "One. Two. " });
    fakeSynthesis.speak.mockImplementationOnce(() => {
      throw new Error("engine unavailable");
    });
    fakeSynthesis.complete();
    expect(session.state.error).toContain("engine unavailable");
    expect(session.state.connected).toBe(false);
    finishTurn(await readySocket(), "Fresh");
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Fresh");
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
  });

  it("stops segmentation after synchronous synthesis startup failure", async () => {
    const socket = await readySocket();
    fakeSynthesis.speak.mockImplementationOnce(() => {
      throw new Error("startup failed");
    });
    socket.message({ kind: "assistant.text.delta", text: "One. Old tail" });
    await Promise.resolve();
    expect(session.state.error).toContain("startup failed");
    expect(session.state.transcript.at(-1)?.text).toBe("One. Old tail");
    finishTurn(await readySocket(), "Fresh");
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Fresh");
  });

  it("buffers a long reply and reads every sentence in order before reopening mute-once capture", async () => {
    const socket = await readySocket();
    socket.message({ kind: "turn.status", state: "thinking" });
    session.setMicrophoneMode("muteOnce");
    session.injectText("Notification");
    const sentences = Array.from({ length: 200 }, (_, index) => `Sentence ${index} ${"word ".repeat(20)}ends.`);
    const text = sentences.join(" ") + " ";
    socket.message({ kind: "assistant.text.delta", text });
    await Promise.resolve();
    expect(session.state.error).toBeNull();
    expect(session.state.connected).toBe(true);
    expect(fakeSynthesis.cancel).not.toHaveBeenCalled();
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual([sentences[0]]);
    socket.message({ kind: "assistant.text.delta", text: "Final tail" });
    socket.message({ kind: "turn.status", state: "idle" });
    expect(session.state.transcript).toEqual([
      { kind: "transcript", speaker: "assistant", text: text + "Final tail", final: true },
    ]);
    expect(session.state.microphoneMode).toBe("muteOnce");
    expect(socket.sent.some((message) => message.includes("Notification"))).toBe(false);
    for (let index = 0; index < sentences.length; index++) {
      expect(fakeSynthesis.spoken.at(-1)?.text).toBe(sentences[index]);
      fakeSynthesis.complete();
      expect(session.state.microphoneMode).toBe("muteOnce");
    }
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Final tail");
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual([...sentences, "Final tail"]);
    expect(session.state.errorHistory).toHaveLength(0);
    expect(session.state.microphoneMode).toBe("listening");
    expect(session.state.listening).toBe(true);
    expect(socket.sent.some((message) => message.includes("Notification"))).toBe(true);
  });

  it("discards a long buffered reply on barge-in and speaks its replacement", async () => {
    const socket = await readySocket();
    socket.message({ kind: "assistant.text.delta", text: "Sentence. ".repeat(2000) });
    const old = fakeSynthesis.spoken[0];
    ok(old);
    const recognition = FakeRecognition.instances.at(-1);
    ok(recognition);
    recognition.result([["Stop", false]]);
    expect(session.state.speaking).toBe(false);
    expect(socket.sent.at(-1)).toBe(JSON.stringify({ kind: "turn.cancel" }));
    recognition.result([["Stop", true]]);
    recognition.end();
    socket.message({ kind: "interrupted", source: "user" });
    socket.message({ kind: "turn.status", state: "thinking" });
    finishTurn(socket, "Fresh reply.");
    old.onend?.call(old, new Event("end") as SpeechSynthesisEvent);
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(["Sentence.", "Fresh reply."]);
    expect(session.state.speaking).toBe(true);
    expect(session.state.error).toBeNull();
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
  });

  it("keeps unread speech when the visible transcript is cleared during generation or playback", async () => {
    const socket = await readySocket();
    socket.message({ kind: "assistant.text.delta", text: "First. Second. " });
    session.clearTranscript();
    expect(session.state.transcript).toHaveLength(0);
    socket.message({ kind: "assistant.text.delta", text: "Third. Final tail" });
    socket.message({ kind: "turn.status", state: "idle" });
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Second.");
    session.clearTranscript();
    for (let index = 0; index < 3; index++) fakeSynthesis.complete();
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(["First.", "Second.", "Third.", "Final tail"]);
    expect(session.state.transcript).toHaveLength(0);
    expect(session.state.connected).toBe(true);
    expect(session.state.error).toBeNull();
    expect(session.state.listening).toBe(true);
  });

  it("reports a synthesis failure during buffered playback and clears the backlog on reconnect", async () => {
    const socket = await readySocket();
    socket.message({ kind: "assistant.text.delta", text: "Sentence. ".repeat(2000) });
    expect(session.state.connected).toBe(true);
    fakeSynthesis.speak.mockImplementationOnce(() => {
      throw new Error("Synthesis unavailable");
    });
    fakeSynthesis.complete();
    expect(session.state.connected).toBe(false);
    expect(session.state.error).toContain("Synthesis unavailable");
    finishTurn(await readySocket(), "Fresh reply");
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Fresh reply");
    expect(session.state.error).toBeNull();
  });

  it("cancels queued fragments on disconnect without reviving capture", async () => {
    const socket = await readySocket();
    socket.message({ kind: "assistant.text.delta", text: "First. Second. Tail" });
    const old = fakeSynthesis.spoken[0];
    ok(old);
    session.disconnect();
    old.onend?.call(old, new Event("end") as SpeechSynthesisEvent);
    expect(fakeSynthesis.spoken).toHaveLength(1);
    expect(FakeRecognition.instances).toHaveLength(1);
    expect(FakeRecognition.instances[0]?.aborted).toBe(true);
    expect(session.state.listening).toBe(false);
    expect(session.state.speaking).toBe(false);
  });

  it("speaks readable formatting while retaining the raw assistant transcript", async () => {
    const socket = await readySocket();
    const raw =
      "## Result\n- **Read** [the guide](https://example.com/a.b) and `snake_case`; 2 * 3 = 6, _private \\*literal\\* **open";
    for (const text of raw) socket.message({ kind: "assistant.text.delta", text });
    socket.message({ kind: "turn.status", state: "idle" });
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual([
      "Result\nRead the guide and snake_case; 2 * 3 = 6, _private *literal* **open",
    ]);
    expect(session.state.transcript.at(-1)?.text).toBe(raw);
    fakeSynthesis.complete();
    const boundaries =
      "*`a`*`b` and **[Step 1]**(optional); [glob *.ts](https://example.com/docs/*.ts); " +
      "[_private](url) for _emphasis_; **int *a** and **int *b**";
    for (const text of boundaries) socket.message({ kind: "assistant.text.delta", text });
    socket.message({ kind: "turn.status", state: "idle" });
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe(
      "ab and Step 1(optional); glob *.ts; _private for emphasis; int *a and int *b",
    );
    expect(session.state.transcript.at(-1)?.text).toBe(boundaries);
    fakeSynthesis.complete();
    finishTurn(socket, "1");
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("1");
    fakeSynthesis.complete();
    socket.message({ kind: "assistant.text.delta", text: "[unfinished](https://" });
    socket.message({ kind: "interrupted", source: "user" });
    finishTurn(socket, "**Fresh**");
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Fresh");
    fakeSynthesis.complete();
    socket.message({ kind: "assistant.text.delta", text: "```kotlin" });
    session.disconnect();
    finishTurn(await readySocket(), "New reply");
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("New reply");
  });

  it("meters quiet speech and silence, keeps capture during replies, and releases capture", async () => {
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
    expect(session.state.micLevel).toBeGreaterThan(0.3);
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
    // An incomplete sentence still needs thinking feedback during tool waits.
    socket.message({ kind: "assistant.text.delta", text: "Working on it" });
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

  it("publishes the host name for a browser-speech call using an external gateway", async () => {
    const mediaSession = { metadata: null as MediaMetadata | null, playbackState: "none" };
    Object.defineProperty(navigator, "mediaSession", { configurable: true, value: mediaSession });
    vi.stubGlobal(
      "MediaMetadata",
      class {
        readonly title: string;
        constructor(init: MediaMetadataInit) {
          this.title = init.title ?? "";
        }
      },
    );
    const gatewayFetch = globalThis.fetch;
    globalThis.fetch = (input, init) =>
      String(input) === "/.well-known/gomode.json"
        ? Promise.resolve(new Response(JSON.stringify({ service: "Home Server" })))
        : gatewayFetch(input, init);
    try {
      await readySocket();
      await vi.waitFor(() => expect(mediaSession.metadata?.title).toBe("Home Server"));
      expect(mediaSession.playbackState).toBe("playing");
      session.disconnect();
      expect(mediaSession.metadata).toBeNull();
      expect(mediaSession.playbackState).toBe("none");
    } finally {
      session.disconnect();
      Reflect.deleteProperty(navigator, "mediaSession");
      vi.unstubAllGlobals();
    }
  });
  it("uses a text choice and one-shot Android recognition when an audio-only choice was saved", async () => {
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "Chrome Android" });
    localStorage.setItem("gomode.voiceChoice.https://gateway.example.webrtc", "gemini");
    const gatewayFetch = globalThis.fetch;
    globalThis.fetch = async (input, init) =>
      String(input).endsWith("/voice/choices")
        ? new Response(
            JSON.stringify({
              choices: [
                { id: "gemini", label: "Gemini Live", transports: ["webrtc"] },
                { id: "remote", label: "Remote LLM", transports: ["text"] },
              ],
              defaults: [
                { transport: "webrtc", id: "gemini" },
                { transport: "text", id: "remote" },
              ],
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
    expect(socket.url.toString()).toBe("wss://gateway.example/api/voicegateway/v2/voice/text/browser");
    expect(socket.protocols).toEqual(["gomode.text.v2", "gomode.ticket.one-time-ticket"]);
    expect(JSON.parse(socket.sent[0] ?? "{}").voice.language).toBe("fr-CA");
    expect(JSON.parse(socket.sent[0] ?? "{}").speech).toEqual({ input: "client", output: "client" });
    expect(JSON.parse(socket.sent[0] ?? "{}")).not.toHaveProperty("recognitionModelID");
    expect(JSON.parse(socket.sent[0] ?? "{}")).not.toHaveProperty("synthesisModelID");
    expect(JSON.parse(socket.sent[0] ?? "{}").voice).not.toHaveProperty("name");
    expect(new VoiceSession(chime).state.speechInput).toBe("client");
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
              defaults: [{ transport: "text", id: "remote" }],
            }),
          )
        : gatewayFetch(input, init);
    await session.loadVoiceChoices();
    session.selectChoice("local");
    await readySocket();
    expect(requests[0]?.body).toEqual({ choiceID: "local" });
    expect(JSON.parse(localStorage.getItem("gomode.voiceSetup.https://gateway.example")!).partsChoiceID).toBe("local");
  });

  it("authenticates same-origin ticket issuance with the host bearer provider", async () => {
    configureVoiceGateway("/", null, () => "host-bearer");
    session.selectSpeechInput("client");
    session.selectSpeechOutput("client");
    await readySocket();
    expect(requests[0]?.authorization).toBe("Bearer host-bearer");
    expect(requests[0]?.body).toEqual({ choiceID: "remote" });
  });

  it("listens during playback and keeps one copy of user text", async () => {
    const socket = await readySocket();
    expect(FakeRecognition.instances).toHaveLength(0);
    finishTurn(socket, "Ready");
    expect(session.state.speaking).toBe(true);
    expect(session.state.listening).toBe(true);
    expect(FakeRecognition.instances).toHaveLength(1);
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
      { kind: "transcript", speaker: "user", text: "I need help", final: true },
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
    expect(session.state.listening).toBe(false);
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
    expect(session.state.listening).toBe(true);
    expect(session.state.microphoneMode).toBe("listening");
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

  it("mutes recognition until the reply finishes and automatically resumes", async () => {
    const socket = await readySocket();
    finishTurn(socket, "Ready");
    for (const id of ["mute-1", "mute-2"]) {
      socket.message({ kind: "tool.call", id, name: "mute", args: {} });
      await vi.waitFor(() =>
        expect(socket.sent.map((text) => JSON.parse(text))).toContainEqual({
          kind: "tool.result",
          id,
          name: "mute",
          result: {},
        }),
      );
    }
    expect(session.state.microphoneMode).toBe("muteOnce");
    expect(session.state.connected).toBe(true);
    const recognition = FakeRecognition.instances.at(-1);
    ok(recognition);
    expect(recognition.aborted).toBe(true);
    recognition.result([["Ignored", true]]);
    recognition.end();
    expect(session.state.listening).toBe(false);
    expect(socket.sent.some((text) => JSON.parse(text).kind === "turn.cancel")).toBe(false);
    expect(session.state.speaking).toBe(true);
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
    expect(session.state.microphoneMode).toBe("listening");
    expect(FakeRecognition.instances).toHaveLength(2);
    FakeRecognition.instances.at(-1)?.result([["Stop", false]]);
    expect(session.state.speaking).toBe(false);
  });

  it("hang_up closes speech and ignores late transport or speech callbacks", async () => {
    const socket = await readySocket();
    finishTurn(socket, "Ready");
    socket.message({ kind: "tool.call", id: "hangup", name: "hang_up", args: {} });
    expect(session.state.connected).toBe(false);
    expect(socket.readyState).toBe(3);
    fakeSynthesis.complete();
    socket.message({ kind: "assistant.text.delta", text: "stale" });
    expect(FakeRecognition.instances).toHaveLength(1);
    expect(FakeRecognition.instances[0]?.aborted).toBe(true);
    expect(session.state.listening).toBe(false);
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
    const options = { sessionID: callTool.mock.calls[0]?.[2]?.sessionID };
    expect(mcpClient.serverInstructions).toHaveBeenCalledWith(options);
    expect(mcpClient.readAdvertisedTextResource).toHaveBeenCalledWith("gomode://items", undefined, options);
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

  it("drains and resets streamed speech after a recoverable turn failure without voicing diagnostics", async () => {
    const socket = await readySocket();
    session.setMicrophoneMode("muteOnce");
    socket.message({ kind: "turn.status", state: "thinking" });
    const raw = "First. Queued. tail **unfinished";
    socket.message({ kind: "assistant.text.delta", text: raw });
    const first = fakeSynthesis.spoken[0];
    ok(first);
    socket.message({ kind: "error", message: "LLM failed: provider unavailable", recoverable: true });
    expect(session.state.connected).toBe(true);
    expect(session.state.error).toBeNull();
    expect(session.state.errorHistory.map((entry) => entry.message)).toEqual(["LLM failed: provider unavailable"]);
    expect(session.state.transcript.at(-1)?.text).toBe("[Voice] LLM failed: provider unavailable");
    expect(fakeSynthesis.cancel).not.toHaveBeenCalled();
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(["First."]);

    // A failed generation still ends with idle; playback must drain its partial
    // reply, flush both buffered tails, and only then reopen capture.
    socket.message({ kind: "turn.status", state: "idle" });
    expect(session.state.transcript.every((entry) => entry.final)).toBe(true);
    expect(session.state.transcript[0]?.text).toBe(raw);
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Queued.");
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("tail **unfinished");
    expect(session.state.listening).toBe(false);
    expect(session.state.microphoneMode).toBe("muteOnce");
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
    expect(session.state.microphoneMode).toBe("listening");
    expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(["First.", "Queued.", "tail **unfinished"]);

    finishTurn(socket, "**Fresh**. Next. ");
    first.onend?.call(first, new Event("end") as SpeechSynthesisEvent);
    first.onerror?.call(first, { error: "synthesis-failed" } as SpeechSynthesisErrorEvent);
    expect(session.state.connected).toBe(true);
    expect(session.state.error).toBeNull();
    expect(session.state.errorHistory).toHaveLength(1);
    expect(session.state.speaking).toBe(true);
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Fresh.");
    fakeSynthesis.complete();
    expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Next.");
    fakeSynthesis.complete();
    expect(session.state.listening).toBe(true);
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

it("routes discovered standalone text tickets and WebSockets through the gateway base path", async () => {
  const origin = window.location.origin;
  const endpoints = resolveBrowserVoiceEndpoints(
    {
      apiVersion: 1,
      service: "voice-gateway",
      webShell: { voiceGateway: { url: "/test/" }, toolGroups: [{ name: "test", endpoint: "/api/mcp" }] },
    },
    { service: "voice-gateway", toolGroup: "test" },
    origin,
  );
  ok(endpoints);
  configureVoiceGateway(endpoints.gatewayURL, null);
  session.selectSpeechInput("client");
  session.selectSpeechOutput("client");
  const socket = await readySocket();
  expect(requests[0]?.url).toBe(origin + "/test/api/voicegateway/v2/voice/text/ticket");
  const expected = new URL("/test/api/voicegateway/v2/voice/text/browser", origin);
  expected.protocol = expected.protocol === "https:" ? "wss:" : "ws:";
  expect(socket.url.href).toBe(expected.href);
  expect(socket.protocols).toEqual(["gomode.text.v2", "gomode.ticket.one-time-ticket"]);
});
