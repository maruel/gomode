// Browser voice sessions via WebRTC and speech, dispatching MCP and frontend tools.

import { createSignal, untrack } from "solid-js";
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
  TurnStateThinking,
  type ServiceAuthorization,
  type VoiceChoice,
  type VoiceChoicesResp,
} from "../../sdk/voicegateway/ts/v1/types.gen";

import { mcpClient, type McpToolDescriptor } from "./McpClient";
import { executeFrontendVoiceTool, frontendVoiceToolDeclarations } from "./FrontendVoiceTools";
import { GO_MODE_ITEMS_RESOURCE_URI, initialServiceContext } from "./ServiceItems";
import { WebAudioVoiceChime, type VoiceChimePlayer } from "./VoiceChime";
import { WebAudioThinkingEarcon, type ThinkingEarcon } from "./ThinkingEarcon";
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
  "Use the service MCP tools whenever they are useful after the user asks. " +
  "Host instructions define which service item updates require a spoken notification and how to respond. " +
  "Follow those instructions even when an update arrives only as context and the user has not asked for a response. " +
  "Host-required notifications and any tools needed to prepare them take priority over instructions to stay silent, " +
  "wait for a user request, or reply only to the current request. " +
  "Otherwise, apart from the initial greeting, do not speak or invoke tools until the user asks, and do not " +
  "verbally acknowledge or confirm receipt of service item updates.";
/** Conservative data-channel/model-safe bound for a recovery context update. */
export const MAX_RECOVERY_CONTEXT_CHARS = 8000;

let gatewayBaseURL: string | null = null;
const [gatewayVersion, setGatewayVersion] = createSignal(0);
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
  setGatewayVersion((version) => version + 1);
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
  let response: Response;
  try {
    response = await fetch(new URL(path, origin).toString(), { ...init, headers });
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    const message =
      error instanceof DOMException && error.name === "TimeoutError"
        ? "Voice connection timed out."
        : "Voice gateway unreachable.";
    throw new globalThis.Error(message, { cause: error });
  }
  const message = gatewayHTTPError(response.status, path);
  if (message !== null) {
    await response.body?.cancel();
    throw new globalThis.Error(message);
  }
  return response;
}

// Classify gateway HTTP failures before the SDK tries to decode a proxy's empty
// or HTML response as JSON. Keep application validation errors with the SDK.
function gatewayHTTPError(status: number, path: string): string | null {
  switch (status) {
    case 401:
      return `Voice authorization failed (HTTP ${status}).`;
    case 403:
      return "Voice access denied (HTTP 403).";
    case 404:
      return path.startsWith("/api/voicegateway/v1/voice/rtc/") && !path.endsWith("/rtc/offer")
        ? "Voice session not found (HTTP 404)."
        : "Voice gateway not found (HTTP 404).";
    case 408:
    case 504:
      return `Voice connection timed out (HTTP ${status}).`;
    case 429:
      return "Voice gateway busy (HTTP 429).";
    default:
      return status >= 500 ? `Voice gateway unavailable (HTTP ${status}).` : null;
  }
}

// Late-bound API for callers outside VoiceSession; session operations use a pinned client.
export const voiceGatewayApi = voicegatewaySDK.createApiClient((path, init) =>
  gatewayFetch(gatewayBaseURL ?? window.location.origin, gatewayBearerTokenProvider, path, init),
);

interface GatewaySnapshot {
  version: number;
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
    version: untrack(gatewayVersion),
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
  choices: VoiceChoice[];
  choiceID: string | null;
  choicesStatus: "idle" | "loading" | "ready" | "error";
  choicesError: string | null;
  voiceName: string | null;
  voiceError: string | null;
  voiceStatus: "idle" | "ready" | "error";
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
  audioDevicesError: string | null;
  audioInputError: string | null;
  audioOutputError: string | null;
  /** The route being changed, or null when no switch is pending. */
  audioSwitching: "input" | "output" | null;
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

function choiceStorageKey(origin: string): string {
  return `gomode.voiceChoice.${origin}`;
}

function savedVoiceChoice(origin: string): string | null {
  return localStorage.getItem(choiceStorageKey(origin));
}

function compatibleChoices(choices: VoiceChoice[], mode: VoiceMode): VoiceChoice[] {
  return choices.filter((choice) => choice.transports.includes(mode === "browser" ? "text" : "webrtc"));
}

function validVoiceCatalog(choice: VoiceChoice): boolean {
  if (choice.voices === undefined) return choice.defaultVoice === undefined || choice.defaultVoice === "";
  if (!Array.isArray(choice.voices)) return false;
  if (choice.voices.length === 0) return choice.defaultVoice === undefined || choice.defaultVoice === "";
  return (
    choice.voices.every((name) => typeof name === "string" && name.trim() !== "") &&
    new Set(choice.voices).size === choice.voices.length &&
    typeof choice.defaultVoice === "string" &&
    choice.voices.includes(choice.defaultVoice)
  );
}

function speakingVoiceStorageKey(origin: string, choiceID: string): string {
  return `gomode.speakingVoice.${JSON.stringify([origin, choiceID])}`;
}

function savedSpeakingVoice(origin: string, choice: VoiceChoice, mode: VoiceMode): string {
  if (mode !== "cloud" || !choice.voices?.length) return "";
  return localStorage.getItem(speakingVoiceStorageKey(origin, choice.id)) ?? choice.defaultVoice!;
}

function speakingVoiceError(choice: VoiceChoice, name: string): string | null {
  const available = choice.voices?.length ? choice.voices.includes(name) : name === "";
  return available ? null : "The saved speaking voice is unavailable. Choose another speaking voice.";
}

function validateVoiceChoices(value: VoiceChoicesResp): VoiceChoicesResp {
  if (
    !Array.isArray(value.choices) ||
    typeof value.defaultChoiceID !== "string" ||
    value.choices.some(
      (choice) =>
        typeof choice.id !== "string" ||
        choice.id === "" ||
        typeof choice.label !== "string" ||
        choice.label === "" ||
        !Array.isArray(choice.transports) ||
        choice.transports.length === 0 ||
        choice.transports.some((transport) => transport !== "text" && transport !== "webrtc") ||
        !validVoiceCatalog(choice),
    ) ||
    new Set(value.choices.map((choice) => choice.id)).size !== value.choices.length ||
    !value.choices.some((choice) => choice.id === value.defaultChoiceID)
  )
    throw new Error("The voice gateway returned invalid choices");
  return value;
}

function resolveVoiceChoice(metadata: VoiceChoicesResp, mode: VoiceMode, selected: string | null): string {
  const compatible = compatibleChoices(metadata.choices, mode);
  if (selected !== null) {
    if (!metadata.choices.some((choice) => choice.id === selected))
      throw new Error("The selected voice choice is unavailable. Choose another voice choice.");
    if (compatible.some((choice) => choice.id === selected)) return selected;
  }
  const choice = compatible.find((choice) => choice.id === metadata.defaultChoiceID) ?? compatible[0];
  if (!choice) throw new Error("The voice gateway has no choices for this voice mode");
  return choice.id;
}

// bluetoothAudioDeviceLabel recognizes explicit Bluetooth and common AirPods labels.
// Browsers do not expose audio transport types.
export function bluetoothAudioDeviceLabel(label: string): boolean {
  return /\b(bluetooth|airpods|bt)\b/i.test(label);
}

// Opaque Bluetooth device names are restored by their saved device ID.
function bluetoothAudioDevice(device: AudioDevice, rememberedId: string | null): boolean {
  return (
    device.deviceId !== "default" &&
    device.deviceId !== "communications" &&
    (device.deviceId === rememberedId || bluetoothAudioDeviceLabel(device.label))
  );
}

type AudioDevicePreference = "BluetoothInput" | "BluetoothOutput" | "Input" | "Output";

function savedAudioDevice(kind: AudioDevicePreference): string | null {
  try {
    return localStorage.getItem(`gomode.voice${kind}Device`);
  } catch (error) {
    console.warn("Could not load voice audio device", error);
    return null;
  }
}

function rememberAudioDevice(kind: AudioDevicePreference, deviceId: string): void {
  try {
    if (deviceId) localStorage.setItem(`gomode.voice${kind}Device`, deviceId);
    else localStorage.removeItem(`gomode.voice${kind}Device`);
  } catch (error) {
    console.warn("Could not save voice audio device", error);
  }
}

// VoiceSession

function newConversationID(): string {
  // getRandomValues also works in HTTP-hosted native shells.
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export class VoiceSession {
  readonly state: VoiceState;
  /** Public store updater (also the seam tests use to arrange session state). */
  readonly setState: (fn: (s: VoiceState) => VoiceState) => void;

  private _choicesVersion = -1;
  private _choicesRequest: { version: number; promise: Promise<void> } | null = null;
  private _choicesAttempt = 0;
  private _sessionGateway: GatewaySnapshot | null = null;
  private _sessionChoiceID: string | null = null;
  private _sessionVoiceName: string | null = null;
  private _conversationID = newConversationID();
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
  private _micMonitorFrame: number | null = null;
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
  private _audioSelectionAttempt = 0;
  private _deviceEnumerationAttempt = 0;
  private _pendingAudioInput: string | null = null;
  private _pendingAudioOutput: string | null = null;
  private _inputPreferenceProvisional = false;
  private _outputPreferenceProvisional = false;
  private _voiceModeActive = false;
  private readonly _chime: VoiceChimePlayer;
  private readonly _thinkingEarcon: ThinkingEarcon;

  constructor(chime?: VoiceChimePlayer, thinkingEarcon?: ThinkingEarcon) {
    const [state, setState] = createStore<VoiceState>({
      mode: savedVoiceMode(),
      choices: [],
      choiceID: null,
      choicesStatus: "idle",
      choicesError: null,
      voiceName: null,
      voiceError: null,
      voiceStatus: "idle",
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
      audioDevicesError: null,
      audioInputError: null,
      audioOutputError: null,
      audioSwitching: null,
      audioOutputs: [],
      selectedInputId: "",
      selectedOutputId: "",
      languageTag: savedVoiceLanguage(),
    });
    this.state = state;
    this.setState = setState as (fn: (s: VoiceState) => VoiceState) => void;
    this._chime = chime ?? new WebAudioVoiceChime();
    this._thinkingEarcon = thinkingEarcon ?? new WebAudioThinkingEarcon();
  }

  // -----------------------------------------------------------------------
  // Public API
  // -----------------------------------------------------------------------

  /** Reactive gateway identity for settings; configuring a host does not start network requests. */
  readonly gatewayIdentity = gatewayVersion;

  get compatibleChoices(): VoiceChoice[] {
    return compatibleChoices(this.state.choices, this.state.mode);
  }

  get speakingVoices(): string[] {
    if (this.state.mode !== "cloud") return [];
    return this.state.choices.find((choice) => choice.id === this.state.choiceID)?.voices ?? [];
  }

  private _restoreSpeakingVoice(origin: string, choice: VoiceChoice): void {
    try {
      const name = savedSpeakingVoice(origin, choice, this.state.mode);
      this._update((s) => {
        s.voiceName = name;
        s.voiceError = this.state.mode === "cloud" ? speakingVoiceError(choice, name) : null;
        s.voiceStatus = "ready";
      });
    } catch (error) {
      this._update((s) => {
        s.voiceName = null;
        s.voiceError = error instanceof Error ? error.message : String(error);
        s.voiceStatus = "error";
      });
    }
  }

  /** Save a supported speaking voice for this gateway and backend choice. */
  selectSpeakingVoice(name: string): void {
    if (this.state.connected || this.state.connectStatus !== null)
      throw new Error("End the session before changing speaking voice");
    if (
      this._choicesVersion !== untrack(gatewayVersion) ||
      this.state.choicesStatus !== "ready" ||
      this.state.voiceStatus !== "ready" ||
      !this.speakingVoices.includes(name)
    )
      throw new Error("Choose an available speaking voice");
    const choiceID = this.state.choiceID!;
    localStorage.setItem(speakingVoiceStorageKey(gatewaySnapshot().origin, choiceID), name);
    this._update((s) => {
      s.voiceName = name;
      s.voiceError = null;
    });
  }

  /** Discover settings choices on demand, keeping obsolete responses out of the current gateway. */
  async loadVoiceChoices(): Promise<void> {
    const snapshot = gatewaySnapshot();
    if (this._choicesRequest?.version === snapshot.version) return this._choicesRequest.promise;
    const attempt = ++this._choicesAttempt;
    const promise = this._loadVoiceChoices(snapshot, attempt);
    this._choicesRequest = { version: snapshot.version, promise };
    await promise;
    if (this._choicesRequest?.promise === promise) this._choicesRequest = null;
  }

  private async _loadVoiceChoices(snapshot: GatewaySnapshot, attempt: number): Promise<void> {
    try {
      this._update((s) => {
        s.choicesStatus = "loading";
        s.choicesError = null;
        s.voiceStatus = "idle";
        s.voiceError = null;
      });
      if (this._choicesVersion !== snapshot.version) {
        const choiceID = savedVoiceChoice(snapshot.origin);
        this._choicesVersion = snapshot.version;
        this._update((s) => {
          s.choices = [];
          s.choiceID = choiceID;
        });
      }
      const metadata = validateVoiceChoices(await snapshot.api.voiceChoices());
      if (snapshot.version !== untrack(gatewayVersion) || attempt !== this._choicesAttempt) return;
      this._update((s) => {
        s.choices = metadata.choices;
        s.choicesStatus = "ready";
      });
      const choiceID = resolveVoiceChoice(metadata, this.state.mode, this.state.choiceID);
      this._update((s) => {
        s.choiceID = choiceID;
      });
      this._restoreSpeakingVoice(
        snapshot.origin,
        metadata.choices.find((choice) => choice.id === choiceID)!,
      );
    } catch (error) {
      if (snapshot.version !== untrack(gatewayVersion) || attempt !== this._choicesAttempt) return;
      this._update((s) => {
        if (s.choicesStatus === "loading") s.choicesStatus = "error";
        s.choicesError = error instanceof Error ? error.message : String(error);
      });
    }
  }

  /** Persist an explicit choice only after validating it against the current gateway and transport. */
  selectChoice(choiceID: string): void {
    if (this.state.connected || this.state.connectStatus !== null)
      throw new Error("End the session before changing voice choice");
    if (this._choicesVersion !== untrack(gatewayVersion) || this.state.choicesStatus !== "ready")
      throw new Error("Load voice choices before selecting one");
    if (!this.compatibleChoices.some((choice) => choice.id === choiceID))
      throw new Error("Choose an available voice choice");
    localStorage.setItem(choiceStorageKey(gatewaySnapshot().origin), choiceID);
    this._update((s) => {
      s.choiceID = choiceID;
      s.choicesError = null;
    });
    this._restoreSpeakingVoice(
      gatewaySnapshot().origin,
      this.state.choices.find((choice) => choice.id === choiceID)!,
    );
  }

  private async _resolveSessionChoice(snapshot: GatewaySnapshot, attempt: number): Promise<string | null> {
    const metadata = validateVoiceChoices(await snapshot.api.voiceChoices());
    if (attempt !== this._connectionAttempt) return null;
    if (snapshot.version === untrack(gatewayVersion)) {
      // A connect-time refresh owns publication over an older settings request.
      this._choicesAttempt++;
      this._choicesVersion = snapshot.version;
      this._update((s) => {
        s.choices = metadata.choices;
        s.choiceID = this._sessionChoiceID;
        s.choicesStatus = "ready";
        s.choicesError = null;
      });
    }
    let choiceID: string;
    try {
      choiceID = resolveVoiceChoice(metadata, this.state.mode, this._sessionChoiceID);
    } catch (error) {
      if (snapshot.version === untrack(gatewayVersion))
        this._update((s) => {
          s.choicesError = error instanceof Error ? error.message : String(error);
        });
      throw error;
    }
    this._sessionChoiceID = choiceID;
    const choice = metadata.choices.find((choice) => choice.id === choiceID)!;
    let voiceError: string | null = null;
    try {
      const name =
        this.state.mode !== "cloud"
          ? ""
          : (this._sessionVoiceName ?? savedSpeakingVoice(snapshot.origin, choice, this.state.mode));
      this._sessionVoiceName = name;
      voiceError = this.state.mode === "cloud" ? speakingVoiceError(choice, name) : null;
      if (snapshot.version === untrack(gatewayVersion))
        this._update((s) => {
          s.choiceID = choiceID;
          s.voiceName = name;
          s.voiceError = voiceError;
          s.voiceStatus = "ready";
        });
    } catch (error) {
      if (snapshot.version === untrack(gatewayVersion))
        this._update((s) => {
          s.voiceName = null;
          s.voiceError = error instanceof Error ? error.message : String(error);
          s.voiceStatus = "error";
        });
      throw error;
    }
    if (voiceError !== null) throw new Error(voiceError);
    return choiceID;
  }

  /** Persist the transport preference without disrupting an active session. */
  selectMode(mode: VoiceMode): void {
    if (mode !== "browser" && mode !== "cloud") throw new Error("Invalid voice mode");
    if (this.state.connected || this.state.connectStatus !== null)
      throw new Error("End the session before changing voice mode");
    if (mode === "browser" && !browserSpeechAvailable())
      throw new Error("Browser speech is unavailable. Use Cloud voice.");
    localStorage.setItem("gomode.voiceMode", mode);
    this.setState((s) => ({ ...s, mode }));
    if (this._choicesVersion === untrack(gatewayVersion) && this.state.choicesStatus === "ready") {
      void this.loadVoiceChoices();
    }
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
    const attempt = ++this._deviceEnumerationAttempt;
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      if (attempt !== this._deviceEnumerationAttempt) return;
      const previousInputs = this.state.audioInputs;
      const previousOutputs = this.state.audioOutputs;
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
      this._update((s) => {
        s.audioInputs = inputs;
        s.audioOutputs = outputs;
        s.audioDevicesError = null;
      });
      // Drop concrete queued routes when their devices disappear. The system
      // default uses an empty ID and stays valid independently of enumeration.
      if (this._pendingAudioInput && !inputs.some((d) => d.deviceId === this._pendingAudioInput))
        this._pendingAudioInput = null;
      if (this._pendingAudioOutput && !outputs.some((d) => d.deviceId === this._pendingAudioOutput))
        this._pendingAudioOutput = null;
      // Only arrivals override a manual selection. Re-enumeration alone leaves it alone.
      const preferred = (
        available: AudioDevice[],
        previous: AudioDevice[],
        current: string,
        kind: "Input" | "Output",
      ) => {
        const rememberedBluetooth = savedAudioDevice(`Bluetooth${kind}`);
        const bluetooth = available.filter((d) => bluetoothAudioDevice(d, rememberedBluetooth));
        const arrived = bluetooth.find(
          (d) => !previous.some((old) => old.deviceId === d.deviceId && bluetoothAudioDevice(old, rememberedBluetooth)),
        );
        const lastBluetooth = arrived ?? bluetooth[0];
        if (lastBluetooth) rememberAudioDevice(`Bluetooth${kind}`, lastBluetooth.deviceId);
        const provisional = kind === "Input" ? this._inputPreferenceProvisional : this._outputPreferenceProvisional;
        return (
          arrived?.deviceId ??
          (!current && !provisional
            ? available.find((d) => d.deviceId === savedAudioDevice(kind))?.deviceId
            : undefined)
        );
      };
      this._pendingAudioOutput =
        preferred(outputs, previousOutputs, this.state.selectedOutputId, "Output") ?? this._pendingAudioOutput;
      this._pendingAudioInput =
        preferred(inputs, previousInputs, this.state.selectedInputId, "Input") ?? this._pendingAudioInput;
      const curOut = this.state.selectedOutputId;
      if (curOut && !outputs.some((d) => d.deviceId === curOut)) {
        if (this.state.audioSwitching !== null) {
          // An output switch already owns recovery and will report its outcome.
          if (this.state.audioSwitching !== "output")
            this._update((s) => {
              s.audioOutputError = "Speaker disconnected. Choose another speaker.";
            });
        } else {
          await this._selectOutputDevice("", false);
        }
      }
      const curSel = this.state.selectedInputId;
      if (attempt !== this._deviceEnumerationAttempt) return;
      if (curSel && !inputs.some((d) => d.deviceId === curSel)) {
        if (this.state.audioSwitching !== null) {
          // Preserve warnings about the other route while this switch completes.
          if (this.state.audioSwitching !== "input")
            this._update((s) => {
              s.audioInputError = "Microphone disconnected. Choose another microphone.";
            });
        } else {
          // Keep the displayed choice until a replacement track is working.
          await this._selectInputDevice("", false);
        }
      }
      if (attempt !== this._deviceEnumerationAttempt) return;
      await this._applyPendingAudioDevices();
    } catch (error) {
      if (attempt !== this._deviceEnumerationAttempt) return;
      this._update((s) => {
        s.audioDevicesError = `Could not list audio devices: ${String(error)}`;
      });
    }
  }

  // Arrivals can occur during a manual switch or a previous automatic switch.
  // Consume each requested route once; failures stay visible until the user retries.
  private async _applyPendingAudioDevices(): Promise<void> {
    if (this.state.audioSwitching !== null) return;
    const output = this._pendingAudioOutput;
    this._pendingAudioOutput = null;
    const needsOutput =
      output !== this.state.selectedOutputId ||
      this._outputPreferenceProvisional ||
      (this._speakerAudio !== null && this._speakerAudio.sinkId !== output);
    if (output !== null && needsOutput && "setSinkId" in HTMLMediaElement.prototype)
      await this._selectOutputDevice(output, true);
    if (this.state.audioSwitching !== null) return;
    const input = this._pendingAudioInput;
    this._pendingAudioInput = null;
    if (input && input !== this.state.selectedInputId) await this._selectInputDevice(input, true);
  }

  /** Select an audio input device. If connected, replaces the mic track. */
  async selectInputDevice(deviceId: string): Promise<void> {
    this._pendingAudioInput = null;
    await this._selectInputDevice(deviceId, true);
  }

  private async _selectInputDevice(deviceId: string, remember: boolean): Promise<void> {
    if (this.state.audioSwitching !== null) return;
    const selection = ++this._audioSelectionAttempt;
    const pc = this._pc;
    const oldStream = this._micStream;
    if (pc && oldStream) {
      this._update((s) => {
        s.audioSwitching = "input";
        s.audioInputError = null;
      });
      let newStream: MediaStream | null = null;
      try {
        // Acquire new mic stream with the selected device.
        const constraints: MediaStreamConstraints = {
          audio: deviceId ? { deviceId: { exact: deviceId } } : true,
          video: false,
        };
        newStream = await navigator.mediaDevices.getUserMedia(constraints);
        if (this._pc !== pc) {
          newStream.getTracks().forEach((track) => track.stop());
          return;
        }
        // Replace tracks on the PeerConnection.
        const sender = pc.getSenders().find((s) => s.track?.kind === "audio");
        for (const t of newStream.getAudioTracks()) {
          t.enabled = !this.state.muted;
          if (sender) {
            await sender.replaceTrack(t);
            if (this._pc !== pc) {
              newStream.getTracks().forEach((track) => track.stop());
              return;
            }
          } else {
            pc.addTrack(t, newStream);
          }
        }
        this._micStream = newStream;
        oldStream.getTracks().forEach((track) => track.stop());
        this._update((s) => {
          s.selectedInputId = deviceId;
          s.audioInputError = null;
        });
        if (remember) rememberAudioDevice("Input", deviceId);
        this._inputPreferenceProvisional = false;
        this._monitorMicrophone(pc, newStream);
      } catch (error) {
        if (this._micStream !== newStream) newStream?.getTracks().forEach((track) => track.stop());
        if (this._pc === pc)
          this._update((s) => {
            s.audioInputError = `Could not switch microphone: ${String(error)}`;
          });
      } finally {
        if (selection === this._audioSelectionAttempt) {
          this._update((s) => {
            s.audioSwitching = null;
          });
          void this._applyPendingAudioDevices();
        }
      }
    } else {
      this._update((s) => {
        s.selectedInputId = deviceId;
        s.audioInputError = null;
      });
      this._inputPreferenceProvisional = remember;
    }
  }

  private _monitorMicrophone(pc: RTCPeerConnection, stream: MediaStream): void {
    if (this._micMonitorFrame !== null) cancelAnimationFrame(this._micMonitorFrame);
    this._micMonitorFrame = null;
    const context = this._audioContext;
    if (context === null) return;
    const analyser = context.createAnalyser();
    analyser.fftSize = 256;
    context.createMediaStreamSource(stream).connect(analyser);
    const buf = new Uint8Array(analyser.frequencyBinCount);
    const pollMicLevel = () => {
      if (this._pc !== pc || this._micStream !== stream) return;
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
      this._micMonitorFrame = requestAnimationFrame(pollMicLevel);
    };
    this._micMonitorFrame = requestAnimationFrame(pollMicLevel);
  }

  /** Select an audio output device. Applies immediately if connected. */
  async selectOutputDevice(deviceId: string): Promise<void> {
    this._pendingAudioOutput = null;
    await this._selectOutputDevice(deviceId, true);
  }

  private async _selectOutputDevice(deviceId: string, remember: boolean): Promise<void> {
    if (this.state.audioSwitching !== null) return;
    const selection = ++this._audioSelectionAttempt;
    const audio = this._speakerAudio;
    this._update((s) => {
      s.audioSwitching = "output";
      s.audioOutputError = null;
    });
    try {
      if (!("setSinkId" in HTMLMediaElement.prototype))
        throw new Error("Use your device's sound settings to change the speaker");
      if (audio) await audio.setSinkId(deviceId);
      if (this._speakerAudio !== audio || selection !== this._audioSelectionAttempt) return;
      this._update((s) => {
        s.selectedOutputId = deviceId;
        s.audioOutputError = null;
      });
      // Before remote audio exists, this is a provisional route selection.
      if (audio) {
        if (remember) rememberAudioDevice("Output", deviceId);
        this._outputPreferenceProvisional = false;
      } else {
        this._outputPreferenceProvisional = remember;
      }
    } catch (error) {
      if (this._speakerAudio === audio && selection === this._audioSelectionAttempt)
        this._update((s) => {
          s.audioOutputError = `Could not switch speaker: ${String(error)}`;
        });
    } finally {
      if (selection === this._audioSelectionAttempt) {
        this._update((s) => {
          s.audioSwitching = null;
        });
        void this._applyPendingAudioDevices();
      }
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
    if (this.state.mode === "browser") this._thinkingEarcon.prepare();
  }

  /** Start a new voice session via WebRTC data channel through the host backend. */
  async connect(): Promise<void> {
    this._conversationID = newConversationID();
    this.prepareAudio();
    this._sessionGateway = gatewaySnapshot();
    this._sessionVoiceName = null;
    try {
      this._sessionChoiceID =
        this._choicesVersion === this._sessionGateway.version
          ? this.state.choiceID
          : savedVoiceChoice(this._sessionGateway.origin);
      if (this._choicesVersion === this._sessionGateway.version) {
        const choice = this.state.choices.find((choice) => choice.id === this._sessionChoiceID);
        if (choice && (this.state.mode !== "cloud" || choice.voices?.length))
          this._sessionVoiceName = savedSpeakingVoice(this._sessionGateway.origin, choice, this.state.mode);
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this._update((s) => {
        s.voiceName = null;
        s.voiceError = message;
        s.voiceStatus = "error";
      });
      this._setError(message);
      return;
    }
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
      const snapshot = this._sessionGateway!;
      const choiceID = await this._resolveSessionChoice(snapshot, attempt);
      if (choiceID === null) return;
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
      // Initial acquisition owns input routing too: devicechange must queue any
      // arrival until the first stream has been installed on the PeerConnection.
      this._update((s) => {
        s.audioSwitching = "input";
      });
      const micStream = await navigator.mediaDevices.getUserMedia(micConstraints);
      if (attempt !== this._connectionAttempt) {
        micStream.getTracks().forEach((track) => track.stop());
        return;
      }
      this._micStream = micStream;
      if (this._inputPreferenceProvisional) {
        rememberAudioDevice("Input", inputId);
        this._inputPreferenceProvisional = false;
      }
      for (const t of micStream.getAudioTracks()) {
        t.enabled = !this.state.muted;
        pc.addTrack(t, micStream);
      }
      this._update((s) => {
        s.audioSwitching = null;
      });
      await this.enumerateDevices();
      if (attempt !== this._connectionAttempt) return;
      const activeMicStream = this._micStream;
      if (activeMicStream === null) return;

      this._monitorMicrophone(pc, activeMicStream);

      // Speaker audio from remote RTP track.
      pc.ontrack = (evt) => {
        if (attempt !== this._connectionAttempt || this._pc !== pc) return;
        const audio = new Audio();
        this._speakerAudio = audio;
        audio.srcObject = evt.streams[0] ?? new MediaStream([evt.track]);
        if (this.state.selectedOutputId || this._outputPreferenceProvisional) {
          this._pendingAudioOutput ??= this.state.selectedOutputId;
          void this._applyPendingAudioDevices();
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
      const service = snapshot.serviceProvider === null ? null : await snapshot.serviceProvider();
      if (attempt !== this._connectionAttempt) return;
      const request = service === null ? { sdp: offerSDP, choiceID } : { sdp: offerSDP, choiceID, service };
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
      const snapshot = this._sessionGateway!;
      const choiceID = await this._resolveSessionChoice(snapshot, attempt);
      if (choiceID === null) return;
      const [systemInstruction, tools, serviceItems, service] = await Promise.all([
        mcpClient.serverInstructions(),
        mcpClient.listTools(),
        mcpClient.readAdvertisedTextResource(GO_MODE_ITEMS_RESOURCE_URI).catch(() => null),
        snapshot.serviceProvider?.() ?? Promise.resolve(null),
      ]);
      if (attempt !== this._connectionAttempt) return;
      const response = await snapshot.api.voiceTextTicket(service === null ? { choiceID } : { choiceID, service });
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
        if (this._ownsChannel(attempt, socket)) this._setError("Voice gateway unreachable.");
      };
      socket.onclose = (event) => {
        if (!this._ownsChannel(attempt, socket)) return;
        if (event.code === 1000) this.disconnect();
        else this._setError(`Text voice connection closed (${event.code})`);
      };
      this._setupTimer = setTimeout(() => {
        if (this._ownsChannel(attempt, socket) && !this.state.connected) this._setError("Voice connection timed out.");
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
    if (this.state.connected && !this.state.muted && !this.state.speaking && this._browserTurnIdle) {
      this._thinkingEarcon.setThinking(false);
      this._browserSpeech?.listen();
    }
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
    this._audioSelectionAttempt++;
    this._deviceEnumerationAttempt++;
    this._pendingAudioInput = null;
    this._pendingAudioOutput = null;
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
      s.audioSwitching = null;
      s.audioDevicesError = null;
      s.audioInputError = null;
      s.audioOutputError = null;
      s.transcript = s.transcript.map((e) => ({ ...e, final: true }));
    });
  }

  dismissError(): void {
    this._update((s) => {
      s.error = null;
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
    this._thinkingEarcon.setThinking(false);
    if (this._micMonitorFrame !== null) cancelAnimationFrame(this._micMonitorFrame);
    this._micMonitorFrame = null;
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
    this._audioSelectionAttempt++;
    this._deviceEnumerationAttempt++;
    this._pendingAudioInput = null;
    this._pendingAudioOutput = null;
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
      s.audioSwitching = null;
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
    untrack(() => {
      const state = this.state;
      this._thinkingEarcon.setThinking(
        state.mode === "browser" &&
          !browserSpeechAvoidsChimes() &&
          this._browserSpeech !== null &&
          state.connected &&
          state.turnState === TurnStateThinking &&
          !state.listening &&
          !state.speaking &&
          !state.muted,
      );
    });
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
      this._sessionVoiceName ?? "",
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
        s.turnState = TurnStateIdle;
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
        : await mcpClient.callTool(msg.name, msg.args ?? {}, {
            locale: this.state.languageTag,
            sessionID: this._conversationID,
          });
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
  voiceName: string,
): SessionSetup {
  return {
    kind: MessageKindSessionSetup,
    voice: {
      name: voiceName,
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
