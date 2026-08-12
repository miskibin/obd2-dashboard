package com.miskibin.obd2dashboard.ui.diagnostics

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.obd.MonitorKind
import com.miskibin.obd2dashboard.obd.MonitorTest
import com.miskibin.obd2dashboard.obd.MonitorTests
import com.miskibin.obd2dashboard.obd.PerformanceTracking
import com.miskibin.obd2dashboard.obd.TrackedMonitor
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.components.SectionHeader
import com.miskibin.obd2dashboard.ui.theme.AmberLight
import com.miskibin.obd2dashboard.ui.theme.Ash
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.ChalkDim
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.Moss
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.SignalLight
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.Smoke
import java.util.Locale

/**
 * What the ECU's own self-tests measured, as one row that opens.
 *
 * This is the half of on-board diagnostics that fault codes leave out. A code says a
 * threshold was crossed; these numbers say how close everything else is to crossing one —
 * how many misfires each cylinder is actually counting, and how much oxygen the catalyst
 * can still store against the minimum below which P0420 gets set. Both are readings that
 * exist nowhere in live data and are worth watching for months before a light comes on.
 *
 * Shaped like the readiness row above it deliberately: a verdict on one line, and the
 * table that needs a mechanic's eye behind a chevron.
 */
@Composable
fun MonitorsCard(monitors: MonitorTests, performance: PerformanceTracking?) {
    var expanded by remember { mutableStateOf(false) }
    val failed = remember(monitors) { monitors.failed }
    // "Everything is within its limits" about a car that reported no test results at all
    // would be a reassurance with nothing behind it. A card can be worth showing on the
    // run counters alone, and then it says so.
    val counted = !monitors.isEmpty

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .clip(PanelCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, PanelCorner)
            .clickable { expanded = !expanded }
            .padding(horizontal = Dimens.cardPaddingH),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            failed.isNotEmpty() -> AmberLight
                            counted -> Moss
                            else -> Fog
                        },
                    ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.monitors_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (failed.isEmpty()) AshDim else Chalk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        failed.isNotEmpty() -> stringResource(R.string.monitors_failing, failed.size)
                        counted -> stringResource(R.string.monitors_all_ok)
                        else -> stringResource(R.string.monitors_counters_only)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed.isEmpty()) Smoke else AmberLight,
                )
            }
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = Fog,
                modifier = Modifier.size(16.dp).rotate(if (expanded) EXPANDED_ROTATION else 0f),
            )
        }

        if (!expanded) return@Column

        val misfires = monitors.misfires
        if (misfires.isNotEmpty()) {
            SectionHeader(text = stringResource(R.string.monitors_section_misfire))
            misfires.forEach { test ->
                TestRow(
                    label = stringResource(R.string.monitors_cylinder, test.cylinder ?: 0),
                    test = test,
                    // A misfire count has a ceiling and a floor of zero, so the number that
                    // means anything is the one it must stay under.
                    limit = R.string.monitors_limit_high,
                    limitValue = test.max,
                )
            }
        }

        val catalyst = monitors.catalyst
        if (catalyst.isNotEmpty()) {
            SectionHeader(text = stringResource(R.string.monitors_section_catalyst))
            catalyst.forEach { test ->
                TestRow(
                    label = stringResource(R.string.monitors_bank, test.position ?: 1),
                    test = test,
                    limit = R.string.monitors_limit_low,
                    limitValue = test.min,
                )
            }
            Note(text = stringResource(R.string.monitors_catalyst_note))
        }

        val oxygen = monitors.oxygenSensors
        if (oxygen.isNotEmpty()) {
            SectionHeader(text = stringResource(R.string.monitors_section_oxygen))
            oxygen.forEach { test ->
                TestRow(
                    label = stringResource(
                        if (test.kind == MonitorKind.OxygenSensorHeater) {
                            R.string.monitors_oxygen_heater
                        } else {
                            R.string.monitors_oxygen_switch
                        },
                        test.position ?: 1,
                    ),
                    test = test,
                    limit = R.string.monitors_limit_high,
                    limitValue = test.max,
                )
            }
        }

        if (performance != null) {
            SectionHeader(text = stringResource(R.string.monitors_section_performance))
            performance.obdConditions?.let {
                CountRow(stringResource(R.string.monitors_obd_conditions), it.toString())
            }
            performance.ignitionCycles?.let {
                CountRow(stringResource(R.string.monitors_ignition_cycles), it.toString())
            }
            performance.monitors.forEach { counts ->
                CountRow(
                    label = stringResource(counts.monitor.labelRes()),
                    value = stringResource(
                        R.string.monitors_ratio,
                        counts.completions,
                        counts.conditions,
                    ),
                    warn = counts.neverRun,
                    note = if (counts.neverRun) stringResource(R.string.monitors_never_run) else null,
                )
            }
            Note(text = stringResource(R.string.monitors_performance_note))
        }

        Spacer(Modifier.height(Dimens.cardPaddingV))
    }
}

/**
 * One measurement and the limit it has to respect.
 *
 * The limit is on the row rather than in a legend because it is what makes the value
 * readable: 0.85 g of oxygen storage is meaningless, and 0.85 g against a minimum of
 * 0.30 g is a converter with plenty of life in it.
 */
@Composable
private fun TestRow(
    label: String,
    test: MonitorTest,
    @StringRes limit: Int,
    limitValue: Double,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (test.passed) Moss else SignalLight),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = ChalkDim,
            )
            Text(
                text = stringResource(limit, format(limitValue, test.unit)),
                style = MaterialTheme.typography.labelMedium,
                color = Smoke,
            )
        }
        Text(
            text = format(test.value, test.unit),
            style = MaterialTheme.typography.bodyMedium,
            color = if (test.passed) Ash else SignalLight,
        )
    }
}

@Composable
private fun CountRow(label: String, value: String, warn: Boolean = false, note: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = ChalkDim,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberLight,
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warn) AmberLight else Ash,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Smoke,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}

/**
 * A test value with its unit, at a precision the unit deserves.
 *
 * Decided by the unit rather than by how big the number happens to be: misfires are whole
 * events and would read as "12.00 count", an oxygen switch time lives in thousandths of a
 * second and would round to nothing, and a converter's storage is worth two decimals of a
 * gram. A number in units this screen could not name is shown whole, because a decimal
 * point on a scale nobody knows is precision that has not been earned.
 */
private fun format(value: Double, unit: String): String {
    val text = String.format(Locale.getDefault(), "%.${decimalsFor(unit)}f", value)
    return if (unit.isEmpty()) text else "$text $unit"
}

private fun decimalsFor(unit: String): Int = when (unit) {
    SECONDS -> SECOND_DECIMALS
    GRAMS -> GRAM_DECIMALS
    else -> 0
}

@StringRes
private fun TrackedMonitor.labelRes(): Int = when (this) {
    TrackedMonitor.CatalystBank1 -> R.string.monitors_ipt_catalyst_1
    TrackedMonitor.CatalystBank2 -> R.string.monitors_ipt_catalyst_2
    TrackedMonitor.OxygenSensorBank1 -> R.string.monitors_ipt_oxygen_1
    TrackedMonitor.OxygenSensorBank2 -> R.string.monitors_ipt_oxygen_2
    TrackedMonitor.Egr -> R.string.monitors_ipt_egr
    TrackedMonitor.SecondaryAir -> R.string.monitors_ipt_air
    TrackedMonitor.Evaporative -> R.string.monitors_ipt_evap
    TrackedMonitor.SecondaryOxygenSensorBank1 -> R.string.monitors_ipt_secondary_oxygen_1
    TrackedMonitor.SecondaryOxygenSensorBank2 -> R.string.monitors_ipt_secondary_oxygen_2
}

private const val SECONDS = "s"
private const val GRAMS = "g"

/** An oxygen sensor switches in tens of milliseconds; two decimals would show 0.05 s. */
private const val SECOND_DECIMALS = 3

/** Oxygen storage is reported in hundredths of a gram, which is the resolution it moves in. */
private const val GRAM_DECIMALS = 2

/** A chevron pointing right becomes one pointing down when its row is open. */
private const val EXPANDED_ROTATION = 90f
