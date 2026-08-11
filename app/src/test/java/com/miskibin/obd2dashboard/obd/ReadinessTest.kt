package com.miskibin.obd2dashboard.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Worked byte examples from SAE J1979 `0101`. The bit that catches everyone is byte D:
 * a *set* bit means the test has **not** finished.
 */
class ReadinessTest {

    private fun stateOf(readiness: Readiness, id: MonitorId): MonitorState? =
        readiness.monitors.firstOrNull { it.id == id }?.state

    @Test
    fun `spark car with catalyst and evap still running`() {
        // B 07: all three continuous monitors supported and complete, spark ignition.
        // C 65 = 0110 0101: catalyst, evap, O2 sensor and O2 heater supported.
        // D 05 = 0000 0101: catalyst and evap not complete.
        val readiness = ReadinessDecoder.parse(listOf(0x07, 0x65, 0x05))!!

        assertEquals(IgnitionType.Spark, readiness.ignition)
        assertEquals(MonitorState.Complete, stateOf(readiness, MonitorId.Misfire))
        assertEquals(MonitorState.Complete, stateOf(readiness, MonitorId.FuelSystem))
        assertEquals(MonitorState.Complete, stateOf(readiness, MonitorId.Components))
        assertEquals(MonitorState.Incomplete, stateOf(readiness, MonitorId.Catalyst))
        assertEquals(MonitorState.Incomplete, stateOf(readiness, MonitorId.EvaporativeSystem))
        assertEquals(MonitorState.Complete, stateOf(readiness, MonitorId.OxygenSensor))
        assertEquals(MonitorState.Complete, stateOf(readiness, MonitorId.OxygenSensorHeater))
        assertEquals(MonitorState.NotSupported, stateOf(readiness, MonitorId.SecondaryAirSystem))
        assertEquals(MonitorState.NotSupported, stateOf(readiness, MonitorId.EgrSystem))

        assertFalse(readiness.ready)
        assertEquals(
            listOf(MonitorId.Catalyst, MonitorId.EvaporativeSystem),
            readiness.incomplete.map(Monitor::id),
        )
        assertEquals(7, readiness.supported.size)
        // The compression-only monitors never appear on a spark car.
        assertNull(stateOf(readiness, MonitorId.ParticulateFilter))
    }

    @Test
    fun `spark car straight after a code clear reports nothing complete`() {
        // B 77: continuous monitors supported (bits 0-2) and all incomplete (bits 4-6).
        val readiness = ReadinessDecoder.parse(listOf(0x77, 0xE1, 0xE1))!!

        assertEquals(IgnitionType.Spark, readiness.ignition)
        assertTrue(readiness.supported.all { it.state == MonitorState.Incomplete })
        assertEquals(7, readiness.incomplete.size)
        assertFalse(readiness.ready)
    }

    @Test
    fun `compression car reads bytes C and D as the diesel monitor set`() {
        // B 0F: bit 3 set, so C and D describe compression-ignition monitors.
        // C EB = 1110 1011: NMHC, NOx, boost, exhaust sensor, PM filter, EGR/VVT.
        // D 40 = 0100 0000: the PM filter test is still running.
        val readiness = ReadinessDecoder.parse(listOf(0x0F, 0xEB, 0x40))!!

        assertEquals(IgnitionType.Compression, readiness.ignition)
        assertEquals(MonitorState.Complete, stateOf(readiness, MonitorId.NmhcCatalyst))
        assertEquals(MonitorState.Complete, stateOf(readiness, MonitorId.NoxAftertreatment))
        assertEquals(MonitorState.Complete, stateOf(readiness, MonitorId.BoostPressure))
        assertEquals(MonitorState.Complete, stateOf(readiness, MonitorId.ExhaustGasSensor))
        assertEquals(MonitorState.Incomplete, stateOf(readiness, MonitorId.ParticulateFilter))
        assertEquals(MonitorState.Complete, stateOf(readiness, MonitorId.EgrVvtSystem))
        // Bit 3 of byte B also marks the continuous monitors, which stay shared.
        assertEquals(MonitorState.Complete, stateOf(readiness, MonitorId.Misfire))
        assertNull(stateOf(readiness, MonitorId.Catalyst))

        assertFalse(readiness.ready)
        assertEquals(listOf(MonitorId.ParticulateFilter), readiness.incomplete.map(Monitor::id))
    }

    @Test
    fun `a car that has finished every test is ready`() {
        val readiness = ReadinessDecoder.parse(listOf(0x07, 0xFF, 0x00))!!

        assertTrue(readiness.ready)
        assertEquals(11, readiness.supported.size)
        assertTrue(readiness.incomplete.isEmpty())
    }

    @Test
    fun `a short reply carries no readiness at all`() {
        assertNull(ReadinessDecoder.parse(emptyList()))
        assertNull(ReadinessDecoder.parse(listOf(0x07, 0x65)))
        assertNull(MonitorStatus(milOn = false, dtcCount = 0).readiness)
    }

    @Test
    fun `monitor status exposes the readiness of its own bytes`() {
        val status = MonitorStatus(milOn = true, dtcCount = 2, readinessBytes = listOf(0x07, 0x65, 0x05))

        assertEquals(2, status.readiness!!.incomplete.size)
    }
}
