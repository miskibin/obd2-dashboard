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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
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
import com.miskibin.obd2dashboard.ui.components.MenuChoice
import com.miskibin.obd2dashboard.ui.components.MenuLabel
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
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
import com.miskibin.obd2dashboard.ui.theme.SlateLine
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SmokeDim
import com.miskibin.obd2dashboard.ui.theme.SteelLight
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
 * one anybody chose to stare at while driving.
 *
 * Everything that configures the plot — how long a window, how the traces share axes,
 * which parameters are on it — used to sit above the plot as three permanent rows of
 * controls for decisions taken about twice a month. It is folded away now: the window and
 * the axis mode into one chip in the header that reads out the current choice, and the
 * parameters into the legend under the plot, where each line can be dropped from the same
 * row that names it. What is left above the plot is the plot.
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
    // middle of the plot, so the traces and the legend stay in step.
    val plotted = remember(chartMetrics) { chartMetrics.filter { Metrics[it] != null } }
    val palette = SeriesColors
    val series = remember(historyRevision, plotted, window, now, palette) {
        plotted.mapIndexed { index, id ->
            val metric = Metrics[id]
            ChartSeries(
                key = id.storageKey,
                label = metric?.let { context.getString(it.nameRes) }.orEmpty(),
                color = palette[index % palette.size],
                unit = metric?.unit.orEmpty(),
                decimals = metric?.decimals ?: 1,
                samples = history.series(id, window.millis, now),
            )
        }
    }
    // A series with nothing behind it is not plotted at all. Drawn anyway it becomes a
    // straight line across the middle of the plot — a reading of exactly the average of a
    // range it never had — which is indistinguishable from a real, perfectly steady value.
    val drawn = remember(series) { series.filter { it.samples.isNotEmpty() } }
    // And when the car has published its supported list, a parameter missing from it is
    // never going to arrive, which the legend says rather than leaving a row waiting.
    val unavailable = remember(plotted, supportedPids) {
        if (supportedPids.isEmpty()) {
            emptySet()
        } else {
            plotted.filter { Metrics[it]?.isAvailable(supportedPids, supportKnown = true) == false }
                .toSet()
        }
    }
    val hasData = drawn.isNotEmpty()
    val units = remember(series) { series.map(ChartSeries::unit).filter(String::isNotBlank).distinct() }
    val rate = remember(series, window) { sampleRateOf(series, window) }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val legendCap = with(LocalDensity.current) { (windowHeight * LEGEND_SHARE).toDp() }

    Column(modifier = modifier.fillMaxSize()) {
        // One row of chrome for the whole screen: what car, what it is doing, and the one
        // control that changes how the plot is drawn.
        ScreenHeader(
            title = vehicleLabel,
            // The chip on the right already names the window, so the subtitle is only
            // worth a line when it has something else to say.
            subtitle = if (rate > 0) stringResource(R.string.chart_window_rate, rate) else null,
            leading = {
                RecordControl(
                    recording = recording,
                    now = now,
                    onToggleRecording = onToggleRecording,
                )
            },
            trailing = {
                ChartConfigChip(
                    window = window,
                    mode = mode,
                    onWindow = { window = it },
                    onMode = { mode = it },
                )
            },
        )

        // Nothing here is a fixed height: the plot is the screen, so it takes whatever the
        // legend leaves, which on a phone in portrait is most of it.
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = ScreenPadding)
                .padding(bottom = Dimens.listBottom),
            verticalArrangement = Arrangement.spacedBy(Dimens.cardGap),
        ) {
            // The one thing a driver could not work out from the mode's own name: an
            // absolute axis is a lie when the series are not in the same units. It costs a
            // line only when it is true, and the plot absorbs the difference rather than
            // the layout jumping.
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
                        series = drawn,
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

            SeriesLegend(
                series = series,
                metrics = plotted,
                unavailable = unavailable,
                snapshot = snapshot,
                maxHeight = legendCap,
                onRemove = onToggleMetric,
                onAdd = { picking = true },
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
 * The whole chart configuration as one chip that reads out its own state.
 *
 * "60 s · Separate" says what the plot is doing without being asked, and opens onto the
 * two choices behind it. Two segmented controls spanning the screen said the same thing
 * permanently, in the space the plot wanted, for settings that are changed once and then
 * lived with.
 */
@Composable
private fun ChartConfigChip(
    window: ChartWindow,
    mode: ChartMode,
    onWindow: (ChartWindow) -> Unit,
    onMode: (ChartMode) -> Unit,
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
                text = stringResource(window.labelRes) + CHIP_SEPARATOR +
                    stringResource(mode.labelRes()),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = SteelLight,
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
            MenuLabel(stringResource(R.string.chart_menu_window))
            ChartWindow.entries.forEach { option ->
                MenuChoice(
                    label = stringResource(option.summaryRes),
                    selected = option == window,
                    onClick = {
                        onWindow(option)
                        open = false
                    },
                )
            }
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
 * What each line did over the window, what it reads now, and the way to add or drop one.
 *
 * The legend and the control for the legend are the same object: a row already carries the
 * colour and the name of its trace, so the × that removes it belongs there rather than in
 * a second row of chips above the plot saying the same names again. The last row is how a
 * parameter gets on, which is why it is here even when nothing is plotted yet.
 *
 * No card and no border: these are a caption for the plot directly above them. [maxHeight]
 * is for the full-legend case only — the plot has first claim on the screen, so past that
 * the rows scroll among themselves.
 */
@Composable
private fun SeriesLegend(
    series: List<ChartSeries>,
    metrics: List<MetricId>,
    unavailable: Set<MetricId>,
    snapshot: VehicleSnapshot,
    maxHeight: Dp,
    onRemove: (MetricId) -> Unit,
    onAdd: () -> Unit,
) {
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
                if (values.isNotEmpty()) {
                    Text(
                        text = "${formatReading(values.min().toDouble(), line.decimals)}–" +
                            formatReading(values.max().toDouble(), line.decimals),
                        style = MaterialTheme.typography.labelMedium,
                        color = SmokeDim,
                    )
                } else if (id != null && id in unavailable) {
                    Text(
                        text = stringResource(R.string.chart_series_unavailable),
                        style = MaterialTheme.typography.labelMedium,
                        color = SmokeDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${formatReading(id?.let(snapshot::valueOf), line.decimals)} ${line.unit}"
                        .trim(),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = line.color,
                )
                Box(
                    modifier = Modifier
                        .size(REMOVE_TARGET.dp)
                        .clip(PillCorner)
                        .clickable(
                            enabled = id != null,
                            onClickLabel = stringResource(
                                R.string.chart_remove_parameter,
                                line.label,
                            ),
                        ) { id?.let(onRemove) },
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

        if (series.isNotEmpty()) {
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

/** Between the window and the axis mode on the chip that carries both. */
private const val CHIP_SEPARATOR = " · "

/** The × in a legend row: a thumb target that does not make the row taller. */
private const val REMOVE_TARGET = 34

/** Every legend row, including the one that adds a parameter, is this tall. */
private const val LEGEND_ROW_HEIGHT = 42

/**
 * How much of the screen the legend may take before it starts scrolling.
 *
 * The plot has the rest, and the point of the whole layout is that "the rest" is a large
 * number: two series leave it about three quarters of the screen, and a full legend plus
 * the row that adds to it cannot take more than this.
 */
private const val LEGEND_SHARE = 0.32f
