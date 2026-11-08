// Tests idempotent usage snapshots, reconnect accounting and unknown metrics.
package com.fghbuild.gomode.voice

import com.fghbuild.voicegateway.sdk.v2.MessageKind
import com.fghbuild.voicegateway.sdk.v2.SessionUsage
import com.fghbuild.voicegateway.sdk.v2.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceUsageTest {
    @Test
    fun responseReportsMatchOnlyTheirOwnAssistantEntryAndSurviveCacheEviction() {
        fun report(
            id: String?,
            tokens: Long,
        ) = SessionUsage(
            MessageKind.SessionUsage,
            "one",
            "test",
            "model",
            TokenUsage(inputTokens = 100),
            lastResponse = TokenUsage(inputTokens = 999),
            responseID = id,
            responseUsage = TokenUsage(inputTokens = tokens, outputTokens = 0),
        )
        var state =
            VoiceState()
                .withUsageReport(report("a", 10))
                .withTranscriptChunk(TranscriptSpeaker.ASSISTANT, "First", responseID = "a")
                .withTranscriptChunk(TranscriptSpeaker.ASSISTANT, "Reason", TranscriptKind.REASONING, "a")
        state =
            state
                .copy(transcript = state.transcript.map { it.copy(final = true) })
                .withTranscriptChunk(TranscriptSpeaker.ASSISTANT, "Second", responseID = "b")
                .withTranscriptChunk(TranscriptSpeaker.ASSISTANT, " continued", responseID = "a")
                .withUsageReport(report("b", 20))
                .withUsageReport(report("a", 11))
                .withUsageReport(report("a", 11))
                .withTranscriptChunk(TranscriptSpeaker.ASSISTANT, "Uncorrelated")
                .withUsageReport(report(null, 99))
        assertEquals(4, state.transcript.size)
        assertEquals("First continued", state.transcript[0].text)
        assertTrue(state.transcript[0].final)
        assertEquals(11L, state.transcript[0].responseUsage?.inputTokens)
        assertNull(state.transcript[1].responseUsage)
        assertEquals(20L, state.transcript[2].responseUsage?.inputTokens)
        assertNull(state.transcript[3].responseUsage)
        assertEquals(100L, state.usage?.totals?.inputTokens)
        repeat(257) { state = state.withUsageReport(report("pending-$it", it.toLong())) }
        state = state.withTranscriptChunk(TranscriptSpeaker.ASSISTANT, " again", responseID = "a")
        assertEquals(11L, state.transcript[0].responseUsage?.inputTokens)
        assertEquals(256, state.usage?.responses?.size)
        val recovered =
            state
                .withUsageReport(report("next/a", 12).copy(sessionID = "two"))
                .withTranscriptChunk(TranscriptSpeaker.ASSISTANT, "New connection", responseID = "next/a")
        assertEquals(11L, recovered.transcript[0].responseUsage?.inputTokens)
        assertEquals(
            12L,
            recovered.transcript
                .last()
                .responseUsage
                ?.inputTokens,
        )
        assertNull(
            VoiceState()
                .withTranscriptChunk(TranscriptSpeaker.ASSISTANT, "Reset", responseID = "a")
                .transcript
                .single()
                .responseUsage,
        )
    }

    @Test
    fun snapshotsReplaceCountsAndReconnectsCarryTheConversation() {
        val first =
            SessionUsage(
                MessageKind.SessionUsage,
                "one",
                "test",
                "model",
                TokenUsage(inputTokens = 100, outputTokens = 0),
            )
        val current = mergeVoiceUsage(null, first)
        assertEquals(100L, current.totals.inputTokens)
        assertEquals(0L, current.totals.outputTokens)
        assertNull(current.totals.cachedTokens)
        assertEquals(current, mergeVoiceUsage(current, first))
        val updated =
            mergeVoiceUsage(
                current,
                first.copy(totals = TokenUsage(inputTokens = 220, outputTokens = 10, cachedTokens = 50)),
            )
        val recovered =
            mergeVoiceUsage(
                updated,
                first.copy(sessionID = "two", totals = TokenUsage(inputTokens = 30, outputTokens = 5)),
            )
        assertEquals(TokenUsage(inputTokens = 250, outputTokens = 15, cachedTokens = 50), recovered.totals)
        assertEquals(recovered, mergeVoiceUsage(recovered, recovered.latest))
        assertNull(mergeVoiceUsage(null, first.copy(totals = TokenUsage())).totals.inputTokens)
    }
}
