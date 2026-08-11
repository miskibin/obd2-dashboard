package com.miskibin.obd2dashboard.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miskibin.obd2dashboard.data.Sample

/** One line on the chart. */
data class ChartSeries(
    val key: String,
    val label: String,
    val color: Color,
    val samples: List<Sample>,
)

/**
 * Live scrolling line chart drawn straight onto a Canvas.
 *
 * Everything a charting library would bring — axes, ticks, smoothing, fills — is a few
 * dozen lines here, and drawing it directly is what lets the axis stay honest about
 * degenerate data instead of collapsing or auto-hiding.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    windowMillis: Long,
    nowMillis: Long,
    axisColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)

    Canvas(modifier = modifier) {
        val leftPadding = LEFT_PADDING.dp.toPx()
        val bottomPadding = BOTTOM_PADDING.dp.toPx()
        val topPadding = TOP_PADDING.dp.toPx()
        val rightPadding = RIGHT_PADDING.dp.toPx()
        val plotWidth = size.width - leftPadding - rightPadding
        val plotHeight = size.height - topPadding - bottomPadding
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        val values = series.flatMap { line -> line.samples.map(Sample::value) }
        val ticks = ChartAxis.ticksOf(values)

        drawGrid(
            ticks = ticks,
            measurer = measurer,
            labelStyle = labelStyle,
            axisColor = axisColor,
            leftPadding = leftPadding,
            topPadding = topPadding,
            plotWidth = plotWidth,
            plotHeight = plotHeight,
        )
        drawTimeAxis(
            windowMillis = windowMillis,
            measurer = measurer,
            labelStyle = labelStyle,
            leftPadding = leftPadding,
            topPadding = topPadding,
            plotWidth = plotWidth,
            plotHeight = plotHeight,
        )

        val fillAlpha = if (series.size == 1) SOLO_FILL_ALPHA else GROUP_FILL_ALPHA
        val startMillis = nowMillis - windowMillis
        series.forEach { line ->
            val points = line.samples.mapNotNull { sample ->
                val x = (sample.timeMillis - startMillis).toFloat() / windowMillis * plotWidth
                if (x < -plotWidth || !sample.value.isFinite()) return@mapNotNull null
                Offset(
                    x = leftPadding + x.coerceIn(0f, plotWidth),
                    y = topPadding + plotHeight * (1f - ticks.fraction(sample.value.toDouble()).toFloat()),
                )
            }
            drawSeries(line.color, points, fillAlpha, topPadding + plotHeight)
        }
    }
}

private fun DrawScope.drawSeries(
    color: Color,
    points: List<Offset>,
    fillAlpha: Float,
    baseline: Float,
) {
    when {
        points.isEmpty() -> return
        // A lone reading has no line to draw, so mark where it is rather than showing
        // an empty plot.
        points.size == 1 -> drawCircle(color, radius = POINT_RADIUS.dp.toPx(), center = points.first())

        else -> {
            val line = smoothPath(points)
            val area = Path().apply {
                addPath(line)
                lineTo(points.last().x, baseline)
                lineTo(points.first().x, baseline)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = fillAlpha), Color.Transparent),
                    startY = points.minOf(Offset::y),
                    endY = baseline,
                ),
            )
            drawPath(
                path = line,
                color = color,
                style = Stroke(
                    width = STROKE_WIDTH.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

private fun DrawScope.drawGrid(
    ticks: AxisTicks,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    axisColor: Color,
    leftPadding: Float,
    topPadding: Float,
    plotWidth: Float,
    plotHeight: Float,
) {
    val dashes = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
    ticks.values.forEach { tick ->
        val y = topPadding + plotHeight * (1f - ticks.fraction(tick).toFloat())
        if (y < topPadding - 1f || y > topPadding + plotHeight + 1f) return@forEach
        drawLine(
            color = axisColor,
            start = Offset(leftPadding, y),
            end = Offset(leftPadding + plotWidth, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dashes,
        )
        val label = measurer.measure(ChartAxis.formatLabel(tick, ticks.step), labelStyle)
        drawText(
            textLayoutResult = label,
            topLeft = Offset(
                x = (leftPadding - LABEL_GAP.dp.toPx() - label.size.width).coerceAtLeast(0f),
                y = y - label.size.height / 2f,
            ),
        )
    }
}

private fun DrawScope.drawTimeAxis(
    windowMillis: Long,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    leftPadding: Float,
    topPadding: Float,
    plotWidth: Float,
    plotHeight: Float,
) {
    val baseline = topPadding + plotHeight
    listOf(0f, 0.5f, 1f).forEach { fraction ->
        val secondsAgo = ((1f - fraction) * windowMillis / 1000f).toInt()
        val text = if (secondsAgo == 0) "0" else "-${formatSeconds(secondsAgo)}"
        val label = measurer.measure(text, labelStyle)
        val x = leftPadding + plotWidth * fraction - label.size.width * fraction
        drawText(
            textLayoutResult = label,
            topLeft = Offset(x, baseline + LABEL_GAP.dp.toPx()),
        )
    }
}

private fun formatSeconds(seconds: Int): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m"

/**
 * Catmull-Rom through every point, emitted as cubic Béziers.
 *
 * Straight segments make a live trace look like it is stepping; interpolating through
 * the points (rather than smoothing past them) keeps every reading truthful.
 */
private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    for (index in 0 until points.lastIndex) {
        val p0 = points[(index - 1).coerceAtLeast(0)]
        val p1 = points[index]
        val p2 = points[index + 1]
        val p3 = points[(index + 2).coerceAtMost(points.lastIndex)]
        path.cubicTo(
            x1 = p1.x + (p2.x - p0.x) / 6f,
            y1 = p1.y + (p2.y - p0.y) / 6f,
            x2 = p2.x - (p3.x - p1.x) / 6f,
            y2 = p2.y - (p3.y - p1.y) / 6f,
            x3 = p2.x,
            y3 = p2.y,
        )
    }
    return path
}

private const val LEFT_PADDING = 44
private const val RIGHT_PADDING = 8
private const val TOP_PADDING = 10
private const val BOTTOM_PADDING = 22
private const val LABEL_GAP = 4
private const val STROKE_WIDTH = 2
private const val POINT_RADIUS = 3
private const val SOLO_FILL_ALPHA = 0.20f
private const val GROUP_FILL_ALPHA = 0.08f
