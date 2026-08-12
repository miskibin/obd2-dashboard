package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.ExtendedPids
import com.miskibin.obd2dashboard.obd.ExtendedReading
import com.miskibin.obd2dashboard.obd.Mode06
import com.miskibin.obd2dashboard.obd.MonitorTest
import com.miskibin.obd2dashboard.obd.MonitorTests
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.Reading
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which parts of the car diagram this car can actually fill.
 *
 * The rule that matters is the strict one: a place on the drawing lights up only when the
 * car has already reported the reading or has said it supports it. Anything looser and a
 * fresh connection covers the car in chips that never fill in — which is worse than a bare
 * drawing, because the driver reads it as the car having gone quiet.
 */
class CarZoneTest {

    private fun snapshotOf(
        readings: Map<Int, Double> = emptyMap(),
        extended: Map<String, Double> = emptyMap(),
        battery: Double? = null,
    ) = VehicleSnapshot(
        readings = readings.mapValues { (key, value) ->
            Reading(key = key, name = "", unit = "", value = value, timestampMillis = 1L)
        },
        extended = extended.mapValues { (id, value) ->
            ExtendedReading(id = id, unit = "", value = value, timestampMillis = 1L)
        },
        batteryVoltage = battery,
    )

    private fun bind(
        zone: CarZone,
        snapshot: VehicleSnapshot = snapshotOf(),
        supportedPids: Set<Int> = emptySet(),
        supportedExtended: Set<String> = emptySet(),
        misfire: MisfireReading? = null,
    ) = zone.sourceOn(snapshot, supportedPids, supportedExtended, misfire)

    @Test
    fun `a fresh install draws the four places every car has something to say about`() {
        assertEquals(
            setOf(CarZone.Engine, CarZone.Coolant, CarZone.Battery, CarZone.Oil),
            CarZone.DEFAULTS,
        )
    }

    @Test
    fun `every zone has a key and a name of its own`() {
        val keys = CarZone.entries.map(CarZone::storageKey)
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(CarZone.entries.all { it.nameRes != 0 })
        assertTrue(CarZone.entries.all { it.sources.isNotEmpty() })
    }

    @Test
    fun `every reading a place can show is one the catalogue knows`() {
        // A place whose fallback names a PID this app has no decoder for would silently
        // skip that fallback and drop to the next one, which is a zone quietly showing the
        // wrong thing rather than a compile error.
        val unknown = CarZone.entries.flatMap { zone ->
            zone.sources.filterIsInstance<ZoneSource.Reading>()
                .filter { Metrics[it.id] == null }
                .map { zone.storageKey + " -> " + it.id.storageKey }
        }
        assertEquals(emptyList<String>(), unknown)
    }

    @Test
    fun `a place a car reports nothing for is not drawn`() {
        CarZone.entries.forEach { zone -> assertNull(zone.storageKey, bind(zone)) }
    }

    @Test
    fun `a reading already in the snapshot fills its place`() {
        val snapshot = snapshotOf(readings = mapOf(Pids.COOLANT_TEMP to 91.0))
        assertEquals(
            ZoneSource.Reading(MetricId.Sensor(Pids.COOLANT_TEMP)),
            bind(CarZone.Coolant, snapshot),
        )
    }

    @Test
    fun `a PID the car listed fills its place before it has answered`() {
        // Nothing has arrived yet; the car said at handshake that it would answer, which is
        // enough to draw the radiator with a dash on it.
        val source = bind(CarZone.Coolant, supportedPids = setOf(Pids.COOLANT_TEMP))
        assertEquals(ZoneSource.Reading(MetricId.Sensor(Pids.COOLANT_TEMP)), source)
    }

    @Test
    fun `a place falls back to the nearest reading beside it`() {
        // No load PID, but the car reports timing advance, which is the third thing the
        // engine zone will settle for.
        val snapshot = snapshotOf(readings = mapOf(Pids.TIMING_ADVANCE to 12.0))
        assertEquals(
            ZoneSource.Reading(MetricId.Sensor(Pids.TIMING_ADVANCE)),
            bind(CarZone.Engine, snapshot),
        )
        // And the first choice wins the moment it arrives.
        val richer = snapshotOf(
            readings = mapOf(Pids.TIMING_ADVANCE to 12.0, Pids.ENGINE_LOAD to 40.0),
        )
        assertEquals(
            ZoneSource.Reading(MetricId.Sensor(Pids.ENGINE_LOAD)),
            bind(CarZone.Engine, richer),
        )
    }

    @Test
    fun `the adapter's own voltage fills the battery, which is not a PID`() {
        assertEquals(
            ZoneSource.Reading(MetricId.Battery),
            bind(CarZone.Battery, snapshotOf(battery = 14.1)),
        )
    }

    @Test
    fun `a tyre is only drawn on a car whose probe answered for it`() {
        val pressure = ExtendedPids.tyrePressureId("fl")
        // A car the extended table does not recognise never gets a tyre zone, however
        // many PIDs it supports.
        assertNull(bind(CarZone.TyreFrontLeft, supportedPids = (0..0xFF).toSet()))
        assertEquals(
            ZoneSource.Reading(MetricId.Extended(pressure)),
            bind(CarZone.TyreFrontLeft, supportedExtended = setOf(pressure)),
        )
        // The front left probe says nothing about the other three corners.
        assertNull(bind(CarZone.TyreRearRight, supportedExtended = setOf(pressure)))
    }

    @Test
    fun `oil falls back from the manufacturer's pressure to the standard temperature`() {
        assertEquals(
            ZoneSource.Reading(MetricId.Extended(ExtendedPids.OIL_PRESSURE)),
            bind(CarZone.Oil, supportedExtended = setOf(ExtendedPids.OIL_PRESSURE)),
        )
        assertEquals(
            ZoneSource.Reading(MetricId.Sensor(Pids.OIL_TEMP)),
            bind(CarZone.Oil, supportedPids = setOf(Pids.OIL_TEMP)),
        )
    }

    @Test
    fun `the coils show misfires when mode 06 has any, and timing when it has not`() {
        assertEquals(
            ZoneSource.Misfires,
            bind(CarZone.Ignition, misfire = MisfireReading(count = 3.0, passed = true)),
        )
        assertEquals(
            ZoneSource.Reading(MetricId.Sensor(Pids.TIMING_ADVANCE)),
            bind(CarZone.Ignition, supportedPids = setOf(Pids.TIMING_ADVANCE)),
        )
    }

    @Test
    fun `the worst misfire count carries the ECU's own verdict`() {
        assertNull(MonitorTests().worstMisfire())
        assertNull(null.worstMisfire())

        val tests = MonitorTests(
            tests = listOf(
                misfireTest(cylinder = 1, count = 2, limit = 10),
                misfireTest(cylinder = 2, count = 7, limit = 10),
            ),
        )
        val worst = tests.worstMisfire()
        assertNotNull(worst)
        assertEquals(7.0, worst!!.count, 1e-9)
        assertTrue(worst.passed)

        // One cylinder past the limit the ECU shipped is the whole engine failing.
        val failing = MonitorTests(
            tests = listOf(
                misfireTest(cylinder = 1, count = 2, limit = 10),
                misfireTest(cylinder = 2, count = 40, limit = 10),
            ),
        )
        assertFalse(failing.worstMisfire()!!.passed)
    }

    @Test
    fun `only the ticked places are bound, and always in drawing order`() {
        val snapshot = snapshotOf(
            readings = mapOf(
                Pids.COOLANT_TEMP to 91.0,
                Pids.ENGINE_LOAD to 40.0,
                Pids.THROTTLE_POSITION to 12.0,
            ),
        )
        val bound = carZoneBindings(
            selected = setOf(CarZone.Coolant, CarZone.Engine),
            snapshot = snapshot,
            supportedPids = emptySet(),
            supportedExtended = emptySet(),
            misfire = null,
        )
        // Throttle is reported but was not ticked; engine comes before coolant because the
        // drawing order is the enum's, not the selection's.
        assertEquals(listOf(CarZone.Engine, CarZone.Coolant), bound.map(CarZoneBinding::zone))
        assertEquals(MetricId.Sensor(Pids.ENGINE_LOAD), bound.first().opens)
        assertEquals(40.0, bound.first().valueIn(snapshot, misfire = null))
    }

    @Test
    fun `a ticked place this car cannot fill is left off the drawing`() {
        val bound = carZoneBindings(
            selected = CarZone.entries.toSet(),
            snapshot = snapshotOf(readings = mapOf(Pids.COOLANT_TEMP to 91.0)),
            supportedPids = emptySet(),
            supportedExtended = emptySet(),
            misfire = null,
        )
        assertEquals(listOf(CarZone.Coolant), bound.map(CarZoneBinding::zone))
    }

    @Test
    fun `the misfire binding shows the count and opens nothing`() {
        val misfire = MisfireReading(count = 5.0, passed = false)
        val binding = CarZoneBinding(CarZone.Ignition, ZoneSource.Misfires)
        assertNull(binding.opens)
        assertNull(binding.metric)
        assertEquals(CarZone.MISFIRE_UNIT, binding.unit)
        assertEquals(0, binding.decimals)
        assertEquals(5.0, binding.valueIn(snapshotOf(), misfire))
    }

    @Test
    fun `a selection survives storage, and ticking every place off is not a fresh install`() {
        val chosen = setOf(CarZone.Exhaust, CarZone.Engine, CarZone.TyreRearLeft)
        assertEquals(chosen, CarZone.decode(CarZone.encode(chosen)))
        assertEquals(emptySet<CarZone>(), CarZone.decode(CarZone.encode(emptySet())))
        // A key written by a later version of the app is skipped rather than losing the
        // whole selection with it.
        assertEquals(setOf(CarZone.Engine), CarZone.decode("engine|sunroof"))
    }

    private fun misfireTest(cylinder: Int, count: Int, limit: Int) = MonitorTest(
        mid = Mode06.MISFIRE_CYLINDER_MIDS.first + cylinder - 1,
        tid = Mode06.TID_MISFIRE_AVERAGE,
        uasid = Mode06.UASID_COUNTS,
        rawValue = count,
        rawMin = 0,
        rawMax = limit,
    )
}
