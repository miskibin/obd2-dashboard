package com.miskibin.obd2dashboard.ui.chart

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.miskibin.obd2dashboard.ui.components.AccentButton
import com.miskibin.obd2dashboard.ui.components.DangerButton
import com.miskibin.obd2dashboard.ui.components.EmptyState
import com.miskibin.obd2dashboard.ui.components.GroupedList
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.Segment
import com.miskibin.obd2dashboard.ui.components.SegmentedControl
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.CardCorner
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.PillCorner
import com.miskibin.obd2dashboard.ui.theme.SeriesColors
import com.miskibin.obd2dashboard.ui.theme.Signal
import com.miskibin.obd2dashboard.ui.theme.SignalBorder
import com.miskibin.obd2dashboard.ui.theme.SignalSurface
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SmokeDim
import com.miskibin.obd2dashboard.ui.theme.Graphite
import kotlinx.coroutines.delay
import java.util.Locale

/** The three windows worth looking at from a driver's seat. */
enum class ChartWindow(val millis: Long, val labelRes: Int) {
    Seconds30(30_000L, R.string.chart_window_30s),
    Minutes2(120_000L, R.string.chart_window_2min),
    Minutes10(600_000L, R.string.chart_window_10min),
}

/**
 * The live traces, and the record button that turns them into a file.
 *
 * The chips come from the driver's own tile set rather than the full PID catalogue —
 * charting is for the handful of values they already decided matter — and the mode
 * switch above the plot decides how those values share one set of axes.
 */
@Composable
fun ChartsScreen(
    tiles: List<MetricId>,
    chartMetrics: List<MetricId>,
    snapshot: VehicleSnapshot,
    history: MetricHistory,
    historyRevision: Long,
    recording: RecordingState,
    tripCount: Int,
    onToggleMetric: (MetricId) -> Unit,
    onToggleRecording: () -> Unit,
    onOpenRecordings: () -> Unit,
    onAddTiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var window by rememberSaveable { mutableStateOf(ChartWindow.Seconds30) }
    var mode by rememberSaveable { mutableStateOf(ChartMode.Bands) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(REFRESH_MILLIS)
        }
    }

    if (tiles.isEmpty()) {
        EmptyState(
            icon = AppIcons.Timeline,
            title = stringResource(R.string.charts_no_tiles_title),
            message = stringResource(R.string.charts_no_tiles_message),
            actionLabel = stringResource(R.string.action_add_tile),
            onAction = onAddTiles,
            modifier = modifier.fillMaxSize().padding(top = 24.dp),
        )
        return
    }

    val selected = remember(chartMetrics, tiles) {
        chartMetrics.filter { it in tiles }.ifEmpty { tiles.take(1) }
    }
    val series = remember(historyRevision, selected, window, now) {
        selected.mapIndexed { index, id ->
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

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.nav_charts),
            subtitle = stringResource(R.string.chart_window_summary, stringResource(window.labelRes)),
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

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SegmentedControl(
                segments = ChartMode.entries.map { option ->
                    Segment(stringResource(option.labelRes())) { mode = option }
                },
                selectedIndex = ChartMode.entries.indexOf(mode),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = mode.hint(units),
                style = MaterialTheme.typography.labelMedium,
                color = Fog,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )

            SeriesChips(
                tiles = tiles,
                selected = selected,
                onToggle = onToggleMetric,
                onAddTiles = onAddTiles,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardCorner)
                    .background(Slate)
                    .border(1.dp, SlateBorder, CardCorner)
                    .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
            ) {
                if (hasData) {
                    LineChart(
                        series = series,
                        windowMillis = window.millis,
                        nowMillis = now,
                        mode = mode,
                        nowLabel = stringResource(R.string.chart_now),
                        modifier = Modifier.fillMaxWidth().height(PLOT_HEIGHT.dp),
                    )
                } else {
                    EmptyState(
                        icon = AppIcons.Timeline,
                        title = stringResource(R.string.charts_waiting_title),
                        message = stringResource(R.string.charts_waiting_message),
                        modifier = Modifier.height(PLOT_HEIGHT.dp),
                    )
                }
            }

            SeriesStats(series = series, selected = selected, snapshot = snapshot)
        }

        RecordingCard(
            recording = recording,
            now = now,
            tripCount = tripCount,
            onToggleRecording = onToggleRecording,
            onOpenRecordings = onOpenRecordings,
            modifier = Modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 12.dp),
        )
    }
}

/**
 * The chips, in two states.
 *
 * A charted value carries a dash in its own line colour and a × to drop it; one the
 * driver has on the dashboard but not on the plot is a plain outline. Both are the same
 * shape, because they are the same list seen twice.
 */
@Composable
private fun SeriesChips(
    tiles: List<MetricId>,
    selected: List<MetricId>,
    onToggle: (MetricId) -> Unit,
    onAddTiles: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        tiles.forEach { id ->
            val metric = Metrics[id] ?: return@forEach
            val index = selected.indexOf(id)
            val on = index >= 0
            val color = if (on) SeriesColors[index % SeriesColors.size] else Graphite
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .clip(PillCorner)
                    .background(if (on) Slate else Color.Transparent)
                    .border(1.dp, if (on) SlateBorder else SlateEdge, PillCorner)
                    .clickable { onToggle(id) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 9.dp, height = 2.dp)
                        .background(color),
                )
                Text(
                    text = stringResource(metric.nameRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (on) AshDim else Smoke,
                    maxLines = 1,
                )
                if (on) {
                    Text(text = "×", style = MaterialTheme.typography.bodySmall, color = SmokeDim)
                }
            }
        }
        Text(
            text = stringResource(R.string.chart_add_parameter),
            style = MaterialTheme.typography.bodySmall,
            color = Smoke,
            modifier = Modifier
                .clip(PillCorner)
                .border(1.dp, SlateEdge, PillCorner)
                .clickable(onClick = onAddTiles)
                .padding(horizontal = 11.dp, vertical = 7.dp),
        )
    }
}

/** What each line did over the window, and what it reads right now. */
@Composable
private fun SeriesStats(
    series: List<ChartSeries>,
    selected: List<MetricId>,
    snapshot: VehicleSnapshot,
) {
    if (series.isEmpty()) return
    GroupedList(modifier = Modifier.fillMaxWidth()) {
        series.forEachIndexed { index, line ->
            val id = selected.getOrNull(index)
            val metric = id?.let { Metrics[it] }
            val values = line.samples.map(Sample::value)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Slate)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 9.dp, height = 2.dp)
                        .background(line.color),
                )
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
                    text = "${formatReading(id?.let(snapshot::valueOf), line.decimals)} ${metric?.unit.orEmpty()}"
                        .trim(),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = line.color,
                )
            }
        }
    }
}

/**
 * Recording, as a card rather than a button.
 *
 * While it runs the card itself turns red — a recording that quietly fills the phone
 * because the driver forgot about it is the failure mode worth designing against.
 */
@Composable
private fun RecordingCard(
    recording: RecordingState,
    now: Long,
    tripCount: Int,
    onToggleRecording: () -> Unit,
    onOpenRecordings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = recording as? RecordingState.Active
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardCorner)
            .background(if (active != null) SignalSurface else Slate)
            .border(1.dp, if (active != null) SignalBorder else SlateBorder, CardCorner)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (active != null) Signal else Graphite),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    if (active != null) R.string.charts_recording_on else R.string.charts_recording_off,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = Chalk,
            )
            Text(
                text = if (active != null) {
                    stringResource(
                        R.string.charts_recording_meta,
                        formatDuration((now - active.startedAtMillis) / 1000),
                    )
                } else {
                    stringResource(R.string.charts_recordings, tripCount)
                },
                style = MaterialTheme.typography.labelMedium,
                color = Smoke,
                modifier = Modifier.clickable(enabled = active == null, onClick = onOpenRecordings),
            )
        }
        if (active != null) {
            DangerButton(
                label = stringResource(R.string.chart_record_stop),
                onClick = onToggleRecording,
            )
        } else {
            AccentButton(
                label = stringResource(R.string.chart_record_start),
                onClick = onToggleRecording,
            )
        }
    }
}

private fun ChartMode.labelRes(): Int = when (this) {
    ChartMode.Bands -> R.string.chart_mode_bands
    ChartMode.Relative -> R.string.chart_mode_relative
    ChartMode.Absolute -> R.string.chart_mode_absolute
}

@Composable
private fun ChartMode.hint(units: List<String>): String = when (this) {
    ChartMode.Bands -> stringResource(R.string.chart_mode_bands_hint)
    ChartMode.Relative -> stringResource(R.string.chart_mode_relative_hint)
    ChartMode.Absolute -> when {
        units.size > 1 -> stringResource(R.string.chart_mode_absolute_mixed, units.joinToString(", "))
        else -> stringResource(R.string.chart_mode_absolute_hint, units.firstOrNull().orEmpty())
    }
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
private const val PLOT_HEIGHT = 250
