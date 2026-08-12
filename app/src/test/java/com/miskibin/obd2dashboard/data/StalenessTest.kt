package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.DerivedMetrics
import com.miskibin.obd2dashboard.obd.DerivedValue
import com.miskibin.obd2dashboard.obd.ExtendedPids
import com.miskibin.obd2dashboard.obd.ExtendedReading
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.Provenance
import com.miskibin.obd2dashboard.obd.Reading
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a value stops being presented as "now".
 *
 * Every kind of value dates itself by its own last answer, so the threshold has to know
 * how often the app even asks: three seconds is a link in trouble for a gauge polled every
 * cycle and a perfectly normal gap for the adapter voltage, which is asked for once every
 * twentieth.
 */
class StalenessTest {

    private val boost = MetricId.Derived(DerivedMetrics.Boost.key)
    private val tyre = MetricId.Extended(ExtendedPids.tyrePressureId("fl"))

    @Test
    fun `a live gauge is judged in seconds`() {
        assertEquals(LIVE_STALE_MILLIS, staleAfterMillis(Metrics.Rpm))
    }

    /** Asked for once every twentieth cycle, so three seconds would dim it permanently. */
    @Test
    fun `the adapter voltage is given the room its polling needs`() {
        assertTrue(staleAfterMillis(MetricId.Battery) > LIVE_STALE_MILLIS * 5)
    }

    /** A tyre pressure the app deliberately asks for once a quarter minute. */
    @Test
    fun `an extended reading is judged against its own minimum interval`() {
        val interval = ExtendedPids[tyre.id]?.minIntervalMillis ?: 0L

        assertTrue("this test needs a rate-limited parameter", interval > 0)
        assertTrue(staleAfterMillis(tyre) >= interval * 2)
    }

    @Test
    fun `a derived value gets the slack of the slowest input behind it`() {
        assertTrue(staleAfterMillis(boost) > LIVE_STALE_MILLIS)
    }

    /**
     * The whole point of dating a derived value by its inputs: a boost figure computed from
     * a manifold pressure that stopped arriving a minute ago must dim, however recently the
     * arithmetic was rerun on the back of some other reading.
     */
    @Test
    fun `a derived value dims on the age of what it was computed from`() {
        val now = 100_000L
        val snapshot = VehicleSnapshot(
            readings = mapOf(
                Pids.ENGINE_RPM to Reading(Pids.ENGINE_RPM, "rpm", "rpm", 2_000.0, now),
            ),
            derived = mapOf(
                DerivedMetrics.Boost.key to DerivedValue(
                    value = 40.0,
                    provenance = Provenance.Derived,
                    timestampMillis = now - 60_000L,
                ),
            ),
            batteryVoltage = 14.1,
            batteryAtMillis = now - 1_000L,
            updatedAtMillis = now,
        )

        assertFalse(snapshot.isStale(Metrics.Rpm, now))
        assertTrue("the inputs are a minute old", snapshot.isStale(boost, now))
        assertFalse(snapshot.isStale(MetricId.Battery, now))
    }

    @Test
    fun `an extended reading that has stopped arriving goes stale`() {
        val now = 100_000L
        val snapshot = VehicleSnapshot(
            extended = mapOf(
                tyre.id to ExtendedReading(tyre.id, "bar", 2.3, now - 5 * 60_000L),
            ),
            updatedAtMillis = now,
        )

        assertTrue(snapshot.isStale(tyre, now))
    }
}
