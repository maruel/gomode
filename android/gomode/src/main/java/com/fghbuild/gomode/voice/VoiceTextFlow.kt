// Flows baseline-aligned voice usage metrics after the final line of text.
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

@Composable
internal fun VoiceTextFlow(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    metrics: (@Composable () -> Unit)? = null,
) {
    if (metrics == null) {
        Text(text, style = style, color = color, modifier = modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val measured =
            remember(text, style, width, measurer) {
                measurer.measure(text, style, constraints = Constraints.fixedWidth(width))
            }
        val line = measured.lineCount - 1
        val lineTop = measured.getLineTop(line).toInt()
        val baseline = (measured.getLineBaseline(line) - measured.getLineTop(line)).toInt()
        val rtl = measured.getParagraphDirection((text.length - 1).coerceAtLeast(0)) == ResolvedTextDirection.Rtl
        val cursor =
            if (rtl) {
                width - measured.getLineLeft(line).toInt()
            } else {
                ceil(measured.getLineRight(line).toDouble()).toInt()
            }
        Layout(
            content = {
                Text(text, style = style, color = color, modifier = Modifier.testTag("gomode-voice-text-part"))
                InlineMetrics(cursor, baseline, rtl, metrics)
            },
        ) { measurables, constraints ->
            val paragraph = measurables.first().measure(Constraints.fixedWidth(width))
            val counts = measurables.last().measure(Constraints.fixedWidth(width))
            layout(width, constraints.constrainHeight(maxOf(paragraph.height, lineTop + counts.height))) {
                paragraph.placeRelative(0, 0)
                counts.placeRelative(0, lineTop)
            }
        }
    }
}

@Composable
private fun InlineMetrics(
    cursor: Int,
    baseline: Int,
    rtl: Boolean,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val items = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }

        var x = cursor + if (cursor > 0) gap else 0
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

            if (x + item.width > constraints.maxWidth) {
                rows.add(row)
                baselines.add(y + ascent)
                y += ascent + descent + gap
                row = mutableListOf()
                ascent = 0
                descent = 0
                x = 0
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
