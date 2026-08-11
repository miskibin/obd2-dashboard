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
import com.miskibin.obd2dashboard.ui.components.DesignSheet
import com.miskibin.obd2dashboard.ui.components.InkButton
import com.miskibin.obd2dashboard.ui.components.QuietButton
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.SectionHeader
import com.miskibin.obd2dashboard.ui.components.SolidDangerButton
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.theme.CardCorner
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.PaperAmber
import com.miskibin.obd2dashboard.ui.theme.PaperAmberBorder
import com.miskibin.obd2dashboard.ui.theme.PaperBorder
import com.miskibin.obd2dashboard.ui.theme.PaperCard
import com.miskibin.obd2dashboard.ui.theme.PaperGrey
import com.miskibin.obd2dashboard.ui.theme.PaperInk
import com.miskibin.obd2dashboard.ui.theme.PaperInkDim
import com.miskibin.obd2dashboard.ui.theme.PaperInkFaint
import com.miskibin.obd2dashboard.ui.theme.PaperLine
import com.miskibin.obd2dashboard.ui.theme.PaperSteel

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
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (analysis == null) {
                item(key = "loading") {
                    Text(
                        text = stringResource(R.string.trip_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PaperInkDim,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }

            val traces = analysis?.traces.orEmpty()
            if (traces.isNotEmpty()) {
                item(key = "chart") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CardCorner)
                            .background(PaperCard)
                            .border(1.dp, PaperBorder, CardCorner)
                            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
                    ) {
                        TripChart(
                            traces = traces,
                            events = analysis?.events.orEmpty(),
                            durationSeconds = analysis?.durationSeconds ?: 0.0,
                            modifier = Modifier.fillMaxWidth().height(PLOT_HEIGHT.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = formatClock(trip.startedAtMillis, 0.0),
                                style = MaterialTheme.typography.labelMedium,
                                color = PaperInkFaint,
                            )
                            Text(
                                text = formatClock(
                                    trip.startedAtMillis,
                                    analysis?.durationSeconds ?: 0.0,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = PaperInkFaint,
                            )
                        }
                        TraceLegend(traces = traces, modifier = Modifier.padding(top = 10.dp))
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
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InkButton(
                        label = stringResource(R.string.trip_export),
                        onClick = onExport,
                        modifier = Modifier.weight(1f),
                    )
                    QuietButton(
                        label = stringResource(R.string.action_delete),
                        onClick = { confirmDelete = true },
                        contentColor = PaperInkDim,
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
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
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

/** How far, how long, how thirsty — and the two maxima worth remembering. */
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
        analysis.averageFuelPer100Km?.let {
            add(stringResource(R.string.trip_stat_fuel) to formatReading(it, 1))
        }
    }
    if (cards.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cards.forEach { (label, value) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(PanelCorner)
                    .background(PaperCard)
                    .border(1.dp, PaperBorder, PanelCorner)
                    .padding(horizontal = 13.dp, vertical = 12.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = PaperInkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    color = PaperInk,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
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
            .background(PaperCard)
            .border(1.dp, if (amber) PaperAmberBorder else PaperBorder, PanelCorner)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (amber) PaperAmber else PaperGrey),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = eventLabel(event),
                style = MaterialTheme.typography.titleSmall,
                color = PaperInk,
            )
            Text(
                text = eventTiming(event, startedAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = PaperInkDim,
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

@Composable
private fun TraceLegend(traces: List<TripTrace>, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        traces.forEach { trace ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 10.dp, height = 2.dp)
                        .background(trace.metric.traceColor()),
                )
                Text(
                    text = Metrics[trace.metric]?.let { stringResource(it.nameRes) }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = PaperInkDim,
                    maxLines = 1,
                )
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
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f || durationSeconds <= 0.0) return@Canvas

        listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
            drawLine(
                color = PaperLine,
                start = Offset(0f, height * fraction),
                end = Offset(width, height * fraction),
                strokeWidth = 1.dp.toPx(),
            )
        }

        events.filter { it.durationSeconds > 0.0 }.forEach { event ->
            val start = (event.startSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
            val end = (event.endSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
            drawRect(
                color = PaperAmber.copy(alpha = EVENT_ALPHA),
                topLeft = Offset(width * start, 0f),
                size = Size((width * (end - start)).coerceAtLeast(MIN_EVENT_WIDTH.dp.toPx()), height),
            )
        }

        traces.forEach { trace ->
            drawTrace(
                points = trace.points,
                durationSeconds = durationSeconds,
                color = trace.metric.traceColor(),
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

/** Engine speed leads, the temperatures follow, road speed is context. */
private fun MetricId.traceColor(): Color = when (this) {
    Metrics.Rpm -> PaperSteel
    Metrics.OilTemp -> PaperAmber
    Metrics.CoolantTemp -> PaperAmber.copy(alpha = 0.55f)
    else -> PaperGrey
}

private fun MetricId.traceWidth(): Float = if (this == Metrics.Rpm) 2.4f else 1.8f

private const val PLOT_HEIGHT = 210
private const val PLOT_INSET = 0.86f
private const val PLOT_MARGIN = 0.07f
private const val EVENT_ALPHA = 0.10f
private const val MIN_EVENT_WIDTH = 2
private const val MIN_EVENT_DURATION_SECONDS = 1.0
private const val EPSILON = 1e-6
