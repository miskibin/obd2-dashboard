package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.MetricGroup
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.MetricStatus
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.NormalBand
import com.miskibin.obd2dashboard.data.Sample
import com.miskibin.obd2dashboard.data.statusOf
import com.miskibin.obd2dashboard.obd.Assumption
import com.miskibin.obd2dashboard.obd.DerivedMetrics
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.Provenance
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.components.EstimateMark
import com.miskibin.obd2dashboard.ui.components.NO_VALUE
import com.miskibin.obd2dashboard.ui.components.dashedBorder
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.label
import com.miskibin.obd2dashboard.ui.theme.AmberBorder
import com.miskibin.obd2dashboard.ui.theme.AmberLight
import com.miskibin.obd2dashboard.ui.theme.AmberSurface
import com.miskibin.obd2dashboard.ui.theme.AmberText
import com.miskibin.obd2dashboard.ui.theme.AshDim
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.Graphite
import com.miskibin.obd2dashboard.ui.theme.InkRaised
import com.miskibin.obd2dashboard.ui.theme.Moss
import com.miskibin.obd2dashboard.ui.theme.NumberTextStyle
import com.miskibin.obd2dashboard.ui.theme.PanelCorner
import com.miskibin.obd2dashboard.ui.theme.PanelRadius
import com.miskibin.obd2dashboard.ui.theme.SeriesColors
import com.miskibin.obd2dashboard.ui.theme.SignalBorder
import com.miskibin.obd2dashboard.ui.theme.SignalLight
import com.miskibin.obd2dashboard.ui.theme.SignalSurface
import com.miskibin.obd2dashboard.ui.theme.SignalText
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.SlateTrack
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.Steel
import com.miskibin.obd2dashboard.ui.theme.SteelLight
import kotlin.math.abs

/**
 * One live value, as a tile.
 *
 * Four things share the card, in the order they are read: what it is (a glyph and a name),
 * what that means in plain language, what it says right now, and where that sits against
 * normal. The last of those is the strip along the foot — not a trace of the last minute,
 * which asked the driver to judge a shape, but a ruler: the pale band is the range the
 * value should be inside, the wash is everywhere it has been this session, and the mark is
 * where it is. "104" becomes "still inside normal, near the top of it" without a number
 * being read twice.
 *
 * A tile whose value has left its band tints whole — card, edge, name and value — because a
 * coloured rule down one side is exactly what is missed in peripheral vision. Amber says
 * outside; the signal red is kept for readings far enough outside to be worth stopping for.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MetricTile(
    metric: Metric,
    value: Double?,
    band: NormalBand?,
    accent: Color,
    samples: List<Sample>,
    status: MetricStatus,
    provenance: Provenance,
    assumption: Assumption?,
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
        label = "tile-value",
    )
    val dim by animateFloatAsState(
        targetValue = if (stale || value == null) STALE_ALPHA else 1f,
        animationSpec = tween(durationMillis = DIM_ANIMATION_MILLIS),
        label = "tile-dim",
    )
    // The colour crosses over on the same curve the value fades on, so a band being left
    // reads as the tile changing state rather than as a flash.
    val mark by animateColorAsState(
        targetValue = statusColor(status),
        animationSpec = tween(durationMillis = DIM_ANIMATION_MILLIS),
        label = "tile-status",
    )
    val (surface, edge) = statusSurface(status)
    val warn = status.breached

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanelCorner)
            .background(surface)
            .border(1.dp, edge, PanelCorner)
            .combinedClickable(
                onClick = { if (!editing) onClick() },
                onLongClick = onLongClick,
            )
            .padding(top = 10.dp, bottom = 11.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = TILE_PADDING.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The glyph keeps the family's own colour whatever the state is doing: it says
            // what kind of reading this is, which does not change when the number does.
            Box(
                modifier = Modifier
                    .size(GLYPH_BOX.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = GLYPH_FILL))
                    .border(
                        width = 1.dp,
                        color = accent.copy(alpha = GLYPH_EDGE),
                        shape = CircleShape,
                    )
                    .alpha(dim),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = metricGlyph(metric.id),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(GLYPH.dp),
                )
            }
            Text(
                text = metric.label(),
                style = MaterialTheme.typography.bodySmall,
                color = if (warn) mark else AshDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alpha(dim),
            )
            // Reordering by explicit steps rather than by dragging: a drag inside a grid
            // that also scrolls needs a handle nobody can find in a moving car, and one
            // tap per position is enough for a list this short.
            AnimatedVisibility(
                visible = editing,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TileButton(
                        icon = Icons.Default.KeyboardArrowUp,
                        description = stringResource(R.string.action_move_up),
                        background = InkRaised,
                        border = SlateEdge,
                        tint = SteelLight,
                        enabled = canMoveUp,
                        onClick = onMoveUp,
                    )
                    TileButton(
                        icon = Icons.Default.Close,
                        description = stringResource(R.string.action_remove_tile),
                        background = SignalSurface,
                        border = SignalBorder,
                        tint = SignalText,
                        enabled = true,
                        onClick = onRemove,
                    )
                }
            }
        }

        Text(
            text = stringResource(metric.hintRes),
            style = MaterialTheme.typography.labelMedium,
            color = Fog,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = TILE_PADDING.dp, end = TILE_PADDING.dp, top = 5.dp)
                .alpha(dim),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = TILE_PADDING.dp, end = TILE_PADDING.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (value == null) NO_VALUE
                else formatReading(animated.toDouble(), metric.decimals),
                style = TileValueTextStyle,
                color = if (warn) mark else Chalk,
                maxLines = 1,
                modifier = Modifier.alignByBaseline().alpha(dim),
            )
            Text(
                text = metric.unit,
                style = MaterialTheme.typography.labelMedium,
                color = Smoke,
                maxLines = 1,
                modifier = Modifier.alignByBaseline().alpha(dim),
            )
            // Right against the unit, before the verdict: a value the app worked out has to
            // say so where the number is, not only in the paragraph behind a tap. A tile is
            // read in half a second and that is the whole time the distinction has.
            if (value != null) {
                EstimateMark(
                    provenance = provenance,
                    assumption = assumption,
                    // Centred rather than on the baseline: a one-character pill has no
                    // baseline of its own worth aligning a 26 sp number to.
                    modifier = Modifier.align(Alignment.CenterVertically).alpha(dim),
                )
            }
            // The verdict takes whatever the number leaves, so a long one never pushes it
            // off the card — on the narrowest phone it ellipsises instead of disappearing.
            val state = band.statusOf(value)
            if (state != null) {
                Text(
                    text = stringResource(state.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        state.severe -> SignalLight
                        state.breached -> AmberLight
                        else -> Graphite
                    },
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).alignByBaseline().alpha(dim),
                )
            }
        }

        RangeBar(
            samples = samples,
            value = value,
            band = band,
            accent = mark,
            trackColor = SlateTrack,
            bandColor = Moss.copy(alpha = BAND_ALPHA),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = TILE_PADDING.dp, end = TILE_PADDING.dp, top = 9.dp)
                .height(BAR_HEIGHT.dp)
                .alpha(dim),
        )
    }
}

/** The last cell of the grid: the way to put another value on the dashboard. */
@Composable
fun AddTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ADD_TILE_MIN_HEIGHT.dp)
            .clip(PanelCorner)
            .dashedBorder(SlateEdge, PanelRadius)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(GLYPH_BOX.dp)
                .clip(CircleShape)
                .border(1.dp, SlateEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "+", style = MaterialTheme.typography.titleMedium, color = Smoke)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Graphite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TileButton(
    icon: ImageVector,
    description: String,
    background: Color,
    border: Color,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(EDIT_BUTTON.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(EDIT_GLYPH.dp),
        )
    }
}

/**
 * Where the value sits against its normal range, as a strip the width of the tile.
 *
 * The scale is the union of what is normal and what the session has actually seen, padded
 * so neither end is ever hard against the edge — which is what makes a coolant temperature
 * that has moved two degrees legible on the same strip as a load that swung sixty percent.
 * A value with no band published for it still gets the strip: the wash is then simply the
 * session's own range, and the mark says where in it the car is now.
 */
@Composable
fun RangeBar(
    samples: List<Sample>,
    value: Double?,
    band: NormalBand?,
    accent: Color,
    trackColor: Color,
    bandColor: Color,
    modifier: Modifier = Modifier,
) {
    val scale = remember(samples, value, band) { rangeScaleOf(samples, value, band) }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val trackHeight = TRACK_HEIGHT.dp.toPx()
        val trackTop = height * TRACK_TOP
        fun x(v: Double): Float =
            (((v - scale.low) / scale.span).toFloat() * width).coerceIn(0f, width)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, trackTop),
            size = Size(width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )

        // The band is drawn on the track, so "normal" is a place on the ruler rather than
        // a second ruler of its own. An open-ended band runs to the end it does not state.
        if (band != null && !band.isEmpty) {
            val from = x(band.min ?: scale.low)
            val to = x(band.max ?: scale.high)
            if (to - from > 1f) {
                drawRoundRect(
                    color = bandColor,
                    topLeft = Offset(from, trackTop),
                    size = Size(to - from, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )
            }
        }

        if (scale.seen) {
            val rangeHeight = RANGE_HEIGHT.dp.toPx()
            val from = x(scale.seenLow)
            val to = x(scale.seenHigh)
            drawRoundRect(
                color = accent.copy(alpha = RANGE_ALPHA),
                topLeft = Offset(from, height * RANGE_TOP),
                size = Size((to - from).coerceAtLeast(rangeHeight / 2f), rangeHeight),
                cornerRadius = CornerRadius(rangeHeight / 2f),
            )
        }

        // No reading means no mark: an empty strip is honest, a mark parked at zero is not.
        if (value != null && value.isFinite()) {
            val markWidth = MARK_WIDTH.dp.toPx()
            val markEnd = (width - markWidth).coerceAtLeast(0f)
            drawRoundRect(
                color = accent,
                topLeft = Offset((x(value) - markWidth / 2f).coerceIn(0f, markEnd), 0f),
                size = Size(markWidth, height),
                cornerRadius = CornerRadius(markWidth / 2f),
            )
        }
    }
}

/** The ends of a [RangeBar]'s scale, and the part of it the session has visited. */
private data class RangeScale(
    val low: Double,
    val high: Double,
    val seen: Boolean,
    val seenLow: Double,
    val seenHigh: Double,
) {
    val span: Double get() = (high - low).takeIf { it > EPSILON } ?: 1.0
}

private fun rangeScaleOf(samples: List<Sample>, value: Double?, band: NormalBand?): RangeScale {
    val values = samples.map { it.value.toDouble() }.filter(Double::isFinite)
    val current = value?.takeIf(Double::isFinite)
    val seenLow = values.minOrNull() ?: current
    val seenHigh = values.maxOrNull() ?: current
    val ends = listOfNotNull(seenLow, seenHigh, current, band?.min, band?.max)
    if (ends.isEmpty()) return RangeScale(0.0, 1.0, seen = false, seenLow = 0.0, seenHigh = 1.0)

    val low = ends.min()
    val high = ends.max()
    // A value that has not moved has no range of its own, so the padding comes off the
    // value itself and the mark lands in the middle instead of on an edge.
    val pad = ((high - low).takeIf { it > EPSILON } ?: abs(low).takeIf { it > EPSILON } ?: 1.0) *
        SCALE_PADDING
    return RangeScale(
        low = low - pad,
        high = high + pad,
        seen = seenLow != null && seenHigh != null,
        seenLow = seenLow ?: low,
        seenHigh = seenHigh ?: high,
    )
}

/**
 * The colour a reading's *state* is drawn in, wherever that state is shown.
 *
 * Steel while everything is where it should be, amber once a value is outside its band,
 * and the signal red only when it is far enough outside to be worth stopping for. The
 * metric's own family colour is not in here on purpose: it tints the glyph and nothing
 * else, so a card that has gone amber cannot be mistaken for a card that is simply an
 * amber-coloured family.
 */
@Composable
@ReadOnlyComposable
fun statusColor(status: MetricStatus): Color = when {
    status.severe -> SignalText
    status.breached -> AmberText
    else -> Steel
}

/** The card a reading of this state sits on, and its edge. */
@Composable
@ReadOnlyComposable
private fun statusSurface(status: MetricStatus): Pair<Color, Color> = when {
    status.severe -> SignalSurface to SignalBorder
    status.breached -> AmberSurface to AmberBorder
    else -> Slate to SlateBorder
}

/**
 * The colour a value carries wherever it appears.
 *
 * By family rather than by position: temperatures that matter are amber, the ones that
 * only inform are steel, electrics violet, anything measured against fuel or air green,
 * the trims rose. Six tiles picked at random then still look like a set, which is what a
 * palette indexed by "third thing in the list" could never promise.
 */
@Composable
@ReadOnlyComposable
fun metricAccent(id: MetricId): Color {
    val series = SeriesColors
    return when (id) {
        Metrics.OilTemp, Metrics.FuelPer100Km, FUEL_RATE -> series[AMBER]
        Metrics.IntakeAirTemp, Metrics.Maf -> series[MOSS]
        Metrics.Battery, Metrics.Timing, MODULE_VOLTAGE -> series[VIOLET]
        Metrics.ShortTrim, Metrics.LongTrim, Metrics.Boost -> series[ROSE]
        Metrics.Speed -> series[GREY]
        else -> when (Metrics.groupOf(id)) {
            MetricGroup.Mixture -> series[MOSS]
            MetricGroup.Vehicle -> series[GREY]
            else -> series[STEEL]
        }
    }
}

/**
 * One glyph per family of readings.
 *
 * Deliberately three drawings and a fallback rather than forty: at seventeen points the
 * icon cannot say *which* temperature this is, only that it is one, and the name directly
 * beside it already says the rest.
 */
fun metricGlyph(id: MetricId): ImageVector = when {
    id == Metrics.Battery || id == MODULE_VOLTAGE -> AppIcons.Battery
    id == FUEL_RATE || id == Metrics.FuelPer100Km -> AppIcons.Droplet
    id is MetricId.Sensor && id.pid in FLUID_PIDS -> AppIcons.Droplet
    Metrics.groupOf(id) == MetricGroup.Temperature -> AppIcons.Thermometer
    Metrics.groupOf(id) == MetricGroup.Mixture -> AppIcons.Droplet
    else -> AppIcons.Gauge
}

private val FUEL_RATE = MetricId.Derived(DerivedMetrics.FuelRate.key)
private val MODULE_VOLTAGE = MetricId.Sensor(Pids.CONTROL_MODULE_VOLTAGE)

/** Pressures and levels that are a fluid rather than a mixture. */
private val FLUID_PIDS = setOf(0x0A, 0x22, 0x23, 0x59, Pids.FUEL_LEVEL, Pids.FUEL_RATE)

/** Which entry of the series palette a family takes. */
private const val STEEL = 0
private const val AMBER = 1
private const val GREY = 2
private const val MOSS = 3
private const val VIOLET = 4
private const val ROSE = 5

/**
 * The tile's number, the largest thing on the card.
 *
 * Same face and same tabular figures as every other live value, so a column of tiles does
 * not shuffle sideways each time a 1 becomes a 7.
 */
private val TileValueTextStyle = NumberTextStyle.copy(
    fontSize = 26.sp,
    letterSpacing = (-0.5).sp,
)

private const val TILE_PADDING = 11
private const val GLYPH_BOX = 26
private const val GLYPH = 17
private const val GLYPH_FILL = 0.12f
private const val GLYPH_EDGE = 0.22f
private const val EDIT_BUTTON = 24
private const val EDIT_GLYPH = 15
private const val ADD_TILE_MIN_HEIGHT = 74

/** The strip along the foot of a tile, and the parts of it, in points. */
private const val BAR_HEIGHT = 14
private const val TRACK_HEIGHT = 2f
private const val TRACK_TOP = 6f / 14f
private const val RANGE_HEIGHT = 4f
private const val RANGE_TOP = 5f / 14f
private const val MARK_WIDTH = 2.5f
private const val RANGE_ALPHA = 0.28f
private const val BAND_ALPHA = 0.5f

/** How much air is left at each end of the scale, as a fraction of what it spans. */
private const val SCALE_PADDING = 0.14
private const val EPSILON = 1e-6

private const val DISABLED_ALPHA = 0.35f
private const val VALUE_ANIMATION_MILLIS = 320
private const val DIM_ANIMATION_MILLIS = 400
private const val STALE_ALPHA = 0.38f
