package com.miskibin.obd2dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GearEstimatorTest {

    /**
     * A clock that ticks once per delivered reading.
     *
     * The estimator counts distinct measurements rather than calls, so every test has to
     * say when its readings were taken — which is also how the repeat-delivery case below
     * is written.
     */
    private var clock = 0L

    private fun estimator(gears: Int = GearEstimator.MAX_GEARS) = GearEstimator(maxGears = gears)

    /** Speed that produces a given km/h per 1 000 rpm ratio at 2 000 rpm. */
    private fun speedFor(ratio: Double, rpm: Double = 2_000.0) = ratio * rpm / 1_000.0

    /** One fresh pairing of the two readings, a tick after the last. */
    private fun GearEstimator.sample(rpm: Double?, speed: Double?): GearReading {
        clock += TICK_MILLIS
        return observe(rpm, speed, rpmAtMillis = clock, speedAtMillis = clock)
    }

    /**
     * Holds a ratio for as long as it takes the estimator to believe it.
     *
     * Nothing is reported from one sample any more, so every test that wants a gear has to
     * drive the car in it for a moment — which is what the fix is.
     */
    private fun GearEstimator.hold(
        ratio: Double,
        rpm: Double = 2_000.0,
        samples: Int = 5,
    ): GearReading {
        var last = GearReading.NONE
        repeat(samples) { last = sample(rpm, speedFor(ratio, rpm)) }
        return last
    }

    @Test
    fun `says nothing while the car is stopped`() {
        val reading = estimator().sample(rpm = 800.0, speed = 0.0)

        assertFalse(reading.moving)
        assertNull(reading.gear)
    }

    @Test
    fun `says nothing without readings`() {
        assertNull(estimator().sample(rpm = null, speed = 40.0).gear)
        assertNull(estimator().sample(rpm = 2_000.0, speed = null).gear)
    }

    @Test
    fun `reads a low ratio as a low gear and a high one as a high gear`() {
        assertEquals(1, estimator().hold(8.0).gear)
        assertEquals(6, estimator().hold(46.0).gear)
    }

    @Test
    fun `never reports a gear above the strip it is drawn on`() {
        val reading = estimator().hold(70.0, rpm = 1_500.0)

        assertEquals(GearEstimator.MAX_GEARS, reading.gear)
    }

    /**
     * A five-speed's top gear sits where the six-speed table calls sixth, and nothing on
     * the bus says which box is fitted — only the vehicle profile can.
     */
    @Test
    fun `a five speed tops out at fifth`() {
        assertEquals(5, estimator(gears = 5).hold(46.0).gear)
    }

    @Test
    fun `keeps calling the same ratio the same gear once it has learned it`() {
        val estimator = estimator()
        estimator.hold(24.0)

        // A neighbouring ratio the prior would put on the boundary: the learned cluster
        // has to win, or the readout would flicker across a shift that never happened.
        val again = estimator.hold(25.4, rpm = 2_500.0)

        assertEquals(3, again.gear)
    }

    @Test
    fun `forgets the car when the adapter goes away`() {
        val estimator = estimator()
        estimator.hold(8.0)

        estimator.reset()

        assertEquals(6, estimator.hold(46.0).gear)
    }

    @Test
    fun `ignores a ratio no road car could produce`() {
        val reading = estimator().sample(rpm = 600.0, speed = 200.0)

        assertTrue(reading.moving)
        assertNull(reading.gear)
    }

    // ---- the steadiness gate ----------------------------------------------------

    /**
     * The heart of the fix. A ratio sweeping through the gears is a shift or a slipping
     * torque converter, and every value it passes through belongs to some gear or other —
     * which is how the old estimator confidently reported four of them in a second.
     */
    @Test
    fun `reports nothing while the ratio is still moving`() {
        val estimator = estimator()

        val sweeping = listOf(12.0, 15.0, 19.0, 23.0).map { ratio ->
            estimator.sample(rpm = 2_000.0, speed = speedFor(ratio))
        }

        assertTrue(sweeping.all { it.moving })
        assertTrue("a sweeping ratio is not a gear", sweeping.all { it.gear == null })
    }

    /**
     * The snapshot is republished whenever any parameter lands, so one pair of speed and
     * rev readings reaches the estimator several times a cycle. Those repeats must not add
     * up to agreement, or a shift would qualify as steady before the car had finished it.
     */
    @Test
    fun `the same reading arriving repeatedly is still one reading`() {
        val estimator = estimator()

        // One physical measurement, delivered five times under the same timestamp, and
        // then the shift carries on.
        repeat(5) {
            estimator.observe(3_000.0, speedFor(19.0, 3_000.0), rpmAtMillis = 100, speedAtMillis = 100)
        }
        val stillShifting =
            estimator.observe(2_400.0, speedFor(23.0, 2_400.0), rpmAtMillis = 200, speedAtMillis = 200)

        assertNull("five copies of one sample are not a steady ratio", stillShifting.gear)
    }

    @Test
    fun `a ratio that settles is reported once it has held`() {
        val estimator = estimator()

        estimator.sample(rpm = 2_000.0, speed = speedFor(12.0))
        estimator.sample(rpm = 2_000.0, speed = speedFor(19.0))
        val settled = estimator.hold(27.0)

        assertEquals(4, settled.gear)
    }

    /** Below this the speed PID's whole-kilometre resolution is wider than a gear. */
    @Test
    fun `stays quiet at walking pace`() {
        val reading = estimator().hold(8.0, rpm = 1_000.0)

        assertFalse(reading.moving)
        assertNull(reading.gear)
    }

    // ---- learning ---------------------------------------------------------------

    /**
     * The other half of the old unreliability: a single off ratio landing on a gear number
     * already learned wiped the good cluster, the real gear then matched nothing, and the
     * two overwrote each other for the rest of the drive.
     */
    @Test
    fun `a stray ratio does not evict a well established gear`() {
        val estimator = estimator()
        repeat(20) { estimator.hold(18.9, samples = 1) }
        val learnedThird = estimator.learned().single { it.first == 3 }.second

        estimator.hold(24.0)

        val third = estimator.learned().singleOrNull { it.first == 3 }
        assertEquals("third should still be where it was learned", learnedThird, third?.second)
    }

    @Test
    fun `learns no more clusters than the box has gears`() {
        val estimator = estimator(gears = 5)

        listOf(8.0, 14.0, 20.0, 27.0, 35.0, 44.0, 52.0).forEach { estimator.hold(it) }

        assertTrue(estimator.learned().size <= 5)
    }

    /**
     * A converter that settles at a slip holds a steady ratio in the gear below, so
     * steadiness alone is not enough: the strip waits for the new gear to be confirmed
     * before it moves.
     */
    @Test
    fun `a brief settled excursion does not move the strip`() {
        val estimator = estimator()
        estimator.hold(27.0)

        // Three samples: the first two are a ratio in motion, the third has settled — and
        // one settled sample does not outvote the gear already on the strip. Nothing is
        // lit for any of them, which is the honest answer while the two disagree.
        val excursion = (1..3).map { estimator.sample(rpm = 2_000.0, speed = speedFor(19.0)) }

        assertTrue(excursion.all { it.gear == null })
        // And the gear it was in is still what the estimator goes back to.
        assertEquals(4, estimator.hold(27.0).gear)
    }

    @Test
    fun `a real shift moves the strip`() {
        val estimator = estimator()
        estimator.hold(27.0)

        val shifted = estimator.hold(38.0)

        assertEquals(5, shifted.gear)
    }

    /**
     * Revs from one moment over a speed from another is not the ratio of anything; under
     * acceleration the quotient is skewed by however much the car sped up in between.
     */
    @Test
    fun `readings taken too far apart are not paired into a ratio`() {
        val estimator = estimator()

        val skewed = (1..4).map {
            clock += TICK_MILLIS
            estimator.observe(
                rpm = 2_000.0,
                speed = speedFor(27.0),
                rpmAtMillis = clock,
                speedAtMillis = clock - SKEW_MILLIS,
            )
        }

        assertTrue(skewed.all { it.gear == null })
    }

    private companion object {
        const val TICK_MILLIS = 100L

        /** Wider than the estimator will pair across. */
        const val SKEW_MILLIS = 900L
    }
}
