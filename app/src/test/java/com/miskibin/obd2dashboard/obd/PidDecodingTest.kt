package com.miskibin.obd2dashboard.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidDecodingTest {

    private fun decode(response: String, pid: Int): Double {
        val frames = ObdResponseParser.frames(listOf(response), ObdProtocol.Automatic)
        val data = ObdResponseParser.values(frames, MODE_CURRENT_DATA, listOf(pid)).getValue(pid)
        return Pids[pid]!!.decode(data)
    }

    @Test
    fun `engine rpm`() = assertEquals(1726.0, decode("410C1AF8", 0x0C), 0.001)

    @Test
    fun `vehicle speed`() = assertEquals(65.0, decode("410D41", 0x0D), 0.001)

    @Test
    fun `coolant temperature`() = assertEquals(50.0, decode("41055A", 0x05), 0.001)

    @Test
    fun `maf air flow`() {
        assertEquals(15.0, decode("411005DC", 0x10), 0.001)
        assertEquals(120.0, decode("41102EE0", 0x10), 0.001)
    }

    @Test
    fun `fuel tank level`() = assertEquals(50.196, decode("412F80", 0x2F), 0.001)

    @Test
    fun `engine load`() = assertEquals(50.196, decode("410480", 0x04), 0.001)

    @Test
    fun `throttle position`() = assertEquals(100.0, decode("4111FF", 0x11), 0.001)

    @Test
    fun `fuel trims are centred on zero`() {
        assertEquals(0.0, decode("410680", 0x06), 0.001)
        assertEquals(-100.0, decode("410700", 0x07), 0.001)
        assertEquals(7.8125, decode("41068A", 0x06), 0.001)
    }

    @Test
    fun `timing advance is signed`() = assertEquals(-14.0, decode("410E64", 0x0E), 0.001)

    @Test
    fun `intake air temperature`() = assertEquals(-4.0, decode("410F24", 0x0F), 0.001)

    @Test
    fun `manifold and barometric pressure are raw kPa`() {
        assertEquals(150.0, decode("410B96", 0x0B), 0.001)
        assertEquals(101.0, decode("413365", 0x33), 0.001)
    }

    @Test
    fun `run time and distances are plain words`() {
        assertEquals(3600.0, decode("411F0E10", 0x1F), 0.001)
        assertEquals(1234.0, decode("412104D2", 0x21), 0.001)
        assertEquals(1234.0, decode("413104D2", 0x31), 0.001)
    }

    @Test
    fun `control module voltage`() = assertEquals(13.982, decode("4142369E", 0x42), 0.001)

    @Test
    fun `ambient and oil temperature`() {
        assertEquals(-1.0, decode("414627", 0x46), 0.001)
        assertEquals(90.0, decode("415C82", 0x5C), 0.001)
    }

    @Test
    fun `engine fuel rate`() = assertEquals(12.5, decode("415E00FA", 0x5E), 0.001)

    @Test
    fun `evap vapour pressure is two's complement`() =
        assertEquals(-1.0, decode("4132FFFC", 0x32), 0.001)

    @Test
    fun `odometer spans four bytes`() =
        assertEquals(1234567.8, decode("41A600BC614E", 0xA6), 0.05)

    @Test
    fun `registry covers the dashboard PIDs`() {
        val required = listOf(
            0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
            0x11, 0x1F, 0x21, 0x2F, 0x31, 0x33, 0x42, 0x46, 0x5C, 0x5E,
        )

        required.forEach { assertNotNull("PID %02X missing".format(it), Pids[it]) }
        assertTrue(Pids.tier(PidTier.Fast).map(Pid::id).containsAll(listOf(0x0C, 0x0D, 0x11, 0x0B, 0x10)))
    }

    @Test
    fun `boost is manifold pressure minus barometric pressure`() {
        val derived = DerivedMetrics.compute(mapOf(0x0B to 180.0, 0x33 to 101.0))

        assertEquals(79.0, derived.getValue(DerivedMetrics.Boost.key), 0.001)
    }

    @Test
    fun `boost falls back to sea level pressure`() {
        val derived = DerivedMetrics.compute(mapOf(0x0B to 101.3))

        assertEquals(0.0, derived.getValue(DerivedMetrics.Boost.key), 0.001)
    }

    @Test
    fun `fuel rate is estimated from MAF when PID 5E is missing`() {
        val derived = DerivedMetrics.compute(mapOf(0x10 to 5.0, 0x0D to 90.0))

        assertEquals(1.4933, derived.getValue(DerivedMetrics.FuelRate.key), 0.001)
        assertEquals(1.6592, derived.getValue(DerivedMetrics.FuelPer100Km.key), 0.001)
    }

    @Test
    fun `reported fuel rate wins over the MAF estimate`() {
        val derived = DerivedMetrics.compute(mapOf(0x10 to 5.0, 0x5E to 3.0))

        assertEquals(3.0, derived.getValue(DerivedMetrics.FuelRate.key), 0.001)
    }

    @Test
    fun `parses the ATRV battery voltage`() {
        assertEquals(12.6, ElmVoltage.parse("12.6V")!!, 0.001)
        assertEquals(12.6, ElmVoltage.parse(" 12.6 V ")!!, 0.001)
        assertEquals(11.9, ElmVoltage.parse("11.9")!!, 0.001)
        assertEquals(12.6, ElmVoltage.parse(listOf("ATRV", "12.6V"))!!, 0.001)
        assertEquals(null, ElmVoltage.parse("?"))
    }
}
