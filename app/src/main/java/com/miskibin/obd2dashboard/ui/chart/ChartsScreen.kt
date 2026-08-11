package com.miskibin.obd2dashboard.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.MetricHistory
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.RecordingState
import com.miskibin.obd2dashboard.data.valueOf
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.components.EmptyState
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.theme.SeriesColors
import kotlinx.coroutines.delay
import java.util.Locale

/** The three windows worth looking at from a driver's seat. */
enum class ChartWindow(val millis: Long, val labelRes: Int) {
    Seconds30(30_000L, R.string.chart_window_30s),
    Minutes2(120_000L, R.string.chart_window_2min),
    Minutes10(600_000L, R.string.chart_window_10min),
}

/**
 * Up to three live traces, and the record button that turns them into a file.
 *
 * The chips come from the driver's own tile set rather than the full PID catalogue —
 * charting is for the handful of values they already decided matter.
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
            modifier = modifier.fillMaxSize().padding(top = 32.dp),
        )
        return
    }

    val selected = remember(chartMetrics, tiles) {
        chartMetrics.filter { it in tiles }.ifEmpty { tiles.take(1) }
    }
    val series = remember(historyRevision, selected, window, now) {
        selected.mapIndexed { index, id ->
            ChartSeries(
                key = id.storageKey,
                label = Metrics[id]?.let { context.getString(it.nameRes) }.orEmpty(),
                color = SeriesColors[index % SeriesColors.size],
                samples = history.series(id, window.millis, now),
            )
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.forEach { id ->
                val metric = Metrics[id] ?: return@forEach
                FilterChip(
                    selected = id in selected,
                    onClick = { onToggleMetric(id) },
                    label = { Text(stringResource(metric.nameRes), maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            ChartWindow.entries.forEach { option ->
                FilterChip(
                    selected = option == window,
                    onClick = { window = option },
                    label = { Text(stringResource(option.labelRes)) },
                )
            }
        }

        val hasData = series.any { it.samples.isNotEmpty() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
        ) {
            if (hasData) {
                LineChart(
                    series = series,
                    windowMillis = window.millis,
                    nowMillis = now,
                    axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
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

        Legend(series = series, selected = selected, snapshot = snapshot)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            RecordButton(
                recording = recording,
                now = now,
                onClick = onToggleRecording,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenRecordings, modifier = Modifier.heightIn(min = 52.dp)) {
                Text(stringResource(R.string.charts_recordings, tripCount))
            }
        }
    }
}

@Composable
private fun Legend(
    series: List<ChartSeries>,
    selected: List<MetricId>,
    snapshot: VehicleSnapshot,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        series.forEachIndexed { index, line ->
            val id = selected.getOrNull(index) ?: return@forEachIndexed
            val metric = Metrics[id] ?: return@forEachIndexed
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(line.color),
                )
                Text(
                    text = "${formatReading(snapshot.valueOf(id), metric.decimals)} ${metric.unit}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun RecordButton(
    recording: RecordingState,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = recording as? RecordingState.Active
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        colors = if (active != null) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        androidx.compose.material3.Icon(
            imageVector = if (active != null) AppIcons.StopSquare else AppIcons.RecordDot,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (active != null) {
                stringResource(
                    R.string.action_stop_recording,
                    formatDuration((now - active.startedAtMillis) / 1000),
                )
            } else {
                stringResource(R.string.action_record)
            },
        )
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
