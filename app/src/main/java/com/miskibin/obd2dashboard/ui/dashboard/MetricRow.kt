package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.NormalBand
import com.miskibin.obd2dashboard.data.Sample
import com.miskibin.obd2dashboard.ui.chart.ChartMode
import com.miskibin.obd2dashboard.ui.chart.ChartSeries
import com.miskibin.obd2dashboard.ui.chart.LineChart
import com.miskibin.obd2dashboard.ui.components.DesignSheet
import com.miskibin.obd2dashboard.ui.components.NO_VALUE
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.theme.AmberSurface
import com.miskibin.obd2dashboard.ui.theme.AmberText
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.ChalkDim
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.InkRaised
import com.miskibin.obd2dashboard.ui.theme.Moss
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.SignalBorder
import com.miskibin.obd2dashboard.ui.theme.SignalSurface
import com.miskibin.obd2dashboard.ui.theme.SignalText
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SmokeDim
import com.miskibin.obd2dashboard.ui.theme.Steel
import com.miskibin.obd2dashboard.ui.theme.SteelLight

/**
 * One live value, as a row rather than a tile.
 *
 * The tile grid this replaced spent most of its area on a sparkline too small to read —
 * thirty seconds of coolant temperature in twenty-six pixels is a texture, not a trace.
 * The row spends that space on the one thing that makes a number actionable instead: what
 * normal looks like. The trace is still there, one tap away, at a size worth drawing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MetricRow(
    metric: Metric,
    value: Double?,
    band: NormalBand?,
    warn: Boolean,
    stale: Boolean,
    editing: Boolean,
    canMoveUp: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoveUp: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = value?.toFloat()?.takeIf { it.isFinite() } ?: 0f,
        animationSpec = tween(durationMillis = VALUE_ANIMATION_MILLIS),
        label = "row-value",
    )
    val dim by animateFloatAsState(
        targetValue = if (stale || value == null) STALE_ALPHA else 1f,
        animationSpec = tween(durationMillis = DIM_ANIMATION_MILLIS),
        label = "row-dim",
    )
    val label = stringResource(metric.nameRes)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (warn) AmberSurface else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Dimens.cardPaddingH, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background((if (warn) AmberText else Steel).copy(alpha = dim)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (warn) stringResource(R.string.dashboard_metric_high, label) else label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (warn) AmberText else ChalkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(dim),
            )
            val bandLabel = band.describe(metric.unit, metric.decimals)
            if (bandLabel != null) {
                Text(
                    text = bandLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = SmokeDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Text(
            text = if (value == null) NO_VALUE else formatReading(animated.toDouble(), metric.decimals),
            style = MaterialTheme.typography.titleLarge,
            color = if (warn) AmberText else Chalk,
            maxLines = 1,
            modifier = Modifier.alpha(dim),
        )
        Text(
            text = metric.unit,
            style = MaterialTheme.typography.labelMedium,
            color = Smoke,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(30.dp).alpha(dim),
        )
        AnimatedVisibility(
            visible = editing,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            // Reordering by explicit steps rather than by dragging: a drag inside a list
            // that also scrolls needs a handle nobody can find in a moving car, and one
            // tap per position is enough for a list this short.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Slate)
                        .border(1.dp, SlateEdge, RoundedCornerShape(9.dp))
                        .alpha(if (canMoveUp) 1f else DISABLED_ALPHA)
                        .clickable(enabled = canMoveUp, onClick = onMoveUp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.action_move_up),
                        tint = SteelLight,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(SignalSurface)
                        .border(1.dp, SignalBorder, RoundedCornerShape(9.dp))
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_remove_tile),
                        tint = SignalText,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/**
 * A row's last minute, at a size worth reading.
 *
 * The band behind the trace is the point of the sheet: it is what turns "104" into "still
 * inside normal, but climbing towards the edge of it", which is the question somebody
 * taps a temperature row to ask.
 */
@Composable
fun MetricSheet(
    metric: Metric,
    label: String,
    samples: List<Sample>,
    band: NormalBand?,
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
                        color = Steel,
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
                accent = Steel,
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
private const val DISABLED_ALPHA = 0.35f
private const val SHEET_PLOT_HEIGHT = 140
private const val VALUE_ANIMATION_MILLIS = 320
private const val DIM_ANIMATION_MILLIS = 400
private const val STALE_ALPHA = 0.38f
