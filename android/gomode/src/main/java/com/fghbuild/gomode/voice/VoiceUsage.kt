// Carries gateway usage snapshots across automatic reconnects in one conversation.
package com.fghbuild.gomode.voice

import com.fghbuild.voicegateway.sdk.v2.SessionUsage
import com.fghbuild.voicegateway.sdk.v2.TokenUsage

/** Reported conversation totals and the latest gateway connection snapshot. */
data class VoiceUsage(
    val totals: TokenUsage,
    val latest: SessionUsage,
    /** Bounded reports that can precede text; historical transcript rows retain their own counts. */
    val responses: Map<String, TokenUsage> = emptyMap(),
)

internal fun mergeVoiceUsage(
    current: VoiceUsage?,
    next: SessionUsage,
): VoiceUsage {
    val previous = current?.latest?.takeIf { it.sessionID == next.sessionID }?.totals

    fun count(
        total: Long?,
        value: Long?,
        old: Long?,
    ): Long? = if (total == null && value == null) null else (total ?: 0) + (value ?: 0) - (old ?: 0)
    val responseID = next.responseID
    val responseUsage = next.responseUsage
    return VoiceUsage(
        totals =
            TokenUsage(
                inputTokens = count(current?.totals?.inputTokens, next.totals.inputTokens, previous?.inputTokens),
                outputTokens = count(current?.totals?.outputTokens, next.totals.outputTokens, previous?.outputTokens),
                cachedTokens = count(current?.totals?.cachedTokens, next.totals.cachedTokens, previous?.cachedTokens),
                reasoningTokens =
                    count(
                        current?.totals?.reasoningTokens,
                        next.totals.reasoningTokens,
                        previous?.reasoningTokens,
                    ),
            ),
        latest = next,
        responses =
            if (responseID != null && responseUsage != null) {
                (current?.responses.orEmpty() - responseID + (responseID to responseUsage))
                    .entries
                    .toList()
                    .takeLast(256)
                    .associate { it.toPair() }
            } else {
                current?.responses.orEmpty()
            },
    )
}

/** Apply only this report's identified response, never the carried latest-response snapshot. */
internal fun VoiceState.withUsageReport(report: SessionUsage): VoiceState =
    copy(
        usage = mergeVoiceUsage(usage, report),
        transcript =
            if (report.responseID != null && report.responseUsage != null) {
                transcript.map { entry ->
                    if (entry.speaker == TranscriptSpeaker.ASSISTANT &&
                        entry.kind == TranscriptKind.TRANSCRIPT && entry.responseID == report.responseID
                    ) {
                        entry.copy(responseUsage = report.responseUsage)
                    } else {
                        entry
                    }
                }
            } else {
                transcript
            },
    )

internal fun VoiceState.withTranscriptChunk(
    speaker: TranscriptSpeaker,
    text: String,
    kind: TranscriptKind = TranscriptKind.TRANSCRIPT,
    responseID: String? = null,
): VoiceState =
    copy(
        transcript =
            transcript.appendChunk(
                speaker,
                text,
                kind,
                responseID,
                responseID?.let { usage?.responses?.get(it) },
            ),
    )
