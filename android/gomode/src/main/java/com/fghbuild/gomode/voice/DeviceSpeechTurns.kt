// Device speech parts shared by text and WebRTC sessions: streamed synthesis, thinking music, and recognition drafts.
//
// DeviceSpeechOutput owns the path from streamed assistant text to TextToSpeech: SpeechTextFilter
// removes Markdown, SentenceSegmenter cuts sentence-sized fragments, and the fragment count tells
// the session when playback drained. Generation ends a turn's text; playback ends the turn's audio,
// so a gap between fragments does not finish a reply. Sessions own turn state and decide when to
// finish or stop output. All methods and callbacks run on the main thread.
package com.fghbuild.gomode.voice

import com.fghbuild.voicegateway.sdk.v2.TurnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Keep quick turns and short pauses between speech fragments silent.
private const val THINKING_AUDIO_DELAY_MS = 200L

/** DeviceSpeechOutput speaks streamed assistant text through [speech]. */
internal class DeviceSpeechOutput(
    private val speech: DeviceSpeech,
    /** A fragment was queued for playback. */
    private val onSpeaking: () -> Unit,
    /** The last queued fragment finished playing. */
    private val onDrained: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val segmenter = SentenceSegmenter()
    private val filter = SpeechTextFilter()
    private var pending = 0
    private var generation = 0L

    /** True while a queued fragment has not finished playing. */
    val active: Boolean get() = pending > 0

    /** Speaks each sentence [delta] completes. */
    fun append(delta: String) {
        segmenter.push(filter.push(delta)).forEach(::speak)
    }

    /** Speaks the rest of the reply once generation has ended. */
    fun finish() {
        segmenter.push(filter.finish()).forEach(::speak)
        speak(segmenter.flush())
    }

    /** Drops unspoken text without stopping playback. */
    fun clear() {
        segmenter.clear()
        filter.clear()
    }

    /** Drops unspoken text and playback; callbacks of stopped fragments never run. */
    fun stop() {
        generation++
        clear()
        pending = 0
        speech.stopSpeaking()
    }

    private fun speak(fragment: String) {
        if (fragment.isBlank()) return
        val playback = generation
        pending++
        onSpeaking()
        speech.speak(
            fragment,
            onDone = { if (playback == generation) done() },
            onError = { message -> if (playback == generation) onError(message) },
        )
    }

    private fun done() {
        pending = maxOf(0, pending - 1)
        if (pending == 0) onDrained()
    }
}

internal val VoiceState.needsThinkingAudio: Boolean
    get() = connected && turnState == TurnState.Thinking && !speaking && !muted

/** Plays thinking music while [needed] holds for the session state, after a short silence. */
internal fun CoroutineScope.launchThinkingAudio(
    state: StateFlow<VoiceState>,
    earcon: ThinkingEarcon,
    needed: (VoiceState) -> Boolean,
): Job =
    launch(start = CoroutineStart.UNDISPATCHED) {
        state
            .map(needed)
            .distinctUntilChanged()
            .collectLatest { thinking ->
                earcon.setThinking(false)
                if (thinking) {
                    delay(THINKING_AUDIO_DELAY_MS)
                    earcon.setThinking(needed(state.value))
                }
            }
    }

/** Removes the live recognition hypothesis, which is always the last user entry. */
internal fun List<TranscriptEntry>.withoutUserDraft(): List<TranscriptEntry> =
    if (lastOrNull()?.let { it.speaker == TranscriptSpeaker.USER && !it.final } == true) dropLast(1) else this

/** Replaces the live recognition hypothesis; a final entry commits the utterance. */
internal fun List<TranscriptEntry>.replaceUserDraft(
    text: String,
    final: Boolean,
): List<TranscriptEntry> =
    withoutUserDraft() +
        if (text.isBlank()) emptyList() else listOf(TranscriptEntry(TranscriptSpeaker.USER, text, final))

/** SentenceSegmenter splits streamed assistant text into sentence-sized speech fragments. */
internal class SentenceSegmenter {
    private val pending = StringBuilder()

    // Numeric periods need one character of lookahead, including across delta boundaries.
    // Long prose/code without punctuation is emitted in at most 240-character fragments.
    fun push(delta: String): List<String> {
        val fragments = mutableListOf<String>()
        for (c in delta) {
            if (pending.length >= 2 && pending.last() == '.' && pending[pending.length - 2].isDigit() &&
                !c.isDigit()
            ) {
                fragments.add(flush())
            }
            val numericPeriod = c == '.' && pending.lastOrNull()?.isDigit() == true
            pending.append(c)
            if ((c == '.' && !numericPeriod) || c == '!' || c == '?' || c == '\n') {
                val fragment = flush()
                if (fragment.isNotEmpty()) fragments.add(fragment)
            } else if (pending.length >= 240) {
                val space = pending.indexOfLast { it.isWhitespace() }
                val end = if (space > 0) space + 1 else pending.length
                val fragment = pending.substring(0, end).trim()
                if (fragment.isNotEmpty()) fragments.add(fragment)
                pending.delete(0, end)
            }
        }
        return fragments
    }

    fun flush(): String {
        val text = pending.toString().trim()
        pending.clear()
        return text
    }

    fun clear() {
        pending.clear()
    }
}
