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
    configureVoiceGateway("https://voice.example.com", null, () => "host-token");
    let authorization: string | null = null;
    globalThis.fetch = async (_input, init) => {
      authorization = new Headers(init?.headers).get("Authorization");
      return new Response(JSON.stringify({ sessionID: "s1", sdp: "answer" }));
    };

    await voiceGatewayApi.voiceRTCOffer({ sdp: "offer" });
    expect(authorization).toBeNull();
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
