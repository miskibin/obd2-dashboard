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
import androidx.compose.material.icons.filled.MoreVert
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
import com.miskibin.obd2dashboard.ui.components.MenuChoice
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.label
import com.miskibin.obd2dashboard.ui.theme.AmberText
import com.miskibin.obd2dashboard.ui.theme.CardCorner
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.PillCorner
import com.miskibin.obd2dashboard.ui.theme.SeriesColors
import com.miskibin.obd2dashboard.ui.theme.Signal
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.SlateLine
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.Steel
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
    chartMetrics: List<MetricId>,
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

    // A value that is also a line on the chart screen carries that line's colour here, so
    // the eye can go from "the coolant row is climbing" to the trace without hunting for
    // which of six lines is which. Everything else is steel: six colours in a list nobody
    // asked to be colour-coded is noise.
    val palette = SeriesColors
    val steel = Steel
    val accents = remember(chartMetrics, palette, steel) {
        chartMetrics.withIndex().associate { (index, id) -> id to palette[index % palette.size] }
            .withDefault { steel }
    }

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
                    // Only while the list is being pruned: the way out of a mode belongs
                    // to the mode, not to the screen.
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
                                .padding(horizontal = 11.dp, vertical = 7.dp),
                        )
                    }
                    DashboardMenu(
                        imperial = imperial,
                        onAddTile = onAddTile,
                        onToggleUnits = onToggleUnits,
                        onOpenSettings = onOpenSettings,
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
                modifier = Modifier.fillMaxSize().padding(top = 12.dp),
            )
            return@Column
        }

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
                                accent = accents.getValue(id),
                                samples = remember(historyRevision, id, now) {
                                    history.series(id, METRIC_SHEET_WINDOW_MILLIS, now)
                                },
                                windowMillis = METRIC_SHEET_WINDOW_MILLIS,
                                nowMillis = now,
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

            // Adding a value is configuration, so it shows up where the list is already
            // being changed; the rest of the time it lives in the header menu and the
            // dashboard is nothing but readings.
            if (editing) {
                item(key = ADD_KEY) {
                    DashedRow(
                        label = stringResource(R.string.action_add_tile),
                        trailing = "+",
                        onClick = onAddTile,
                    )
                }
            }

            // A row that said "Recording off" was a row that said nothing: the nav bar
            // carries the same dot. Running, it is worth its line — a recording the driver
            // forgot about announces itself, with the way to stop it one tap away.
            val active = recording as? RecordingState.Active
            if (active != null) {
                item(key = RECORDING_KEY) {
                    DashedRow(
                        label = stringResource(
                            R.string.dashboard_recording_active,
                            formatDuration((now - active.startedAtMillis) / 1000),
                        ),
                        trailing = "›",
                        dotColor = Signal,
                        onClick = onOpenCharts,
                    )
                }
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
            label = metric.label(),
            samples = samples,
            band = Metrics.bandFor(metricId, alertRules),
            accent = if (metricId in breached) AmberText else accents.getValue(metricId),
            windowMillis = METRIC_SHEET_WINDOW_MILLIS,
            nowMillis = now,
            onDismiss = { openMetric = null },
        )
    }
}

/**
 * Everything about the dashboard that is not a reading, behind one icon.
 *
 * The unit switch and the way into settings were two permanent controls in the header of a
 * screen whose whole job is numbers — and the units are picked once, when the app is
 * installed, and then never again. In a menu they cost nothing until they are wanted, and
 * the menu row can say which system is on instead of the header having to.
 */
@Composable
private fun DashboardMenu(
    imperial: Boolean,
    onAddTile: () -> Unit,
    onToggleUnits: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.action_more),
            tint = Smoke,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable { open = true }
                .padding(8.dp),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Slate),
        ) {
            MenuChoice(
                label = stringResource(R.string.action_add_tile),
                onClick = {
                    open = false
                    onAddTile()
                },
            )
            MenuChoice(
                label = stringResource(R.string.dashboard_units),
                detail = stringResource(
                    if (imperial) R.string.dashboard_units_imperial
                    else R.string.dashboard_units_metric,
                ),
                onClick = {
                    open = false
                    onToggleUnits()
                },
            )
            MenuChoice(
                label = stringResource(R.string.nav_settings),
                onClick = {
                    open = false
                    onOpenSettings()
                },
            )
        }
    }
}

/** The dashed row at the foot of the list: what a running recording is doing. */
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
            .padding(horizontal = Dimens.cardPaddingH, vertical = 11.dp),
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
