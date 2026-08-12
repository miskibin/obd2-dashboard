package com.miskibin.obd2dashboard.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.Sample
import com.miskibin.obd2dashboard.data.Trip
import com.miskibin.obd2dashboard.data.TripAnalysis
import com.miskibin.obd2dashboard.data.TripEntry
import com.miskibin.obd2dashboard.data.TripEvent
import com.miskibin.obd2dashboard.data.TripEventKind
import com.miskibin.obd2dashboard.data.TripPoint
import com.miskibin.obd2dashboard.data.TripTrace
import com.miskibin.obd2dashboard.ui.chart.ChartMode
import com.miskibin.obd2dashboard.ui.chart.ChartSeries
import com.miskibin.obd2dashboard.ui.chart.ChartSpan
import com.miskibin.obd2dashboard.ui.chart.LineChart
import com.miskibin.obd2dashboard.ui.chart.ParameterRow
import com.miskibin.obd2dashboard.ui.chart.chartLeftInset
import com.miskibin.obd2dashboard.ui.chart.chartRightInset
import com.miskibin.obd2dashboard.ui.chart.formatDuration
import com.miskibin.obd2dashboard.ui.chart.labelRes
import com.miskibin.obd2dashboard.ui.components.AccentButton
import com.miskibin.obd2dashboard.ui.components.DesignSheet
import com.miskibin.obd2dashboard.ui.components.GroupedList
import com.miskibin.obd2dashboard.ui.components.MenuChoice
import com.miskibin.obd2dashboard.ui.components.MenuLabel
import com.miskibin.obd2dashboard.ui.components.QuietButton
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.SectionHeader
import com.miskibin.obd2dashboard.ui.components.SolidDangerButton
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.label
import com.miskibin.obd2dashboard.ui.theme.Amber
import com.miskibin.obd2dashboard.ui.theme.AmberBorder
import com.miskibin.obd2dashboard.ui.theme.AmberLight
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.CardCorner
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.Ink
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.PillCorner
import com.miskibin.obd2dashboard.ui.theme.SeriesColors
import com.miskibin.obd2dashboard.ui.theme.SignalLight
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.SlateLine
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SmokeDim
import com.miskibin.obd2dashboard.ui.theme.SteelBorder
import com.miskibin.obd2dashboard.ui.theme.SteelDeep
import com.miskibin.obd2dashboard.ui.theme.SteelLight
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * One drive, opened.
 *
 * The chart is the trip, and it is the same instrument the Charts tab is: the driver
 * chooses which of the recorded parameters are on it, how they share the axis, and — the
 * part a live chart cannot offer — which stretch of the drive is on screen. A recording
 * now carries every parameter that was being polled, so drawing all of them at once made
 * a plot that said nothing; what opens is the handful a drive is read by plus whatever
 * earned a badge, and the rest are one tap away in the legend.
 *
 * Under the plot are the numbers somebody asks about a drive afterwards — recomputed for
 * whatever the window is showing, and labelled so it is never unclear which — then the
 * events with the second they happened. Tapping one takes the chart there.
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
    var picking by remember { mutableStateOf(false) }

    val traces = analysis?.traces.orEmpty()
    val events = analysis?.events.orEmpty()
    val duration = analysis?.durationSeconds ?: 0.0

    // Every choice on this screen is the screen's own and lasts as long as it is open: a
    // recording is opened to answer one question, and the parameters that answer it are
    // not the ones the next recording will be opened for.
    //
    // Null means "nobody has chosen", which is not the same as "nothing is chosen" — the
    // file is still being parsed when the screen first draws, so the defaults have to be
    // taken again once the traces arrive.
    var chosenKeys by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var chosenMode by rememberSaveable { mutableStateOf<ChartMode?>(null) }
    var zoom by rememberSaveable(stateSaver = ViewportSaver) { mutableStateOf<TripViewport?>(null) }
    var cursorSeconds by rememberSaveable { mutableStateOf<Double?>(null) }

    val selected = remember(chosenKeys, traces, events) {
        val chosen = chosenKeys?.mapNotNull(MetricId::parse) ?: defaultTripSeries(traces, events)
        chosen.filter { id -> traces.any { it.metric == id } }
    }
    // Strips when there are more than two lines, one axis when there are not: two traces
    // can usually be read against each other, six never can.
    val mode = chosenMode ?: if (selected.size > 2) ChartMode.Bands else ChartMode.Absolute
    val viewport = (zoom ?: TripViewport.whole(duration)).clampedTo(duration)
    val zoomed = !viewport.isWholeTrip(duration)
    val cursor = cursorSeconds?.takeIf { it in viewport }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val select: (MetricId) -> Unit = { id ->
        val next = if (id in selected) selected - id else selected + id
        chosenKeys = next.map(MetricId::storageKey)
    }

    // Every gesture reads the window back out of state instead of closing over the one
    // that was on screen when it started. A touch handler is a coroutine that is *not*
    // restarted by recomposition, so a captured window would freeze at the frame the
    // finger went down on and every pinch after the first would zoom from the same place.
    val windowNow = { (zoom ?: TripViewport.whole(duration)).clampedTo(duration) }
    val gestures = TripChartGestures(
        onZoom = { focus, scale ->
            zoom = windowNow().zoomedBy(scale.toDouble(), focus.toDouble(), duration)
        },
        onPan = { fraction -> zoom = windowNow().pannedBy(fraction.toDouble(), duration) },
        // Tapping where the cursor already is takes it away again, which is the only
        // other thing anybody would expect a tap on it to do.
        onTapAt = { fraction ->
            val window = windowNow()
            val seconds = window.secondsAt(fraction.toDouble())
            val current = cursorSeconds
            cursorSeconds = if (
                current != null && abs(seconds - current) < window.spanSeconds * CURSOR_GRAB
            ) {
                null
            } else {
                seconds
            }
        },
        onReset = {
            zoom = null
            cursorSeconds = null
        },
        onCentre = { fraction ->
            zoom = TripViewport.around(duration * fraction, windowNow().spanSeconds, duration)
        },
        onShift = { fraction ->
            val window = windowNow()
            zoom = TripViewport(
                startSeconds = window.startSeconds + duration * fraction,
                endSeconds = window.endSeconds + duration * fraction,
            ).clampedTo(duration)
        },
    )
    // An event opens as itself plus a quarter-minute either side: the badge is about what
    // happened, and what happened is only readable against what led into it.
    val focusOn: (TripEvent) -> Unit = { event ->
        val centre = event.startSeconds + event.durationSeconds / 2
        zoom = TripViewport.around(
            centerSeconds = centre,
            spanSeconds = event.durationSeconds + TripViewport.EVENT_SPAN_SECONDS,
            durationSeconds = duration,
        )
        cursorSeconds = centre
        scope.launch { listState.animateScrollToItem(0) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = formatTripTitle(trip.startedAtMillis),
            subtitle = tripMeta(TripEntry(trip, analysis)),
            onBack = onBack,
            trailing = {
                if (traces.isNotEmpty()) {
                    TripChartChip(
                        viewport = viewport,
                        mode = mode,
                        zoomed = zoomed,
                        onMode = { chosenMode = it },
                        onWholeTrip = gestures.onReset,
                    )
                }
            },
        )

        LazyColumn(
            state = listState,
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

            if (traces.isNotEmpty()) {
                item(key = "chart") {
                    TripChartCard(
                        startedAtMillis = trip.startedAtMillis,
                        traces = traces,
                        selected = selected,
                        events = events,
                        mode = mode,
                        viewport = viewport,
                        durationSeconds = duration,
                        cursorSeconds = cursor,
                        gestures = gestures,
                        onRemove = select,
                        onAdd = { picking = true },
                    )
                }
            }

            if (analysis != null) {
                item(key = "stats") {
                    TripStats(
                        maxima = if (zoomed) traces.maximaIn(viewport) else analysis.maxima,
                        zoomed = zoomed,
                    )
                }
                // The two headline figures in the line under the title are both integrals,
                // and the tildes in front of them are only a promise that this card keeps.
                if (analysis.distanceKm != null || analysis.averageFuelPer100Km != null) {
                    item(key = "estimates") { TripEstimates(analysis) }
                }
            }

            if (events.isNotEmpty()) {
                item(key = "events-header") {
                    SectionHeader(text = stringResource(R.string.trip_events))
                }
                events.forEach { event ->
                    item(key = "event-${event.metric.storageKey}-${event.kind}") {
                        EventCard(
                            event = event,
                            startedAtMillis = trip.startedAtMillis,
                            onClick = { focusOn(event) },
                        )
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

    if (picking) {
        TripParameterSheet(
            traces = traces,
            selected = selected,
            onToggle = select,
            onDismiss = { picking = false },
        )
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
 * What a finger can do to the plot, in fractions rather than in windows.
 *
 * The gestures report what the hand did — pinched by this much about here, dragged this
 * far across — and the screen that owns the window applies it. Handing them a window to
 * work from instead is what would go stale, since the coroutine behind a touch handler
 * outlives the composition that started it.
 */
private data class TripChartGestures(
    val onZoom: (focusFraction: Float, scale: Float) -> Unit,
    val onPan: (fraction: Float) -> Unit,
    val onTapAt: (fraction: Float) -> Unit,
    val onReset: () -> Unit,
    /** From the strip: put the window around this point of the whole recording. */
    val onCentre: (fraction: Float) -> Unit,
    /** From the strip: shift the window by this fraction of the whole recording. */
    val onShift: (fraction: Float) -> Unit,
)

/** One plotted parameter, resolved against the window that is on screen. */
private data class TripLine(
    val metric: MetricId,
    val label: String,
    val unit: String,
    val decimals: Int,
    val color: Color,
    val points: List<TripPoint>,
    val cursorValue: Double?,
)

/**
 * The plot, what it is showing, where that is in the drive, and what is on it.
 *
 * Four things in one card because they are one instrument: the traces, the clock under
 * them, the strip that says which slice of the recording the traces are, and the legend
 * that both names the lines and is how they are added and dropped.
 */
@Composable
private fun TripChartCard(
    startedAtMillis: Long,
    traces: List<TripTrace>,
    selected: List<MetricId>,
    events: List<TripEvent>,
    mode: ChartMode,
    viewport: TripViewport,
    durationSeconds: Double,
    cursorSeconds: Double?,
    gestures: TripChartGestures,
    onRemove: (MetricId) -> Unit,
    onAdd: () -> Unit,
) {
    val palette = SeriesColors
    // The plot keeps a gutter for its value labels in every mode but Bands; the strip
    // under it and every touch turned back into a moment have to skip the same one.
    val leftInset = chartLeftInset(mode)
    val rightInset = chartRightInset()
    val lines = selected.mapIndexedNotNull { index, id ->
        val trace = traces.firstOrNull { it.metric == id } ?: return@mapIndexedNotNull null
        val metric = Metrics[id]
        TripLine(
            metric = id,
            label = metric?.let { it.label() } ?: id.storageKey,
            unit = metric?.unit.orEmpty(),
            decimals = metric?.decimals ?: 0,
            color = palette[index % palette.size],
            points = trace.pointsIn(viewport),
            cursorValue = cursorSeconds?.let(trace::valueAt),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, CardCorner)
            .padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PLOT_HEIGHT.dp)
                .plotGestures(
                    key = durationSeconds,
                    leftInset = leftInset,
                    rightInset = rightInset,
                    gestures = gestures,
                ),
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.trip_chart_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Smoke,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LineChart(
                    series = lines.map { line ->
                        ChartSeries(
                            key = line.metric.storageKey,
                            label = line.label,
                            color = line.color,
                            unit = line.unit,
                            decimals = line.decimals,
                            samples = line.points.map { point ->
                                Sample(
                                    timeMillis = startedAtMillis + (point.seconds * 1_000).toLong(),
                                    value = point.value.toFloat(),
                                )
                            },
                        )
                    },
                    windowMillis = (viewport.spanSeconds * 1_000).toLong().coerceAtLeast(1L),
                    nowMillis = startedAtMillis + (viewport.endSeconds * 1_000).toLong(),
                    mode = mode,
                    nowLabel = "",
                    markerFraction = cursorSeconds?.let { viewport.fractionOf(it).toFloat() },
                    spans = events.spansIn(viewport),
                    axisLabels = tripAxisLabels(viewport),
                    // Nothing on a recording is happening now, so nothing is the head of
                    // the trace — the right edge is only where the driver dragged to.
                    trailingDot = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // The clock either side of the window, and the moment under the cursor between
        // them: an elapsed axis says how far into the drive, this says when.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp, start = leftInset, end = rightInset),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatClock(startedAtMillis, viewport.startSeconds),
                style = MaterialTheme.typography.labelMedium,
                color = Fog,
            )
            if (cursorSeconds != null) {
                Text(
                    text = formatClock(startedAtMillis, cursorSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = SignalLight,
                )
            }
            Text(
                text = formatClock(startedAtMillis, viewport.endSeconds),
                style = MaterialTheme.typography.labelMedium,
                color = Fog,
            )
        }

        TripMiniMap(
            overview = lines.firstOrNull()?.metric
                ?.let { id -> traces.firstOrNull { it.metric == id } }
                ?.points
                .orEmpty(),
            events = events,
            durationSeconds = durationSeconds,
            viewport = viewport,
            onCentre = gestures.onCentre,
            onDrag = gestures.onShift,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = leftInset, end = rightInset),
        )

        TripLegend(
            lines = lines,
            cursorSet = cursorSeconds != null,
            onRemove = onRemove,
            onAdd = onAdd,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * The whole recording as a strip, with the window drawn on it.
 *
 * Zoomed in, the plot stops being able to say where in the drive it is; without this the
 * only way back out is the gesture that got there. The trace behind it is the first
 * plotted parameter, at whatever resolution a few hundred points give — it is there to be
 * recognised, not read — and the amber ticks are the events, so a window can be dragged
 * onto one without going back to the list.
 */
@Composable
private fun TripMiniMap(
    overview: List<TripPoint>,
    events: List<TripEvent>,
    durationSeconds: Double,
    viewport: TripViewport,
    onCentre: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (durationSeconds <= 0.0) return
    val track = Ink
    val edge = SlateEdge
    val trace = SmokeDim
    val eventTint = Amber
    val window = SteelDeep
    val windowEdge = SteelBorder

    Canvas(
        modifier = modifier
            .height(MINIMAP_HEIGHT.dp)
            .clip(PillCorner)
            .background(track)
            .border(1.dp, edge, PillCorner)
            .pointerInput(durationSeconds) {
                detectTapGestures { offset ->
                    if (size.width > 0) onCentre(offset.x / size.width.toFloat())
                }
            }
            .pointerInput(durationSeconds) {
                detectHorizontalDragGestures { change, delta ->
                    change.consume()
                    if (size.width > 0) onDrag(delta / size.width.toFloat())
                }
            },
    ) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        if (overview.size >= 2) {
            val low = overview.minOf(TripPoint::value)
            val high = overview.maxOf(TripPoint::value)
            val span = (high - low).takeIf { it > MINIMAP_EPSILON }
            val path = Path()
            overview.forEachIndexed { index, point ->
                val x = (point.seconds / durationSeconds).toFloat().coerceIn(0f, 1f) * width
                val fraction = if (span == null) 0.5 else (point.value - low) / span
                val y = height * (1f - fraction.toFloat()) * MINIMAP_INSET +
                    height * MINIMAP_MARGIN
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = trace,
                style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        events.forEach { event ->
            val start = (event.startSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
            val end = (event.endSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
            drawRect(
                color = eventTint.copy(alpha = MINIMAP_EVENT_ALPHA),
                topLeft = Offset(width * start, 0f),
                size = Size((width * (end - start)).coerceAtLeast(1.dp.toPx()), height),
            )
        }

        val left = (viewport.startSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) * width
        val right = (viewport.endSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) * width
        val handle = (right - left).coerceAtLeast(MINIMAP_MIN_WINDOW.dp.toPx())
        drawRect(
            color = window.copy(alpha = MINIMAP_WINDOW_ALPHA),
            topLeft = Offset(left, 0f),
            size = Size(handle, height),
        )
        drawRect(
            color = windowEdge,
            topLeft = Offset(left, 0f),
            size = Size(handle, height),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/**
 * What each line is, what it did in the window, and what it reads under the cursor.
 *
 * The same object the Charts tab uses, for the same reason: the row already carries the
 * colour and the name of its trace, so the × that drops it belongs there rather than in a
 * second row of chips saying the same names again. The last row is how a parameter gets
 * on, which is why it is there even when nothing is plotted.
 */
@Composable
private fun TripLegend(
    lines: List<TripLine>,
    cursorSet: Boolean,
    onRemove: (MetricId) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = LEGEND_MAX_HEIGHT.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        lines.forEachIndexed { index, line ->
            if (index > 0) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SlateLine))
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = LEGEND_ROW_HEIGHT.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(modifier = Modifier.size(width = 9.dp, height = 2.dp).background(line.color))
                Text(
                    text = line.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = AshDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val values = line.points.map(TripPoint::value)
                if (values.isNotEmpty()) {
                    Text(
                        text = "${formatReading(values.min(), line.decimals)}–" +
                            formatReading(values.max(), line.decimals),
                        style = MaterialTheme.typography.labelMedium,
                        color = SmokeDim,
                    )
                }
                if (cursorSet) {
                    Text(
                        text = "${formatReading(line.cursorValue, line.decimals)} ${line.unit}"
                            .trim(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = line.color,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(REMOVE_TARGET.dp)
                        .clip(PillCorner)
                        .clickable(
                            onClickLabel = stringResource(
                                R.string.chart_remove_parameter,
                                line.label,
                            ),
                        ) { onRemove(line.metric) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.bodySmall,
                        color = SmokeDim,
                    )
                }
            }
        }

        if (lines.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SlateLine))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LEGEND_ROW_HEIGHT.dp)
                .clickable(onClick = onAdd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chart_add_parameter),
                style = MaterialTheme.typography.bodySmall,
                color = SteelLight,
            )
        }
    }
}

/**
 * How much of the drive is on the plot and how the traces share it, as one chip.
 *
 * The same control the Charts tab carries, reading out the same way: the length of the
 * window, then what the axis is doing. The way back to the whole recording lives in it
 * too, because a double tap on the plot is not something anybody discovers.
 */
@Composable
private fun TripChartChip(
    viewport: TripViewport,
    mode: ChartMode,
    zoomed: Boolean,
    onMode: (ChartMode) -> Unit,
    onWholeTrip: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .sizeIn(minHeight = 36.dp)
                .clip(PillCorner)
                .background(Slate)
                .border(1.dp, SlateBorder, PillCorner)
                .clickable { open = true }
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatDuration(viewport.spanSeconds.toLong()) + CHIP_SEPARATOR +
                    stringResource(mode.labelRes()),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (zoomed) Chalk else SteelLight,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = stringResource(R.string.chart_settings),
                tint = Smoke,
                modifier = Modifier.size(20.dp),
            )
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Slate),
        ) {
            MenuLabel(stringResource(R.string.trip_menu_zoom))
            MenuChoice(
                label = stringResource(R.string.trip_zoom_whole),
                selected = !zoomed,
                onClick = {
                    onWholeTrip()
                    open = false
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .height(1.dp)
                    .background(SlateLine),
            )
            MenuLabel(stringResource(R.string.chart_menu_axis))
            ChartMode.entries.forEach { option ->
                MenuChoice(
                    label = stringResource(option.labelRes()),
                    selected = option == mode,
                    onClick = {
                        onMode(option)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * Everything the recording carries, to pick from.
 *
 * Not the catalogue: a file holds the columns that were being polled on that drive, which
 * is both fewer than every PID the app knows and — for a recording made by an older or a
 * newer build — occasionally something the catalogue cannot name. What it recorded is
 * what is offered, with the range each column covered so a parameter can be recognised
 * before it is plotted.
 */
@Composable
private fun TripParameterSheet(
    traces: List<TripTrace>,
    selected: List<MetricId>,
    onToggle: (MetricId) -> Unit,
    onDismiss: () -> Unit,
) {
    DesignSheet(
        title = stringResource(R.string.trip_picker_title),
        subtitle = stringResource(R.string.trip_picker_subtitle, traces.size),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .heightIn(max = PICKER_MAX_HEIGHT.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            GroupedList(modifier = Modifier.fillMaxWidth()) {
                traces.forEach { trace ->
                    val metric = Metrics[trace.metric]
                    val decimals = metric?.decimals ?: 0
                    val low = trace.points.minOfOrNull(TripPoint::value)
                    val high = trace.points.maxOfOrNull(TripPoint::value)
                    ParameterRow(
                        label = metric?.let { it.label() } ?: trace.metric.storageKey,
                        unit = metric?.unit.orEmpty(),
                        selected = trace.metric in selected,
                        onToggle = { onToggle(trace.metric) },
                        detail = if (low == null || high == null) {
                            null
                        } else {
                            "${formatReading(low, decimals)}–${formatReading(high, decimals)}"
                        },
                    )
                }
            }
        }

        AccentButton(
            label = stringResource(R.string.action_done),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

/**
 * The maxima worth remembering, for whatever the chart is showing.
 *
 * How far, how long and how thirsty are already in the line under the title, so a card
 * repeating the average consumption was the same number twice on one screen. Zoomed in
 * these become the maxima of the visible window — which is the whole point of zooming
 * into the two minutes something went wrong — and the heading says so.
 */
@Composable
private fun TripStats(maxima: Map<MetricId, Double>, zoomed: Boolean) {
    val cards = buildList {
        maxima[Metrics.Rpm]?.let {
            add(maximumLabel(Metrics.Rpm) to formatReading(it, 0))
        }
        val oil = maxima[Metrics.OilTemp] ?: maxima[Metrics.CoolantTemp]
        val oilMetric = if (maxima[Metrics.OilTemp] != null) Metrics.OilTemp else Metrics.CoolantTemp
        if (oil != null) {
            add(maximumLabel(oilMetric) to "${formatReading(oil, 0)} ${Metrics[oilMetric]?.unit.orEmpty()}")
        }
    }
    if (cards.isEmpty()) return

    Column {
        SectionHeader(
            text = stringResource(
                if (zoomed) R.string.trip_stats_window else R.string.trip_stats_full,
            ),
        )
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
}

/**
 * Where the distance and the consumption came from, in the words a driver would use.
 *
 * Both are integrals of things the car said a few times a second, and neither is a reading
 * of anything: there is no trip-distance PID and no trip-consumption PID. On a screen that
 * otherwise shows measurements, two integrals printed to one decimal look exactly like an
 * odometer and a trip computer, so the difference is stated once, plainly, under them —
 * along with any part of the drive that could not be counted at all.
 */
@Composable
private fun TripEstimates(analysis: TripAnalysis) {
    val skipped = analysis.skippedSeconds.takeIf { it >= MIN_REPORTED_GAP_SECONDS }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.trip_estimates_title),
            style = MaterialTheme.typography.titleSmall,
            color = Chalk,
        )
        if (analysis.distanceKm != null) {
            Text(
                text = stringResource(R.string.trip_estimates_distance),
                style = MaterialTheme.typography.bodySmall,
                color = Smoke,
            )
        }
        if (analysis.averageFuelPer100Km != null) {
            Text(
                text = stringResource(R.string.trip_estimates_fuel),
                style = MaterialTheme.typography.bodySmall,
                color = Smoke,
            )
        }
        // Amber, and last, because it is the one line here that says a number on this
        // screen is missing something rather than merely being approximate.
        if (skipped != null) {
            Text(
                text = stringResource(
                    R.string.trip_estimates_gap,
                    formatDuration(skipped.toLong()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AmberLight,
            )
        }
    }
}

/** Below this, a gap is polling jitter rather than a hole in the drive. */
private const val MIN_REPORTED_GAP_SECONDS = 10.0

@Composable
private fun EventCard(event: TripEvent, startedAtMillis: Long, onClick: () -> Unit) {
    val amber = event.kind != TripEventKind.Redline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, if (amber) AmberBorder else SlateBorder, PanelCorner)
            .clickable(onClickLabel = stringResource(R.string.trip_event_focus), onClick = onClick)
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
        Text(text = "⤢", style = MaterialTheme.typography.bodySmall, color = Fog)
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
 * Pinch to zoom, drag to pan, tap to read, double tap to come back out.
 *
 * The plot lives inside a scrolling list, so a drag has to be shared rather than taken:
 * nothing is claimed until the finger has moved past the touch slop, and then only if it
 * moved sideways. A drag that set off downwards is left entirely alone and the list
 * scrolls with it, which is why this is written out rather than handed to
 * `detectTransformGestures` — that one takes every drag it sees.
 */
private fun Modifier.plotGestures(
    key: Any?,
    leftInset: Dp,
    rightInset: Dp,
    gestures: TripChartGestures,
): Modifier = this
    .pointerInput(key, leftInset, rightInset) {
        detectTapGestures(
            onDoubleTap = { gestures.onReset() },
            onTap = { offset ->
                val width = plotWidthOf(leftInset, rightInset)
                if (width > 0f) {
                    gestures.onTapAt(((offset.x - leftInset.toPx()) / width).coerceIn(0f, 1f))
                }
            },
        )
    }
    .pointerInput(key, leftInset, rightInset) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var claimed = false
            var surrendered = false
            var travelX = 0f
            var travelY = 0f
            val slop = viewConfiguration.touchSlop
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
                val width = plotWidthOf(leftInset, rightInset)
                if (width <= 0f) break
                // Two fingers are never a scroll, whatever direction they moved.
                if (event.changes.count { it.pressed } > 1) claimed = true
                val pan = event.calculatePan()
                if (!claimed) {
                    travelX += pan.x
                    travelY += pan.y
                    if (abs(travelX) > slop || abs(travelY) > slop) {
                        if (abs(travelX) > abs(travelY)) claimed = true else surrendered = true
                    }
                }
                if (surrendered) break
                if (!claimed) continue
                val scale = event.calculateZoom()
                if (scale > 0f && scale != 1f) {
                    val focus = (event.calculateCentroid().x - leftInset.toPx()) / width
                    gestures.onZoom(focus.coerceIn(0f, 1f), scale)
                }
                if (pan.x != 0f) gestures.onPan(-pan.x / width)
                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
            }
        }
    }

/** The width of the plot itself, which is the pointer area less the axis gutters. */
private fun PointerInputScope.plotWidthOf(leftInset: Dp, rightInset: Dp): Float =
    size.width - leftInset.toPx() - rightInset.toPx()

/** The events that fall inside the window, as fractions of it. */
private fun List<TripEvent>.spansIn(viewport: TripViewport): List<ChartSpan> =
    filter { it.durationSeconds > 0.0 }.mapNotNull { event ->
        val start = viewport.fractionOf(event.startSeconds)
        val end = viewport.fractionOf(event.endSeconds)
        if (end < 0.0 || start > 1.0) {
            null
        } else {
            ChartSpan(start.toFloat().coerceIn(0f, 1f), end.toFloat().coerceIn(0f, 1f))
        }
    }

/** Two doubles, so a window survives the screen being rotated on top of it. */
private val ViewportSaver = listSaver<TripViewport?, Double>(
    save = { viewport ->
        if (viewport == null) emptyList() else listOf(viewport.startSeconds, viewport.endSeconds)
    },
    restore = { saved -> if (saved.size < 2) null else TripViewport(saved[0], saved[1]) },
)

/**
 * The chart is the trip, and a trip with no events is otherwise three cards and a lot of
 * shell, so it takes the height back.
 */
private const val PLOT_HEIGHT = 232

/** The strip under the plot: tall enough to hit with a thumb, short enough to be chrome. */
private const val MINIMAP_HEIGHT = 26

/** Every legend row, including the one that adds a parameter, is this tall. */
private const val LEGEND_ROW_HEIGHT = 42

/** Past this the legend scrolls among itself rather than pushing the events off screen. */
private const val LEGEND_MAX_HEIGHT = 220

/** The × in a legend row: a thumb target that does not make the row taller. */
private const val REMOVE_TARGET = 34

/** How close to the cursor a tap has to land to be read as taking it away. */
private const val CURSOR_GRAB = 0.02

private const val PICKER_MAX_HEIGHT = 360
private const val CHIP_SEPARATOR = " · "
private const val MINIMAP_INSET = 0.8f
private const val MINIMAP_MARGIN = 0.1f
private const val MINIMAP_WINDOW_ALPHA = 0.55f
private const val MINIMAP_EVENT_ALPHA = 0.6f
private const val MINIMAP_MIN_WINDOW = 3
private const val MINIMAP_EPSILON = 1e-6
private const val MIN_EVENT_DURATION_SECONDS = 1.0
