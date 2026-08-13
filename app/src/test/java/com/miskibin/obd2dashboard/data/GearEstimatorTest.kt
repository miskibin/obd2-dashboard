package com.miskibin.obd2dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GearEstimatorTest {

    private fun estimator(gears: Int = GearEstimator.MAX_GEARS) = GearEstimator(maxGears = gears)

    /** Speed that produces a given km/h per 1 000 rpm ratio at 2 000 rpm. */
    private fun speedFor(ratio: Double, rpm: Double = 2_000.0) = ratio * rpm / 1_000.0

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
        repeat(samples) { last = observe(rpm, speedFor(ratio, rpm)) }
        return last
    }

    @Test
    fun `says nothing while the car is stopped`() {
        val reading = estimator().observe(rpm = 800.0, speed = 0.0)

        assertFalse(reading.moving)
        assertNull(reading.gear)
    }

    @Test
    fun `says nothing without readings`() {
        assertNull(estimator().observe(rpm = null, speed = 40.0).gear)
        assertNull(estimator().observe(rpm = 2_000.0, speed = null).gear)
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
        val reading = estimator().observe(rpm = 600.0, speed = 200.0)

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
            estimator.observe(rpm = 2_000.0, speed = speedFor(ratio))
        }

        assertTrue(sweeping.all { it.moving })
        assertTrue("a sweeping ratio is not a gear", sweeping.all { it.gear == null })
    }

    @Test
    fun `a ratio that settles is reported once it has held`() {
        val estimator = estimator()

        estimator.observe(rpm = 2_000.0, speed = speedFor(12.0))
        estimator.observe(rpm = 2_000.0, speed = speedFor(19.0))
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

        // Three samples: the first two are a ratio in motion and read as no gear at all,
        // the third has settled — and still has to argue against the gear on the strip.
        val excursion = (1..3).map { estimator.observe(rpm = 2_000.0, speed = speedFor(19.0)) }

        assertNull(excursion[0].gear)
        assertEquals(4, excursion.last().gear)
    }

    @Test
    fun `a real shift moves the strip`() {
        val estimator = estimator()
        estimator.hold(27.0)

        val shifted = estimator.hold(38.0)

        assertEquals(5, shifted.gear)
    }
}
