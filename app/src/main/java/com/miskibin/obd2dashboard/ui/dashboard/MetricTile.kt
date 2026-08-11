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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.Sample
import com.miskibin.obd2dashboard.ui.components.ArcGauge
import com.miskibin.obd2dashboard.ui.components.NO_VALUE
import com.miskibin.obd2dashboard.ui.components.Sparkline
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.theme.ReadoutTextStyle

/** How long a reading stays trusted before the tile dims it. */
const val STALE_AFTER_MILLIS = 3_000L

/**
 * One live value.
 *
 * The number is the tile: label and unit are deliberately small, the last thirty seconds
 * sit underneath as a shape rather than a chart, and a reading that stops arriving fades
 * instead of silently lying.
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
    val fullScale = Metrics.fullScaleOf(metric.id)
    val text = if (value == null) NO_VALUE else formatReading(animatedValue.toDouble(), metric.decimals)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        if (fullScale != null) {
            ArcGauge(
                fraction = animatedValue / fullScale,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                warningColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(dim * ARC_ALPHA),
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(metric.nameRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(dim),
            )

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.alpha(dim),
            ) {
                Text(
                    text = text,
                    style = ReadoutTextStyle.copy(fontSize = fontSizeFor(text.length)),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = metric.unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            if (fullScale == null) {
                Sparkline(
                    samples = samples,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SPARKLINE_HEIGHT.dp)
                        .alpha(dim),
                )
            } else {
                Box(Modifier.height(SPARKLINE_HEIGHT.dp))
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
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_remove_tile),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The last cell of the grid: the only way tiles get added, so it is never hidden. */
@Composable
fun AddTileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(20.dp),
            )
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
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = stringResource(R.string.action_add_tile),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Keeps long readings on one line without shrinking the common short ones. */
private fun fontSizeFor(length: Int) = when {
    length <= 3 -> 40.sp
    length == 4 -> 36.sp
    length == 5 -> 32.sp
    length == 6 -> 28.sp
    else -> 24.sp
}

private const val VALUE_ANIMATION_MILLIS = 320
private const val DIM_ANIMATION_MILLIS = 400
private const val STALE_ALPHA = 0.38f
private const val ARC_ALPHA = 0.55f
private const val SPARKLINE_HEIGHT = 30
