package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manufacturer-specific read path: which cars get asked, what the answers decode to,
 * and what each kind of refusal is taken to mean.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtendedPidTest {

    private fun pid(id: String) = ExtendedPids[id] ?: error("no extended parameter $id")

    private fun decode(id: String, vararg bytes: Int) = pid(id).decode(bytes)

    @Test
    fun `oil pressure is signed kPa and 0xFFFF means the sensor said nothing`() {
        assertEquals(380.0, decode(ExtendedPids.OIL_PRESSURE, 0x01, 0x7C), 0.001)
        assertEquals(0.0, decode(ExtendedPids.OIL_PRESSURE, 0x00, 0x00), 0.001)
        // Negative pressure is nonsense but the encoding is signed, so a sensor that
        // reports below zero must not read as 65 000 kPa.
        assertEquals(-2.0, decode(ExtendedPids.OIL_PRESSURE, 0xFF, 0xFE), 0.001)
        assertFalse(decode(ExtendedPids.OIL_PRESSURE, 0xFF, 0xFF).isFinite())
    }

    @Test
    fun `oil temperature is hundredths of a degree above minus forty`() {
        // 0x3106 = 12550 → 125.50 − 40 = 85.5 °C
        assertEquals(85.5, decode(ExtendedPids.OIL_TEMPERATURE, 0x31, 0x06), 0.001)
        assertEquals(-40.0, decode(ExtendedPids.OIL_TEMPERATURE, 0x00, 0x00), 0.001)
    }

    @Test
    fun `transmission fluid temperature is gated to a range a gearbox can be in`() {
        // 0x1F40 = 8000 → 8000 / 80 = 100 °C
        assertEquals(100.0, decode(ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE, 0x1F, 0x40), 0.001)
        // The published lists disagree about the divisor; a car using the other one would
        // read 800 °C here, and 800 °C of gearbox oil is a reading to withhold, not show.
        val absurd = decode(ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE, 0xFF, 0x00)
        assertFalse("an out-of-range value must not be published", absurd.isFinite())
    }

    @Test
    fun `both tyre pressure encodings land on the same bar figure`() {
        val bp = ExtendedPids.entries.first {
            it.id == ExtendedPids.tyrePressureId("fl") && it.header == "726"
        }
        val bm = ExtendedPids.entries.first {
            it.id == ExtendedPids.tyrePressureId("fl") && it.header == "720"
        }

        // 167 fifths of a psi is 33.4 psi is 2.30 bar; 168 counts of the older encoding is
        // 2.31 bar. Two generations, two formulas, one unit on the tile.
        assertEquals(2.30, bp.decode(intArrayOf(167)), 0.02)
        assertEquals(2.31, bm.decode(intArrayOf(168)), 0.02)
        assertEquals("bar", bp.unit)
        assertEquals(bp.unit, bm.unit)
    }

    @Test
    fun `tyre temperature carries a fifty degree offset rather than the usual forty`() {
        assertEquals(24.0, decode(ExtendedPids.tyreTemperatureId("rr"), 74), 0.001)
    }

    @Test
    fun `a car the table does not know is asked nothing at all`() {
        val golf = ExtendedVehicle(vin = "WVWZZZ1KZ8W123456", modelYear = 2008)

        assertEquals(emptyList<ExtendedPid>(), ExtendedPids.candidatesFor(golf))
        assertEquals(emptyList<ExtendedPid>(), ExtendedPids.candidatesFor(ExtendedVehicle()))
    }

    @Test
    fun `the model year picks which module the tyre pressures are asked of`() {
        val bp = ExtendedVehicle(vin = "JM1BPBLM7K1234567", modelYear = 2019)
        val bm = ExtendedVehicle(vin = "JM1BM1V70E1234567", modelYear = 2014)

        val bpTyres = ExtendedPids.candidatesFor(bp).filter { it.unit == "bar" }
        val bmTyres = ExtendedPids.candidatesFor(bm).filter { it.unit == "bar" }

        assertEquals(setOf("726"), bpTyres.map(ExtendedPid::header).toSet())
        assertEquals(setOf("720"), bmTyres.map(ExtendedPid::header).toSet())
        assertEquals(4, bpTyres.size)
        // Whichever generation it is, no reading is ever offered twice.
        listOf(bp, bm).forEach { vehicle ->
            val candidates = ExtendedPids.candidatesFor(vehicle)
            assertEquals(candidates.size, candidates.distinctBy(ExtendedPid::id).size)
        }
    }

    @Test
    fun `a year the VIN did not settle leaves both tyre blocks alone`() {
        // The engine parameters apply to any Mazda; the tyre pressures need the generation,
        // and guessing it would mean asking a module that may not be there.
        val candidates = ExtendedPids.candidatesFor(ExtendedVehicle(vin = "JM1BPBLM7K1234567"))

        assertEquals(
            setOf(
                ExtendedPids.OIL_PRESSURE,
                ExtendedPids.OIL_TEMPERATURE,
                ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE,
                ExtendedPids.GEAR,
            ),
            candidates.map(ExtendedPid::id).toSet(),
        )
    }

    @Test
    fun `the response marker is the service plus forty, wherever it is asked for`() {
        assertTrue(ExtendedPids.entries.all { it.responseMarker == it.service + 0x40 })
        assertEquals(0x62, pid(ExtendedPids.OIL_PRESSURE).responseMarker)
        assertEquals("220415", pid(ExtendedPids.OIL_PRESSURE).request)
        assertEquals("221E1C", pid(ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE).request)
    }

    /** One probe of the oil-pressure identifier against a car that replies [reply]. */
    private suspend fun probe(scope: CoroutineScope, reply: String): ExtendedProbe {
        val oilPressure = pid(ExtendedPids.OIL_PRESSURE)
        val transport = FakeElmTransport.scripted(script = mapOf(oilPressure.request to reply))
        val client = Obd2Client(ElmSession(transport, scope), ObdProtocol.Can11Bit500)
        return client.probeExtended(oilPressure)
    }

    @Test
    fun `a positive response is supported and the three permanent refusals are not`() = runTest {
        assertEquals(ExtendedProbe.Supported, probe(backgroundScope, "7E8 05 62 04 15 01 7C"))
        // requestOutOfRange, subFunctionNotSupported, serviceNotSupported: this car will
        // never answer, so it is written off rather than retried every connection.
        assertEquals(ExtendedProbe.Absent, probe(backgroundScope, "7E8 03 7F 22 31"))
        assertEquals(ExtendedProbe.Absent, probe(backgroundScope, "7E8 03 7F 22 12"))
        assertEquals(ExtendedProbe.Absent, probe(backgroundScope, "7E8 03 7F 22 11"))
    }

    @Test
    fun `busy and wrong-conditions are worth retrying, silence is worth retrying later`() = runTest {
        assertEquals(ExtendedProbe.Retry, probe(backgroundScope, "7E8 03 7F 22 21"))
        assertEquals(ExtendedProbe.Retry, probe(backgroundScope, "7E8 03 7F 22 22"))
        // NO DATA is not a refusal — a module can be asleep — so nothing is remembered.
        assertEquals(ExtendedProbe.Unknown, probe(backgroundScope, "NO DATA"))
    }

    @Test
    fun `a response-pending stall is waited out and the real answer read`() = runTest {
        // The ECU says "still working" and prints a prompt, then answers properly. Taking
        // the first prompt as the end of the exchange would write the parameter off and
        // leave the real answer to be misread as the reply to the next request.
        val transport = FakeElmTransport { command ->
            if (command.startsWith("220415")) {
                "7E8 03 7F 22 78" + FakeElmTransport.PROMPT + "7E8 05 62 04 15 01 7C" +
                    FakeElmTransport.PROMPT
            } else {
                "?" + FakeElmTransport.PROMPT
            }
        }
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)

        val read = client.readExtended(pid(ExtendedPids.OIL_PRESSURE))

        assertTrue("read was $read", read is ExtendedRead.Value)
        assertEquals(380.0, (read as ExtendedRead.Value).value, 0.001)
    }

    @Test
    fun `entering a module sets both headers and always puts them back`() = runTest {
        val transport = FakeElmTransport.scripted(
            script = mapOf("22D922" to "72E 04 62 D9 22 A7", "ATSH726" to "OK", "ATCRA72E" to "OK"),
        )
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)
        val tyre = ExtendedPids.entries.first {
            it.id == ExtendedPids.tyrePressureId("fl") && it.header == "726"
        }

        val read = client.withModule(tyre.header, tyre.receiveHeader) { client.readExtended(tyre) }

        assertTrue(read is ExtendedRead.Value)
        assertEquals(
            listOf("ATSH726", "ATCRA72E", "22D922", "ATCRA", "ATSH7DF"),
            transport.commands,
        )
    }

    @Test
    fun `only eleven-bit CAN can be pointed at one module`() = runTest {
        val transport = FakeElmTransport.scripted(script = emptyMap())
        val session = ElmSession(transport, backgroundScope)

        // `ATSH 7DF` is the eleven-bit functional address and nothing else, so on any
        // other protocol there is no default header to put back afterwards.
        assertTrue(Obd2Client(session, ObdProtocol.Can11Bit500).canAddressModules)
        assertFalse(Obd2Client(session, ObdProtocol.Can29Bit500).canAddressModules)
        assertFalse(Obd2Client(session, ObdProtocol.Iso9141).canAddressModules)
        assertFalse(Obd2Client(session, ObdProtocol.Automatic).canAddressModules)
    }

    @Test
    fun `a module with a long answer gets flow control, and gives it back`() = runTest {
        val transport = FakeElmTransport.scripted(
            script = mapOf(
                "ATSH70B" to "OK",
                "ATFCSH70B" to "OK",
                "ATFCSD300000" to "OK",
                "ATFCSM1" to "OK",
                "ATFCSM0" to "OK",
                "2218A1" to "70B 10 0A 62 18 A1 00 00 00\r70B 21 00 00 5C 00",
            ),
        )
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)
        val tyre = ExtendedPids.entries.first { it.header == "70B" && it.did == 0x18A1 }

        val read = client.withModule(tyre.header, tyre.receiveHeader, tyre.flowControl) {
            client.readExtended(tyre)
        }

        // The pressure is the sixth byte of the answer, i.e. in the consecutive frame that
        // only arrives because something answered the first one.
        assertTrue("read was $read", read is ExtendedRead.Value)
        assertEquals(2.30, (read as ExtendedRead.Value).value, 0.01)
        assertEquals(
            listOf(
                "ATSH70B", "ATFCSH70B", "ATFCSD300000", "ATFCSM1",
                "2218A1",
                "ATFCSM0", "ATSH7DF",
            ),
            transport.commands,
        )
    }

    @Test
    fun `a block read asks with one identifier byte and finds its value inside the block`() =
        runTest {
            // Toyota's `21 51`: one byte of identifier, an answer forty bytes long, and the
            // oil temperature at the tenth of them.
            val transport = FakeElmTransport.scripted(
                script = mapOf(
                    "ATSH7E0" to "OK",
                    "ATCRA7E8" to "OK",
                    "2151" to "7E8 10 11 61 51 00 00 00\r7E8 21 00 00 00 00 00 00\r7E8 22 82 8C 00 00 00",
                ),
            )
            val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)
            val oil = ExtendedPids.entries.first {
                it.service == MODE_READ_LOCAL_ID && it.did == 0x51
            }

            val read = client.withModule(oil.header, oil.receiveHeader) { client.readExtended(oil) }

            assertTrue("read was $read", read is ExtendedRead.Value)
            assertEquals(90.0, (read as ExtendedRead.Value).value, 0.001)
            assertTrue("2151" in transport.commands)
        }

    @Test
    fun `the restore happens even when the read throws`() = runTest {
        val transport = FakeElmTransport.scripted(script = emptyMap())
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)

        runCatching { client.withModule("7E1", null) { error("boom") } }

        // An ATSH left pointing at a gearbox is every Mode 01 request going to the wrong
        // ECU for the rest of the drive.
        assertEquals(listOf("ATSH7E1", "ATSH7DF"), transport.commands)
    }
}
