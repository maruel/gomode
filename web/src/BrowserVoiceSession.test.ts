// Tests browser-speech voice sessions over the authenticated text gateway transport.

import { afterEach, beforeEach, describe, it } from "node:test";
import { expect, vi } from "../tests/expect";
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
  requests = [];
  globalThis.fetch = async (input, init) => {
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
  session = new VoiceSession(chime);
  session.selectMode("browser");
});
afterEach(() => {
  session.disconnect();
  restore();
  globalThis.fetch = originalFetch;
  vi.useRealTimers();
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
      service: { kind: "web", instanceID: "one", baseURL: "https://host.example", token: "scoped-token" },
    });
    expect(socket.url.toString()).toBe("wss://gateway.example/api/voicegateway/v1/voice/text/browser");
    expect(socket.protocols).toEqual(["gomode.text.v1", "gomode.ticket.one-time-ticket"]);
    expect(JSON.parse(socket.sent[0] ?? "{}").voice.language).toBe("fr-CA");
    expect(new VoiceSession(chime).state.mode).toBe("browser");
    expect(localStorage.getItem("gomode.voiceLanguage")).toBe("fr-CA");
  });

  it("authenticates same-origin ticket issuance with the host bearer provider", async () => {
    configureVoiceGateway("/", null, () => "host-bearer");
    await readySocket();
    expect(requests[0]?.authorization).toBe("Bearer host-bearer");
    expect(requests[0]?.body).toEqual({});
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

  it("mutes recognition and unmuting resumes only while the assistant is idle", async () => {
    const socket = await readySocket();
    finishTurn(socket, "Ready");
    session.toggleMute();
    fakeSynthesis.complete();
    expect(FakeRecognition.instances).toHaveLength(0);
    session.toggleMute();
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
    const socket = await readySocket();
    socket.message({ kind: "tool.call", id: "pending", name: "items_list", args: {} });
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
    globalThis.fetch = () =>
      new Promise((resolve) => {
        finish = resolve;
      });
    const connect = session.connect();
    await vi.waitFor(() => expect(session.state.connectStatus).not.toBeNull());
    await Promise.resolve();
    session.disconnect();
    finish(new Response(JSON.stringify({ ticket: "late" })));
    await connect;
    expect(FakeWebSocket.instances).toHaveLength(0);
  });

  it("suppresses chimes on iOS and bounds text setup time", async () => {
    vi.useFakeTimers();
    Object.defineProperty(navigator, "userAgent", { configurable: true, value: "iPhone Safari" });
    await readySocket();
    expect(chime.prepare).not.toHaveBeenCalled();
    expect(chime.playConnected).not.toHaveBeenCalled();
    session.disconnect();
    expect(chime.playDisconnected).not.toHaveBeenCalled();
    await session.connect();
    vi.advanceTimersByTime(15000);
    expect(session.state.error).toContain("timed out");
  });
});
