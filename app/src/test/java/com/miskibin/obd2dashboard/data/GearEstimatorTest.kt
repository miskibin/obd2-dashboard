package com.miskibin.obd2dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GearEstimatorTest {

    private fun estimator() = GearEstimator()

    /** Speed that produces a given km/h per 1 000 rpm ratio at 2 000 rpm. */
    private fun speedFor(ratio: Double, rpm: Double = 2_000.0) = ratio * rpm / 1_000.0

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
        val estimator = estimator()

        val first = estimator.observe(rpm = 2_000.0, speed = speedFor(8.0))
        val sixth = estimator.observe(rpm = 2_000.0, speed = speedFor(46.0))

        assertEquals(1, first.gear)
        assertEquals(6, sixth.gear)
    }

    @Test
    fun `never reports a gear above the strip it is drawn on`() {
        val reading = estimator().observe(rpm = 1_500.0, speed = speedFor(70.0, 1_500.0))

        assertEquals(GearEstimator.MAX_GEARS, reading.gear)
    }

    @Test
    fun `keeps calling the same ratio the same gear once it has learned it`() {
        val estimator = estimator()
        // A ratio the prior calls third, then a neighbouring one the prior would put on
        // the boundary: the learned cluster has to win, or the readout would flicker.
        estimator.observe(rpm = 2_000.0, speed = speedFor(24.0))
        val again = estimator.observe(rpm = 2_500.0, speed = speedFor(25.4, 2_500.0))

        assertEquals(3, again.gear)
    }

    @Test
    fun `forgets the car when the adapter goes away`() {
        val estimator = estimator()
        estimator.observe(rpm = 2_000.0, speed = speedFor(8.0))

        estimator.reset()
        val reading = estimator.observe(rpm = 2_000.0, speed = speedFor(46.0))

        assertEquals(6, reading.gear)
    }

    @Test
    fun `ignores a ratio no road car could produce`() {
        val reading = estimator().observe(rpm = 600.0, speed = 200.0)

        assertTrue(reading.moving)
        assertNull(reading.gear)
    }
}
