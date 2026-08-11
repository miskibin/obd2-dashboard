package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demo mode is only worth anything if it goes through the same pipeline a real dongle
 * does, so every assertion here reads the simulation back through [ElmSession] and
 * [Obd2Client] rather than poking at the transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoElmTransportTest {

    private suspend fun connect(scope: CoroutineScope): Pair<Obd2Client, AdapterInfo> {
        val transport = DemoElmTransport(latencyMillis = IntRange.EMPTY)
        transport.open()
        val session = ElmSession(transport, scope)
        val outcome = ElmInitializer(session).initialize()
        assertTrue("init failed: $outcome", outcome is InitOutcome.Success)
        val info = (outcome as InitOutcome.Success).info
        return Obd2Client(session, info.protocol) to info
    }

    @Test
    fun `passes the full init sequence as an ELM327 v2 1 on CAN 11-bit`() = runTest {
        val (_, info) = connect(backgroundScope)

        assertEquals("ELM327 v2.1", info.identifier)
        assertEquals(ObdProtocol.Can11Bit500, info.protocol)
        assertTrue(info.autoDetected)
        assertEquals(emptyList<String>(), info.unsupportedCommands)
        assertEquals(14.2, info.batteryVoltage!!, 0.5)
    }

    @Test
    fun `reports a petrol-car support set across all three bitmask blocks`() = runTest {
        val (client, _) = connect(backgroundScope)
        val supported = client.scanSupportedPids()

        assertTrue(supported.size >= 20)
        assertTrue(supported.containsAll(listOf(Pids.ENGINE_RPM, Pids.VEHICLE_SPEED)))
        assertTrue(supported.containsAll(listOf(Pids.COOLANT_TEMP, Pids.INTAKE_AIR_TEMP)))
        // The chain bits are what make 0120 and 0140 happen at all.
        assertTrue(0x20 in supported && 0x40 in supported)
        assertFalse(0x60 in supported)
        assertTrue(supported.count { Pids[it] != null } >= 20)
    }

    @Test
    fun `answers every supported PID with a decodable value`() = runTest {
        val (client, _) = connect(backgroundScope)
        val supported = client.scanSupportedPids().mapNotNull { Pids[it] }

        for (pid in supported) {
            val read = client.readPid(pid)
            assertTrue("${pid.name} read as $read", read is PidRead.Value)
        }
    }

    @Test
    fun `live values are plausible for a running engine`() = runTest {
        val (client, _) = connect(backgroundScope)

        val rpm = (client.readPid(Pids[Pids.ENGINE_RPM]!!) as PidRead.Value).value
        val coolant = (client.readPid(Pids[Pids.COOLANT_TEMP]!!) as PidRead.Value).value
        val trim = (client.readPid(Pids[Pids.SHORT_FUEL_TRIM_1]!!) as PidRead.Value).value
        val fuel = (client.readPid(Pids[Pids.FUEL_LEVEL]!!) as PidRead.Value).value

        assertTrue("rpm was $rpm", rpm in 750.0..3_600.0)
        assertTrue("coolant was $coolant", coolant in 18.0..92.0)
        assertTrue("short trim was $trim", trim in -5.0..5.0)
        assertTrue("fuel level was $fuel", fuel in 60.0..70.0)
        assertEquals(14.2, client.readVoltage()!!, 0.5)
    }

    @Test
    fun `batches several PIDs into one CAN response`() = runTest {
        val (client, _) = connect(backgroundScope)
        val fast = Pids.tier(PidTier.Fast)

        assertTrue(client.probeBatching(fast.take(3)))
        val answered = client.readBatch(fast)
        assertEquals(fast.map(Pid::id).toSet(), answered.keys)
    }

    @Test
    fun `reports two stored codes, one pending and the light on`() = runTest {
        val (client, _) = connect(backgroundScope)
        val diagnostics = client.readDiagnostics()

        assertEquals(listOf("P0420", "P0301"), diagnostics.stored.map(Dtc::code))
        assertEquals(listOf("P0171"), diagnostics.pending.map(Dtc::code))
        assertEquals(emptyList<Dtc>(), diagnostics.permanent)
        assertEquals(true, diagnostics.monitorStatus?.milOn)
        assertEquals(2, diagnostics.monitorStatus?.dtcCount)
    }

    @Test
    fun `mode 04 clears stored and pending codes and turns the light off`() = runTest {
        val (client, _) = connect(backgroundScope)

        assertTrue(client.clearDtcs())

        val diagnostics = client.readDiagnostics()
        assertEquals(emptyList<Dtc>(), diagnostics.stored)
        assertEquals(emptyList<Dtc>(), diagnostics.pending)
        assertEquals(false, diagnostics.monitorStatus?.milOn)
        assertEquals(0, diagnostics.monitorStatus?.dtcCount)
    }

    @Test
    fun `returns a VIN over a multi-frame 0902 response`() = runTest {
        val (client, _) = connect(backgroundScope)
        val vin = client.readVin()

        assertNotNull(vin)
        assertEquals(17, vin!!.length)
        assertEquals(DemoElmTransport.VIN, vin)
    }

    @Test
    fun `drive cycle warms up, shifts gears and keeps speed following rpm`() {
        val vehicle = DemoVehicle()

        val idle = vehicle.sampleAt(5_000)
        assertEquals(0.0, idle.speedKph, 0.001)
        assertTrue("idle rpm was ${idle.rpm}", idle.rpm in 800.0..900.0)
        assertTrue("cold coolant was ${idle.coolantC}", idle.coolantC in 20.0..25.0)

        val cycle = (0..180).map { vehicle.sampleAt(it * 1_000L) }
        assertTrue(cycle.all { it.rpm in 800.0..3_500.0 })
        assertTrue(cycle.all { it.speedKph in 0.0..125.0 })
        assertTrue(cycle.all { it.intakeC in 25.0..40.0 })
        assertTrue(cycle.all { it.shortTrimPercent in -5.0..5.0 })
        assertTrue(cycle.maxOf { it.speedKph } > 100.0)

        // An upshift is the one moment where speed rises while the engine slows down.
        val shifts = cycle.zipWithNext().count { (before, after) ->
            after.speedKph > before.speedKph && after.rpm < before.rpm - 200.0
        }
        assertTrue("only $shifts upshifts in one cycle", shifts >= 4)

        val warm = vehicle.sampleAt(200_000)
        assertTrue("warm coolant was ${warm.coolantC}", warm.coolantC in 87.0..92.0)
        assertTrue("battery was ${warm.batteryVolts}", warm.batteryVolts in 13.8..14.4)
    }
}
