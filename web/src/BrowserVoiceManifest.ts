// Validates browser voice discovery and credential-bearing endpoints before runtime configuration.

export interface BrowserVoiceEndpoints {
  gatewayURL: string;
  mcpEndpoint: string;
  tokenEndpoint: string | null;
}

export interface BrowserVoiceService {
  service: string;
  toolGroup: string;
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

/** A gateway is a secure origin; HTTP loopback is allowed for local development. */
export function voiceGatewayOrigin(value: string, origin: string): string {
  if (value !== "/" && !/^https?:\/\/[^/?#@\\]+\/?$/.test(value)) {
    throw new Error("Voice gateway must be a secure origin");
  }
  const url = new URL(value, origin);
  // URL canonicalizes IP literals; require the entire hostname to be a loopback address.
  const loopback =
    url.hostname === "localhost" || url.hostname === "[::1]" || /^127(?:\.\d{1,3}){3}$/.test(url.hostname);
  if (
    (url.protocol !== "https:" && !(url.protocol === "http:" && loopback)) ||
    url.username ||
    url.password ||
    url.pathname !== "/" ||
    url.search ||
    url.hash
  )
    throw new Error("Voice gateway must be a secure origin");
  return url.href;
}

/** Host credentials may only be sent to an unambiguous same-origin HTTP endpoint. */
export function sameOriginVoiceEndpoint(value: string, origin: string): string {
  if (
    value.includes("\\") ||
    value.includes("#") ||
    value.startsWith("//") ||
    (!value.startsWith("/") && !/^https?:\/\//.test(value))
  ) {
    throw new Error("Voice token and MCP endpoints must be same-origin");
  }
  const url = new URL(value, origin);
  if (url.origin !== origin || !["http:", "https:"].includes(url.protocol) || url.username || url.password) {
    throw new Error("Voice token and MCP endpoints must be same-origin");
  }
  return url.href;
}

/** A valid manifest with no gateway URL explicitly disables browser voice. */
export function resolveBrowserVoiceEndpoints(
  value: unknown,
  expected: BrowserVoiceService,
  origin: string,
): BrowserVoiceEndpoints | null {
  const manifest = record(value);
  if (manifest?.apiVersion !== 1) throw new Error("Unsupported Go Mode discovery API version");
  if (manifest?.service !== expected.service) throw new Error("Unexpected Go Mode service");
  const shell = record(manifest.webShell);
  const gateway = record(shell?.voiceGateway);
  if (gateway === null) throw new Error("Go Mode manifest has no voice gateway settings");
  if (gateway.url === undefined || gateway.url === "") return null;
  if (typeof gateway.url !== "string") throw new Error("Invalid voice gateway URL");
  const gatewayURL = voiceGatewayOrigin(gateway.url, origin);
  let tokenEndpoint: string | null = null;
  if (gateway.tokenEndpoint !== undefined && gateway.tokenEndpoint !== null) {
    if (typeof gateway.tokenEndpoint !== "string" || !gateway.tokenEndpoint)
      throw new Error("Invalid voice token endpoint");
    tokenEndpoint = sameOriginVoiceEndpoint(gateway.tokenEndpoint, origin);
  }
  if (new URL(gatewayURL).origin !== origin && tokenEndpoint === null) {
    throw new Error("External voice gateway requires a same-origin token endpoint");
  }
  const groups = shell?.toolGroups;
  if (!Array.isArray(groups)) throw new Error("Go Mode manifest has no MCP groups");
  const matches = groups.map(record).filter((group) => group?.name === expected.toolGroup);
  if (matches.length !== 1 || typeof matches[0]?.endpoint !== "string") {
    throw new Error("Go Mode manifest has no unique selected MCP endpoint");
  }
  return { gatewayURL, mcpEndpoint: sameOriginVoiceEndpoint(matches[0].endpoint, origin), tokenEndpoint };
}

/** Missing routes and HTML SPA fallbacks are unavailable; malformed advertised JSON is an error. */
export async function discoverBrowserVoice(
  expected: BrowserVoiceService,
  signal: AbortSignal,
): Promise<BrowserVoiceEndpoints | null> {
  const response = await fetch("/.well-known/gomode.json", {
    cache: "no-store",
    credentials: "same-origin",
    redirect: "error",
    signal,
  });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`Go Mode discovery returned HTTP ${response.status}`);
  if (!response.headers.get("content-type")?.toLowerCase().includes("application/json")) return null;
  const manifest: unknown = await response.json();
  signal.throwIfAborted();
  return resolveBrowserVoiceEndpoints(manifest, expected, window.location.origin);
}
