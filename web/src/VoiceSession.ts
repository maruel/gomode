// Browser voice sessions via WebRTC and speech, dispatching MCP and frontend tools.

import { createStore, produce } from "solid-js/store";

// TODO: Cleanup imports.
import * as voicegatewaySDK from "../../sdk/voicegateway/ts/v1/api.gen";
import {
  type Error,
  type ContextUpdate,
  type UserMessage,
  type MessageEnvelope,
  type SessionSetup,
  type ToolResult,
  type ToolCall,
  type TranscriptDelta,
  type AssistantTextDelta,
  type TurnState,
  type TurnStatus,
  type VoiceRTCClientDiagnostics,
  type VoiceRTCDiagnosticsResp,
  MessageKindContextUpdate,
  MessageKindAssistantTextDelta,
  MessageKindUserMessage,
  MessageKindError,
  MessageKindInterrupted,
  MessageKindSessionReady,
  MessageKindSessionSetup,
  MessageKindSpeechEnded,
  MessageKindSpeechStarted,
  MessageKindToolCall,
  MessageKindToolResult,
  MessageKindTranscriptDelta,
  MessageKindTurnStatus,
  TurnStateIdle,
  type ServiceAuthorization,
} from "../../sdk/voicegateway/ts/v1/types.gen";

import { mcpClient, type McpToolDescriptor } from "./McpClient";
import { executeFrontendVoiceTool, frontendVoiceToolDeclarations } from "./FrontendVoiceTools";
import { GO_MODE_ITEMS_RESOURCE_URI, initialServiceContext } from "./ServiceItems";
import { WebAudioVoiceChime, type VoiceChimePlayer } from "./VoiceChime";
import { BrowserSpeech, browserSpeechAvailable, browserSpeechAvoidsChimes } from "./BrowserSpeech";

// Constants

/** Max time (ms) to wait for session.ready before timing out. */
const SETUP_TIMEOUT_MS = 15000;
const ICE_GATHERING_TIMEOUT_MS = 10000;
const ICE_HOST_CANDIDATE_GRACE_MS = 1000;
const ICE_DISCONNECTED_GRACE_MS = 5000;
const MAX_RECONNECT_ATTEMPTS = 3;
const HANG_UP_TOOL_NAME = "hang_up";
const GO_MODE_SYSTEM_INSTRUCTION =
  'You are a terse voice assistant. After the voice session connects, greet the user once with only "Ready". ' +
  "Reply only to the current request in one or two short sentences unless the user explicitly asks for more detail. " +
  "Speak quickly and omit background, explanations, and summaries that were not requested. " +
  'For simple confirmations and acknowledgements, prefer only "Okay", "Yes", "No", or "Done". ' +
  "When the user's question can be answered completely with a number, reply with only the number. " +
  "When the user asks for specific information, reply directly without forcing it into a complete sentence. " +
  "Do not ask a follow-up or confirmation unless missing information makes the request impossible or " +
  "safety-critical. " +
  "Ask only for information required to proceed. Once enough information is available, perform the request without " +
  "asking for confirmation. " +
  "Do not volunteer ideas, next steps, related actions, or offers. " +
  "Use the service MCP tools whenever they are useful after the user asks. Other than the initial greeting, do not " +
  "speak or invoke tools until the user asks. When notified of service item updates, do not verbally acknowledge " +
  "or confirm receipt. Stay silent unless host instructions require a service-specific notification or the user " +
  "explicitly asks for a response.";
/** Conservative data-channel/model-safe bound for a recovery context update. */
export const MAX_RECOVERY_CONTEXT_CHARS = 8000;

let gatewayBaseURL: string | null = null;
type ServiceAuthorizationProvider = () => Promise<ServiceAuthorization>;
let serviceAuthorizationProvider: ServiceAuthorizationProvider | null = null;
let gatewayBearerTokenProvider: (() => string | null | Promise<string | null>) | null = null;

/** Configure a gateway origin and an optional host-issued scoped-token provider. */
export function configureVoiceGateway(
  baseURL: string,
  provider: ServiceAuthorizationProvider | null,
  tokenProvider?: () => string | null | Promise<string | null>,
): void {
  const u = new URL(baseURL, window.location.origin);
  if (u.protocol !== "http:" && u.protocol !== "https:") throw new Error("Voice gateway URL must use HTTP or HTTPS");
  gatewayBaseURL = u.origin;
  serviceAuthorizationProvider = provider;
  gatewayBearerTokenProvider = tokenProvider ?? null;
}

type BearerTokenProvider = () => string | null | Promise<string | null>;

async function gatewayFetch(
  origin: string,
  bearerProvider: BearerTokenProvider | null,
  path: string,
  init?: RequestInit,
): Promise<Response> {
  const headers = new Headers(init?.headers);
  if (origin === window.location.origin && !headers.has("Authorization")) {
    const token = await bearerProvider?.();
    if (token) headers.set("Authorization", `Bearer ${token}`);
  }
  return fetch(new URL(path, origin).toString(), { ...init, headers });
}

// Late-bound API for callers outside VoiceSession; session operations use a pinned client.
export const voiceGatewayApi = voicegatewaySDK.createApiClient((path, init) =>
  gatewayFetch(gatewayBaseURL ?? window.location.origin, gatewayBearerTokenProvider, path, init),
);

interface GatewaySnapshot {
  origin: string;
  serviceProvider: ServiceAuthorizationProvider | null;
  bearerProvider: BearerTokenProvider | null;
  api: ReturnType<typeof voicegatewaySDK.createApiClient>;
}

function gatewaySnapshot(): GatewaySnapshot {
  const origin = gatewayBaseURL ?? window.location.origin;
  const serviceProvider = serviceAuthorizationProvider;
  const bearerProvider = gatewayBearerTokenProvider;
  return {
    origin,
    serviceProvider,
    bearerProvider,
    api: voicegatewaySDK.createApiClient((path, init) => gatewayFetch(origin, bearerProvider, path, init)),
  };
}

function stripTrailingSlash(value: string): string {
  return value.replace(/\/+$/, "");
}

/** Resolves the token subject used to detect an identity change across token refreshes. */
function scopedTokenSubject(service: ServiceAuthorization): string | null {
  const token = service.token;
  const parts = token.split(".");
  const payload = parts.length === 2 ? parts[0] : parts.length === 3 ? parts[1] : undefined;
  if (!payload) return null;
  try {
    const binary = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
    const claims: unknown = JSON.parse(new TextDecoder().decode(bytes));
    if (typeof claims !== "object" || claims === null) return null;
    const fields = claims as Record<string, unknown>;
    if (typeof fields.sub !== "string" || fields.sub === "") return null;
    if (
      typeof fields.serviceKind === "string" ||
      typeof fields.serviceInstanceID === "string" ||
      typeof fields.backendOrigin === "string"
    ) {
      // Transitional scoped Ed25519 token: bind every service claim.
      if (
        fields.serviceKind !== service.kind ||
        fields.serviceInstanceID !== service.instanceID ||
        fields.backendOrigin !== service.baseURL
      )
        return null;
      return fields.sub;
    }
    // OAuth access token: bind the issuer to the service base URL.
    if (typeof fields.iss === "string" && stripTrailingSlash(fields.iss) === stripTrailingSlash(service.baseURL))
      return fields.sub;
    return null;
  } catch {
    return null;
  }
}

function sameServiceIdentity(offer: ServiceAuthorization, refreshed: ServiceAuthorization): boolean {
  const subject = scopedTokenSubject(offer);
  return (
    subject !== null &&
    scopedTokenSubject(refreshed) === subject &&
    offer.kind === refreshed.kind &&
    offer.instanceID === refreshed.instanceID &&
    offer.baseURL === refreshed.baseURL
  );
}

async function closeVoiceGatewaySession(
  snapshot: GatewaySnapshot,
  sessionID: string,
  offerService: ServiceAuthorization | null,
): Promise<void> {
  const token = await refreshedServiceToken(snapshot, offerService);
  const headers: Record<string, string> = token === null ? {} : { Authorization: `Bearer ${token}` };
  await snapshot.api.closeVoiceRTC(sessionID, headers);
}

async function refreshedServiceToken(
  snapshot: GatewaySnapshot,
  offerService: ServiceAuthorization | null,
): Promise<string | null> {
  if (offerService !== null && snapshot.serviceProvider !== null) {
    try {
      const refreshed = await snapshot.serviceProvider();
      if (sameServiceIdentity(offerService, refreshed)) {
        return refreshed.token;
      } else {
        console.warn("Voice gateway authorization changed identity; using offer token");
      }
    } catch (error: unknown) {
      console.warn("Could not refresh voice gateway authorization", error);
    }
  }
  return offerService?.token ?? null;
}

/**
 * Voice-local tool declarations, kept outside the service MCP tool set.
 * Keep this list and its dispatcher in sync with android/gomode/src/main/java/com/fghbuild/gomode/voice/VoiceSession.kt.
 */
export function voiceToolDeclarations(mcpTools: McpToolDescriptor[]): SessionSetup["tools"] {
  if (mcpTools.some((tool) => tool.name === HANG_UP_TOOL_NAME)) {
    throw new Error(`MCP tool "${HANG_UP_TOOL_NAME}" conflicts with the reserved voice command.`);
  }
  const frontendTools = frontendVoiceToolDeclarations();
  for (const tool of frontendTools) {
    if (mcpTools.some((mcp) => mcp.name === tool.name)) {
      throw new Error(`MCP tool "${tool.name}" conflicts with a frontend voice tool.`);
    }
  }
  return [
    ...frontendTools,
    {
      name: HANG_UP_TOOL_NAME,
      description:
        "End the current voice conversation immediately when the user asks to hang up, end the call, or stop voice mode.",
      parameters: { type: "object", properties: {} },
    },
    ...mcpTools.map((tool) => ({
      name: tool.name,
      description: tool.description,
      parameters: tool.inputSchema,
    })),
  ];
}

// State types

export type TranscriptSpeaker = "user" | "assistant";

export interface TranscriptEntry {
  speaker: TranscriptSpeaker;
  text: string;
  final: boolean;
}

/** Describes a single audio input/output device. Mirrors Android's AudioDevice. */
export interface AudioDevice {
  deviceId: string;
  kind: MediaDeviceKind;
  label: string;
}

export type VoiceMode = "browser" | "cloud";
type VoiceChannel = RTCDataChannel | WebSocket;

function voiceChannelOpen(channel: VoiceChannel): boolean {
  return channel.readyState === "open" || channel.readyState === 1; // WebSocket.OPEN
}

export interface VoiceState {
  mode: VoiceMode;
  connectStatus: string | null;
  connectPhase: "setup" | "waiting" | "signaling" | "reconnecting" | null;
  connected: boolean;
  listening: boolean;
  speaking: boolean;
  muted: boolean;
  activeTool: string | null;
  /** Gateway work before assistant output; idle when the gateway does not report any. */
  turnState: TurnState;
  transcript: TranscriptEntry[];
  micLevel: number;
  error: string | null;
  /** Available audio input devices (microphones). */
  audioInputs: AudioDevice[];
  /** Available audio output devices (speakers, headsets). */
  audioOutputs: AudioDevice[];
  /** Currently selected input device ID, empty string for system default. */
  selectedInputId: string;
  /** Currently selected output device ID, empty string for system default. */
  selectedOutputId: string;
  /** Saved BCP 47 speech language, independent of the browser locale. */
  languageTag: string;
}

const DEFAULT_VOICE_LANGUAGE = "en-US";

/** Validate a BCP 47 language tag and preserve its regional variant. */
export function normalizeVoiceLanguage(tag: string): string {
  const normalized = Intl.getCanonicalLocales(tag.trim())[0];
  if (!normalized || normalized === "und" || normalized.startsWith("und-")) {
    throw new Error("Specify a language tag, such as en-US");
  }
  return normalized;
}

function savedVoiceMode(): VoiceMode {
  try {
    return localStorage.getItem("gomode.voiceMode") === "browser" ? "browser" : "cloud";
  } catch (error) {
    console.warn("Could not load voice mode; using Cloud voice", error);
    return "cloud";
  }
}

function savedVoiceLanguage(): string {
  try {
    const saved = localStorage.getItem("gomode.voiceLanguage");
    return saved === null ? DEFAULT_VOICE_LANGUAGE : normalizeVoiceLanguage(saved);
  } catch (err) {
    console.warn("Could not load voice language; using en-US", err);
    return DEFAULT_VOICE_LANGUAGE;
  }
}

// VoiceSession

export class VoiceSession {
  readonly state: VoiceState;
  /** Public store updater (also the seam tests use to arrange session state). */
  readonly setState: (fn: (s: VoiceState) => VoiceState) => void;

  private _frontendToolNames = new Set<string>();
  private _pc: RTCPeerConnection | null = null;
  private _dc: RTCDataChannel | null = null;
  private _textSocket: WebSocket | null = null;
  private _browserSpeech: BrowserSpeech | null = null;
  private _browserReply = "";
  private _browserTurnIdle = false;
  private _rtcSessionID: string | null = null;
  private _rtcGatewaySnapshot: GatewaySnapshot | null = null;
  private _rtcOfferService: ServiceAuthorization | null = null;
  private _lastOfferSDP = "";
  private _lastAnswerSDP = "";
  private _audioContext: AudioContext | null = null;
  private _micStream: MediaStream | null = null;
  /** The <audio> element playing remote RTP audio, stored so we can call setSinkId(). */
  private _speakerAudio: HTMLAudioElement | null = null;
  /** True while the model is speaking — injected text is buffered and flushed after the turn ends. */
  private _speakerActive = false;
  /** Text notifications buffered while the model is speaking; flushed on turn end. */
  private _pendingNotifications: string[] = [];
  private _setupTimer: ReturnType<typeof setTimeout> | null = null;
  private _reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private _reconnectEnabled = false;
  private _reconnectAttempts = 0;
  private _recoveryContext = "";
  private _connectionAttempt = 0;
  private _voiceModeActive = false;
  private readonly _chime: VoiceChimePlayer;

  constructor(chime?: VoiceChimePlayer) {
    const [state, setState] = createStore<VoiceState>({
      mode: savedVoiceMode(),
      connectStatus: null,
      connectPhase: null,
      connected: false,
      listening: false,
      speaking: false,
      muted: false,
      activeTool: null,
      turnState: TurnStateIdle,
      transcript: [],
      micLevel: 0,
      error: null,
      audioInputs: [],
      audioOutputs: [],
      selectedInputId: "",
      selectedOutputId: "",
      languageTag: savedVoiceLanguage(),
    });
    this.state = state;
    this.setState = setState as (fn: (s: VoiceState) => VoiceState) => void;
    this._chime = chime ?? new WebAudioVoiceChime();
  }

  // -----------------------------------------------------------------------
  // Public API
  // -----------------------------------------------------------------------

  /** Persist the transport preference without disrupting an active session. */
  selectMode(mode: VoiceMode): void {
    if (mode !== "browser" && mode !== "cloud") throw new Error("Invalid voice mode");
    if (this.state.connected || this.state.connectStatus !== null)
      throw new Error("End the session before changing voice mode");
    if (mode === "browser" && !browserSpeechAvailable())
      throw new Error("Browser speech is unavailable. Use Cloud voice.");
    localStorage.setItem("gomode.voiceMode", mode);
    this.setState((s) => ({ ...s, mode }));
  }

  /** Save the language for the next session. An active session keeps its language. */
  selectLanguage(tag: string): void {
    if (this.state.connected || this.state.connectStatus !== null)
      throw new Error("End the session before changing language");
    const normalized = normalizeVoiceLanguage(tag);
    localStorage.setItem("gomode.voiceLanguage", normalized);
    this.setState((s) => ({ ...s, languageTag: normalized }));
  }

  /** Enumerate available audio devices and auto-select defaults. Call before connect(). */
  async enumerateDevices(): Promise<void> {
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      const inputs: AudioDevice[] = [];
      const outputs: AudioDevice[] = [];
      for (const d of devices) {
        if (d.kind === "audioinput") {
          inputs.push({
            deviceId: d.deviceId,
            kind: d.kind,
            label: d.label || "Microphone",
          });
        } else if (d.kind === "audiooutput") {
          outputs.push({
            deviceId: d.deviceId,
            kind: d.kind,
            label: d.label || "Speaker",
          });
        }
      }
      // Auto-select: first available, or keep current selection if still valid.
      const curSel = this.state.selectedInputId;
      const curOut = this.state.selectedOutputId;
      const newInId = curSel && inputs.some((d) => d.deviceId === curSel) ? curSel : (inputs[0]?.deviceId ?? "");
      const newOutId = curOut && outputs.some((d) => d.deviceId === curOut) ? curOut : (outputs[0]?.deviceId ?? "");
      this._update((s) => {
        s.audioInputs = inputs;
        s.audioOutputs = outputs;
        s.selectedInputId = newInId;
        s.selectedOutputId = newOutId;
      });
    } catch {
      // enumerateDevices() can fail if permissions are denied; ignore silently.
    }
  }

  /** Select an audio input device. If connected, replaces the mic track. */
  async selectInputDevice(deviceId: string): Promise<void> {
    this._update((s) => {
      s.selectedInputId = deviceId;
    });
    const pc = this._pc;
    if (pc && this._micStream) {
      try {
        // Stop old mic tracks.
        this._micStream.getTracks().forEach((t) => t.stop());
        // Acquire new mic stream with the selected device.
        const constraints: MediaStreamConstraints = {
          audio: deviceId ? { deviceId: { exact: deviceId } } : true,
          video: false,
        };
        const newStream = await navigator.mediaDevices.getUserMedia(constraints);
        if (this._pc !== pc) {
          newStream.getTracks().forEach((track) => track.stop());
          return;
        }
        this._micStream = newStream;
        // Replace tracks on the PeerConnection.
        const sender = pc.getSenders().find((s) => s.track?.kind === "audio");
        for (const t of newStream.getAudioTracks()) {
          if (sender) {
            await sender.replaceTrack(t);
            if (this._pc !== pc) return;
          } else {
            pc.addTrack(t, newStream);
          }
        }
        // Reconnect AnalyserNode if present.
        if (this._audioContext) {
          const analyser = this._audioContext.createAnalyser();
          analyser.fftSize = 256;
          const source = this._audioContext.createMediaStreamSource(newStream);
          source.connect(analyser);
          const buf = new Uint8Array(analyser.frequencyBinCount);
          const pollMicLevel = () => {
            if (this._pc !== pc) return;
            analyser.getByteTimeDomainData(buf);
            let sumSq = 0;
            for (const sample of buf) {
              const v = (sample - 128) / 128;
              sumSq += v * v;
            }
            const rms = Math.sqrt(sumSq / buf.length);
            if (!this.state.muted && !this._speakerActive) {
              this._update((s) => {
                s.micLevel = Math.min(1, Math.sqrt(rms));
              });
            }
            requestAnimationFrame(pollMicLevel);
          };
          requestAnimationFrame(pollMicLevel);
        }
      } catch {
        // If switching fails, leave the previous stream in place.
      }
    }
  }

  /** Select an audio output device. Applies immediately if connected. */
  selectOutputDevice(deviceId: string): void {
    this._update((s) => {
      s.selectedOutputId = deviceId;
    });
    if (this._speakerAudio && "setSinkId" in this._speakerAudio) {
      void (
        this._speakerAudio as HTMLAudioElement & {
          setSinkId: (id: string) => Promise<void>;
        }
      ).setSinkId(deviceId);
    }
  }

  /** Unlock chime playback while a browser user-activation event is still active. */
  prepareAudio(): void {
    if (this.state.mode === "browser" && !browserSpeechAvailable()) return;
    if (this.state.mode === "browser" && browserSpeechAvoidsChimes()) {
      window.speechSynthesis.resume();
      return;
    }
    this._chime.prepare();
  }

  /** Start a new voice session via WebRTC data channel through the host backend. */
  async connect(): Promise<void> {
    this.prepareAudio();
    this._reconnectEnabled = true;
    await this._connect(false);
  }

  private async _connect(preserveTranscript: boolean): Promise<void> {
    if (this.state.mode === "browser") {
      await this._connectBrowser(preserveTranscript);
      return;
    }
    const attempt = ++this._connectionAttempt;
    if (this._reconnectTimer !== null) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
    this._releaseAll();
    this._lastOfferSDP = "";
    this._lastAnswerSDP = "";
    this._audioContext = new AudioContext({ sampleRate: 16000 });
    if (!preserveTranscript) {
      this._reconnectAttempts = 0;
      this._recoveryContext = "";
      this._clearTranscript();
    }
    this._setStatus("setup", "Setting up WebRTC…");

    try {
      const [systemInstruction, mcpTools, serviceItemsText] = await Promise.all([
        mcpClient.serverInstructions(),
        mcpClient.listTools(),
        // Initial service context is advisory. A resource failure must not make
        // the independent voice transport unavailable.
        mcpClient.readAdvertisedTextResource(GO_MODE_ITEMS_RESOURCE_URI).catch(() => null),
      ]);
      if (attempt !== this._connectionAttempt) return;
      // This client owns the bounded session baseline and refreshes it on every
      // reconnect; see gomode/docs/ANDROID_SHELL.md#service-item-voice-context-ownership.
      const serviceContext = initialServiceContext(serviceItemsText);

      // Create PeerConnection.
      const pc = new RTCPeerConnection({
        iceServers: [{ urls: "stun:stun.l.google.com:19302" }],
      });
      this._pc = pc;

      // Mic audio → RTP track (browser handles Opus encoding + AEC).
      const inputId = this.state.selectedInputId;
      const micConstraints: MediaStreamConstraints = {
        audio: inputId ? { deviceId: { exact: inputId } } : true,
        video: false,
      };
      const micStream = await navigator.mediaDevices.getUserMedia(micConstraints);
      if (attempt !== this._connectionAttempt) {
        micStream.getTracks().forEach((track) => track.stop());
        return;
      }
      this._micStream = micStream;
      for (const t of micStream.getAudioTracks()) {
        t.enabled = !this.state.muted;
        pc.addTrack(t, micStream);
      }

      // Mic level via AnalyserNode (replaces AudioWorklet RMS in WebRTC mode).
      if (this._audioContext) {
        const analyser = this._audioContext.createAnalyser();
        analyser.fftSize = 256;
        const source = this._audioContext.createMediaStreamSource(micStream);
        source.connect(analyser);
        const buf = new Uint8Array(analyser.frequencyBinCount);
        const pollMicLevel = () => {
          if (!this._pc || this._pc !== pc) return;
          analyser.getByteTimeDomainData(buf);
          let sumSq = 0;
          for (const sample of buf) {
            const v = (sample - 128) / 128;
            sumSq += v * v;
          }
          const rms = Math.sqrt(sumSq / buf.length);
          if (!this.state.muted && !this._speakerActive) {
            this._update((s) => {
              s.micLevel = Math.min(1, Math.sqrt(rms));
            });
          }
          requestAnimationFrame(pollMicLevel);
        };
        requestAnimationFrame(pollMicLevel);
      }

      // Speaker audio from remote RTP track.
      const outId = this.state.selectedOutputId;
      pc.ontrack = (evt) => {
        if (attempt !== this._connectionAttempt || this._pc !== pc) return;
        const audio = new Audio();
        this._speakerAudio = audio;
        audio.srcObject = evt.streams[0] ?? new MediaStream([evt.track]);
        if (outId && "setSinkId" in audio) {
          void (
            audio as HTMLAudioElement & {
              setSinkId: (id: string) => Promise<void>;
            }
          ).setSinkId(outId);
        }
        audio.play().catch(() => {
          // Autoplay may be blocked; user interaction will resume.
        });
      };

      // Create data channel carrying the voice gateway control protocol.
      const dc = pc.createDataChannel("voice-gateway", { ordered: true });
      this._dc = dc;

      dc.onmessage = (evt: MessageEvent<string>) => {
        if (attempt !== this._connectionAttempt || this._dc !== dc) return;
        this._handleMessage(evt.data, attempt, dc).catch((err: unknown) => {
          if (attempt === this._connectionAttempt) {
            this._setError(err instanceof Error ? err.message : "Message handling failed");
          }
        });
      };

      dc.onopen = () => {
        if (attempt !== this._connectionAttempt || this._dc !== dc) return;
        this._reconnectAttempts = 0;
        this._setStatus("waiting", "Waiting for server…");
        this._sendSetup(mcpTools, systemInstruction, serviceContext);
      };

      dc.onclose = () => {
        if (this._dc !== dc) return;
        if (this.state.connected) {
          this._update((s) => {
            s.connected = false;
            s.listening = false;
            s.speaking = false;
          });
        }
      };

      pc.oniceconnectionstatechange = () => {
        const st = pc.iceConnectionState;
        if (st === "failed") {
          this._scheduleReconnect(pc, `WebRTC ICE ${st}`, 0);
        } else if (st === "disconnected") {
          this._scheduleReconnect(pc, `WebRTC ICE ${st}`, ICE_DISCONNECTED_GRACE_MS);
        } else if (st === "connected" && this._reconnectTimer !== null) {
          clearTimeout(this._reconnectTimer);
          this._reconnectTimer = null;
        } else if (st === "closed") {
          void this._setDiagnosticError(pc, `WebRTC ICE ${st}`);
        }
      };

      // SDP offer/answer exchange.
      this._setStatus("signaling", "Signaling…");
      const offer = await pc.createOffer();
      if (attempt !== this._connectionAttempt) return;
      const offerSDP = await completeLocalOffer(pc, offer);
      if (attempt !== this._connectionAttempt) return;
      this._lastOfferSDP = offerSDP;
      const snapshot = gatewaySnapshot();
      const service = snapshot.serviceProvider === null ? null : await snapshot.serviceProvider();
      if (attempt !== this._connectionAttempt) return;
      const request = service === null ? { sdp: offerSDP } : { sdp: offerSDP, service };
      const resp = await snapshot.api.voiceRTCOffer(request);
      if (attempt !== this._connectionAttempt) {
        void closeVoiceGatewaySession(snapshot, resp.sessionID, service).catch((error: unknown) =>
          console.warn("Could not close cancelled voice session", error),
        );
        return;
      }
      this._rtcSessionID = resp.sessionID;
      this._rtcGatewaySnapshot = snapshot;
      this._rtcOfferService = service;
      this._lastAnswerSDP = resp.sdp;
      await pc.setRemoteDescription({ type: "answer", sdp: resp.sdp });
      if (attempt !== this._connectionAttempt) return;
      this._startSetupTimer(pc);

      this._update((s) => {
        s.listening = true;
      });
    } catch (e: unknown) {
      if (attempt === this._connectionAttempt) {
        this._setError(e instanceof Error ? e.message : "WebRTC connection failed");
      }
    }
  }

  private async _connectBrowser(preserveTranscript: boolean): Promise<void> {
    const attempt = ++this._connectionAttempt;
    this._reconnectEnabled = false;
    if (this._reconnectTimer !== null) clearTimeout(this._reconnectTimer);
    if (this._setupTimer !== null) clearTimeout(this._setupTimer);
    this._reconnectTimer = null;
    this._setupTimer = null;
    this._releaseAll();
    this._speakerActive = false;
    this._pendingNotifications = [];
    if (!preserveTranscript) this._clearTranscript();
    this._update((s) => {
      s.connected = false;
      s.muted = false;
      s.speaking = false;
      s.activeTool = null;
      s.turnState = TurnStateIdle;
    });
    this._setStatus("setup", "Setting up browser speech…");
    if (!browserSpeechAvailable()) {
      this._setError("Browser speech is unavailable. Use Cloud voice.");
      return;
    }
    try {
      const snapshot = gatewaySnapshot();
      const [systemInstruction, tools, serviceItems, service] = await Promise.all([
        mcpClient.serverInstructions(),
        mcpClient.listTools(),
        mcpClient.readAdvertisedTextResource(GO_MODE_ITEMS_RESOURCE_URI).catch(() => null),
        snapshot.serviceProvider?.() ?? Promise.resolve(null),
      ]);
      if (attempt !== this._connectionAttempt) return;
      const response = await snapshot.api.voiceTextTicket(service === null ? {} : { service });
      if (attempt !== this._connectionAttempt) return;
      const url = new URL("/api/voicegateway/v1/voice/text/browser", snapshot.origin);
      url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
      const socket = new WebSocket(url, ["gomode.text.v1", `gomode.ticket.${response.ticket}`]);
      this._textSocket = socket;
      const speech = new BrowserSpeech(this.state.languageTag, {
        listening: (value) => {
          if (this._ownsChannel(attempt, socket))
            this._update((s) => {
              s.listening = value;
            });
        },
        interim: (text) => {
          if (this._ownsChannel(attempt, socket)) this._browserUserText(text, false);
        },
        final: (text) => {
          if (!this._ownsChannel(attempt, socket)) return;
          this._browserTurnIdle = false;
          this._speakerActive = true;
          this._browserUserText(text, true);
          this._send(JSON.stringify(gatewayUserMessage(text)));
        },
        error: (message) => {
          if (this._ownsChannel(attempt, socket)) this._setError(message);
        },
      });
      this._browserSpeech = speech;
      socket.onopen = () => {
        if (!this._ownsChannel(attempt, socket)) return;
        this._setStatus("waiting", "Waiting for server…");
        this._sendSetup(tools, systemInstruction, initialServiceContext(serviceItems));
      };
      socket.onmessage = (event: MessageEvent<unknown>) => {
        if (typeof event.data !== "string") return;
        void this._handleMessage(event.data, attempt, socket).catch((error: unknown) => {
          if (this._ownsChannel(attempt, socket))
            this._setError(error instanceof Error ? error.message : "Text voice message failed");
        });
      };
      socket.onerror = () => {
        if (this._ownsChannel(attempt, socket)) this._setError("Could not connect to the text voice gateway");
      };
      socket.onclose = (event) => {
        if (!this._ownsChannel(attempt, socket)) return;
        if (event.code === 1000) this.disconnect();
        else this._setError(`Text voice connection closed (${event.code})`);
      };
      this._setupTimer = setTimeout(() => {
        if (this._ownsChannel(attempt, socket) && !this.state.connected)
          this._setError("Text voice connection timed out");
      }, SETUP_TIMEOUT_MS);
    } catch (error) {
      if (attempt === this._connectionAttempt)
        this._setError(error instanceof Error ? error.message : "Text voice connection failed");
    }
  }

  private _browserUserText(text: string, final: boolean): void {
    this._update((s) => {
      const last = s.transcript.at(-1);
      const entries = last?.speaker === "user" && !last.final ? s.transcript.slice(0, -1) : s.transcript;
      s.transcript = text === "" ? entries : [...entries, { speaker: "user", text, final }];
    });
  }

  private _startBrowserListening(): void {
    if (this.state.connected && !this.state.muted && !this.state.speaking && this._browserTurnIdle)
      this._browserSpeech?.listen();
  }

  private _finishBrowserTurn(attempt: number, channel: VoiceChannel): void {
    this._browserTurnIdle = true;
    if (this.state.speaking) return;
    this._update((s) => {
      s.transcript = s.transcript.map((entry) => ({ ...entry, final: true }));
    });
    const text = this._browserReply;
    this._browserReply = "";
    if (text !== "" && this._browserSpeech !== null) {
      this._update((s) => {
        s.speaking = true;
      });
      this._browserSpeech.speak(text, () => {
        if (!this._ownsChannel(attempt, channel)) return;
        this._update((s) => {
          s.speaking = false;
        });
        if (this._browserTurnIdle) this._finishBrowserTurn(attempt, channel);
      });
    } else {
      this._speakerActive = false;
      this._flushPendingNotifications();
      this._startBrowserListening();
    }
  }

  disconnect(): void {
    this._leaveVoiceMode();
    this._connectionAttempt++;
    this._reconnectEnabled = false;
    if (this._reconnectTimer !== null) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
    if (this._setupTimer !== null) {
      clearTimeout(this._setupTimer);
      this._setupTimer = null;
    }
    this._releaseAll();
    this._speakerAudio = null;
    // Preserve transcript for review; mark all entries final.
    this._update((s) => {
      s.connected = false;
      s.listening = false;
      s.speaking = false;
      s.muted = false;
      s.connectStatus = null;
      s.connectPhase = null;
      s.activeTool = null;
      s.turnState = TurnStateIdle;
      s.micLevel = 0;
      s.transcript = s.transcript.map((e) => ({ ...e, final: true }));
    });
  }

  toggleMute(): void {
    this._update((s) => {
      s.muted = !s.muted;
    });
    if (this._browserSpeech !== null) {
      if (this.state.muted) this._browserSpeech.stopListening();
      else this._startBrowserListening();
    }
    if (this._micStream) {
      const enabled = !this.state.muted;
      this._micStream.getAudioTracks().forEach((t) => {
        t.enabled = enabled;
      });
    }
  }

  injectText(text: string): void {
    if (this._speakerActive) {
      this._pendingNotifications.push(text);
      return;
    }
    this._send(JSON.stringify(gatewayContextUpdate(text)));
  }

  private _flushPendingNotifications(): void {
    if (this._pendingNotifications.length === 0) return;
    const text = this._pendingNotifications.join("\n");
    this._pendingNotifications = [];
    this.injectText(text);
  }

  clearTranscript(): void {
    this._clearTranscript();
  }

  // -----------------------------------------------------------------------
  // Private helpers
  // -----------------------------------------------------------------------

  /** Send a message via the WebRTC data channel. */
  private _send(msg: string): void {
    const channel = this._textSocket ?? this._dc;
    if (channel !== null && voiceChannelOpen(channel)) channel.send(msg);
  }

  /** Release WebRTC transport and audio resources. */
  private _releaseAll(): void {
    const socket = this._textSocket;
    const speech = this._browserSpeech;
    this._textSocket = null;
    this._browserSpeech = null;
    this._browserReply = "";
    this._browserTurnIdle = false;
    speech?.close();
    socket?.close(1000);
    const sessionID = this._rtcSessionID;
    const snapshot = this._rtcGatewaySnapshot;
    const service = this._rtcOfferService;
    this._rtcSessionID = null;
    this._rtcGatewaySnapshot = null;
    this._rtcOfferService = null;
    this._dc?.close();
    this._dc = null;
    this._pc?.close();
    this._pc = null;
    this._releaseAudio();
    if (sessionID !== null && snapshot !== null) {
      void closeVoiceGatewaySession(snapshot, sessionID, service).catch((error: unknown) => {
        console.warn("Could not close voice gateway session", error);
      });
    }
  }

  private _setStatus(phase: Exclude<VoiceState["connectPhase"], null>, status: string): void {
    this._update((s) => {
      s.connectStatus = status;
      s.connectPhase = phase;
      s.error = null;
    });
  }

  private _scheduleReconnect(pc: RTCPeerConnection, reason: string, delay: number): void {
    if (this._pc !== pc || !this._reconnectEnabled) return;
    if (this._reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      this._setError(`Voice connection lost after ${MAX_RECONNECT_ATTEMPTS} recovery attempts`);
      return;
    }
    if (this._reconnectTimer !== null) {
      if (delay !== 0) return;
      clearTimeout(this._reconnectTimer);
    }
    this._setStatus("reconnecting", `${reason}; reconnecting…`);
    this._reconnectTimer = setTimeout(() => {
      this._reconnectTimer = null;
      if (!this._reconnectEnabled) return;
      this._reconnectAttempts++;
      this._speakerActive = false;
      this._update((s) => {
        s.speaking = false;
        s.activeTool = null;
        s.turnState = TurnStateIdle;
      });
      this._recoveryContext = buildRecoveryContext(this.state.transcript);
      void this._connect(true);
    }, delay);
  }

  private _startSetupTimer(pc: RTCPeerConnection): void {
    if (this._setupTimer !== null) clearTimeout(this._setupTimer);
    this._setupTimer = setTimeout(() => {
      if (this._pc === pc && !this.state.connected && !this.state.error) {
        void this._setConnectionTimeoutError(pc);
      }
    }, SETUP_TIMEOUT_MS);
  }

  private async _setConnectionTimeoutError(pc: RTCPeerConnection): Promise<void> {
    await this._setDiagnosticError(pc, "Connection timed out — server did not respond");
  }

  private async _setDiagnosticError(pc: RTCPeerConnection, fallback: string): Promise<void> {
    if (this._pc !== pc || this.state.error) {
      return;
    }
    const sessionID = this._rtcSessionID;
    const snapshot = this._rtcGatewaySnapshot;
    if (!sessionID || snapshot === null) {
      this._setError(fallback);
      return;
    }
    try {
      const token = await refreshedServiceToken(snapshot, this._rtcOfferService);
      if (this._pc !== pc) return;
      const diagnostics = await snapshot.api.diagnoseVoiceRTC(
        sessionID,
        { client: this._clientDiagnostics(pc) },
        token === null ? {} : { Authorization: `Bearer ${token}` },
      );
      if (this._pc !== pc) return;
      logVoiceRTCDiagnostics(diagnostics, this._lastOfferSDP, this._lastAnswerSDP);
      if (this._pc === pc && !this.state.connected && !this.state.error) {
        this._setError(formatVoiceRTCDiagnostics(diagnostics));
      }
    } catch (e: unknown) {
      const detail = e instanceof Error ? ` (${e.message})` : "";
      if (this._pc === pc && !this.state.connected && !this.state.error) {
        this._setError(`${fallback}${detail}`);
      }
    }
  }

  private _clientDiagnostics(pc: RTCPeerConnection): VoiceRTCClientDiagnostics {
    return {
      iceConnectionState: pc.iceConnectionState,
      iceGatheringState: pc.iceGatheringState,
      connectionState: pc.connectionState,
      signalingState: pc.signalingState,
      dataChannelState: this._dc?.readyState,
    };
  }

  private _setError(message: string): void {
    this._leaveVoiceMode();
    this._connectionAttempt++;
    this._releaseAll();
    if (this._setupTimer !== null) clearTimeout(this._setupTimer);
    this._setupTimer = null;
    this._update((s) => {
      s.connectStatus = null;
      s.connectPhase = null;
      s.connected = false;
      s.listening = false;
      s.speaking = false;
      s.error = message;
    });
  }

  private _leaveVoiceMode(): void {
    if (!this._voiceModeActive) return;
    this._voiceModeActive = false;
    if (this.state.mode !== "browser" || !browserSpeechAvoidsChimes()) this._chime.playDisconnected();
  }

  private _clearTranscript(): void {
    this._update((s) => {
      s.transcript = [];
    });
  }

  private _update(fn: (s: VoiceState) => void): void {
    this.setState(produce(fn));
  }

  // -----------------------------------------------------------------------
  // Gateway setup message
  // -----------------------------------------------------------------------

  private _sendSetup(tools: McpToolDescriptor[], systemInstruction: string, serviceContext: string): void {
    this._frontendToolNames = new Set(frontendVoiceToolDeclarations().map((tool) => tool.name));
    const setup = gatewaySessionSetup(
      voiceToolDeclarations(tools),
      systemInstruction,
      serviceContext,
      this.state.languageTag,
    );
    this._send(JSON.stringify(setup));
  }

  // -----------------------------------------------------------------------
  // Message handling
  // -----------------------------------------------------------------------

  private _ownsChannel(attempt: number, channel: VoiceChannel): boolean {
    return attempt === this._connectionAttempt && (channel === this._dc || channel === this._textSocket);
  }

  private async _handleMessage(text: string, attempt: number, channel: VoiceChannel): Promise<void> {
    if (!this._ownsChannel(attempt, channel)) return;
    let env: MessageEnvelope;
    try {
      env = JSON.parse(text) as MessageEnvelope;
    } catch {
      return;
    }

    if (env.kind === MessageKindSessionReady) {
      if (this._setupTimer !== null) {
        clearTimeout(this._setupTimer);
        this._setupTimer = null;
      }
      this._update((s) => {
        s.connectStatus = null;
        s.connectPhase = null;
        s.connected = true;
        s.error = null;
      });
      if (!this._voiceModeActive) {
        this._voiceModeActive = true;
        if (this.state.mode !== "browser" || !browserSpeechAvoidsChimes()) this._chime.playConnected();
      }
      if (this._recoveryContext !== "") {
        this._send(JSON.stringify(gatewayContextUpdate(this._recoveryContext)));
        this._recoveryContext = "";
      }
      this._flushPendingNotifications();
      this._send(JSON.stringify(gatewayUserMessage("Say exactly one word: Ready")));
      // Audio capture is handled via WebRTC RTP tracks; no separate audio setup needed.
      return;
    }

    if (env.kind === MessageKindTranscriptDelta) {
      const delta = JSON.parse(text) as TranscriptDelta;
      // Browser recognition already owns the user's interim and final text.
      if (this.state.mode !== "browser" || delta.speaker !== "user") this._handleTranscriptDelta(delta);
      return;
    }

    if (env.kind === MessageKindAssistantTextDelta && this.state.mode === "browser") {
      const delta = JSON.parse(text) as AssistantTextDelta;
      this._browserSpeech?.stopListening();
      this._browserTurnIdle = false;
      this._speakerActive = true;
      this._browserReply += delta.text;
      this._update((s) => {
        s.transcript = appendChunk(s.transcript, "assistant", delta.text);
      });
      return;
    }

    if (env.kind === MessageKindSpeechStarted) {
      this._speakerActive = true;
      this._update((s) => {
        s.speaking = true;
      });
      return;
    }

    if (env.kind === MessageKindSpeechEnded) {
      this._speakerActive = false;
      this._flushPendingNotifications();
      this._update((s) => {
        s.speaking = false;
        s.transcript = s.transcript.map((e) => ({ ...e, final: true }));
      });
      return;
    }

    if (env.kind === MessageKindInterrupted) {
      this._speakerActive = false;
      this._flushPendingNotifications();
      this._update((s) => {
        s.speaking = false;
        s.activeTool = null;
      });
      return;
    }

    if (env.kind === MessageKindTurnStatus) {
      const msg = JSON.parse(text) as TurnStatus;
      this._update((s) => {
        s.turnState = msg.state;
      });
      if (this.state.mode === "browser") {
        if (msg.state === TurnStateIdle) this._finishBrowserTurn(attempt, channel);
        else {
          this._browserTurnIdle = false;
          this._browserSpeech?.stopListening();
        }
      }
      return;
    }

    if (env.kind === MessageKindToolCall) {
      await this._handleToolCall(JSON.parse(text) as ToolCall, attempt, channel);
      return;
    }

    if (env.kind === MessageKindError) {
      const msg = JSON.parse(text) as Error;
      if (msg.recoverable) {
        // Keep the connection and outstanding tool deliveries alive. Diagnostics
        // use the same transcript presentation as tool errors, not fatal state.
        this._update((s) => {
          s.transcript = [...s.transcript, { speaker: "assistant", text: `[Voice] ${msg.message}`, final: true }];
        });
        return;
      }
      this._setError(msg.message);
    }
  }

  private _handleTranscriptDelta(msg: TranscriptDelta): void {
    if (msg.speaker !== "user" && msg.speaker !== "assistant") return;
    if (!msg.text) return;
    this._update((s) => {
      s.transcript = appendChunk(s.transcript, msg.speaker, msg.text ?? "");
    });
  }

  private async _handleToolCall(msg: ToolCall, attempt: number, channel: VoiceChannel): Promise<void> {
    if (!this._ownsChannel(attempt, channel)) return;
    if (!msg.id || !msg.name) return;
    if (msg.name === HANG_UP_TOOL_NAME) {
      this.disconnect();
      return;
    }

    try {
      this._update((s) => {
        s.activeTool = msg.name ?? null;
      });
      const result = this._frontendToolNames.has(msg.name)
        ? { structuredContent: executeFrontendVoiceTool(msg.name, msg.args ?? {}), isError: false }
        : await mcpClient.callTool(msg.name, msg.args ?? {});
      if (!this._ownsChannel(attempt, channel)) return;
      this._update((s) => {
        s.activeTool = null;
      });

      // Surface tool errors in the transcript.
      if (result.isError) {
        const errMsg =
          typeof result.structuredContent["error"] === "string" ? result.structuredContent["error"] : "Tool error";
        this._update((s) => {
          s.transcript = [
            ...s.transcript,
            {
              speaker: "assistant" as TranscriptSpeaker,
              text: `[${msg.name}] ${errMsg}`,
              final: true,
            },
          ];
        });
      }

      this._sendToolResult(channel, msg.id, msg.name, result.structuredContent);
    } catch (e: unknown) {
      if (!this._ownsChannel(attempt, channel)) return;
      this._update((s) => {
        s.activeTool = null;
      });
      const errMsg = e instanceof Error ? e.message : "Unknown error";
      this._update((s) => {
        s.transcript = [
          ...s.transcript,
          {
            speaker: "assistant" as TranscriptSpeaker,
            text: `[${msg.name}] ${errMsg}`,
            final: true,
          },
        ];
      });
      this._sendToolResult(channel, msg.id, msg.name, { error: errMsg });
    }
  }

  private _sendToolResult(channel: VoiceChannel, id: string, name: string, result: Record<string, unknown>): void {
    if (voiceChannelOpen(channel)) channel.send(JSON.stringify(gatewayToolResult(id, name, result)));
  }

  // -----------------------------------------------------------------------
  // Audio cleanup
  // -----------------------------------------------------------------------

  private _releaseAudio(): void {
    try {
      this._micStream?.getTracks().forEach((t) => t.stop());
      this._micStream = null;
    } catch {
      // ignore
    }
    try {
      void this._audioContext?.close();
      this._audioContext = null;
    } catch {
      // ignore
    }
    this._speakerActive = false;
    this._pendingNotifications = [];
  }
}

function completeLocalOffer(pc: RTCPeerConnection, offer: RTCSessionDescriptionInit): Promise<string> {
  return pc.setLocalDescription(offer).then(async () => {
    await waitForUsableICECandidate(pc);
    const sdp = pc.localDescription?.sdp;
    if (!sdp) {
      throw new Error("WebRTC local offer SDP unavailable after ICE gathering");
    }
    return sdp;
  });
}

// waitForUsableICECandidate does not wait for every configured STUN server.
// A slow or unreachable STUN request must not delay LAN or Tailscale signaling.
function waitForUsableICECandidate(pc: RTCPeerConnection): Promise<void> {
  return new Promise((resolve, reject) => {
    let hostCandidateGrace: number | null = null;
    const timeout = window.setTimeout(() => {
      cleanup();
      reject(new Error("Timed out waiting for a usable WebRTC ICE candidate"));
    }, ICE_GATHERING_TIMEOUT_MS);
    const cleanup = () => {
      window.clearTimeout(timeout);
      if (hostCandidateGrace !== null) window.clearTimeout(hostCandidateGrace);
      pc.removeEventListener("icecandidate", onICECandidate);
      pc.removeEventListener("icegatheringstatechange", onGatheringStateChange);
      pc.removeEventListener("connectionstatechange", onConnectionStateChange);
    };
    const resolveIfUsable = (): boolean => {
      if (!localDescriptionHasUsableICECandidate(pc)) return false;
      cleanup();
      resolve();
      return true;
    };
    const onICECandidate = (event: Event) => {
      const candidate = (event as Event & { candidate: RTCIceCandidate | null }).candidate;
      if (candidate === null || !isUsableICECandidate(candidate.candidate)) return;
      if (iceCandidateType(candidate.candidate) === "srflx") {
        cleanup();
        resolve();
        return;
      }
      if (hostCandidateGrace !== null) return;
      hostCandidateGrace = window.setTimeout(resolveIfUsable, ICE_HOST_CANDIDATE_GRACE_MS);
    };
    const onGatheringStateChange = () => {
      if (resolveIfUsable() || pc.iceGatheringState !== "complete") return;
      cleanup();
      reject(new Error("WebRTC ICE gathering completed without a usable candidate"));
    };
    const onConnectionStateChange = () => {
      if (pc.connectionState !== "closed" && pc.connectionState !== "failed") return;
      cleanup();
      reject(new Error(`WebRTC connection ${pc.connectionState} before ICE candidate gathering completed`));
    };
    pc.addEventListener("icecandidate", onICECandidate);
    pc.addEventListener("icegatheringstatechange", onGatheringStateChange);
    pc.addEventListener("connectionstatechange", onConnectionStateChange);
    if (pc.iceGatheringState === "complete" || localDescriptionHasReflexiveICECandidate(pc)) {
      resolveIfUsable();
    } else if (localDescriptionHasUsableICECandidate(pc)) {
      hostCandidateGrace = window.setTimeout(resolveIfUsable, ICE_HOST_CANDIDATE_GRACE_MS);
    }
  });
}

function localDescriptionHasUsableICECandidate(pc: RTCPeerConnection): boolean {
  return pc.localDescription?.sdp.split("\n").some(isUsableICECandidate) ?? false;
}

function localDescriptionHasReflexiveICECandidate(pc: RTCPeerConnection): boolean {
  return (
    pc.localDescription?.sdp.split("\n").some((candidate) => {
      return isUsableICECandidate(candidate) && iceCandidateType(candidate) === "srflx";
    }) ?? false
  );
}

function isUsableICECandidate(candidate: string): boolean {
  const fields = candidate.trim().split(/\s+/);
  return fields.length >= 8 && fields[2] === "udp" && isUsableIPv4(fields[4]);
}

function iceCandidateType(candidate: string): string | undefined {
  return candidate.trim().split(/\s+/)[7];
}

function isUsableIPv4(address: string | undefined): boolean {
  if (address === undefined || address.startsWith("169.254.")) return false;
  const octets = address.split(".");
  return octets.length === 4 && octets.every((octet) => /^\d{1,3}$/.test(octet) && Number(octet) <= 255);
}

function logVoiceRTCDiagnostics(diagnostics: VoiceRTCDiagnosticsResp, offerSDP: string, answerSDP: string): void {
  console.warn("Voice RTC diagnostics", {
    issue: diagnostics.issue,
    side: diagnostics.side,
    message: diagnostics.message,
    server: diagnostics.server,
    client: diagnostics.client,
    clientOfferCandidates: summarizeSDPCandidates(offerSDP),
    serverAnswerCandidates: summarizeSDPCandidates(answerSDP),
  });
}

export function summarizeSDPCandidates(sdp: string): string {
  const candidates: string[] = [];
  for (const line of sdp.split("\n")) {
    const body = line.trim();
    if (!body.startsWith("a=candidate:")) continue;
    const fields = body.split(/\s+/);
    if (fields.length < 8) continue;
    candidates.push(`${fields[4]}:${fields[5]} ${fields[7]}`);
  }
  return candidates.length === 0 ? "none" : candidates.join(", ");
}

export function formatVoiceRTCDiagnostics(diagnostics: VoiceRTCDiagnosticsResp): string {
  const side = diagnostics.side === "none" ? "unknown" : diagnostics.side;
  const mappingError = diagnostics.server.udpMappingError ? ` UDP mapping: ${diagnostics.server.udpMappingError}` : "";
  return `Voice connection failed (${side}: ${diagnostics.issue}) — ${diagnostics.message}${mappingError}`;
}

/** Build a bounded recovery-only context without replaying unfinished transcript deltas. */
export function buildRecoveryContext(transcript: TranscriptEntry[]): string {
  const prefix = "Network recovery context. Continue the existing conversation; do not treat this as a new user turn.";
  const availableTranscriptChars = MAX_RECOVERY_CONTEXT_CHARS - prefix.length - 24;
  const lines: string[] = [];
  let lineChars = 0;
  for (const entry of [...transcript].reverse()) {
    if (!entry.final || entry.text.trim() === "") continue;
    const line = `${entry.speaker}: ${entry.text.trim()}`;
    if (lineChars + line.length + (lines.length === 0 ? 0 : 1) > availableTranscriptChars) break;
    lines.unshift(line);
    lineChars += line.length + (lines.length === 1 ? 0 : 1);
  }
  const transcriptSection = lines.length === 0 ? "" : `\nFinalized transcript:\n${lines.join("\n")}`;
  return `${prefix}${transcriptSection}`;
}

function gatewayContextUpdate(text: string): ContextUpdate {
  return {
    kind: MessageKindContextUpdate,
    context: { text },
  };
}

function gatewayUserMessage(text: string): UserMessage {
  return {
    kind: MessageKindUserMessage,
    text,
  };
}

function gatewaySessionSetup(
  tools: SessionSetup["tools"],
  systemInstruction: string,
  serviceContext: string,
  languageTag: string,
): SessionSetup {
  return {
    kind: MessageKindSessionSetup,
    voice: {
      name: "Orus",
      language: languageTag,
    },
    tools,
    context: {
      systemInstruction: combineSystemInstructions(systemInstruction),
      text: serviceContext,
    },
  };
}

function combineSystemInstructions(hostInstruction: string): string {
  const host = hostInstruction.trim();
  return host === "" ? GO_MODE_SYSTEM_INSTRUCTION : `${GO_MODE_SYSTEM_INSTRUCTION}\n\n${host}`;
}

function gatewayToolResult(id: string, name: string, result: Record<string, unknown>): ToolResult {
  return {
    kind: MessageKindToolResult,
    id,
    name,
    result,
  };
}

// Transcript helpers

function appendChunk(transcript: TranscriptEntry[], speaker: TranscriptSpeaker, text: string): TranscriptEntry[] {
  const last = transcript[transcript.length - 1];
  if (last && last.speaker === speaker && !last.final) {
    return [...transcript.slice(0, -1), { speaker, text: last.text + text, final: false }];
  }
  return [...transcript, { speaker, text, final: false }];
}

// Singleton — lives at module level so the WebRTC connection survives component remounts.
export const voiceSession = new VoiceSession();

// Disconnect on actual page unload (browser close/refresh).
if (typeof window !== "undefined") {
  window.addEventListener("beforeunload", () => {
    voiceSession.disconnect();
  });
}
