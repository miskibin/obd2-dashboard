package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.MonitorCounts
import com.miskibin.obd2dashboard.obd.MonitorTest
import com.miskibin.obd2dashboard.obd.PerformanceTracking
import com.miskibin.obd2dashboard.obd.TrackedMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-car history of what the on-board monitors said, which is the only way a catalyst
 * storage figure becomes a trend rather than a number.
 */
class MonitorLogTest {

    private val vin = "JM1BPBLM7K1234567"

    private fun snapshot(
        at: Long,
        vin: String = this.vin,
        storage: Int = 85,
    ) = MonitorSnapshot(
        vin = vin,
        capturedAtMillis = at,
        tests = listOf(
            MonitorTest(0x21, 0x82, 0x1E, rawValue = storage, rawMin = 30, rawMax = 250),
            MonitorTest(0xA3, 0x0B, 0x24, rawValue = 12, rawMin = 0, rawMax = 10),
        ),
        performance = PerformanceTracking(
            obdConditions = 210,
            ignitionCycles = 250,
            monitors = listOf(MonitorCounts(TrackedMonitor.CatalystBank1, 30, 45)),
        ),
    )

    @Test
    fun `a snapshot survives a round trip through storage`() {
        val decoded = MonitorLog.decode(MonitorLog.encode(listOf(snapshot(at = 1_000))))

        assertEquals(listOf(snapshot(at = 1_000)), decoded)
    }

    @Test
    fun `the performance counters keep their positions when a monitor is missing`() {
        // Only the catalyst counters were reported; the EGR pair must not slide into its
        // place on the way back out, because position is the only thing naming them.
        val stored = MonitorLog.encode(listOf(snapshot(at = 1)))

        val performance = MonitorLog.decode(stored).single().performance!!

        assertEquals(1, performance.monitors.size)
        assertEquals(TrackedMonitor.CatalystBank1, performance.monitors.single().monitor)
        assertEquals(210, performance.obdConditions)
    }

    @Test
    fun `readings of one car accumulate newest first and are capped`() {
        var log = emptyList<MonitorSnapshot>()
        repeat(MonitorLog.MAX_PER_VEHICLE + 3) { index ->
            log = MonitorLog.merge(log, snapshot(at = index.toLong(), storage = 90 - index))
        }

        assertEquals(MonitorLog.MAX_PER_VEHICLE, log.size)
        assertEquals(
            (MonitorLog.MAX_PER_VEHICLE + 2).toLong(),
            log.first().capturedAtMillis,
        )
        // A falling series is exactly the signal worth keeping: the converter is ageing.
        // The log runs newest first, so the newest reading is the lowest of them.
        val storage = log.map { it.tests.first().rawValue }
        assertEquals(storage.sorted(), storage)
    }

    @Test
    fun `one car's readings never displace another's`() {
        val other = "JM1BM1V70E1234567"
        var log = emptyList<MonitorSnapshot>()
        repeat(MonitorLog.MAX_PER_VEHICLE + 2) { index ->
            log = MonitorLog.merge(log, snapshot(at = index.toLong(), storage = 90 - index))
        }
        log = MonitorLog.merge(log, snapshot(at = 500, vin = other))

        assertEquals(1, MonitorLog.historyFor(log, other).size)
        assertEquals(MonitorLog.MAX_PER_VEHICLE, MonitorLog.historyFor(log, vin).size)
        assertEquals(emptyList<MonitorSnapshot>(), MonitorLog.historyFor(log, vin = null))
    }

    @Test
    fun `the second half of one capture completes it rather than repeating it`() {
        // The test results arrive first and the performance counters a moment later, both
        // describing the same instant. Two records of it would be a step in the series
        // that never happened.
        val partial = snapshot(at = 1_000).copy(performance = null)

        val log = MonitorLog.merge(MonitorLog.merge(emptyList(), partial), snapshot(at = 1_000))

        assertEquals(1, log.size)
        assertNotNull(log.single().performance)
    }

    @Test
    fun `a reconnect that reads the same numbers does not spend the history on one drive`() {
        // A dongle that sleeps and comes back reads the monitors again minutes later. The
        // numbers move over months, so five identical readings of one afternoon would push
        // out the whole series they exist to be compared against.
        var log = MonitorLog.merge(emptyList(), snapshot(at = 1_000))
        repeat(MonitorLog.MAX_PER_VEHICLE + 2) { index ->
            log = MonitorLog.merge(log, snapshot(at = 2_000L + index))
        }

        assertEquals(1, log.size)
        // The latest sighting is what is kept, so the reading is not stale.
        assertEquals(2_000L + MonitorLog.MAX_PER_VEHICLE + 1, log.single().capturedAtMillis)

        // A drive later, with the converter one hundredth of a gram worse off, is a new
        // reading and is kept alongside.
        log = MonitorLog.merge(log, snapshot(at = 900_000, storage = 84))
        assertEquals(2, log.size)
    }

    @Test
    fun `a car with nothing to report is not recorded as having reported nothing`() {
        val empty = MonitorSnapshot(vin = vin, capturedAtMillis = 1)

        assertTrue(empty.isEmpty)
        assertEquals(emptyList<MonitorSnapshot>(), MonitorLog.merge(emptyList(), empty))
        assertEquals("", MonitorLog.recordInto(null, empty))
    }

    @Test
    fun `storage it cannot read is ignored rather than half-decoded`() {
        assertEquals(emptyList<MonitorSnapshot>(), MonitorLog.decode(null))
        assertEquals(emptyList<MonitorSnapshot>(), MonitorLog.decode(""))
        assertEquals(emptyList<MonitorSnapshot>(), MonitorLog.decode("nonsense"))
        assertNull(MonitorLog.decode("$vin;1;;").single().performance)
    }

    @Test
    fun `the demo car cannot write into the history of the real one`() {
        assertNotEquals(
            MonitorLog.storageKey(SessionKind.Real),
            MonitorLog.storageKey(SessionKind.Demo),
        )
        assertEquals("monitor_log", MonitorLog.storageKey(SessionKind.Real))
    }
}
