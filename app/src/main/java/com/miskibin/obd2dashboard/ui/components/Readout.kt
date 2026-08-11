package com.miskibin.obd2dashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.data.Sample
import java.util.Locale

/** Placeholder shown where a value would be before the car has reported one. */
const val NO_VALUE = "—"

/** Formats a reading for a tile or a legend; null becomes [NO_VALUE]. */
fun formatReading(value: Double?, decimals: Int): String {
    if (value == null || !value.isFinite()) return NO_VALUE
    return "%.${decimals}f".format(Locale.getDefault(), value)
}

/**
 * A 30-second trace drawn under a tile's value.
 *
 * It is deliberately unlabelled — the point is the shape of the last few seconds, and a
 * second set of axes on every tile is exactly the clutter this dashboard avoids.
 */
@Composable
fun Sparkline(
    samples: List<Sample>,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
) {
    Canvas(modifier = modifier) {
        drawSparkline(samples, color, strokeWidth.toPx())
    }
}

private fun DrawScope.drawSparkline(samples: List<Sample>, color: Color, strokeWidth: Float) {
    if (samples.isEmpty()) return
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return

    val values = samples.map(Sample::value)
    val minValue = values.min()
    val maxValue = values.max()
    val span = maxValue - minValue
    val inset = strokeWidth
    val usableHeight = (height - inset * 2).coerceAtLeast(1f)

    fun yOf(value: Float): Float =
        // A constant series has nowhere to go, so it sits on the centre line.
        if (span <= EPSILON) height / 2f
        else inset + usableHeight * (1f - (value - minValue) / span)

    val firstTime = samples.first().timeMillis
    val lastTime = samples.last().timeMillis
    val timeSpan = (lastTime - firstTime).coerceAtLeast(1L).toFloat()

    fun xOf(timeMillis: Long): Float =
        if (samples.size == 1) width else width * (timeMillis - firstTime) / timeSpan

    val line = Path()
    val area = Path()
    samples.forEachIndexed { index, sample ->
        val x = xOf(sample.timeMillis)
        val y = yOf(sample.value)
        if (index == 0) {
            line.moveTo(if (samples.size == 1) 0f else x, y)
            area.moveTo(if (samples.size == 1) 0f else x, y)
        } else {
            line.lineTo(x, y)
            area.lineTo(x, y)
        }
    }
    if (samples.size == 1) {
        // One reading is still information: draw it as a flat line across the tile.
        val y = yOf(samples.first().value)
        line.lineTo(width, y)
        area.lineTo(width, y)
    }
    area.lineTo(width, height)
    area.lineTo(0f, height)
    area.close()

    drawPath(
        path = area,
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = 0.22f), Color.Transparent),
            startY = 0f,
            endY = height,
        ),
    )
    drawPath(
        path = line,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}

/**
 * The quiet arc behind the RPM tile.
 *
 * A full round dial would dominate a grid of otherwise flat tiles, so this is a thin
 * 240° sweep that reads as a progress hint rather than an instrument in its own right.
 */
@Composable
fun ArcGauge(
    fraction: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    warningFraction: Float = 0.75f,
    warningColor: Color = color,
    strokeWidth: Dp = 6.dp,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2f
        val diameter = minOf(size.width, size.height) - stroke.width
        if (diameter <= 0f) return@Canvas
        val topLeft = Offset(x = (size.width - diameter) / 2f, y = inset)
        val arcSize = Size(diameter, diameter)
        drawArc(
            color = trackColor,
            startAngle = START_ANGLE,
            sweepAngle = SWEEP_ANGLE,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped <= 0f) return@Canvas
        drawArc(
            color = if (clamped >= warningFraction) warningColor else color,
            startAngle = START_ANGLE,
            sweepAngle = SWEEP_ANGLE * clamped,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
    }
}

private const val START_ANGLE = 150f
private const val SWEEP_ANGLE = 240f
private const val EPSILON = 1e-6f
