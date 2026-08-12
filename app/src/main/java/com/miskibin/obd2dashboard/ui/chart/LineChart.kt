package com.miskibin.obd2dashboard.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.data.NormalBand
import com.miskibin.obd2dashboard.data.Sample
import com.miskibin.obd2dashboard.ui.theme.BandLabelTextStyle
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.Moss
import com.miskibin.obd2dashboard.ui.theme.Signal
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.SlateFaint
import com.miskibin.obd2dashboard.ui.theme.SlateTrack
import com.miskibin.obd2dashboard.ui.theme.TickTextStyle

/** One line on the chart. */
data class ChartSeries(
    val key: String,
    val label: String,
    val color: Color,
    val unit: String,
    val decimals: Int,
    val samples: List<Sample>,
)

/**
 * How several traces share one plot.
 *
 * There is no honest single answer: boost in bar and revs in rpm cannot share an axis,
 * but stacking every trace in its own strip hides how they line up. So the driver picks,
 * and each mode says in one line what it is doing to the numbers.
 */
enum class ChartMode { Bands, Relative, Absolute }

/**
 * Everything the plot paints with that is not a series colour.
 *
 * A [DrawScope] is not a composition and cannot ask which ground it is drawing on, so the
 * gridlines, the baseline, the band and the tick colour are read once at the top of
 * [LineChart] and carried down. It is also the reason the chart cannot quietly go on using
 * a dark grey on paper: there is nowhere left to hard-code one.
 */
private data class ChartInk(
    val marker: Color,
    val baseline: Color,
    val gridStrong: Color,
    val gridFaint: Color,
    val band: Color,
    val label: Color,
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
    mode: ChartMode,
    nowLabel: String,
    modifier: Modifier = Modifier,
    band: NormalBand? = null,
    markerFraction: Float? = null,
) {
    val measurer = rememberTextMeasurer()
    val tickStyle = TickTextStyle.copy(color = Fog)
    val ink = ChartInk(
        marker = Signal,
        baseline = SlateEdge,
        gridStrong = SlateTrack,
        gridFaint = SlateFaint,
        band = Moss.copy(alpha = BAND_ALPHA),
        label = Fog,
    )

    Canvas(modifier = modifier) {
        val leftPadding = if (mode == ChartMode.Bands) 0f else LEFT_PADDING.dp.toPx()
        val bottomPadding = BOTTOM_PADDING.dp.toPx()
        val topPadding = TOP_PADDING.dp.toPx()
        val rightPadding = RIGHT_PADDING.dp.toPx()
        val plotWidth = size.width - leftPadding - rightPadding
        val plotHeight = size.height - topPadding - bottomPadding
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        drawTimeAxis(
            windowMillis = windowMillis,
            nowLabel = nowLabel,
            measurer = measurer,
            labelStyle = tickStyle,
            leftPadding = leftPadding,
            topPadding = topPadding,
            plotWidth = plotWidth,
            plotHeight = plotHeight,
        )

        // The instant the fault was set, marked before the traces so a line crossing it
        // stays readable.
        if (markerFraction != null) {
            val x = leftPadding + plotWidth * markerFraction.coerceIn(0f, 1f)
            drawLine(
                color = ink.marker,
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + plotHeight),
                strokeWidth = MARKER_WIDTH.dp.toPx(),
            )
        }

        when (mode) {
            ChartMode.Bands -> drawBands(
                series = series,
                measurer = measurer,
                ink = ink,
                windowMillis = windowMillis,
                nowMillis = nowMillis,
                left = leftPadding,
                top = topPadding,
                width = plotWidth,
                height = plotHeight,
            )

            ChartMode.Relative -> {
                drawGrid(
                    labels = RELATIVE_TICKS,
                    fractions = RELATIVE_TICKS.indices.map { 1f - it / (RELATIVE_TICKS.size - 1f) },
                    measurer = measurer,
                    labelStyle = tickStyle,
                    ink = ink,
                    leftPadding = leftPadding,
                    topPadding = topPadding,
                    plotWidth = plotWidth,
                    plotHeight = plotHeight,
                )
                series.forEach { line ->
                    val values = line.samples.map(Sample::value)
                    val low = values.minOrNull() ?: 0f
                    val high = values.maxOrNull() ?: 1f
                    drawTrace(
                        line = line,
                        windowMillis = windowMillis,
                        nowMillis = nowMillis,
                        left = leftPadding,
                        top = topPadding,
                        width = plotWidth,
                        height = plotHeight,
                        low = low,
                        high = high,
                        fill = series.size == 1,
                    )
                }
            }

            ChartMode.Absolute -> {
                // A band that sits outside the readings still has to be visible, otherwise
                // "you are well under the limit" looks identical to "there is no limit".
                val bandValues = listOfNotNull(band?.min?.toFloat(), band?.max?.toFloat())
                val ticks = ChartAxis.ticksOf(
                    series.flatMap { line -> line.samples.map(Sample::value) } + bandValues,
                )
                if (band != null) {
                    drawNormalBand(
                        band = band,
                        ticks = ticks,
                        color = ink.band,
                        left = leftPadding,
                        top = topPadding,
                        width = plotWidth,
                        height = plotHeight,
                    )
                }
                drawGrid(
                    labels = ticks.values.map { ChartAxis.formatLabel(it, ticks.step) },
                    fractions = ticks.values.map { ticks.fraction(it).toFloat() },
                    measurer = measurer,
                    labelStyle = tickStyle,
                    ink = ink,
                    leftPadding = leftPadding,
                    topPadding = topPadding,
                    plotWidth = plotWidth,
                    plotHeight = plotHeight,
                )
                series.forEach { line ->
                    drawTrace(
                        line = line,
                        windowMillis = windowMillis,
                        nowMillis = nowMillis,
                        left = leftPadding,
                        top = topPadding,
                        width = plotWidth,
                        height = plotHeight,
                        low = ticks.min.toFloat(),
                        high = ticks.max.toFloat(),
                        fill = series.size == 1,
                    )
                }
            }
        }
    }
}

/**
 * One strip per series, each scaled to its own range.
 *
 * This is the mode that answers "did the mixture go lean at the same moment the load
 * spiked?", which is the question a shared axis flattens away when one series happens to
 * be measured in thousands and the other in single digits.
 */
private fun DrawScope.drawBands(
    series: List<ChartSeries>,
    measurer: TextMeasurer,
    ink: ChartInk,
    windowMillis: Long,
    nowMillis: Long,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
) {
    if (series.isEmpty()) return
    val gap = BAND_GAP.dp.toPx()
    val bandHeight = (height - gap * (series.size - 1)) / series.size
    if (bandHeight <= 0f) return
    val labelInset = BAND_LABEL_INSET.dp.toPx()

    series.forEachIndexed { index, line ->
        val bandTop = top + index * (bandHeight + gap)
        val bandBottom = bandTop + bandHeight
        val values = line.samples.map(Sample::value)
        val low = values.minOrNull() ?: 0f
        val high = values.maxOrNull() ?: 1f

        drawLine(
            color = if (index == 0) ink.gridStrong else ink.gridFaint,
            start = Offset(left, bandTop),
            end = Offset(left + width, bandTop),
            strokeWidth = 1.dp.toPx(),
        )
        drawTrace(
            line = line,
            windowMillis = windowMillis,
            nowMillis = nowMillis,
            left = left,
            top = bandTop + labelInset,
            width = width,
            height = bandBottom - bandTop - labelInset,
            low = low,
            high = high,
            fill = false,
        )

        val name = measurer.measure(line.label, BandLabelTextStyle.copy(color = line.color))
        drawText(name, topLeft = Offset(left + 2.dp.toPx(), bandTop + 2.dp.toPx()))
        if (values.isEmpty()) return@forEachIndexed
        val range = measurer.measure(
            text = "${format(low, line.decimals)}–${format(high, line.decimals)} ${line.unit}".trim(),
            style = BandLabelTextStyle.copy(color = ink.label),
        )
        drawText(
            textLayoutResult = range,
            topLeft = Offset(left + width - range.size.width, bandTop + 2.dp.toPx()),
        )
    }
    drawLine(
        color = ink.baseline,
        start = Offset(left, top + height),
        end = Offset(left + width, top + height),
        strokeWidth = 1.dp.toPx(),
    )
}

/**
 * The range the value is supposed to stay inside, painted behind the trace.
 *
 * An open-ended band — a ceiling with no floor, which is what most warnings are — is
 * drawn all the way to the edge of the plot rather than stopping at the lowest reading,
 * because "anything below this is fine" is exactly what it means.
 */
private fun DrawScope.drawNormalBand(
    band: NormalBand,
    ticks: AxisTicks,
    color: Color,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
) {
    val lowFraction = band.min?.let { ticks.fraction(it) }?.coerceIn(0.0, 1.0) ?: 0.0
    val highFraction = band.max?.let { ticks.fraction(it) }?.coerceIn(0.0, 1.0) ?: 1.0
    if (highFraction <= lowFraction) return
    val bandTop = top + height * (1f - highFraction.toFloat())
    val bandHeight = height * (highFraction - lowFraction).toFloat()
    drawRect(
        color = color,
        topLeft = Offset(left, bandTop),
        size = Size(width, bandHeight),
    )
}

private fun DrawScope.drawTrace(
    line: ChartSeries,
    windowMillis: Long,
    nowMillis: Long,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    low: Float,
    high: Float,
    fill: Boolean,
) {
    val span = (high - low).takeIf { it > EPSILON }
    val startMillis = nowMillis - windowMillis
    val points = line.samples.mapNotNull { sample ->
        if (!sample.value.isFinite()) return@mapNotNull null
        val x = (sample.timeMillis - startMillis).toFloat() / windowMillis * width
        if (x < -width) return@mapNotNull null
        // A series that never moved has nowhere to go, so it sits on its own centre line.
        val fraction = if (span == null) 0.5f else (sample.value - low) / span
        Offset(
            x = left + x.coerceIn(0f, width),
            y = top + height * (1f - fraction.coerceIn(0f, 1f)),
        )
    }
    drawSeries(line.color, points, fill, top + height)
}

private fun DrawScope.drawSeries(
    color: Color,
    points: List<Offset>,
    fill: Boolean,
    baseline: Float,
) {
    when {
        points.isEmpty() -> return
        // A lone reading has no line to draw, so mark where it is rather than showing
        // an empty plot.
        points.size == 1 -> drawCircle(color, radius = POINT_RADIUS.dp.toPx(), center = points.first())

        else -> {
            val path = smoothPath(points)
            if (fill) {
                val area = Path().apply {
                    addPath(path)
                    lineTo(points.last().x, baseline)
                    lineTo(points.first().x, baseline)
                    close()
                }
                drawPath(
                    path = area,
                    brush = Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = FILL_ALPHA), Color.Transparent),
                        startY = points.minOf(Offset::y),
                        endY = baseline,
                    ),
                )
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = STROKE_WIDTH.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            // The head of the trace, so "now" is findable when six lines overlap.
            drawCircle(color, radius = HEAD_RADIUS.dp.toPx(), center = points.last())
        }
    }
}

private fun DrawScope.drawGrid(
    labels: List<String>,
    fractions: List<Float>,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    ink: ChartInk,
    leftPadding: Float,
    topPadding: Float,
    plotWidth: Float,
    plotHeight: Float,
) {
    fractions.forEachIndexed { index, fraction ->
        val y = topPadding + plotHeight * (1f - fraction)
        if (y < topPadding - 1f || y > topPadding + plotHeight + 1f) return@forEachIndexed
        drawLine(
            color = when {
                fraction <= 0f -> ink.baseline
                index % 2 == 0 -> ink.gridStrong
                else -> ink.gridFaint
            },
            start = Offset(leftPadding, y),
            end = Offset(leftPadding + plotWidth, y),
            strokeWidth = 1.dp.toPx(),
        )
        val label = measurer.measure(labels.getOrElse(index) { "" }, labelStyle)
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
    nowLabel: String,
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
        val text = if (secondsAgo == 0) nowLabel else "−${formatSeconds(secondsAgo)}"
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

private fun format(value: Float, decimals: Int): String =
    "%.${decimals}f".format(java.util.Locale.getDefault(), value)

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

private val RELATIVE_TICKS = listOf("100%", "75%", "50%", "25%", "0%")

private const val LEFT_PADDING = 44
private const val RIGHT_PADDING = 8
private const val TOP_PADDING = 10
private const val BOTTOM_PADDING = 20
private const val LABEL_GAP = 4

/**
 * A hairline, not a marker pen.
 *
 * A trace is read for its shape, and a thick stroke turns a lively signal into a fat
 * ribbon that hides its own detail — worse still with six of them crossing. The head dot
 * and the fill under a lone series are sized to match, so nothing outweighs the line.
 */
private const val STROKE_WIDTH = 1.5f
private const val POINT_RADIUS = 3
private const val HEAD_RADIUS = 2f
private const val FILL_ALPHA = 0.14f
private const val BAND_GAP = 6
private const val BAND_LABEL_INSET = 11
private const val BAND_ALPHA = 0.12f
private const val MARKER_WIDTH = 1.5f
private const val EPSILON = 1e-6f
