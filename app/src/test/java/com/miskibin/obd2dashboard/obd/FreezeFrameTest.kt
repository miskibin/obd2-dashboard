package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FreezeFrameTest {

    private fun newClient(
        script: Map<String, String>,
        scope: CoroutineScope,
        protocol: ObdProtocol = ObdProtocol.Can11Bit500,
    ): Pair<Obd2Client, FakeElmTransport> {
        val transport = FakeElmTransport.scripted(script = script)
        return Obd2Client(ElmSession(transport, scope), protocol) to transport
    }

    /** `42 <pid> <frame> <data>` — the frame number sits between the PID and its bytes. */
    @Test
    fun `decodes the frozen values and the code that caused them`() = runTest {
        val (client, transport) = newClient(
            mapOf(
                "020200" to "7E8 05 42 02 00 04 20",
                "020C00" to "7E8 05 42 0C 00 20 D0",
                "020D00" to "7E8 04 42 0D 00 54",
                "020400" to "7E8 04 42 04 00 70",
                "020500" to "7E8 04 42 05 00 84",
                "020B00" to "7E8 04 42 0B 00 30",
                "020F00" to "7E8 04 42 0F 00 4A",
                "020600" to "7E8 04 42 06 00 85",
                "020700" to "7E8 04 42 07 00 8D",
            ),
            backgroundScope,
        )

        val frame = client.readFreezeFrame()

        assertEquals("P0420", frame.triggerCode)
        assertEquals(2100.0, frame.values[Pids.ENGINE_RPM]!!, 0.001)
        assertEquals(84.0, frame.values[Pids.VEHICLE_SPEED]!!, 0.001)
        assertEquals(92.0, frame.values[Pids.COOLANT_TEMP]!!, 0.001)
        assertEquals(48.0, frame.values[Pids.INTAKE_MAP]!!, 0.001)
        assertEquals(34.0, frame.values[Pids.INTAKE_AIR_TEMP]!!, 0.001)
        assertEquals(43.9, frame.values[Pids.ENGINE_LOAD]!!, 0.1)
        assertEquals(3.9, frame.values[Pids.SHORT_FUEL_TRIM_1]!!, 0.1)
        assertEquals(10.2, frame.values[Pids.LONG_FUEL_TRIM_1]!!, 0.1)
        assertEquals(FreezeFrames.pids.size, frame.values.size)
        // One request per parameter, all on frame 00.
        assertTrue(transport.commands.all { it.startsWith("02") && it.endsWith("00") })
    }

    @Test
    fun `NO DATA everywhere leaves an empty frame rather than zeroes`() = runTest {
        val (client, _) = newClient(
            FreezeFrames.pids.associate { "02%02X00".format(it.id) to "NO DATA" } +
                mapOf("020200" to "NO DATA"),
            backgroundScope,
        )

        val frame = client.readFreezeFrame()

        assertTrue(frame.isEmpty)
        assertNull(frame.triggerCode)
        assertEquals(emptyMap<Int, Double>(), frame.values)
    }

    @Test
    fun `a car with no stored code answers the values but not the trigger`() = runTest {
        val (client, _) = newClient(
            mapOf(
                "020200" to "NO DATA",
                "020C00" to "7E8 05 42 0C 00 20 D0",
            ),
            backgroundScope,
        )

        val frame = client.readFreezeFrame()

        assertNull(frame.triggerCode)
        assertEquals(1, frame.values.size)
        assertTrue(!frame.isEmpty)
    }

    /** A truncated reply is worse than none, so a short payload is dropped. */
    @Test
    fun `ignores a reply that is missing data bytes`() = runTest {
        val (client, _) = newClient(mapOf("020C00" to "7E8 03 42 0C 00"), backgroundScope)

        assertTrue(client.readFreezeFrame().isEmpty)
    }
}
