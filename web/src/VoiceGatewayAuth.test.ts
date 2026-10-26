// Tests bearer authentication for same-origin browser voice gateway signaling.

import { afterEach, describe, it } from "node:test";
import { expect } from "../tests/expect";

import { configureVoiceGateway, voiceGatewayApi } from "./VoiceSession";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
  configureVoiceGateway("/", null);
});

describe("voice gateway bearer authentication", () => {
  it("explains an empty reverse-proxy failure without attempting JSON decoding", async () => {
    globalThis.fetch = async () => new Response(null, { status: 502 });
    await expect(voiceGatewayApi.voiceChoices()).rejects.toThrow("Voice gateway unavailable (HTTP 502).");
  });

  it("distinguishes authentication, timeout, and network failures", async () => {
    globalThis.fetch = async () => new Response("<html>Sign in</html>", { status: 401 });
    await expect(voiceGatewayApi.voiceChoices()).rejects.toThrow("Voice authorization failed (HTTP 401).");
    globalThis.fetch = async () => {
      throw new DOMException("expired", "TimeoutError");
    };
    await expect(voiceGatewayApi.voiceChoices()).rejects.toThrow("Voice connection timed out.");
    globalThis.fetch = async () => {
      throw new TypeError("Failed to fetch");
    };
    await expect(voiceGatewayApi.voiceChoices()).rejects.toThrow("Voice gateway unreachable.");
  });

  it("preserves structured gateway validation errors", async () => {
    globalThis.fetch = async () =>
      new Response(JSON.stringify({ error: { code: "BAD_REQUEST", message: "unknown voice choice" } }), {
        status: 400,
      });
    await expect(voiceGatewayApi.voiceRTCOffer({ sdp: "offer" })).rejects.toThrow("unknown voice choice");
  });

  it("explains gateway configuration, rate limits, and upstream timeouts", async () => {
    for (const [status, message] of [
      [403, "Voice access denied (HTTP 403)."],
      [404, "Voice gateway not found (HTTP 404)."],
      [429, "Voice gateway busy (HTTP 429)."],
      [503, "Voice gateway unavailable (HTTP 503)."],
      [504, "Voice connection timed out (HTTP 504)."],
    ] as const) {
      globalThis.fetch = async () => new Response("proxy error", { status });
      await expect(voiceGatewayApi.voiceChoices()).rejects.toThrow(message);
    }
  });

  it("distinguishes a missing diagnostics session from a missing gateway", async () => {
    globalThis.fetch = async () =>
      new Response(JSON.stringify({ error: { code: "NOT_FOUND", message: "voice session not found" } }), {
        status: 404,
      });
    await expect(voiceGatewayApi.diagnoseVoiceRTC("expired", { client: {} })).rejects.toThrow(
      "Voice session not found (HTTP 404).",
    );
  });

  it("adds the latest bearer token to same-origin signaling requests", async () => {
    let token = "first";
    configureVoiceGateway("/", null, () => token);
    const authorizations: string[] = [];
    globalThis.fetch = async (_input, init) => {
      authorizations.push(new Headers(init?.headers).get("Authorization") ?? "");
      return new Response(JSON.stringify({ sessionID: "s1", sdp: "answer" }));
    };

    await voiceGatewayApi.voiceRTCOffer({ sdp: "offer" });
    token = "second";
    await voiceGatewayApi.closeVoiceRTC("s1");
    expect(authorizations).toEqual(["Bearer first", "Bearer second"]);
  });

  it("does not forward a host token to an external gateway", async () => {
    configureVoiceGateway(
      "https://voice.example.com",
      null,
      () => "host-token",
      () => ({ "X-Host-Session": "private-host-pin" }),
    );
    let authorization: string | null = null;
    let hostPin: string | null = null;
    globalThis.fetch = async (_input, init) => {
      authorization = new Headers(init?.headers).get("Authorization");
      hostPin = new Headers(init?.headers).get("X-Host-Session");
      return new Response(JSON.stringify({ sessionID: "s1", sdp: "answer" }));
    };

    await voiceGatewayApi.voiceRTCOffer({ sdp: "offer" });
    expect(authorization).toBeNull();
    expect(hostPin).toBeNull();
  });

  it("preserves a scoped session token when closing an external gateway session", async () => {
    configureVoiceGateway("https://voice.example.com", null, () => "host-token");
    let authorization: string | null = null;
    globalThis.fetch = async (_input, init) => {
      authorization = new Headers(init?.headers).get("Authorization");
      return new Response(JSON.stringify({ status: "ok" }));
    };

    await voiceGatewayApi.closeVoiceRTC("s1", { Authorization: "Bearer scoped-token" });
    expect(authorization).toBe("Bearer scoped-token");
  });
});
