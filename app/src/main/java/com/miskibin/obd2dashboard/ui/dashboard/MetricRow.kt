package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.unit.sp
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
import com.miskibin.obd2dashboard.ui.label
import com.miskibin.obd2dashboard.ui.theme.AmberSurface
import com.miskibin.obd2dashboard.ui.theme.AmberText
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Dimens
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.InkRaised
import com.miskibin.obd2dashboard.ui.theme.Moss
import com.miskibin.obd2dashboard.ui.theme.NumberTextStyle
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.SignalBorder
import com.miskibin.obd2dashboard.ui.theme.SignalSurface
import com.miskibin.obd2dashboard.ui.theme.SignalText
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SteelLight

/**
 * One live value, as a row rather than a tile.
 *
 * Three things share the row, in the order they are read: what it is, what it has been
 * doing, and what it is now. The label and the normal band are deliberately the quietest
 * part — they are context, and context is read once — while the number is the largest
 * thing on the line. Between them sits a minute of trace ([Sparkline]), which is what
 * turns a column of digits into a readout that is visibly alive without adding a single
 * gauge, tick or frame to the screen.
 *
 * The rule down the left is the row's colour: steel by default, the metric's own trace
 * colour when it is one of the lines on the chart screen, amber when a rule it is bound to
 * is being broken. The sparkline and the sheet behind the row take the same colour, so a
 * value keeps its identity across all three places it appears.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MetricRow(
    metric: Metric,
    value: Double?,
    band: NormalBand?,
    accent: Color,
    samples: List<Sample>,
    windowMillis: Long,
    nowMillis: Long,
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
    // The colour crosses over on the same curve the value fades on, so a rule breaking
    // reads as the row changing state rather than as a flash.
    val rule by animateColorAsState(
        targetValue = if (warn) AmberText else accent,
        animationSpec = tween(durationMillis = DIM_ANIMATION_MILLIS),
        label = "row-accent",
    )
    val label = metric.label()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (warn) AmberSurface else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Dimens.cardPaddingH, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(rule.copy(alpha = dim)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (warn) stringResource(R.string.dashboard_metric_high, label) else label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (warn) AmberText else AshDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(dim),
            )
            val bandLabel = band.describe(metric.unit, metric.decimals)
            if (bandLabel != null) {
                Text(
                    text = bandLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Fog,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        // While the list is being pruned the trace gives its width to the two controls:
        // six rows of buttons and traces at once is the clutter this row is avoiding.
        AnimatedVisibility(
            visible = !editing,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Sparkline(
                samples = samples,
                color = rule,
                idleColor = SlateEdge,
                windowMillis = windowMillis,
                nowMillis = nowMillis,
                modifier = Modifier
                    .size(width = TRACE_WIDTH.dp, height = TRACE_HEIGHT.dp)
                    .alpha(dim),
            )
        }
        Text(
            text = if (value == null) NO_VALUE else formatReading(animated.toDouble(), metric.decimals),
            style = RowValueTextStyle,
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

/**
 * The row's number, a size up from a title.
 *
 * Same face, same tabular figures as everything else that changes several times a second;
 * the extra two points are what puts it above the label instead of beside it.
 */
private val RowValueTextStyle = NumberTextStyle.copy(
    fontSize = 21.sp,
    letterSpacing = (-0.3).sp,
)

/** Wide enough for a shape, narrow enough that the number stays the loudest thing. */
private const val TRACE_WIDTH = 52
private const val TRACE_HEIGHT = 22

private const val BAND_SWATCH_ALPHA = 0.35f
private const val DISABLED_ALPHA = 0.35f
private const val SHEET_PLOT_HEIGHT = 170
private const val VALUE_ANIMATION_MILLIS = 320
private const val DIM_ANIMATION_MILLIS = 400
private const val STALE_ALPHA = 0.38f
