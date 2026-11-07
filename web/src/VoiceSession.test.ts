// Tests for the browser voice gateway session manager.

import { readFileSync } from "node:fs";
import { afterEach, beforeEach, describe, it } from "node:test";
import { createEffect, createRoot } from "solid-js";
import { expect, vi } from "../tests/expect";
import { FakeMicrophoneContext, microphoneFrames } from "../tests/microphone-audio";

import {
  buildRecoveryContext,
  formatVoiceRTCDiagnostics,
  MAX_RECOVERY_CONTEXT_CHARS,
  previousErrors,
  summarizeSDPCandidates,
  VoiceSession,
  configureVoiceGateway,
  voiceToolDeclarations,
} from "./VoiceSession";
import {
  captureFrontendVoiceTools,
  createFrontendPreparationClient,
  type FrontendVoiceToolContext,
} from "./FrontendVoiceTools";
import { mcpClient } from "./McpClient";
import { FakeRecognition, fakeSynthesis, installBrowserSpeech } from "../tests/browser-speech";

// MCP calls are spied on; gateway signaling is exercised through fetch.
const mcpMocks = {
  mcpCallTool: vi.spyOn(mcpClient, "callTool"),
  mcpListTools: vi.spyOn(mcpClient, "listTools"),
  mcpReadAdvertisedTextResource: vi.spyOn(mcpClient, "readAdvertisedTextResource"),
  mcpServerInstructions: vi.spyOn(mcpClient, "serverInstructions"),
};
import {
  MessageKindError,
  MessageKindSessionReady,
  MessageKindToolCall,
  MessageKindTurnStatus,
  TurnStateIdle,
  TurnStateThinking,
  VoiceRTCConnectivityIssueUDPUnreachable,
  VoiceRTCConnectivitySideNetwork,
  type VoiceRTCAnswerResp,
  type VoiceRTCDiagnosticsResp,
} from "../../sdk/voicegateway/ts/v2/types.gen";

class FakePeerConnection extends EventTarget {
  readonly sender = { track: { kind: "audio" }, replaceTrack: vi.fn(async (_track: MediaStreamTrack) => {}) };
  readonly addedTracks: MediaStreamTrack[] = [];

  getSenders() {
    return [this.sender];
  }
  static completeICE = true;
  static reflexiveCandidateDelayMs: number | null = null;
  static last: FakePeerConnection | null = null;
  static instances: FakePeerConnection[] = [];
  static dataChannels: FakeDataChannel[] = [];

  connectionState: RTCPeerConnectionState = "new";
  iceConnectionState: RTCIceConnectionState = "new";
  iceGatheringState: RTCIceGatheringState = "new";
  localDescription: RTCSessionDescription | null = null;
  oniceconnectionstatechange: ((this: RTCPeerConnection, ev: Event) => unknown) | null = null;
  ontrack: ((this: RTCPeerConnection, ev: RTCTrackEvent) => unknown) | null = null;

  constructor() {
    super();
    FakePeerConnection.last = this;
    FakePeerConnection.instances.push(this);
  }

  readonly addTransceiver = vi.fn();

  addTrack(track: MediaStreamTrack): void {
    this.addedTracks.push(track);
  }

  createDataChannel(): RTCDataChannel {
    const channel = new FakeDataChannel();
    FakePeerConnection.dataChannels.push(channel);
    return channel as unknown as RTCDataChannel;
  }

  createOffer(): Promise<RTCSessionDescriptionInit> {
    return Promise.resolve({ type: "offer", sdp: "initial-offer" });
  }

  setLocalDescription(): Promise<void> {
    this.iceGatheringState = "gathering";
    this.localDescription = { type: "offer", sdp: "initial-offer" } as RTCSessionDescription;
    queueMicrotask(() => {
      this.localDescription = {
        type: "offer",
        sdp: "v=0\r\na=candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host\r\n",
      } as RTCSessionDescription;
      const candidateEvent = new Event("icecandidate");
      Object.defineProperty(candidateEvent, "candidate", {
        value: { candidate: "candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host" },
      });
      this.dispatchEvent(candidateEvent);
      if (FakePeerConnection.reflexiveCandidateDelayMs !== null) {
        window.setTimeout(() => {
          this.localDescription = {
            type: "offer",
            sdp: "v=0\r\na=candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host\r\na=candidate:2 1 udp 1694498815 203.0.113.2 50000 typ srflx raddr 192.0.2.2 rport 50000\r\n",
          } as RTCSessionDescription;
          const reflexiveCandidateEvent = new Event("icecandidate");
          Object.defineProperty(reflexiveCandidateEvent, "candidate", {
            value: {
              candidate: "candidate:2 1 udp 1694498815 203.0.113.2 50000 typ srflx raddr 192.0.2.2 rport 50000",
            },
          });
          this.dispatchEvent(reflexiveCandidateEvent);
        }, FakePeerConnection.reflexiveCandidateDelayMs);
      }
      if (FakePeerConnection.completeICE) {
        this.iceGatheringState = "complete";
        this.dispatchEvent(new Event("icegatheringstatechange"));
      }
    });
    return Promise.resolve();
  }

  setRemoteDescription(): Promise<void> {
    return Promise.resolve();
  }

  close(): void {}
}

class FakeDataChannel {
  readonly send = vi.fn();
  readyState: RTCDataChannelState = "open";
  onclose: (() => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onopen: (() => void) | null = null;

  close(): void {
    this.readyState = "closed";
  }
}

function dispatchToolCall(channel: FakeDataChannel | undefined, id: string, name: string): void {
  channel?.onmessage?.(
    new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindToolCall, id, name, args: {} }) }),
  );
}

function deferred<T>() {
  let resolve: (value: T) => void = () => {
    throw new Error("Deferred promise was not initialized");
  };
  let reject: (reason: unknown) => void = () => {
    throw new Error("Deferred promise was not initialized");
  };
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function outgoingMicrophoneTrack(): MediaStreamTrack | undefined {
  return [...FakeMicrophoneContext.instances]
    .reverse()
    .find((context) => context.destinations.length > 0)
    ?.destinations.at(-1)
    ?.stream.getAudioTracks()[0];
}

const originalFetch = globalThis.fetch;
let closeRequests: Array<{ url: string; authorization: string | null }> = [];
let offerRequests: Array<{ url: string; authorization: string | null; body: unknown }> = [];
let diagnosticRequests: Array<{ url: string; authorization: string | null }> = [];
let offerResponse: Promise<Response> | null = null;
let diagnosticResponse: VoiceRTCDiagnosticsResp | null = null;
let offeredVoices: string[] | null = null;
let mixedSpeech = false;
let independentSpeech = false;

function serviceToken(subject: string, serial: number): string {
  const claims = {
    serviceKind: "caic",
    serviceInstanceID: "home",
    backendOrigin: "https://caic.example.com",
    sub: subject,
  };
  return `${btoa(JSON.stringify(claims)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "")}.${serial}`;
}

function oauthToken(subject: string, serial: number): string {
  const encode = (value: unknown) =>
    btoa(JSON.stringify(value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
  return `${encode({ alg: "ES256", kid: "test-key", typ: "at+jwt" })}.${encode({ iss: "https://caic.example.com", sub: subject, aud: "voice-gateway", scope: "voice.session", serial })}.signature-${serial}`;
}

afterEach(() => {
  globalThis.fetch = originalFetch;
});

beforeEach(() => {
  vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
  localStorage.clear();
  closeRequests = [];
  offerRequests = [];
  diagnosticRequests = [];
  offerResponse = null;
  diagnosticResponse = null;
  offeredVoices = null;
  mixedSpeech = false;
  independentSpeech = false;
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    const authorization = new Headers(init?.headers).get("Authorization");
    if (url.endsWith("/voice/choices"))
      return new Response(
        JSON.stringify({
          choices: [
            {
              id: "gemini",
              label: "Gemini Live",
              transports: ["webrtc", "text"],
              ...(independentSpeech ? { composable: true } : {}),
              ...(offeredVoices === null ? {} : { voices: offeredVoices }),
              ...(mixedSpeech
                ? {
                    speechModes: [
                      { input: "gateway", output: "gateway" },
                      { input: "gateway", output: "client" },
                      { input: "client", output: "gateway" },
                      { input: "client", output: "client" },
                    ],
                  }
                : {}),
            },
          ],
          ...(independentSpeech
            ? {
                recognitionModels: [
                  { id: "asr-a", label: "ASR A" },
                  { id: "asr-b", label: "ASR B" },
                ],
                synthesisModels: [
                  { id: "tts-a", label: "TTS A" },
                  { id: "tts-b", label: "TTS B" },
                ],
              }
            : {}),
          defaults: [
            { transport: "webrtc", id: "gemini" },
            { transport: "text", id: "gemini" },
          ],
        }),
      );
    if (url.endsWith("/voice/rtc/offer")) {
      offerRequests.push({ url, authorization, body: JSON.parse(String(init?.body)) });
      return offerResponse ?? new Response(JSON.stringify({ sdp: "answer-sdp", sessionID: "session-1" }));
    }
    if (url.endsWith("/diagnostics")) {
      diagnosticRequests.push({ url, authorization });
      return new Response(JSON.stringify(diagnosticResponse ?? {}));
    }
    closeRequests.push({ url: String(input), authorization: new Headers(init?.headers).get("Authorization") });
    return new Response(JSON.stringify({ status: "closed" }));
  };
  configureVoiceGateway("/", null);
  FakePeerConnection.completeICE = true;
  FakePeerConnection.reflexiveCandidateDelayMs = null;
  FakePeerConnection.last = null;
  FakePeerConnection.instances = [];
  FakePeerConnection.dataChannels = [];
  mcpMocks.mcpCallTool.mockReset();
  mcpMocks.mcpListTools.mockReset();
  mcpMocks.mcpListTools.mockResolvedValue([]);
  mcpMocks.mcpReadAdvertisedTextResource.mockReset();
  mcpMocks.mcpReadAdvertisedTextResource.mockResolvedValue('{"items":[]}');
  mcpMocks.mcpServerInstructions.mockReset();
  mcpMocks.mcpServerInstructions.mockResolvedValue("instructions");
  vi.stubGlobal("RTCPeerConnection", FakePeerConnection as unknown as typeof RTCPeerConnection);
  vi.stubGlobal("AudioContext", FakeMicrophoneContext as unknown as typeof AudioContext);
  vi.stubGlobal(
    "requestAnimationFrame",
    vi.fn(() => 1),
  );
  Object.defineProperty(navigator, "mediaDevices", {
    configurable: true,
    value: {
      enumerateDevices: vi.fn(async () => []),
      getUserMedia: vi.fn(async () => ({
        getAudioTracks: () => [{}],
        getTracks: () => [],
      })),
    },
  });
});

describe("VoiceSession", () => {
  for (const native of [true, false]) {
    it(`prepares real audio for the cold restored ${native ? "native" : "browser"} setup`, async () => {
      const restore = installBrowserSpeech("Chrome desktop");
      configureVoiceGateway("https://unrelated.example", null);
      const chime = { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() };
      const thinking = { prepare: vi.fn(), setThinking: vi.fn() };
      const session = new VoiceSession(chime, thinking);
      session.setState((s) => ({
        ...s,
        speechInput: native ? "client" : "gateway",
        speechOutput: native ? "client" : "gateway",
      }));
      configureVoiceGateway("https://cold.example", null);
      localStorage.setItem(
        "gomode.voiceSetup.https://cold.example",
        JSON.stringify({
          mode: native ? "all-in-one" : "parts",
          nativeChoiceID: "native",
          partsChoiceID: "parts",
          partsInput: "client",
          partsOutput: "client",
          recognitionModelID: null,
          synthesisModelID: null,
        }),
      );
      const meter = vi.spyOn(session, "enableMicrophoneMeter").mockResolvedValue();
      const catalog = deferred<Response>();
      const requested = deferred<void>();
      let requests = 0;
      globalThis.fetch = async () => {
        requests++;
        requested.resolve();
        return catalog.promise;
      };
      try {
        // This is the overlay's synchronous user-gesture path, before enumeration.
        session.prepareAudio();
        expect(session.speech).toEqual({ input: native ? "gateway" : "client", output: native ? "gateway" : "client" });
        expect(chime.prepare).toHaveBeenCalledOnce();
        expect(thinking.prepare).toHaveBeenCalledTimes(native ? 0 : 1);
        session.setState((s) => ({
          ...s,
          speechInput: native ? "client" : "gateway",
          speechOutput: native ? "client" : "gateway",
        }));
        const prepareAudio = session.prepareAudio;
        const prepare = vi.spyOn(session, "prepareAudio").mockImplementation(() => {
          expect(session.speech).toEqual({
            input: native ? "gateway" : "client",
            output: native ? "gateway" : "client",
          });
          prepareAudio.call(session);
        });
        const connecting = session.connect();
        expect(prepare).toHaveBeenCalledOnce();
        expect(thinking.prepare).toHaveBeenCalledTimes(native ? 0 : 2);
        await requested.promise;
        expect(requests).toBe(1);
        session.disconnect();
        catalog.resolve(new Response(JSON.stringify({ choices: [], defaults: [] })));
        await connecting;
        prepare.mockRestore();
      } finally {
        session.disconnect();
        meter.mockRestore();
        restore();
      }
    });
  }

  it("restores saved native and mixed setups on cold connect ahead of delayed settings discovery", async () => {
    const restore = installBrowserSpeech("Chrome desktop");
    const catalog = {
      choices: [
        { id: "native", label: "Gemini Live", transports: ["webrtc"], voices: ["Kore"] },
        {
          id: "parts",
          label: "Language model",
          composable: true,
          transports: ["text", "webrtc"],
          speechModes: [
            { input: "gateway", output: "gateway" },
            { input: "gateway", output: "client" },
            { input: "client", output: "gateway" },
            { input: "client", output: "client" },
          ],
        },
      ],
      recognitionModels: [{ id: "asr", label: "Whisper" }],
      synthesisModels: [{ id: "tts", label: "Kokoro" }],
      defaults: [
        { transport: "webrtc", id: "native" },
        { transport: "text", id: "parts" },
      ],
    };
    const signaling = globalThis.fetch;
    try {
      for (const native of [true, false]) {
        localStorage.clear();
        configureVoiceGateway("https://voice.example", null);
        globalThis.fetch = async (input, init) =>
          String(input).endsWith("/voice/choices") ? new Response(JSON.stringify(catalog)) : signaling(input, init);
        const saved = new VoiceSession();
        await saved.loadVoiceChoices();
        if (native) {
          saved.selectSetupMode("all-in-one");
          saved.selectSpeakingVoice("Kore");
        } else {
          saved.selectSetupMode("parts");
          saved.selectSpeechStage("output", "client", null);
        }
        saved.disconnect();
        // Construction happened before the host configured this gateway.
        configureVoiceGateway("https://unrelated.example", null);
        const session = new VoiceSession(
          { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() },
          { prepare: vi.fn(), setThinking: vi.fn() },
        );
        configureVoiceGateway("https://voice.example", null);
        let finishSettings!: (response: Response) => void;
        let finishConnection!: (response: Response) => void;
        let requests = 0;
        globalThis.fetch = async (input, init) => {
          if (!String(input).endsWith("/voice/choices")) return signaling(input, init);
          requests++;
          return new Promise<Response>((resolve) => {
            if (requests === 1) finishSettings = resolve;
            else finishConnection = resolve;
          });
        };
        try {
          const settings = session.loadVoiceChoices();
          const connection = session.connect();
          await vi.waitFor(() => expect(requests).toBe(2));
          expect(session.state.setupMode).toBe(native ? "all-in-one" : "parts");
          expect(session.speech).toEqual({ input: "gateway", output: native ? "gateway" : "client" });
          expect(session.transport).toBe("webrtc");
          // External changes during admission cannot replace the captured setup.
          const key = "gomode.voiceSetup.https://voice.example";
          const preference = JSON.parse(localStorage.getItem(key)!);
          preference.mode = native ? "parts" : "all-in-one";
          localStorage.setItem(key, JSON.stringify(preference));
          finishConnection(new Response(JSON.stringify(catalog)));
          await connection;
          expect(session.state.error).toBeNull();
          const channel = FakePeerConnection.dataChannels.at(-1)!;
          channel.onopen?.();
          const start = JSON.parse(channel.send.mock.calls[0]![0] as string);
          expect(start).toMatchObject({
            speech: { input: "gateway", output: native ? "gateway" : "client" },
          });
          expect(offerRequests.at(-1)!.body).toMatchObject({ choiceID: native ? "native" : "parts" });
          if (native) expect(start.voice).toMatchObject({ name: "Kore" });
          else {
            expect(start.recognitionModelID).toBe("asr");
            expect(start).not.toHaveProperty("synthesisModelID");
          }
          finishSettings(new Response(JSON.stringify({ ...catalog, choices: [catalog.choices[0]] })));
          await settings;
          expect(session.state.setupMode).toBe(native ? "all-in-one" : "parts");
          expect(session.state.choiceIDs.webrtc).toBe(native ? "native" : "parts");
          expect(session.state.choices).toHaveLength(2);
        } finally {
          session.disconnect();
        }
      }
    } finally {
      restore();
    }
  });

  it("pins independent stages through reconnect and omits client-owned stage IDs", async () => {
    const restore = installBrowserSpeech("Chrome desktop");
    configureVoiceGateway("https://voice.example", null);
    independentSpeech = true;
    mixedSpeech = true;
    try {
      for (const [input, output] of [
        ["gateway", "gateway"],
        ["gateway", "client"],
        ["client", "gateway"],
      ] as const) {
        const session = new VoiceSession(
          { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() },
          { prepare: vi.fn(), setThinking: vi.fn() },
        );
        try {
          session.selectSpeechInput(input);
          session.selectSpeechOutput(output);
          await session.loadVoiceChoices();
          if (input === "gateway") session.selectSpeechModel("input", "asr-b");
          if (output === "gateway") session.selectSpeechModel("output", "tts-b");
          await session.connect();
          const channel = FakePeerConnection.dataChannels.at(-1)!;
          channel.onopen?.();
          const expected = {
            ...(input === "gateway" ? { recognitionModelID: "asr-b" } : {}),
            ...(output === "gateway" ? { synthesisModelID: "tts-b" } : {}),
          };
          expect(JSON.parse(channel.send.mock.calls[0]![0] as string)).toMatchObject(expected);
          if (input === "client")
            expect(JSON.parse(channel.send.mock.calls[0]![0] as string)).not.toHaveProperty("recognitionModelID");
          if (output === "client")
            expect(JSON.parse(channel.send.mock.calls[0]![0] as string)).not.toHaveProperty("synthesisModelID");
          channel.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind: "session.ready" }) }));
          localStorage.setItem("gomode.voiceSpeechModel.https://voice.example.input", "asr-a");
          localStorage.setItem("gomode.voiceSpeechModel.https://voice.example.output", "tts-a");
          const offers = offerRequests.length;
          vi.useFakeTimers();
          triggerIceState("failed");
          await vi.advanceTimersByTimeAsync(0);
          await vi.waitFor(() => expect(offerRequests.length).toBe(offers + 1));
          const replacement = FakePeerConnection.dataChannels.at(-1)!;
          replacement.onopen?.();
          expect(JSON.parse(replacement.send.mock.calls[0]![0] as string)).toMatchObject(expected);
        } finally {
          session.disconnect();
          vi.useRealTimers();
        }
      }
    } finally {
      restore();
    }
  });

  it("rejects an explicitly saved removed recognition stage before creating RTC", async () => {
    configureVoiceGateway("https://voice.example", null);
    independentSpeech = true;
    mixedSpeech = true;
    localStorage.setItem("gomode.voiceSpeechModel.https://voice.example.input", "removed");
    const session = new VoiceSession();
    try {
      await session.connect();
      expect(session.state.error).toContain("selected recognition model is unavailable");
      expect(offerRequests).toHaveLength(0);
      expect(localStorage.getItem("gomode.voiceSpeechModel.https://voice.example.input")).toBe("removed");
    } finally {
      session.disconnect();
    }
  });

  it("uses gateway recognition with browser TTS without requiring browser recognition", async () => {
    const restore = installBrowserSpeech("Firefox/150");
    const session = new VoiceSession(
      { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() },
      { prepare: vi.fn(), setThinking: vi.fn() },
    );
    mixedSpeech = true;
    const remoteAudio = vi.fn();
    vi.stubGlobal("Audio", remoteAudio);
    try {
      session.selectSpeechOutput("client");
      await session.connect();
      expect(session.state.error).toBeNull();
      const pc = FakePeerConnection.last!;
      const channel = FakePeerConnection.dataChannels.at(-1)!;
      const message = (value: unknown) =>
        channel.onmessage?.(new MessageEvent("message", { data: JSON.stringify(value) }));
      channel.onopen?.();
      expect(JSON.parse(channel.send.mock.calls[0]![0] as string).speech).toEqual({
        input: "gateway",
        output: "client",
      });
      expect(pc.addedTracks).toHaveLength(1);
      pc.ontrack?.call(pc as unknown as RTCPeerConnection, { streams: [{}] } as unknown as RTCTrackEvent);
      expect(remoteAudio).not.toHaveBeenCalled();
      message({ kind: "session.ready" });
      message({ kind: "transcript.draft", id: "draft", text: "Question" });
      expect(session.state.transcriptDraft).toEqual({ id: "draft", text: "Question" });
      message({ kind: "transcript.delta", speaker: "user", draft_id: "draft", text: "Question?" });
      expect(session.state.transcript.at(-1)?.text).toBe("Question?");
      message({ kind: "turn.status", state: "thinking" });
      message({ kind: "assistant.text.delta", text: "An answer." });
      message({ kind: "turn.status", state: "idle" });
      expect(fakeSynthesis.spoken.at(-1)?.text).toBe("An answer.");
      expect(session.state.speaking).toBe(true);
      expect(FakeRecognition.instances).toHaveLength(0);
      const cancel = vi.spyOn(fakeSynthesis, "cancel");
      // The gateway's new VAD onset must stop playback even after generation is idle.
      message({ kind: "interrupted", source: "user" });
      expect(cancel).toHaveBeenCalled();
      expect(session.state.speaking).toBe(false);
      message({ kind: "transcript.delta", speaker: "user", text: "Next question" });
      message({ kind: "turn.status", state: "thinking" });
      message({ kind: "assistant.text.delta", text: "Next answer." });
      message({ kind: "turn.status", state: "idle" });
      expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Next answer.");
      fakeSynthesis.complete();
      expect(session.state.speaking).toBe(false);
    } finally {
      session.disconnect();
      restore();
    }
  });

  it("reads a complete long browser reply over RTC at playback speed before releasing mute-once", async () => {
    const restore = installBrowserSpeech("Firefox/150");
    const session = new VoiceSession(
      { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() },
      { prepare: vi.fn(), setThinking: vi.fn() },
    );
    mixedSpeech = true;
    try {
      session.selectSpeechOutput("client");
      await session.connect();
      const channel = FakePeerConnection.dataChannels.at(-1)!;
      const message = (value: unknown) =>
        channel.onmessage?.(new MessageEvent("message", { data: JSON.stringify(value) }));
      channel.onopen?.();
      message({ kind: "session.ready" });
      message({ kind: "turn.status", state: "thinking" });
      session.setMicrophoneMode("muteOnce");
      session.injectText("Deferred notice");
      const sentences = Array.from({ length: 200 }, (_, index) => `Sentence ${index} ${"word ".repeat(20)}ends.`);
      message({ kind: "assistant.text.delta", text: sentences.join(" ") + " " });
      message({ kind: "turn.status", state: "idle" });
      expect(session.state.error).toBeNull();
      expect(fakeSynthesis.spoken).toHaveLength(1);
      for (const sentence of sentences) {
        expect(session.state.microphoneMode).toBe("muteOnce");
        expect(fakeSynthesis.spoken.at(-1)?.text).toBe(sentence);
        fakeSynthesis.complete();
      }
      expect(fakeSynthesis.spoken.map((entry) => entry.text)).toEqual(sentences);
      expect(session.state.microphoneMode).toBe("listening");
      expect(session.state.speaking).toBe(false);
      expect(channel.send.mock.calls.some(([text]) => (text as string).includes("Deferred notice"))).toBe(true);
    } finally {
      session.disconnect();
      restore();
    }
  });

  it("retains delayed browser speech ownership after idle and cancels it on a new microphone onset", async () => {
    const restore = installBrowserSpeech("Firefox/150");
    localStorage.setItem("gomode.browserVoiceURI", "chosen");
    const session = new VoiceSession(
      { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() },
      { prepare: vi.fn(), setThinking: vi.fn() },
    );
    mixedSpeech = true;
    try {
      session.selectSpeechOutput("client");
      await session.connect();
      const channel = FakePeerConnection.dataChannels.at(-1)!;
      const message = (value: unknown) =>
        channel.onmessage?.(new MessageEvent("message", { data: JSON.stringify(value) }));
      channel.onopen?.();
      message({ kind: "session.ready" });
      session.setMicrophoneMode("muteOnce");
      message({ kind: "assistant.text.delta", text: "Waiting for voices." });
      expect(fakeSynthesis.spoken).toHaveLength(0);
      session.injectText("Deferred notification");
      message({ kind: "turn.status", state: "idle" });
      expect(session.state.microphoneMode).toBe("muteOnce");
      expect(session.state.speaking).toBe(true);
      expect(channel.send.mock.calls.some(([text]) => JSON.parse(text as string).kind === "context.update")).toBe(
        false,
      );
      session.setMicrophoneMode("listening");
      message({ kind: "interrupted", source: "user" });
      expect(session.state.speaking).toBe(false);
      fakeSynthesis.voices = [{ voiceURI: "chosen", name: "Chosen", lang: "en-US" } as SpeechSynthesisVoice];
      fakeSynthesis.dispatchEvent(new Event("voiceschanged"));
      expect(fakeSynthesis.spoken).toHaveLength(0);
      expect(session.state.error).toBeNull();
      message({ kind: "assistant.text.delta", text: "Replacement answer." });
      message({ kind: "turn.status", state: "idle" });
      expect(fakeSynthesis.spoken.at(-1)?.text).toBe("Replacement answer.");
      expect(session.state.speaking).toBe(true);
      fakeSynthesis.complete();
      expect(session.state.speaking).toBe(false);
    } finally {
      session.disconnect();
      restore();
    }
  });

  it("uses browser recognition with gateway speech and cancels remote playback without losing tools", async () => {
    const restore = installBrowserSpeech("Chrome desktop");
    Reflect.deleteProperty(window, "speechSynthesis");
    const session = new VoiceSession(
      { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() },
      { prepare: vi.fn(), setThinking: vi.fn() },
    );
    mixedSpeech = true;
    vi.stubGlobal("Audio", window.Audio);
    vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
    let audio: HTMLAudioElement | null = null;
    const create = window.Audio;
    vi.stubGlobal("Audio", function () {
      audio = new create();
      return audio;
    });
    try {
      session.selectSpeechInput("client");
      await session.connect();
      expect(session.state.error).toBeNull();
      const pc = FakePeerConnection.last!;
      const channel = FakePeerConnection.dataChannels.at(-1)!;
      const message = (value: unknown) =>
        channel.onmessage?.(new MessageEvent("message", { data: JSON.stringify(value) }));
      channel.onopen?.();
      expect(JSON.parse(channel.send.mock.calls[0]![0] as string).speech).toEqual({
        input: "client",
        output: "gateway",
      });
      expect(pc.addedTracks).toHaveLength(0);
      expect(pc.addTransceiver).toHaveBeenCalledWith("audio", { direction: "recvonly" });
      pc.ontrack?.call(pc as unknown as RTCPeerConnection, { streams: [{}] } as unknown as RTCTrackEvent);
      message({ kind: "session.ready" });
      message({ kind: "turn.status", state: "idle" });
      const recognition = FakeRecognition.instances.at(-1)!;
      expect(recognition).toBeDefined();
      message({ kind: "speech.started", speaker: "assistant" });
      recognition.result([["Interrupt", false]]);
      expect(audio!.muted).toBe(true);
      expect(channel.send.mock.calls.some(([text]) => JSON.parse(text as string).kind === "turn.cancel")).toBe(true);
      const beforeAlert = channel.send.mock.calls.length;
      session.injectText("Deferred until recognition commits", true);
      expect(channel.send).toHaveBeenCalledTimes(beforeAlert);
      message({ kind: "speech.started", speaker: "assistant" });
      expect(session.state.speaking).toBe(false);
      message({ kind: "session.usage", sessionID: "cancelled-session", totals: { outputTokens: 12 } });
      expect(session.state.usage?.totals.outputTokens).toBe(12);
      mcpMocks.mcpCallTool.mockResolvedValue({ structuredContent: {} });
      message({ kind: "tool.call", id: "late-tool", name: "lookup", args: {} });
      await vi.waitFor(() =>
        expect(channel.send.mock.calls.some(([text]) => JSON.parse(text as string).kind === "tool.result")).toBe(true),
      );
      message({ kind: "interrupted", source: "user" });
      recognition.result([["Interrupt", true]]);
      recognition.end();
      expect(channel.send.mock.calls.slice(-2).map(([text]) => JSON.parse(text as string))).toEqual([
        { kind: "context.update", context: { text: "Deferred until recognition commits" } },
        { kind: "user.message", text: "Interrupt" },
      ]);
      message({ kind: "turn.status", state: "idle" });
      message({ kind: "turn.status", state: "thinking" });
      expect(audio!.muted).toBe(false);
      message({ kind: "speech.started", speaker: "assistant" });
      expect(session.state.speaking).toBe(true);
      expect(fakeSynthesis.spoken).toHaveLength(0);
    } finally {
      session.disconnect();
      vi.mocked(HTMLMediaElement.prototype.pause).mockClear();
      restore();
    }
  });

  it("mutes active browser recognition for a gateway tool continuation and resumes after that reply", async () => {
    const restore = installBrowserSpeech("Chrome desktop");
    const session = new VoiceSession(
      { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() },
      { prepare: vi.fn(), setThinking: vi.fn() },
    );
    mixedSpeech = true;
    const tool = deferred<{ structuredContent: Record<string, never> }>();
    mcpMocks.mcpCallTool.mockReturnValue(tool.promise);
    try {
      session.selectSpeechInput("client");
      await session.connect();
      const channel = FakePeerConnection.dataChannels.at(-1)!;
      const message = (value: unknown) =>
        channel.onmessage?.(new MessageEvent("message", { data: JSON.stringify(value) }));
      channel.onopen?.();
      message({ kind: "session.ready" });
      message({ kind: "turn.status", state: "idle" });
      message({ kind: "speech.started", speaker: "assistant" });
      message({ kind: "speech.ended", speaker: "assistant" });
      const recognition = FakeRecognition.instances.at(-1)!;
      expect(session.state.listening).toBe(true);
      expect(recognition.aborted).toBe(false);
      message({ kind: "tool.call", id: "continuation", name: "lookup", args: {} });
      expect(session.state.activeTool).toBe("lookup");
      session.setMicrophoneMode("muteOnce");
      expect(recognition.aborted).toBe(false);
      tool.resolve({ structuredContent: {} });
      await vi.waitFor(() =>
        expect(channel.send.mock.calls.some(([text]) => JSON.parse(text as string).kind === "tool.result")).toBe(true),
      );
      message({ kind: "speech.started", speaker: "assistant" });
      expect(session.state.speaking).toBe(true);
      expect(session.state.listening).toBe(false);
      expect(recognition.aborted).toBe(true);
      const sent = channel.send.mock.calls.length;
      recognition.result([["Do not interrupt", false]]);
      recognition.result([["Do not submit", true]]);
      recognition.end();
      expect(channel.send.mock.calls.length).toBe(sent);
      expect(session.state.microphoneMode).toBe("muteOnce");
      message({ kind: "speech.ended", speaker: "assistant" });
      expect(session.state.microphoneMode).toBe("listening");
      expect(session.state.listening).toBe(true);
      const resumed = FakeRecognition.instances.at(-1)!;
      expect(resumed).not.toBe(recognition);
      resumed.result([["Next question", true]]);
      resumed.end();
      expect(channel.send.mock.calls.slice(sent).map(([text]) => JSON.parse(text as string))).toEqual([
        { kind: "user.message", text: "Next question" },
      ]);
    } finally {
      tool.resolve({ structuredContent: {} });
      session.disconnect();
      restore();
    }
  });

  it("reconnects mixed sessions with the same speech selection and retires old playback callbacks", async () => {
    const restore = installBrowserSpeech("Firefox/150");
    const session = new VoiceSession(
      { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() },
      { prepare: vi.fn(), setThinking: vi.fn() },
    );
    mixedSpeech = true;
    try {
      session.selectSpeechOutput("client");
      await session.connect();
      const old = FakePeerConnection.dataChannels.at(-1)!;
      old.onopen?.();
      old.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind: "session.ready" }) }));
      vi.useFakeTimers();
      triggerIceState("failed");
      await vi.advanceTimersByTimeAsync(0);
      await vi.waitFor(() => expect(offerRequests).toHaveLength(2));
      const current = FakePeerConnection.dataChannels.at(-1)!;
      current.onopen?.();
      expect(JSON.parse(current.send.mock.calls[0]![0] as string).speech).toEqual({
        input: "gateway",
        output: "client",
      });
      old.onmessage?.(
        new MessageEvent("message", { data: JSON.stringify({ kind: "assistant.text.delta", text: "Stale answer." }) }),
      );
      expect(fakeSynthesis.spoken).toHaveLength(0);
      expect(session.state.transcript.some((entry) => entry.text.includes("Stale"))).toBe(false);
    } finally {
      session.disconnect();
      vi.useRealTimers();
      restore();
    }
  });

  it("closes a mixed session when browser synthesis cannot use its saved voice", async () => {
    const restore = installBrowserSpeech("Firefox/150");
    localStorage.setItem("gomode.browserVoiceURI", "missing");
    fakeSynthesis.voices = [{ voiceURI: "other", lang: "en-US" } as SpeechSynthesisVoice];
    const session = new VoiceSession(
      { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() },
      { prepare: vi.fn(), setThinking: vi.fn() },
    );
    mixedSpeech = true;
    try {
      session.selectSpeechOutput("client");
      await session.connect();
      const pc = FakePeerConnection.last!;
      const close = vi.spyOn(pc, "close");
      const channel = FakePeerConnection.dataChannels.at(-1)!;
      channel.onopen?.();
      const message = (value: unknown) =>
        channel.onmessage?.(new MessageEvent("message", { data: JSON.stringify(value) }));
      message({ kind: "session.ready" });
      message({ kind: "assistant.text.delta", text: "An answer." });
      message({ kind: "turn.status", state: "idle" });
      expect(session.state.connected).toBe(false);
      expect(session.state.error).toContain("saved browser voice is unavailable");
      expect(close).toHaveBeenCalledOnce();
      expect(fakeSynthesis.spoken).toHaveLength(0);
    } finally {
      session.disconnect();
      restore();
    }
  });

  it("rejects saved browser speech when its platform capability is unavailable before capturing audio", async () => {
    localStorage.setItem("gomode.voiceInput", "client");
    localStorage.setItem("gomode.voiceOutput", "gateway");
    const restore = installBrowserSpeech("Firefox/150");
    const session = new VoiceSession(
      { prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() },
      { prepare: vi.fn(), setThinking: vi.fn() },
    );
    try {
      await session.connect();
      expect(session.state.error).toContain("unavailable");
      expect(navigator.mediaDevices.getUserMedia).not.toHaveBeenCalled();
    } finally {
      session.disconnect();
      restore();
    }
  });

  it("attenuates the transmitted microphone during replies and restores it after interruption and input replacement", async () => {
    FakeMicrophoneContext.instances = [];
    const session = new VoiceSession();
    try {
      await session.connect();
      const context = FakeMicrophoneContext.instances.find((context) => context.gains.length > 0);
      expect(context).toBeDefined();
      context!.state = "suspended";
      await session.enableMicrophoneMeter();
      expect(context!.state).toBe("running");
      const gain = context!.gains[0]!;
      expect(gain.gain.value).toBe(1);
      expect(FakePeerConnection.last?.addedTracks[0]).toBe(context!.destinations[0]!.stream.getAudioTracks()[0]);
      const event = (kind: string) =>
        FakePeerConnection.dataChannels
          .at(-1)
          ?.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind }) }));
      event("speech.started");
      expect(gain.gain.linearRampToValueAtTime).toHaveBeenLastCalledWith(0.1, 0.03);
      event("speech.started");
      expect(gain.gain.linearRampToValueAtTime).toHaveBeenCalledOnce();
      await session.selectInputDevice("headset");
      const replacement = context!.gains.at(-1)!;
      expect(replacement.gain.value).toBeLessThanOrEqual(0.1);
      session.setMicrophoneMode("muted");
      expect(outgoingMicrophoneTrack()?.enabled).toBe(false);
      event("interrupted");
      expect(replacement.gain.linearRampToValueAtTime).toHaveBeenLastCalledWith(1, 0.1);
      expect(outgoingMicrophoneTrack()?.enabled).toBe(false);
      session.setMicrophoneMode("listening");
      expect(outgoingMicrophoneTrack()?.enabled).toBe(true);
      event("speech.started");
      event("speech.ended");
      expect(replacement.gain.linearRampToValueAtTime).toHaveBeenLastCalledWith(1, 0.1);
    } finally {
      session.disconnect();
    }
  });
  it("keeps the gesture-unlocked meter and meters during cloud playback without freezing on mute or silence", async () => {
    FakeMicrophoneContext.instances = [];
    const poll = microphoneFrames();
    const session = new VoiceSession({ prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() });
    session.prepareAudio();
    const context = FakeMicrophoneContext.instances[0];
    expect(context).toBeDefined();
    await session.connect();
    expect(FakeMicrophoneContext.instances.filter((context) => context.gains.length === 0)).toHaveLength(1);
    poll();
    expect(session.state.micLevel).toBeGreaterThan(0.3);
    session.setState((s) => ({ ...s, speaking: true }));
    poll();
    expect(session.state.micLevel).toBeGreaterThan(0.3);
    session.setMicrophoneMode("muteOnce");
    poll();
    expect(session.state.micLevel).toBe(0);
    session.setMicrophoneMode("listening");
    context!.sample = 0;
    poll();
    expect(session.state.micLevel).toBe(0);
    context!.sample = 0.01;
    session.setMicrophoneMode("muted");
    poll();
    expect(session.state.micLevel).toBe(0);
    session.setMicrophoneMode("listening");
    poll();
    expect(session.state.micLevel).toBeGreaterThan(0.3);
    session.disconnect();
    poll();
    expect(session.state.micLevel).toBe(0);
    expect(context!.source.disconnect).toHaveBeenCalledOnce();
  });

  it("coalesces access requests and releases a late stream without clearing a newer failure", async () => {
    const media = deferred<MediaStream>();
    const getMedia = vi.mocked(navigator.mediaDevices.getUserMedia).mockReturnValue(media.promise);
    const session = new VoiceSession();
    const first = session.requestMicrophoneAccess();
    await session.requestMicrophoneAccess();
    expect(getMedia).toHaveBeenCalledOnce();
    session.disconnect();
    session.setState((state) => ({ ...state, error: "A newer connection failed" }));
    const stop = vi.fn();
    media.resolve({ getTracks: () => [{ stop }] } as unknown as MediaStream);
    await first;
    expect(stop).toHaveBeenCalledOnce();
    expect(session.state.error).toBe("A newer connection failed");
    expect(session.state.microphoneAccessPending).toBe(false);
  });

  it("reacts to microphone revocation and releases its permission subscription", async () => {
    const originalPermissions = navigator.permissions;
    const status = new EventTarget() as PermissionStatus;
    Object.defineProperty(status, "state", { configurable: true, value: "granted" });
    Object.defineProperty(navigator, "permissions", {
      configurable: true,
      value: { query: async () => status },
    });
    const session = new VoiceSession();
    const stopObserving = session.observeMicrophoneAccess();
    try {
      await Promise.resolve();
      expect(session.state.microphoneAccess).toBe("granted");
      await session.connect();
      Object.defineProperty(status, "state", { configurable: true, value: "denied" });
      status.dispatchEvent(new Event("change"));
      expect(session.state.microphoneAccess).toBe("denied");
      expect(session.state.connectStatus).toBeNull();
      expect(FakePeerConnection.dataChannels.at(-1)?.readyState).toBe("closed");
      session.setState((state) => ({ ...state, error: "Gateway unavailable", audioInputError: "Device unavailable" }));
      window.dispatchEvent(new window.Event("focus"));
      expect(session.state.error).toBe("Gateway unavailable");
      expect(session.state.audioInputError).toBe("Device unavailable");
      Object.defineProperty(status, "state", { configurable: true, value: "granted" });
      status.dispatchEvent(new Event("change"));
      expect(session.state.microphoneAccess).toBe("granted");
      expect(session.state.connected).toBe(false);
      stopObserving();
      Object.defineProperty(status, "state", { configurable: true, value: "denied" });
      status.dispatchEvent(new Event("change"));
      expect(session.state.microphoneAccess).toBe("granted");
    } finally {
      stopObserving();
      session.disconnect();
      Object.defineProperty(navigator, "permissions", { configurable: true, value: originalPermissions });
    }
  });

  it("keeps blocked playback recoverable and ignores playback results after disconnect", async () => {
    vi.stubGlobal("Audio", window.Audio);
    const play = vi.spyOn(HTMLMediaElement.prototype, "play");
    const pause = vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
    const session = new VoiceSession();
    try {
      play.mockRejectedValueOnce(new DOMException("Autoplay blocked", "NotAllowedError"));
      await session.connect();
      FakePeerConnection.last?.ontrack?.call(
        FakePeerConnection.last as unknown as RTCPeerConnection,
        { streams: [{}] } as unknown as RTCTrackEvent,
      );
      await Promise.resolve();
      expect(session.state.audioPlaybackError).toContain("Autoplay blocked");
      expect(session.state.microphoneAccess).toBe("granted");
      play.mockResolvedValueOnce();
      await session.resumeAudio();
      expect(session.state.audioPlaybackError).toBeNull();
      let reject: (error: Error) => void = () => {};
      play.mockImplementationOnce(
        () =>
          new Promise<void>((_, fail) => {
            reject = fail;
          }),
      );
      const pending = session.resumeAudio();
      session.disconnect();
      reject(new Error("late failure"));
      await pending;
      expect(session.state.audioPlaybackError).toBeNull();
      expect(pause).toHaveBeenCalledOnce();
    } finally {
      session.disconnect();
      play.mockRestore();
      pause.mockRestore();
    }
  });

  it("offers microphone recovery after denial without starting a session on re-enable", async () => {
    const session = new VoiceSession();
    const getMedia = vi.mocked(navigator.mediaDevices.getUserMedia);
    getMedia.mockRejectedValueOnce(new DOMException("Permission denied", "NotAllowedError"));
    await session.connect();
    expect(session.state.connected).toBe(false);
    expect(session.state.microphoneAccess).toBe("denied");
    expect(session.state.error).toBeNull();
    const stop = vi.fn();
    getMedia.mockResolvedValueOnce({ getTracks: () => [{ stop }] } as unknown as MediaStream);
    await session.requestMicrophoneAccess();
    expect(session.state.microphoneAccess).toBe("granted");
    expect(session.state.connected).toBe(false);
    expect(stop).toHaveBeenCalledOnce();
    expect(offerRequests).toHaveLength(0);
    session.disconnect();
  });

  for (const late of [false, true]) {
    it(`publishes the host caller name and clears it on hang-up (late discovery: ${late})`, async () => {
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
      const discovery = deferred<Response>();
      const gatewayFetch = globalThis.fetch;
      globalThis.fetch = (input, init) =>
        String(input) === "/.well-known/gomode.json" ? discovery.promise : gatewayFetch(input, init);
      const session = new VoiceSession({ prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() });
      try {
        await session.connect();
        FakePeerConnection.dataChannels[0]?.onmessage?.(
          new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindSessionReady }) }),
        );
        if (late) session.disconnect();
        discovery.resolve(new Response(JSON.stringify({ service: "My Server" })));
        if (!late) {
          await vi.waitFor(() => expect(mediaSession.metadata?.title).toBe("My Server"));
          expect(mediaSession.playbackState).toBe("playing");
          session.disconnect();
        } else {
          await new Promise<void>((resolve) => setImmediate(resolve));
        }
        expect(mediaSession.metadata).toBeNull();
        expect(mediaSession.playbackState).toBe("none");
      } finally {
        session.disconnect();
        Reflect.deleteProperty(navigator, "mediaSession");
        vi.unstubAllGlobals();
      }
    });
  }
  it("does not report a device-list failure when the browser has no media APIs", async () => {
    Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: undefined });
    const session = new VoiceSession();
    await session.enumerateDevices();
    expect(session.state.audioDevicesError).toBeNull();
    expect(session.state.audioInputs).toEqual([]);
    expect(session.state.audioOutputs).toEqual([]);
  });

  it("prefers newly connected Bluetooth and remembers its routes across sessions", async () => {
    const sink = vi.fn(async (_id: string) => {});
    Object.defineProperty(HTMLMediaElement.prototype, "setSinkId", { configurable: true, value: sink });
    const session = new VoiceSession();
    const speaker = { kind: "audiooutput", deviceId: "speaker", label: "Speakers" } as MediaDeviceInfo;
    const headset = [
      { kind: "audioinput", deviceId: "bt-mic", label: "Bluetooth microphone" } as MediaDeviceInfo,
      { kind: "audiooutput", deviceId: "bt-out", label: "Bluetooth headphones" } as MediaDeviceInfo,
    ];
    try {
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([speaker]);
      await session.enumerateDevices();
      await session.selectOutputDevice("speaker");
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([speaker, ...headset]);
      await session.enumerateDevices();
      expect(session.state.selectedOutputId).toBe("bt-out");
      expect(session.state.selectedInputId).toBe("bt-mic");
      // A manual speaker choice lasts until the next Bluetooth arrival.
      await session.selectOutputDevice("speaker");
      await session.enumerateDevices();
      expect(session.state.selectedOutputId).toBe("speaker");
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([speaker]);
      await session.enumerateDevices();
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([
        speaker,
        ...headset.map((d) => ({ ...d, label: "Headset" })),
      ]);
      await session.enumerateDevices();
      expect(session.state.selectedOutputId).toBe("bt-out");
      // Device IDs restore routes even when the browser stops exposing a descriptive label.
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue(
        headset.map((d) => ({ ...d, label: "Headset" })),
      );
      const restored = new VoiceSession();
      await restored.enumerateDevices();
      expect(restored.state.selectedOutputId).toBe("bt-out");
      expect(restored.state.selectedInputId).toBe("bt-mic");
    } finally {
      session.disconnect();
      Reflect.deleteProperty(HTMLMediaElement.prototype, "setSinkId");
    }
  });

  it("routes a Bluetooth arrival after an outstanding microphone switch completes", async () => {
    const session = new VoiceSession();
    const microphone = { stop: vi.fn(), enabled: true };
    const stream = { getTracks: () => [microphone], getAudioTracks: () => [microphone] } as unknown as MediaStream;
    try {
      await session.connect();
      const acquiring = deferred<MediaStream>();
      vi.mocked(navigator.mediaDevices.getUserMedia).mockReturnValueOnce(acquiring.promise).mockResolvedValue(stream);
      const selecting = session.selectInputDevice("usb");
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([
        { kind: "audioinput", deviceId: "usb", label: "USB microphone" } as MediaDeviceInfo,
        { kind: "audioinput", deviceId: "bt-mic", label: "Bluetooth microphone" } as MediaDeviceInfo,
      ]);
      await session.enumerateDevices();
      acquiring.resolve(stream);
      await selecting;
      await vi.waitFor(() => expect(session.state.selectedInputId).toBe("bt-mic"));
    } finally {
      session.disconnect();
    }
  });

  it("keeps a Bluetooth arrival queued across overlapping unplug recovery refreshes", async () => {
    const session = new VoiceSession();
    const microphone = { stop: vi.fn(), enabled: true };
    const stream = { getTracks: () => [microphone], getAudioTracks: () => [microphone] } as unknown as MediaStream;
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue(stream);
    vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([
      { kind: "audioinput", deviceId: "usb", label: "USB microphone" } as MediaDeviceInfo,
    ]);
    try {
      await session.selectInputDevice("usb");
      await session.connect();
      const recovering = deferred<MediaStream>();
      vi.mocked(navigator.mediaDevices.getUserMedia).mockReturnValueOnce(recovering.promise).mockResolvedValue(stream);
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([
        { kind: "audioinput", deviceId: "bt-mic", label: "Bluetooth microphone" } as MediaDeviceInfo,
      ]);
      const first = session.enumerateDevices();
      await vi.waitFor(() => expect(session.state.audioSwitching).toBe("input"));
      await session.enumerateDevices();
      recovering.resolve(stream);
      await first;
      await vi.waitFor(() => expect(session.state.selectedInputId).toBe("bt-mic"));
    } finally {
      session.disconnect();
    }
  });

  it("keeps pre-connect routes provisional when applying them fails", async () => {
    localStorage.setItem("gomode.voiceInputDevice", "usb-mic");
    localStorage.setItem("gomode.voiceOutputDevice", "usb-out");
    const sink = vi.fn(async (_id: string): Promise<void> => {
      throw new Error("Speaker permission denied");
    });
    Object.defineProperty(HTMLMediaElement.prototype, "setSinkId", { configurable: true, value: sink });
    const audio = new window.Audio();
    vi.spyOn(audio, "play").mockResolvedValue();
    vi.stubGlobal(
      "Audio",
      class {
        constructor() {
          return audio;
        }
      },
    );
    const session = new VoiceSession();
    try {
      await session.selectInputDevice("bt-mic");
      await session.selectOutputDevice("bt-out");
      expect(localStorage.getItem("gomode.voiceInputDevice")).toBe("usb-mic");
      expect(localStorage.getItem("gomode.voiceOutputDevice")).toBe("usb-out");
      vi.mocked(navigator.mediaDevices.getUserMedia).mockRejectedValueOnce(new Error("Microphone permission denied"));
      await session.connect();
      expect(localStorage.getItem("gomode.voiceInputDevice")).toBe("usb-mic");
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([
        { kind: "audioinput", deviceId: "bt-mic", label: "Headset mic" } as MediaDeviceInfo,
        { kind: "audiooutput", deviceId: "bt-out", label: "Headset speaker" } as MediaDeviceInfo,
      ]);
      await session.connect();
      expect(localStorage.getItem("gomode.voiceInputDevice")).toBe("bt-mic");
      const pc = FakePeerConnection.last;
      pc?.ontrack?.call(pc as unknown as RTCPeerConnection, { streams: [{}] } as unknown as RTCTrackEvent);
      await vi.waitFor(() => expect(session.state.audioOutputError).toContain("Speaker permission denied"));
      expect(localStorage.getItem("gomode.voiceOutputDevice")).toBe("usb-out");
      sink.mockResolvedValue();
      await session.selectOutputDevice("bt-out");
      expect(localStorage.getItem("gomode.voiceOutputDevice")).toBe("bt-out");
    } finally {
      session.disconnect();
      Reflect.deleteProperty(HTMLMediaElement.prototype, "setSinkId");
    }
  });

  it("preserves provisional system-default choices through pre-connect enumeration", async () => {
    localStorage.setItem("gomode.voiceInputDevice", "usb-mic");
    localStorage.setItem("gomode.voiceOutputDevice", "usb-out");
    const sink = vi.fn(async (_id: string) => {});
    Object.defineProperty(HTMLMediaElement.prototype, "setSinkId", { configurable: true, value: sink });
    const audio = new window.Audio();
    vi.spyOn(audio, "play").mockResolvedValue();
    vi.stubGlobal(
      "Audio",
      class {
        constructor() {
          return audio;
        }
      },
    );
    const session = new VoiceSession();
    try {
      await session.selectInputDevice("");
      await session.selectOutputDevice("");
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([
        { kind: "audioinput", deviceId: "usb-mic", label: "USB mic" } as MediaDeviceInfo,
        { kind: "audiooutput", deviceId: "usb-out", label: "USB speaker" } as MediaDeviceInfo,
      ]);
      await session.enumerateDevices();
      expect(session.state.selectedInputId).toBe("");
      expect(session.state.selectedOutputId).toBe("");
      expect(localStorage.getItem("gomode.voiceInputDevice")).toBe("usb-mic");
      expect(localStorage.getItem("gomode.voiceOutputDevice")).toBe("usb-out");
      await session.connect();
      const pc = FakePeerConnection.last;
      pc?.ontrack?.call(pc as unknown as RTCPeerConnection, { streams: [{}] } as unknown as RTCTrackEvent);
      await vi.waitFor(() => expect(sink).toHaveBeenCalledWith(""));
      expect(localStorage.getItem("gomode.voiceInputDevice")).toBeNull();
      expect(localStorage.getItem("gomode.voiceOutputDevice")).toBeNull();
    } finally {
      session.disconnect();
      Reflect.deleteProperty(HTMLMediaElement.prototype, "setSinkId");
    }
  });

  it("applies Bluetooth arriving during initial microphone acquisition", async () => {
    const initial = deferred<MediaStream>();
    const usb = { stop: vi.fn(), enabled: true };
    const bluetooth = { stop: vi.fn(), enabled: true };
    vi.mocked(navigator.mediaDevices.getUserMedia)
      .mockReturnValueOnce(initial.promise)
      .mockResolvedValue({
        getTracks: () => [bluetooth],
        getAudioTracks: () => [bluetooth],
      } as unknown as MediaStream);
    const session = new VoiceSession();
    try {
      await session.selectInputDevice("usb");
      const connecting = session.connect();
      await vi.waitFor(() => expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledOnce());
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([
        { kind: "audioinput", deviceId: "usb", label: "USB mic" } as MediaDeviceInfo,
        { kind: "audioinput", deviceId: "bt", label: "Bluetooth mic" } as MediaDeviceInfo,
      ]);
      await session.enumerateDevices();
      initial.resolve({ getTracks: () => [usb], getAudioTracks: () => [usb] } as unknown as MediaStream);
      await connecting;
      expect(FakePeerConnection.last?.sender.replaceTrack).toHaveBeenCalledWith(outgoingMicrophoneTrack());
      expect(session.state.selectedInputId).toBe("bt");
      expect(localStorage.getItem("gomode.voiceInputDevice")).toBe("bt");
    } finally {
      session.disconnect();
    }
  });

  for (const output of ["", "bt-out"]) {
    it(`routes ${output || "system-default"} audio through refreshes during a Bluetooth microphone switch`, async () => {
      localStorage.setItem("gomode.voiceOutputDevice", "usb-out");
      const sink = vi.fn(async (_id: string) => {});
      Object.defineProperty(HTMLMediaElement.prototype, "setSinkId", { configurable: true, value: sink });
      const audio = new window.Audio();
      vi.spyOn(audio, "play").mockResolvedValue();
      vi.stubGlobal(
        "Audio",
        class {
          constructor() {
            return audio;
          }
        },
      );
      const session = new VoiceSession();
      try {
        await session.selectOutputDevice(output);
        await session.connect();
        const acquiring = deferred<MediaStream>();
        vi.mocked(navigator.mediaDevices.getUserMedia).mockReturnValueOnce(acquiring.promise);
        vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([
          { kind: "audioinput", deviceId: "bt-mic", label: "Bluetooth mic" } as MediaDeviceInfo,
          {
            kind: "audiooutput",
            deviceId: output || "usb-out",
            label: output ? "Bluetooth speaker" : "USB speaker",
          } as MediaDeviceInfo,
        ]);
        const refreshing = session.enumerateDevices();
        await vi.waitFor(() => expect(session.state.audioSwitching).toBe("input"));
        const pc = FakePeerConnection.last;
        pc?.ontrack?.call(pc as unknown as RTCPeerConnection, { streams: [{}] } as unknown as RTCTrackEvent);
        // A device refresh must retain the queued system-default sink request.
        await session.enumerateDevices();
        acquiring.resolve({ getTracks: () => [], getAudioTracks: () => [] } as unknown as MediaStream);
        await refreshing;
        await vi.waitFor(() => expect(sink).toHaveBeenCalledWith(output));
        expect(localStorage.getItem("gomode.voiceOutputDevice")).toBe(output || null);
      } finally {
        session.disconnect();
        Reflect.deleteProperty(HTMLMediaElement.prototype, "setSinkId");
      }
    });
  }

  it("switches a live microphone to Bluetooth and preserves its preference after unplugging", async () => {
    const oldTrack = { stop: vi.fn(), enabled: true };
    const newTrack = { stop: vi.fn(), enabled: true };
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue({
      getTracks: () => [oldTrack],
      getAudioTracks: () => [oldTrack],
    } as unknown as MediaStream);
    const session = new VoiceSession();
    try {
      await session.connect();
      session.setMicrophoneMode("muted");
      vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue({
        getTracks: () => [newTrack],
        getAudioTracks: () => [newTrack],
      } as unknown as MediaStream);
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([
        { kind: "audioinput", deviceId: "bt-mic", label: "Bluetooth microphone" } as MediaDeviceInfo,
      ]);
      await session.enumerateDevices();
      expect(FakePeerConnection.last?.sender.replaceTrack).toHaveBeenCalledWith(outgoingMicrophoneTrack());
      expect(newTrack.enabled).toBe(false);
      expect(oldTrack.stop).toHaveBeenCalledOnce();
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([]);
      await session.enumerateDevices();
      expect(session.state.selectedInputId).toBe("");
      expect(localStorage.getItem("gomode.voiceInputDevice")).toBe("bt-mic");
      await session.selectInputDevice("");
      expect(localStorage.getItem("gomode.voiceInputDevice")).toBeNull();
    } finally {
      session.disconnect();
    }
  });

  it("keeps a failed speaker recovery visible when the same headset microphone recovers", async () => {
    const devices = [
      { kind: "audioinput", deviceId: "usb", label: "Headset microphone" } as MediaDeviceInfo,
      { kind: "audiooutput", deviceId: "bt", label: "Headset speaker" } as MediaDeviceInfo,
    ];
    const sink = vi.fn(async (_id: string) => {});
    Object.defineProperty(HTMLMediaElement.prototype, "setSinkId", { configurable: true, value: sink });
    const audio = new window.Audio();
    vi.spyOn(audio, "play").mockResolvedValue();
    vi.stubGlobal(
      "Audio",
      class {
        constructor() {
          return audio;
        }
      },
    );
    const session = new VoiceSession();
    try {
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue(devices);
      await session.selectInputDevice("usb");
      await session.selectOutputDevice("bt");
      await session.connect();
      const pc = FakePeerConnection.last;
      pc?.ontrack?.call(pc as unknown as RTCPeerConnection, { streams: [{}] } as unknown as RTCTrackEvent);
      await vi.waitFor(() => expect(session.state.audioSwitching).toBeNull());
      sink.mockRejectedValueOnce(new Error("Speaker permission denied"));
      vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([]);
      await session.enumerateDevices();
      expect(session.state.selectedInputId).toBe("");
      expect(session.state.selectedOutputId).toBe("bt");
      expect(session.state.audioOutputError).toContain("Speaker permission denied");
      expect(session.state.audioInputError).toBeNull();
    } finally {
      session.disconnect();
      Reflect.deleteProperty(HTMLMediaElement.prototype, "setSinkId");
    }
  });

  for (const kind of ["input", "output"] as const) {
    it(`does not retain an ${kind} disconnection warning after overlapping refreshes recover it`, async () => {
      const devices = [
        { kind: "audioinput", deviceId: "usb", label: "USB microphone" } as MediaDeviceInfo,
        { kind: "audiooutput", deviceId: "bt", label: "Bluetooth headphones" } as MediaDeviceInfo,
      ];
      const sink = vi.fn(async (_id: string) => {});
      Object.defineProperty(HTMLMediaElement.prototype, "setSinkId", { configurable: true, value: sink });
      const audio = new window.Audio();
      vi.spyOn(audio, "play").mockResolvedValue();
      vi.stubGlobal(
        "Audio",
        class {
          constructor() {
            return audio;
          }
        },
      );
      const session = new VoiceSession();
      try {
        vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue(devices);
        await session.selectInputDevice("usb");
        await session.selectOutputDevice("bt");
        await session.connect();
        const pc = FakePeerConnection.last;
        pc?.ontrack?.call(pc as unknown as RTCPeerConnection, { streams: [{}] } as unknown as RTCTrackEvent);
        await vi.waitFor(() => expect(session.state.audioSwitching).toBeNull());
        const microphone = deferred<MediaStream>();
        const speaker = deferred<void>();
        if (kind === "input") vi.mocked(navigator.mediaDevices.getUserMedia).mockReturnValueOnce(microphone.promise);
        else sink.mockReturnValueOnce(speaker.promise);
        vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue(
          devices.filter((d) => d.kind !== `audio${kind}`),
        );
        const recovery = session.enumerateDevices();
        await vi.waitFor(() => expect(session.state.audioSwitching).toBe(kind));
        await session.enumerateDevices();
        if (kind === "input")
          microphone.resolve({ getTracks: () => [], getAudioTracks: () => [{}] } as unknown as MediaStream);
        else speaker.resolve();
        await recovery;
        expect(kind === "input" ? session.state.selectedInputId : session.state.selectedOutputId).toBe("");
        expect(session.state.audioInputError).toBeNull();
        expect(session.state.audioOutputError).toBeNull();

        // A successful switch must preserve a newer warning about the other route.
        const nextMicrophone = deferred<MediaStream>();
        const nextSpeaker = deferred<void>();
        if (kind === "input")
          vi.mocked(navigator.mediaDevices.getUserMedia).mockReturnValueOnce(nextMicrophone.promise);
        else sink.mockReturnValueOnce(nextSpeaker.promise);
        const switching = kind === "input" ? session.selectInputDevice("") : session.selectOutputDevice("");
        vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([]);
        await session.enumerateDevices();
        if (kind === "input")
          nextMicrophone.resolve({ getTracks: () => [], getAudioTracks: () => [{}] } as unknown as MediaStream);
        else nextSpeaker.resolve();
        await switching;
        expect(kind === "input" ? session.state.audioOutputError : session.state.audioInputError).toBe(
          kind === "input"
            ? "Speaker disconnected. Choose another speaker."
            : "Microphone disconnected. Choose another microphone.",
        );
      } finally {
        session.disconnect();
        Reflect.deleteProperty(HTMLMediaElement.prototype, "setSinkId");
      }
    });
  }

  it("installs the initial sender before recovering a mic lost during setup", async () => {
    const stop = vi.fn();
    const oldTrack = { stop, enabled: true };
    const newTrack = { stop: vi.fn(), enabled: true };
    vi.mocked(navigator.mediaDevices.getUserMedia)
      .mockResolvedValueOnce({
        getTracks: () => [oldTrack],
        getAudioTracks: () => [oldTrack],
      } as unknown as MediaStream)
      .mockImplementationOnce(async () => {
        expect(FakePeerConnection.last?.addedTracks).toHaveLength(1);
        expect(FakePeerConnection.last?.addedTracks[0]).not.toBe(oldTrack);
        return { getTracks: () => [newTrack], getAudioTracks: () => [newTrack] } as unknown as MediaStream;
      });
    const session = new VoiceSession();
    await session.selectInputDevice("usb");
    await session.connect();
    expect(FakePeerConnection.last?.addedTracks).toHaveLength(1);
    expect(FakePeerConnection.last?.addedTracks[0]).not.toBe(oldTrack);
    expect(FakePeerConnection.last?.sender.replaceTrack).toHaveBeenCalledWith(outgoingMicrophoneTrack());
    expect(stop).toHaveBeenCalledOnce();
    expect(session.state.selectedInputId).toBe("");
    session.disconnect();
  });

  it("ignores an older device list that omits a currently selected microphone", async () => {
    const devices = [{ kind: "audioinput", deviceId: "usb", label: "USB headset" } as MediaDeviceInfo];
    vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue(devices);
    const session = new VoiceSession();
    await session.selectInputDevice("usb");
    await session.connect();
    const oldList = deferred<MediaDeviceInfo[]>();
    const newList = deferred<MediaDeviceInfo[]>();
    vi.mocked(navigator.mediaDevices.enumerateDevices)
      .mockReturnValueOnce(oldList.promise)
      .mockReturnValueOnce(newList.promise);
    const older = session.enumerateDevices();
    const newer = session.enumerateDevices();
    newList.resolve(devices);
    await newer;
    oldList.resolve([]);
    await older;
    expect(session.state.selectedInputId).toBe("usb");
    expect(session.state.audioInputs[0]?.deviceId).toBe("usb");
    expect(FakePeerConnection.last?.sender.replaceTrack).not.toHaveBeenCalled();
    session.disconnect();
  });

  it("replaces an unplugged selected microphone with the system default", async () => {
    const stop = vi.fn();
    const oldTrack = { stop, enabled: true };
    const newTrack = { stop: vi.fn(), enabled: true };
    vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([
      { kind: "audioinput", deviceId: "usb", label: "USB headset" } as MediaDeviceInfo,
    ]);
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue({
      getTracks: () => [oldTrack],
      getAudioTracks: () => [oldTrack],
    } as unknown as MediaStream);
    const session = new VoiceSession();
    await session.selectInputDevice("usb");
    await session.connect();
    vi.mocked(navigator.mediaDevices.enumerateDevices).mockResolvedValue([]);
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue({
      getTracks: () => [newTrack],
      getAudioTracks: () => [newTrack],
    } as unknown as MediaStream);
    await session.enumerateDevices();
    expect(FakePeerConnection.last?.sender.replaceTrack).toHaveBeenCalledWith(outgoingMicrophoneTrack());
    expect(stop).toHaveBeenCalledOnce();
    expect(session.state.selectedInputId).toBe("");
    expect(session.state.audioInputError).toBeNull();
    session.disconnect();
  });

  it("keeps the working microphone and selection when switching fails", async () => {
    const stop = vi.fn();
    const track = { stop, enabled: true };
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue({
      getTracks: () => [track],
      getAudioTracks: () => [track],
    } as unknown as MediaStream);
    const session = new VoiceSession();
    await session.connect();
    vi.mocked(navigator.mediaDevices.getUserMedia).mockRejectedValue(new Error("Permission denied"));
    await session.selectInputDevice("headset");
    expect(stop).not.toHaveBeenCalled();
    expect(session.state.selectedInputId).toBe("");
    expect(session.state.audioInputError).toContain("Permission denied");
    expect(localStorage.getItem("gomode.voiceInputDevice")).toBeNull();
    expect(session.state.audioSwitching).toBeNull();
    session.disconnect();
  });

  it("returns mute once to listening after a reply or interruption and retains full mute", async () => {
    const track = { stop: vi.fn(), enabled: true };
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue({
      getTracks: () => [track],
      getAudioTracks: () => [track],
    } as unknown as MediaStream);
    const session = new VoiceSession();
    try {
      await session.connect();
      const channel = FakePeerConnection.dataChannels.at(-1);
      const event = (kind: string) =>
        channel?.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind }) }));
      event("speech.started");
      expect(track.enabled).toBe(true);
      session.setMicrophoneMode("muteOnce");
      expect(track.enabled).toBe(false);
      event("speech.ended");
      expect(track.enabled).toBe(true);
      expect(session.state.microphoneMode).toBe("listening");
      event("speech.started");
      expect(track.enabled).toBe(true);
      session.setMicrophoneMode("muteOnce");
      expect(track.enabled).toBe(false);
      event("interrupted");
      expect(track.enabled).toBe(true);
      expect(session.state.microphoneMode).toBe("listening");
      session.setMicrophoneMode("muted");
      expect(track.enabled).toBe(false);
      event("speech.started");
      event("speech.ended");
      event("interrupted");
      expect(track.enabled).toBe(false);
      session.setMicrophoneMode("listening");
      expect(track.enabled).toBe(true);
      expect(session.state.microphoneMode).toBe("listening");
    } finally {
      session.disconnect();
    }
  });

  it("keeps an in-flight microphone replacement gated when agent speech starts", async () => {
    const oldTrack = { stop: vi.fn(), enabled: true };
    const newTrack = { stop: vi.fn(), enabled: true };
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue({
      getTracks: () => [oldTrack],
      getAudioTracks: () => [oldTrack],
    } as unknown as MediaStream);
    const session = new VoiceSession();
    try {
      await session.connect();
      session.setMicrophoneMode("muteOnce");
      const replacement = deferred<void>();
      FakePeerConnection.last?.sender.replaceTrack.mockImplementation(() => replacement.promise);
      vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue({
        getTracks: () => [newTrack],
        getAudioTracks: () => [newTrack],
      } as unknown as MediaStream);
      const switching = session.selectInputDevice("headset");
      await vi.waitFor(() => expect(FakePeerConnection.last?.sender.replaceTrack).toHaveBeenCalled());
      FakePeerConnection.dataChannels.at(-1)?.onmessage?.(
        new MessageEvent("message", {
          data: JSON.stringify({ kind: "speech.started" }),
        }),
      );
      expect(newTrack.enabled).toBe(false);
      replacement.resolve();
      await switching;
      expect(newTrack.enabled).toBe(false);
      FakePeerConnection.dataChannels.at(-1)?.onmessage?.(
        new MessageEvent("message", {
          data: JSON.stringify({ kind: "speech.ended" }),
        }),
      );
      expect(newTrack.enabled).toBe(true);
    } finally {
      session.disconnect();
    }
  });

  it("replaces a muted microphone before stopping the old stream", async () => {
    const stop = vi.fn();
    const oldTrack = { stop, enabled: true };
    const newTrack = { stop: vi.fn(), enabled: true };
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue({
      getTracks: () => [oldTrack],
      getAudioTracks: () => [oldTrack],
    } as unknown as MediaStream);
    const session = new VoiceSession();
    await session.connect();
    session.setMicrophoneMode("muted");
    const pc = FakePeerConnection.last;
    pc?.sender.replaceTrack.mockImplementation(async () => {
      expect(stop).not.toHaveBeenCalled();
    });
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue({
      getTracks: () => [newTrack],
      getAudioTracks: () => [newTrack],
    } as unknown as MediaStream);
    await session.selectInputDevice("headset");
    expect(pc?.sender.replaceTrack).toHaveBeenCalledWith(outgoingMicrophoneTrack());
    expect(newTrack.enabled).toBe(false);
    expect(stop).toHaveBeenCalledOnce();
    expect(session.state.selectedInputId).toBe("headset");
    session.disconnect();
  });

  it("does not resume setup when disconnected during MCP discovery", async () => {
    const tools = deferred<Awaited<ReturnType<typeof mcpClient.listTools>>>();
    mcpMocks.mcpListTools.mockReturnValue(tools.promise);
    const session = new VoiceSession();

    const connecting = session.connect();
    await vi.waitFor(() => expect(mcpMocks.mcpListTools).toHaveBeenCalled());
    session.disconnect();
    tools.resolve([]);
    await connecting;

    expect(FakePeerConnection.instances).toHaveLength(0);
    expect(offerRequests).toHaveLength(0);
    expect(session.state.connectStatus).toBeNull();
    expect(session.state.error).toBeNull();
  });

  it("stops a late microphone stream after disconnect", async () => {
    const media = deferred<MediaStream>();
    const stop = vi.fn();
    vi.mocked(navigator.mediaDevices.getUserMedia).mockReturnValue(media.promise);
    const session = new VoiceSession();

    const connecting = session.connect();
    await vi.waitFor(() => expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalled());
    session.disconnect();
    media.resolve({ getTracks: () => [{ stop }], getAudioTracks: () => [{ stop }] } as unknown as MediaStream);
    await connecting;

    expect(stop).toHaveBeenCalledOnce();
    expect(offerRequests).toHaveLength(0);
    expect(session.state.connectStatus).toBeNull();
    expect(session.state.error).toBeNull();
  });

  it("ignores a late signaling response and stale data-channel events", async () => {
    const offer = deferred<VoiceRTCAnswerResp>();
    offerResponse = offer.promise.then((answer) => new Response(JSON.stringify(answer)));
    const session = new VoiceSession();

    const connecting = session.connect();
    await vi.waitFor(() => expect(offerRequests).toHaveLength(1));
    session.disconnect();
    offer.resolve({ sdp: "answer-sdp", sessionID: "late-session" });
    await connecting;
    FakePeerConnection.dataChannels[0]?.onopen?.();

    await vi.waitFor(() =>
      expect(closeRequests).toEqual([
        { url: `${window.location.origin}/api/voicegateway/v2/voice/rtc/late-session`, authorization: null },
      ]),
    );
    expect(FakePeerConnection.dataChannels[0]?.send).not.toHaveBeenCalled();
    expect(session.state.connectStatus).toBeNull();
    expect(session.state.listening).toBe(false);
    expect(session.state.error).toBeNull();
  });

  it("refreshes the token for a late external session close without restoring UI on failure", async () => {
    let issued = 0;
    configureVoiceGateway("https://voice.example.com", async () => ({
      kind: "caic",
      instanceID: "home",
      baseURL: "https://caic.example.com",
      token: serviceToken("user-1", ++issued),
    }));
    const offer = deferred<VoiceRTCAnswerResp>();
    offerResponse = offer.promise.then((answer) => new Response(JSON.stringify(answer)));
    const fetchOffer = globalThis.fetch;
    globalThis.fetch = async (input, init) => {
      if (String(input).endsWith("/voice/rtc/offer") || String(input).endsWith("/voice/choices"))
        return fetchOffer(input, init);
      closeRequests.push({ url: String(input), authorization: new Headers(init?.headers).get("Authorization") });
      return new Response(JSON.stringify({ error: { code: "UNAVAILABLE", message: "gateway unavailable" } }), {
        status: 503,
      });
    };
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const session = new VoiceSession();

    try {
      const connecting = session.connect();
      await vi.waitFor(() => expect(offerRequests).toHaveLength(1));
      session.disconnect();
      offer.resolve({ sdp: "answer-sdp", sessionID: "late-external-session" });
      await connecting;

      await vi.waitFor(() =>
        expect(closeRequests).toEqual([
          {
            url: "https://voice.example.com/api/voicegateway/v2/voice/rtc/late-external-session",
            authorization: `Bearer ${serviceToken("user-1", 2)}`,
          },
        ]),
      );
      await vi.waitFor(() => expect(warn).toHaveBeenCalled());
      expect(session.state.connectStatus).toBeNull();
      expect(session.state.error).toBeNull();
    } finally {
      warn.mockRestore();
    }
  });

  it("stops a device-switch microphone stream returned after disconnect", async () => {
    const session = new VoiceSession();
    await session.connect();
    const media = deferred<MediaStream>();
    const stop = vi.fn();
    vi.mocked(navigator.mediaDevices.getUserMedia).mockReturnValue(media.promise);

    const switching = session.selectInputDevice("other-microphone");
    session.disconnect();
    media.resolve({ getTracks: () => [{ stop }], getAudioTracks: () => [{ stop }] } as unknown as MediaStream);
    await switching;

    expect(stop).toHaveBeenCalledOnce();
    expect(session.state.connected).toBe(false);
    expect(session.state.error).toBeNull();
  });

  it("keeps hang_up local to voice and rejects MCP name conflicts", async () => {
    const tools = voiceToolDeclarations(
      [
        {
          name: "items_list",
          description: "List items",
          inputSchema: { type: "object", properties: {} },
        },
      ],
      [],
    );
    expect(tools.map((tool) => tool.name)).toEqual(["hang_up", "items_list"]);
    expect(tools[0]?.parameters).toEqual({ type: "object", properties: {} });

    expect(() =>
      voiceToolDeclarations(
        [
          {
            name: "hang_up",
            description: "Unexpected MCP tool",
            inputSchema: { type: "object", properties: {} },
          },
        ],
        [],
      ),
    ).toThrow('MCP tool "hang_up" conflicts with the reserved voice command.');

    const chime = {
      prepare: vi.fn(),
      playConnected: vi.fn(),
      playDisconnected: vi.fn(),
    };
    const session = new VoiceSession(chime);
    await session.connect();
    const channel = FakePeerConnection.dataChannels[0];
    channel?.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindSessionReady }) }));
    dispatchToolCall(channel, "hang-up-1", "hang_up");

    expect(mcpMocks.mcpCallTool).not.toHaveBeenCalled();
    expect(chime.playDisconnected).toHaveBeenCalledOnce();
    expect(session.state.connected).toBe(false);
  });

  it("preserves MCP success status when structured content contains domain error data", async () => {
    const session = new VoiceSession({ prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() });
    try {
      await session.connect();
      const channel = FakePeerConnection.dataChannels[0];
      channel?.onopen?.();
      for (const [index, error] of [null, "domain validation metadata"].entries()) {
        const content = { error, result: "saved" };
        mcpMocks.mcpCallTool.mockResolvedValue({ isError: false, structuredContent: content });
        const id = `mcp-success-${index}`;
        dispatchToolCall(channel, id, "save");
        await vi.waitFor(() =>
          expect(channel?.send).toHaveBeenCalledWith(
            JSON.stringify({ kind: "tool.result", id, name: "save", result: content }),
          ),
        );
        expect(session.state.transcript).not.toEqual(
          expect.arrayContaining([expect.objectContaining({ text: expect.stringContaining("[save]") })]),
        );
      }
      mcpMocks.mcpCallTool.mockResolvedValue({ structuredContent: { error: "ordinary domain field" } });
      dispatchToolCall(channel, "mcp-default-success", "save");
      await vi.waitFor(() =>
        expect(channel?.send).toHaveBeenCalledWith(
          JSON.stringify({
            kind: "tool.result",
            id: "mcp-default-success",
            name: "save",
            result: { error: "ordinary domain field" },
          }),
        ),
      );
      expect(session.state.transcript).not.toEqual(
        expect.arrayContaining([expect.objectContaining({ text: expect.stringContaining("[save]") })]),
      );
    } finally {
      session.disconnect();
    }
  });

  it("normalizes empty frontend tool schemas", () => {
    const tools = voiceToolDeclarations([], [{ name: "select_item", description: "Select", parameters: {} }]);
    expect(tools.find((tool) => tool.name === "select_item")?.parameters).toEqual({ type: "object", properties: {} });
  });

  it("executes frontend tools locally and returns errors through the gateway", async () => {
    const execute = vi.fn(() => ({ selected: "item-1" }));
    const preparation = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: true });
    const scope = preparation.createScope();
    scope.setTools([
      {
        declaration: { name: "select_item", description: "Select an item", parameters: { type: "object" } },
        execute,
      },
    ]);
    scope.setDataReady(true);
    const session = new VoiceSession({ prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() });
    try {
      const declarations = captureFrontendVoiceTools().declarations;
      expect(voiceToolDeclarations([], declarations).some((tool) => tool.name === "select_item")).toBe(true);
      expect(() =>
        voiceToolDeclarations([{ name: "select_item", description: "Conflict", inputSchema: {} }], declarations),
      ).toThrow(/conflicts/);
      await session.connect();
      const channel = FakePeerConnection.dataChannels[0];
      channel?.onopen?.();
      dispatchToolCall(channel, "frontend-1", "select_item");
      await vi.waitFor(() => expect(execute).toHaveBeenCalledOnce());
      expect(mcpMocks.mcpCallTool).not.toHaveBeenCalled();
      expect(channel?.send).toHaveBeenCalledWith(
        JSON.stringify({ kind: "tool.result", id: "frontend-1", name: "select_item", result: { selected: "item-1" } }),
      );
      execute.mockImplementation(() => {
        throw new Error("Missing item");
      });
      dispatchToolCall(channel, "frontend-2", "select_item");
      await vi.waitFor(() =>
        expect(channel?.send).toHaveBeenCalledWith(
          JSON.stringify({
            kind: "tool.result",
            id: "frontend-2",
            name: "select_item",
            result: { error: "Missing item" },
          }),
        ),
      );
      expect(session.state.transcript).toEqual(
        expect.arrayContaining([expect.objectContaining({ text: "[select_item] Missing item" })]),
      );
      const replacement = preparation.createScope();
      const replacementExecute = vi.fn(() => ({ selected: "replacement" }));
      replacement.setTools([
        { declaration: { name: "select_item", description: "Select", parameters: {} }, execute: replacementExecute },
      ]);
      replacement.setDataReady(true);
      dispatchToolCall(channel, "frontend-retired", "select_item");
      await vi.waitFor(() =>
        expect(channel?.send).toHaveBeenCalledWith(
          JSON.stringify({
            kind: "tool.result",
            id: "frontend-retired",
            name: "select_item",
            result: { error: 'Frontend voice tool "select_item" is unavailable.' },
          }),
        ),
      );
      expect(replacementExecute).not.toHaveBeenCalled();
      expect(mcpMocks.mcpCallTool).not.toHaveBeenCalled();
    } finally {
      session.disconnect();
      preparation.dispose();
    }
  });

  it("cannot dispatch a host action after synchronous voice retirement", async () => {
    const preparation = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: true });
    const scope = preparation.createScope();
    const execute = vi.fn(() => ({ selected: true }));
    scope.setTools([{ declaration: { name: "select_item", description: "Select", parameters: {} }, execute }]);
    scope.setDataReady(true);
    const session = new VoiceSession();
    const dispose = createRoot((dispose) => {
      createEffect(() => {
        if (session.state.activeTool === "select_item") session.disconnect();
      });
      return dispose;
    });
    try {
      await session.connect();
      dispatchToolCall(FakePeerConnection.dataChannels[0], "retired-before-host", "select_item");
      await Promise.resolve();
      expect(execute).not.toHaveBeenCalled();
    } finally {
      dispose();
      session.disconnect();
      preparation.dispose();
    }
  });

  for (const kind of ["frontend", "mcp"] as const) {
    it(`cannot publish ${kind} failure after synchronous completion retirement`, async () => {
      const preparation = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: true });
      const scope = preparation.createScope();
      scope.setTools(
        kind === "frontend"
          ? [
              {
                declaration: { name: "select_item", description: "Select", parameters: {} },
                execute: () => ({ error: "old failure" }),
              },
            ]
          : [],
      );
      scope.setDataReady(true);
      if (kind === "mcp") mcpMocks.mcpCallTool.mockRejectedValueOnce(new Error("old failure"));
      const session = new VoiceSession();
      let active = false;
      let replaced = false;
      let replacement: Promise<void> | undefined;
      const dispose = createRoot((dispose) => {
        createEffect(() => {
          if (session.state.activeTool === "select_item") active = true;
          else if (active && !replaced) {
            replaced = true;
            session.disconnect();
            replacement = session.connect();
          }
        });
        return dispose;
      });
      try {
        await session.connect();
        const previous = session.conversationID;
        const channel = FakePeerConnection.dataChannels[0];
        dispatchToolCall(channel, "retired-result", "select_item");
        await vi.waitFor(() => expect(replaced).toBe(true));
        await replacement;
        expect(session.conversationID).not.toBe(previous);
        expect(session.state.transcript.some((entry) => entry.text.includes("old failure"))).toBe(false);
        expect(channel?.send).not.toHaveBeenCalledWith(expect.stringContaining('"id":"retired-result"'));
      } finally {
        dispose();
        session.disconnect();
        preparation.dispose();
      }
    });
  }

  it("awaits frontend completion and aborts pending host actions when the call ends", async () => {
    const preparation = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: true });
    const scope = preparation.createScope();
    const gate = deferred<void>();
    let committed = false;
    let aborted = false;
    scope.setTools([
      {
        declaration: { name: "select_item", description: "Select", parameters: {} },
        execute: async (_args, context) => {
          context.signal.addEventListener("abort", () => {
            aborted = true;
          });
          await gate.promise;
          if (context.isCurrent()) committed = true;
          return { selected: true };
        },
      },
    ]);
    scope.setDataReady(true);
    const session = new VoiceSession({ prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() });
    try {
      await session.connect();
      const channel = FakePeerConnection.dataChannels[0];
      channel?.onopen?.();
      dispatchToolCall(channel, "pending-frontend", "select_item");
      await vi.waitFor(() => expect(session.state.activeTool).toBe("select_item"));
      expect(channel?.send).not.toHaveBeenCalledWith(expect.stringContaining("pending-frontend"));
      session.disconnect();
      expect(aborted).toBe(true);
      gate.resolve();
      await gate.promise;
      expect(committed).toBe(false);
      expect(channel?.send).not.toHaveBeenCalledWith(expect.stringContaining("pending-frontend"));
    } finally {
      session.disconnect();
      preparation.dispose();
    }
  });

  it("tracks gateway turn status until disconnect", async () => {
    const earcon = { prepare: vi.fn(), setThinking: vi.fn() };
    const session = new VoiceSession({ prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() }, earcon);
    await session.connect();
    const channel = FakePeerConnection.dataChannels[0];
    channel?.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindSessionReady }) }));
    channel?.onmessage?.(
      new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindTurnStatus, state: TurnStateThinking }) }),
    );
    await vi.waitFor(() => expect(session.state.turnState).toBe(TurnStateThinking));
    expect(earcon.prepare).not.toHaveBeenCalled();
    expect(earcon.setThinking).not.toHaveBeenCalledWith(true);

    session.disconnect();
    expect(session.state.turnState).toBe(TurnStateIdle);
  });

  it("chimes once when voice mode connects and disconnects", async () => {
    const chime = {
      prepare: vi.fn(),
      playConnected: vi.fn(),
      playDisconnected: vi.fn(),
    };
    const session = new VoiceSession(chime);

    await session.connect();
    const channel = FakePeerConnection.dataChannels[0];
    channel?.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindSessionReady }) }));
    channel?.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindSessionReady }) }));
    session.disconnect();
    session.disconnect();

    expect(chime.prepare).toHaveBeenCalledOnce();
    expect(chime.playConnected).toHaveBeenCalledOnce();
    expect(chime.playDisconnected).toHaveBeenCalledOnce();
  });

  it("sends an MCP result through its originating voice connection", async () => {
    mcpMocks.mcpCallTool.mockResolvedValue({ structuredContent: { answer: 42 } });
    const session = new VoiceSession();
    await session.connect();
    const channel = FakePeerConnection.dataChannels[0];
    dispatchToolCall(channel, "active-tool", "lookup");

    await vi.waitFor(() => expect(channel?.send).toHaveBeenCalledOnce());
    expect(JSON.parse(channel?.send.mock.calls[0]?.[0] as string)).toEqual({
      kind: "tool.result",
      id: "active-tool",
      name: "lookup",
      result: { answer: 42 },
    });
    expect(session.state.activeTool).toBeNull();
    session.disconnect();
  });

  it("keeps conversation IDs across reconnects and renews them without requiring a secure-context UUID API", async (t) => {
    t.mock.method(crypto, "randomUUID", () => {
      throw new Error("randomUUID is unavailable on HTTP origins");
    });
    mcpMocks.mcpCallTool.mockResolvedValue({ structuredContent: {} });
    const preparation = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: true });
    const scope = preparation.createScope();
    const frontend = vi.fn((_args: Record<string, unknown>, context: FrontendVoiceToolContext) => ({
      sessionID: context.sessionID,
    }));
    scope.setTools([
      { declaration: { name: "select_item", description: "Select", parameters: {} }, execute: frontend },
    ]);
    scope.setDataReady(true);
    const prepare = vi.fn(async (_sessionID: string, _signal: AbortSignal) => {});
    configureVoiceGateway("/", null, undefined, undefined, prepare);
    const session = new VoiceSession();
    session.selectLanguage("fr-CA");
    await session.connect();
    dispatchToolCall(FakePeerConnection.dataChannels[0], "first", "lookup");
    const firstID = mcpMocks.mcpCallTool.mock.calls[0]?.[2]?.sessionID;
    dispatchToolCall(FakePeerConnection.dataChannels[0], "frontend-first", "select_item");
    await vi.waitFor(() => expect(frontend).toHaveBeenCalledOnce());
    expect(frontend.mock.calls[0]?.[1].sessionID).toBe(firstID);
    expect(mcpMocks.mcpReadAdvertisedTextResource.mock.calls[0]?.[2]?.sessionID).toBe(firstID);
    expect(firstID).toMatch(/^[0-9a-f]{32}$/);
    expect(mcpMocks.mcpCallTool.mock.calls[0]?.[2]?.locale).toBe("fr-CA");
    vi.useFakeTimers();
    try {
      triggerIceState("failed");
      await vi.advanceTimersByTimeAsync(0);
      await vi.waitFor(() => expect(offerRequests).toHaveLength(2));
      dispatchToolCall(FakePeerConnection.dataChannels[1], "reconnected", "lookup");
      expect(mcpMocks.mcpCallTool.mock.calls[1]?.[2]).toEqual({ locale: "fr-CA", sessionID: firstID });
      dispatchToolCall(FakePeerConnection.dataChannels[1], "frontend-recovered", "select_item");
      await vi.waitFor(() => expect(frontend).toHaveBeenCalledTimes(2));
      expect(frontend.mock.calls[1]?.[1].sessionID).toBe(firstID);
      expect(mcpMocks.mcpReadAdvertisedTextResource.mock.calls[1]?.[2]?.sessionID).toBe(firstID);
      session.disconnect();
      session.selectLanguage("de-DE");
      await session.connect();
      dispatchToolCall(FakePeerConnection.dataChannels[2], "new", "lookup");
      expect(mcpMocks.mcpCallTool.mock.calls[2]?.[2]?.sessionID).toMatch(/^[0-9a-f]{32}$/);
      expect(mcpMocks.mcpCallTool.mock.calls[2]?.[2]?.sessionID).not.toBe(firstID);
      expect(mcpMocks.mcpCallTool.mock.calls[2]?.[2]?.locale).toBe("de-DE");
      dispatchToolCall(FakePeerConnection.dataChannels[2], "frontend-new", "select_item");
      await vi.waitFor(() => expect(frontend).toHaveBeenCalledTimes(3));
      expect(frontend.mock.calls[2]?.[1].sessionID).toBe(mcpMocks.mcpCallTool.mock.calls[2]?.[2]?.sessionID);
      expect(frontend.mock.calls[2]?.[1].sessionID).not.toBe(firstID);
      expect(prepare.mock.calls.map(([sessionID]) => sessionID)).toEqual([
        firstID,
        firstID,
        frontend.mock.calls[2]?.[1].sessionID,
      ]);
    } finally {
      session.disconnect();
      preparation.dispose();
      vi.useRealTimers();
    }
  });

  it("surfaces tool result delivery failures without replaying the action", async () => {
    for (const failure of ["closed", "exception"]) {
      const session = new VoiceSession();
      await session.connect();
      const channel = FakePeerConnection.dataChannels.at(-1);
      if (!channel) throw new Error("Missing voice channel");
      session.setState((state) => ({ ...state, connected: true, turnState: TurnStateThinking }));
      mcpMocks.mcpCallTool.mockResolvedValue({ structuredContent: { private: "private result" } });
      const calls = mcpMocks.mcpCallTool.mock.calls.length;
      if (failure === "closed") channel.readyState = "closed";
      else
        channel.send.mockImplementation(() => {
          throw new Error("private result");
        });
      dispatchToolCall(channel, "delivery", "save");
      await vi.waitFor(() =>
        expect(session.state.error).toBe(
          "Couldn't deliver the action's result. Reconnect voice. The action may have completed; check before repeating it.",
        ),
      );
      expect(session.state.connected).toBe(false);
      expect(session.state.turnState).toBe(TurnStateIdle);
      expect(session.state.activeTool).toBeNull();
      expect(mcpMocks.mcpCallTool.mock.calls.length).toBe(calls + 1);
      expect(session.state.errorHistory[0]?.message).toBe(session.state.error);
      const sends = channel.send.mock.calls.length;
      dispatchToolCall(channel, "late", "save");
      expect(channel.send.mock.calls.length).toBe(sends);
      expect(mcpMocks.mcpCallTool.mock.calls.length).toBe(calls + 1);
      session.disconnect();
    }
  });

  it("wakes for a queued alert after a silent model turn becomes idle", async () => {
    const session = new VoiceSession();
    try {
      await session.connect();
      const channel = FakePeerConnection.dataChannels.at(-1);
      if (!channel) throw new Error("Missing voice channel");
      const event = (state: string) =>
        channel.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind: "turn.status", state }) }));
      event("thinking");
      for (let index = 0; index <= 100; index++) session.setServiceContext(`Overview ${index}`);
      session.injectText("Build is waiting", true);
      expect(channel.send.mock.calls.some(([text]) => text.includes("Build is waiting"))).toBe(false);
      event("idle");
      expect(channel.send).toHaveBeenLastCalledWith(
        JSON.stringify({ kind: "context.update", context: { text: "Overview 100\nBuild is waiting" }, generate: true }),
      );
      session.injectText("Review has a question", true);
      expect(channel.send.mock.calls.some(([text]) => text.includes("Review has a question"))).toBe(false);
      event("idle");
      expect(channel.send).toHaveBeenLastCalledWith(
        JSON.stringify({ kind: "context.update", context: { text: "Review has a question" }, generate: true }),
      );
    } finally {
      session.disconnect();
    }
  });

  it("surfaces a rejected current context send", async () => {
    const session = new VoiceSession();
    await session.connect();
    const channel = FakePeerConnection.dataChannels.at(-1);
    if (!channel) throw new Error("Missing voice channel");
    channel.readyState = "closed";
    session.injectText("private context");
    expect(session.state.error).toBe("Couldn't send a voice message. Reconnect voice.");
    expect(session.state.connected).toBe(false);
    session.disconnect();
  });

  it("retains a failure through a reconnect and bounds the record", async () => {
    const session = new VoiceSession();
    for (let index = 0; index <= 5; index++) {
      await session.connect();
      FakePeerConnection.dataChannels.at(-1)?.onmessage?.(
        new MessageEvent("message", {
          data: JSON.stringify({ kind: MessageKindError, message: `failure ${index}` }),
        }),
      );
      await vi.waitFor(() => expect(session.state.error).toBe(`failure ${index}`));
    }
    expect(session.state.errorHistory).toHaveLength(5);
    expect(session.state.errorHistory[0]?.message).toBe("failure 5");
    expect(session.state.errorHistory.at(-1)?.message).toBe("failure 1");
    await session.connect();
    expect(session.state.error).toBeNull();
    expect(session.state.errorHistory).toHaveLength(5);
    expect(previousErrors(session.state)).toEqual(session.state.errorHistory);
    session.disconnect();
  });

  it("lists failures before the displayed one", async () => {
    const session = new VoiceSession();
    session.setState((state) => ({
      ...state,
      error: "Voice turn failed (llm): out of memory",
      errorHistory: [
        { at: 2, message: "Voice turn failed (llm): out of memory" },
        { at: 1, message: "Voice connection failed" },
      ],
    }));
    expect(previousErrors(session.state).map((entry) => entry.message)).toEqual(["Voice connection failed"]);
    expect(session.state.errorHistory).toHaveLength(2);
    const empty = new VoiceSession();
    expect(previousErrors(empty.state)).toEqual([]);
  });

  it("retains the latest tool names after completion and disconnect, then resets for a new conversation", async () => {
    mcpMocks.mcpCallTool.mockResolvedValue({ structuredContent: {} });
    const session = new VoiceSession();
    await session.connect();
    const channel = FakePeerConnection.dataChannels.at(-1);
    dispatchToolCall(channel, "first", "same_tool");
    dispatchToolCall(channel, "second", "same_tool");
    expect(session.state.toolHistory.map((entry) => entry.name)).toEqual(["same_tool", "same_tool"]);
    expect(new Set(session.state.toolHistory.map((entry) => entry.sequence)).size).toBe(2);
    for (let i = 1; i <= 32; i++) dispatchToolCall(channel, `call-${i}`, `tool_${i}`);
    expect(session.state.toolHistory).toHaveLength(32);
    expect(session.state.toolHistory[0]?.name).toBe("tool_1");
    expect(session.state.toolHistory.at(-1)?.name).toBe("tool_32");
    await Promise.resolve();
    expect(session.state.activeTool).toBeNull();
    session.disconnect();
    expect(session.state.toolHistory.at(-1)?.name).toBe("tool_32");
    await session.connect();
    dispatchToolCall(channel, "late", "old_connection");
    expect(session.state.toolHistory).toEqual([]);
    session.disconnect();
  });

  it("preserves outstanding tools and transport after a recoverable gateway error", async () => {
    const tool = deferred<Awaited<ReturnType<typeof mcpClient.callTool>>>();
    mcpMocks.mcpCallTool.mockReturnValue(tool.promise);
    const session = new VoiceSession({ prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() });
    await session.connect();
    const channel = FakePeerConnection.dataChannels[0];
    channel?.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindSessionReady }) }));
    dispatchToolCall(channel, "pending-tool", "lookup");
    expect(session.state.activeTool).toBe("lookup");
    channel?.onmessage?.(
      new MessageEvent("message", {
        data: JSON.stringify({
          kind: MessageKindError,
          message: "Streaming OutOfOrder support is unmeasured",
          recoverable: true,
        }),
      }),
    );
    expect(session.state.connected).toBe(true);
    expect(session.state.error).toBeNull();
    expect(session.state.activeTool).toBe("lookup");
    expect(session.state.transcript.at(-1)?.text).toBe("[Voice] Streaming OutOfOrder support is unmeasured");
    expect(channel?.readyState).toBe("open");
    expect(closeRequests).toEqual([]);
    tool.resolve({ structuredContent: { answer: "late" } });
    await tool.promise;
    await Promise.resolve();
    expect(channel?.send).toHaveBeenCalledWith(
      JSON.stringify({ kind: "tool.result", id: "pending-tool", name: "lookup", result: { answer: "late" } }),
    );
    expect(session.state.activeTool).toBeNull();
    session.disconnect();
  });

  it("replaces live drafts, commits atomically, and isolates replaced connections", async () => {
    const session = new VoiceSession();
    await session.connect();
    const channel = FakePeerConnection.dataChannels[0];
    const send = (value: object) => channel?.onmessage?.(new MessageEvent("message", { data: JSON.stringify(value) }));
    send({ kind: "transcript.draft", id: "a", text: "I love pears" });
    send({ kind: "transcript.draft", id: "a", text: "I love bananas" });
    expect(session.state.transcriptDraft).toEqual({ id: "a", text: "I love bananas" });
    expect(session.state.transcript).toEqual([]);
    expect(buildRecoveryContext(session.state.transcript)).not.toContain("bananas");
    send({ kind: "transcript.draft", id: "b", text: "Next request" });
    send({ kind: "transcript.delta", speaker: "user", draft_id: "a", text: "I love bananas." });
    send({ kind: "transcript.draft", id: "a", text: "" });
    expect(session.state.transcriptDraft?.text).toBe("Next request");
    expect(session.state.transcript).toEqual([
      { kind: "transcript", speaker: "user", text: "I love bananas.", final: true },
    ]);
    send({ kind: "transcript.delta", speaker: "user", draft_id: "b", text: "Next request." });
    expect(session.state.transcriptDraft).toBeNull();
    expect(session.state.transcript).toHaveLength(2);
    send({ kind: "transcript.draft", id: "c", text: "Canceled" });
    send({ kind: "transcript.draft", id: "c", text: "" });
    expect(session.state.transcriptDraft).toBeNull();
    session.disconnect();
    await session.connect();
    send({ kind: "transcript.draft", id: "stale", text: "Retired" });
    expect(session.state.transcriptDraft).toBeNull();
    session.disconnect();
  });

  it("discards a late MCP result from a replaced voice connection", async () => {
    const tool = deferred<Awaited<ReturnType<typeof mcpClient.callTool>>>();
    mcpMocks.mcpCallTool.mockReturnValue(tool.promise);
    const session = new VoiceSession();
    await session.connect();
    dispatchToolCall(FakePeerConnection.dataChannels[0], "old-tool", "lookup");
    await vi.waitFor(() => expect(mcpMocks.mcpCallTool).toHaveBeenCalled());

    session.disconnect();
    await session.connect();
    tool.resolve({ structuredContent: { error: "late result" }, isError: true });
    await new Promise((resolve) => setImmediate(resolve));

    expect(FakePeerConnection.dataChannels[1]?.send).not.toHaveBeenCalled();
    expect(session.state.transcript).toEqual([]);
    expect(session.state.activeTool).toBeNull();
    session.disconnect();
  });

  it("discards a late MCP error from a replaced voice connection", async () => {
    const tool = deferred<Awaited<ReturnType<typeof mcpClient.callTool>>>();
    mcpMocks.mcpCallTool.mockReturnValue(tool.promise);
    const session = new VoiceSession();
    await session.connect();
    dispatchToolCall(FakePeerConnection.dataChannels[0], "old-tool", "lookup");
    await vi.waitFor(() => expect(mcpMocks.mcpCallTool).toHaveBeenCalled());

    session.disconnect();
    await session.connect();
    tool.reject(new Error("late failure"));
    await new Promise((resolve) => setImmediate(resolve));

    expect(FakePeerConnection.dataChannels[1]?.send).not.toHaveBeenCalled();
    expect(session.state.transcript).toEqual([]);
    expect(session.state.activeTool).toBeNull();
    session.disconnect();
  });

  it("revalidates a removed selection before creating an offer", async () => {
    const session = new VoiceSession();
    await session.loadVoiceChoices();
    session.selectChoice("gemini");
    const gatewayFetch = globalThis.fetch;
    globalThis.fetch = async (input, init) =>
      String(input).endsWith("/voice/choices")
        ? new Response(
            JSON.stringify({
              choices: [{ id: "local", label: "Local LLM", transports: ["webrtc"] }],
              defaults: [{ transport: "webrtc", id: "local" }],
            }),
          )
        : gatewayFetch(input, init);
    await session.connect();
    expect(offerRequests).toHaveLength(0);
    expect(session.state.choiceIDs.webrtc).toBe("gemini");
    expect(session.state.error).toContain("selected voice choice is unavailable");
    expect(session.state.choicesError).toContain("selected voice choice is unavailable");
    session.disconnect();
  });

  it("pins the selected gateway and choice through setup and reconnect", async () => {
    configureVoiceGateway("https://original.example", null);
    const session = new VoiceSession();
    await session.loadVoiceChoices();
    session.selectChoice("gemini");
    const instructions = deferred<string>();
    mcpMocks.mcpServerInstructions.mockReturnValueOnce(instructions.promise);
    const connecting = session.connect();
    await vi.waitFor(() => expect(mcpMocks.mcpServerInstructions).toHaveBeenCalledOnce());
    configureVoiceGateway("https://replacement.example", null);
    instructions.resolve("instructions");
    await connecting;
    expect(offerRequests[0]?.url).toContain("https://original.example/");
    vi.useFakeTimers();
    try {
      triggerIceState("failed");
      await vi.advanceTimersByTimeAsync(0);
      await vi.waitFor(() => expect(offerRequests).toHaveLength(2));
      expect(offerRequests[1]?.url).toContain("https://original.example/");
      expect(offerRequests[1]?.body).toMatchObject({ choiceID: "gemini" });
    } finally {
      session.disconnect();
      vi.useRealTimers();
    }
  });

  it("sends the complete local SDP after ICE gathering", async () => {
    const session = new VoiceSession();

    await session.connect();

    expect(offerRequests[0]?.body).toEqual({
      choiceID: "gemini",
      sdp: "v=0\r\na=candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host\r\n",
    });
  });

  it("includes host-issued authorization for an external gateway", async () => {
    let issued = 0;
    const service = {
      kind: "caic",
      instanceID: "home",
      baseURL: "https://caic.example.com",
      token: serviceToken("user-1", 1),
    };
    configureVoiceGateway("https://voice.example.com", async () => ({
      ...service,
      token: serviceToken("user-1", ++issued),
    }));
    const session = new VoiceSession();

    await session.connect();

    expect(offerRequests[0]?.body).toEqual({
      choiceID: "gemini",
      sdp: "v=0\r\na=candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host\r\n",
      service,
    });
    session.disconnect();
    await vi.waitFor(() =>
      expect(closeRequests).toEqual([
        {
          url: "https://voice.example.com/api/voicegateway/v2/voice/rtc/session-1",
          authorization: `Bearer ${serviceToken("user-1", 2)}`,
        },
      ]),
    );
  });

  it("refreshes an OAuth access token by issuer and subject", async () => {
    let issued = 0;
    const service = {
      kind: "caic",
      instanceID: "home",
      baseURL: "https://caic.example.com",
      token: oauthToken("user-1", 1),
    };
    configureVoiceGateway("https://voice.example.com", async () => ({
      ...service,
      token: oauthToken("user-1", ++issued),
    }));
    const session = new VoiceSession();

    await session.connect();
    session.disconnect();

    await vi.waitFor(() =>
      expect(closeRequests).toEqual([
        {
          url: "https://voice.example.com/api/voicegateway/v2/voice/rtc/session-1",
          authorization: `Bearer ${oauthToken("user-1", 2)}`,
        },
      ]),
    );
  });

  it("attempts close with the offer token if refresh fails", async () => {
    let issued = false;
    configureVoiceGateway("https://voice.example.com", async () => {
      if (issued) throw new Error("token endpoint unavailable");
      issued = true;
      return {
        kind: "caic",
        instanceID: "home",
        baseURL: "https://caic.example.com",
        token: serviceToken("user-1", 1),
      };
    });
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const session = new VoiceSession();
    try {
      await session.connect();
      session.disconnect();

      await vi.waitFor(() =>
        expect(closeRequests).toEqual([
          {
            url: "https://voice.example.com/api/voicegateway/v2/voice/rtc/session-1",
            authorization: `Bearer ${serviceToken("user-1", 1)}`,
          },
        ]),
      );
      expect(warn).toHaveBeenCalled();
    } finally {
      warn.mockRestore();
    }
  });

  it("keeps the offer gateway and account when configuration changes before close", async () => {
    let issued = 0;
    configureVoiceGateway("https://old-voice.example.com", async () => ({
      kind: "caic",
      instanceID: "home",
      baseURL: "https://caic.example.com",
      token: serviceToken(++issued === 1 ? "user-1" : "user-2", issued),
    }));
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const session = new VoiceSession();
    try {
      await session.connect();
      configureVoiceGateway("https://new-voice.example.com", async () => ({
        kind: "caic",
        instanceID: "home",
        baseURL: "https://caic.example.com",
        token: serviceToken("user-2", 3),
      }));
      session.disconnect();

      await vi.waitFor(() =>
        expect(closeRequests).toEqual([
          {
            url: "https://old-voice.example.com/api/voicegateway/v2/voice/rtc/session-1",
            authorization: `Bearer ${serviceToken("user-1", 1)}`,
          },
        ]),
      );
      expect(warn).toHaveBeenCalledWith("Voice gateway authorization changed identity; using offer token");
    } finally {
      warn.mockRestore();
    }
  });

  it("sends the offer to the captured gateway when token issuance is pending", async () => {
    const authorization = deferred<{ kind: string; instanceID: string; baseURL: string; token: string }>();
    const oldProvider = vi.fn(() => authorization.promise);
    configureVoiceGateway("https://old-voice.example.com", oldProvider);
    const session = new VoiceSession();

    const connecting = session.connect();
    await vi.waitFor(() => expect(oldProvider).toHaveBeenCalledOnce());
    configureVoiceGateway("https://new-voice.example.com", async () => ({
      kind: "caic",
      instanceID: "home",
      baseURL: "https://caic.example.com",
      token: serviceToken("user-2", 2),
    }));
    const service = {
      kind: "caic",
      instanceID: "home",
      baseURL: "https://caic.example.com",
      token: serviceToken("user-1", 1),
    };
    authorization.resolve(service);
    await connecting;

    expect(offerRequests).toEqual([
      {
        url: "https://old-voice.example.com/api/voicegateway/v2/voice/rtc/offer",
        authorization: null,
        body: {
          choiceID: "gemini",
          sdp: "v=0\r\na=candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host\r\n",
          service,
        },
      },
    ]);
    session.disconnect();
  });

  it("keeps the offer gateway and account for a late response after reconfiguration", async () => {
    let issued = 0;
    configureVoiceGateway("https://old-voice.example.com", async () => ({
      kind: "caic",
      instanceID: "home",
      baseURL: "https://caic.example.com",
      token: serviceToken(++issued === 1 ? "user-1" : "user-2", issued),
    }));
    const offer = deferred<VoiceRTCAnswerResp>();
    offerResponse = offer.promise.then((answer) => new Response(JSON.stringify(answer)));
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const session = new VoiceSession();
    try {
      const connecting = session.connect();
      await vi.waitFor(() => expect(offerRequests).toHaveLength(1));
      session.disconnect();
      configureVoiceGateway("https://new-voice.example.com", async () => ({
        kind: "caic",
        instanceID: "home",
        baseURL: "https://caic.example.com",
        token: serviceToken("user-2", 3),
      }));
      offer.resolve({ sdp: "answer-sdp", sessionID: "late-session" });
      await connecting;

      await vi.waitFor(() =>
        expect(closeRequests).toEqual([
          {
            url: "https://old-voice.example.com/api/voicegateway/v2/voice/rtc/late-session",
            authorization: `Bearer ${serviceToken("user-1", 1)}`,
          },
        ]),
      );
      expect(warn).toHaveBeenCalledWith("Voice gateway authorization changed identity; using offer token");
    } finally {
      warn.mockRestore();
    }
  });

  it("closes a same-origin gateway session on disconnect only once", async () => {
    const session = new VoiceSession();
    await session.connect();

    session.disconnect();
    session.disconnect();

    await vi.waitFor(() =>
      expect(closeRequests).toEqual([
        {
          url: `${window.location.origin}/api/voicegateway/v2/voice/rtc/session-1`,
          authorization: null,
        },
      ]),
    );
  });

  it("leaves same-origin close authentication to the gateway fetch wrapper", async () => {
    let token: string | null = "host-token";
    configureVoiceGateway("/", null, () => token);
    const session = new VoiceSession();
    await session.connect();
    token = "refreshed-token";

    session.disconnect();

    expect(offerRequests[0]?.body).toEqual({
      choiceID: "gemini",
      sdp: "v=0\r\na=candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host\r\n",
    });
    await vi.waitFor(() =>
      expect(closeRequests).toEqual([
        {
          url: `${window.location.origin}/api/voicegateway/v2/voice/rtc/session-1`,
          authorization: "Bearer refreshed-token",
        },
      ]),
    );
  });

  it("retains originating host headers for signaling and cleanup after session replacement", async () => {
    let identity = "session-A";
    const seen: Array<{ url: string; identity: string | null }> = [];
    const fetchGateway = globalThis.fetch;
    globalThis.fetch = (input, init) => {
      seen.push({ url: String(input), identity: new Headers(init?.headers).get("X-Host-Session") });
      return fetchGateway(input, init);
    };
    configureVoiceGateway("/", null, undefined, () => ({ "X-Host-Session": identity }));
    const session = new VoiceSession();
    await session.connect();
    identity = "session-B";
    configureVoiceGateway("/", null, undefined, () => ({ "X-Host-Session": identity }));
    session.disconnect();
    await vi.waitFor(() => expect(closeRequests).toHaveLength(1));
    expect(seen.some((request) => request.url.endsWith("/rtc/offer"))).toBe(true);
    expect(
      seen.filter((request) => request.url.includes("/rtc/")).every((request) => request.identity === "session-A"),
    ).toBe(true);
  });

  it("closes the gateway session and drops late tools when the voice protocol reports a fatal error", async () => {
    const tool = deferred<Awaited<ReturnType<typeof mcpClient.callTool>>>();
    mcpMocks.mcpCallTool.mockReturnValue(tool.promise);
    const session = new VoiceSession();
    await session.connect();
    const channel = FakePeerConnection.dataChannels[0];
    dispatchToolCall(channel, "pending-tool", "lookup");

    FakePeerConnection.dataChannels[0]?.onmessage?.(
      new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindError, message: "session failed" }) }),
    );

    await vi.waitFor(() =>
      expect(closeRequests).toEqual([
        {
          url: `${window.location.origin}/api/voicegateway/v2/voice/rtc/session-1`,
          authorization: null,
        },
      ]),
    );
    expect(session.state.error).toBe("session failed");
    expect(session.state.connected).toBe(false);
    expect(channel?.readyState).toBe("closed");
    tool.resolve({ structuredContent: { answer: "late" } });
    await tool.promise;
    await Promise.resolve();
    expect(channel?.send).not.toHaveBeenCalled();
  });

  it("refreshes host authorization for standalone diagnostics", async () => {
    let issue = 0;
    configureVoiceGateway("https://voice.example.com", async () => ({
      kind: "caic",
      instanceID: "home",
      baseURL: "https://caic.example.com",
      token: serviceToken("user-1", ++issue),
    }));
    diagnosticResponse = {} as VoiceRTCDiagnosticsResp;
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const session = new VoiceSession();
    try {
      await session.connect();
      configureVoiceGateway("https://new-voice.example.com", async () => ({
        kind: "caic",
        instanceID: "home",
        baseURL: "https://caic.example.com",
        token: serviceToken("user-2", 3),
      }));
      triggerIceState("closed");

      await vi.waitFor(() => expect(warn).toHaveBeenCalledWith("Voice RTC diagnostics", expect.any(Object)));
      expect(diagnosticRequests).toHaveLength(1);
      expect(diagnosticRequests[0]?.authorization).toBe(`Bearer ${serviceToken("user-1", 2)}`);
      expect(diagnosticRequests[0]?.url).toBe(
        "https://voice.example.com/api/voicegateway/v2/voice/rtc/session-1/diagnostics",
      );
    } finally {
      session.disconnect();
      warn.mockRestore();
    }
  });

  it("awaits host session preparation before reading setup MCP context", async () => {
    const gate = deferred<void>();
    const prepare = vi.fn(async (_sessionID: string, _signal: AbortSignal) => gate.promise);
    configureVoiceGateway("/", null, undefined, undefined, prepare);
    const session = new VoiceSession();
    try {
      const connecting = session.connect();
      await vi.waitFor(() => expect(prepare).toHaveBeenCalledOnce());
      expect(prepare.mock.calls[0]?.[0]).toBe(session.conversationID);
      expect(mcpMocks.mcpServerInstructions).not.toHaveBeenCalled();
      expect(mcpMocks.mcpListTools).not.toHaveBeenCalled();
      expect(mcpMocks.mcpReadAdvertisedTextResource).not.toHaveBeenCalled();
      gate.resolve();
      await connecting;
      expect(mcpMocks.mcpReadAdvertisedTextResource).toHaveBeenCalledWith("gomode://items", undefined, {
        sessionID: session.conversationID,
      });
    } finally {
      session.disconnect();
    }
  });

  it("retires pending host preparation without adopting a replacement callback", async () => {
    const gate = deferred<void>();
    const original = vi.fn(async (_sessionID: string, _signal: AbortSignal) => gate.promise);
    const replacement = vi.fn(async (_sessionID: string, _signal: AbortSignal) => {});
    configureVoiceGateway("/", null, undefined, undefined, original);
    const session = new VoiceSession();
    try {
      const old = session.connect();
      await vi.waitFor(() => expect(original).toHaveBeenCalledOnce());
      const firstID = session.conversationID;
      configureVoiceGateway("/", null, undefined, undefined, replacement);
      expect(replacement).not.toHaveBeenCalled();
      session.disconnect();
      expect(original.mock.calls[0]?.[1].aborted).toBe(true);
      await session.connect();
      expect(replacement).toHaveBeenCalledOnce();
      expect(session.conversationID).not.toBe(firstID);
      const reads = mcpMocks.mcpReadAdvertisedTextResource.mock.calls.length;
      gate.resolve();
      await old;
      expect(mcpMocks.mcpReadAdvertisedTextResource).toHaveBeenCalledTimes(reads);
      expect(replacement).toHaveBeenCalledOnce();
    } finally {
      gate.resolve();
      session.disconnect();
    }
  });

  it("treats host focus publication failure as advisory but honors explicit abort", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const session = new VoiceSession();
    try {
      configureVoiceGateway("/", null, undefined, undefined, async () => {
        throw new Error("Focus publication unavailable");
      });
      await session.connect();
      expect(mcpMocks.mcpReadAdvertisedTextResource).toHaveBeenCalledOnce();
      expect(warn).toHaveBeenCalledWith("Could not prepare voice context", expect.any(Error));
      session.disconnect();
      const reads = mcpMocks.mcpReadAdvertisedTextResource.mock.calls.length;
      configureVoiceGateway("/", null, undefined, undefined, async () => {
        throw new DOMException("Owner retired", "AbortError");
      });
      await session.connect();
      expect(mcpMocks.mcpReadAdvertisedTextResource).toHaveBeenCalledTimes(reads);
      expect(session.state.connectStatus).toBeNull();
    } finally {
      session.disconnect();
      warn.mockRestore();
    }
  });

  it("includes the current bounded service items in session setup", async () => {
    mcpMocks.mcpReadAdvertisedTextResource.mockResolvedValue(
      '{"items":[{"id":"1","reference":"Item #1","title":"Build feature","state":"running","needsAttention":false,"active":true}]}',
    );
    const session = new VoiceSession();
    session.setShowReasoning(true);

    await session.connect();
    FakePeerConnection.dataChannels[0]?.onopen?.();

    const sent = FakePeerConnection.dataChannels[0]?.send.mock.calls[0]?.[0];
    const setup = JSON.parse(sent as string);
    expect(setup).toMatchObject({
      kind: "session.setup",
      includeReasoning: true,
      context: {
        text: "Active service item: Item #1 (1).\nCurrent service items:\n- Item #1: Build feature (running)",
      },
    });
    expect(setup.context.systemInstruction).toContain('only "Ready"');
    expect(setup.context.systemInstruction).toContain('"Done"');
    expect(setup.context.systemInstruction).toContain("answered completely with a number");
    expect(setup.voice.language).toBe("en-US");
    expect(setup.context.systemInstruction).toContain("service item updates");
    expect(setup.context.systemInstruction).toMatch(/\n\ninstructions$/);
  });

  it("leaves voice selection to the provider unless a named preference is saved", async () => {
    for (const catalog of [null, [], ["Aoede", "Orus"]]) {
      offeredVoices = catalog;
      const session = new VoiceSession();
      await session.connect();
      const channel = FakePeerConnection.dataChannels.at(-1);
      channel?.onopen?.();
      expect(JSON.parse(channel?.send.mock.calls[0]?.[0] as string).voice).not.toHaveProperty("name");
      session.disconnect();
    }
  });

  it("pins the saved speaking voice during delayed setup and reconnect", async () => {
    offeredVoices = ["Aoede", "Orus"];
    configureVoiceGateway("https://original.example", null);
    const session = new VoiceSession();
    await session.loadVoiceChoices();
    session.selectSpeakingVoice("Aoede");
    const instructions = deferred<string>();
    mcpMocks.mcpServerInstructions.mockReturnValueOnce(instructions.promise);
    const connecting = session.connect();
    await vi.waitFor(() => expect(mcpMocks.mcpServerInstructions).toHaveBeenCalledOnce());
    localStorage.setItem('gomode.speakingVoice.["https://original.example","gemini"]', "Orus");
    configureVoiceGateway("https://replacement.example", null);
    instructions.resolve("instructions");
    await connecting;
    FakePeerConnection.dataChannels[0]?.onopen?.();
    expect(JSON.parse(FakePeerConnection.dataChannels[0]?.send.mock.calls[0]?.[0] as string).voice.name).toBe("Aoede");
    expect(() => session.selectSpeakingVoice("Orus")).toThrow("End the session");
    vi.useFakeTimers();
    try {
      triggerIceState("failed");
      await vi.advanceTimersByTimeAsync(0);
      await vi.waitFor(() => expect(offerRequests).toHaveLength(2));
      FakePeerConnection.dataChannels[1]?.onopen?.();
      expect(JSON.parse(FakePeerConnection.dataChannels[1]?.send.mock.calls[0]?.[0] as string).voice.name).toBe(
        "Aoede",
      );
      expect(offerRequests[1]?.url).toContain("https://original.example/");
    } finally {
      session.disconnect();
      vi.useRealTimers();
    }
  });

  const recoveryFixtures = JSON.parse(
    readFileSync(new URL("../../testdata/voice-choices/selection.json", import.meta.url), "utf8"),
  ) as {
    recovery: Array<{
      name: string;
      initialVoices: string[] | null;
      voice: string;
      voices: string[] | null;
      browserError: boolean;
    }>;
  };
  for (const row of recoveryFixtures.recovery) {
    it(`shared voice recovery: ${row.name}`, async () => {
      offeredVoices = row.initialVoices;
      const session = new VoiceSession({ prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() });
      await session.loadVoiceChoices();
      if (row.voice !== "") session.selectSpeakingVoice(row.voice);
      await session.connect();
      const channel = FakePeerConnection.dataChannels[0];
      channel?.onopen?.();
      channel?.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindSessionReady }) }));
      expect(session.state.connected).toBe(true);
      offeredVoices = row.voices;
      vi.useFakeTimers();
      try {
        triggerIceState("failed");
        await vi.advanceTimersByTimeAsync(0);
        if (row.browserError) {
          await vi.waitFor(() => expect(session.state.error).toContain("speaking voice is unavailable"));
          expect(offerRequests).toHaveLength(1);
        } else {
          await vi.waitFor(() => expect(offerRequests).toHaveLength(2));
          const recovered = FakePeerConnection.dataChannels.at(-1);
          recovered?.onopen?.();
          expect(JSON.parse(recovered?.send.mock.calls[0]?.[0] as string).voice.name).toBe(row.voice || undefined);
        }
        expect(session.state.voiceName).toBe(row.voice);
      } finally {
        session.disconnect();
        vi.useRealTimers();
      }
    });
  }

  it("blocks removed speaking voices until an available replacement is saved", async () => {
    offeredVoices = ["Aoede", "Orus"];
    const session = new VoiceSession();
    await session.loadVoiceChoices();
    session.selectSpeakingVoice("Aoede");
    offeredVoices = ["Orus"];
    await session.connect();
    expect(offerRequests).toHaveLength(0);
    expect(session.state.error).toContain("saved speaking voice is unavailable");
    expect(session.state.voiceName).toBe("Aoede");
    expect(session.state.voiceError).toContain("saved speaking voice is unavailable");
    session.selectSpeakingVoice("Orus");
    await session.connect();
    FakePeerConnection.dataChannels[0]?.onopen?.();
    expect(JSON.parse(FakePeerConnection.dataChannels[0]?.send.mock.calls[0]?.[0] as string).voice.name).toBe("Orus");
    session.disconnect();
  });

  it("reports speaking-voice reads that fail during fresh connection discovery", async () => {
    offeredVoices = ["Aoede", "Orus"];
    const session = new VoiceSession();
    const read = window.Storage.prototype.getItem;
    const load = vi.spyOn(window.Storage.prototype, "getItem").mockImplementation(function (this: Storage, key) {
      if (key.startsWith("gomode.speakingVoice.")) throw new Error("Voice storage unavailable");
      return read.call(this, key);
    });
    try {
      await session.connect();
      expect(offerRequests).toHaveLength(0);
      expect(session.state.error).toBe("Voice storage unavailable");
      expect(session.state.voiceStatus).toBe("error");
    } finally {
      load.mockRestore();
      session.disconnect();
    }
    await session.loadVoiceChoices();
    expect(session.state.voiceName).toBe("");
    expect(session.state.voiceError).toBeNull();
  });

  it("saves a regional language and sends it to the gateway", async () => {
    const session = new VoiceSession();
    session.selectLanguage(" fr-ca ");
    expect(new VoiceSession().state.languageTag).toBe("fr-CA");
    await session.connect();
    FakePeerConnection.dataChannels[0]?.onopen?.();
    const sent = FakePeerConnection.dataChannels[0]?.send.mock.calls[0]?.[0];
    expect(JSON.parse(sent as string).voice.language).toBe("fr-CA");
    expect(() => session.selectLanguage("en-US")).toThrow("End the session");
    session.disconnect();
    session.selectLanguage("en-US");
    expect(() => session.selectLanguage("en_US")).toThrow();
    expect(session.state.languageTag).toBe("en-US");
  });

  it("uses the Go Mode instruction when the host has no instructions", async () => {
    mcpMocks.mcpServerInstructions.mockResolvedValueOnce("");
    const session = new VoiceSession();

    await session.connect();
    FakePeerConnection.dataChannels[0]?.onopen?.();

    const sent = FakePeerConnection.dataChannels[0]?.send.mock.calls[0]?.[0];
    const instruction = JSON.parse(sent as string).context.systemInstruction;
    expect(instruction).not.toBe("");
    expect(instruction).not.toContain("\n\n");
  });

  it("starts with an empty baseline when service-item loading fails", async () => {
    mcpMocks.mcpReadAdvertisedTextResource.mockRejectedValue(new Error("resource unavailable"));
    const session = new VoiceSession();

    await session.connect();
    FakePeerConnection.dataChannels[0]?.onopen?.();

    expect(offerRequests).toHaveLength(1);
    const sent = FakePeerConnection.dataChannels[0]?.send.mock.calls[0]?.[0];
    expect(JSON.parse(sent as string).context.text).toBe("No visible service items.");
  });

  it("waits briefly for a reflexive candidate without waiting for ICE completion", async () => {
    vi.useFakeTimers();
    FakePeerConnection.completeICE = false;
    FakePeerConnection.reflexiveCandidateDelayMs = 50;
    const session = new VoiceSession();

    try {
      const connect = session.connect();
      await vi.advanceTimersByTimeAsync(49);
      expect(offerRequests).toHaveLength(0);

      await vi.advanceTimersByTimeAsync(1);
      await connect;

      expect(offerRequests[0]?.body).toEqual({
        choiceID: "gemini",
        sdp: "v=0\r\na=candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host\r\na=candidate:2 1 udp 1694498815 203.0.113.2 50000 typ srflx raddr 192.0.2.2 rport 50000\r\n",
      });
      expect(FakePeerConnection.last?.iceGatheringState).toBe("gathering");
    } finally {
      session.disconnect();
      vi.useRealTimers();
    }
  });

  it("does not start setup timeout before signaling completes", async () => {
    vi.useFakeTimers();
    const offer = deferred<VoiceRTCAnswerResp>();
    offerResponse = offer.promise.then((answer) => new Response(JSON.stringify(answer)));
    const session = new VoiceSession();

    try {
      const connect = session.connect();
      await vi.waitFor(() => expect(offerRequests).toHaveLength(1));

      await vi.advanceTimersByTimeAsync(15_000);

      expect(session.state.error).toBeNull();
      offer.resolve({ sdp: "answer-sdp", sessionID: "session-1" });
      await connect;
    } finally {
      session.disconnect();
      vi.useRealTimers();
    }
  });

  it("keeps WebRTC setup alive while the local stack reports progress", async () => {
    vi.useFakeTimers();
    const session = new VoiceSession({ prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() });
    try {
      await session.connect();
      const channel = FakePeerConnection.dataChannels[0];
      channel?.onopen?.();
      for (let i = 0; i < 5; i++) {
        await vi.advanceTimersByTimeAsync(10000);
        channel?.onmessage?.(
          new MessageEvent("message", {
            data: JSON.stringify({
              kind: "session.status",
              message: "Loading local stack…",
            }),
          }),
        );
        expect(session.state.connectStatus).toBe("Loading local stack…");
        expect(session.state.connected).toBe(false);
        expect(session.state.error).toBeNull();
      }
      channel?.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindSessionReady }) }));
      await vi.advanceTimersByTimeAsync(15000);
      expect(session.state.connected).toBe(true);
      expect(session.state.connectStatus).toBeNull();
      expect(session.state.error).toBeNull();
    } finally {
      session.disconnect();
      vi.useRealTimers();
    }
  });
});

function triggerIceState(state: RTCIceConnectionState): void {
  const pc = FakePeerConnection.last;
  if (!pc) throw new Error("Expected a peer connection");
  pc.iceConnectionState = state;
  pc.oniceconnectionstatechange?.call(pc as unknown as RTCPeerConnection, new Event("iceconnectionstatechange"));
}

describe("voice network recovery", () => {
  it("associates response reports independently of text order, fragments and reasoning", async () => {
    const session = new VoiceSession();
    const send = (message: object) =>
      FakePeerConnection.dataChannels[0]?.onmessage?.(new MessageEvent("message", { data: JSON.stringify(message) }));
    const report = (responseID: string | undefined, inputTokens: number) =>
      send({
        kind: "session.usage",
        sessionID: "one",
        provider: "test",
        model: "model",
        totals: { inputTokens: 100 },
        responseID,
        responseUsage: { inputTokens, outputTokens: 0 },
        lastResponse: { inputTokens: 999 },
      });
    const text = (responseID: string | undefined, text: string) =>
      send({
        kind: "transcript.delta",
        speaker: "assistant",
        text,
        responseID,
      });
    try {
      await session.connect();
      session.setState((s) => ({ ...s, showReasoning: true }));
      report("a", 10);
      text("a", "First");
      send({ kind: "reasoning.delta", text: "Reason", responseID: "a" });
      send({ kind: "speech.ended" });
      text("b", "Second");
      text("a", " continued");
      report("b", 20);
      report("a", 11);
      report("a", 11);
      text(undefined, "Uncorrelated");
      report(undefined, 99);
      const rows = session.state.transcript;
      expect(rows).toHaveLength(4);
      expect(rows[0]).toMatchObject({
        text: "First continued",
        final: true,
        responseID: "a",
        responseUsage: { inputTokens: 11, outputTokens: 0 },
      });
      expect(rows[1]?.responseUsage).toBeUndefined();
      expect(rows[2]).toMatchObject({ text: "Second", responseID: "b", responseUsage: { inputTokens: 20 } });
      expect(rows[3]?.responseUsage).toBeUndefined();
      expect(session.state.usage?.totals.inputTokens).toBe(100);
      for (let i = 0; i < 257; i++) report(`pending-${i}`, i);
      text("a", " again");
      expect(session.state.transcript[0]?.responseUsage?.inputTokens).toBe(11);
      expect(session.state.usage?.responses?.size).toBe(256);
      session.disconnect();
      expect(session.state.transcript[0]?.responseUsage?.inputTokens).toBe(11);
    } finally {
      session.disconnect();
    }
  });
  it("retains usage across recovery and disconnect, then resets for a new conversation", async () => {
    vi.useFakeTimers();
    const session = new VoiceSession();
    const report = (channel: FakeDataChannel, sessionID: string, inputTokens: number) =>
      channel.onmessage?.(
        new MessageEvent("message", {
          data: JSON.stringify({
            kind: "session.usage",
            sessionID,
            provider: "test",
            model: "model",
            totals: { inputTokens, outputTokens: 0 },
            responseID: `${sessionID}/response`,
            responseUsage: { inputTokens },
          }),
        }),
      );
    try {
      await session.connect();
      const first = FakePeerConnection.dataChannels[0]!;
      report(first, "one", 100);
      report(first, "one", 100);
      expect(session.state.usage?.totals.inputTokens).toBe(100);
      triggerIceState("failed");
      await vi.advanceTimersByTimeAsync(0);
      await vi.waitFor(() => expect(FakePeerConnection.dataChannels).toHaveLength(2));
      report(FakePeerConnection.dataChannels[1]!, "two", 30);
      report(first, "one", 999);
      const recovered = FakePeerConnection.dataChannels[1]!;
      for (const responseID of ["one/response", "two/response"])
        recovered.onmessage?.(
          new MessageEvent("message", {
            data: JSON.stringify({ kind: "transcript.delta", speaker: "assistant", text: responseID, responseID }),
          }),
        );
      expect(session.state.transcript.map((entry) => entry.responseUsage?.inputTokens)).toEqual([100, 30]);
      expect(session.state.usage?.totals.inputTokens).toBe(130);
      session.disconnect();
      expect(session.state.usage?.totals.inputTokens).toBe(130);
      await session.connect();
      expect(session.state.usage).toBeNull();
    } finally {
      session.disconnect();
      vi.useRealTimers();
    }
  });
  it("retains a fatal delivery error when ICE recovery was pending", async () => {
    vi.useFakeTimers();
    const session = new VoiceSession();
    try {
      await session.connect();
      const channel = FakePeerConnection.dataChannels[0]!;
      channel.onopen?.();
      triggerIceState("disconnected");
      channel.send.mockImplementation(() => {
        throw new Error("private context");
      });
      session.injectText("context");
      const failure = session.state.error;
      expect(failure).toContain("Reconnect voice");
      await vi.advanceTimersByTimeAsync(5_000);
      expect(FakePeerConnection.instances).toHaveLength(1);
      expect(session.state.error).toBe(failure);
      expect(session.state.connected).toBe(false);
    } finally {
      session.disconnect();
      vi.useRealTimers();
    }
  });

  it("fetches a fresh service-item snapshot for reconnect setup", async () => {
    vi.useFakeTimers();
    mcpMocks.mcpReadAdvertisedTextResource
      .mockResolvedValueOnce('{"items":[{"id":"1","title":"Old state","state":"running","needsAttention":false}]}')
      .mockResolvedValueOnce('{"items":[{"id":"1","title":"Fresh state","state":"waiting","needsAttention":true}]}');
    const session = new VoiceSession();
    try {
      await session.connect();
      FakePeerConnection.dataChannels[0]?.onopen?.();

      triggerIceState("failed");
      await vi.advanceTimersByTimeAsync(0);
      await vi.waitFor(() => expect(FakePeerConnection.dataChannels).toHaveLength(2));
      expect(closeRequests).toContainEqual({
        url: `${window.location.origin}/api/voicegateway/v2/voice/rtc/session-1`,
        authorization: null,
      });
      FakePeerConnection.dataChannels[1]?.onopen?.();

      const firstSetup = FakePeerConnection.dataChannels[0]?.send.mock.calls[0]?.[0];
      const secondSetup = FakePeerConnection.dataChannels[1]?.send.mock.calls[0]?.[0];
      expect(JSON.parse(firstSetup as string).context.text).toContain("Old state");
      expect(JSON.parse(secondSetup as string).context.text).toContain("Fresh state");
    } finally {
      session.disconnect();
      vi.useRealTimers();
    }
  });

  it("cancels the disconnected grace recovery when ICE reconnects", async () => {
    vi.useFakeTimers();
    const session = new VoiceSession();
    try {
      await session.connect();
      triggerIceState("disconnected");
      expect(session.state.connectStatus).toContain("reconnecting");

      await vi.advanceTimersByTimeAsync(4_999);
      triggerIceState("connected");
      await vi.advanceTimersByTimeAsync(1);

      expect(FakePeerConnection.instances).toHaveLength(1);
    } finally {
      session.disconnect();
      vi.useRealTimers();
    }
  });

  it("recovers immediately after ICE failure and stops after three retries", async () => {
    vi.useFakeTimers();
    const session = new VoiceSession();
    try {
      await session.connect();
      for (let attempt = 1; attempt <= 3; attempt++) {
        triggerIceState("failed");
        await vi.advanceTimersByTimeAsync(0);
        await vi.waitFor(() => expect(FakePeerConnection.instances).toHaveLength(attempt + 1));
      }

      triggerIceState("failed");
      expect(session.state.error).toBe("Voice connection lost after 3 recovery attempts");
      expect(FakePeerConnection.instances).toHaveLength(4);
    } finally {
      session.disconnect();
      vi.useRealTimers();
    }
  });

  it("cancels a pending recovery when manually disconnected", async () => {
    vi.useFakeTimers();
    const session = new VoiceSession();
    try {
      await session.connect();
      triggerIceState("disconnected");
      session.disconnect();
      await vi.advanceTimersByTimeAsync(5_000);

      expect(FakePeerConnection.instances).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("reasoning display preference", () => {
  it("defaults to hidden and persists the choice for later sessions", () => {
    const session = new VoiceSession();
    expect(session.state.showReasoning).toBe(false);
    session.setShowReasoning(true);
    expect(localStorage.getItem("gomode.voiceReasoning")).toBe("on");
    expect(new VoiceSession().state.showReasoning).toBe(true);
    session.setShowReasoning(false);
    expect(localStorage.getItem("gomode.voiceReasoning")).toBe("off");
    expect(new VoiceSession().state.showReasoning).toBe(false);
  });
});

describe("usage display preference", () => {
  it("defaults to hidden and persists the choice for later sessions", () => {
    const session = new VoiceSession();
    expect(session.state.showUsageStats).toBe(false);
    session.setShowUsageStats(true);
    expect(localStorage.getItem("gomode.voiceUsageStats")).toBe("on");
    expect(new VoiceSession().state.showUsageStats).toBe(true);
    session.setShowUsageStats(false);
    expect(localStorage.getItem("gomode.voiceUsageStats")).toBe("off");
    expect(new VoiceSession().state.showUsageStats).toBe(false);
  });
});

describe("buildRecoveryContext", () => {
  it("preserves finalized chronological transcript and bounded service context", () => {
    const context = buildRecoveryContext([
      { kind: "transcript", speaker: "user", text: "first", final: true },
      { kind: "reasoning", speaker: "assistant", text: "weigh both dates", final: true },
      { kind: "transcript", speaker: "assistant", text: "second", final: true },
      { kind: "transcript", speaker: "user", text: "partial", final: false },
    ]);

    expect(context).toContain("do not treat this as a new user turn");
    expect(context).toContain("user: first\nassistant: second");
    expect(context).not.toContain("partial");
    expect(context).not.toContain("weigh both dates");
  });

  it("bounds recovery messages without splitting finalized history order", () => {
    const context = buildRecoveryContext(
      Array.from({ length: 20 }, (_, index) => ({
        kind: "transcript" as const,
        speaker: "user" as const,
        text: `${index}: ${"x".repeat(600)}`,
        final: true,
      })),
    );

    expect(context.length).toBeLessThanOrEqual(MAX_RECOVERY_CONTEXT_CHARS);
    expect(context.indexOf("user: 19:")).toBeGreaterThan(context.indexOf("user: 18:"));
  });
});

describe("summarizeSDPCandidates", () => {
  it("summarizes candidate host, port, and type", () => {
    const sdp =
      "v=0\r\na=candidate:1 1 udp 2130706431 70.51.33.231 42602 typ srflx raddr 192.168.1.123 rport 42602\r\na=candidate:2 1 udp 2130706431 192.168.1.123 42602 typ host\r\n";

    expect(summarizeSDPCandidates(sdp)).toBe("70.51.33.231:42602 srflx, 192.168.1.123:42602 host");
  });

  it("reports none when SDP has no candidates", () => {
    expect(summarizeSDPCandidates("v=0\r\n")).toBe("none");
  });
});

describe("formatVoiceRTCDiagnostics", () => {
  it("surfaces UDP mapping errors", () => {
    const diagnostics: VoiceRTCDiagnosticsResp = {
      sessionID: "voice-session",
      issue: VoiceRTCConnectivityIssueUDPUnreachable,
      side: VoiceRTCConnectivitySideNetwork,
      message: "server is waiting for a WebRTC data channel",
      server: {
        sessionFound: true,
        udpMappingError: "refresh UPnP UDP mapping 40000 -> 3478: timeout",
      },
    };

    expect(formatVoiceRTCDiagnostics(diagnostics)).toContain(
      "UDP mapping: refresh UPnP UDP mapping 40000 -> 3478: timeout",
    );
  });
});
