// Tests idempotent usage snapshots, reconnect accounting and unknown metrics.
import { it } from "node:test";
import { expect } from "../tests/expect";
import { MessageKindSessionUsage, type SessionUsage } from "../../sdk/voicegateway/ts/v2/types.gen";
import { mergeVoiceUsage } from "./VoiceUsage";

it("replaces connection snapshots and carries reported totals across reconnects", () => {
  const first: SessionUsage = {
    kind: MessageKindSessionUsage,
    sessionID: "one",
    provider: "test",
    model: "model",
    totals: { inputTokens: 100, outputTokens: 0 },
  };
  const current = mergeVoiceUsage(null, first);
  expect(current.totals).toEqual({
    inputTokens: 100,
    outputTokens: 0,
    cachedTokens: undefined,
    reasoningTokens: undefined,
  });
  expect(mergeVoiceUsage(current, first).totals).toEqual(current.totals);
  const updated = mergeVoiceUsage(current, {
    ...first,
    totals: { inputTokens: 220, outputTokens: 10, cachedTokens: 50 },
  });
  const recovered = mergeVoiceUsage(updated, {
    ...first,
    sessionID: "two",
    totals: { inputTokens: 30, outputTokens: 5 },
  });
  expect(recovered.totals).toEqual({
    inputTokens: 250,
    outputTokens: 15,
    cachedTokens: 50,
    reasoningTokens: undefined,
  });
  expect(mergeVoiceUsage(recovered, recovered.latest)).toEqual(recovered);
  expect(mergeVoiceUsage(null, { ...first, totals: {} }).totals.inputTokens).toBeUndefined();
});
