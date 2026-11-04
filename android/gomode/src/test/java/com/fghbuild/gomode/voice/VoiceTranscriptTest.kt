// Unit tests for transcript entry merging across reasoning and spoken text.
package com.fghbuild.gomode.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTranscriptTest {
    @Test
    fun reasoningMergesIntoItsOwnEntryAndNeverIntoSpokenText() {
        val transcript =
            emptyList<TranscriptEntry>()
                .appendChunk(TranscriptSpeaker.ASSISTANT, "The first date ")
                .appendChunk(TranscriptSpeaker.ASSISTANT, "is earlier.")
                .appendChunk(TranscriptSpeaker.ASSISTANT, "Weigh both ", TranscriptKind.REASONING)
                .appendChunk(TranscriptSpeaker.ASSISTANT, "dates.", TranscriptKind.REASONING)
        assertEquals(
            listOf(
                TranscriptEntry(TranscriptSpeaker.ASSISTANT, "The first date is earlier."),
                TranscriptEntry(TranscriptSpeaker.ASSISTANT, "Weigh both dates.", kind = TranscriptKind.REASONING),
            ),
            transcript,
        )
    }

    @Test
    fun aFinishedEntryStartsANewChunkOfTheSameKind() {
        val finished = listOf(TranscriptEntry(TranscriptSpeaker.ASSISTANT, "Earlier.", final = true))
        assertEquals(
            listOf(
                TranscriptEntry(TranscriptSpeaker.ASSISTANT, "Earlier.", final = true),
                TranscriptEntry(TranscriptSpeaker.ASSISTANT, "Then.", kind = TranscriptKind.REASONING),
            ),
            finished.appendChunk(TranscriptSpeaker.ASSISTANT, "Then.", TranscriptKind.REASONING),
        )
    }
}
