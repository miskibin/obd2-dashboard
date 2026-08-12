package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.obd.ExtendedPids
import com.miskibin.obd2dashboard.obd.ExtendedReading
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.ui.chart.isAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A manufacturer-specific reading has to travel the ordinary route — catalogue, tile,
 * chart, recording — or it is a number that exists and cannot be looked at.
 */
class ExtendedMetricTest {

    private val oilPressure = MetricId.Extended(ExtendedPids.OIL_PRESSURE)

    @Test
    fun `a stored key still means the same metric after a round trip`() {
        assertEquals("ext:${ExtendedPids.OIL_PRESSURE}", oilPressure.storageKey)
        assertEquals(oilPressure, MetricId.parse(oilPressure.storageKey))
        // Nothing else claims the prefix, and an empty id is not a metric.
        assertNull(MetricId.parse("ext:"))
    }

    @Test
    fun `every extended parameter is in the catalogue exactly once, named and explained`() {
        val extended = Metrics.catalog.filter { it.id is MetricId.Extended }

        assertEquals(ExtendedPids.metrics.size, extended.size)
        assertEquals(extended.size, extended.distinctBy { it.id }.size)
        assertTrue(extended.none { it.nameRes == R.string.metric_unknown })
        assertTrue(extended.none { it.descriptionRes == R.string.metric_desc_unknown })
        assertTrue(extended.none { it.hintRes == R.string.metric_hint_unknown })
    }

    @Test
    fun `the catalogue entry carries the unit and precision the decoder produces`() {
        val metric = Metrics[oilPressure]!!
        val tyre = Metrics[MetricId.Extended(ExtendedPids.tyrePressureId("fl"))]!!

        assertEquals("kPa", metric.unit)
        assertEquals(0, metric.decimals)
        // A tyre pressure moving by a hundredth of a bar is the whole point of reading it.
        assertEquals("bar", tyre.unit)
        assertEquals(2, tyre.decimals)
    }

    @Test
    fun `an extended reading is found by the same lookups every other reading is`() {
        val snapshot = VehicleSnapshot(
            extended = mapOf(
                ExtendedPids.OIL_PRESSURE to ExtendedReading(
                    id = ExtendedPids.OIL_PRESSURE,
                    unit = "kPa",
                    value = 380.0,
                    timestampMillis = 1_234,
                ),
            ),
            updatedAtMillis = 9_999,
        )

        assertEquals(380.0, snapshot.valueOf(oilPressure)!!, 0.001)
        // Its own timestamp, not the snapshot's: an extended parameter is read every few
        // cycles, so borrowing the snapshot's would call a stale reading fresh.
        assertEquals(1_234L, snapshot.updatedAtOf(oilPressure))
        assertTrue(oilPressure in snapshot.presentMetrics())
        assertNull(VehicleSnapshot().valueOf(oilPressure))
    }

    @Test
    fun `the picker files each extended reading where somebody would look for it`() {
        assertEquals(MetricGroup.Engine, Metrics.groupOf(oilPressure))
        assertEquals(
            MetricGroup.Temperature,
            Metrics.groupOf(MetricId.Extended(ExtendedPids.OIL_TEMPERATURE)),
        )
        assertEquals(
            MetricGroup.Temperature,
            Metrics.groupOf(MetricId.Extended(ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE)),
        )
        assertEquals(
            MetricGroup.Vehicle,
            Metrics.groupOf(MetricId.Extended(ExtendedPids.tyrePressureId("rl"))),
        )
    }

    @Test
    fun `an extended parameter is offered only once the car has answered for it`() {
        val metric = Metrics[oilPressure]!!
        val everyPid = (0x00..0xFF).toSet()

        // Unlike a PID, an unknown support state is not enough: a Mazda identifier offered
        // on a Golf is a row that will never fill in.
        assertFalse(metric.isAvailable(emptySet(), emptySet(), supportKnown = false))
        assertFalse(metric.isAvailable(everyPid, emptySet(), supportKnown = true))
        assertTrue(
            metric.isAvailable(everyPid, setOf(ExtendedPids.OIL_PRESSURE), supportKnown = true),
        )
        // The ordinary PIDs keep the old rule: unknown support means "not yet ruled out".
        assertNotNull(Metrics[Metrics.Rpm])
        assertTrue(Metrics[Metrics.Rpm]!!.isAvailable(emptySet(), emptySet(), supportKnown = false))
    }
}
