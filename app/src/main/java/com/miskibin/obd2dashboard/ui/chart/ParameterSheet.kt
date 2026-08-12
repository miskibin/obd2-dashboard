package com.miskibin.obd2dashboard.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.MetricGroup
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.ui.components.AccentButton
import com.miskibin.obd2dashboard.ui.components.DesignSheet
import com.miskibin.obd2dashboard.ui.label
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.Ink
import com.miskibin.obd2dashboard.ui.theme.InkRaised
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.Steel

/**
 * Which parameters go on the plot.
 *
 * Forty PIDs in one alphabetical list is a list nobody reads; grouped by where the number
 * comes from, it is four short lists, and somebody chasing a lean mixture can go straight
 * to the one with the trims and the airflow in it. Unsupported PIDs are simply absent
 * here — unlike the tile picker, which shows them greyed out, this sheet is opened
 * mid-diagnosis and a value the car will never answer for is only in the way.
 */
@Composable
fun ParameterSheet(
    selected: List<MetricId>,
    supportedPids: Set<Int>,
    maxSeries: Int,
    onToggle: (MetricId) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val supportKnown = supportedPids.isNotEmpty()
    val groups = remember(context, supportedPids, selected) {
        MetricGroup.entries.map { group ->
            group to Metrics.catalog
                .filter { Metrics.groupOf(it.id) == group }
                .filter { it.id in selected || it.isAvailable(supportedPids, supportKnown) }
                .sortedBy { it.label(context) }
        }.filter { (_, items) -> items.isNotEmpty() }
    }

    DesignSheet(
        title = stringResource(R.string.chart_picker_title),
        subtitle = stringResource(R.string.chart_picker_subtitle, maxSeries),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .heightIn(max = PICKER_MAX_HEIGHT.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            groups.forEach { (group, items) ->
                Column {
                    Text(
                        text = stringResource(group.titleRes()).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Fog,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PanelCorner)
                            .background(SlateBorder)
                            .border(1.dp, SlateBorder, PanelCorner),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        items.forEach { metric ->
                            ParameterRow(
                                label = metric.label(),
                                unit = metric.unit,
                                selected = metric.id in selected,
                                onToggle = { onToggle(metric.id) },
                            )
                        }
                    }
                }
            }
        }

        AccentButton(
            label = pluralStringResource(
                R.plurals.chart_picker_done,
                selected.size,
                selected.size,
            ),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

/**
 * One parameter, ticked or not.
 *
 * Takes the label rather than the [Metric] because the trip screen picks from what a
 * recording happens to carry, which can include a column this build of the app no longer
 * has a definition for — and a row that vanishes because the catalogue moved on is worse
 * than a row named by its storage key.
 */
@Composable
fun ParameterRow(
    label: String,
    unit: String,
    selected: Boolean,
    onToggle: () -> Unit,
    detail: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(InkRaised)
            .clickable(onClick = onToggle)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (selected) Steel else Color.Transparent)
                .border(1.5.dp, if (selected) Steel else SlateEdge, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text(text = "✓", style = MaterialTheme.typography.labelSmall, color = Ink)
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Chalk else AshDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = Smoke,
                maxLines = 1,
            )
        }
        if (unit.isNotBlank()) {
            Text(
                text = unit,
                style = MaterialTheme.typography.labelMedium,
                color = Fog,
            )
        }
    }
}

private fun MetricGroup.titleRes(): Int = when (this) {
    MetricGroup.Engine -> R.string.metric_group_engine
    MetricGroup.Temperature -> R.string.metric_group_temperature
    MetricGroup.Mixture -> R.string.metric_group_mixture
    MetricGroup.Vehicle -> R.string.metric_group_vehicle
}

/** A metric the car answers for, or one whose support is not known yet. */
fun Metric.isAvailable(supportedPids: Set<Int>, supportKnown: Boolean): Boolean =
    when (val metricId = id) {
        is MetricId.Sensor -> !supportKnown || metricId.pid in supportedPids
        else -> true
    }

private const val PICKER_MAX_HEIGHT = 360
