package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `0908`, which answers the question a clean code list cannot: has each self-test actually
 * run, or has it merely never had the chance to fail?
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceTrackingTest {

    private fun frames(vararg lines: String) =
        ObdResponseParser.frames(lines.toList(), ObdProtocol.Can11Bit500)

    /** The bytes of a `4908` response carrying [counters], as ISO-TP text lines. */
    private fun response(counters: List<Int>): List<String> {
        val payload = listOf(0x49, 0x08, counters.size) +
            counters.flatMap { listOf(it shr 8, it and 0xFF) }
        val lines = mutableListOf(
            (listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(6))
                .joinToString(" ") { "%02X".format(it) },
        )
        var index = 6
        var sequence = 1
        while (index < payload.size) {
            val end = minOf(index + 7, payload.size)
            lines += (listOf(0x20 or sequence) + payload.subList(index, end))
                .joinToString(" ") { "%02X".format(it) }
            index = end
            sequence++
        }
        return lines.map { "7E8 $it" }
    }

    @Test
    fun `reads the two engine-wide counters and a pair per monitor, in order`() {
        val counters = listOf(210, 250, 30, 45, 0, 0, 40, 44, 0, 0, 25, 41, 0, 0, 6, 38, 12, 44, 0, 0)

        val tracking = PerformanceTrackingDecoder.parse(frames(*response(counters).toTypedArray()))!!

        assertEquals(210, tracking.obdConditions)
        assertEquals(250, tracking.ignitionCycles)
        assertEquals(PerformanceTrackingDecoder.ORDER, tracking.monitors.map { it.monitor })
        val evap = tracking.monitors.single { it.monitor == TrackedMonitor.Evaporative }
        assertEquals(6, evap.completions)
        assertEquals(38, evap.conditions)
        assertEquals(6.0 / 38.0, evap.ratio!!, 0.0001)
        assertFalse(evap.neverRun)
    }

    @Test
    fun `a monitor that never ran is said to have never run, not to have failed`() {
        val counters = listOf(210, 250, 0, 0)

        val tracking = PerformanceTrackingDecoder.parse(frames(*response(counters).toTypedArray()))!!
        val catalyst = tracking.monitors.single()

        assertEquals(TrackedMonitor.CatalystBank1, catalyst.monitor)
        assertTrue(catalyst.neverRun)
        // No conditions encountered means no ratio at all: nought per cent would be a
        // verdict on a test that has not been attempted.
        assertNull(catalyst.ratio)
    }

    @Test
    fun `an ECU that stops early contributes what it sent and nothing more`() {
        // Sixteen items rather than twenty, which the standard explicitly allows.
        val counters = List(16) { it * 3 }

        val tracking = PerformanceTrackingDecoder.parse(frames(*response(counters).toTypedArray()))!!

        assertEquals(7, tracking.monitors.size)
        assertTrue(tracking.monitors.none { it.monitor == TrackedMonitor.SecondaryOxygenSensorBank2 })
    }

    @Test
    fun `an answer with no item count is read as counters all the way down`() {
        // Some ECUs omit the leading count. Reading it as one anyway would shift every
        // value by a byte and turn a plausible table into nonsense.
        val tracking = PerformanceTrackingDecoder.parse(
            frames("7E8 07 49 08 00 D2 00 FA"),
        )!!

        assertEquals(210, tracking.obdConditions)
        assertEquals(250, tracking.ignitionCycles)
        assertEquals(emptyList<MonitorCounts>(), tracking.monitors)
    }

    @Test
    fun `a car that does not track performance reports none rather than an empty table`() = runTest {
        val transport = FakeElmTransport.scripted(script = mapOf("0908" to "NO DATA"))
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)

        assertNull(client.readPerformanceTracking())
    }
}
