// Removes the supported Markdown subset from streamed text for client-owned speech.
package com.fghbuild.gomode.voice

// Speech-only, not CommonMark: line headings/lists, emphasis, backticks, fenced
// code (without language labels), and inline link labels. Code content is literal.
// Inline code is line-local: newline or CR ends even an unmatched backtick span.
// Bracketed labels lose brackets even without a destination. No reference links,
// HTML or indented code parsing. Only prefix heading/list markers are recognized.
// ASCII punctuation escapes are literal outside code. Emphasis uses 1–3 matching
// asterisks/underscores, non-word outer boundaries and non-space inner boundaries.
// A pair cannot cross an enclosing label boundary or close within a nested label.
// Only proven pairs are removed; emitted speech is never retracted. Ambiguity is
// line-local and bounded: both delimiters plus closing lookahead must
// fit in 256 UTF-16 units; otherwise preserve the opening run literally. Thus long
// emphasis stays literal, intraword operators/identifiers survive, and _name_ is
// emphasis (escape it or use code for a literal). No whole reply/line is buffered.
// An unfinished destination is omitted through newline/end; nesting is capped at 32.
// Keep equivalent to web/src/SpeechTextFilter.ts and testdata/speech-text/golden.json.
internal class SpeechTextFilter {
    private var pending = ""
    private var lineStart = true
    private var fence = ""
    private var skipLine = false
    private var inlineCode = false
    private var label = false
    private var destinationDepth = 0
    private var previous = ""

    fun push(text: String): String =
        buildString {
            for (c in text) {
                pending += c
                append(drain(false))
            }
        }

    fun finish(): String {
        val output = drain(true)
        clear()
        return output
    }

    fun clear() {
        pending = ""
        lineStart = true
        fence = ""
        skipLine = false
        inlineCode = false
        label = false
        destinationDepth = 0
        previous = ""
    }

    // null waits for bounded lookahead; zero preserves a literal run.
    private fun emphasisEnd(
        count: Int,
        final: Boolean,
    ): Int? {
        val marker = pending[0]
        if (count > 3 || WORD.matches(previous) || previous == marker.toString()) return 0
        if (pending.getOrNull(count)?.isWhitespace() == true) return 0
        var code = false
        var label = false
        var destination = 0
        var i = count
        while (i < pending.length) {
            val c = pending[i]
            if (c == '\n' || c == '\r') return 0
            if (destination > 0) {
                if (c == '(') destination = minOf(32, destination + 1)
                if (c == ')') destination--
            } else if (c == '\\' && !code &&
                ASCII_PUNCTUATION.matches(pending.getOrNull(i + 1)?.toString().orEmpty())
            ) {
                i++
            } else if (c == '`') {
                val ticks = checkNotNull(TICKS.find(pending.drop(i))).value
                i += ticks.length - 1
                code = !code
            } else if (!code && c == '[') {
                label = true
            } else if (!code && c == ']') {
                if (label) {
                    label = false
                    if (pending.getOrNull(i + 1) == '(') {
                        destination = 1
                        i++
                    }
                } else if (this.label) {
                    return 0
                }
            } else if (!code && !label && c == marker) {
                var end = i
                while (pending.getOrNull(end) == marker) end++
                val run = end - i
                if (run in count..3 && !pending[i - 1].isWhitespace()) {
                    if (end == pending.length && !final) break
                    if (end < EMPHASIS_LOOKAHEAD && !WORD.matches(pending.getOrNull(end)?.toString().orEmpty())) {
                        return end - count
                    }
                }
                i = end - 1
            }
            i++
        }
        return if (final || pending.length >= EMPHASIS_LOOKAHEAD) 0 else null
    }

    private fun drain(final: Boolean): String =
        buildString {
            while (pending.isNotEmpty()) {
                val c = pending[0]
                var count = 1
                var spoken = ""
                if (c == '\n' || c == '\r') {
                    spoken = c.toString()
                    lineStart = true
                    skipLine = false
                    inlineCode = false
                    destinationDepth = 0
                    label = false
                } else if (skipLine) {
                    // Fence labels and closing fence lines are discarded incrementally.
                } else if (destinationDepth > 0) {
                    if (c == '(') destinationDepth = minOf(32, destinationDepth + 1)
                    if (c == ')') destinationDepth--
                } else {
                    if (lineStart && !inlineCode) {
                        if (c == ' ' || c == '\t') {
                            append(c)
                            pending = pending.drop(1)
                            continue
                        }
                        val fenceMarker = FENCE.find(pending)?.value
                        if (fenceMarker != null && (fence.isEmpty() || fence == fenceMarker)) {
                            fence = if (fence.isEmpty()) fenceMarker else ""
                            skipLine = true
                            pending = pending.drop(3)
                            continue
                        }
                        if (!final && FENCE_PREFIX.matches(pending)) break
                        if (fence.isEmpty()) {
                            val marker = MARKER.find(pending)?.value
                            if (marker != null) {
                                pending = pending.drop(marker.length)
                                lineStart = false
                                continue
                            }
                            if (!final && MARKER_PREFIX.matches(pending)) break
                        }
                        lineStart = false
                    }
                    when {
                        fence.isNotEmpty() -> {
                            spoken = c.toString()
                        }

                        c == '`' -> {
                            val ticks = checkNotNull(TICKS.find(pending)).value
                            if (!final && ticks.length == pending.length && ticks.length < 3) break
                            count = ticks.length
                            inlineCode = !inlineCode
                        }

                        inlineCode -> {
                            spoken = c.toString()
                        }

                        c == '\\' -> {
                            if (!final && pending.length == 1) break
                            if (ASCII_PUNCTUATION.matches(pending.getOrNull(1)?.toString().orEmpty())) {
                                count = 2
                                spoken = pending[1].toString()
                            } else {
                                spoken = c.toString()
                            }
                        }

                        c == '_' || c == '*' -> {
                            val markers = checkNotNull(EMPHASIS.find(pending)).value
                            count = markers.length
                            if (!final && count == pending.length && count < 4) break
                            val end = emphasisEnd(count, final) ?: break
                            if (end > 0) {
                                // Finish the bounded inner slice before parsing adjacent raw tokens.
                                // The closing delimiter also ends any inner unmatched emphasis candidate.
                                val remaining = pending.drop(end + count)
                                pending = pending.substring(count, end)
                                append(drain(true))
                                pending = remaining
                                previous = ""
                                continue
                            }
                            spoken = markers
                        }

                        c == '[' -> {
                            label = true
                        }

                        c == ']' && label -> {
                            if (!final && pending.length == 1) break
                            label = false
                            if (pending.getOrNull(1) == '(') {
                                count = 2
                                destinationDepth = 1
                            }
                        }

                        else -> {
                            spoken = c.toString()
                        }
                    }
                }
                append(spoken)
                if (spoken.isNotEmpty()) previous = spoken.takeLast(1)
                pending = pending.drop(count)
            }
        }

    private companion object {
        val FENCE = Regex("^(?:`{3}|~{3})")
        val FENCE_PREFIX = Regex("^(?:`{1,2}|~{1,2})$")
        val MARKER = Regex("^(?:#{1,6}|[-+*]|\\d{1,9}[.)])[ \\t]")
        val MARKER_PREFIX = Regex("^(?:#{1,6}|[-+*]|\\d{1,9}[.)]?)$")
        val TICKS = Regex("^`{1,3}")
        const val EMPHASIS_LOOKAHEAD = 256
        val EMPHASIS = Regex("^(?:\\*{1,4}|_{1,4})")
        val WORD = Regex("^[\\p{L}\\p{N}\\p{M}\\uD800-\\uDFFF]$")
        val ASCII_PUNCTUATION = Regex("^[\\x21-\\x2f\\x3a-\\x40\\x5b-\\x60\\x7b-\\x7e]$")
    }
}
