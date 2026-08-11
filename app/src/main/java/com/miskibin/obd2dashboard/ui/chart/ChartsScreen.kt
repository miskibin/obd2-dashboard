package com.miskibin.obd2dashboard.ui.chart

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.MetricHistory
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.RecordingState
import com.miskibin.obd2dashboard.data.Sample
import com.miskibin.obd2dashboard.data.valueOf
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.components.EmptyState
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.Segment
import com.miskibin.obd2dashboard.ui.components.SegmentedControl
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.theme.AmberText
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.CardCorner
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.Graphite
import com.miskibin.obd2dashboard.ui.theme.PillCorner
import com.miskibin.obd2dashboard.ui.theme.SeriesColors
import com.miskibin.obd2dashboard.ui.theme.Signal
import com.miskibin.obd2dashboard.ui.theme.SignalBorder
import com.miskibin.obd2dashboard.ui.theme.SignalSurface
import com.miskibin.obd2dashboard.ui.theme.SignalText
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.SlateLine
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SmokeDim
import kotlinx.coroutines.delay
import java.util.Locale

/** The three windows worth looking at from a driver's seat. */
enum class ChartWindow(val millis: Long, val labelRes: Int, val summaryRes: Int) {
    Seconds30(30_000L, R.string.chart_window_30s, R.string.chart_window_30s_long),
    Seconds60(60_000L, R.string.chart_window_60s, R.string.chart_window_60s_long),
    Minutes5(300_000L, R.string.chart_window_5min, R.string.chart_window_5min_long),
}

/**
 * The live traces, and the record button that turns them into a file.
 *
 * Parameters come from the whole catalogue rather than from the dashboard's tiles: the
 * chart is where a fault gets diagnosed, and the value that explains a misfire is rarely
 * one anybody chose to stare at while driving. The mode switch above the plot decides how
 * they share axes, because there is no single honest answer — boost in bar and revs in rpm
 * cannot sit on one scale, and stacking every trace in its own strip hides how they line
 * up.
 */
@Composable
fun ChartsScreen(
    vehicleLabel: String,
    chartMetrics: List<MetricId>,
    supportedPids: Set<Int>,
    snapshot: VehicleSnapshot,
    history: MetricHistory,
    historyRevision: Long,
    recording: RecordingState,
    maxSeries: Int,
    onToggleMetric: (MetricId) -> Unit,
    onToggleRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var window by rememberSaveable { mutableStateOf(ChartWindow.Seconds60) }
    var mode by rememberSaveable { mutableStateOf(ChartMode.Bands) }
    var picking by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(REFRESH_MILLIS)
        }
    }

    // Anything the catalogue no longer knows about is dropped here rather than in the
    // middle of the plot, so the chips, the traces and the stats stay in step.
    val plotted = remember(chartMetrics) { chartMetrics.filter { Metrics[it] != null } }
    val series = remember(historyRevision, plotted, window, now) {
        plotted.mapIndexed { index, id ->
            val metric = Metrics[id]
            ChartSeries(
                key = id.storageKey,
                label = metric?.let { context.getString(it.nameRes) }.orEmpty(),
                color = SeriesColors[index % SeriesColors.size],
                unit = metric?.unit.orEmpty(),
                decimals = metric?.decimals ?: 1,
                samples = history.series(id, window.millis, now),
            )
        }
    }
    val hasData = series.any { it.samples.isNotEmpty() }
    val units = remember(series) { series.map(ChartSeries::unit).filter(String::isNotBlank).distinct() }
    val rate = remember(series, window) { sampleRateOf(series, window) }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val statsCap = with(LocalDensity.current) { (windowHeight * STATS_SHARE).toDp() }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = vehicleLabel,
            // The window is already named by the control on the right of this very row, so
            // restating it here only bought an ellipsis in the middle of the sample rate.
            subtitle = if (rate > 0) {
                stringResource(R.string.chart_window_rate, rate)
            } else {
                stringResource(window.summaryRes)
            },
            leading = {
                RecordControl(
                    recording = recording,
                    now = now,
                    onToggleRecording = onToggleRecording,
                )
            },
            trailing = {
                SegmentedControl(
                    segments = ChartWindow.entries.map { option ->
                        Segment(stringResource(option.labelRes)) { window = option }
                    },
                    selectedIndex = ChartWindow.entries.indexOf(window),
                    fill = false,
                )
            },
        )

        // Nothing here scrolls and nothing here is a fixed height: the plot is the screen,
        // so it takes whatever the controls and the readings leave, which on a phone in
        // portrait is most of it. The readings are the only part that could grow without
        // limit — six series is six rows — so they are capped at [STATS_SHARE] of what
        // there is and scroll among themselves past that, which is what stops a full
        // legend from squeezing the plot into a strip.
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = ScreenPadding)
                .padding(bottom = Dimens.listBottom),
            verticalArrangement = Arrangement.spacedBy(Dimens.cardGap),
        ) {
            SegmentedControl(
                segments = ChartMode.entries.map { option ->
                    Segment(stringResource(option.labelRes())) { mode = option }
                },
                selectedIndex = ChartMode.entries.indexOf(mode),
                modifier = Modifier.fillMaxWidth(),
            )

            SeriesChips(
                series = series,
                metrics = plotted,
                onToggle = onToggleMetric,
                onAdd = { picking = true },
            )

            // All that survives of the row of mode hints: the one thing a driver could not
            // work out from the mode's own name, which is that an absolute axis is a lie
            // when the series are not in the same units. It costs a line only when it is
            // true, and the plot absorbs the difference rather than the layout jumping.
            if (mode == ChartMode.Absolute && units.size > 1) {
                Text(
                    text = stringResource(
                        R.string.chart_mode_absolute_mixed,
                        units.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(CardCorner)
                    .background(Slate)
                    .border(1.dp, SlateBorder, CardCorner)
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            ) {
                if (hasData) {
                    LineChart(
                        series = series,
                        windowMillis = window.millis,
                        nowMillis = now,
                        mode = mode,
                        nowLabel = stringResource(R.string.chart_now),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    EmptyState(
                        icon = AppIcons.Timeline,
                        title = stringResource(R.string.charts_waiting_title),
                        message = stringResource(R.string.charts_waiting_message),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            // Whatever the mode does to the axes, this row always states the real value in
            // real units — without it, "relative" would be a chart of nothing in
            // particular. Plain rows: the plot above already carries a border, and a second
            // box around six lines of text was one frame too many.
            SeriesStats(
                series = series,
                metrics = plotted,
                snapshot = snapshot,
                maxHeight = statsCap,
            )
        }
    }

    if (picking) {
        ParameterSheet(
            selected = chartMetrics,
            supportedPids = supportedPids,
            maxSeries = maxSeries,
            onToggle = onToggleMetric,
            onDismiss = { picking = false },
        )
    }
}

/**
 * The chips: one per charted parameter, carrying its own line colour, plus the way in.
 *
 * A tap drops the parameter, which is why every chip has a ×: on a screen where the
 * plot is the point, the legend and the control for it should be the same object.
 */
@Composable
private fun SeriesChips(
    series: List<ChartSeries>,
    metrics: List<MetricId>,
    onToggle: (MetricId) -> Unit,
    onAdd: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        series.forEachIndexed { index, line ->
            val id = metrics.getOrNull(index) ?: return@forEachIndexed
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .clip(PillCorner)
                    .background(Slate)
                    .border(1.dp, SlateBorder, PillCorner)
                    .clickable { onToggle(id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Box(modifier = Modifier.size(width = 9.dp, height = 2.dp).background(line.color))
                Text(
                    text = line.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = AshDim,
                    maxLines = 1,
                )
                Text(text = "×", style = MaterialTheme.typography.bodySmall, color = SmokeDim)
            }
        }
        Text(
            text = stringResource(R.string.chart_add_parameter),
            style = MaterialTheme.typography.bodySmall,
            color = Smoke,
            modifier = Modifier
                .clip(PillCorner)
                .border(1.dp, SlateEdge, PillCorner)
                .clickable(onClick = onAdd)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * What each line did over the window, and what it reads right now.
 *
 * No card and no border: these are a caption for the plot directly above them, and wrapping
 * them in their own panel made the screen read as two competing things. Hairlines between
 * the rows are enough to keep the columns lined up.
 *
 * [maxHeight] is for the six-series case only — the plot has first claim on the screen, and
 * rather than let a full legend crush it, the readings scroll among themselves.
 */
@Composable
private fun SeriesStats(
    series: List<ChartSeries>,
    metrics: List<MetricId>,
    snapshot: VehicleSnapshot,
    maxHeight: Dp,
) {
    if (series.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        series.forEachIndexed { index, line ->
            val id = metrics.getOrNull(index)
            val values = line.samples.map(Sample::value)
            if (index > 0) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SlateLine))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.rowPaddingV),
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
                if (values.isNotEmpty()) {
                    Text(
                        text = "${formatReading(values.min().toDouble(), line.decimals)}–" +
                            formatReading(values.max().toDouble(), line.decimals),
                        style = MaterialTheme.typography.labelMedium,
                        color = SmokeDim,
                    )
                }
                Text(
                    text = "${formatReading(id?.let(snapshot::valueOf), line.decimals)} ${line.unit}"
                        .trim(),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = line.color,
                )
            }
        }
    }
}

/**
 * Recording, as one control in the header row.
 *
 * A card pinned above the navigation bar spent a tenth of the screen saying "not
 * recording" — everything it had to communicate is a dot and, once it runs, how long for.
 * Idle it is a grey dot; recording it turns red and pulses, and states the elapsed time
 * next to itself so a recording the driver forgot about announces itself.
 */
@Composable
private fun RecordControl(
    recording: RecordingState,
    now: Long,
    onToggleRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = recording as? RecordingState.Active
    val pulse = if (active != null) {
        val transition = rememberInfiniteTransition(label = "record-pulse")
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "record-alpha",
        ).value
    } else {
        1f
    }

    Row(
        modifier = modifier
            .sizeIn(minWidth = 36.dp, minHeight = 36.dp)
            .clip(PillCorner)
            .background(if (active != null) SignalSurface else Slate)
            .border(1.dp, if (active != null) SignalBorder else SlateBorder, PillCorner)
            .clickable(onClick = onToggleRecording)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            imageVector = AppIcons.RecordDot,
            contentDescription = stringResource(
                if (active != null) R.string.chart_record_stop else R.string.chart_record_start,
            ),
            tint = if (active != null) Signal else Graphite,
            modifier = Modifier.size(14.dp).alpha(pulse),
        )
        if (active != null) {
            Text(
                text = formatDuration((now - active.startedAtMillis) / 1000),
                style = MaterialTheme.typography.labelMedium,
                color = SignalText,
                maxLines = 1,
            )
        }
    }
}

/**
 * Samples per second, taken from the densest series.
 *
 * The header claims a rate, so it has to be measured rather than assumed: the scheduler
 * slows down when the adapter does, and a stated "11 samples/s" that is really three
 * would make every gap in a trace look like a fault in the car.
 */
private fun sampleRateOf(series: List<ChartSeries>, window: ChartWindow): Int {
    val densest = series.maxOfOrNull { it.samples.size } ?: return 0
    if (densest < 2) return 0
    val seconds = window.millis / 1_000.0
    return (densest / seconds).toInt()
}

private fun ChartMode.labelRes(): Int = when (this) {
    ChartMode.Bands -> R.string.chart_mode_bands
    ChartMode.Relative -> R.string.chart_mode_relative
    ChartMode.Absolute -> R.string.chart_mode_absolute
}

/** `m:ss` up to an hour, then `h:mm:ss`. */
fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.getDefault(), hours, minutes, remainder)
    } else {
        "%d:%02d".format(Locale.getDefault(), minutes, remainder)
    }
}

private const val REFRESH_MILLIS = 200L

/**
 * How much of the screen the readings under the plot may take before they start scrolling.
 *
 * The plot has the rest, and the point of the whole layout is that "the rest" is a large
 * number: two series leave it about four fifths of the screen, and six cannot take more
 * than this.
 */
private const val STATS_SHARE = 0.22f
