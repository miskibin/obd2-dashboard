package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.data.Sample

/**
 * The last minute of one reading, as a line the width of two words.
 *
 * This is not a chart and does not pretend to be one: there is no axis, no scale and no
 * number on it, and it is scaled to its own minimum and maximum so a coolant temperature
 * that moved two degrees fills the same height as a load that swung sixty percent. What it
 * answers is the one thing a bare column of digits cannot — *is this number going
 * somewhere?* — which is the difference between a dashboard that is alive and a list.
 *
 * An earlier version of this app put a trace like this inside a tile twenty-six pixels
 * wide, where it was a texture rather than a signal. At this width, with the value it
 * belongs to right next to it, the shape is legible; and the full trace with its axes is
 * still one tap away in the sheet.
 */
@Composable
fun Sparkline(
    samples: List<Sample>,
    color: Color,
    idleColor: Color,
    windowMillis: Long,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    // Sampled at up to ten a second, a minute is six hundred points inside sixty dp. The
    // extra five hundred and fifty of them are invisible and cost a path segment each.
    val points = remember(samples) { samples.thinnedTo(MAX_POINTS) }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val inset = STROKE.dp.toPx()
        val top = inset
        val usable = (height - inset * 2).coerceAtLeast(1f)

        // A row that has not received anything yet keeps its space and says so with a rule
        // rather than a hole, so the list does not reflow the moment data starts arriving.
        if (points.size < 2) {
            drawLine(
                color = idleColor,
                start = Offset(0f, height / 2f),
                end = Offset(width, height / 2f),
                strokeWidth = 1.dp.toPx(),
            )
            return@Canvas
        }

        val low = points.minOf(Sample::value)
        val high = points.maxOf(Sample::value)
        val span = (high - low).takeIf { it > EPSILON }
        val start = nowMillis - windowMillis

        val offsets = points.map { sample ->
            val x = ((sample.timeMillis - start).toFloat() / windowMillis).coerceIn(0f, 1f) * width
            // A reading that never moved has nowhere to go, so it sits on its own middle.
            val fraction = if (span == null) 0.5f else (sample.value - low) / span
            Offset(x, top + usable * (1f - fraction.coerceIn(0f, 1f)))
        }

        val path = Path().apply {
            moveTo(offsets.first().x, offsets.first().y)
            offsets.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = STROKE.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        // The head, so "now" is findable on a trace that is mostly flat.
        drawCircle(color, radius = HEAD_RADIUS.dp.toPx(), center = offsets.last())
    }
}

/** Every nth sample, keeping the newest one: the head of the trace is the point of it. */
private fun List<Sample>.thinnedTo(limit: Int): List<Sample> {
    if (size <= limit) return this
    val step = size / limit + 1
    return filterIndexed { index, _ -> index % step == 0 || index == lastIndex }
}

private const val MAX_POINTS = 48
private const val STROKE = 1.4f
private const val HEAD_RADIUS = 1.7f
private const val EPSILON = 1e-6f
