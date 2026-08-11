package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.Sample
import com.miskibin.obd2dashboard.ui.components.NO_VALUE
import com.miskibin.obd2dashboard.ui.components.Sparkline
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.theme.Amber
import com.miskibin.obd2dashboard.ui.theme.AmberBorder
import com.miskibin.obd2dashboard.ui.theme.AmberSurface
import com.miskibin.obd2dashboard.ui.theme.AmberText
import com.miskibin.obd2dashboard.ui.theme.CardCorner
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.ReadoutTextStyle
import com.miskibin.obd2dashboard.ui.theme.Signal
import com.miskibin.obd2dashboard.ui.theme.SignalBorder
import com.miskibin.obd2dashboard.ui.theme.SignalSurface
import com.miskibin.obd2dashboard.ui.theme.SignalText
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.SlateTrack
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SmokeDim
import com.miskibin.obd2dashboard.ui.theme.Steel

/** How long a reading stays trusted before the tile dims it. */
const val STALE_AFTER_MILLIS = 3_000L

/**
 * One live value.
 *
 * The number is the tile: label and unit are deliberately small, the last thirty seconds
 * sit underneath as a shape rather than a chart, and a reading that stops arriving fades
 * instead of silently lying. The accent rule down the left edge is the only colour, and
 * it turns red when the value is past its limit.
 */
@Composable
fun MetricTile(
    metric: Metric,
    value: Double?,
    stale: Boolean,
    samples: List<Sample>,
    editing: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
) {
    val animatedValue by animateFloatAsState(
        targetValue = value?.toFloat()?.takeIf { it.isFinite() } ?: 0f,
        animationSpec = tween(durationMillis = VALUE_ANIMATION_MILLIS),
        label = "tile-value",
    )
    val dim by animateFloatAsState(
        targetValue = if (stale || value == null) STALE_ALPHA else 1f,
        animationSpec = tween(durationMillis = DIM_ANIMATION_MILLIS),
        label = "tile-dim",
    )
    val text = if (value == null) NO_VALUE else formatReading(animatedValue.toDouble(), metric.decimals)
    val accent = if (warn) Amber else Steel

    Box(
        modifier = modifier
            .clip(CardCorner)
            .background(if (warn) AmberSurface else Slate)
            .border(1.dp, if (warn) AmberBorder else SlateBorder, CardCorner)
            .padding(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent.copy(alpha = dim)),
            )
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(metric.nameRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (warn) AmberText else Smoke,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(dim),
                )

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.alpha(dim),
                ) {
                    Text(
                        text = text,
                        style = ReadoutTextStyle.copy(fontSize = fontSizeFor(text.length)),
                        color = if (warn) AmberText else Chalk,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = metric.unit,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (warn) AmberText else Smoke,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }

                Sparkline(
                    samples = samples,
                    color = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SPARKLINE_HEIGHT.dp)
                        .alpha(dim),
                )
            }
        }

        AnimatedVisibility(
            visible = editing,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SignalSurface)
                    .border(1.dp, SignalBorder, RoundedCornerShape(10.dp))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_remove_tile),
                    tint = SignalText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The engine-speed tile, drawn full width.
 *
 * Revs are the one number a driver reads at a glance without looking down, so this one
 * gets the whole width, the largest type in the app, and a bar with the redline marked
 * on it — the shape of the bar answers "how close am I?" before the digits are read at
 * all.
 */
@Composable
fun RpmHeroTile(
    value: Double?,
    redline: Int,
    stale: Boolean,
    secondary: HeroSecondary?,
    editing: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedValue by animateFloatAsState(
        targetValue = value?.toFloat()?.takeIf { it.isFinite() } ?: 0f,
        animationSpec = tween(durationMillis = VALUE_ANIMATION_MILLIS),
        label = "hero-value",
    )
    val dim by animateFloatAsState(
        targetValue = if (stale || value == null) STALE_ALPHA else 1f,
        animationSpec = tween(durationMillis = DIM_ANIMATION_MILLIS),
        label = "hero-dim",
    )
    val fraction = (animatedValue / redline.coerceAtLeast(1)).coerceIn(0f, 1f)
    val past = fraction >= REDLINE_WARNING_FRACTION
    val barColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (past) Signal else Steel,
        animationSpec = tween(durationMillis = DIM_ANIMATION_MILLIS),
        label = "hero-bar",
    )

    Box(
        modifier = modifier
            .clip(CardCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, CardCorner)
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().alpha(dim)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (value == null) NO_VALUE else formatReading(animatedValue.toDouble(), 0),
                            style = MaterialTheme.typography.displayLarge,
                            color = if (past) Signal else Chalk,
                            maxLines = 1,
                        )
                        Text(
                            text = stringResource(R.string.unit_rpm),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Smoke,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.dashboard_redline, redline),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                        color = SmokeDim,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                if (secondary != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                text = secondary.value,
                                style = MaterialTheme.typography.displayMedium,
                                color = Chalk,
                                maxLines = 1,
                            )
                            Text(
                                text = secondary.unit,
                                style = MaterialTheme.typography.labelMedium,
                                color = Smoke,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        Text(
                            text = secondary.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = SmokeDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 5.dp).widthIn(max = 130.dp),
                        )
                    }
                }
            }

            RedlineBar(
                fraction = fraction,
                color = barColor,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("0", style = MaterialTheme.typography.labelMedium, color = Fog)
                Text(
                    text = (redline / 2).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Fog,
                )
                Text(
                    text = redline.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Signal,
                )
            }
        }

        AnimatedVisibility(
            visible = editing,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SignalSurface)
                    .border(1.dp, SignalBorder, RoundedCornerShape(10.dp))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_remove_tile),
                    tint = SignalText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** The smaller number shown beside the revs, when the driver has that tile too. */
data class HeroSecondary(val value: String, val unit: String, val label: String)

@Composable
private fun RedlineBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(9.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(SlateTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(color),
        )
        // The marker sits where the driver should already be lifting, not at the very
        // end of the bar where the fill that reached it would hide it.
        Box(
            modifier = Modifier
                .fillMaxWidth(REDLINE_WARNING_FRACTION)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Signal.copy(alpha = 0.55f)),
            )
        }
    }
}

/** The last cell of the grid: the only way tiles get added, so it is never hidden. */
@Composable
fun AddTileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CardCorner)
            .border(1.dp, SlateEdge, CardCorner)
            .background(Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = Steel,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(R.string.action_add_tile),
                style = MaterialTheme.typography.labelMedium,
                color = Smoke,
            )
        }
    }
}

/** Keeps long readings on one line without shrinking the common short ones. */
private fun fontSizeFor(length: Int) = when {
    length <= 3 -> 38.sp
    length == 4 -> 34.sp
    length == 5 -> 30.sp
    length == 6 -> 26.sp
    else -> 22.sp
}

/** Where the bar turns red, as a fraction of the configured redline. */
const val REDLINE_WARNING_FRACTION = 0.92f

private const val VALUE_ANIMATION_MILLIS = 320
private const val DIM_ANIMATION_MILLIS = 400
private const val STALE_ALPHA = 0.38f
private const val SPARKLINE_HEIGHT = 26
