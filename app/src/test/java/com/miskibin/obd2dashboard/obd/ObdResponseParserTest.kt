package com.miskibin.obd2dashboard.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdResponseParserTest {

    private fun value(pid: Int, lines: List<String>, protocol: ObdProtocol): Double? {
        val frames = ObdResponseParser.frames(lines, protocol)
        val data = ObdResponseParser.values(frames, MODE_CURRENT_DATA, listOf(pid))[pid] ?: return null
        return Pids[pid]!!.decode(data)
    }

    @Test
    fun `parses a headerless response`() {
        assertEquals(1726.0, value(0x0C, listOf("410C1AF8"), ObdProtocol.Automatic)!!, 0.001)
    }

    @Test
    fun `strips an 11 bit CAN header`() {
        val lines = listOf("7E803410C1AF8")
        val frames = ObdResponseParser.frames(lines, ObdProtocol.Can11Bit500)

        assertEquals("7E8", frames.single().header)
        assertEquals(1726.0, value(0x0C, lines, ObdProtocol.Can11Bit500)!!, 0.001)
    }

    @Test
    fun `strips a 29 bit CAN header`() {
        val lines = listOf("18DAF11003410C1AF8")
        val frames = ObdResponseParser.frames(lines, ObdProtocol.Can29Bit500)

        assertEquals("18DAF110", frames.single().header)
        assertEquals(1726.0, value(0x0C, lines, ObdProtocol.Can29Bit500)!!, 0.001)
    }

    @Test
    fun `parses a J1850 line whose header length differs`() {
        val lines = listOf("48 6B 10 41 0C 1A F8 A3")

        assertEquals(1726.0, value(0x0C, lines, ObdProtocol.J1850Vpw)!!, 0.001)
    }

    @Test
    fun `detects an 11 bit header without knowing the protocol`() {
        assertEquals(1726.0, value(0x0C, listOf("7E803410C1AF8"), ObdProtocol.Automatic)!!, 0.001)
    }

    @Test
    fun `keeps one frame per responding ECU`() {
        val frames = ObdResponseParser.frames(
            listOf("7E8 06 41 00 BE 3E B8 11", "7EA 06 41 00 80 00 00 01"),
            ObdProtocol.Can11Bit500,
        )

        assertEquals(listOf("7E8", "7EA"), frames.map { it.header })
    }

    @Test
    fun `parses a multi PID batch by id and not by position`() {
        val lines = listOf("7E8 10 08 41 0C 1A F8 0D 41", "7E8 21 05 5A")
        val frames = ObdResponseParser.frames(lines, ObdProtocol.Can11Bit500)
        val values = ObdResponseParser.values(frames, MODE_CURRENT_DATA, listOf(0x0C, 0x0D, 0x05))

        assertEquals(1726.0, Pids[0x0C]!!.decode(values.getValue(0x0C)), 0.001)
        assertEquals(65.0, Pids[0x0D]!!.decode(values.getValue(0x0D)), 0.001)
        assertEquals(50.0, Pids[0x05]!!.decode(values.getValue(0x05)), 0.001)
    }

    @Test
    fun `skips PIDs the ECU left out of a batch`() {
        val frames = ObdResponseParser.frames(listOf("410C1AF8054A"), ObdProtocol.Automatic)
        val values = ObdResponseParser.values(frames, MODE_CURRENT_DATA, listOf(0x0C, 0x0D, 0x05))

        assertEquals(setOf(0x0C, 0x05), values.keys)
    }

    @Test
    fun `decodes the supported PID bitmask`() {
        val supported = ObdResponseParser.supportedPids(
            ObdResponseParser.frames(listOf("41 00 BE 3E B8 11"), ObdProtocol.Automatic),
            base = 0x00,
        )

        assertEquals(
            setOf(
                0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E,
                0x0F, 0x11, 0x13, 0x14, 0x15, 0x1C, 0x20,
            ),
            supported,
        )
    }

    @Test
    fun `ors the bitmasks of every ECU together`() {
        val supported = ObdResponseParser.supportedPids(
            ObdResponseParser.frames(
                listOf("7E8 06 41 00 80 00 00 00", "7EA 06 41 00 40 00 00 00"),
                ObdProtocol.Can11Bit500,
            ),
            base = 0x00,
        )

        assertEquals(setOf(0x01, 0x02), supported)
    }

    @Test
    fun `chains into the next block only while its bit is set`() {
        val withNext = ObdResponseParser.decodeSupportMask(0x00, intArrayOf(0xBE, 0x3E, 0xB8, 0x11))
        val withoutNext = ObdResponseParser.decodeSupportMask(0x00, intArrayOf(0xBE, 0x3E, 0xB8, 0x10))

        assertEquals(0x20, ObdResponseParser.nextSupportBlock(0x00, withNext))
        assertNull(ObdResponseParser.nextSupportBlock(0x00, withoutNext))
    }

    @Test
    fun `reassembles an ISO-TP VIN from length and sequence lines`() {
        val frames = ObdResponseParser.frames(
            listOf(
                "014",
                "0: 49 02 01 31 44 34",
                "1: 47 50 30 30 52 35 35",
                "2: 42 31 32 33 34 35 36",
            ),
            ObdProtocol.Can11Bit500,
        )

        assertTrue(frames.single().multiFrame)
        assertEquals(20, frames.single().data.size)
        assertEquals("1D4GP00R55B123456", VinDecoder.decode(frames))
    }

    @Test
    fun `reassembles an ISO-TP VIN from raw PCI frames`() {
        val frames = ObdResponseParser.frames(
            listOf(
                "7E8 10 14 49 02 01 31 44 34",
                "7E8 21 47 50 30 30 52 35 35",
                "7E8 22 42 31 32 33 34 35 36",
            ),
            ObdProtocol.Can11Bit500,
        )

        assertEquals("7E8", frames.single().header)
        assertEquals("1D4GP00R55B123456", VinDecoder.decode(frames))
    }

    @Test
    fun `reassembles a VIN sent as separate non-CAN lines`() {
        val frames = ObdResponseParser.frames(
            listOf(
                "48 6B 10 49 02 01 00 00 00 31",
                "48 6B 10 49 02 02 44 34 47 50",
                "48 6B 10 49 02 03 30 30 52 35",
                "48 6B 10 49 02 04 35 42 31 32",
                "48 6B 10 49 02 05 33 34 35 36",
            ),
            ObdProtocol.Iso9141,
        )

        assertEquals("1D4GP00R55B123456", VinDecoder.decode(frames))
    }
}
