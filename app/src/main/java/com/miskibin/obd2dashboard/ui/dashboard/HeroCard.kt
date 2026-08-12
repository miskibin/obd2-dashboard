package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.GearEstimator
import com.miskibin.obd2dashboard.data.GearReading
import com.miskibin.obd2dashboard.ui.components.NO_VALUE
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.theme.CardCorner
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Fog
import com.miskibin.obd2dashboard.ui.theme.Graphite
import com.miskibin.obd2dashboard.ui.theme.InkRaised
import com.miskibin.obd2dashboard.ui.theme.Signal
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.SmokeDim
import com.miskibin.obd2dashboard.ui.theme.Steel
import com.miskibin.obd2dashboard.ui.theme.SteelDeep
import com.miskibin.obd2dashboard.ui.theme.SteelLight

/** What the hero card is told about the car, already converted into display units. */
data class HeroState(
    val rpm: Double?,
    val redline: Int,
    val sessionMaxRpm: Double?,
    val speed: Double?,
    val speedUnit: String,
    val gear: GearReading,
    val stale: Boolean,
)

/**
 * The card the driver reads at a glance, and the only one that is allowed to be loud.
 *
 * Revs, road speed and the engaged gear are the three things a hand on the wheel looks
 * for, so they share one card rather than three tiles: the eye lands once. The bar under
 * the number is the part that works in peripheral vision — its length answers "how close
 * to the limiter?" before any digit has been read — and the gear strip below it turns the
 * estimate into something scannable without reading a number at all.
 */
@Composable
fun HeroCard(state: HeroState, modifier: Modifier = Modifier) {
    val animatedRpm by animateFloatAsState(
        targetValue = state.rpm?.toFloat()?.takeIf { it.isFinite() } ?: 0f,
        animationSpec = tween(durationMillis = VALUE_ANIMATION_MILLIS),
        label = "hero-rpm",
    )
    val dim by animateFloatAsState(
        targetValue = if (state.stale || state.rpm == null) STALE_ALPHA else 1f,
        animationSpec = tween(durationMillis = DIM_ANIMATION_MILLIS),
        label = "hero-dim",
    )
    val fraction = (animatedRpm / state.redline.coerceAtLeast(1)).coerceIn(0f, 1f)
    val past = fraction >= REDLINE_WARNING_FRACTION
    val barColor by animateColorAsState(
        targetValue = if (past) Signal else Steel,
        animationSpec = tween(durationMillis = DIM_ANIMATION_MILLIS),
        label = "hero-bar",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardCorner)
            .background(Slate)
            // A wash of the accent down from the top edge, at an alpha low enough that it
            // is never seen as a colour — only as the one card on the screen that is lit
            // from somewhere. It is the whole difference between the hero reading as the
            // headline and reading as the first row of the list, and it costs no ink.
            .background(
                Brush.verticalGradient(
                    colors = listOf(barColor.copy(alpha = HERO_WASH_ALPHA), Color.Transparent),
                    endY = HERO_WASH_HEIGHT,
                ),
            )
            .border(1.dp, SlateBorder, CardCorner)
            .padding(start = 15.dp, end = 15.dp, top = 13.dp, bottom = 11.dp)
            .alpha(dim),
    ) {
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
                        text = if (state.rpm == null) NO_VALUE else formatReading(animatedRpm.toDouble(), 0),
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
                    text = state.sessionMaxRpm?.let {
                        stringResource(R.string.dashboard_session_max, formatReading(it, 0))
                    } ?: stringResource(R.string.dashboard_redline, state.redline),
                    style = MaterialTheme.typography.labelMedium,
                    color = SmokeDim,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = formatReading(state.speed, 0),
                        style = MaterialTheme.typography.displayMedium,
                        color = Chalk,
                        maxLines = 1,
                    )
                    Text(
                        text = state.speedUnit,
                        style = MaterialTheme.typography.labelMedium,
                        color = Smoke,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }

        RedlineBar(
            fraction = fraction,
            color = barColor,
            modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("0", style = MaterialTheme.typography.labelMedium, color = Fog)
            Text(
                text = (state.redline / 2).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = Fog,
            )
            Text(
                text = state.redline.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = Signal,
            )
        }

        GearStrip(
            gear = state.gear,
            modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
        )
    }
}

/**
 * One cell per gear, the estimated one lit — the strip a cluster would draw.
 *
 * This is the only place the gear is stated. A "D 3" badge next to the speed said the same
 * thing in words a foot above it, and of the two this is the one that can be read without
 * looking away from the road: there is no selector-position PID on generic OBD2, so
 * nothing is lit until speed ÷ revs means something, which is exactly what an unlit strip
 * should say.
 */
@Composable
private fun GearStrip(gear: GearReading, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        (1..GearEstimator.MAX_GEARS).forEach { number ->
            val on = gear.gear == number
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = if (on) SteelLight else Graphite,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) SteelDeep else InkRaised)
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun RedlineBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(SlateBorder),
    ) {
        // The fill deepens towards its own head rather than being one flat block, so the
        // bar reads as a level rising out of the track instead of a progress bar.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(
                    Brush.horizontalGradient(
                        listOf(color.copy(alpha = FILL_TAIL_ALPHA), color),
                    ),
                ),
        )
        // The marker sits where the driver should already be lifting, not at the very end
        // of the bar where the fill that reached it would hide it.
        Box(
            modifier = Modifier
                .fillMaxWidth(REDLINE_MARK_FRACTION)
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

/** Where the bar turns red, as a fraction of the configured redline. */
const val REDLINE_WARNING_FRACTION = 0.92f

/** Where the tick is drawn on the bar. */
private const val REDLINE_MARK_FRACTION = 0.96f

/** How far down the card the wash reaches, in pixels — about the height of the number. */
private const val HERO_WASH_HEIGHT = 300f
private const val HERO_WASH_ALPHA = 0.09f
private const val FILL_TAIL_ALPHA = 0.55f

private const val VALUE_ANIMATION_MILLIS = 320
private const val DIM_ANIMATION_MILLIS = 400
private const val STALE_ALPHA = 0.38f

/** How long a reading stays trusted before the dashboard dims it. */
const val STALE_AFTER_MILLIS = 3_000L
