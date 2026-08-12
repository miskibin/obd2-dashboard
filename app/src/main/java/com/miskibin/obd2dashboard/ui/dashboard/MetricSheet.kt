package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.NormalBand
import com.miskibin.obd2dashboard.data.Sample
import com.miskibin.obd2dashboard.obd.Assumption
import com.miskibin.obd2dashboard.obd.Provenance
import com.miskibin.obd2dashboard.ui.chart.ChartMode
import com.miskibin.obd2dashboard.ui.chart.ChartSeries
import com.miskibin.obd2dashboard.ui.chart.LineChart
import com.miskibin.obd2dashboard.ui.components.DesignSheet
import com.miskibin.obd2dashboard.ui.components.EstimateMark
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.components.provenanceText
import com.miskibin.obd2dashboard.ui.theme.AmberText
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.InkRaised
import com.miskibin.obd2dashboard.ui.theme.Moss
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.Smoke

/**
 * A tile's last minute, at a size worth reading.
 *
 * The strip on the tile says where the value is; this says how it got there. The band
 * behind the trace is the point of it — it is what turns "104" into "still inside normal,
 * but climbing towards the edge of it", which is the question somebody taps a temperature
 * tile to ask.
 */
@Composable
fun MetricSheet(
    metric: Metric,
    label: String,
    samples: List<Sample>,
    band: NormalBand?,
    bandIsDriverSet: Boolean,
    provenance: Provenance,
    assumption: Assumption?,
    accent: Color,
    windowMillis: Long,
    nowMillis: Long,
    onDismiss: () -> Unit,
) {
    val values = samples.map(Sample::value)
    DesignSheet(
        title = label,
        subtitle = stringResource(R.string.metric_sheet_subtitle),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(PanelCorner)
                .background(InkRaised)
                .border(1.dp, SlateBorder, PanelCorner)
                .padding(start = 11.dp, end = 11.dp, top = 10.dp, bottom = 8.dp),
        ) {
            LineChart(
                series = listOf(
                    ChartSeries(
                        key = metric.id.storageKey,
                        label = label,
                        color = accent,
                        unit = metric.unit,
                        decimals = metric.decimals,
                        samples = samples,
                    ),
                ),
                windowMillis = windowMillis,
                nowMillis = nowMillis,
                mode = ChartMode.Absolute,
                nowLabel = stringResource(R.string.chart_now),
                band = band,
                modifier = Modifier.fillMaxWidth().height(SHEET_PLOT_HEIGHT.dp),
            )

            val bandLabel = band.describe(metric.unit, metric.decimals)
            if (bandLabel != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(1.dp)
                        .background(SlateBorder),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 16.dp, height = 8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Moss.copy(alpha = BAND_SWATCH_ALPHA)),
                    )
                    Text(
                        text = stringResource(R.string.metric_sheet_band, bandLabel),
                        style = MaterialTheme.typography.labelMedium,
                        color = Smoke,
                    )
                }
                // Where the band came from, which is not a detail: nothing in OBD2 reports
                // what a given engine's normal running temperature is, so a car that is
                // meant to sit at 103 °C would otherwise read as permanently too hot
                // against a range the app made up for it.
                Text(
                    text = stringResource(
                        if (bandIsDriverSet) {
                            R.string.metric_sheet_band_rule
                        } else {
                            R.string.metric_sheet_band_generic
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Fog,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatCard(
                label = stringResource(R.string.metric_sheet_now),
                value = formatReading(values.lastOrNull()?.toDouble(), metric.decimals),
                unit = metric.unit,
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = stringResource(R.string.metric_sheet_min),
                value = formatReading(values.minOrNull()?.toDouble(), metric.decimals),
                unit = metric.unit,
                accent = Chalk,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = stringResource(R.string.metric_sheet_max),
                value = formatReading(values.maxOrNull()?.toDouble(), metric.decimals),
                unit = metric.unit,
                accent = Chalk,
                modifier = Modifier.weight(1f),
            )
        }

        // Under the numbers rather than over them: somebody who taps a tile is looking at
        // the trace first, and reads what the reading actually is once they have.
        Text(
            text = stringResource(metric.descriptionRes),
            style = MaterialTheme.typography.bodyMedium,
            color = Smoke,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        // What the app is claiming by showing this at all, and — where one was needed —
        // which constant it had to supply. The paragraph above says what the parameter is
        // in general; this says what *this* number, on *this* car, right now, is worth.
        if (provenance.estimated) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                EstimateMark(provenance = provenance, assumption = assumption)
                Text(
                    text = provenanceText(provenance, assumption),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (provenance == Provenance.Assumed) AmberText else Smoke,
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = Smoke)
        Text(
            text = "$value $unit".trim(),
            style = MaterialTheme.typography.titleLarge,
            color = accent,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** The band as one line of text: a range, a ceiling, a floor, or nothing at all. */
@Composable
fun NormalBand?.describe(unit: String, decimals: Int): String? {
    if (this == null || isEmpty) return null
    val suffix = if (unit.isBlank()) "" else " $unit"
    val low = min?.let { formatReading(it, decimals) }
    val high = max?.let { formatReading(it, decimals) }
    return when {
        low != null && high != null -> "$low–$high$suffix"
        high != null -> stringResource(R.string.metric_band_below, "$high$suffix")
        low != null -> stringResource(R.string.metric_band_above, "$low$suffix")
        else -> null
    }
}

private const val BAND_SWATCH_ALPHA = 0.35f
private const val SHEET_PLOT_HEIGHT = 170
