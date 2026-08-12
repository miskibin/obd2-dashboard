package com.miskibin.obd2dashboard.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What each derived value is worth, which is the half of it the dashboard has to print.
 *
 * The arithmetic is [FuelRateTest]'s and [PidDecodingTest]'s subject; this is about the
 * claim attached to the result — measured, worked out, or worked out around a constant the
 * app supplied — and about the value being dated by its inputs rather than by the moment
 * the arithmetic happened to run.
 */
class DerivedProvenanceTest {

    private val cruising = mapOf(Pids.MAF_RATE to 15.0, Pids.VEHICLE_SPEED to 90.0)

    private fun fuelRate(
        values: Map<Int, Double>,
        fuel: FuelType? = null,
        timestamps: Map<Int, Long> = emptyMap(),
    ) = DerivedMetrics.computeAll(values, fuel, timestamps).getValue(DerivedMetrics.FuelRate.key)

    private fun boost(values: Map<Int, Double>, timestamps: Map<Int, Long> = emptyMap()) =
        DerivedMetrics.computeAll(values, timestamps = timestamps)
            .getValue(DerivedMetrics.Boost.key)

    // ---- boost ------------------------------------------------------------------

    @Test
    fun `boost is a plain computation when the car reports the outside air`() {
        val reading = boost(mapOf(Pids.INTAKE_MAP to 180.0, Pids.BAROMETRIC_PRESSURE to 99.0))

        assertEquals(81.0, reading.value, 0.001)
        assertEquals(Provenance.Derived, reading.provenance)
        assertNull(reading.assumption)
    }

    /**
     * The substitution this exists to stop being silent: without PID 33 the app has no idea
     * what the air outside is doing and uses sea level, which is a kilopascal per hundred
     * metres of altitude out.
     */
    @Test
    fun `boost says so when it had to assume sea level`() {
        val reading = boost(mapOf(Pids.INTAKE_MAP to 180.0))

        assertEquals(78.7, reading.value, 0.001)
        assertEquals(Provenance.Assumed, reading.provenance)
        assertEquals(Assumption.SeaLevelPressure, reading.assumption)
    }

    // ---- fuel -------------------------------------------------------------------

    /** PID 5E is the ECU's own answer, and the app adds nothing to it. */
    @Test
    fun `a reported fuel rate is a measurement`() {
        val reading = fuelRate(cruising + (Pids.FUEL_RATE to 6.0), FuelType.Diesel)

        assertEquals(6.0, reading.value, 0.001)
        assertEquals(Provenance.Measured, reading.provenance)
    }

    @Test
    fun `a MAF fuel rate with a known fuel and a measured mixture is a computation`() {
        val reading = fuelRate(
            values = cruising + (WIDE_RANGE_LAMBDA to 1.4),
            fuel = FuelType.Diesel,
        )

        assertEquals(Provenance.Derived, reading.provenance)
        assertNull(reading.assumption)
    }

    /**
     * The costliest assumption in the app: a diesel run through petrol's chemistry reads
     * about four fifths high, and the driver has no way to know unless the tile says so.
     */
    @Test
    fun `an unfilled fuel type is named as the assumption it is`() {
        val reading = fuelRate(cruising + (WIDE_RANGE_LAMBDA to 1.4), fuel = null)

        assertEquals(Provenance.Assumed, reading.provenance)
        assertEquals(Assumption.FuelTypeUnset, reading.assumption)
    }

    @Test
    fun `a car that reports no mixture at all is named separately`() {
        val reading = fuelRate(cruising, fuel = FuelType.Petrol)

        assertEquals(Provenance.Assumed, reading.provenance)
        assertEquals(Assumption.NominalLambda, reading.assumption)
    }

    /** A placeholder lambda is not a mixture, so it does not clear the assumption. */
    @Test
    fun `a zeroed commanded ratio still counts as no mixture`() {
        val reading = fuelRate(
            values = cruising + (Pids.COMMANDED_EQUIV_RATIO to 0.0),
            fuel = FuelType.Petrol,
        )

        assertEquals(Assumption.NominalLambda, reading.assumption)
    }

    @Test
    fun `consumption per 100 km inherits what the rate behind it is worth`() {
        val derived = DerivedMetrics.computeAll(cruising, fuel = null)

        assertEquals(
            Provenance.Assumed,
            derived.getValue(DerivedMetrics.FuelPer100Km.key).provenance,
        )
        assertEquals(
            Assumption.FuelTypeUnset,
            derived.getValue(DerivedMetrics.FuelPer100Km.key).assumption,
        )
    }

    // ---- age --------------------------------------------------------------------

    /**
     * The bug this file exists for. Boost is recomputed on every publish out of the whole
     * reading map, so a manifold pressure that stopped arriving a minute ago used to be
     * restamped as fresh by the next engine-speed frame and never dimmed.
     */
    @Test
    fun `a derived value is as old as the oldest reading behind it`() {
        val reading = boost(
            values = mapOf(Pids.INTAKE_MAP to 180.0, Pids.BAROMETRIC_PRESSURE to 99.0),
            timestamps = mapOf(Pids.INTAKE_MAP to 1_000L, Pids.BAROMETRIC_PRESSURE to 400L),
        )

        assertEquals(400L, reading.timestampMillis)
    }

    @Test
    fun `consumption is dated by the speed as well as by the rate`() {
        val derived = DerivedMetrics.computeAll(
            values = cruising,
            fuel = FuelType.Petrol,
            timestamps = mapOf(Pids.MAF_RATE to 900L, Pids.VEHICLE_SPEED to 250L),
        )

        assertEquals(900L, derived.getValue(DerivedMetrics.FuelRate.key).timestampMillis)
        assertEquals(250L, derived.getValue(DerivedMetrics.FuelPer100Km.key).timestampMillis)
    }

    /** `0124`, the pre-catalyst wide-range probe. */
    private companion object {
        const val WIDE_RANGE_LAMBDA = 0x24
    }
}
