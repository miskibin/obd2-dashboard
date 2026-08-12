package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.miskibin.obd2dashboard.ui.theme.NumberTextStyle
import com.miskibin.obd2dashboard.ui.theme.Signal
import com.miskibin.obd2dashboard.ui.theme.Slate
import com.miskibin.obd2dashboard.ui.theme.SlateBorder
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.Smoke
import com.miskibin.obd2dashboard.ui.theme.Steel
import com.miskibin.obd2dashboard.ui.theme.SteelDeep
import com.miskibin.obd2dashboard.ui.theme.SteelLight
import kotlin.math.roundToInt

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
 * Road speed is the headline, because it is the number a hand on the wheel looks for and
 * the one a phone in a windscreen mount is worst at showing small. Revs are stated under
 * it as a figure and drawn beside it as a scale: the tick strip is the part that works in
 * peripheral vision — the lit length answers "how close to the limiter?" before any digit
 * has been read — and the gear chips turn the estimate into something scannable without
 * reading a number at all.
 *
 * Everything here is a flat fill. An earlier version washed the accent down from the top
 * edge and graded the rev bar towards its head; both were decoration on the one card whose
 * job is to be read in half a second, and neither survived the redesign.
 */
@Composable
fun HeroCard(state: HeroState, modifier: Modifier = Modifier) {
    val animatedRpm by animateFloatAsState(
        targetValue = state.rpm?.toFloat()?.takeIf { it.isFinite() } ?: 0f,
        animationSpec = tween(durationMillis = VALUE_ANIMATION_MILLIS),
        label = "hero-rpm",
    )
    val animatedSpeed by animateFloatAsState(
        targetValue = state.speed?.toFloat()?.takeIf { it.isFinite() } ?: 0f,
        animationSpec = tween(durationMillis = VALUE_ANIMATION_MILLIS),
        label = "hero-speed",
    )
    val dim by animateFloatAsState(
        targetValue = if (state.stale || state.rpm == null) STALE_ALPHA else 1f,
        animationSpec = tween(durationMillis = DIM_ANIMATION_MILLIS),
        label = "hero-dim",
    )
    val redline = state.redline.coerceAtLeast(1)
    val fraction = (animatedRpm / redline).coerceIn(0f, 1f)
    val past = fraction >= REDLINE_WARNING_FRACTION

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardCorner)
            .background(Slate)
            .border(1.dp, SlateBorder, CardCorner)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
            .alpha(dim),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                CardLabel(stringResource(R.string.dashboard_speed_label))
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(top = 3.dp),
                ) {
                    Text(
                        text = if (state.speed == null) NO_VALUE
                        else formatReading(animatedSpeed.toDouble(), 0),
                        style = SpeedTextStyle,
                        color = Chalk,
                        maxLines = 1,
                    )
                    Text(
                        text = state.speedUnit,
                        style = MaterialTheme.typography.labelMedium,
                        color = Smoke,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CardLabel(stringResource(R.string.dashboard_gear_label))
                GearStrip(gear = state.gear)
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (state.rpm == null) NO_VALUE
                        else formatReading(animatedRpm.toDouble(), 0),
                        style = RpmTextStyle,
                        color = if (past) Signal else Chalk,
                        maxLines = 1,
                    )
                    Text(
                        text = stringResource(R.string.unit_rpm),
                        style = MaterialTheme.typography.labelMedium,
                        color = Smoke,
                    )
                }
                Text(
                    text = state.sessionMaxRpm?.let {
                        stringResource(R.string.dashboard_session_max, formatReading(it, 0))
                    } ?: stringResource(R.string.dashboard_redline, state.redline),
                    style = MaterialTheme.typography.labelMedium,
                    color = Graphite,
                    maxLines = 1,
                )
            }

            RpmScale(
                rpm = animatedRpm,
                redline = redline,
                litColor = Steel,
                idleColor = SlateEdge,
                warnColor = Signal,
                headColor = if (past) Signal else SteelLight,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(SCALE_HEIGHT.dp),
            )

            ScaleLabels(
                redline = redline,
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            )
        }
    }
}

/** The small letter-spaced caption over a value: what the number under it is. */
@Composable
private fun CardLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = LABEL_TRACKING.sp),
        color = Fog,
        maxLines = 1,
    )
}

/**
 * One chip per gear, the estimated one lit — the strip a cluster would draw.
 *
 * This is the only place the gear is stated. A "D 3" badge next to the speed said the same
 * thing in words a foot above it, and of the two this is the one that can be read without
 * looking away from the road: there is no selector-position PID on generic OBD2, so
 * nothing is lit until speed ÷ revs means something, which is exactly what an unlit strip
 * should say.
 */
@Composable
private fun GearStrip(gear: GearReading, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..GearEstimator.MAX_GEARS).forEach { number ->
            val on = gear.gear == number
            Box(
                modifier = Modifier
                    .size(GEAR_CHIP.dp)
                    .clip(CircleShape)
                    .background(if (on) SteelDeep else InkRaised),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) SteelLight else Graphite,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The rev counter as a ruler rather than as a bar.
 *
 * A filled bar answers "how far along?" with an edge that has to be found; a strip of
 * ticks answers it with a length that is already countable, and it carries the scale it is
 * read against — every thousand is a tall mark, every two hundred and fifty a short one.
 * The ticks past the warning fraction are drawn in the warning colour whether or not the
 * engine has reached them, so where the red starts is legible at idle.
 */
@Composable
private fun RpmScale(
    rpm: Float,
    redline: Int,
    litColor: Color,
    idleColor: Color,
    warnColor: Color,
    headColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val tickWidth = TICK_WIDTH.dp.toPx()
        val warnFrom = redline * REDLINE_WARNING_FRACTION
        var value = 0
        while (value <= redline) {
            val major = value % MAJOR_STEP == 0
            val lit = value <= rpm
            val warn = value >= warnFrom
            val top = if (major) height * MAJOR_TOP else height * MINOR_TOP
            drawRect(
                color = when {
                    warn && lit -> warnColor
                    warn -> warnColor.copy(alpha = IDLE_WARN_ALPHA)
                    lit -> litColor
                    else -> idleColor
                },
                topLeft = Offset(
                    x = (value.toFloat() / redline * width).coerceAtMost(width - tickWidth),
                    y = top,
                ),
                size = Size(tickWidth, height - top),
            )
            value += TICK_STEP
        }

        // The head, so the current value is findable on a strip that is mostly scale.
        val headWidth = HEAD_WIDTH.dp.toPx()
        val headEnd = (width - headWidth).coerceAtLeast(0f)
        drawRoundRect(
            color = headColor,
            topLeft = Offset(
                x = (rpm / redline * width - headWidth / 2f).coerceIn(0f, headEnd),
                y = 0f,
            ),
            size = Size(headWidth, height),
            cornerRadius = CornerRadius(headWidth / 2f),
        )
    }
}

/**
 * The scale in thousands, each label centred on the tick it names.
 *
 * Spreading them evenly would be a lie on any redline that is not a round multiple of two
 * thousand — on a 5 500 engine the "4" would sit at the right-hand edge, a thousand revs
 * from where four thousand actually is.
 */
@Composable
private fun ScaleLabels(redline: Int, modifier: Modifier = Modifier) {
    val values = remember(redline) {
        generateSequence(0) { it + LABEL_STEP }.takeWhile { it <= redline }.toList()
    }
    Layout(
        content = {
            values.forEach { value ->
                Text(
                    text = (value / 1_000).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Graphite,
                    maxLines = 1,
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val centre = width * (values[index].toFloat() / redline)
                val x = (centre - placeable.width / 2f).roundToInt()
                placeable.place(x = x.coerceIn(0, (width - placeable.width).coerceAtLeast(0)), y = 0)
            }
        }
    }
}

/** Where the scale turns red, as a fraction of the configured redline. */
const val REDLINE_WARNING_FRACTION = 0.92f

/** How long a reading stays trusted before the dashboard dims it. */
const val STALE_AFTER_MILLIS = 3_000L

/** The headline number: as large as a phone in a mount can carry without wrapping. */
private val SpeedTextStyle = NumberTextStyle.copy(
    fontSize = 64.sp,
    lineHeight = 60.sp,
    letterSpacing = (-2.6).sp,
)

/** Revs, a step down: stated, not shouted, because the strip under it does the shouting. */
private val RpmTextStyle = NumberTextStyle.copy(
    fontSize = 24.sp,
    letterSpacing = (-0.5).sp,
)

private const val LABEL_TRACKING = 1.5f
private const val GEAR_CHIP = 21
private const val SCALE_HEIGHT = 26

/** One tick every 250 rpm, a tall one every 1 000, a printed label every 2 000. */
private const val TICK_STEP = 250
private const val MAJOR_STEP = 1_000
private const val LABEL_STEP = 2_000

private const val TICK_WIDTH = 1.5f
private const val HEAD_WIDTH = 3f
private const val MAJOR_TOP = 0.115f
private const val MINOR_TOP = 0.385f
private const val IDLE_WARN_ALPHA = 0.3f

private const val VALUE_ANIMATION_MILLIS = 320
private const val DIM_ANIMATION_MILLIS = 400
private const val STALE_ALPHA = 0.38f
