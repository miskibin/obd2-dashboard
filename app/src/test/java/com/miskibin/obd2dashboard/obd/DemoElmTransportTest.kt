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
    fun `reports a petrol-car support set the whole bitmask chain long`() = runTest {
        val (client, _) = connect(backgroundScope)
        val supported = client.scanSupportedPids()

        assertTrue(supported.size >= 20)
        assertTrue(supported.containsAll(listOf(Pids.ENGINE_RPM, Pids.VEHICLE_SPEED)))
        assertTrue(supported.containsAll(listOf(Pids.COOLANT_TEMP, Pids.INTAKE_AIR_TEMP)))
        // The chain bits are what make 0120, 0140 and everything after them happen at all,
        // and the parameters this car reports run as far as the 0x80 block.
        assertTrue(listOf(0x20, 0x40, 0x60, 0x80).all { it in supported })
        assertTrue(
            supported.containsAll(
                listOf(Pids.AUXILIARY_IO, Pids.FRICTION_TORQUE, Pids.FUEL_RATE_MASS),
            ),
        )
        // The chain stops there: nothing past 0xA0 is claimed.
        assertFalse(0xC0 in supported)
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
    fun `readiness has the catalyst and evap monitors still running`() = runTest {
        val (client, _) = connect(backgroundScope)
        val readiness = client.readDiagnostics().monitorStatus?.readiness!!

        assertEquals(IgnitionType.Spark, readiness.ignition)
        assertEquals(
            listOf(MonitorId.Catalyst, MonitorId.EvaporativeSystem),
            readiness.incomplete.map(Monitor::id),
        )
        assertFalse(readiness.ready)
        assertEquals(7, readiness.supported.size)
    }

    @Test
    fun `a freeze frame is stored for the code that set the light`() = runTest {
        val (client, _) = connect(backgroundScope)
        val frame = client.readFreezeFrame()

        assertEquals(DemoElmTransport.FREEZE_FRAME_CODE, frame.triggerCode)
        assertEquals(2_100.0, frame.values[Pids.ENGINE_RPM]!!, 1.0)
        assertEquals(84.0, frame.values[Pids.VEHICLE_SPEED]!!, 1.0)
        assertEquals(92.0, frame.values[Pids.COOLANT_TEMP]!!, 1.0)
        assertEquals(48.0, frame.values[Pids.INTAKE_MAP]!!, 1.0)
        assertEquals(10.2, frame.values[Pids.LONG_FUEL_TRIM_1]!!, 0.5)
        assertEquals(FreezeFrames.pids.size, frame.values.size)
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
        // Clearing takes the freeze frame with it.
        assertTrue(client.readFreezeFrame().isEmpty)
    }

    @Test
    fun `the drive cycle heats the coolant past the default alert threshold`() {
        val vehicle = DemoVehicle()
        val cycle = (0..179).map { vehicle.sampleAt(it * 1_000L) }

        val hot = cycle.filter { it.coolantC > 105.0 }
        assertTrue("nothing crossed 105 °C in one cycle", hot.isNotEmpty())
        assertTrue("coolant reached ${cycle.maxOf { it.coolantC }}", cycle.maxOf { it.coolantC } < 115.0)
        // It comes back down, so a threshold alert re-arms rather than latching on.
        assertTrue(cycle.last().coolantC < 95.0)
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

    // ---- the extended slice, through the same pipeline as everything else --------

    @Test
    fun `the simulated car is a Mazda 3 of the generation the tyre block needs`() = runTest {
        val (client, _) = connect(backgroundScope)
        val vin = client.readVin()!!

        val vehicle = ExtendedVehicle(vin = vin, modelYear = BP_MODEL_YEAR)
        assertTrue("the demo VIN must pass the marque gate", vehicle.isMazda)
        // Gating that nothing exercised would be gating nobody could trust.
        val candidates = ExtendedPids.candidatesFor(vehicle)
        assertEquals(12, candidates.size)
        assertEquals(setOf("7E0", "7E1", "726"), candidates.map(ExtendedPid::header).toSet())
    }

    @Test
    fun `mode 06 reports a healthy catalyst and a misfiring second cylinder`() = runTest {
        val (client, _) = connect(backgroundScope)
        val monitors = client.readMonitorTests()

        val misfires = monitors.misfires
        assertEquals(listOf(1, 2, 3, 4), misfires.map { it.cylinder })
        val cylinderTwo = misfires.single { it.cylinder == 2 }
        assertEquals(12.0, cylinderTwo.value, 0.001)
        assertFalse("cylinder 2 is over its limit", cylinderTwo.passed)
        assertTrue(misfires.filter { it.cylinder != 2 }.all(MonitorTest::passed))

        val catalyst = monitors.catalyst.single()
        assertEquals(0.85, catalyst.value, 0.001)
        assertEquals(0.30, catalyst.min, 0.001)
        assertTrue("a healthy converter sits well clear of its floor", catalyst.headroom!! > 0.2)
        // One failing test in the whole set, which is what the screen summarises.
        assertEquals(1, monitors.failed.size)
    }

    @Test
    fun `mode 09 reports how often each monitor has run`() = runTest {
        val (client, _) = connect(backgroundScope)
        val tracking = client.readPerformanceTracking()!!

        assertEquals(210, tracking.obdConditions)
        assertEquals(250, tracking.ignitionCycles)
        val evap = tracking.monitors.single { it.monitor == TrackedMonitor.Evaporative }
        assertEquals(6, evap.completions)
        assertEquals(38, evap.conditions)
        // A single-bank car reports nothing for bank 2, which is not the same as failing.
        assertTrue(
            tracking.monitors.single { it.monitor == TrackedMonitor.CatalystBank2 }.neverRun,
        )
    }

    @Test
    fun `the extended probe finds the engine and body parameters and is refused the tyre temperatures`() =
        runTest {
            val (client, _) = connect(backgroundScope)
            val vehicle = ExtendedVehicle(DemoElmTransport.VIN, BP_MODEL_YEAR)

            val verdicts = ExtendedPids.candidatesFor(vehicle)
                .groupBy(ExtendedPid::header)
                .flatMap { (header, group) ->
                    client.withModule(header, group.first().receiveHeader) {
                        group.map { it.id to client.probeExtended(it) }
                    }
                }
                .toMap()

            assertEquals(ExtendedProbe.Supported, verdicts[ExtendedPids.OIL_PRESSURE])
            assertEquals(ExtendedProbe.Supported, verdicts[ExtendedPids.OIL_TEMPERATURE])
            assertEquals(
                ExtendedProbe.Supported,
                verdicts[ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE],
            )
            assertEquals(
                ExtendedProbe.Supported,
                verdicts[ExtendedPids.tyrePressureId("fl")],
            )
            // The tyre temperatures are refused outright, which is what puts the probe's
            // give-up path under test without a car in the driveway.
            ExtendedPids.WHEELS.forEach { wheel ->
                assertEquals(
                    "tyre temperature $wheel",
                    ExtendedProbe.Absent,
                    verdicts[ExtendedPids.tyreTemperatureId(wheel)],
                )
            }
        }

    @Test
    fun `the extended readings are plausible for the car the simulation describes`() = runTest {
        val (client, _) = connect(backgroundScope)

        val engine = client.withModule("7E0", null) {
            listOf(ExtendedPids.OIL_PRESSURE, ExtendedPids.OIL_TEMPERATURE)
                .map { client.readExtended(ExtendedPids[it]!!) }
        }
        val fluid = client.withModule("7E1", null) {
            client.readExtended(ExtendedPids[ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE]!!)
        }
        val tyres = client.withModule("726", "72E") {
            ExtendedPids.WHEELS.map {
                client.readExtended(ExtendedPids[ExtendedPids.tyrePressureId(it)]!!)
            }
        }

        val pressure = (engine[0] as ExtendedRead.Value).value
        assertTrue("oil pressure was $pressure kPa", pressure in 150.0..500.0)
        val oil = (engine[1] as ExtendedRead.Value).value
        assertTrue("oil temperature was $oil", oil in 15.0..100.0)
        val gearbox = (fluid as ExtendedRead.Value).value
        assertTrue("gearbox oil was $gearbox", gearbox in 5.0..100.0)
        tyres.forEach { read ->
            val bar = (read as ExtendedRead.Value).value
            assertTrue("tyre pressure was $bar bar", bar in 2.2..2.5)
        }
    }

    @Test
    fun `a body module reached without the header switch answers nothing`() = runTest {
        val (client, _) = connect(backgroundScope)

        // The whole point of ATSH and ATCRA: without them the request goes to the engine
        // ECU, which has never heard of a tyre pressure.
        val blind = client.readExtended(ExtendedPids[ExtendedPids.tyrePressureId("fl")]!!)

        assertEquals(ExtendedRead.Silent, blind)
    }

    @Test
    fun `mode 01 goes quiet while the adapter is pointed at another module`() = runTest {
        val (client, _) = connect(backgroundScope)

        val duringSwitch = client.withModule("726", "72E") {
            client.readPid(Pids[Pids.ENGINE_RPM]!!)
        }
        // And works again the moment the headers are put back, which is what the restore
        // in withModule exists for.
        val after = client.readPid(Pids[Pids.ENGINE_RPM]!!)

        assertTrue("rpm answered from the body module: $duringSwitch", duringSwitch !is PidRead.Value)
        assertTrue(after is PidRead.Value)
    }

    private companion object {
        /** `K` in position ten of the demo VIN, which is the 2019 model year. */
        const val BP_MODEL_YEAR = 2019
    }
}
