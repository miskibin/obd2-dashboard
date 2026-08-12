package com.miskibin.obd2dashboard.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MAF-derived fuel rate, which is what almost every car without PID 5E is judged on.
 *
 * 15 g/s of air is roughly a warm engine cruising, and is used throughout so the numbers
 * below can be compared with each other.
 */
class FuelRateTest {

    private val cruising = mapOf(Pids.MAF_RATE to 15.0, Pids.VEHICLE_SPEED to 90.0)

    private fun rate(values: Map<Int, Double>, fuel: FuelType = FuelType.Default): Double? =
        DerivedMetrics.compute(values, fuel)[DerivedMetrics.FuelRate.key]

    @Test
    fun `petrol burns what it breathes`() {
        // 15 g/s × 3600 ÷ (14.7 × 820) = 4.48 L/h
        assertEquals(4.480, rate(cruising, FuelType.Petrol)!!, 0.001)
    }

    /**
     * The bug this exists to stop coming back: a diesel is never at stoichiometric, so
     * dividing its air flow by the stoichiometric ratio invents fuel it did not burn.
     */
    @Test
    fun `diesel is not judged as if it were petrol`() {
        val diesel = rate(cruising, FuelType.Diesel)!!
        val petrol = rate(cruising, FuelType.Petrol)!!
        assertTrue("diesel should read leaner than petrol, got $diesel vs $petrol", diesel < petrol)
        assertEquals(2.478, diesel, 0.001)
    }

    /** λ from the car beats the nominal figure, at every load. */
    @Test
    fun `uses the commanded lambda when the car reports one`() {
        val measured = rate(cruising + (Pids.COMMANDED_EQUIV_RATIO to 1.2), FuelType.Diesel)!!
        val nominal = rate(cruising, FuelType.Diesel)!!
        assertEquals(3.717, measured, 0.001)
        assertTrue("a richer measured mixture means more fuel", measured > nominal)
    }

    @Test
    fun `a petrol engine enriching under full throttle burns more`() {
        val enriched = rate(cruising + (Pids.COMMANDED_EQUIV_RATIO to 0.85), FuelType.Petrol)!!
        assertTrue(enriched > rate(cruising, FuelType.Petrol)!!)
    }

    /**
     * An ECU that does not really implement PID 44 still answers it, usually with zero.
     * Dividing by that would report an infinite fuel rate.
     */
    @Test
    fun `ignores a lambda reading that is a placeholder`() {
        val zeroed = rate(cruising + (Pids.COMMANDED_EQUIV_RATIO to 0.0), FuelType.Diesel)!!
        assertEquals(rate(cruising, FuelType.Diesel)!!, zeroed, 0.001)
        assertTrue(zeroed.isFinite())
    }

    @Test
    fun `LPG burns more litres than petrol for the same air`() {
        assertTrue(rate(cruising, FuelType.Lpg)!! > rate(cruising, FuelType.Petrol)!!)
    }

    /** PID 5E is the car's own answer and is never second-guessed. */
    @Test
    fun `prefers the reported fuel rate over the derived one`() {
        val values = cruising + (Pids.FUEL_RATE to 6.0)
        assertEquals(6.0, rate(values, FuelType.Diesel)!!, 0.001)
    }

    @Test
    fun `an unfilled profile reads exactly as the app did before`() {
        assertEquals(rate(cruising, FuelType.Petrol)!!, rate(cruising)!!, 0.001)
    }

    @Test
    fun `consumption per 100 km follows the corrected rate`() {
        val derived = DerivedMetrics.compute(cruising, FuelType.Diesel)
        // 2.478 L/h at 90 km/h = 2.75 L/100 km
        assertEquals(2.753, derived.getValue(DerivedMetrics.FuelPer100Km.key), 0.001)
    }

    @Test
    fun `says nothing about consumption when the car is not moving`() {
        val derived = DerivedMetrics.compute(mapOf(Pids.MAF_RATE to 3.0), FuelType.Diesel)
        assertNull(derived[DerivedMetrics.FuelPer100Km.key])
        assertTrue(derived.containsKey(DerivedMetrics.FuelRate.key))
    }

    @Test
    fun `says nothing at all without a MAF or a fuel rate`() {
        assertNull(rate(mapOf(Pids.VEHICLE_SPEED to 90.0)))
    }
}
