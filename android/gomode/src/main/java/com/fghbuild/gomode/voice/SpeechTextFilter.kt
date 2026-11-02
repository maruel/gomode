// Removes the supported Markdown subset from streamed text for client-owned speech.
package com.fghbuild.gomode.voice

// Speech-only, not CommonMark: line headings/lists, emphasis, backticks, fenced
// code (without language labels), and inline link labels. Code content is literal.
// Inline code is line-local: newline or CR ends even an unmatched backtick span.
// Bracketed labels lose brackets even without a destination. No reference links,
// HTML, escapes, or indented code parsing. Only prefix heading/list markers are
// recognized. Outside code, asterisks and boundary underscores are formatting
// even when unpaired; word-internal underscore runs up to 8 characters are literal.
// Lookahead is at most 12 characters; labels/code/lines are never buffered.
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

                        c == '_' -> {
                            val underscores = checkNotNull(UNDERSCORES.find(pending)).value
                            if (!final && underscores.length == pending.length && underscores.length < 9) break
                            count = underscores.length
                            if (WORD.matches(previous) &&
                                WORD.matches(pending.getOrNull(count)?.toString().orEmpty())
                            ) {
                                spoken = underscores
                            }
                        }

                        c == '*' -> {
                            // Emphasis delimiters do not contribute spoken characters.
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
        val UNDERSCORES = Regex("^_{1,9}")
        val WORD = Regex("^[\\p{L}\\p{N}]$")
    }
}
