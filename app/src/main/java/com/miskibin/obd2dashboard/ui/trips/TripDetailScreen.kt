package com.miskibin.obd2dashboard.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.Trip
import com.miskibin.obd2dashboard.data.TripAnalysis
import com.miskibin.obd2dashboard.data.TripEntry
import com.miskibin.obd2dashboard.data.TripEvent
import com.miskibin.obd2dashboard.data.TripEventKind
import com.miskibin.obd2dashboard.data.TripPoint
import com.miskibin.obd2dashboard.data.TripTrace
import com.miskibin.obd2dashboard.ui.components.AccentButton
import com.miskibin.obd2dashboard.ui.components.DesignSheet
import com.miskibin.obd2dashboard.ui.components.QuietButton
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.SectionHeader
import com.miskibin.obd2dashboard.ui.components.SolidDangerButton
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.label
import com.miskibin.obd2dashboard.ui.theme.Amber
import com.miskibin.obd2dashboard.ui.theme.AmberBorder
import com.miskibin.obd2dashboard.ui.theme.CardCorner
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.SeriesColors
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateTrack
import com.miskibin.obd2dashboard.ui.theme.Smoke

/**
 * One drive, opened.
 *
 * The chart is the trip: speed, revs and the temperatures on one time axis, with the
 * moments that earned a badge marked on it. Under it are the three numbers somebody
 * actually asks about a drive afterwards, then the events with the second they happened —
 * the timestamps are the whole reason the recording exists, because "the oil went over
 * 110" is not actionable until you know it was for forty seconds on the motorway.
 */
@Composable
fun TripDetailScreen(
    trip: Trip,
    analysis: TripAnalysis?,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = formatTripTitle(trip.startedAtMillis),
            subtitle = tripMeta(TripEntry(trip, analysis)),
            onBack = onBack,
        )

        LazyColumn(
            contentPadding = PaddingValues(
                start = ScreenPadding,
                end = ScreenPadding,
                top = 2.dp,
                bottom = Dimens.listBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.cardGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (analysis == null) {
                item(key = "loading") {
                    Text(
                        text = stringResource(R.string.trip_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Smoke,
                        modifier = Modifier.padding(vertical = 18.dp),
                    )
                }
            }

            // A recording now carries every parameter that was being polled, which is more
            // lines than one plot can say anything with; the leading ones are drawn and the
            // rest stay in the file for whoever opens it in a spreadsheet.
            val traces = analysis?.traces.orEmpty().take(MAX_TRACES)
            if (traces.isNotEmpty()) {
                item(key = "chart") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CardCorner)
                            .background(Slate)
                            .border(1.dp, SlateBorder, CardCorner)
                            .padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 8.dp),
                    ) {
                        TripChart(
                            traces = traces,
                            events = analysis?.events.orEmpty(),
                            durationSeconds = analysis?.durationSeconds ?: 0.0,
                            modifier = Modifier.fillMaxWidth().height(PLOT_HEIGHT.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = formatClock(trip.startedAtMillis, 0.0),
                                style = MaterialTheme.typography.labelMedium,
                                color = Fog,
                            )
                            Text(
                                text = formatClock(
                                    trip.startedAtMillis,
                                    analysis?.durationSeconds ?: 0.0,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = Fog,
                            )
                        }
                        TraceLegend(traces = traces, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            if (analysis != null) {
                item(key = "stats") { TripStats(analysis = analysis) }
            }

            val events = analysis?.events.orEmpty()
            if (events.isNotEmpty()) {
                item(key = "events-header") {
                    SectionHeader(text = stringResource(R.string.trip_events))
                }
                events.forEach { event ->
                    item(key = "event-${event.metric.storageKey}-${event.kind}") {
                        EventCard(event = event, startedAtMillis = trip.startedAtMillis)
                    }
                }
            }

            item(key = "actions") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AccentButton(
                        label = stringResource(R.string.trip_export),
                        onClick = onExport,
                        modifier = Modifier.weight(1f),
                    )
                    QuietButton(
                        label = stringResource(R.string.action_delete),
                        onClick = { confirmDelete = true },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        DesignSheet(
            title = stringResource(R.string.recordings_delete_title),
            subtitle = stringResource(R.string.recordings_delete_message, trip.name),
            onDismiss = { confirmDelete = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuietButton(
                    label = stringResource(R.string.action_cancel),
                    onClick = { confirmDelete = false },
                    modifier = Modifier.weight(1f),
                )
                SolidDangerButton(
                    label = stringResource(R.string.action_delete),
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The maxima worth remembering.
 *
 * How far, how long and how thirsty are already in the line under the title, so a card
 * repeating the average consumption was the same number twice on one screen.
 */
@Composable
private fun TripStats(analysis: TripAnalysis) {
    val cards = buildList {
        analysis.maxima[Metrics.Rpm]?.let {
            add(maximumLabel(Metrics.Rpm) to formatReading(it, 0))
        }
        val oil = analysis.maxima[Metrics.OilTemp] ?: analysis.maxima[Metrics.CoolantTemp]
        val oilMetric = if (analysis.maxima[Metrics.OilTemp] != null) Metrics.OilTemp else Metrics.CoolantTemp
        if (oil != null) {
            add(maximumLabel(oilMetric) to "${formatReading(oil, 0)} ${Metrics[oilMetric]?.unit.orEmpty()}")
        }
    }
    if (cards.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cards.forEach { (label, value) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(PanelCorner)
                    .background(Slate)
                    .border(1.dp, SlateBorder, PanelCorner)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Smoke,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    color = Chalk,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun EventCard(event: TripEvent, startedAtMillis: Long) {
    val amber = event.kind != TripEventKind.Redline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, if (amber) AmberBorder else SlateBorder, PanelCorner)
            .padding(horizontal = 13.dp, vertical = Dimens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (amber) Amber else Smoke),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = eventLabel(event),
                style = MaterialTheme.typography.titleSmall,
                color = Chalk,
            )
            Text(
                text = eventTiming(event, startedAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = Smoke,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * When it happened, to the second.
 *
 * A breach that lasted gets a range and a duration; an instant — hitting the limiter —
 * gets the moment, and the count if it happened more than once.
 */
@Composable
private fun eventTiming(event: TripEvent, startedAtMillis: Long): String {
    val start = formatClock(startedAtMillis, event.startSeconds)
    if (event.durationSeconds < MIN_EVENT_DURATION_SECONDS) {
        return if (event.occurrences > 1) {
            stringResource(R.string.trip_event_at_repeated, start, event.occurrences)
        } else {
            stringResource(R.string.trip_event_at, start)
        }
    }
    return stringResource(
        R.string.trip_event_range,
        start,
        formatClock(startedAtMillis, event.endSeconds),
        event.durationSeconds.toInt(),
    )
}

/**
 * Names every trace on the plot, wrapped rather than truncated.
 *
 * With up to six parameters on one chart a single row runs off the screen and the last
 * names — which are exactly the ones the driver added themselves — would be the ones lost.
 */
@Composable
private fun TraceLegend(traces: List<TripTrace>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        traces.withIndex().chunked(LEGEND_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (index, trace) ->
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 10.dp, height = 2.dp)
                                .background(traceColor(index)),
                        )
                        Text(
                            text = Metrics[trace.metric]?.let { it.label() }
                                .orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Smoke,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Keeps a short last row aligned with the one above it.
                repeat(LEGEND_COLUMNS - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The whole trip on one time axis, each trace scaled to its own range.
 *
 * A shared scale would flatten the temperatures against engine speed; what matters here
 * is the shape and where the shapes coincide, which is exactly what a mechanic reads a
 * trip chart for. The shaded columns are the events, so the eye lands on them first.
 */
@Composable
private fun TripChart(
    traces: List<TripTrace>,
    events: List<TripEvent>,
    durationSeconds: Double,
    modifier: Modifier = Modifier,
) {
    // Everything the canvas paints with is read from the ground here: a DrawScope is not a
    // composition, so it cannot ask which theme it is drawing on.
    val gridline = SlateTrack
    val eventTint = Amber.copy(alpha = EVENT_ALPHA)
    val traceColors = traces.indices.map { traceColor(it) }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f || durationSeconds <= 0.0) return@Canvas

        listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
            drawLine(
                color = gridline,
                start = Offset(0f, height * fraction),
                end = Offset(width, height * fraction),
                strokeWidth = 1.dp.toPx(),
            )
        }

        events.filter { it.durationSeconds > 0.0 }.forEach { event ->
            val start = (event.startSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
            val end = (event.endSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
            drawRect(
                color = eventTint,
                topLeft = Offset(width * start, 0f),
                size = Size((width * (end - start)).coerceAtLeast(MIN_EVENT_WIDTH.dp.toPx()), height),
            )
        }

        traces.forEachIndexed { index, trace ->
            drawTrace(
                points = trace.points,
                durationSeconds = durationSeconds,
                color = traceColors[index],
                strokeWidth = trace.metric.traceWidth().dp.toPx(),
            )
        }
    }
}

private fun DrawScope.drawTrace(
    points: List<TripPoint>,
    durationSeconds: Double,
    color: Color,
    strokeWidth: Float,
) {
    if (points.size < 2) return
    val low = points.minOf(TripPoint::value)
    val high = points.maxOf(TripPoint::value)
    val span = (high - low).takeIf { it > EPSILON }
    val path = Path()
    points.forEachIndexed { index, point ->
        val x = (point.seconds / durationSeconds).toFloat().coerceIn(0f, 1f) * size.width
        val fraction = if (span == null) 0.5 else (point.value - low) / span
        val y = size.height * (1f - fraction.toFloat()) * PLOT_INSET + size.height * PLOT_MARGIN
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** The same palette the live chart uses, so a trace keeps its colour between the two. */
@Composable
private fun traceColor(index: Int): Color = SeriesColors.let { it[index % it.size] }

/** Hairline traces: thicker lines blur together wherever four of them cross. */
private fun MetricId.traceWidth(): Float = if (this == Metrics.Rpm) 1.5f else 1.2f

/**
 * The chart is the trip, and a trip with no events is otherwise three cards and a lot of
 * shell, so it takes the height back.
 */
private const val PLOT_HEIGHT = 240

/** As many traces as the palette has distinct colours, and as many as a plot can carry. */
private const val MAX_TRACES = 6

private const val LEGEND_COLUMNS = 3
private const val PLOT_INSET = 0.86f
private const val PLOT_MARGIN = 0.07f
private const val EVENT_ALPHA = 0.14f
private const val MIN_EVENT_WIDTH = 2
private const val MIN_EVENT_DURATION_SECONDS = 1.0
private const val EPSILON = 1e-6
