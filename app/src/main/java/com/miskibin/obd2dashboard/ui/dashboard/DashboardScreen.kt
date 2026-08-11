package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.AlertRule
import com.miskibin.obd2dashboard.data.GearReading
import com.miskibin.obd2dashboard.data.MetricHistory
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.RecordingState
import com.miskibin.obd2dashboard.data.isBreached
import com.miskibin.obd2dashboard.data.updatedAtOf
import com.miskibin.obd2dashboard.data.valueOf
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.chart.formatDuration
import com.miskibin.obd2dashboard.ui.components.EmptyState
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.theme.CardCorner
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.Graphite
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.PillCorner
import com.miskibin.obd2dashboard.ui.theme.Signal
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.SlateLine
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SteelLight
import kotlinx.coroutines.delay

/**
 * The screen the driver actually looks at.
 *
 * One hero card for the three values that get read at speed, then everything else as a
 * list of rows: label, what normal looks like, the number. A grid of equal tiles gave
 * every value the same weight, which is not how a dashboard is read — coolant temperature
 * does not deserve the same area as engine speed. Any row opens onto its own trace, and a
 * long press turns the list into something the driver can prune and reorder.
 */
@Composable
fun DashboardScreen(
    vehicleName: String,
    connectionLabel: String,
    tiles: List<MetricId>,
    snapshot: VehicleSnapshot,
    history: MetricHistory,
    historyRevision: Long,
    showEmptyState: Boolean,
    redline: Int,
    sessionMaxRpm: Double?,
    gear: GearReading,
    imperial: Boolean,
    alertRules: List<AlertRule>,
    recording: RecordingState,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (MetricId) -> Unit,
    onAddTile: () -> Unit,
    onToggleUnits: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCharts: () -> Unit,
    onOpenConnection: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    var openMetric by remember { mutableStateOf<MetricId?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(STALENESS_TICK_MILLIS)
        }
    }

    // The hero card already draws revs and speed, so a row for either would say it twice.
    val rows = remember(tiles) { tiles.filterNot { it == Metrics.Rpm || it == Metrics.Speed } }
    LaunchedEffect(rows.size) { if (rows.isEmpty()) editing = false }

    val breached = remember(snapshot, alertRules) {
        alertRules.filter { rule ->
            val value = snapshot.valueOf(rule.metric)
            rule.enabled && value != null && rule.isBreached(value)
        }.map(AlertRule::metric).toSet()
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = vehicleName,
            subtitle = connectionLabel,
            modifier = Modifier.clickable(onClick = onOpenConnection),
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnimatedVisibility(visible = editing) {
                        Text(
                            text = stringResource(R.string.action_done),
                            style = MaterialTheme.typography.labelLarge,
                            color = SteelLight,
                            modifier = Modifier
                                .clip(PillCorner)
                                .background(Slate)
                                .border(1.dp, SlateBorder, PillCorner)
                                .clickable { editing = false }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Text(
                        text = stringResource(
                            if (imperial) R.string.dashboard_units_imperial
                            else R.string.dashboard_units_metric,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = Smoke,
                        modifier = Modifier
                            .clip(PillCorner)
                            .background(Slate)
                            .border(1.dp, SlateBorder, PillCorner)
                            .clickable(onClick = onToggleUnits)
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                    )
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.nav_settings),
                        tint = Smoke,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onOpenSettings)
                            .padding(8.dp),
                    )
                }
            },
        )

        if (showEmptyState) {
            EmptyState(
                icon = AppIcons.Bluetooth,
                title = stringResource(R.string.dashboard_empty_title),
                message = stringResource(R.string.dashboard_empty_message),
                actionLabel = stringResource(R.string.action_connect),
                onAction = onConnect,
                modifier = Modifier.fillMaxSize().padding(top = 24.dp),
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = ScreenPadding,
                end = ScreenPadding,
                top = 4.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = HERO_KEY) {
                HeroCard(
                    state = HeroState(
                        rpm = snapshot.valueOf(Metrics.Rpm),
                        redline = redline,
                        sessionMaxRpm = sessionMaxRpm,
                        speed = snapshot.valueOf(Metrics.Speed)
                            ?.let { if (imperial) it * MILES_PER_KM else it },
                        speedUnit = stringResource(
                            if (imperial) R.string.unit_mph else R.string.unit_kmh,
                        ),
                        gear = gear,
                        stale = now - snapshot.updatedAtOf(Metrics.Rpm) > STALE_AFTER_MILLIS,
                    ),
                )
            }

            if (rows.isNotEmpty()) {
                item(key = ROWS_KEY) {
                    // One card, hairlines between the rows: a stack of separately rounded
                    // rows would read as a list of buttons rather than as one readout.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CardCorner)
                            .background(Slate)
                            .border(1.dp, SlateBorder, CardCorner),
                    ) {
                        rows.forEachIndexed { index, id ->
                            val metric = Metrics[id] ?: return@forEachIndexed
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(SlateLine),
                                )
                            }
                            MetricRow(
                                metric = metric,
                                value = snapshot.valueOf(id),
                                band = Metrics.bandFor(id, alertRules),
                                warn = id in breached,
                                stale = now - snapshot.updatedAtOf(id) > STALE_AFTER_MILLIS,
                                editing = editing,
                                canMoveUp = index > 0,
                                onClick = { openMetric = id },
                                onLongClick = {
                                    editing = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onMoveUp = {
                                    val from = tiles.indexOf(id)
                                    val to = tiles.indexOf(rows[index - 1])
                                    if (from >= 0 && to >= 0) onMove(from, to)
                                },
                                onRemove = { onRemove(id) },
                            )
                        }
                    }
                }
            }

            item(key = ADD_KEY) {
                DashedRow(
                    label = stringResource(R.string.action_add_tile),
                    trailing = "+",
                    onClick = onAddTile,
                )
            }

            item(key = RECORDING_KEY) {
                val active = recording as? RecordingState.Active
                DashedRow(
                    label = if (active != null) {
                        stringResource(
                            R.string.dashboard_recording_active,
                            formatDuration((now - active.startedAtMillis) / 1000),
                        )
                    } else {
                        stringResource(R.string.dashboard_recording_idle)
                    },
                    trailing = "›",
                    dotColor = if (active != null) Signal else Graphite,
                    onClick = onOpenCharts,
                )
            }
        }
    }

    val metricId = openMetric
    val metric = metricId?.let { Metrics[it] }
    if (metricId != null && metric != null) {
        val samples = remember(historyRevision, metricId, now) {
            history.series(metricId, METRIC_SHEET_WINDOW_MILLIS, now)
        }
        MetricSheet(
            metric = metric,
            label = stringResource(metric.nameRes),
            samples = samples,
            band = Metrics.bandFor(metricId, alertRules),
            windowMillis = METRIC_SHEET_WINDOW_MILLIS,
            nowMillis = now,
            onDismiss = { openMetric = null },
        )
    }
}

/** The dashed rows at the foot of the list: add a value, and what recording is doing. */
@Composable
private fun DashedRow(
    label: String,
    trailing: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .border(1.dp, SlateEdge, PanelCorner)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (dotColor != null) {
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dotColor))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Smoke,
            modifier = Modifier.weight(1f),
        )
        Text(text = trailing, style = MaterialTheme.typography.bodyLarge, color = Fog)
    }
}

/** How much of a minute the metric sheet plots. */
const val METRIC_SHEET_WINDOW_MILLIS = 60_000L

private const val HERO_KEY = "hero"
private const val ROWS_KEY = "rows"
private const val ADD_KEY = "add-metric"
private const val RECORDING_KEY = "recording"
private const val MILES_PER_KM = 0.621371
private const val STALENESS_TICK_MILLIS = 500L
