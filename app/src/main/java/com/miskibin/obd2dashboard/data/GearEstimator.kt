package com.miskibin.obd2dashboard.data

import kotlin.math.abs

/**
 * Which gear the car is probably in.
 *
 * Generic OBD2 has no selector-position PID — an ELM327 cannot be asked "P, R, N or D?"
 * and no amount of polling will make it answerable. What *is* on the bus is road speed
 * and engine speed, and their quotient is the overall ratio of whatever gear is engaged.
 * So the gear is inferred, never claimed: [GearReading.gear] is null whenever the car is
 * too slow for the quotient to mean anything, and the UI says the number is an estimate.
 */
data class GearReading(
    /** True once the car is moving fast enough for the ratio to be meaningful. */
    val moving: Boolean,
    /** 1-based gear, or null when it cannot be told. */
    val gear: Int?,
    /** km/h per 1 000 rpm, the quantity the estimate is made from. */
    val ratio: Double?,
)

/**
 * Estimates the engaged gear from the speed-to-revs ratio, learning the car as it goes.
 *
 * A cold start has nothing to go on but physics, so the first estimate comes from a prior
 * table of ratios that hold for almost every road car — around 8 km/h per 1 000 rpm in
 * first, around 46 in sixth. From then on each steady ratio the car actually shows is
 * clustered, and a cluster keeps the gear number the prior gave it when it was created.
 * That is what makes the readout stable: once this car's third gear has been seen, every
 * later ratio near it reads as third even if the prior would have called it borderline.
 */
class GearEstimator(private val maxGears: Int = MAX_GEARS) {

    private val clusters = mutableListOf<Cluster>()

    private data class Cluster(var ratio: Double, val gear: Int, var samples: Int)

    fun observe(rpm: Double?, speed: Double?): GearReading {
        if (rpm == null || speed == null) return STOPPED
        if (!rpm.isFinite() || !speed.isFinite()) return STOPPED
        if (speed < MIN_SPEED_KMH || rpm < MIN_RPM) return STOPPED

        val ratio = speed / rpm * 1_000.0
        if (ratio <= 0.0 || ratio > MAX_RATIO) return GearReading(moving = true, gear = null, ratio = ratio)

        val nearest = clusters.minByOrNull { abs(it.ratio - ratio) }
        if (nearest != null && abs(nearest.ratio - ratio) <= nearest.ratio * TOLERANCE) {
            // A running mean, damped so one bad sample during a shift cannot drag the
            // cluster onto the neighbouring gear.
            nearest.samples = (nearest.samples + 1).coerceAtMost(MAX_SAMPLES)
            nearest.ratio += (ratio - nearest.ratio) / nearest.samples
            return GearReading(moving = true, gear = nearest.gear, ratio = ratio)
        }

        val gear = priorGearFor(ratio)
        // One cluster per gear: a second ratio that the prior calls third replaces the
        // remembered third rather than sitting beside it.
        clusters.removeAll { it.gear == gear }
        clusters += Cluster(ratio, gear, samples = 1)
        return GearReading(moving = true, gear = gear, ratio = ratio)
    }

    fun reset() = clusters.clear()

    private fun priorGearFor(ratio: Double): Int {
        val index = PRIOR_BOUNDARIES.indexOfFirst { ratio < it }
        val gear = if (index < 0) PRIOR_BOUNDARIES.size + 1 else index + 1
        return gear.coerceIn(1, maxGears)
    }

    companion object {
        /** How many cells the gear strip draws, and the highest gear ever reported. */
        const val MAX_GEARS = 6

        /** Below this the quotient is dominated by the clutch or the torque converter. */
        const val MIN_SPEED_KMH = 5.0

        private const val MIN_RPM = 500.0

        /** Nothing on a road car reaches this; above it the reading is a decode error. */
        private const val MAX_RATIO = 90.0

        /** How far a ratio can sit from a learned cluster and still be that gear. */
        private const val TOLERANCE = 0.08

        private const val MAX_SAMPLES = 40

        /**
         * Midpoints between typical overall ratios (km/h per 1 000 rpm) for gears one to
         * six. Wide enough to be wrong only on a car with unusual gearing, and the
         * learned clusters correct that within a few shifts.
         */
        private val PRIOR_BOUNDARIES = listOf(11.0, 17.5, 25.0, 33.0, 41.5, 50.5)

        private val STOPPED = GearReading(moving = false, gear = null, ratio = null)
    }
}
