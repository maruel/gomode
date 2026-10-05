// Tests for the browser voice gateway session manager.

import { afterEach, beforeEach, describe, it } from "node:test";
import { expect, vi } from "../tests/expect";

import {
  buildRecoveryContext,
  formatVoiceRTCDiagnostics,
  MAX_RECOVERY_CONTEXT_CHARS,
  summarizeSDPCandidates,
  VoiceSession,
  configureVoiceGateway,
  voiceToolDeclarations,
} from "./VoiceSession";
import { registerFrontendVoiceTool } from "./FrontendVoiceTools";
import { mcpClient } from "./McpClient";

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
} from "../../sdk/voicegateway/ts/v1/types.gen";

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

const originalFetch = globalThis.fetch;
let closeRequests: Array<{ url: string; authorization: string | null }> = [];
let offerRequests: Array<{ url: string; authorization: string | null; body: unknown }> = [];
let diagnosticRequests: Array<{ url: string; authorization: string | null }> = [];
let offerResponse: Promise<Response> | null = null;
let diagnosticResponse: VoiceRTCDiagnosticsResp | null = null;

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
  localStorage.removeItem("gomode.voiceLanguage");
  closeRequests = [];
  offerRequests = [];
  diagnosticRequests = [];
  offerResponse = null;
  diagnosticResponse = null;
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    const authorization = new Headers(init?.headers).get("Authorization");
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
  vi.stubGlobal("AudioContext", FakeAudioContext as unknown as typeof AudioContext);
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

class FakeAudioContext {
  createAnalyser(): AnalyserNode {
    return {
      fftSize: 0,
      frequencyBinCount: 1,
      getByteTimeDomainData: () => {},
    } as unknown as AnalyserNode;
  }

  createMediaStreamSource(): MediaStreamAudioSourceNode {
    return { connect: () => {} } as unknown as MediaStreamAudioSourceNode;
  }

  close(): Promise<void> {
    return Promise.resolve();
  }
}

describe("VoiceSession", () => {
  it("keeps a failed speaker recovery visible when the same headset microphone recovers", async () => {
    const devices = [
      { kind: "audioinput", deviceId: "usb", label: "Headset microphone" } as MediaDeviceInfo,
      { kind: "audiooutput", deviceId: "bt", label: "Headset speaker" } as MediaDeviceInfo,
    ];
    const sink = vi.fn(async (_id: string) => {});
    Object.defineProperty(HTMLMediaElement.prototype, "setSinkId", { configurable: true, value: sink });
    const audio = new window.Audio();
    vi.spyOn(audio, "play").mockResolvedValue();
    vi.stubGlobal("Audio", function () {
      return audio;
    });
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
      vi.stubGlobal("Audio", function () {
        return audio;
      });
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
        expect(FakePeerConnection.last?.addedTracks).toEqual([oldTrack]);
        return { getTracks: () => [newTrack], getAudioTracks: () => [newTrack] } as unknown as MediaStream;
      });
    const session = new VoiceSession();
    await session.selectInputDevice("usb");
    await session.connect();
    expect(FakePeerConnection.last?.addedTracks).toEqual([oldTrack]);
    expect(FakePeerConnection.last?.sender.replaceTrack).toHaveBeenCalledWith(newTrack);
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
    expect(FakePeerConnection.last?.sender.replaceTrack).toHaveBeenCalledWith(newTrack);
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
    expect(session.state.audioSwitching).toBeNull();
    session.disconnect();
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
    session.toggleMute();
    const pc = FakePeerConnection.last;
    pc?.sender.replaceTrack.mockImplementation(async () => {
      expect(stop).not.toHaveBeenCalled();
    });
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue({
      getTracks: () => [newTrack],
      getAudioTracks: () => [newTrack],
    } as unknown as MediaStream);
    await session.selectInputDevice("headset");
    expect(pc?.sender.replaceTrack).toHaveBeenCalledWith(newTrack);
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
        { url: `${window.location.origin}/api/voicegateway/v1/voice/rtc/late-session`, authorization: null },
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
      if (String(input).endsWith("/voice/rtc/offer")) return fetchOffer(input, init);
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
            url: "https://voice.example.com/api/voicegateway/v1/voice/rtc/late-external-session",
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
    const tools = voiceToolDeclarations([
      {
        name: "items_list",
        description: "List items",
        inputSchema: { type: "object", properties: {} },
      },
    ]);
    expect(tools.map((tool) => tool.name)).toEqual(["hang_up", "items_list"]);

    expect(() =>
      voiceToolDeclarations([
        {
          name: "hang_up",
          description: "Unexpected MCP tool",
          inputSchema: { type: "object", properties: {} },
        },
      ]),
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

  it("executes frontend tools locally and returns errors through the gateway", async () => {
    const execute = vi.fn(() => ({ selected: "item-1" }));
    const unregister = registerFrontendVoiceTool({
      declaration: { name: "select_item", description: "Select an item", parameters: { type: "object" } },
      execute,
    });
    const session = new VoiceSession({ prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() });
    try {
      expect(voiceToolDeclarations([]).some((tool) => tool.name === "select_item")).toBe(true);
      expect(() => voiceToolDeclarations([{ name: "select_item", description: "Conflict", inputSchema: {} }])).toThrow(
        /conflicts/,
      );
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
    } finally {
      session.disconnect();
      unregister();
    }
  });

  it("tracks gateway turn status until disconnect", async () => {
    const session = new VoiceSession({ prepare: vi.fn(), playConnected: vi.fn(), playDisconnected: vi.fn() });
    await session.connect();
    const channel = FakePeerConnection.dataChannels[0];
    channel?.onmessage?.(new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindSessionReady }) }));
    channel?.onmessage?.(
      new MessageEvent("message", { data: JSON.stringify({ kind: MessageKindTurnStatus, state: TurnStateThinking }) }),
    );
    await vi.waitFor(() => expect(session.state.turnState).toBe(TurnStateThinking));

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

  it("sends the complete local SDP after ICE gathering", async () => {
    const session = new VoiceSession();

    await session.connect();

    expect(offerRequests[0]?.body).toEqual({
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
      sdp: "v=0\r\na=candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host\r\n",
      service,
    });
    session.disconnect();
    await vi.waitFor(() =>
      expect(closeRequests).toEqual([
        {
          url: "https://voice.example.com/api/voicegateway/v1/voice/rtc/session-1",
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
          url: "https://voice.example.com/api/voicegateway/v1/voice/rtc/session-1",
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
            url: "https://voice.example.com/api/voicegateway/v1/voice/rtc/session-1",
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
            url: "https://old-voice.example.com/api/voicegateway/v1/voice/rtc/session-1",
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
        url: "https://old-voice.example.com/api/voicegateway/v1/voice/rtc/offer",
        authorization: null,
        body: { sdp: "v=0\r\na=candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host\r\n", service },
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
            url: "https://old-voice.example.com/api/voicegateway/v1/voice/rtc/late-session",
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
          url: `${window.location.origin}/api/voicegateway/v1/voice/rtc/session-1`,
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
      sdp: "v=0\r\na=candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host\r\n",
    });
    await vi.waitFor(() =>
      expect(closeRequests).toEqual([
        {
          url: `${window.location.origin}/api/voicegateway/v1/voice/rtc/session-1`,
          authorization: "Bearer refreshed-token",
        },
      ]),
    );
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
          url: `${window.location.origin}/api/voicegateway/v1/voice/rtc/session-1`,
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
        "https://voice.example.com/api/voicegateway/v1/voice/rtc/session-1/diagnostics",
      );
    } finally {
      session.disconnect();
      warn.mockRestore();
    }
  });

  it("includes the current bounded service items in session setup", async () => {
    mcpMocks.mcpReadAdvertisedTextResource.mockResolvedValue(
      '{"items":[{"id":"1","reference":"Item #1","title":"Build feature","state":"running","needsAttention":false}]}',
    );
    const session = new VoiceSession();

    await session.connect();
    FakePeerConnection.dataChannels[0]?.onopen?.();

    const sent = FakePeerConnection.dataChannels[0]?.send.mock.calls[0]?.[0];
    const setup = JSON.parse(sent as string);
    expect(setup).toMatchObject({
      kind: "session.setup",
      context: { text: "Current service items:\n- Item #1: Build feature (running)" },
    });
    expect(setup.context.systemInstruction).toContain('only "Ready"');
    expect(setup.context.systemInstruction).toContain('"Done"');
    expect(setup.context.systemInstruction).toContain("answered completely with a number");
    expect(setup.voice.language).toBe("en-US");
    expect(setup.context.systemInstruction).toContain("service item updates");
    expect(setup.context.systemInstruction).toMatch(/\n\ninstructions$/);
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
});

function triggerIceState(state: RTCIceConnectionState): void {
  const pc = FakePeerConnection.last;
  if (!pc) throw new Error("Expected a peer connection");
  pc.iceConnectionState = state;
  pc.oniceconnectionstatechange?.call(pc as unknown as RTCPeerConnection, new Event("iceconnectionstatechange"));
}

describe("voice network recovery", () => {
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
        url: `${window.location.origin}/api/voicegateway/v1/voice/rtc/session-1`,
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

describe("buildRecoveryContext", () => {
  it("preserves finalized chronological transcript and bounded service context", () => {
    const context = buildRecoveryContext([
      { speaker: "user", text: "first", final: true },
      { speaker: "assistant", text: "second", final: true },
      { speaker: "user", text: "partial", final: false },
    ]);

    expect(context).toContain("do not treat this as a new user turn");
    expect(context).toContain("user: first\nassistant: second");
    expect(context).not.toContain("partial");
  });

  it("bounds recovery messages without splitting finalized history order", () => {
    const context = buildRecoveryContext(
      Array.from({ length: 20 }, (_, index) => ({
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
