// Flows voice text and baseline-aligned metrics around the floating close target.
package com.fghbuild.gomode.voice

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

internal data class TextExclusion(
    val height: Int,
    val width: Int,
)

private data class TextPart(
    val text: AnnotatedString,
    val layout: TextLayoutResult,
    val top: Int,
)

// Only complete lines are split. TextMeasurer and Text share typography, annotations and widths.
private fun flowingParts(
    text: AnnotatedString,
    style: TextStyle,
    width: Int,
    exclusion: TextExclusion?,
    measurer: TextMeasurer,
): List<TextPart> {
    val parts = mutableListOf<TextPart>()
    var offset = 0
    var top = 0
    var continuation: TextDirection? = null

    fun append(
        maxWidth: Int,
        until: Int?,
    ) {
        if (offset >= text.length && parts.isNotEmpty()) return
        val rest = text.subSequence(offset, text.length)
        val direction = continuation
        val remaining =
            if (direction == null) {
                rest
            } else {
                AnnotatedString
                    .Builder(rest)
                    .apply {
                        val paragraphEnd = rest.text.indexOf('\n').let { if (it < 0) rest.length else it + 1 }
                        addStyle(ParagraphStyle(textDirection = direction), 0, paragraphEnd)
                    }.toAnnotatedString()
            }
        val measured = measurer.measure(remaining, style, constraints = Constraints(maxWidth = maxWidth))
        var lines = measured.lineCount
        if (until != null) {
            lines = 0
            while (lines < measured.lineCount && top + measured.getLineTop(lines) < until) lines++
        }
        if (lines == 0) return
        val end = measured.getLineEnd(lines - 1)
        val part =
            remaining.subSequence(0, end).let {
                // A consumed paragraph break starts the next part; don't render an extra empty line.
                if (end < remaining.length && it.text.endsWith('\n')) it.subSequence(0, it.length - 1) else it
            }
        val layout =
            if (end ==
                remaining.length
            ) {
                measured
            } else {
                measurer.measure(part, style, constraints = Constraints(maxWidth = maxWidth))
            }
        parts.add(TextPart(part, layout, top))
        top += layout.size.height
        offset += end
        continuation =
            if (end > 0 && remaining[end - 1] != '\n') {
                if (measured.getParagraphDirection(end - 1) ==
                    ResolvedTextDirection.Ltr
                ) {
                    TextDirection.Ltr
                } else {
                    TextDirection.Rtl
                }
            } else {
                null
            }
    }
    if (exclusion != null) {
        append((width - exclusion.width).coerceAtLeast(1), exclusion.height)
    }
    append(width, null)
    return parts
}

@Composable
internal fun VoiceTextFlow(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    exclusion: TextExclusion?,
    modifier: Modifier = Modifier,
    metrics: (@Composable () -> Unit)? = null,
) {
    if (exclusion == null && metrics == null) {
        Text(text, style = style, color = color, modifier = modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val parts =
            remember(text, style, width, exclusion, measurer) {
                flowingParts(text, style, width, exclusion, measurer)
            }
        val last = parts.last()
        val line = last.layout.lineCount - 1
        val lineTop = last.top + last.layout.getLineTop(line).toInt()
        val baseline = (last.layout.getLineBaseline(line) - last.layout.getLineTop(line)).toInt()
        val rtl =
            last.layout.getParagraphDirection((last.text.length - 1).coerceAtLeast(0)) == ResolvedTextDirection.Rtl
        val excludedOnRight = LocalLayoutDirection.current == LayoutDirection.Ltr
        val partLeft = if (excludedOnRight) 0 else width - last.layout.layoutInput.constraints.maxWidth
        val cursor =
            if (rtl) {
                width - (partLeft + last.layout.getLineLeft(line)).toInt()
            } else {
                partLeft + ceil(last.layout.getLineRight(line).toDouble()).toInt()
            }
        Layout(
            content = {
                TextParts(text, parts, style, color, width)
                if (metrics != null) {
                    InlineMetrics(cursor, baseline, lineTop, exclusion, rtl, rtl == excludedOnRight, metrics)
                }
            },
        ) { measurables, constraints ->
            val paragraph = measurables.first().measure(Constraints.fixedWidth(width))
            val counts = if (metrics != null) measurables.last().measure(Constraints.fixedWidth(width)) else null
            layout(width, constraints.constrainHeight(maxOf(paragraph.height, lineTop + (counts?.height ?: 0)))) {
                paragraph.placeRelative(0, 0)
                counts?.placeRelative(0, lineTop)
            }
        }
    }
}

@Composable
private fun TextParts(
    text: AnnotatedString,
    parts: List<TextPart>,
    style: TextStyle,
    color: Color,
    width: Int,
) {
    Layout(
        modifier =
            Modifier.clearAndSetSemantics {
                this.text = text
                // Accessibility interprets a layout against the node's complete text.
                // Split or inset text has no complete layout at this node's origin.
                if (parts.size == 1 && parts
                        .single()
                        .layout.layoutInput.constraints.maxWidth == width
                ) {
                    getTextLayoutResult { results ->
                        results.add(parts.single().layout)
                        true
                    }
                }
            },
        content = {
            parts.forEach { part ->
                Text(part.text, style = style, color = color, modifier = Modifier.testTag("gomode-voice-text-part"))
            }
        },
    ) { measurables, constraints ->
        val texts =
            parts.mapIndexed { index, part ->
                measurables[index].measure(Constraints.fixedWidth(part.layout.layoutInput.constraints.maxWidth))
            }
        layout(constraints.maxWidth, parts.last().top + texts.last().height) {
            texts.forEachIndexed { index, item -> item.placeRelative(0, parts[index].top) }
        }
    }
}

@Composable
private fun InlineMetrics(
    cursor: Int,
    baseline: Int,
    top: Int,
    exclusion: TextExclusion?,
    rtl: Boolean,
    exclusionAtStart: Boolean,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val items = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }

        fun startInset(y: Int): Int =
            if (exclusionAtStart && exclusion != null &&
                top + y < exclusion.height
            ) {
                exclusion.width
            } else {
                0
            }
        var x = maxOf(cursor + if (cursor > 0) gap else 0, startInset(0))
        var y = 0
        var ascent = baseline
        var descent = 0
        val rows = mutableListOf<List<Int>>()
        var row = mutableListOf<Int>()
        val positions = mutableListOf<Pair<Int, Int>>()
        val baselines = mutableListOf<Int>()
        items.forEachIndexed { index, item ->
            val itemBaseline =
                item[FirstBaseline].let {
                    if (it ==
                        AlignmentLine.Unspecified
                    ) {
                        item.height
                    } else {
                        it
                    }
                }

            fun availableWidth(): Int {
                val beside = exclusion != null && top + y < exclusion.height
                return constraints.maxWidth -
                    if (beside && !exclusionAtStart && exclusion != null) exclusion.width else 0
            }
            if (x + item.width > availableWidth()) {
                rows.add(row)
                baselines.add(y + ascent)
                y += ascent + descent + gap
                row = mutableListOf()
                ascent = 0
                descent = 0
                x = startInset(y)
                // An indivisible metric that cannot fit beside the target starts below it.
                if (x + item.width > availableWidth() && exclusion != null) {
                    y = maxOf(y, exclusion.height - top)
                    x = 0
                }
            }
            positions.add(x to y)
            row.add(index)
            ascent = maxOf(ascent, itemBaseline)
            descent = maxOf(descent, item.height - itemBaseline)
            x += item.width + gap
        }
        rows.add(row)
        baselines.add(y + ascent)
        layout(constraints.maxWidth, constraints.constrainHeight(y + ascent + descent)) {
            rows.forEachIndexed { rowIndex, indices ->
                indices.forEach { index ->
                    val item = items[index]
                    val itemBaseline =
                        item[FirstBaseline].let {
                            if (it ==
                                AlignmentLine.Unspecified
                            ) {
                                item.height
                            } else {
                                it
                            }
                        }
                    val left = positions[index].first
                    item.place(
                        if (rtl) constraints.maxWidth - left - item.width else left,
                        baselines[rowIndex] - itemBaseline,
                    )
                }
            }
        }
    }
}
