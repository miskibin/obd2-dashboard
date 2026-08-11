package com.miskibin.obd2dashboard.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDecodingTest {

    private fun codes(lines: List<String>, kind: DtcKind, protocol: ObdProtocol): List<String> =
        DtcDecoder.parse(ObdResponseParser.frames(lines, protocol), kind, protocol.isCan)
            .map(Dtc::code)

    @Test
    fun `decodes the system letter and digits`() {
        assertEquals("P0133", DtcDecoder.decode(0x01, 0x33))
        assertEquals("P0420", DtcDecoder.decode(0x04, 0x20))
        assertEquals("C0301", DtcDecoder.decode(0x43, 0x01))
        assertEquals("B0130", DtcDecoder.decode(0x81, 0x30))
        assertEquals("U0100", DtcDecoder.decode(0xC1, 0x00))
        assertEquals("P1A2B", DtcDecoder.decode(0x1A, 0x2B))
    }

    @Test
    fun `treats an all zero pair as padding`() = assertNull(DtcDecoder.decode(0x00, 0x00))

    @Test
    fun `reads the CAN count byte`() {
        val found = codes(listOf("7E8 06 43 02 01 33 04 20"), DtcKind.Stored, ObdProtocol.Can11Bit500)

        assertEquals(listOf("P0133", "P0420"), found)
    }

    @Test
    fun `ignores padding on protocols without a count byte`() {
        val found = codes(
            listOf("48 6B 10 43 01 33 04 20 00 00"),
            DtcKind.Stored,
            ObdProtocol.Iso9141,
        )

        assertEquals(listOf("P0133", "P0420"), found)
    }

    @Test
    fun `a zero count means no stored codes`() {
        assertTrue(
            codes(listOf("7E8 02 43 00"), DtcKind.Stored, ObdProtocol.Can11Bit500).isEmpty(),
        )
    }

    @Test
    fun `truncates the pairs to the reported count`() {
        val found = codes(
            listOf("7E8 06 43 01 01 33 00 00 00 00"),
            DtcKind.Stored,
            ObdProtocol.Can11Bit500,
        )

        assertEquals(listOf("P0133"), found)
    }

    @Test
    fun `tags each code with the ECU that reported it`() {
        val frames = ObdResponseParser.frames(
            listOf("7E8 04 43 01 04 20", "7EA 04 43 01 C1 00"),
            ObdProtocol.Can11Bit500,
        )
        val found = DtcDecoder.parse(frames, DtcKind.Stored, isCan = true)

        assertEquals(listOf("P0420" to "7E8", "U0100" to "7EA"), found.map { it.code to it.ecu })
    }

    @Test
    fun `reads pending and permanent codes from their own modes`() {
        assertEquals(
            listOf("P0133"),
            codes(listOf("7E8 04 47 01 01 33"), DtcKind.Pending, ObdProtocol.Can11Bit500),
        )
        assertEquals(
            listOf("P0420"),
            codes(listOf("7E8 04 4A 01 04 20"), DtcKind.Permanent, ObdProtocol.Can11Bit500),
        )
    }

    @Test
    fun `reassembles a multi frame DTC list`() {
        val found = codes(
            listOf("7E8 10 0C 43 05 01 33 04 20", "7E8 21 C1 00 01 71 00 03"),
            DtcKind.Stored,
            ObdProtocol.Can11Bit500,
        )

        assertEquals(listOf("P0133", "P0420", "U0100", "P0171", "P0003"), found)
    }

    @Test
    fun `only a 44 counts as a successful clear`() {
        assertTrue(DtcDecoder.isClearAccepted(listOf("44")))
        assertFalse(DtcDecoder.isClearAccepted(listOf("43 00")))
        assertFalse(DtcDecoder.isClearAccepted(emptyList()))
    }

    @Test
    fun `decodes MIL state and the confirmed code count`() {
        val frames = ObdResponseParser.frames(listOf("41 01 82 07 65 04"), ObdProtocol.Automatic)
        val data = ObdResponseParser.values(frames, MODE_CURRENT_DATA, listOf(0x01)) { 4 }
            .getValue(0x01)
        val status = DtcDecoder.parseMonitorStatus(data)

        assertTrue(status.milOn)
        assertEquals(2, status.dtcCount)
        assertEquals(listOf(0x07, 0x65, 0x04), status.readinessBytes)
    }

    @Test
    fun `a clear lamp means no confirmed codes`() {
        val status = DtcDecoder.parseMonitorStatus(intArrayOf(0x00, 0x07, 0x65, 0x04))

        assertFalse(status.milOn)
        assertEquals(0, status.dtcCount)
    }
}
