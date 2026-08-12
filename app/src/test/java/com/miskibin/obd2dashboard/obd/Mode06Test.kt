package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mode 06, which is where the per-cylinder misfire counts and the catalyst's remaining
 * oxygen storage live — two numbers no live PID carries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Mode06Test {

    private fun frames(vararg lines: String) =
        ObdResponseParser.frames(lines.toList(), ObdProtocol.Can11Bit500)

    @Test
    fun `reads one nine-byte record as value, minimum and maximum`() {
        // 06 21: catalyst bank 1, oxygen storage, 0.85 g against a floor of 0.30 g.
        val tests = Mode06.parse(
            frames("7E8 10 0A 46 21 82 1E 00 55", "7E8 21 00 1E 00 FA"),
        )

        assertEquals(1, tests.size)
        val test = tests.single()
        assertEquals(0x21, test.mid)
        assertEquals(0x82, test.tid)
        assertEquals(0x55, test.rawValue)
        assertEquals(MonitorKind.CatalystStorage, test.kind)
        assertEquals(0.85, test.value, 0.0001)
        assertEquals(0.30, test.min, 0.0001)
        assertEquals(2.50, test.max, 0.0001)
        assertTrue(test.passed)
    }

    @Test
    fun `walks several records out of one multi-frame response`() {
        // Both misfire tests for cylinder 2 in one ISO-TP message: the ten-cycle average
        // and the count for the cycle being driven now.
        val tests = Mode06.parse(
            frames(
                "7E8 10 13 46 A3 0B 24 00 0C",
                "7E8 21 00 00 00 0A A3 0C 24",
                "7E8 22 00 03 00 00 00 0A",
            ),
        )

        assertEquals(2, tests.size)
        assertEquals(listOf(0x0B, 0x0C), tests.map(MonitorTest::tid))
        assertEquals(listOf(2, 2), tests.map { it.cylinder })
        assertEquals(12.0, tests[0].value, 0.0001)
        assertEquals(3.0, tests[1].value, 0.0001)
        // Twelve misfires against a ceiling of ten is the ECU's own verdict, not ours.
        assertFalse(tests[0].passed)
        assertTrue(tests[1].passed)
    }

    @Test
    fun `a part record at the end of a frame is dropped rather than padded`() {
        // Eight bytes where nine are needed: a truncated reassembly must not become a
        // reading with a limit invented from whatever followed it.
        val tests = Mode06.parse(frames("7E8 09 46 A2 0B 24 00 00 00 00"))

        assertEquals(emptyList<MonitorTest>(), tests)
    }

    @Test
    fun `misfire counts stay in counts and switch times become seconds`() {
        val misfire = MonitorTest(0xA2, Mode06.TID_MISFIRE_AVERAGE, Mode06.UASID_COUNTS, 7, 0, 10)
        val switch = MonitorTest(0x01, 0x05, 0x04, rawValue = 48, rawMin = 0, rawMax = 100)

        assertEquals(7.0, misfire.value, 0.0001)
        assertEquals("count", misfire.unit)
        assertEquals(0.048, switch.value, 0.0001)
        assertEquals("s", switch.unit)
        assertEquals(MonitorKind.OxygenSensorSwitch, switch.kind)
    }

    @Test
    fun `a test nobody has a name for keeps its raw counts and its verdict`() {
        val unknown = MonitorTest(0x39, 0x91, uasid = 0x67, rawValue = 40, rawMin = 0, rawMax = 30)

        assertEquals(MonitorKind.Other, unknown.kind)
        assertEquals(40.0, unknown.value, 0.0001)
        assertEquals("", unknown.unit)
        assertFalse(unknown.passed)
        assertNull(unknown.cylinder)
    }

    @Test
    fun `headroom says how close a reading is to the limit that would set a code`() {
        val healthy = MonitorTest(0x21, 0x82, 0x1E, rawValue = 85, rawMin = 30, rawMax = 250)
        val tired = MonitorTest(0x21, 0x82, 0x1E, rawValue = 36, rawMin = 30, rawMax = 250)

        assertEquals(0.25, healthy.headroom!!, 0.005)
        assertTrue("a tired converter should be near the floor", tired.headroom!! < 0.05)
        // A window with no width cannot say how far through it anything is.
        assertNull(MonitorTest(0x21, 0x82, 0x1E, 5, 5, 5).headroom)
    }

    @Test
    fun `the monitor scan walks the bitmask chain and drops the markers`() = runTest {
        val transport = FakeElmTransport.scripted(
            script = mapOf(
                // Bit A7 is MID 01, bit D0 is MID 20; the last bit chains into 0620.
                "0600" to "7E8 06 46 00 C0 00 00 01",
                "0620" to "7E8 06 46 20 80 00 00 00",
            ),
        )
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)

        val monitors = client.scanSupportedMonitors()

        assertEquals(setOf(0x01, 0x02, 0x21), monitors)
        // 0640 is never asked for, because 0620 did not set the chain bit.
        assertEquals(0, transport.countOf("0640"))
    }

    @Test
    fun `a monitor answering with somebody else's records contributes none of them`() = runTest {
        val transport = FakeElmTransport.scripted(
            script = mapOf(
                "0600" to "7E8 06 46 00 80 00 00 00",
                // The ECU answers 0601 with a record for monitor 02.
                "0601" to "7E8 10 0A 46 02 05 04 00 30\r7E8 21 00 00 00 64",
            ),
        )
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)

        assertEquals(emptyList<MonitorTest>(), client.readMonitorTests().tests)
    }
}
