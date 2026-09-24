// Tests for the browser voice gateway session manager.

import { beforeEach, describe, it } from "node:test";
import { expect, vi } from "../tests/expect";

import {
  buildRecoveryContext,
  formatVoiceRTCDiagnostics,
  MAX_RECOVERY_CONTEXT_CHARS,
  summarizeSDPCandidates,
  VoiceSession,
  configureVoiceGateway,
  voiceGatewayApi,
  voiceToolDeclarations,
} from "./VoiceSession";
import { mcpClient } from "./McpClient";

// Spies on the voicegateway API client and the MCP client object replace the former module mocks.
const sdkMocks = {
  voiceRTCOffer: vi.spyOn(voiceGatewayApi, "voiceRTCOffer"),
  diagnoseVoiceRTC: vi.spyOn(voiceGatewayApi, "diagnoseVoiceRTC"),
  closeVoiceRTC: vi.spyOn(voiceGatewayApi, "closeVoiceRTC"),
};
const mcpMocks = {
  mcpCallTool: vi.spyOn(mcpClient, "callTool"),
  mcpListTools: vi.spyOn(mcpClient, "listTools"),
  mcpReadAdvertisedTextResource: vi.spyOn(mcpClient, "readAdvertisedTextResource"),
  mcpServerInstructions: vi.spyOn(mcpClient, "serverInstructions"),
};
import {
  MessageKindToolCall,
  VoiceRTCConnectivityIssueUDPUnreachable,
  VoiceRTCConnectivitySideNetwork,
  type VoiceRTCDiagnosticsResp,
} from "../../sdk/voicegateway/ts/v1/types.gen";

class FakePeerConnection extends EventTarget {
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

  addTrack(): void {}

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

beforeEach(() => {
  configureVoiceGateway("/", null);
  FakePeerConnection.completeICE = true;
  FakePeerConnection.reflexiveCandidateDelayMs = null;
  FakePeerConnection.last = null;
  FakePeerConnection.instances = [];
  FakePeerConnection.dataChannels = [];
  sdkMocks.closeVoiceRTC.mockReset();
  sdkMocks.diagnoseVoiceRTC.mockReset();
  sdkMocks.voiceRTCOffer.mockReset();
  sdkMocks.voiceRTCOffer.mockResolvedValue({ sdp: "answer-sdp", sessionID: "session-1" });
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
    expect(sdkMocks.voiceRTCOffer).not.toHaveBeenCalled();
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
    expect(sdkMocks.voiceRTCOffer).not.toHaveBeenCalled();
    expect(session.state.connectStatus).toBeNull();
    expect(session.state.error).toBeNull();
  });

  it("ignores a late signaling response and stale data-channel events", async () => {
    const offer = deferred<Awaited<ReturnType<typeof voiceGatewayApi.voiceRTCOffer>>>();
    sdkMocks.voiceRTCOffer.mockReturnValue(offer.promise);
    sdkMocks.closeVoiceRTC.mockResolvedValue({ status: "closed" });
    const session = new VoiceSession();

    const connecting = session.connect();
    await vi.waitFor(() => expect(sdkMocks.voiceRTCOffer).toHaveBeenCalled());
    session.disconnect();
    offer.resolve({ sdp: "answer-sdp", sessionID: "late-session" });
    await connecting;
    FakePeerConnection.dataChannels[0]?.onopen?.();

    expect(sdkMocks.closeVoiceRTC).toHaveBeenCalledWith("late-session", {});
    expect(FakePeerConnection.dataChannels[0]?.send).not.toHaveBeenCalled();
    expect(session.state.connectStatus).toBeNull();
    expect(session.state.listening).toBe(false);
    expect(session.state.error).toBeNull();
  });

  it("uses the offer token to close a late external session without restoring UI on failure", async () => {
    configureVoiceGateway("https://voice.example.com", async () => ({
      kind: "caic",
      instanceID: "home",
      baseURL: "https://caic.example.com",
      token: "offer-token",
    }));
    const offer = deferred<Awaited<ReturnType<typeof voiceGatewayApi.voiceRTCOffer>>>();
    sdkMocks.voiceRTCOffer.mockReturnValue(offer.promise);
    sdkMocks.closeVoiceRTC.mockRejectedValue(new Error("gateway unavailable"));
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const session = new VoiceSession();

    try {
      const connecting = session.connect();
      await vi.waitFor(() => expect(sdkMocks.voiceRTCOffer).toHaveBeenCalled());
      session.disconnect();
      offer.resolve({ sdp: "answer-sdp", sessionID: "late-external-session" });
      await connecting;

      expect(sdkMocks.closeVoiceRTC).toHaveBeenCalledWith("late-external-session", {
        Authorization: "Bearer offer-token",
      });
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

    const session = new VoiceSession();
    await session.connect();
    dispatchToolCall(FakePeerConnection.dataChannels[0], "hang-up-1", "hang_up");

    expect(mcpMocks.mcpCallTool).not.toHaveBeenCalled();
    expect(session.state.connected).toBe(false);
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

    expect(sdkMocks.voiceRTCOffer).toHaveBeenCalledWith({
      sdp: "v=0\r\na=candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host\r\n",
    });
  });

  it("includes host-issued authorization for an external gateway", async () => {
    const service = {
      kind: "caic",
      instanceID: "home",
      baseURL: "https://caic.example.com",
      token: "scoped-token",
    };
    configureVoiceGateway("https://voice.example.com", async () => service);
    const session = new VoiceSession();

    await session.connect();

    expect(sdkMocks.voiceRTCOffer).toHaveBeenCalledWith({
      sdp: "v=0\r\na=candidate:1 1 udp 2130706431 192.0.2.2 50000 typ host\r\n",
      service,
    });
    session.disconnect();
  });

  it("refreshes host authorization for standalone diagnostics", async () => {
    let issue = 0;
    configureVoiceGateway("https://voice.example.com", async () => ({
      kind: "caic",
      instanceID: "home",
      baseURL: "https://caic.example.com",
      token: `token-${++issue}`,
    }));
    sdkMocks.diagnoseVoiceRTC.mockResolvedValue({} as VoiceRTCDiagnosticsResp);
    const session = new VoiceSession();

    await session.connect();
    triggerIceState("closed");

    await vi.waitFor(() => expect(sdkMocks.diagnoseVoiceRTC).toHaveBeenCalled());
    expect(sdkMocks.diagnoseVoiceRTC.mock.calls[0]?.[2]).toEqual({ Authorization: "Bearer token-2" });
    session.disconnect();
  });

  it("includes the current bounded service items in session setup", async () => {
    mcpMocks.mcpReadAdvertisedTextResource.mockResolvedValue(
      '{"items":[{"id":"1","reference":"Item #1","title":"Build feature","state":"running","needsAttention":false}]}',
    );
    const session = new VoiceSession();

    await session.connect();
    FakePeerConnection.dataChannels[0]?.onopen?.();

    const sent = FakePeerConnection.dataChannels[0]?.send.mock.calls[0]?.[0];
    expect(JSON.parse(sent as string)).toMatchObject({
      kind: "session.setup",
      context: {
        systemInstruction: "instructions",
        text: "Current service items:\n- Item #1: Build feature (running)",
      },
    });
  });

  it("starts with an empty baseline when service-item loading fails", async () => {
    mcpMocks.mcpReadAdvertisedTextResource.mockRejectedValue(new Error("resource unavailable"));
    const session = new VoiceSession();

    await session.connect();
    FakePeerConnection.dataChannels[0]?.onopen?.();

    expect(sdkMocks.voiceRTCOffer).toHaveBeenCalled();
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
      expect(sdkMocks.voiceRTCOffer).not.toHaveBeenCalled();

      await vi.advanceTimersByTimeAsync(1);
      await connect;

      expect(sdkMocks.voiceRTCOffer).toHaveBeenCalledWith({
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
    let resolveOffer: (value: { sdp: string; sessionID: string }) => void = () => {};
    sdkMocks.voiceRTCOffer.mockReturnValue(
      new Promise((resolve) => {
        resolveOffer = resolve;
      }),
    );
    const session = new VoiceSession();

    try {
      const connect = session.connect();
      await vi.waitFor(() => expect(sdkMocks.voiceRTCOffer).toHaveBeenCalled());

      await vi.advanceTimersByTimeAsync(15_000);

      expect(session.state.error).toBeNull();
      resolveOffer({ sdp: "answer-sdp", sessionID: "session-1" });
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
