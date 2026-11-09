// Browser voice sessions own transport attempts and delegate audio routes and conversation-bound host tools.

import { AudioRoutes, type AudioRouteState } from "./AudioRoutes";
import { voiceGatewayBaseURL } from "./BrowserVoiceManifest";
import { BrowserSpeechBuffer } from "./BrowserSpeechBuffer";
import { MicrophoneLevel } from "./MicrophoneLevel";
import { MicrophoneInput } from "./MicrophoneInput";
import { mergeVoiceUsage, type VoiceUsage } from "./VoiceUsage";
import { batch, createSignal, untrack } from "solid-js";
import { createStore, produce } from "solid-js/store";

// TODO: Cleanup imports.
import * as voicegatewaySDK from "../../sdk/voicegateway/ts/v2/api.gen";
import { createApiClient as createGoModeClient } from "../../sdk/gomode/ts/v1/api.gen";
import {
  type ContextUpdate,
  type Error as GatewayError,
  type UserMessage,
  type MessageEnvelope,
  type SessionSetup,
  type ToolResult,
  type ToolCall,
  type TranscriptDelta,
  type TranscriptDraft,
  type AssistantTextDelta,
  type ReasoningDelta,
  type TurnState,
  type TurnStatus,
  type SessionStatus,
  type SessionUsage,
  type TokenUsage,
  type VoiceRTCClientDiagnostics,
  type VoiceRTCDiagnosticsResp,
  MessageKindContextUpdate,
  MessageKindAssistantTextDelta,
  MessageKindReasoningDelta,
  MessageKindUserMessage,
  MessageKindError,
  MessageKindInterrupted,
  MessageKindSessionReady,
  MessageKindSessionStatus,
  MessageKindSessionUsage,
  MessageKindSessionSetup,
  MessageKindSpeechEnded,
  MessageKindSpeechStarted,
  MessageKindToolCall,
  MessageKindToolResult,
  MessageKindTranscriptDelta,
  MessageKindTranscriptDraft,
  MessageKindTurnStatus,
  MessageKindTurnCancel,
  TurnStateIdle,
  TurnStateThinking,
  type ServiceAuthorization,
  type VoiceChoice,
  type VoiceChoicesResp,
  type VoiceTransport,
  type SpeechConfig,
  type SpeechLocation,
  type SpeechModel,
} from "../../sdk/voicegateway/ts/v2/types.gen";

import { mcpClient, type McpToolDescriptor } from "./McpClient";
import {
  captureFrontendVoiceTools,
  type FrontendVoiceToolsSnapshot,
  type FrontendSessionPreparation,
} from "./FrontendVoiceTools";
import { GO_MODE_ITEMS_RESOURCE_URI, initialServiceContext } from "./ServiceItems";
import { WebAudioVoiceChime, type VoiceChimePlayer } from "./VoiceChime";
import { WebAudioThinkingEarcon, type ThinkingEarcon } from "./ThinkingEarcon";
import {
  BrowserSpeech,
  browserRecognitionAvailable,
  browserSynthesisAvailable,
  browserVoiceMatchesLanguage,
  browserSpeechAvoidsChimes,
} from "./BrowserSpeech";

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
let gatewayHeadersProvider: (() => HeadersInit) | null = null;
let sessionPreparation: FrontendSessionPreparation | null = null;

/** Configure gateway authorization; host headers are captured per session and sent only to same-origin endpoints. */
export function configureVoiceGateway(
  baseURL: string,
  provider: ServiceAuthorizationProvider | null,
  tokenProvider?: () => string | null | Promise<string | null>,
  headersProvider?: () => HeadersInit,
  preparation?: FrontendSessionPreparation,
): void {
  gatewayBaseURL = voiceGatewayBaseURL(baseURL, window.location.origin);
  serviceAuthorizationProvider = provider;
  gatewayBearerTokenProvider = tokenProvider ?? null;
  gatewayHeadersProvider = headersProvider ?? null;
  sessionPreparation = preparation ?? null;
  setGatewayVersion((version) => version + 1);
}

type BearerTokenProvider = () => string | null | Promise<string | null>;

async function gatewayFetch(
  baseURL: string,
  bearerProvider: BearerTokenProvider | null,
  hostHeaders: HeadersInit | undefined,
  path: string,
  init?: RequestInit,
): Promise<Response> {
  const origin = new URL(baseURL).origin;
  const headers = new Headers(init?.headers);
  if (origin === window.location.origin) {
    new Headers(hostHeaders).forEach((value, key) => headers.set(key, value));
  }
  if (origin === window.location.origin && !headers.has("Authorization")) {
    const token = await bearerProvider?.();
    if (token) headers.set("Authorization", `Bearer ${token}`);
  }
  let response: Response;
  try {
    response = await fetch(new URL(path.slice(1), baseURL).toString(), { ...init, headers });
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
      return path.startsWith("/api/voicegateway/v2/voice/rtc/") && !path.endsWith("/rtc/offer")
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
  gatewayFetch(
    gatewayBaseURL ?? window.location.origin + "/",
    gatewayBearerTokenProvider,
    gatewayHeadersProvider?.(),
    path,
    init,
  ),
);

interface GatewaySnapshot {
  url: URL;
  version: number;
  serviceProvider: ServiceAuthorizationProvider | null;
  bearerProvider: BearerTokenProvider | null;
  preparation: FrontendSessionPreparation | null;
  api: ReturnType<typeof voicegatewaySDK.createApiClient>;
}

function gatewaySnapshot(): GatewaySnapshot {
  const url = new URL(gatewayBaseURL ?? window.location.origin + "/");
  const serviceProvider = serviceAuthorizationProvider;
  const bearerProvider = gatewayBearerTokenProvider;
  // Keep host identity fixed for this session's signaling, retries and cleanup.
  const headers = new Headers(gatewayHeadersProvider?.());
  return {
    url,
    version: untrack(gatewayVersion),
    serviceProvider,
    bearerProvider,
    preparation: sessionPreparation,
    api: voicegatewaySDK.createApiClient((path, init) => gatewayFetch(url.href, bearerProvider, headers, path, init)),
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
function normalizeToolParameters(parameters: unknown): Record<string, unknown> {
  if (
    parameters !== null &&
    typeof parameters === "object" &&
    !Array.isArray(parameters) &&
    Object.keys(parameters).length > 0
  ) {
    return parameters as Record<string, unknown>;
  }
  return { type: "object", properties: {} };
}

export function voiceToolDeclarations(
  mcpTools: McpToolDescriptor[],
  frontendTools: SessionSetup["tools"],
): SessionSetup["tools"] {
  if (mcpTools.some((tool) => tool.name === HANG_UP_TOOL_NAME)) {
    throw new Error(`MCP tool "${HANG_UP_TOOL_NAME}" conflicts with the reserved voice command.`);
  }
  for (const tool of frontendTools) {
    if (mcpTools.some((mcp) => mcp.name === tool.name)) {
      throw new Error(`MCP tool "${tool.name}" conflicts with a frontend voice tool.`);
    }
  }
  return [
    ...frontendTools.map((tool) => ({ ...tool, parameters: normalizeToolParameters(tool.parameters) })),
    {
      name: HANG_UP_TOOL_NAME,
      description:
        "End the current voice conversation immediately when the user asks to hang up, end the call, or stop voice mode.",
      parameters: { type: "object", properties: {} },
    },
    ...mcpTools.map((tool) => ({
      name: tool.name,
      description: tool.description,
      parameters: normalizeToolParameters(tool.inputSchema),
    })),
  ];
}

// State types

export type TranscriptSpeaker = "user" | "assistant";

/** What a transcript entry carries: a statement or provider reasoning. */
export type TranscriptKind = "transcript" | "reasoning";

export interface TranscriptEntry {
  kind: TranscriptKind;
  speaker: TranscriptSpeaker;
  text: string;
  final: boolean;
  responseID?: string;
  responseUsage?: TokenUsage;
}

export type SpeechSelection = Required<SpeechConfig>;
type VoiceChannel = RTCDataChannel | WebSocket;

function voiceChannelOpen(channel: VoiceChannel): boolean {
  return channel.readyState === "open" || channel.readyState === 1; // WebSocket.OPEN
}

export type MicrophoneMode = "listening" | "muteOnce" | "muted";

/** VoiceErrorEntry is one retained failure with the moment it happened. */
export interface VoiceErrorEntry {
  at: number;
  message: string;
}

/** Count of retained failures the overlay keeps reviewable. */
export const MAX_VOICE_ERRORS = 5;
const VOICE_MESSAGE_DELIVERY_ERROR = "Couldn't send a voice message. Reconnect voice.";
const VOICE_TOOL_RESULT_DELIVERY_ERROR =
  "Couldn't deliver the action's result. Reconnect voice. The action may have completed; check before repeating it.";

/** Failures before the one the panel shows, newest first. */
export function previousErrors(state: VoiceState): VoiceErrorEntry[] {
  const [newest, ...rest] = state.errorHistory;
  if (newest === undefined) return [];
  return state.error !== null && newest.message === state.error ? rest : state.errorHistory;
}

export function microphoneMuted(state: Pick<VoiceState, "microphoneMode" | "speaking">): boolean {
  return state.microphoneMode === "muted" || (state.microphoneMode === "muteOnce" && state.speaking);
}

/** One admitted tool invocation; sequence distinguishes repeated names in a conversation. */
export interface ToolInvocation {
  sequence: number;
  name: string;
}

export type VoiceSetupMode = "all-in-one" | "parts";

export interface VoiceState extends AudioRouteState {
  setupMode: VoiceSetupMode;
  usage: VoiceUsage | null;
  choices: VoiceChoice[];
  speechModels: Record<"input" | "output", SpeechModel[]>;
  speechModelIDs: Record<"input" | "output", string | null>;
  /** Effective gateway choice per family, null when the gateway offers none. */
  choiceIDs: Record<VoiceTransport, string | null>;
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
  microphoneMode: MicrophoneMode;
  activeTool: string | null;
  /** Latest 32 admitted tool names, in invocation order across reconnects. */
  toolHistory: ToolInvocation[];
  /** Gateway work before assistant output; idle when the gateway does not report any. */
  turnState: TurnState;
  transcript: TranscriptEntry[];
  /** Provisional server recognition; excluded from history and recovery. */
  transcriptDraft: { id: string; text: string } | null;
  /** Shows provider reasoning in the transcript. Off by default. */
  showReasoning: boolean;
  /** Shows reported token counts in the voice overlay. Off by default. */
  showUsageStats: boolean;
  micLevel: number;
  micLevelError: string | null;
  error: string | null;
  /** Retained failures, newest first; the panel shows the newest one. */
  errorHistory: VoiceErrorEntry[];
  /** Browser permission, null when the browser cannot report it yet. */
  microphoneAccess: PermissionState | null;
  microphoneAccessPending: boolean;
  audioPlaybackError: string | null;
  /** Available audio input devices (microphones). */
  /** Available audio output devices (speakers, headsets). */
  /** Currently selected input device ID, empty string for system default. */
  /** Currently selected output device ID, empty string for system default. */
  /** Saved BCP 47 speech language, independent of the browser locale. */
  languageTag: string;
  /** Saved browser synthesis voice URI; empty string uses the browser default. */
  browserVoiceURI: string;
  /** Saved browser speaking speed as a multiple of the voice's normal rate. */
  browserSpeechRate: number;
}

export const BROWSER_SPEECH_RATES: readonly number[] = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];

function validateBrowserSpeechRate(rate: number): number {
  if (!BROWSER_SPEECH_RATES.includes(rate)) throw new Error("Choose an available speaking speed");
  return rate;
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

function savedSpeechLocation(stage: "Input" | "Output"): SpeechLocation {
  try {
    const saved = localStorage.getItem(`gomode.voice${stage}`);
    if (saved === "client" || saved === "gateway") return saved;
    return localStorage.getItem("gomode.voiceMode") === "browser" ? "client" : "gateway";
  } catch (error) {
    console.warn("Could not load speech preference; using gateway", error);
    return "gateway";
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

function savedBrowserVoiceURI(): string {
  try {
    return localStorage.getItem("gomode.browserVoiceURI") ?? "";
  } catch (error) {
    console.warn("Could not load browser speaking voice; using browser default", error);
    return "";
  }
}

function savedBrowserSpeechRate(): number {
  try {
    const saved = localStorage.getItem("gomode.browserSpeechRate");
    return saved === null ? 1 : validateBrowserSpeechRate(Number(saved));
  } catch (error) {
    console.warn("Could not load browser speaking speed; using normal speed", error);
    return 1;
  }
}

function savedReasoning(): boolean {
  try {
    return localStorage.getItem("gomode.voiceReasoning") === "on";
  } catch (error) {
    console.warn("Could not load the reasoning display preference; hiding reasoning", error);
    return false;
  }
}

function savedUsageStats(): boolean {
  try {
    return localStorage.getItem("gomode.voiceUsageStats") === "on";
  } catch (error) {
    console.warn("Could not load the usage display preference; hiding usage stats", error);
    return false;
  }
}

function choiceStorageKey(origin: string, transport: VoiceTransport): string {
  return `gomode.voiceChoice.${origin}.${transport}`;
}

function savedVoiceChoice(origin: string, transport: VoiceTransport): string | null {
  const setup = savedSetup(origin);
  if (setup !== null)
    return setup.mode === "all-in-one" && transport === "webrtc" ? setup.nativeChoiceID : setup.partsChoiceID;
  return (
    localStorage.getItem(`gomode.voiceModel.${origin}`) ?? localStorage.getItem(choiceStorageKey(origin, transport))
  );
}

const VOICE_TRANSPORTS: readonly VoiceTransport[] = ["text", "webrtc"];

// A single saved selection owns both the active setup and the remembered parts.
// The active model is derived from mode; it is never persisted a second time.
interface VoiceSetupPreference {
  mode: VoiceSetupMode;
  nativeChoiceID: string | null;
  partsChoiceID: string | null;
  partsInput: SpeechLocation;
  partsOutput: SpeechLocation;
  recognitionModelID: string | null;
  synthesisModelID: string | null;
}

function setupStorageKey(origin: string): string {
  return `gomode.voiceSetup.${origin}`;
}

function savedSetup(origin: string): VoiceSetupPreference | null {
  const raw = localStorage.getItem(setupStorageKey(origin));
  if (raw === null) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (value === null || typeof value !== "object") throw new Error("Invalid saved voice setup");
    const v = value as Record<string, unknown>;
    if (
      (v.mode !== "all-in-one" && v.mode !== "parts") ||
      (v.partsInput !== "gateway" && v.partsInput !== "client") ||
      (v.partsOutput !== "gateway" && v.partsOutput !== "client") ||
      [v.nativeChoiceID, v.partsChoiceID, v.recognitionModelID, v.synthesisModelID].some(
        (id) => id !== null && (typeof id !== "string" || id === ""),
      )
    )
      throw new Error("Invalid saved voice setup");
    return v as unknown as VoiceSetupPreference;
  } catch (error) {
    console.warn("Could not load the voice setup; using available models", error);
    return null;
  }
}

function nativeVoiceChoice(choice: VoiceChoice): boolean {
  return choice.composable !== true && !choice.transports.includes("text");
}

function setupChoices(choices: VoiceChoice[], mode: VoiceSetupMode): VoiceChoice[] {
  const composable = choices.some((choice) => choice.composable === true);
  return choices.filter((choice) =>
    mode === "all-in-one"
      ? nativeVoiceChoice(choice)
      : choice.composable === true || (!composable && choice.transports.includes("text")),
  );
}

function transportFor(speech: SpeechSelection): VoiceTransport {
  return speech.input === "client" && speech.output === "client" ? "text" : "webrtc";
}

function choiceSupportsSpeech(choice: VoiceChoice, speech: SpeechSelection): boolean {
  const modes =
    choice.speechModes ??
    choice.transports.map((transport) =>
      transport === "text" ? { input: "client", output: "client" } : { input: "gateway", output: "gateway" },
    );
  return modes.some((mode) => mode.input === speech.input && mode.output === speech.output);
}

function compatibleChoices(
  choices: VoiceChoice[],
  transport: VoiceTransport,
  speech: SpeechSelection | null,
): VoiceChoice[] {
  return choices.filter(
    (choice) =>
      (!choices.some((candidate) => candidate.composable === true) ||
        choice.composable === true ||
        !choice.transports.includes("text")) &&
      choice.transports.includes(transport) &&
      (speech === null || choiceSupportsSpeech(choice, speech)),
  );
}

type SpeechStage = "input" | "output";

function speechModelStorageKey(origin: string, stage: SpeechStage): string {
  return `gomode.voiceSpeechModel.${origin}.${stage}`;
}

function resolveSpeechModel(models: SpeechModel[], selected: string | null, stage: SpeechStage): string {
  const label = stage === "input" ? "recognition" : "speech";
  if (selected !== null) {
    if (!models.some((model) => model.id === selected))
      throw new Error(`The selected ${label} model is unavailable. Choose another ${label} model.`);
    return selected;
  }
  if (!models[0])
    throw new Error(
      `The gateway has no ${label} models. Choose browser ${stage === "input" ? "recognition" : "speech output"} or a native audio model.`,
    );
  return models[0].id;
}

function validSpeechModels(models: SpeechModel[] | undefined): boolean {
  return (
    models === undefined ||
    (Array.isArray(models) &&
      models.every(
        (model) =>
          model !== null &&
          typeof model === "object" &&
          typeof model.id === "string" &&
          model.id.trim() !== "" &&
          typeof model.label === "string" &&
          model.label.trim() !== "",
      ) &&
      new Set(models.map((model) => model.id)).size === models.length)
  );
}

function validVoiceCatalog(choice: VoiceChoice): boolean {
  if (choice.voices == null) return true;
  return (
    Array.isArray(choice.voices) &&
    choice.voices.every((name) => typeof name === "string" && name.trim() !== "") &&
    new Set(choice.voices).size === choice.voices.length
  );
}

function speakingVoiceStorageKey(origin: string, choiceID: string): string {
  return `gomode.speakingVoice.${JSON.stringify([origin, choiceID])}`;
}

function savedSpeakingVoice(origin: string, choice: VoiceChoice, output: SpeechLocation): string {
  if (output !== "gateway" || !choice.voices?.length) return "";
  return localStorage.getItem(speakingVoiceStorageKey(origin, choice.id)) ?? "";
}

function speakingVoiceError(choice: VoiceChoice, name: string): string | null {
  const available = name === "" || choice.voices?.includes(name) === true;
  return available ? null : "The saved speaking voice is unavailable. Choose another speaking voice.";
}

function validateVoiceChoices(value: VoiceChoicesResp): VoiceChoicesResp {
  if (
    !Array.isArray(value.choices) ||
    !Array.isArray(value.defaults) ||
    !validSpeechModels(value.recognitionModels) ||
    !validSpeechModels(value.synthesisModels) ||
    value.choices.some(
      (choice) =>
        choice === null ||
        typeof choice !== "object" ||
        (choice.composable !== undefined && typeof choice.composable !== "boolean") ||
        typeof choice.id !== "string" ||
        choice.id === "" ||
        typeof choice.label !== "string" ||
        choice.label === "" ||
        !Array.isArray(choice.transports) ||
        choice.transports.length === 0 ||
        choice.transports.some((transport) => !VOICE_TRANSPORTS.includes(transport)) ||
        !validVoiceCatalog(choice) ||
        (choice.speechModes !== undefined &&
          (!Array.isArray(choice.speechModes) ||
            choice.speechModes.length === 0 ||
            choice.speechModes.some(
              (mode) =>
                mode === null ||
                typeof mode !== "object" ||
                !["client", "gateway"].includes(mode.input ?? "") ||
                !["client", "gateway"].includes(mode.output ?? ""),
            ))),
    ) ||
    value.defaults.some(
      (fallback) =>
        !VOICE_TRANSPORTS.includes(fallback.transport) ||
        typeof fallback.id !== "string" ||
        fallback.id === "" ||
        !value.choices.some((choice) => choice.id === fallback.id && choice.transports.includes(fallback.transport)),
    ) ||
    new Set(value.choices.map((choice) => choice.id)).size !== value.choices.length ||
    new Set(value.defaults.map((fallback) => fallback.transport)).size !== value.defaults.length
  )
    throw new Error("The voice gateway returned invalid choices");
  return value;
}

function defaultChoiceID(metadata: VoiceChoicesResp, transport: VoiceTransport): string | null {
  return metadata.defaults.find((fallback) => fallback.transport === transport)?.id ?? null;
}

// resolveVoiceChoice keeps a saved choice for its own family, and falls back to
// the family default when the saved choice serves only the other family.
function resolveVoiceChoice(
  metadata: VoiceChoicesResp,
  transport: VoiceTransport,
  selected: string | null,
  speech: SpeechSelection | null,
): string | null {
  const compatible = compatibleChoices(metadata.choices, transport, speech);
  if (selected !== null) {
    if (!metadata.choices.some((choice) => choice.id === selected))
      throw new Error("The selected voice choice is unavailable. Choose another voice choice.");
    if (compatible.some((choice) => choice.id === selected)) return selected;
  }
  const fallback = defaultChoiceID(metadata, transport);
  const choice = compatible.find((choice) => choice.id === fallback) ?? compatible[0];
  return choice?.id ?? null;
}

// VoiceSession

function newConversationID(): string {
  // getRandomValues also works in HTTP-hosted native shells.
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

// One transport attempt owns resources until replacement, disconnect, or failure.
class VoiceConnection {
  readonly lifetime = new AbortController();
  pc: RTCPeerConnection | null = null;
  channel: VoiceChannel | null = null;
  speech: BrowserSpeech | null = null;
  readonly speechBuffer = new BrowserSpeechBuffer();
  microphone: MediaStream | null = null;
  outgoing: ReturnType<MicrophoneInput["connect"]> | null = null;
  speaker: HTMLAudioElement | null = null;
  rtc: { sessionID: string; service: ServiceAuthorization | null } | null = null;
  offerSDP = "";
  answerSDP = "";
  setupTimer: ReturnType<typeof setTimeout> | null = null;
  reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(readonly gateway: GatewaySnapshot) {}

  clearSetupTimer(): void {
    if (this.setupTimer !== null) clearTimeout(this.setupTimer);
    this.setupTimer = null;
  }

  setSetupTimer(callback: () => void): void {
    this.clearSetupTimer();
    if (!this.lifetime.signal.aborted) this.setupTimer = setTimeout(callback, SETUP_TIMEOUT_MS);
  }

  dispose(): void {
    this.lifetime.abort();
    this.clearSetupTimer();
    if (this.reconnectTimer !== null) clearTimeout(this.reconnectTimer);
    this.speaker?.pause();
    if (this.speaker !== null) this.speaker.srcObject = null;
    this.speechBuffer.clear();
    this.speech?.close();
    if (this.channel instanceof WebSocket) this.channel.close(1000);
    else this.channel?.close();
    this.pc?.close();
    this.outgoing?.close();
    this.microphone?.getTracks().forEach((track) => track.stop());
    this.channel = null;
    this.pc = null;
    this.speech = null;
    this.speaker = null;
    this.outgoing = null;
    this.microphone = null;
    this.offerSDP = "";
    this.answerSDP = "";
    this.reconnectTimer = null;
    if (this.rtc !== null) {
      void closeVoiceGatewaySession(this.gateway, this.rtc.sessionID, this.rtc.service).catch((error: unknown) => {
        console.warn("Could not close voice gateway session", error);
      });
      this.rtc = null;
    }
  }
}

export class VoiceSession {
  private readonly _microphoneInput = new MicrophoneInput();
  readonly state: VoiceState;
  /** Public store updater (also the seam tests use to arrange session state). */
  readonly setState: (fn: (s: VoiceState) => VoiceState) => void;

  private _connection: VoiceConnection | null = null;
  // User-gesture audio activation also exists while no transport is connected.
  private _audioAccess = new AbortController();
  private _choicesVersion = -1;
  private _choicesRequest: { version: number; promise: Promise<void> } | null = null;
  private _choicesAttempt = 0;
  private _sessionGateway: GatewaySnapshot | null = null;
  private _sessionChoiceID: string | null = null;
  private _sessionComposable = false;
  private _sessionSpeechModelIDs: Record<SpeechStage, string | null> = { input: null, output: null };
  private _sessionVoiceName: string | null = null;
  private readonly _conversationID: () => string;
  private readonly _setConversationID: (value: string) => string;

  /** Identity shared by setup, tools, and resource subscriptions in this conversation. */
  get conversationID(): string {
    return this._conversationID();
  }
  private _frontendTools: FrontendVoiceToolsSnapshot | null = null;
  private _browserTurnIdle = false;
  private _browserUserSpeechActive = false;
  // The cancelled generation can still report idle after its interruption.
  // Admit output again only when the next submitted user turn starts thinking.
  private _browserCancellation: "none" | "pending" | "acknowledged" = "none";
  private readonly _microphoneMeter = new MicrophoneLevel(
    (level) =>
      this._update((s) => {
        s.micLevel = level;
      }),
    (error) =>
      this._update((s) => {
        s.micLevelError = error;
      }),
  );
  /** The <audio> element playing remote RTP audio, stored so we can call setSinkId(). */
  /** True while the model is speaking — injected text is buffered and flushed after the turn ends. */
  private _speakerActive = false;
  /** Text notifications buffered while the model is speaking; flushed on turn end. */
  private _pendingNotifications: Array<{ text: string; generate: boolean }> = [];
  private _pendingServiceContext: string | null = null;
  private _reconnectEnabled = false;
  private _reconnectAttempts = 0;
  private _recoveryContext = "";
  private readonly _audioRoutes: AudioRoutes;
  private _voiceModeActive = false;
  private _callMetadata: MediaMetadata | null = null;
  private readonly _chime: VoiceChimePlayer;
  private readonly _thinkingEarcon: ThinkingEarcon;

  constructor(chime?: VoiceChimePlayer, thinkingEarcon?: ThinkingEarcon) {
    let setup: VoiceSetupPreference | null = null;
    try {
      setup = savedSetup(gatewaySnapshot().url.origin);
    } catch (error) {
      console.warn("Could not load the voice setup", error);
    }
    const [conversationID, setConversationID] = createSignal(newConversationID());
    this._conversationID = conversationID;
    this._setConversationID = setConversationID;
    const [state, setState] = createStore<VoiceState>({
      setupMode: setup?.mode ?? "parts",
      speechInput: setup?.mode === "all-in-one" ? "gateway" : (setup?.partsInput ?? savedSpeechLocation("Input")),
      speechOutput: setup?.mode === "all-in-one" ? "gateway" : (setup?.partsOutput ?? savedSpeechLocation("Output")),
      choices: [],
      speechModels: { input: [], output: [] },
      speechModelIDs: { input: setup?.recognitionModelID ?? null, output: setup?.synthesisModelID ?? null },
      choiceIDs: {
        text: setup?.partsChoiceID ?? null,
        webrtc: setup?.mode === "all-in-one" ? setup.nativeChoiceID : (setup?.partsChoiceID ?? null),
      },
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
      microphoneMode: "listening",
      activeTool: null,
      toolHistory: [],
      turnState: TurnStateIdle,
      transcript: [],
      usage: null,
      transcriptDraft: null,
      showReasoning: savedReasoning(),
      showUsageStats: savedUsageStats(),
      micLevel: 0,
      micLevelError: null,
      error: null,
      errorHistory: [],
      audioInputs: [],
      audioDevicesError: null,
      audioInputError: null,
      microphoneAccess: null,
      microphoneAccessPending: false,
      audioPlaybackError: null,
      audioOutputError: null,
      audioSwitching: null,
      audioOutputs: [],
      selectedInputId: "",
      selectedOutputId: "",
      languageTag: savedVoiceLanguage(),
      browserVoiceURI: savedBrowserVoiceURI(),
      browserSpeechRate: savedBrowserSpeechRate(),
    });
    this.state = state;
    this._audioRoutes = new AudioRoutes({
      state,
      update: (change) => this._update(change),
      connection: () => this._connection,
      input: this._microphoneInput,
      microphoneActive: () => this._microphoneActive(),
      microphoneMuted: () => microphoneMuted(this.state),
      monitorMicrophone: (stream) => this._monitorMicrophone(stream),
      microphoneDenied: () => this._microphoneDenied(),
    });
    this.setState = setState;
    this._chime = chime ?? new WebAudioVoiceChime();
    this._thinkingEarcon = thinkingEarcon ?? new WebAudioThinkingEarcon();
  }

  // -----------------------------------------------------------------------
  // Public API
  // -----------------------------------------------------------------------

  /** Reactive gateway identity for settings; configuring a host does not start network requests. */
  readonly gatewayIdentity = gatewayVersion;

  get speech(): SpeechSelection {
    return { input: this.state.speechInput, output: this.state.speechOutput };
  }

  get transport(): VoiceTransport {
    return transportFor(this.speech);
  }

  get compatibleChoices(): VoiceChoice[] {
    return compatibleChoices(setupChoices(this.state.choices, this.state.setupMode), this.transport, this.speech);
  }

  get composableChoice(): boolean {
    return this.state.choices.find((choice) => choice.id === this.state.choiceIDs[this.transport])?.composable === true;
  }

  /** Available native models or independently selectable language models. */
  choicesForSetup(mode: VoiceSetupMode): VoiceChoice[] {
    return setupChoices(this.state.choices, mode);
  }

  private _setupPreference(): VoiceSetupPreference {
    const origin = gatewaySnapshot().url.origin;
    const saved = savedSetup(origin);
    if (this._choicesVersion !== untrack(gatewayVersion))
      return (
        saved ?? {
          mode: "parts",
          nativeChoiceID: null,
          partsChoiceID:
            localStorage.getItem(`gomode.voiceModel.${origin}`) ??
            localStorage.getItem(choiceStorageKey(origin, "text")),
          partsInput: savedSpeechLocation("Input"),
          partsOutput: savedSpeechLocation("Output"),
          recognitionModelID: localStorage.getItem(speechModelStorageKey(origin, "input")),
          synthesisModelID: localStorage.getItem(speechModelStorageKey(origin, "output")),
        }
      );
    const preference: VoiceSetupPreference = saved ?? {
      mode: this.state.setupMode,
      nativeChoiceID:
        this.state.setupMode === "all-in-one"
          ? this.state.choiceIDs.webrtc
          : (this.choicesForSetup("all-in-one")[0]?.id ?? null),
      partsChoiceID:
        this.state.setupMode === "all-in-one" ? this.state.choiceIDs.text : this.state.choiceIDs[this.transport],
      partsInput: savedSpeechLocation("Input"),
      partsOutput: savedSpeechLocation("Output"),
      recognitionModelID: this.state.speechModelIDs.input,
      synthesisModelID: this.state.speechModelIDs.output,
    };
    preference.mode = this.state.setupMode;
    if (preference.mode === "all-in-one") preference.nativeChoiceID = this.state.choiceIDs.webrtc;
    else {
      preference.partsChoiceID = this.state.choiceIDs[this.transport];
      preference.partsInput = this.state.speechInput;
      preference.partsOutput = this.state.speechOutput;
      preference.recognitionModelID = this.state.speechModelIDs.input;
      preference.synthesisModelID = this.state.speechModelIDs.output;
    }
    return preference;
  }

  // Compute every effective field before saving or publishing any of them. One
  // storage operation keeps mode switches atomic, including a throwing storage API.
  private _applySetup(preference: VoiceSetupPreference, persist: boolean): void {
    const speech =
      preference.mode === "all-in-one"
        ? { input: "gateway" as const, output: "gateway" as const }
        : { input: preference.partsInput, output: preference.partsOutput };
    const transport = transportFor(speech);
    const idKey = preference.mode === "all-in-one" ? "nativeChoiceID" : "partsChoiceID";
    const choices = setupChoices(this.state.choices, preference.mode);
    let id = preference[idKey];
    let failure: string | null = null;
    const compatible = compatibleChoices(choices, transport, speech);
    if (
      id !== null &&
      this.state.choices.some((choice) => choice.id === id) &&
      !choices.some((choice) => choice.id === id)
    )
      id = null;
    if (id === null) id = compatible[0]?.id ?? null;
    const choice = choices.find((choice) => choice.id === id);
    if (this.state.choicesStatus === "ready") {
      if (id !== null && !choice) failure = "The selected voice choice is unavailable. Choose another voice choice.";
      else if (choice && !compatible.some((candidate) => candidate.id === id))
        failure = "This model does not support the selected recognition and speech output.";
      else if (id === null) failure = "The voice gateway has no model for this setup. Choose another setup.";
    }
    preference[idKey] = id;
    const models = { input: preference.recognitionModelID, output: preference.synthesisModelID };
    if (choice?.composable) {
      for (const stage of ["input", "output"] as const) {
        if (speech[stage] !== "gateway") continue;
        try {
          models[stage] = resolveSpeechModel(this.state.speechModels[stage], models[stage], stage);
        } catch (error) {
          failure ??= error instanceof Error ? error.message : String(error);
        }
      }
      preference.recognitionModelID = models.input;
      preference.synthesisModelID = models.output;
    }
    const origin = gatewaySnapshot().url.origin;
    let name: string | null = null;
    let voiceError: string | null = null;
    let voiceStatus: VoiceState["voiceStatus"] = "ready";
    try {
      name = choice ? savedSpeakingVoice(origin, choice, speech.output) : null;
      voiceError = choice && name !== null && speech.output === "gateway" ? speakingVoiceError(choice, name) : null;
    } catch (error) {
      if (persist) throw error;
      voiceError = error instanceof Error ? error.message : String(error);
      voiceStatus = "error";
    }
    if (persist) localStorage.setItem(setupStorageKey(origin), JSON.stringify(preference));
    this.setState((s) => ({
      ...s,
      setupMode: preference.mode,
      speechInput: speech.input,
      speechOutput: speech.output,
      choiceIDs: {
        text: preference.partsChoiceID,
        webrtc: preference.mode === "all-in-one" ? preference.nativeChoiceID : preference.partsChoiceID,
      },
      speechModelIDs: models,
      choicesError: failure,
      voiceName: name,
      voiceError,
      voiceStatus,
    }));
  }

  /** Switch the effective setup while remembering the other setup's selections. */
  selectSetupMode(mode: VoiceSetupMode): void {
    if (this.state.connected || this.state.connectStatus !== null)
      throw new Error("End the session before changing voice setup");
    if (mode !== "all-in-one" && mode !== "parts") throw new Error("Invalid voice setup");
    if (this._choicesVersion !== untrack(gatewayVersion) || this.state.choicesStatus !== "ready")
      throw new Error("Load voice choices before selecting a setup");
    const choices = setupChoices(this.state.choices, mode);
    if (choices.length === 0) throw new Error("This voice setup is unavailable. Choose another setup.");
    const next = this._setupPreference();
    next.mode = mode;
    const key = mode === "all-in-one" ? "nativeChoiceID" : "partsChoiceID";
    if (next[key] === null) next[key] = choices[0]?.id ?? null;
    this._applySetup(next, true);
  }

  /** Select a browser stage or one configured gateway model as one saved change. */
  selectSpeechStage(stage: SpeechStage, location: SpeechLocation, modelID: string | null): void {
    if (this.state.connected || this.state.connectStatus !== null)
      throw new Error("End the session before changing speech settings");
    if (location !== "client" && location !== "gateway") throw new Error("Invalid speech location");
    if (location === "client" && !(stage === "input" ? browserRecognitionAvailable() : browserSynthesisAvailable()))
      throw new Error(
        `Browser ${stage === "input" ? "recognition" : "speech output"} is unavailable. Use a configured model.`,
      );
    if (modelID !== null && !this.state.speechModels[stage].some((model) => model.id === modelID))
      throw new Error("Choose an available speech model");
    const next = this._setupPreference();
    next.mode = "parts";
    if (stage === "input") {
      next.partsInput = location;
      if (modelID !== null) next.recognitionModelID = modelID;
    } else {
      next.partsOutput = location;
      if (modelID !== null) next.synthesisModelID = modelID;
    }
    this._applySetup(next, true);
  }

  /** Save one gateway speech stage independently of the selected language model. */
  selectSpeechModel(stage: SpeechStage, id: string): void {
    if (
      this._choicesVersion !== untrack(gatewayVersion) ||
      this.state.choicesStatus !== "ready" ||
      !this.composableChoice ||
      this.speech[stage] !== "gateway"
    )
      throw new Error("Load an available gateway speech stage before selecting its model");
    this.selectSpeechStage(stage, "gateway", id);
  }

  get speakingVoices(): string[] {
    if (this.state.speechOutput !== "gateway") return [];
    return this.state.choices.find((choice) => choice.id === this.state.choiceIDs.webrtc)?.voices ?? [];
  }

  /** Save a supported speaking voice for this gateway and backend choice. */
  selectSpeakingVoice(name: string): void {
    if (this.state.connected || this.state.connectStatus !== null)
      throw new Error("End the session before changing speaking voice");
    if (
      this._choicesVersion !== untrack(gatewayVersion) ||
      this.state.choicesStatus !== "ready" ||
      this.state.voiceStatus !== "ready" ||
      (name !== "" && !this.speakingVoices.includes(name))
    )
      throw new Error("Choose an available speaking voice");
    const choiceID = this.state.choiceIDs.webrtc;
    if (choiceID === null) throw new Error("Choose a voice model first");
    localStorage.setItem(speakingVoiceStorageKey(gatewaySnapshot().url.origin, choiceID), name);
    this._update((s) => {
      s.voiceName = name;
      s.voiceError = null;
    });
  }

  /** Discover settings choices on demand, keeping obsolete responses out of the current gateway. */
  async loadVoiceChoices(): Promise<void> {
    const snapshot = gatewaySnapshot();
    if (this.state.connected || this.state.connectStatus !== null) return;
    if (this._choicesRequest?.version === snapshot.version) {
      await this._choicesRequest.promise;
      if (snapshot.version === untrack(gatewayVersion) && this.state.choicesStatus === "idle")
        return this.loadVoiceChoices();
      return;
    }
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
      const setup = savedSetup(snapshot.url.origin);
      const speechModelIDs = {
        input: setup?.recognitionModelID ?? localStorage.getItem(speechModelStorageKey(snapshot.url.origin, "input")),
        output: setup?.synthesisModelID ?? localStorage.getItem(speechModelStorageKey(snapshot.url.origin, "output")),
      };
      if (this._choicesVersion !== snapshot.version) {
        const choiceIDs = {
          text: savedVoiceChoice(snapshot.url.origin, "text"),
          webrtc: savedVoiceChoice(snapshot.url.origin, "webrtc"),
        };
        const setup = savedSetup(snapshot.url.origin);
        this._choicesVersion = snapshot.version;
        this._update((s) => {
          s.setupMode = setup?.mode ?? "parts";
          s.speechInput =
            setup?.mode === "all-in-one" ? "gateway" : (setup?.partsInput ?? savedSpeechLocation("Input"));
          s.speechOutput =
            setup?.mode === "all-in-one" ? "gateway" : (setup?.partsOutput ?? savedSpeechLocation("Output"));
          s.choices = [];
          s.speechModels = { input: [], output: [] };
          s.speechModelIDs = speechModelIDs;
          s.choiceIDs = choiceIDs;
        });
      }
      const metadata = validateVoiceChoices(await snapshot.api.voiceChoices());
      if (snapshot.version !== untrack(gatewayVersion) || attempt !== this._choicesAttempt) return;
      if (this.state.connected || this.state.connectStatus !== null) {
        // Session admission owns selection until it succeeds or is released.
        // Idle lets inactive settings retry the discarded request exactly once.
        this._update((s) => {
          s.choicesStatus = "idle";
        });
        return;
      }
      this._update((s) => {
        s.choices = metadata.choices;
        s.speechModels = { input: metadata.recognitionModels ?? [], output: metadata.synthesisModels ?? [] };
        s.speechModelIDs = speechModelIDs;
        s.choicesStatus = "ready";
      });
      this._resolveFamilies(snapshot.url.origin, metadata);
    } catch (error) {
      if (snapshot.version !== untrack(gatewayVersion) || attempt !== this._choicesAttempt) return;
      this._update((s) => {
        s.choicesStatus = "error";
        s.choicesError = error instanceof Error ? error.message : String(error);
      });
    }
  }

  /** Restore the saved setup, retaining unavailable selections for explicit recovery. */
  private _resolveFamilies(origin: string, metadata: VoiceChoicesResp): void {
    let preference = savedSetup(origin);
    if (preference === null) {
      const selected = this.state.choiceIDs[this.transport] ?? savedVoiceChoice(origin, this.transport);
      const initial = metadata.choices.find(
        (choice) => choice.id === (selected ?? defaultChoiceID(metadata, this.transport)),
      );
      const mode: VoiceSetupMode =
        initial && nativeVoiceChoice(initial)
          ? "all-in-one"
          : setupChoices(metadata.choices, "parts").length > 0
            ? "parts"
            : "all-in-one";
      preference = {
        mode,
        nativeChoiceID:
          initial && nativeVoiceChoice(initial)
            ? initial.id
            : (setupChoices(metadata.choices, "all-in-one")[0]?.id ?? null),
        partsChoiceID:
          initial && !nativeVoiceChoice(initial)
            ? initial.id
            : mode === "parts"
              ? selected
              : localStorage.getItem(choiceStorageKey(origin, "text")),
        partsInput: this.state.speechInput,
        partsOutput: this.state.speechOutput,
        recognitionModelID: this.state.speechModelIDs.input,
        synthesisModelID: this.state.speechModelIDs.output,
      };
      if (preference.partsChoiceID === null) {
        const parts = setupChoices(metadata.choices, "parts");
        preference.partsChoiceID =
          parts.find((choice) => choice.id === defaultChoiceID(metadata, "text"))?.id ?? parts[0]?.id ?? null;
      }
      // Fixed presets are omitted when named language models are available.
      const partsChoiceID = preference.partsChoiceID;
      if (
        partsChoiceID !== null &&
        metadata.choices.some((choice) => choice.id === partsChoiceID) &&
        !setupChoices(metadata.choices, "parts").some((choice) => choice.id === partsChoiceID)
      )
        preference.partsChoiceID = null;
    }
    this._applySetup(preference, false);
  }

  /** Persist a model only after validating it against the current setup. */
  selectChoice(choiceID: string): void {
    if (this.state.connected || this.state.connectStatus !== null)
      throw new Error("End the session before changing voice choice");
    if (this._choicesVersion !== untrack(gatewayVersion) || this.state.choicesStatus !== "ready")
      throw new Error("Load voice choices before selecting one");
    const choice = setupChoices(this.state.choices, this.state.setupMode).find(
      (candidate) => candidate.id === choiceID,
    );
    if (!choice) throw new Error("Choose an available voice choice");
    const next = this._setupPreference();
    next.mode = nativeVoiceChoice(choice) ? "all-in-one" : "parts";
    if (next.mode === "all-in-one") next.nativeChoiceID = choiceID;
    else next.partsChoiceID = choiceID;
    this._applySetup(next, true);
  }

  private async _resolveSessionChoice(attempt: VoiceConnection): Promise<string | null> {
    const snapshot = attempt.gateway;
    const metadata = validateVoiceChoices(await snapshot.api.voiceChoices());
    if (attempt !== this._connection) return null;
    if (snapshot.version === untrack(gatewayVersion)) {
      // A connect-time refresh owns publication over an older settings request.
      this._choicesAttempt++;
      this._choicesVersion = snapshot.version;
      const transport = this.transport;
      this._update((s) => {
        s.choices = metadata.choices;
        s.speechModels = { input: metadata.recognitionModels ?? [], output: metadata.synthesisModels ?? [] };
        s.choiceIDs = { ...s.choiceIDs, [transport]: this._sessionChoiceID };
        s.speechModelIDs = { ...this._sessionSpeechModelIDs };
        s.choicesStatus = "ready";
        s.choicesError = null;
      });
    }
    let choiceID: string | null;
    try {
      const stored = savedSetup(snapshot.url.origin);
      const selected = metadata.choices.find(
        (choice) => choice.id === (this._sessionChoiceID ?? defaultChoiceID(metadata, this.transport)),
      );
      if (stored === null && selected && nativeVoiceChoice(selected) && this.transport === "webrtc")
        this._update((s) => {
          s.setupMode = "all-in-one";
          s.speechInput = "gateway";
          s.speechOutput = "gateway";
        });
      const choices = setupChoices(metadata.choices, this.state.setupMode);
      const previous =
        metadata.choices.some((choice) => choice.id === this._sessionChoiceID) &&
        !choices.some((choice) => choice.id === this._sessionChoiceID)
          ? null
          : this._sessionChoiceID;
      choiceID = resolveVoiceChoice({ ...metadata, choices }, this.transport, previous, this.speech);
    } catch (error) {
      if (snapshot.version === untrack(gatewayVersion))
        this._update((s) => {
          s.choicesError = error instanceof Error ? error.message : String(error);
        });
      throw error;
    }
    if (choiceID === null)
      throw new Error("The voice gateway has no model for the selected recognition and speech output");
    this._sessionChoiceID = choiceID;
    const choice = metadata.choices.find((choice) => choice.id === choiceID);
    if (!choice) throw new Error("Selected voice model is no longer available");
    this._sessionComposable = choice.composable === true;
    for (const stage of ["input", "output"] as const) {
      if (this._sessionComposable && this.speech[stage] === "gateway")
        this._sessionSpeechModelIDs[stage] = resolveSpeechModel(
          stage === "input" ? (metadata.recognitionModels ?? []) : (metadata.synthesisModels ?? []),
          this._sessionSpeechModelIDs[stage],
          stage,
        );
    }
    if (snapshot.version === untrack(gatewayVersion))
      this._update((s) => {
        for (const stage of ["input", "output"] as const) {
          if (this._sessionSpeechModelIDs[stage] !== null) s.speechModelIDs[stage] = this._sessionSpeechModelIDs[stage];
        }
      });
    let voiceError: string | null = null;
    try {
      const name =
        this.state.speechOutput !== "gateway"
          ? ""
          : (this._sessionVoiceName ?? savedSpeakingVoice(snapshot.url.origin, choice, this.state.speechOutput));
      this._sessionVoiceName = name;
      voiceError = this.state.speechOutput === "gateway" ? speakingVoiceError(choice, name) : null;
      if (snapshot.version === untrack(gatewayVersion))
        this._update((s) => {
          s.choiceIDs = { ...s.choiceIDs, [this.transport]: choiceID };
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

  /** Save recognition ownership for the next session. */
  selectSpeechInput(location: SpeechLocation): void {
    this._selectSpeech("Input", location);
  }

  /** Save synthesis ownership for the next session. */
  selectSpeechOutput(location: SpeechLocation): void {
    this._selectSpeech("Output", location);
  }

  private _selectSpeech(stage: "Input" | "Output", location: SpeechLocation): void {
    this.selectSpeechStage(stage === "Input" ? "input" : "output", location, null);
  }

  /** Save the browser's speaking voice independently of gateway voices. */
  selectBrowserVoice(uri: string): void {
    if (this.state.connected || this.state.connectStatus !== null)
      throw new Error("End the session before changing speaking voice");
    if (
      uri !== "" &&
      !window.speechSynthesis
        .getVoices()
        .some((voice) => voice.voiceURI === uri && browserVoiceMatchesLanguage(voice, this.state.languageTag))
    )
      throw new Error("Choose an available speaking voice");
    localStorage.setItem("gomode.browserVoiceURI", uri);
    this.setState((s) => ({ ...s, browserVoiceURI: uri }));
  }

  /** Save browser synthesis speed for the next session. */
  selectBrowserSpeechRate(rate: number): void {
    if (this.state.connected || this.state.connectStatus !== null)
      throw new Error("End the session before changing speaking speed");
    validateBrowserSpeechRate(rate);
    localStorage.setItem("gomode.browserSpeechRate", String(rate));
    this.setState((s) => ({ ...s, browserSpeechRate: rate }));
  }

  /** Save the language for the next session. An active session keeps its language. */
  selectLanguage(tag: string): void {
    if (this.state.connected || this.state.connectStatus !== null)
      throw new Error("End the session before changing language");
    const normalized = normalizeVoiceLanguage(tag);
    localStorage.setItem("gomode.voiceLanguage", normalized);
    this.setState((s) => ({ ...s, languageTag: normalized }));
  }

  /** Control reasoning display immediately and request provider reasoning on the next connection. */
  setShowReasoning(show: boolean): void {
    localStorage.setItem("gomode.voiceReasoning", show ? "on" : "off");
    this.setState((s) => ({ ...s, showReasoning: show }));
  }

  /** Save whether the overlay displays reported usage, independently of collection. */
  setShowUsageStats(show: boolean): void {
    localStorage.setItem("gomode.voiceUsageStats", show ? "on" : "off");
    this.setState((s) => ({ ...s, showUsageStats: show }));
  }

  /** Enumerate audio devices and apply the route owner's arrival policy. */
  enumerateDevices(): Promise<void> {
    return this._audioRoutes.enumerateDevices();
  }

  /** Select an input immediately or queue it behind the current replacement. */
  selectInputDevice(deviceId: string): Promise<void> {
    return this._audioRoutes.selectInputDevice(deviceId);
  }

  private _microphoneActive(): boolean {
    return !microphoneMuted(this.state) && (this.state.speechInput === "gateway" || this.state.listening);
  }

  private _monitorMicrophone(stream: MediaStream): void {
    this._microphoneMeter.start(
      stream,
      () => (this._connection?.microphone ?? null) === stream && this._microphoneActive(),
    );
  }

  /** Resume analysis from a user activation if browser audio policy paused it. */
  enableMicrophoneMeter(): Promise<void> {
    const signal = this._audioAccess.signal;
    const activation = this._microphoneMeter.prepare();
    const processing =
      this.state.speechInput === "gateway"
        ? this._microphoneInput.prepare().catch((error: unknown) => {
            if (!signal.aborted)
              this._update((s) => {
                s.error = `Could not activate microphone processing: ${String(error)}`;
              });
          })
        : Promise.resolve();
    const microphone = this._connection?.microphone;
    if (microphone) this._monitorMicrophone(microphone);
    return Promise.all([activation, processing]).then(() => undefined);
  }

  /** Select an output immediately or queue it behind the current replacement. */
  selectOutputDevice(deviceId: string): Promise<void> {
    return this._audioRoutes.selectOutputDevice(deviceId);
  }

  private _restoreColdSetup(snapshot: GatewaySnapshot): VoiceSetupPreference | null {
    if (this._choicesVersion === snapshot.version && this.state.choicesStatus === "ready") return null;
    const setup = savedSetup(snapshot.url.origin);
    this.setState((s) => ({
      ...s,
      setupMode: setup?.mode ?? "parts",
      speechInput: setup?.mode === "all-in-one" ? "gateway" : (setup?.partsInput ?? savedSpeechLocation("Input")),
      speechOutput: setup?.mode === "all-in-one" ? "gateway" : (setup?.partsOutput ?? savedSpeechLocation("Output")),
      choiceIDs: {
        text: setup?.partsChoiceID ?? null,
        webrtc: setup?.mode === "all-in-one" ? setup.nativeChoiceID : (setup?.partsChoiceID ?? null),
      },
    }));
    return setup;
  }

  /** Unlock chime playback while a browser user-activation event is still active. */
  prepareAudio(): void {
    if (!this.state.connected && this.state.connectStatus === null) {
      try {
        this._restoreColdSetup(gatewaySnapshot());
      } catch (error) {
        this._setError(error instanceof Error ? error.message : String(error));
        return;
      }
    }
    if (
      (this.state.speechInput === "client" && !browserRecognitionAvailable()) ||
      (this.state.speechOutput === "client" && !browserSynthesisAvailable())
    )
      return;
    void this.enableMicrophoneMeter();
    if (this.state.speechOutput === "client" && browserSpeechAvoidsChimes()) {
      window.speechSynthesis.resume();
      return;
    }
    this._chime.prepare();
    if (this.state.speechOutput === "client") this._thinkingEarcon.prepare();
  }

  /** Observe permission changes without prompting. The overlay owns this subscription. */
  observeMicrophoneAccess(): () => void {
    let disposed = false;
    let permission: PermissionStatus | null = null;
    const update = () => {
      if (disposed || permission === null) return;
      const access = permission.state;
      if (
        access === "denied" &&
        this.state.microphoneAccess !== "denied" &&
        (this._connection !== null || this.state.connectStatus !== null)
      ) {
        this.disconnect();
      }
      this._update((s) => {
        s.microphoneAccess = access;
      });
    };
    if (navigator.permissions) {
      void navigator.permissions
        .query({ name: "microphone" })
        .then((status) => {
          if (disposed) return;
          permission = status;
          permission.addEventListener("change", update);
          update();
        })
        .catch((error: unknown) => {
          // Some browsers expose Permissions but do not implement microphone queries.
          if (!(error instanceof TypeError)) console.warn("Could not read microphone permission", error);
        });
    }
    window.addEventListener("focus", update);
    return () => {
      disposed = true;
      permission?.removeEventListener("change", update);
      window.removeEventListener("focus", update);
    };
  }

  /** A user gesture can request access without starting voice or retaining a microphone stream. */
  async requestMicrophoneAccess(): Promise<void> {
    if (this.state.microphoneAccessPending) return;
    const signal = this._audioAccess.signal;
    this._update((s) => {
      s.microphoneAccessPending = true;
    });
    try {
      if (!navigator.mediaDevices?.getUserMedia)
        throw new Error("Microphone access requires HTTPS and a supported browser.");
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      stream.getTracks().forEach((track) => track.stop());
      if (signal.aborted) return;
      this._update((s) => {
        s.microphoneAccess = "granted";
        s.error = null;
      });
      await this.enumerateDevices();
    } catch (error) {
      if (signal.aborted) return;
      if (error instanceof DOMException && error.name === "NotAllowedError") this._microphoneDenied();
      else
        this._update((s) => {
          s.error = `Could not enable microphone: ${String(error)}`;
        });
    } finally {
      this._update((s) => {
        s.microphoneAccessPending = false;
      });
    }
  }

  private _microphoneDenied(): void {
    this.disconnect();
    this._update((s) => {
      s.microphoneAccess = "denied";
      s.error = null;
    });
  }

  /** Retry playback directly from a user gesture after browser autoplay restrictions. */
  async resumeAudio(): Promise<void> {
    const audio = this._connection?.speaker ?? null;
    if (audio === null) return;
    try {
      await audio.play();
      if ((this._connection?.speaker ?? null) === audio)
        this._update((s) => {
          s.audioPlaybackError = null;
        });
    } catch (error) {
      if ((this._connection?.speaker ?? null) === audio)
        this._update((s) => {
          s.audioPlaybackError = `Sound could not start: ${String(error)}`;
        });
    }
  }

  /** Start a new voice session via WebRTC data channel through the host backend. */
  async connect(): Promise<void> {
    this._sessionGateway?.preparation?.retireSession(this.conversationID);
    this._frontendTools = captureFrontendVoiceTools();
    this._setConversationID(newConversationID());
    this._sessionGateway = gatewaySnapshot();
    this._sessionVoiceName = null;
    try {
      const catalogReady =
        this._choicesVersion === this._sessionGateway.version && this.state.choicesStatus === "ready";
      const setup = this._restoreColdSetup(this._sessionGateway);
      this.prepareAudio();
      for (const stage of ["input", "output"] as const) {
        this._sessionSpeechModelIDs[stage] = catalogReady
          ? this.state.speechModelIDs[stage]
          : ((stage === "input" ? setup?.recognitionModelID : setup?.synthesisModelID) ??
            localStorage.getItem(speechModelStorageKey(this._sessionGateway.url.origin, stage)));
      }
      this._sessionChoiceID = catalogReady
        ? this.state.choiceIDs[this.transport]
        : savedVoiceChoice(this._sessionGateway.url.origin, this.transport);
      if (catalogReady) {
        const choice = this.state.choices.find((choice) => choice.id === this._sessionChoiceID);
        if (choice && (this.state.speechOutput !== "gateway" || choice.voices?.length))
          this._sessionVoiceName = savedSpeakingVoice(this._sessionGateway.url.origin, choice, this.state.speechOutput);
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

  private async _prepareHostSession(attempt: VoiceConnection): Promise<boolean> {
    const snapshot = attempt.gateway;
    const signal = attempt.lifetime.signal;
    if (signal.aborted || attempt !== this._connection) return false;
    try {
      await snapshot.preparation?.prepareSession(this.conversationID, signal);
    } catch (error) {
      if (signal.aborted || attempt !== this._connection) return false;
      if (error instanceof DOMException && error.name === "AbortError") {
        this.disconnect();
        return false;
      }
      // Selected-item context is advisory; an ordinary publication failure must
      // not prevent the otherwise independent voice transport from starting.
      // Publication diagnostics and recovery belong to the preparation owner.
    }
    return !signal.aborted && attempt === this._connection;
  }

  private async _connect(preserveTranscript: boolean): Promise<void> {
    if (this.transport === "text") {
      await this._connectBrowser(preserveTranscript);
      return;
    }
    if (
      (this.state.speechInput === "client" && !browserRecognitionAvailable()) ||
      (this.state.speechOutput === "client" && !browserSynthesisAvailable())
    ) {
      this._setError("Browser recognition or speech output is unavailable. Use the gateway.");
      return;
    }
    if (!this._sessionGateway) {
      this._setError("Voice gateway session is unavailable");
      return;
    }
    const attempt = new VoiceConnection(this._sessionGateway);
    this._replaceConnection(attempt, true);
    if (this._connection !== attempt) return;
    if (!preserveTranscript) {
      this._update((s) => {
        s.usage = null;
      });
      this._reconnectAttempts = 0;
      this._recoveryContext = "";
      this._clearTranscript();
    }
    this._setStatus("setup", "Setting up WebRTC…");

    try {
      const snapshot = attempt.gateway;
      if (!(await this._prepareHostSession(attempt))) return;
      const choiceID = await this._resolveSessionChoice(attempt);
      if (choiceID === null || this._connection !== attempt) return;
      const [systemInstruction, mcpTools, serviceItemsText] = await Promise.all([
        mcpClient.serverInstructions({ sessionID: this.conversationID }),
        mcpClient.listTools({ sessionID: this.conversationID }),
        // Initial service context is advisory. A resource failure must not make
        // the independent voice transport unavailable.
        mcpClient
          .readAdvertisedTextResource(GO_MODE_ITEMS_RESOURCE_URI, undefined, { sessionID: this.conversationID })
          .catch(() => null),
      ]);
      if (attempt !== this._connection) return;
      // This client owns the bounded session baseline and refreshes it on every
      // reconnect; see gomode/docs/ANDROID_SHELL.md#service-item-voice-context-ownership.
      const serviceContext = initialServiceContext(serviceItemsText);

      // Create PeerConnection.
      const pc = new RTCPeerConnection({
        iceServers: [{ urls: "stun:stun.l.google.com:19302" }],
      });
      attempt.pc = pc;

      if (this.state.speechInput === "gateway") await this._microphoneInput.prepare();
      if (attempt !== this._connection) return;
      // Browser AEC precedes outgoing attenuation and Opus encoding.
      const inputId = this.state.selectedInputId;
      const micConstraints: MediaStreamConstraints = {
        audio: this.state.speechInput === "gateway" && inputId ? { deviceId: { exact: inputId } } : true,
        video: false,
      };
      // Initial acquisition owns input routing too: devicechange must queue any
      // arrival until the first stream has been installed on the PeerConnection.
      const inputInstalled = this._audioRoutes.beginCapture();
      if (!navigator.mediaDevices?.getUserMedia)
        throw new Error("Microphone access requires HTTPS and a supported browser.");
      const micStream = await navigator.mediaDevices.getUserMedia(micConstraints);
      if (attempt !== this._connection) {
        micStream.getTracks().forEach((track) => track.stop());
        return;
      }
      attempt.microphone = micStream;
      this._update((s) => {
        s.microphoneAccess = "granted";
      });
      micStream.getAudioTracks().forEach((track) => {
        track.enabled = this._microphoneActive();
      });
      if (this.state.speechInput === "gateway") {
        attempt.outgoing = this._microphoneInput.connect(micStream);
        for (const track of attempt.outgoing.stream.getAudioTracks()) {
          track.enabled = this._microphoneActive();
          pc.addTrack(track, attempt.outgoing.stream);
        }
      } else {
        pc.addTransceiver("audio", { direction: "recvonly" });
      }
      inputInstalled(inputId);
      await this.enumerateDevices();
      if (attempt !== this._connection) return;
      const activeMicStream = attempt.microphone;
      if (activeMicStream === null) return;

      this._monitorMicrophone(activeMicStream);

      // Speaker audio from remote RTP track.
      pc.ontrack = (evt) => {
        if (attempt !== this._connection || this.state.speechOutput !== "gateway") return;
        const audio = new Audio();
        attempt.speaker = audio;
        audio.srcObject = evt.streams[0] ?? new MediaStream([evt.track]);
        this._audioRoutes.speakerAttached();
        void this.resumeAudio();
      };

      // Create data channel carrying the voice gateway control protocol.
      const dc = pc.createDataChannel("voice-gateway", { ordered: true });
      attempt.channel = dc;
      this._createBrowserSpeech(attempt, dc);

      dc.onmessage = (evt: MessageEvent<string>) => {
        if (!this._ownsChannel(attempt, dc)) return;
        this._handleMessage(evt.data, attempt, dc).catch((err: unknown) => {
          if (attempt === this._connection) {
            this._setError(err instanceof Error ? err.message : "Message handling failed");
          }
        });
      };

      dc.onopen = () => {
        if (!this._ownsChannel(attempt, dc)) return;
        this._reconnectAttempts = 0;
        this._setStatus("waiting", "Waiting for server…");
        this._sendSetup(mcpTools, systemInstruction, serviceContext);
      };

      dc.onclose = () => {
        if (!this._ownsChannel(attempt, dc)) return;
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
        } else if (st === "connected" && attempt.reconnectTimer !== null) {
          clearTimeout(attempt.reconnectTimer);
          attempt.reconnectTimer = null;
        } else if (st === "closed") {
          void this._setDiagnosticError(pc, `WebRTC ICE ${st}`);
        }
      };

      // SDP offer/answer exchange.
      this._setStatus("signaling", "Signaling…");
      const offer = await pc.createOffer();
      if (attempt !== this._connection) return;
      const offerSDP = await completeLocalOffer(pc, offer);
      if (attempt !== this._connection) return;
      attempt.offerSDP = offerSDP;
      const service = snapshot.serviceProvider === null ? null : await snapshot.serviceProvider();
      if (attempt !== this._connection) return;
      const request = service === null ? { sdp: offerSDP, choiceID } : { sdp: offerSDP, choiceID, service };
      const resp = await snapshot.api.voiceRTCOffer(request);
      if (attempt !== this._connection) {
        void closeVoiceGatewaySession(snapshot, resp.sessionID, service).catch((error: unknown) =>
          console.warn("Could not close cancelled voice session", error),
        );
        return;
      }
      attempt.rtc = { sessionID: resp.sessionID, service };
      attempt.answerSDP = resp.sdp;
      await pc.setRemoteDescription({ type: "answer", sdp: resp.sdp });
      if (attempt !== this._connection) return;
      this._startSetupTimer(pc);

      this._update((s) => {
        s.listening = true;
      });
    } catch (e: unknown) {
      if (attempt === this._connection) {
        if (e instanceof DOMException && e.name === "NotAllowedError") this._microphoneDenied();
        else this._setError(e instanceof Error ? e.message : "WebRTC connection failed");
      }
    }
  }

  private async _connectBrowser(preserveTranscript: boolean): Promise<void> {
    if (!this._sessionGateway) {
      this._setError("Voice gateway session is unavailable");
      return;
    }
    const attempt = new VoiceConnection(this._sessionGateway);
    this._replaceConnection(attempt, true);
    if (this._connection !== attempt) return;
    this._reconnectEnabled = false;
    this._speakerActive = false;
    this._pendingNotifications = [];
    this._pendingServiceContext = null;
    if (!preserveTranscript) {
      this._clearTranscript();
      this._update((s) => {
        s.usage = null;
      });
    }
    this._update((s) => {
      s.connected = false;
      s.speaking = false;
      s.activeTool = null;
      s.turnState = TurnStateIdle;
    });
    this._setStatus("setup", "Setting up browser speech…");
    if (!browserRecognitionAvailable() || !browserSynthesisAvailable()) {
      this._setError("Browser recognition or speech output is unavailable. Use the gateway.");
      return;
    }
    try {
      const snapshot = attempt.gateway;
      if (!(await this._prepareHostSession(attempt))) return;
      const choiceID = await this._resolveSessionChoice(attempt);
      if (choiceID === null || this._connection !== attempt) return;
      const [systemInstruction, tools, serviceItems, service] = await Promise.all([
        mcpClient.serverInstructions({ sessionID: this.conversationID }),
        mcpClient.listTools({ sessionID: this.conversationID }),
        mcpClient
          .readAdvertisedTextResource(GO_MODE_ITEMS_RESOURCE_URI, undefined, { sessionID: this.conversationID })
          .catch(() => null),
        snapshot.serviceProvider?.() ?? Promise.resolve(null),
      ]);
      if (attempt !== this._connection) return;
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (attempt !== this._connection) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }
      attempt.microphone = stream;
      stream.getAudioTracks().forEach((track) => {
        track.enabled = this._microphoneActive();
      });
      this._update((s) => {
        s.microphoneAccess = "granted";
      });
      this._monitorMicrophone(stream);
      const response = await snapshot.api.voiceTextTicket(service === null ? { choiceID } : { choiceID, service });
      if (attempt !== this._connection) return;
      const url = new URL("api/voicegateway/v2/voice/text/browser", snapshot.url);
      url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
      const socket = new WebSocket(url, ["gomode.text.v2", `gomode.ticket.${response.ticket}`]);
      attempt.channel = socket;
      this._createBrowserSpeech(attempt, socket);
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
      attempt.setSetupTimer(() => {
        if (this._ownsChannel(attempt, socket) && !this.state.connected) this._setError("Voice connection timed out.");
      });
    } catch (error) {
      if (attempt === this._connection) {
        if (error instanceof DOMException && error.name === "NotAllowedError") this._microphoneDenied();
        else this._setError(error instanceof Error ? error.message : "Text voice connection failed");
      }
    }
  }

  private _createBrowserSpeech(attempt: VoiceConnection, channel: VoiceChannel): void {
    if (this.state.speechInput !== "client" && this.state.speechOutput !== "client") return;
    const speech = new BrowserSpeech(this.state.languageTag, this.state.browserVoiceURI, this.state.browserSpeechRate, {
      nextSpeech: () => attempt.speechBuffer.next(),
      listening: (value) => {
        if (this._ownsChannel(attempt, channel))
          this._update((s) => {
            s.listening = value;
          });
        if (!value)
          queueMicrotask(() => {
            if (!this._ownsChannel(attempt, channel) || this.state.listening) return;
            this._browserUserSpeechActive = false;
            this._flushPendingNotifications(true);
          });
      },
      speaking: (value) => {
        if (!this._ownsChannel(attempt, channel)) return;
        this._update((s) => {
          s.speaking = value;
        });
        if (microphoneMuted(this.state)) speech.stopListening();
        else this._startBrowserListening();
        if (!value) this._drainBrowserTurn();
      },
      interim: (text) => {
        if (!this._ownsChannel(attempt, channel)) return;
        if (text !== "") this._browserUserSpeechActive = true;
        if (text !== "" && this._speakerActive) this._interruptBrowserTurn();
        this._browserUserText(text, false);
      },
      final: (text) => {
        if (!this._ownsChannel(attempt, channel)) return;
        this._browserUserSpeechActive = false;
        this._flushPendingNotifications(false);
        this._browserTurnIdle = false;
        this._speakerActive = true;
        this._browserUserText(text, true);
        this._send(JSON.stringify(gatewayUserMessage(text)));
      },
      error: (message, permissionDenied) => {
        if (!this._ownsChannel(attempt, channel)) return;
        if (permissionDenied) this._microphoneDenied();
        else this._setError(message);
      },
    });
    attempt.speech = speech;
  }

  private _browserUserText(text: string, final: boolean): void {
    this._update((s) => {
      // Tool diagnostics can arrive while recognition is still revising a draft.
      const draft = s.transcript.findIndex((entry) => entry.speaker === "user" && !entry.final);
      if (text === "") {
        if (draft !== -1) s.transcript = s.transcript.filter((_, index) => index !== draft);
        return;
      }
      const entry: TranscriptEntry = { kind: "transcript", speaker: "user", text, final };
      s.transcript =
        draft === -1
          ? [...s.transcript, entry]
          : s.transcript.map((current, index) => (index === draft ? entry : current));
    });
  }

  private _interruptBrowserTurn(): void {
    if (this._browserCancellation !== "none") return;
    this._browserCancellation = "pending";
    this._browserTurnIdle = true;
    this._speakerActive = false;
    this._connection?.speechBuffer.clear();
    this._connection?.speech?.stopSpeaking();
    const speaker = this._connection?.speaker;
    if (speaker) speaker.muted = true;
    this._update((s) => {
      s.speaking = false;
      s.activeTool = null;
      s.turnState = TurnStateIdle;
      s.transcript = s.transcript.map((entry) => ({ ...entry, final: true }));
    });
    this._send(JSON.stringify({ kind: MessageKindTurnCancel }));
  }

  private _startBrowserListening(): void {
    if (
      this.state.speechInput === "client" &&
      this.state.connected &&
      !microphoneMuted(this.state) &&
      (this.state.speaking || this._browserTurnIdle)
    ) {
      this._thinkingEarcon.setThinking(false);
      this._connection?.speech?.listen();
    }
  }

  private _finishBrowserTurn(): void {
    // Generation owns transcript boundaries, independently of queued playback.
    this._update((s) => {
      s.transcript = s.transcript.map((entry) => ({ ...entry, final: true }));
    });
    this._browserTurnIdle = true;
    this._connection?.speechBuffer.finish();
    this._connection?.speech?.speak();
    this._drainBrowserTurn();
  }

  private _drainBrowserTurn(): void {
    if (!this.state.connected || !this._browserTurnIdle || this.state.speaking) return;
    this._speakerActive = false;
    this._update((s) => {
      if (s.microphoneMode === "muteOnce") s.microphoneMode = "listening";
    });
    this._flushPendingNotifications(true);
    this._startBrowserListening();
  }

  disconnect(): void {
    batch(() => {
      this._sessionGateway?.preparation?.retireSession(this.conversationID);
      this._leaveVoiceMode();
      this._reconnectEnabled = false;
      this._replaceConnection(null, false);
      // Preserve transcript for review; mark all entries final.
      this._update((s) => {
        s.connected = false;
        s.listening = false;
        s.speaking = false;
        s.microphoneMode = "listening";
        s.connectStatus = null;
        s.connectPhase = null;
        s.activeTool = null;
        s.turnState = TurnStateIdle;
        s.micLevel = 0;
        s.audioDevicesError = null;
        s.audioInputError = null;
        s.audioOutputError = null;
        s.transcript = s.transcript.map((e) => ({ ...e, final: true }));
        s.transcriptDraft = null;
      });
    });
  }

  setMicrophoneMode(mode: MicrophoneMode): void {
    this._update((s) => {
      s.microphoneMode = mode;
    });
    const speech = this._connection?.speech;
    if (speech) {
      if (microphoneMuted(this.state)) speech.stopListening();
      else this._startBrowserListening();
    }
  }

  /** Replace the authoritative service overview without replaying obsolete snapshots. */
  setServiceContext(text: string): void {
    if (this._contextUpdateBlocked()) {
      this._pendingServiceContext = text;
      return;
    }
    this._sendContextUpdate(text, false);
  }

  injectText(text: string, generate?: boolean): void {
    if (this._contextUpdateBlocked()) {
      if (this._pendingNotifications.length >= 64) {
        this._setError("Too many pending service updates. Reconnect voice.");
        return;
      }
      this._pendingNotifications.push({ text, generate: generate === true });
      return;
    }
    this._sendContextUpdate(text, generate === true);
  }

  private _contextUpdateBlocked(): boolean {
    return (
      this._browserUserSpeechActive ||
      this._speakerActive ||
      this.state.turnState !== TurnStateIdle ||
      this.state.activeTool !== null
    );
  }

  private _sendContextUpdate(text: string, generate: boolean): void {
    if (generate) {
      this._speakerActive = true;
      if (this.state.speechInput === "client" || this.state.speechOutput === "client") this._browserTurnIdle = false;
    }
    this._send(JSON.stringify(gatewayContextUpdate(text, generate)));
  }

  private _flushPendingNotifications(allowResponse: boolean): void {
    if (this._contextUpdateBlocked()) return;
    if (this._pendingNotifications.length === 0 && this._pendingServiceContext === null) return;
    const text = [this._pendingServiceContext, ...this._pendingNotifications.map((update) => update.text)]
      .filter((value): value is string => value !== null)
      .join("\n");
    const generate = allowResponse && this._pendingNotifications.some((update) => update.generate);
    this._pendingNotifications = [];
    this._pendingServiceContext = null;
    this._sendContextUpdate(text, generate);
  }

  clearTranscript(): void {
    this._clearTranscript();
  }

  // -----------------------------------------------------------------------
  // Private helpers
  // -----------------------------------------------------------------------

  /** Send a message via the WebRTC data channel. */
  private _send(msg: string): void {
    const channel = this._connection?.channel ?? null;
    if (channel !== null) this._sendOn(channel, msg, VOICE_MESSAGE_DELIVERY_ERROR);
  }

  private _sendOn(channel: VoiceChannel, msg: string, failureMessage: string): void {
    if (channel !== this._connection?.channel) return;
    try {
      if (voiceChannelOpen(channel)) {
        channel.send(msg);
        return;
      }
    } catch (error: unknown) {
      console.warn("Voice message send failed", error instanceof Error ? error.name : "unknown error");
    }
    this._setError(failureMessage);
  }

  /** Install the successor before closing resources or publishing cleanup state. */
  private _replaceConnection(next: VoiceConnection | null, keepMeterContext: boolean): void {
    batch(() => {
      this._audioAccess.abort();
      this._audioAccess = new AbortController();
      const previous = this._connection;
      this._connection = next;
      this._audioRoutes.reset();
      previous?.dispose();
      if (this._connection !== next) return;
      this._thinkingEarcon.setThinking(false);
      this._browserTurnIdle = false;
      this._browserUserSpeechActive = false;
      this._browserCancellation = "none";
      this._microphoneInput.setSpeaking(false);
      if (!keepMeterContext) this._microphoneInput.close();
      this._microphoneMeter.stop(keepMeterContext);
      this._speakerActive = false;
      this._pendingNotifications = [];
      this._pendingServiceContext = null;
      this._update((s) => {
        s.audioPlaybackError = null;
      });
    });
  }

  private _setStatus(phase: Exclude<VoiceState["connectPhase"], null>, status: string): void {
    this._update((s) => {
      s.connectStatus = status;
      s.connectPhase = phase;
      s.error = null;
    });
  }

  private _scheduleReconnect(pc: RTCPeerConnection, reason: string, delay: number): void {
    const connection = this._connection;
    if (connection === null || connection.pc !== pc) return;
    if (!this._reconnectEnabled) return;
    if (this._reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      this._setError(`Voice connection lost after ${MAX_RECONNECT_ATTEMPTS} recovery attempts`);
      return;
    }
    if (connection.reconnectTimer !== null) {
      if (delay !== 0) return;
      clearTimeout(connection.reconnectTimer);
    }
    this._setStatus("reconnecting", `${reason}; reconnecting…`);
    if (connection !== this._connection) return;
    connection.reconnectTimer = setTimeout(() => {
      if (connection !== this._connection) return;
      connection.reconnectTimer = null;
      if (!this._reconnectEnabled) return;
      this._reconnectAttempts++;
      this._speakerActive = false;
      this._update((s) => {
        s.speaking = false;
        s.activeTool = null;
        s.turnState = TurnStateIdle;
        s.transcriptDraft = null;
      });
      if (connection !== this._connection) return;
      this._recoveryContext = buildRecoveryContext(this.state.transcript);
      void this._connect(true);
    }, delay);
  }

  private _startSetupTimer(pc: RTCPeerConnection): void {
    const connection = this._connection;
    if (connection === null || connection.pc !== pc) return;
    connection.setSetupTimer(() => {
      if (connection === this._connection && connection.pc === pc && !this.state.connected && !this.state.error) {
        void this._setConnectionTimeoutError(pc);
      }
    });
  }

  private async _setConnectionTimeoutError(pc: RTCPeerConnection): Promise<void> {
    await this._setDiagnosticError(pc, "Connection timed out — server did not respond");
  }

  private async _setDiagnosticError(pc: RTCPeerConnection, fallback: string): Promise<void> {
    const connection = this._connection;
    if (connection === null || connection.pc !== pc) return;
    if (this.state.error) return;
    const sessionID = connection.rtc?.sessionID;
    const snapshot = connection.gateway;
    if (!sessionID) {
      this._setError(fallback);
      return;
    }
    try {
      const token = await refreshedServiceToken(snapshot, connection.rtc?.service ?? null);
      if (connection !== this._connection || connection.pc !== pc) return;
      const diagnostics = await snapshot.api.diagnoseVoiceRTC(
        sessionID,
        { client: this._clientDiagnostics(pc) },
        token === null ? {} : { Authorization: `Bearer ${token}` },
      );
      if (connection !== this._connection || connection.pc !== pc) return;
      logVoiceRTCDiagnostics(diagnostics, connection.offerSDP, connection.answerSDP);
      if (connection === this._connection && connection.pc === pc && !this.state.connected && !this.state.error) {
        this._setError(formatVoiceRTCDiagnostics(diagnostics));
      }
    } catch (e: unknown) {
      const detail = e instanceof Error ? ` (${e.message})` : "";
      if (connection === this._connection && connection.pc === pc && !this.state.connected && !this.state.error) {
        this._setError(`${fallback}${detail}`);
      }
    }
  }

  private _clientDiagnostics(pc: RTCPeerConnection): VoiceRTCClientDiagnostics {
    const channelState = this._connection?.channel?.readyState;
    return {
      iceConnectionState: pc.iceConnectionState,
      iceGatheringState: pc.iceGatheringState,
      connectionState: pc.connectionState,
      signalingState: pc.signalingState,
      dataChannelState: typeof channelState === "string" ? channelState : undefined,
    };
  }

  /** Retains a failure for the overlay's error history. */
  private _recordError(message: string): void {
    this._update((s) => {
      s.errorHistory = [{ at: Date.now(), message }, ...s.errorHistory].slice(0, MAX_VOICE_ERRORS);
    });
  }

  private _setError(message: string): void {
    batch(() => {
      this._sessionGateway?.preparation?.retireSession(this.conversationID);
      this._reconnectEnabled = false;
      this._recordError(message);
      this._leaveVoiceMode();
      this._replaceConnection(null, false);
      this._update((s) => {
        s.connectStatus = null;
        s.connectPhase = null;
        s.connected = false;
        s.listening = false;
        s.speaking = false;
        s.turnState = TurnStateIdle;
        s.activeTool = null;
        s.error = message;
      });
    });
  }

  private _leaveVoiceMode(): void {
    if (this._callMetadata !== null) {
      if (navigator.mediaSession.metadata === this._callMetadata) {
        navigator.mediaSession.metadata = null;
        navigator.mediaSession.playbackState = "none";
      }
      this._callMetadata = null;
    }
    if (!this._voiceModeActive) return;
    this._voiceModeActive = false;
    if (this.state.speechOutput !== "client" || !browserSpeechAvoidsChimes()) this._chime.playDisconnected();
  }

  private async _publishCallMetadata(attempt: VoiceConnection): Promise<void> {
    if (!("mediaSession" in navigator) || typeof MediaMetadata === "undefined") return;
    try {
      // The caller is the host service, even when it uses an external voice gateway.
      const settings = await createGoModeClient().getSettings();
      if (attempt !== this._connection || !this._voiceModeActive) return;
      if (typeof settings.service !== "string" || settings.service.trim() === "") {
        throw new Error("Go Mode service name is missing");
      }
      this._callMetadata = new MediaMetadata({ title: settings.service });
      navigator.mediaSession.metadata = this._callMetadata;
      navigator.mediaSession.playbackState = "playing";
    } catch (error) {
      console.warn("Could not publish voice caller name", error);
    }
  }

  private _clearTranscript(): void {
    this._update((s) => {
      s.transcript = [];
      s.toolHistory = [];
      s.transcriptDraft = null;
    });
  }

  private _update(fn: (s: VoiceState) => void): void {
    const wasActive = untrack(() => this._microphoneActive());
    this.setState(produce(fn));
    untrack(() => {
      const active = this._microphoneActive();
      this._microphoneInput.setSpeaking(this.state.speaking);
      if (active !== wasActive) {
        this._connection?.microphone?.getAudioTracks().forEach((track) => {
          track.enabled = active;
        });
        this._connection?.outgoing?.stream.getAudioTracks().forEach((track) => {
          track.enabled = active;
        });
      }
      if (!active && this.state.micLevel !== 0)
        this.setState(
          produce((s) => {
            s.micLevel = 0;
          }),
        );
      const state = this.state;
      this._thinkingEarcon.setThinking(
        state.speechOutput === "client" &&
          !browserSpeechAvoidsChimes() &&
          (this._connection?.speech ?? null) !== null &&
          state.connected &&
          state.turnState === TurnStateThinking &&
          !state.listening &&
          !state.speaking &&
          !microphoneMuted(state),
      );
    });
  }

  // -----------------------------------------------------------------------
  // Gateway setup message
  // -----------------------------------------------------------------------

  private _sendSetup(tools: McpToolDescriptor[], systemInstruction: string, serviceContext: string): void {
    const setup = gatewaySessionSetup(
      voiceToolDeclarations(tools, this._frontendTools?.declarations ?? []),
      systemInstruction,
      serviceContext,
      this.state.languageTag,
      this._sessionVoiceName ?? "",
      this.state.showReasoning,
    );
    setup.speech = this.speech;
    if (this._sessionComposable && this.state.speechInput === "gateway" && this._sessionSpeechModelIDs.input !== null)
      setup.recognitionModelID = this._sessionSpeechModelIDs.input;
    if (this._sessionComposable && this.state.speechOutput === "gateway" && this._sessionSpeechModelIDs.output !== null)
      setup.synthesisModelID = this._sessionSpeechModelIDs.output;
    this._send(JSON.stringify(setup));
  }

  // -----------------------------------------------------------------------
  // Message handling
  // -----------------------------------------------------------------------

  private _ownsChannel(attempt: VoiceConnection, channel: VoiceChannel): boolean {
    return attempt === this._connection && channel === attempt.channel;
  }

  private async _handleMessage(text: string, attempt: VoiceConnection, channel: VoiceChannel): Promise<void> {
    if (!this._ownsChannel(attempt, channel)) return;
    let env: MessageEnvelope;
    try {
      env = JSON.parse(text) as MessageEnvelope;
    } catch {
      return;
    }
    if (env.kind === MessageKindSessionUsage) {
      const report = JSON.parse(text) as SessionUsage;
      this._update((s) => {
        s.usage = mergeVoiceUsage(s.usage, report);
        if (report.responseID && report.responseUsage) {
          s.transcript = s.transcript.map((entry) =>
            entry.speaker === "assistant" && entry.kind === "transcript" && entry.responseID === report.responseID
              ? { ...entry, responseUsage: report.responseUsage }
              : entry,
          );
        }
      });
      return;
    }

    if (this.state.speechInput === "client" && this._browserCancellation !== "none") {
      if (env.kind === MessageKindInterrupted) {
        this._browserCancellation = "acknowledged";
        this._drainBrowserTurn();
        return;
      }
      if (
        env.kind === MessageKindTurnStatus &&
        this._browserCancellation === "acknowledged" &&
        !this._browserTurnIdle &&
        (JSON.parse(text) as TurnStatus).state === TurnStateThinking
      )
        this._browserCancellation = "none";
      else if (
        env.kind === MessageKindAssistantTextDelta ||
        env.kind === MessageKindReasoningDelta ||
        env.kind === MessageKindTranscriptDelta ||
        env.kind === MessageKindTurnStatus ||
        env.kind === MessageKindSpeechStarted ||
        env.kind === MessageKindSpeechEnded
      )
        return;
      // Dispatched tool calls belong to the session and still need a result.
    }

    if (env.kind === MessageKindSessionStatus && !this.state.connected) {
      const msg = JSON.parse(text) as SessionStatus;
      if (typeof msg.message !== "string") return;
      this._update((s) => {
        s.connectStatus = msg.message;
      });
      attempt.setSetupTimer(() => {
        if (this._ownsChannel(attempt, channel) && !this.state.connected) this._setError("Voice connection timed out.");
      });
      return;
    }

    if (env.kind === MessageKindSessionReady) {
      attempt.clearSetupTimer();
      this._update((s) => {
        s.connectStatus = null;
        s.connectPhase = null;
        s.connected = true;
        s.error = null;
      });
      if (!this._voiceModeActive) {
        this._voiceModeActive = true;
        if (this.state.speechOutput !== "client" || !browserSpeechAvoidsChimes()) this._chime.playConnected();
      }
      if (this._callMetadata === null) void this._publishCallMetadata(attempt);
      if (this._recoveryContext !== "") {
        this._send(JSON.stringify(gatewayContextUpdate(this._recoveryContext)));
        this._recoveryContext = "";
      }
      this._flushPendingNotifications(true);
      this._speakerActive = true;
      this._send(JSON.stringify(gatewayUserMessage("Say exactly one word: Ready")));
      // Audio capture is handled via WebRTC RTP tracks; no separate audio setup needed.
      return;
    }

    if (env.kind === MessageKindTranscriptDraft) {
      const draft = JSON.parse(text) as TranscriptDraft;
      if (
        this.state.speechInput !== "gateway" ||
        typeof draft.id !== "string" ||
        !draft.id ||
        typeof draft.text !== "string" ||
        draft.text.length > 65536
      )
        return;
      this._update((s) => {
        if (draft.text) s.transcriptDraft = { id: draft.id, text: draft.text };
        else if (s.transcriptDraft?.id === draft.id) s.transcriptDraft = null;
      });
      return;
    }

    if (env.kind === MessageKindTranscriptDelta) {
      const delta = JSON.parse(text) as TranscriptDelta;
      // Browser recognition already owns the user's interim and final text.
      if (this.state.speechInput !== "client" || delta.speaker !== "user") this._handleTranscriptDelta(delta);
      return;
    }

    if (env.kind === MessageKindAssistantTextDelta && this.state.speechOutput === "client") {
      const delta = JSON.parse(text) as AssistantTextDelta;
      this._browserTurnIdle = false;
      this._speakerActive = true;
      this._update((s) => {
        s.transcript = appendChunk(s.transcript, "assistant", delta.text, "transcript", delta.responseID, s.usage);
      });
      attempt.speechBuffer.append(delta.text);
      attempt.speech?.speak();
      return;
    }

    if (env.kind === MessageKindReasoningDelta) {
      // Reasoning is display-only: it is never spoken or replayed as context.
      if (!this.state.showReasoning) return;
      const delta = JSON.parse(text) as ReasoningDelta;
      if (!delta.text) return;
      this._update((s) => {
        s.transcript = appendChunk(s.transcript, "assistant", delta.text, "reasoning", delta.responseID, s.usage);
      });
      return;
    }

    if (env.kind === MessageKindSpeechStarted) {
      this._speakerActive = true;
      this._update((s) => {
        s.speaking = true;
      });
      if (microphoneMuted(this.state)) attempt.speech?.stopListening();
      else this._startBrowserListening();
      return;
    }

    if (env.kind === MessageKindSpeechEnded) {
      this._speakerActive = false;
      this._flushPendingNotifications(true);
      this._update((s) => {
        s.speaking = false;
        if (s.microphoneMode === "muteOnce") s.microphoneMode = "listening";
        s.transcript = s.transcript.map((e) => ({ ...e, final: true }));
      });
      this._startBrowserListening();
      return;
    }

    if (env.kind === MessageKindInterrupted) {
      if (this.state.speechOutput === "client") {
        this._browserTurnIdle = true;
        attempt.speechBuffer.clear();
        attempt.speech?.stopSpeaking();
        this._update((s) => {
          s.speaking = false;
          s.activeTool = null;
          s.turnState = TurnStateIdle;
        });
        this._finishBrowserTurn();
        return;
      }
      this._speakerActive = false;
      this._update((s) => {
        s.speaking = false;
        if (s.microphoneMode === "muteOnce") s.microphoneMode = "listening";
        s.activeTool = null;
        s.turnState = TurnStateIdle;
      });
      this._flushPendingNotifications(true);
      return;
    }

    if (env.kind === MessageKindTurnStatus) {
      const msg = JSON.parse(text) as TurnStatus;
      this._update((s) => {
        s.turnState = msg.state;
      });
      if (this.state.speechInput === "client" || this.state.speechOutput === "client") {
        if (msg.state === TurnStateIdle) {
          if (this.state.speechOutput === "client") this._finishBrowserTurn();
          else {
            this._browserTurnIdle = true;
            this._drainBrowserTurn();
          }
        } else {
          this._browserTurnIdle = false;
          this._speakerActive = true;
          if (attempt.speaker !== null) attempt.speaker.muted = false;
          if (!this.state.speaking) attempt.speech?.stopListening();
        }
      } else if (msg.state === TurnStateIdle) {
        if (!this.state.speaking) this._speakerActive = false;
        this._flushPendingNotifications(true);
      }
      return;
    }

    if (env.kind === MessageKindToolCall) {
      await this._handleToolCall(JSON.parse(text) as ToolCall, attempt, channel);
      return;
    }

    if (env.kind === MessageKindError) {
      const msg = JSON.parse(text) as GatewayError;
      if (msg.recoverable) {
        // Keep the connection and outstanding tool deliveries alive. Diagnostics
        // use the same transcript presentation as tool errors, not fatal state.
        this._recordError(msg.message);
        this._update((s) => {
          s.transcript = [
            ...s.transcript,
            { kind: "transcript", speaker: "assistant", text: `[Voice] ${msg.message}`, final: true },
          ];
        });
        return;
      }
      this._setError(msg.message);
    }
  }

  private _handleTranscriptDelta(msg: TranscriptDelta): void {
    if (msg.speaker !== "user" && msg.speaker !== "assistant") return;
    this._update((s) => {
      if (msg.speaker === "user" && msg.draft_id) {
        if (s.transcriptDraft?.id === msg.draft_id) s.transcriptDraft = null;
        if (msg.text)
          s.transcript = [...s.transcript, { kind: "transcript", speaker: "user", text: msg.text, final: true }];
        return;
      }
      if (!msg.text) return;
      s.transcript = appendChunk(s.transcript, msg.speaker, msg.text ?? "", "transcript", msg.responseID, s.usage);
    });
  }

  private async _handleToolCall(msg: ToolCall, attempt: VoiceConnection, channel: VoiceChannel): Promise<void> {
    if (!this._ownsChannel(attempt, channel)) return;
    if (!msg.id || !msg.name) return;
    const sessionID = this.conversationID;
    this._update((s) => {
      const sequence = (s.toolHistory.at(-1)?.sequence ?? 0) + 1;
      s.toolHistory = [...s.toolHistory.slice(-31), { sequence, name: msg.name }];
    });
    // Store observers can retire or replace this conversation synchronously.
    if (!this._ownsChannel(attempt, channel)) return;
    if (msg.name === HANG_UP_TOOL_NAME) {
      this.disconnect();
      return;
    }

    try {
      this._update((s) => {
        s.activeTool = msg.name;
      });
      if (!this._ownsChannel(attempt, channel)) return;
      const frontend = this._frontendTools?.declarations.some((tool) => tool.name === msg.name)
        ? this._frontendTools
        : null;
      const rawArgs: unknown = msg.args;
      if (rawArgs != null && (typeof rawArgs !== "object" || Array.isArray(rawArgs)))
        throw new Error("Voice tool arguments must be an object");
      const toolArgs = (rawArgs ?? {}) as Record<string, unknown>;
      const result = frontend
        ? {
            structuredContent: await frontend.execute(msg.name, toolArgs, sessionID, attempt.lifetime.signal),
            isError: false,
          }
        : await mcpClient.callTool(msg.name, toolArgs, {
            locale: this.state.languageTag,
            sessionID,
          });
      if (!this._ownsChannel(attempt, channel)) return;
      if (frontend && result.structuredContent["error"] !== undefined) result.isError = true;
      this._update((s) => {
        s.activeTool = null;
      });
      if (!this._ownsChannel(attempt, channel)) return;

      // Surface tool errors in the transcript.
      if (result.isError) {
        const errMsg =
          typeof result.structuredContent["error"] === "string" ? result.structuredContent["error"] : "Tool error";
        this._update((s) => {
          s.transcript = [
            ...s.transcript,
            {
              kind: "transcript",
              speaker: "assistant",
              text: `[${msg.name}] ${errMsg}`,
              final: true,
            },
          ];
        });
      }

      if (!this._ownsChannel(attempt, channel)) return;
      this._sendToolResult(channel, msg.id, msg.name, result.structuredContent);
    } catch (e: unknown) {
      if (!this._ownsChannel(attempt, channel)) return;
      this._update((s) => {
        s.activeTool = null;
      });
      if (!this._ownsChannel(attempt, channel)) return;
      const errMsg = e instanceof Error ? e.message : "Unknown error";
      this._update((s) => {
        s.transcript = [
          ...s.transcript,
          {
            kind: "transcript",
            speaker: "assistant",
            text: `[${msg.name}] ${errMsg}`,
            final: true,
          },
        ];
      });
      if (!this._ownsChannel(attempt, channel)) return;
      this._sendToolResult(channel, msg.id, msg.name, { error: errMsg });
    }
  }

  private _sendToolResult(channel: VoiceChannel, id: string, name: string, result: Record<string, unknown>): void {
    this._sendOn(channel, JSON.stringify(gatewayToolResult(id, name, result)), VOICE_TOOL_RESULT_DELIVERY_ERROR);
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
  const mappingError = diagnostics.server.udpMappingError
    ? ` — UDP mapping: ${diagnostics.server.udpMappingError}`
    : "";
  return `${side}: ${diagnostics.issue} — ${diagnostics.message}${mappingError}`;
}

/** Build a bounded recovery-only context without replaying unfinished transcript deltas. */
export function buildRecoveryContext(transcript: TranscriptEntry[]): string {
  const prefix = "Network recovery context. Continue the existing conversation; do not treat this as a new user turn.";
  const availableTranscriptChars = MAX_RECOVERY_CONTEXT_CHARS - prefix.length - 24;
  const lines: string[] = [];
  let lineChars = 0;
  for (const entry of [...transcript].reverse()) {
    if (entry.kind !== "transcript" || !entry.final || entry.text.trim() === "") continue;
    const line = `${entry.speaker}: ${entry.text.trim()}`;
    if (lineChars + line.length + (lines.length === 0 ? 0 : 1) > availableTranscriptChars) break;
    lines.unshift(line);
    lineChars += line.length + (lines.length === 1 ? 0 : 1);
  }
  const transcriptSection = lines.length === 0 ? "" : `\nFinalized transcript:\n${lines.join("\n")}`;
  return `${prefix}${transcriptSection}`;
}

function gatewayContextUpdate(text: string, generate?: boolean): ContextUpdate {
  return {
    kind: MessageKindContextUpdate,
    context: { text },
    ...(generate ? { generate: true } : {}),
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
  includeReasoning: boolean,
): SessionSetup {
  return {
    kind: MessageKindSessionSetup,
    ...(includeReasoning ? { includeReasoning: true } : {}),
    voice: {
      ...(voiceName === "" ? {} : { name: voiceName }),
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

function appendChunk(
  transcript: TranscriptEntry[],
  speaker: TranscriptSpeaker,
  text: string,
  kind: TranscriptKind,
  responseID: string | undefined,
  usage: VoiceUsage | null,
): TranscriptEntry[] {
  const id = speaker === "assistant" ? responseID : undefined;
  const index = id
    ? transcript.findIndex((entry) => entry.speaker === speaker && entry.kind === kind && entry.responseID === id)
    : transcript.length - 1;
  const previous = transcript[index];
  const counts = kind === "transcript" && id ? usage?.responses?.get(id) : undefined;
  if (
    previous &&
    previous.speaker === speaker &&
    previous.kind === kind &&
    previous.responseID === id &&
    (id || !previous.final)
  ) {
    return transcript.map((entry, i) =>
      i === index ? { ...entry, text: entry.text + text, ...(counts ? { responseUsage: counts } : {}) } : entry,
    );
  }
  return [...transcript, { kind, speaker, text, final: false, responseID: id, responseUsage: counts }];
}

// Singleton — lives at module level so the WebRTC connection survives component remounts.
export const voiceSession = new VoiceSession();

// Disconnect on actual page unload (browser close/refresh).
if (typeof window !== "undefined") {
  window.addEventListener("beforeunload", () => {
    voiceSession.disconnect();
  });
}
