package com.miskibin.obd2dashboard.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        // 5 g/s of air at λ=1 is 5/14.7 g/s of petrol, which at 745 g/L is 1.64 L/h.
        assertEquals(1.6436, derived.getValue(DerivedMetrics.FuelRate.key), 0.001)
        assertEquals(1.8262, derived.getValue(DerivedMetrics.FuelPer100Km.key), 0.001)
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

    // ---- multi-channel parameters ---------------------------------------------------

    /** Decodes one channel of a PID out of a full `41 xx …` response line. */
    private fun channel(response: String, pid: Int, channel: Int): Double {
        val frames = ObdResponseParser.frames(listOf(response), ObdProtocol.Automatic)
        val data = ObdResponseParser.values(frames, MODE_CURRENT_DATA, listOf(pid)).getValue(pid)
        return Pids[pid]!!.channels.first { it.index == channel }.decode(data)
    }

    @Test
    fun `narrow band oxygen sensor reports volts and the trim beside it`() {
        // 0x80 = 128 counts of 5 mV = 0.64 V; 0x8A = 7.8125% of trim.
        assertEquals(0.64, channel("4114808A", 0x14, 0), 0.001)
        assertEquals(7.8125, channel("4114808A", 0x14, 1), 0.001)
    }

    @Test
    fun `an oxygen sensor not used in the trim calculation reports no trim`() {
        assertEquals(0.64, channel("411580FF", 0x15, 0), 0.001)
        assertTrue(channel("411580FF", 0x15, 1).isNaN())
    }

    @Test
    fun `all eight narrow band oxygen sensors are decodable`() {
        (0x14..0x1B).forEach { pid ->
            assertNotNull("PID %02X missing".format(pid), Pids[pid])
            assertEquals(2, Pids[pid]!!.channels.size)
            assertEquals(2, Pids[pid]!!.bytes)
        }
    }

    @Test
    fun `wide range oxygen sensor reports lambda and a voltage`() {
        // 0x8000 of 65536 doubled is lambda 1.0; 0x4000 of 65536 times eight is 2.0 V.
        assertEquals(1.0, channel("4124800040 00", 0x24, 0), 0.001)
        assertEquals(2.0, channel("4124800040 00", 0x24, 1), 0.001)
    }

    @Test
    fun `wide range oxygen sensor reports lambda and a current`() {
        assertEquals(1.0, channel("41348000 8000", 0x34, 0), 0.001)
        assertEquals(0.0, channel("41348000 8000", 0x34, 1), 0.001)
        // A quarter of a count below centre is a quarter of a milliamp negative.
        assertEquals(-1.0, channel("41348000 7F00", 0x34, 1), 0.001)
    }

    @Test
    fun `secondary oxygen sensor trims carry two banks each`() {
        assertEquals(0.0, channel("41558080", 0x55, 0), 0.001)
        assertEquals(7.8125, channel("4155808A", 0x55, 1), 0.001)
        assertEquals(-100.0, channel("41560000", 0x56, 0), 0.001)
    }

    @Test
    fun `sensor arrays honour the bitmask that leads them`() {
        // 0167: A=03 means both coolant sensors fitted; B and C are each minus forty.
        assertEquals(50.0, channel("4167035A5A", 0x67, 0), 0.001)
        assertEquals(50.0, channel("4167035A5A", 0x67, 1), 0.001)
        // A=01 means only the first is fitted, so the second must not be invented.
        assertEquals(50.0, channel("4167015A00", 0x67, 0), 0.001)
        assertTrue(channel("4167015A00", 0x67, 1).isNaN())
    }

    @Test
    fun `exhaust gas temperature reads four sensors behind one bitmask`() {
        // A=03, then four words: 2000/10-40 = 160, 3000/10-40 = 260.
        val response = "417803" + "07D0" + "0BB8" + "0000" + "0000"
        assertEquals(160.0, channel(response, 0x78, 0), 0.001)
        assertEquals(260.0, channel(response, 0x78, 1), 0.001)
        assertTrue(channel(response, 0x78, 2).isNaN())
        assertEquals(9, Pids[0x78]!!.bytes)
    }

    @Test
    fun `turbocharger speed and exhaust pressure are diesel staples`() {
        // 0174: A=01, B,C = 0x9C40 = 40000 rpm.
        assertEquals(40_000.0, channel("4174019C400000", 0x74, 0), 0.001)
        // 0173: A=01, B,C = 0x4000 = 16384, over 128 is 128 kPa.
        assertEquals(128.0, channel("417301400000 00", 0x73, 0), 0.001)
    }

    @Test
    fun `single value additions follow the standard`() {
        assertEquals(0.0, decode("412D80", 0x2D), 0.001)
        assertEquals(100.0, decode("4148FF", 0x48), 0.001)
        assertEquals(100.0, decode("414BFF", 0x4B), 0.001)
        assertEquals(4.0, decode("415104", 0x51), 0.001)
        // 0154 is centred on 32767 rather than two's complement.
        assertEquals(0.0, decode("41547FFF", 0x54), 0.001)
        assertEquals(1.0, decode("41548000", 0x54), 0.001)
        assertEquals(200.0, decode("419E03E8", 0x9E), 0.001)
    }

    @Test
    fun `transmission gear is reported rather than guessed`() {
        // A=01 supported, B reserved, C,D = 4000 thousandths = gear ratio 4.0.
        assertEquals(4.0, channel("41A4010 00FA0", 0xA4, 0), 0.001)
    }

    @Test
    fun `every catalogued PID declares a byte count its channels can index`() {
        Pids.entries.forEach { pid ->
            val data = IntArray(pid.bytes) { 0xFF }
            pid.channels.forEach { channel ->
                // Decoding all-ones must not walk off the end of the response.
                channel.decode(data)
            }
        }
    }

    @Test
    fun `no two catalogued PIDs claim the same id`() {
        val ids = Pids.entries.map(Pid::id)

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every channel gets a key of its own and channel zero keeps the bare PID`() {
        assertEquals(Pids.sensorKeys.size, Pids.sensorKeys.toSet().size)
        // The keys saved tiles and CSV headers were written with must not move.
        assertEquals(Pids.ENGINE_RPM, sensorKey(Pids.ENGINE_RPM, 0))
        Pids.entries.forEach { pid ->
            pid.channels.forEach { channel ->
                assertEquals(pid.id, keyPid(sensorKey(pid.id, channel.index)))
                assertEquals(channel, Pids.channelOf(sensorKey(pid.id, channel.index)))
            }
        }
    }

    @Test
    fun `the families a car is most likely to report are all covered`() {
        val families = mapOf(
            "narrow band oxygen sensors" to (0x14..0x1B),
            "wide range lambda, voltage" to (0x24..0x2B),
            "wide range lambda, current" to (0x34..0x3B),
            "catalyst temperatures" to (0x3C..0x3F),
            "secondary oxygen sensor trims" to (0x55..0x58),
        )

        families.forEach { (name, range) ->
            range.forEach { assertNotNull("$name: %02X missing".format(it), Pids[it]) }
        }
        // The diesel block, which is what a DPF or turbo complaint is diagnosed from.
        listOf(0x6B, 0x73, 0x74, 0x77, 0x78, 0x79, 0x9E).forEach {
            assertNotNull("diesel PID %02X missing".format(it), Pids[it])
        }
    }

    // ---- fuel rate ------------------------------------------------------------------

    @Test
    fun `a diesel without a reported fuel rate gets no petrol estimate`() {
        val diesel = mapOf(0x10 to 20.0, 0x0D to 90.0, Pids.FUEL_TYPE to 4.0)

        // Running 20 g/s of air through petrol's numbers would claim 6.6 L/h; a diesel
        // that reports no mixture gets nothing rather than a number that is simply wrong.
        assertNull(DerivedMetrics.compute(diesel)[DerivedMetrics.FuelRate.key])
    }

    @Test
    fun `a diesel with a measured lambda is estimated on that lambda`() {
        val diesel = mapOf(0x10 to 20.0, Pids.FUEL_TYPE to 4.0, 0x24 to 2.0)

        // 20 g/s of air at lambda 2 burns 20/(2*14.5) = 0.6897 g/s, or 2.98 L/h at 832 g/L.
        assertEquals(2.984, DerivedMetrics.compute(diesel).getValue(DerivedMetrics.FuelRate.key), 0.01)
    }

    @Test
    fun `a rich petrol mixture burns more than a stoichiometric one`() {
        val rich = DerivedMetrics.compute(mapOf(0x10 to 5.0, Pids.COMMANDED_AFR to 0.8))
        val neutral = DerivedMetrics.compute(mapOf(0x10 to 5.0))

        assertTrue(
            rich.getValue(DerivedMetrics.FuelRate.key) >
                neutral.getValue(DerivedMetrics.FuelRate.key),
        )
    }

    @Test
    fun `an electric drivetrain reports no litres per hour`() {
        val electric = mapOf(0x10 to 5.0, Pids.FUEL_TYPE to 8.0)

        assertNull(DerivedMetrics.compute(electric)[DerivedMetrics.FuelRate.key])
    }
}
