package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Obd2ClientTest {

    private fun newClient(
        script: Map<String, String>,
        scope: CoroutineScope,
        protocol: ObdProtocol = ObdProtocol.Can11Bit500,
    ): Pair<Obd2Client, FakeElmTransport> {
        val transport = FakeElmTransport.scripted(script = script)
        return Obd2Client(ElmSession(transport, scope), protocol) to transport
    }

    @Test
    fun `walks the supported PID blocks while the chain bit is set`() = runTest {
        val (client, transport) = newClient(
            mapOf(
                "0100" to "7E8 06 41 00 BE 3E B8 11",
                "0120" to "7E8 06 41 20 80 00 00 00",
            ),
            backgroundScope,
        )

        val supported = client.scanSupportedPids()

        assertTrue(0x0C in supported)
        assertTrue(0x21 in supported)
        assertEquals(listOf("0100", "0120"), transport.commands)
    }

    @Test
    fun `learns the response line count and replays it as the count digit`() = runTest {
        val (client, transport) = newClient(
            mapOf("010C" to "7E8 03 41 0C 1A F8"),
            backgroundScope,
        )

        val first = client.readPid(Pids[0x0C]!!)
        val second = client.readPid(Pids[0x0C]!!)

        assertEquals(1726.0, (first as PidRead.Value).value, 0.001)
        assertEquals(1726.0, (second as PidRead.Value).value, 0.001)
        assertEquals(listOf("010C", "010C1"), transport.commands)
        assertEquals(1, client.lineCountOf(0x0C))
    }

    @Test
    fun `reports NO DATA as an unsupported PID`() = runTest {
        val (client, _) = newClient(mapOf("015C" to "NO DATA"), backgroundScope)

        assertEquals(PidRead.Unsupported, client.readPid(Pids[0x5C]!!))
    }

    @Test
    fun `surfaces adapter failures that need a reinit`() = runTest {
        val (client, _) = newClient(mapOf("010C" to "ERR94"), backgroundScope)

        val read = client.readPid(Pids[0x0C]!!)

        assertTrue(read is PidRead.Failed)
        assertTrue((read as PidRead.Failed).error.requiresReinit)
    }

    @Test
    fun `enables batching only when every PID comes back`() = runTest {
        val pids = listOf(Pids[0x0C]!!, Pids[0x0D]!!, Pids[0x05]!!)
        val (complete, _) = newClient(
            mapOf("010C0D05" to "7E8 10 08 41 0C 1A F8 0D 41\r7E8 21 05 5A"),
            backgroundScope,
        )
        val (truncating, _) = newClient(
            mapOf("010C0D05" to "7E8 04 41 0C 1A F8"),
            backgroundScope,
        )

        assertTrue(complete.probeBatching(pids))
        assertFalse(truncating.probeBatching(pids))
    }

    @Test
    fun `does not batch on non CAN protocols`() = runTest {
        val (client, transport) = newClient(
            mapOf("010C0D" to "41 0C 1A F8 0D 41"),
            backgroundScope,
            ObdProtocol.Iso9141,
        )

        assertFalse(client.probeBatching(listOf(Pids[0x0C]!!, Pids[0x0D]!!)))
        assertTrue(transport.commands.isEmpty())
    }

    @Test
    fun `reads all three DTC stores plus the monitor status`() = runTest {
        val (client, _) = newClient(
            mapOf(
                "03" to "7E8 06 43 02 01 33 04 20",
                "07" to "7E8 04 47 01 01 71",
                "0A" to "7E8 02 4A 00",
                "0101" to "7E8 06 41 01 82 07 65 04",
            ),
            backgroundScope,
        )

        val diagnostics = client.readDiagnostics()

        assertEquals(listOf("P0133", "P0420"), diagnostics.stored.map(Dtc::code))
        assertEquals(listOf("P0171"), diagnostics.pending.map(Dtc::code))
        assertTrue(diagnostics.permanent.isEmpty())
        assertTrue(diagnostics.monitorStatus!!.milOn)
        assertEquals(2, diagnostics.monitorStatus!!.dtcCount)
    }

    @Test
    fun `clearing raises the timeout and accepts only a 44`() = runTest {
        val (client, transport) = newClient(mapOf("04" to "7E8 01 44"), backgroundScope)

        assertTrue(client.clearDtcs())
        assertEquals(listOf("ATSTFF", "04", "ATST32"), transport.commands)
    }

    @Test
    fun `a clear that is ignored by the ECU is not a success`() = runTest {
        val (client, _) = newClient(mapOf("04" to "NO DATA"), backgroundScope)

        assertFalse(client.clearDtcs())
    }

    @Test
    fun `reads the VIN through ISO-TP`() = runTest {
        val (client, _) = newClient(
            mapOf(
                "0902" to "7E8 10 14 49 02 01 31 44 34\r" +
                    "7E8 21 47 50 30 30 52 35 35\r" +
                    "7E8 22 42 31 32 33 34 35 36",
            ),
            backgroundScope,
        )

        assertEquals("1D4GP00R55B123456", client.readVin())
    }

    @Test
    fun `reads the adapter voltage`() = runTest {
        val (client, _) = newClient(mapOf("ATRV" to "12.6V"), backgroundScope)

        assertEquals(12.6, client.readVoltage()!!, 0.001)
    }
}
