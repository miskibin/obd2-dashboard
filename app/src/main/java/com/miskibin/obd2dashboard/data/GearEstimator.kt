package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.ExtendedPids
import com.miskibin.obd2dashboard.obd.Provenance
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import kotlin.math.abs

/**
 * Which gear the car is in, and how well the app knows it.
 *
 * Two sources, and the difference matters enough to be carried in the type. A few cars —
 * the ones whose gearbox module answers an identifier the app knows — simply say which gear
 * is engaged, and that is a measurement. Everywhere else there is nothing to ask: generic
 * OBD2 has no selector-position PID, so the gear is inferred from road speed over engine
 * speed, and [provenance] is what stops the strip presenting the two as the same thing.
 */
data class GearReading(
    /** True once the car is moving fast enough for the question to have an answer. */
    val moving: Boolean,
    /** 1-based gear, or null when it cannot be told. */
    val gear: Int?,
    /** km/h per 1 000 rpm, the quantity an estimate is made from; null for a measurement. */
    val ratio: Double?,
    val provenance: Provenance = Provenance.Derived,
) {
    val measured: Boolean get() = provenance == Provenance.Measured

    companion object {
        val NONE = GearReading(moving = false, gear = null, ratio = null)

        /** What the gearbox itself said, when the car has a module that will say it. */
        fun measured(gear: Int) =
            GearReading(moving = true, gear = gear, ratio = null, provenance = Provenance.Measured)
    }
}

/**
 * Estimates the engaged gear from the speed-to-revs ratio, learning the car as it goes.
 *
 * The quotient of road speed and engine speed is the overall ratio of whatever gear is
 * engaged — but only while the drivetrain is solid between the two. It is not solid during
 * a shift, it is not solid with a clutch part-engaged, and on an automatic it is not solid
 * whenever the torque converter is unlocked, which in town is much of the time. Feeding
 * every sample straight into a lookup table is what made this readout unreliable: a
 * slipping converter produces a ratio a few percent to a third low, which lands squarely in
 * the gear below.
 *
 * So nothing is reported from a single sample. A ratio has to hold still across
 * [STEADY_SAMPLES] readings within [STEADY_TOLERANCE] of each other before it counts as the
 * car actually being in a gear; anything else — a shift, a slip, a bad pairing of readings
 * — leaves the strip unlit, which is the honest thing for it to say. Steady ratios are then
 * clustered, and the cluster keeps the gear number the prior table gave it, so once this
 * car's third has been seen every later ratio near it reads as third.
 *
 * @param maxGears how many forward gears the box has, from the vehicle profile. Nothing on
 * the bus reports it, and it is the difference between a five-speed's top gear reading as
 * fifth and reading as sixth.
 */
class GearEstimator(private val maxGears: Int = MAX_GEARS) {

    private val clusters = mutableListOf<Cluster>()
    private val recent = ArrayDeque<Double>()

    /** What the strip is currently showing, and how many samples have argued against it. */
    private var shown: Int? = null
    private var dissent = 0

    /** The moment the last accepted pair of readings was taken; see [observe]. */
    private var lastSampleAt = Long.MIN_VALUE

    private data class Cluster(var ratio: Double, val gear: Int, var samples: Int)

    /**
     * @param rpmAtMillis when the engine speed behind this call was read, and
     * [speedAtMillis] the same for the road speed. Both are needed, and not for staleness:
     * a snapshot is republished every time *any* parameter lands, so one pair of readings
     * reaches this several times a cycle, and counting those repeats as agreement would let
     * any three of them pass for a steady ratio — including the three that arrive in the
     * middle of a shift. They also say whether the two halves of the quotient describe the
     * same moment, which under acceleration is the difference between a gear and a number.
     */
    fun observe(rpm: Double?, speed: Double?, rpmAtMillis: Long, speedAtMillis: Long): GearReading {
        if (rpm == null || speed == null || !rpm.isFinite() || !speed.isFinite()) return stop()
        if (speed < MIN_SPEED_KMH || rpm < MIN_RPM) return stop()

        val at = maxOf(rpmAtMillis, speedAtMillis)
        if (at == lastSampleAt) return GearReading(moving = true, gear = shown, ratio = null)
        lastSampleAt = at

        val ratio = speed / rpm * 1_000.0
        if (ratio <= 0.0 || ratio > MAX_RATIO) return unknown(ratio)

        // Revs from one moment over a speed from another is not the ratio of anything. The
        // two normally arrive in the same batch and share a timestamp to the millisecond;
        // when they do not, the car was accelerating through the gap and the quotient is
        // skewed by however much it accelerated.
        if (abs(rpmAtMillis - speedAtMillis) > MAX_SKEW_MILLIS) return unknown(ratio)

        recent.addLast(ratio)
        while (recent.size > STEADY_SAMPLES) recent.removeFirst()

        // Mid-shift and mid-slip the quotient is sweeping rather than sitting, and a gear
        // read off a sweeping quotient is a guess dressed as a reading.
        if (!isSteady()) return unknown(ratio)

        // Steady, but between two gears this car has already shown: a converter holding a
        // slip does exactly that, and there is no gear number that honestly fits.
        val gear = gearFor(ratio) ?: return unknown(ratio)
        return settle(gear, ratio)
    }

    fun reset() {
        clusters.clear()
        recent.clear()
        shown = null
        dissent = 0
        lastSampleAt = Long.MIN_VALUE
    }

    /** The learned ratios, lowest gear first — for tests and for anybody debugging a car. */
    fun learned(): List<Pair<Int, Double>> =
        clusters.sortedBy(Cluster::gear).map { it.gear to it.ratio }

    /**
     * Whether the last few ratios agree with each other closely enough to be one gear.
     *
     * The window is deliberately short: three samples at the rate the dashboard polls is
     * well under a second, so a real shift still shows up as a shift rather than as a
     * second of blankness.
     */
    private fun isSteady(): Boolean {
        if (recent.size < STEADY_SAMPLES) return false
        val low = recent.min()
        val high = recent.max()
        return low > 0.0 && (high - low) / low <= STEADY_TOLERANCE
    }

    /**
     * The gear a steady ratio belongs to, learning it if this is a new one, or null when no
     * gear number honestly fits it.
     *
     * A ratio that matches nothing becomes a cluster of its own rather than evicting the
     * cluster the prior table happens to give the same number to. That eviction was the
     * other half of the old readout's unreliability: one slipping sample near the boundary
     * replaced a well-established gear with a bad ratio, the real gear then matched
     * nothing, and the two took turns overwriting each other for the rest of the drive.
     *
     * The number a new cluster gets is the prior's, but constrained to leave the gears in
     * the order physics puts them in: a higher ratio is always a higher gear, so a new
     * cluster has to fit strictly between its neighbours' numbers. When there is no room —
     * the ratio sits between two gears this car has already shown, one apart — it is not a
     * gear at all but a converter slipping or a clutch on its way, and nothing is learned
     * and nothing is claimed.
     */
    private fun gearFor(ratio: Double): Int? {
        val nearest = clusters.minByOrNull { abs(it.ratio - ratio) }
        if (nearest != null && abs(nearest.ratio - ratio) <= nearest.ratio * TOLERANCE) {
            // A running mean, damped so one poor sample cannot drag a learned cluster onto
            // its neighbour.
            nearest.samples = (nearest.samples + 1).coerceAtMost(MAX_SAMPLES)
            nearest.ratio += (ratio - nearest.ratio) / nearest.samples
            return nearest.gear
        }

        val below = clusters.filter { it.ratio < ratio }.maxByOrNull(Cluster::ratio)
        val above = clusters.filter { it.ratio > ratio }.minByOrNull(Cluster::ratio)
        val lowest = (below?.gear ?: 0) + 1
        val highest = (above?.gear ?: (maxGears + 1)) - 1
        if (lowest > highest) return null

        val gear = priorGearFor(ratio).coerceIn(lowest, highest)
        // Capped rather than pruned by gear number, so what falls off the list is whatever
        // has been seen least rather than whatever the newcomer happens to be called.
        if (clusters.size >= maxGears) clusters.remove(clusters.minByOrNull(Cluster::samples))
        clusters += Cluster(ratio, gear, samples = 1)
        return gear
    }

    /**
     * Holds the displayed gear until the new one has been seen [CONFIRMATIONS] times.
     *
     * Without this the strip follows every steady sample, and the steadiness test alone
     * does not stop a converter that has settled at a slip from reading as the gear below
     * for as long as it stays there. A real shift outlasts the confirmations easily; a
     * moment of slip does not.
     */
    private fun settle(gear: Int, ratio: Double): GearReading {
        if (gear == shown) {
            dissent = 0
            return GearReading(moving = true, gear = shown, ratio = ratio)
        }
        if (++dissent >= CONFIRMATIONS || shown == null) {
            shown = gear
            dissent = 0
            return GearReading(moving = true, gear = gear, ratio = ratio)
        }
        // Disagreed with but not yet outvoted. The strip stays dark for the sample rather
        // than flashing the old gear back up between the shift and its confirmation, which
        // would read as the box going 4 → nothing → 4 → 5 on every change.
        return GearReading(moving = true, gear = null, ratio = ratio)
    }

    /**
     * Moving, but with nothing worth claiming: the strip goes dark rather than guessing.
     *
     * [shown] survives, because it is what the next gear has to argue against. Clearing it
     * would mean every shift — which passes through unsteadiness by definition — handed the
     * first steady sample after it an unopposed vote, and a converter settling into a slip
     * would take the strip with it on the strength of one reading.
     */
    private fun unknown(ratio: Double): GearReading {
        dissent = 0
        return GearReading(moving = true, gear = null, ratio = ratio)
    }

    private fun stop(): GearReading {
        recent.clear()
        shown = null
        dissent = 0
        return GearReading.NONE
    }


    private fun priorGearFor(ratio: Double): Int {
        val index = PRIOR_BOUNDARIES.indexOfFirst { ratio < it }
        val gear = if (index < 0) PRIOR_BOUNDARIES.size + 1 else index + 1
        return gear.coerceIn(1, maxGears)
    }

    companion object {
        /** How many gears a car is assumed to have when the driver has not said. */
        const val MAX_GEARS = 6

        /** The most the strip will ever draw, whatever a profile claims. */
        const val GEAR_LIMIT = 8

        /**
         * Below this the quotient is dominated by the clutch or the torque converter — and
         * by the speed PID's own resolution, which is a whole kilometre an hour: at 6 km/h
         * that is an eight percent uncertainty on its own, wider than the tolerance a gear
         * is recognised by.
         */
        const val MIN_SPEED_KMH = 12.0

        private const val MIN_RPM = 500.0

        /** Nothing on a road car reaches this; above it the reading is a decode error. */
        private const val MAX_RATIO = 90.0

        /** How far a ratio can sit from a learned cluster and still be that gear. */
        private const val TOLERANCE = 0.08

        /** How many consecutive ratios make a gear, and how alike they have to be. */
        private const val STEADY_SAMPLES = 3
        private const val STEADY_TOLERANCE = 0.03

        /** How many steady samples of a different gear it takes to move the strip. */
        private const val CONFIRMATIONS = 2

        /** How far apart the two readings may be taken and still describe one moment. */
        private const val MAX_SKEW_MILLIS = 400L

        private const val MAX_SAMPLES = 40

        /**
         * Midpoints between typical overall ratios (km/h per 1 000 rpm) for gears one to
         * six. Wide enough to be wrong only on a car with unusual gearing, and the
         * learned clusters correct that within a few shifts.
         */
        private val PRIOR_BOUNDARIES = listOf(11.0, 17.5, 25.0, 33.0, 41.5, 50.5)
    }
}

/**
 * The gear the gearbox itself reported, when this car has a module that reports one.
 *
 * Only while it is fresh. The reading is absent in park, reverse and neutral — those are
 * not forward gears and the decoder publishes nothing for them — so without the staleness
 * check the strip would go on lighting the last gear engaged for as long as the car sat at
 * the lights.
 *
 * Here rather than in the phone's view model because the car screen asks the same question
 * of the same snapshot, and two copies of this would be two chances to answer it differently.
 */
fun VehicleSnapshot.reportedGear(nowMillis: Long = System.currentTimeMillis()): GearReading? {
    val gear = valueOf(REPORTED_GEAR)?.takeIf { it >= 1.0 } ?: return null
    if (isStale(REPORTED_GEAR, nowMillis)) return null
    return GearReading.measured(gear.toInt())
}

/** The gearbox's own answer, on the cars whose module gives one. */
private val REPORTED_GEAR = MetricId.Extended(ExtendedPids.GEAR)
