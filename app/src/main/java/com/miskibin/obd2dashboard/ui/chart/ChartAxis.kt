package com.miskibin.obd2dashboard.ui.chart

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** A computed Y axis: the range actually drawn plus the values to label. */
data class AxisTicks(
    val min: Double,
    val max: Double,
    val step: Double,
    val values: List<Double>,
) {
    val span: Double get() = max - min

    /** Where [value] sits in the axis, 0 at [min] and 1 at [max]. */
    fun fraction(value: Double): Double = if (span == 0.0) 0.5 else (value - min) / span
}

/**
 * "Nice number" axis scaling.
 *
 * Chart labels are only readable if the gaps between them are numbers a human counts in,
 * so the step is always 1, 2 or 5 times a power of ten and the range is widened to whole
 * steps. Degenerate inputs — no data, one point, a value that never changes — are padded
 * into a real range instead of collapsing the chart onto a single line at the bottom.
 */
object ChartAxis {

    const val DEFAULT_TICK_COUNT = 4

    fun ticks(min: Double, max: Double, targetCount: Int = DEFAULT_TICK_COUNT): AxisTicks {
        val count = max(1, targetCount)
        val (low, high) = padded(min, max)
        val step = niceStep((high - low) / count)
        val niceMin = floor(low / step) * step
        val niceMax = ceil(high / step) * step
        val values = buildList {
            var value = niceMin
            // Compare against a half-step slack so floating point drift cannot drop the
            // final tick.
            while (value <= niceMax + step / 2) {
                add(roundToStep(value, step))
                value += step
            }
        }
        return AxisTicks(niceMin, niceMax, step, values)
    }

    fun ticksOf(values: List<Float>, targetCount: Int = DEFAULT_TICK_COUNT): AxisTicks {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return ticks(0.0, 1.0, targetCount)
        return ticks(finite.min().toDouble(), finite.max().toDouble(), targetCount)
    }

    /** Rounds a raw step up to the next 1, 2 or 5 times a power of ten. */
    fun niceStep(rawStep: Double): Double {
        if (!rawStep.isFinite() || rawStep <= 0.0) return 1.0
        val exponent = floor(log10(rawStep))
        val magnitude = 10.0.pow(exponent)
        val normalised = rawStep / magnitude
        val nice = when {
            normalised <= 1.0 -> 1.0
            normalised <= 2.0 -> 2.0
            normalised <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * magnitude
    }

    /** How many decimals a label needs so neighbouring ticks stay distinguishable. */
    fun labelDecimals(step: Double): Int {
        if (!step.isFinite() || step <= 0.0) return 0
        if (step >= 1.0) return 0
        return ceil(-log10(step)).toInt().coerceIn(0, MAX_DECIMALS)
    }

    fun formatLabel(value: Double, step: Double): String {
        val decimals = labelDecimals(step)
        val rounded = roundToStep(value, step)
        val text = "%.${decimals}f".format(Locale.getDefault(), rounded)
        return if (text == negativeZero(decimals)) text.removePrefix("-") else text
    }

    /**
     * Widens a degenerate range into something drawable: no data becomes 0..1, and a
     * constant value gets symmetric padding so its line lands in the middle of the plot.
     */
    private fun padded(min: Double, max: Double): Pair<Double, Double> {
        if (!min.isFinite() || !max.isFinite()) return 0.0 to 1.0
        val low = minOf(min, max)
        val high = maxOf(min, max)
        if (high > low) return low to high
        val pad = if (low == 0.0) 1.0 else abs(low) * CONSTANT_PADDING_FRACTION
        return low - pad to high + pad
    }

    private fun roundToStep(value: Double, step: Double): Double {
        if (!step.isFinite() || step <= 0.0) return value
        val snapped = Math.round(value / step) * step
        return if (abs(snapped) < step * ZERO_EPSILON) 0.0 else snapped
    }

    private fun negativeZero(decimals: Int): String = "%.${decimals}f".format(Locale.getDefault(), -0.0)

    private const val CONSTANT_PADDING_FRACTION = 0.1
    private const val ZERO_EPSILON = 1e-9
    private const val MAX_DECIMALS = 3
}
