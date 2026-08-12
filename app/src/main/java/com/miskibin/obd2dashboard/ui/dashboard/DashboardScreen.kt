package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import com.miskibin.obd2dashboard.data.CarZone
import com.miskibin.obd2dashboard.data.GearReading
import com.miskibin.obd2dashboard.data.MetricHistory
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.MetricStatus
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.MisfireReading
import com.miskibin.obd2dashboard.data.RecordingState
import com.miskibin.obd2dashboard.data.assumptionOf
import com.miskibin.obd2dashboard.data.carZoneBindings
import com.miskibin.obd2dashboard.data.isStale
import com.miskibin.obd2dashboard.data.provenanceOf
import com.miskibin.obd2dashboard.data.statusOf
import com.miskibin.obd2dashboard.data.updatedAtOf
import com.miskibin.obd2dashboard.data.valueOf
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.chart.formatDuration
import com.miskibin.obd2dashboard.ui.components.EmptyState
import com.miskibin.obd2dashboard.ui.components.MenuChoice
import com.miskibin.obd2dashboard.ui.components.ScreenHeader
import com.miskibin.obd2dashboard.ui.components.ScreenPadding
import com.miskibin.obd2dashboard.ui.components.dashedBorder
import com.miskibin.obd2dashboard.ui.label
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.InkRaised
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.PanelRadius
import com.miskibin.obd2dashboard.ui.theme.PillCorner
import com.miskibin.obd2dashboard.ui.theme.Signal
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SteelLight
import kotlinx.coroutines.delay

/**
 * The screen the driver actually looks at.
 *
 * One hero card for the two values that get read at speed, then everything else as a grid
 * of tiles two across. A single column of rows gave every value a whole line of the screen
 * to say one number in, which meant four readings on a phone and a scroll for the fifth; a
 * tile pairs the number with the one line of plain language that says what it *is* and the
 * strip that says whether it is where it should be, and six of them fit above the fold.
 *
 * Any tile opens onto its own trace, and a long press turns the grid into something the
 * driver can prune and reorder.
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
    carZones: Set<CarZone>,
    supportedPids: Set<Int>,
    supportedExtended: Set<String>,
    misfire: MisfireReading?,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (MetricId) -> Unit,
    onAddTile: () -> Unit,
    onToggleUnits: () -> Unit,
    onToggleCarZone: (CarZone) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCharts: () -> Unit,
    onOpenMonitors: () -> Unit,
    onOpenConnection: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    var editingZones by remember { mutableStateOf(false) }
    var openMetric by remember { mutableStateOf<MetricId?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(STALENESS_TICK_MILLIS)
        }
    }

    // The hero card already draws revs and speed, so a tile for either would say it twice;
    // and a stored key the catalogue no longer knows gets no cell rather than an empty one.
    val cells = remember(tiles) {
        tiles.filterNot { it == Metrics.Rpm || it == Metrics.Speed }
            .filter { Metrics[it] != null }
    }
    LaunchedEffect(cells.size) { if (cells.isEmpty()) editing = false }

    // The controls the screen carries wherever its title happens to be: while there are
    // tiles that is inside the hero card, and while there are none it is the plain header
    // over the invitation to connect.
    val controls: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Only while the grid is being pruned: the way out of a mode belongs
            // to the mode, not to the screen.
            AnimatedVisibility(visible = editing) {
                Text(
                    text = stringResource(R.string.action_done),
                    style = MaterialTheme.typography.labelLarge,
                    color = SteelLight,
                    modifier = Modifier
                        .clip(PillCorner)
                        .background(InkRaised)
                        .border(1.dp, SlateEdge, PillCorner)
                        .clickable { editing = false }
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                )
            }
            DashboardMenu(
                imperial = imperial,
                editing = editing,
                onAddTile = {
                    editing = true
                    onAddTile()
                },
                onToggleEditing = { editing = !editing },
                onToggleUnits = onToggleUnits,
                onEditCarZones = { editingZones = true },
                onOpenSettings = onOpenSettings,
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (showEmptyState) {
            ScreenHeader(
                title = vehicleName,
                subtitle = connectionLabel,
                modifier = Modifier.clickable(onClick = onOpenConnection),
                trailing = controls,
            )
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = ScreenPadding,
                    end = ScreenPadding,
                    top = Dimens.headerTop,
                    bottom = Dimens.listBottom,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimens.cardGap),
        ) {
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
                    stale = snapshot.isStale(Metrics.Rpm, now),
                ),
                title = vehicleName,
                subtitle = connectionLabel,
                onOpenConnection = onOpenConnection,
                trailing = controls,
            )

            // The car between the hero and the grid: the same readings, placed where they
            // are taken. A tile says what the number is, the drawing says what it is *of*.
            // A car that can fill none of the places the driver ticked gets no drawing at
            // all rather than an empty outline with nothing on it.
            val bindings = remember(carZones, snapshot, supportedPids, supportedExtended, misfire) {
                carZoneBindings(carZones, snapshot, supportedPids, supportedExtended, misfire)
            }
            if (bindings.isNotEmpty()) {
                CarDiagram(
                    bindings = bindings,
                    snapshot = snapshot,
                    alertRules = alertRules,
                    misfire = misfire,
                    onOpenMetric = { openMetric = it },
                    onOpenMonitors = onOpenMonitors,
                    onEditZones = { editingZones = true },
                    modifier = Modifier.padding(horizontal = CAR_INSET),
                )
            }

            // Two across, and the pair measured together: tiles side by side that end at
            // different heights read as two lists rather than as one grid, so the row
            // takes the height of its taller cell and both fill it.
            cells.plus(null).chunked(COLUMNS).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.cardGap),
                ) {
                    pair.forEach { id ->
                        val cell = Modifier.weight(1f).fillMaxHeight()
                        val metric = id?.let { Metrics[it] }
                        if (id == null) {
                            // The empty cell at the end of the grid, rather than a control
                            // in the header: adding a value is something done *to* the
                            // grid, and the gap where the next tile would go is the only
                            // place that says so without a word of chrome.
                            AddTile(
                                label = stringResource(R.string.action_add_tile),
                                onClick = onAddTile,
                                modifier = cell,
                            )
                        } else if (metric != null) {
                            val index = cells.indexOf(id)
                            val value = snapshot.valueOf(id)
                            val band = Metrics.bandFor(id, alertRules)
                            MetricTile(
                                metric = metric,
                                value = value,
                                band = band,
                                accent = metricAccent(id),
                                samples = remember(historyRevision, id, now) {
                                    history.series(id, METRIC_SHEET_WINDOW_MILLIS, now)
                                },
                                status = band.statusOf(value) ?: MetricStatus.Normal,
                                provenance = snapshot.provenanceOf(id),
                                assumption = snapshot.assumptionOf(id),
                                stale = snapshot.isStale(id, now),
                                editing = editing,
                                canMoveUp = index > 0,
                                onClick = { openMetric = id },
                                onLongClick = {
                                    editing = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onMoveUp = {
                                    if (index > 0) {
                                        val from = tiles.indexOf(id)
                                        val to = tiles.indexOf(cells[index - 1])
                                        if (from >= 0 && to >= 0) onMove(from, to)
                                    }
                                },
                                onRemove = { onRemove(id) },
                                modifier = cell,
                            )
                        }
                    }
                    // An odd number of cells leaves the last one half a screen wide rather
                    // than stretched across the whole of it.
                    if (pair.size < COLUMNS) Spacer(modifier = Modifier.weight(1f))
                }
            }

            // A strip that said "recording off" was a strip that said nothing: the nav bar
            // carries the same dot. Running, it is worth its line — a recording the driver
            // forgot about announces itself, with the way to stop it one tap away.
            val active = recording as? RecordingState.Active
            if (active != null) {
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

    if (editingZones) {
        CarZoneSheet(
            selected = carZones,
            snapshot = snapshot,
            supportedPids = supportedPids,
            supportedExtended = supportedExtended,
            misfire = misfire,
            onToggle = onToggleCarZone,
            onDismiss = { editingZones = false },
        )
    }

    val metricId = openMetric
    val metric = metricId?.let { Metrics[it] }
    if (metricId != null && metric != null) {
        val samples = remember(historyRevision, metricId, now) {
            history.series(metricId, METRIC_SHEET_WINDOW_MILLIS, now)
        }
        val band = Metrics.bandFor(metricId, alertRules)
        val status = band.statusOf(snapshot.valueOf(metricId)) ?: MetricStatus.Normal
        MetricSheet(
            metric = metric,
            label = metric.label(),
            samples = samples,
            band = band,
            bandIsDriverSet = Metrics.bandIsDriverSet(metricId, alertRules),
            provenance = snapshot.provenanceOf(metricId),
            assumption = snapshot.assumptionOf(metricId),
            accent = if (status.breached) statusColor(status) else metricAccent(metricId),
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
    editing: Boolean,
    onAddTile: () -> Unit,
    onToggleEditing: () -> Unit,
    onToggleUnits: () -> Unit,
    onEditCarZones: () -> Unit,
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
                label = stringResource(R.string.dashboard_edit_list),
                detail = if (editing) stringResource(R.string.dashboard_edit_list_on) else null,
                onClick = {
                    open = false
                    onToggleEditing()
                },
            )
            // The only way back to the drawing once every zone has been ticked off and the
            // card has collapsed: an affordance that lives on the thing it edits is no
            // affordance at all when the thing is gone.
            MenuChoice(
                label = stringResource(R.string.car_zones_title),
                onClick = {
                    open = false
                    onEditCarZones()
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

/** The dashed strip at the foot of the grid: what a running recording is doing. */
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
            .dashedBorder(SlateEdge, PanelRadius)
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

/**
 * How far the car diagram is inset from the cards either side of it.
 *
 * The drawing has no card of its own, and a line-art car that ran to the same edge as the
 * panels above and below it would read as a third panel with its border missing.
 */
private val CAR_INSET = 4.dp

/** How much of a minute the metric sheet plots, and the tile strip measures itself over. */
const val METRIC_SHEET_WINDOW_MILLIS = 60_000L

/** Two across: wide enough for a number at 26 sp with its unit, on the narrowest phone. */
private const val COLUMNS = 2

private const val MILES_PER_KM = 0.621371
private const val STALENESS_TICK_MILLIS = 500L
