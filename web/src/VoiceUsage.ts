// Carries gateway usage snapshots across automatic reconnects in one conversation.
import type { SessionUsage, TokenUsage } from "../../sdk/voicegateway/ts/v2/types.gen";

export interface VoiceUsage {
  totals: TokenUsage;
  latest: SessionUsage;
  /** Bounded reports that may arrive before their transcript. Historical rows own their counts. */
  responses?: ReadonlyMap<string, TokenUsage>;
}

export function mergeVoiceUsage(current: VoiceUsage | null, next: SessionUsage): VoiceUsage {
  const previous = current?.latest.sessionID === next.sessionID ? current.latest.totals : null;
  const count = (key: keyof TokenUsage): number | undefined => {
    const total = current?.totals[key];
    const value = next.totals[key];
    if (total === undefined && value === undefined) return undefined;
    return (total ?? 0) + (value ?? 0) - (previous?.[key] ?? 0);
  };
  const responses = new Map(current?.responses);
  if (next.responseID && next.responseUsage) {
    responses.delete(next.responseID);
    responses.set(next.responseID, next.responseUsage);
    if (responses.size > 256) responses.delete(responses.keys().next().value as string);
  }
  return {
    responses,
    totals: {
      inputTokens: count("inputTokens"),
      outputTokens: count("outputTokens"),
      cachedTokens: count("cachedTokens"),
      reasoningTokens: count("reasoningTokens"),
    },
    latest: next,
  };
}
