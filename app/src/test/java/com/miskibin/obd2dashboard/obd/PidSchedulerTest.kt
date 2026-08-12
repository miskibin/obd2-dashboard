package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PidSchedulerTest {

    private val script = mapOf(
        "010B" to "7E8 03 41 0B 96",
        "010C" to "7E8 04 41 0C 1A F8",
        "010D" to "7E8 03 41 0D 41",
        "0110" to "7E8 04 41 10 05 DC",
        "0111" to "NO DATA",
        "0105" to "7E8 03 41 05 5A",
        "0133" to "7E8 03 41 33 65",
        "ATRV" to "12.6V",
    )

    private val supported = setOf(0x0B, 0x0C, 0x0D, 0x10, 0x11, 0x05, 0x33, 0x2F)

    private fun scheduler(scope: CoroutineScope): Pair<PidScheduler, FakeElmTransport> {
        val transport = FakeElmTransport.scripted(script = script)
        val client = Obd2Client(ElmSession(transport, scope), ObdProtocol.Can11Bit500)
        val scheduler = PidScheduler(client, clock = { 0L }, cycleDelayMillis = CYCLE_MILLIS)
        scheduler.configure(supported)
        return scheduler to transport
    }

    @Test
    fun `polls the fast tier every cycle and the medium tier every fifth`() = runTest {
        val (scheduler, transport) = scheduler(backgroundScope)

        withTimeoutOrNull(CYCLE_MILLIS * 10 + CYCLE_MILLIS / 2) { scheduler.run() }

        assertEquals(11, transport.countOf("010C"))
        assertEquals(11, transport.countOf("010D"))
        assertEquals(3, transport.countOf("0105"))
        assertEquals(3, transport.countOf("0133"))
    }

    @Test
    fun `walks the slow tier round robin and keeps the voltage fresh`() = runTest {
        val (scheduler, transport) = scheduler(backgroundScope)

        withTimeoutOrNull(CYCLE_MILLIS * 10 + CYCLE_MILLIS / 2) { scheduler.run() }

        assertEquals(1, transport.countOf("ATRV"))
        assertEquals(1, transport.countOf("012F"))
        assertEquals(12.6, scheduler.snapshot.value.batteryVoltage!!, 0.001)
    }

    @Test
    fun `stops polling a PID that keeps answering NO DATA`() = runTest {
        val (scheduler, transport) = scheduler(backgroundScope)

        withTimeoutOrNull(CYCLE_MILLIS * 10 + CYCLE_MILLIS / 2) { scheduler.run() }

        assertEquals(PidScheduler.MAX_MISSES, transport.countOf("0111"))
    }

    @Test
    fun `publishes decoded readings and derived values`() = runTest {
        val (scheduler, _) = scheduler(backgroundScope)

        withTimeoutOrNull(CYCLE_MILLIS * 2) { scheduler.run() }
        val snapshot = scheduler.snapshot.value

        assertEquals(1726.0, snapshot[0x0C]!!, 0.001)
        assertEquals(65.0, snapshot[0x0D]!!, 0.001)
        assertEquals(15.0, snapshot[0x10]!!, 0.001)
        assertEquals("Engine RPM", snapshot.readings.getValue(0x0C).name)
        assertEquals(49.0, snapshot.derived.getValue(DerivedMetrics.Boost.key), 0.001)
    }

    @Test
    fun `only polls PIDs the vehicle reported as supported`() = runTest {
        val transport = FakeElmTransport.scripted(script = script)
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)
        val scheduler = PidScheduler(client, cycleDelayMillis = CYCLE_MILLIS)
        scheduler.configure(setOf(0x0C))

        withTimeoutOrNull(CYCLE_MILLIS / 2) { scheduler.run() }

        assertEquals(listOf("010C", "ATRV"), transport.commands)
    }

    @Test
    fun `pauses while polling is disabled`() = runTest {
        val transport = FakeElmTransport.scripted(script = script)
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)
        val scheduler = PidScheduler(client, cycleDelayMillis = CYCLE_MILLIS, pollingEnabled = { false })

        withTimeoutOrNull(CYCLE_MILLIS * 5) { scheduler.run() }

        assertTrue(transport.commands.isEmpty())
    }

    @Test
    fun `plans each tier on its own cadence`() = runTest {
        val (scheduler, _) = scheduler(backgroundScope)

        val fastOnly = scheduler.plan(1).map(Pid::id)
        val withMedium = scheduler.plan(5).map(Pid::id)

        assertTrue(0x0C in fastOnly)
        assertTrue(0x05 !in fastOnly)
        assertTrue(0x05 in withMedium)
    }

    @Test
    fun `a parameter on screen is polled every cycle whatever tier it is in`() = runTest {
        val (scheduler, _) = scheduler(backgroundScope)
        // Fuel tank level is a slow-tier PID, so off screen it appears once every twenty
        // cycles and on screen it has to appear in all of them.
        assertTrue(Pids.FUEL_LEVEL !in scheduler.plan(1).map(Pid::id))

        scheduler.prioritize(setOf(sensorKey(Pids.FUEL_LEVEL, 0)))

        assertTrue(Pids.FUEL_LEVEL in scheduler.plan(1).map(Pid::id))
        assertTrue(Pids.FUEL_LEVEL in scheduler.plan(7).map(Pid::id))
        // Once each, not once per reason it is due.
        assertEquals(1, scheduler.plan(20).count { it.id == Pids.FUEL_LEVEL })
    }

    @Test
    fun `a prioritised channel prioritises the PID that carries it`() = runTest {
        val (scheduler, _) = scheduler(backgroundScope)

        // Channel 1 of PID 14 is asked for with the same request as channel 0.
        scheduler.prioritize(setOf(sensorKey(0x2F, 1)))

        assertTrue(Pids.FUEL_LEVEL in scheduler.plan(1).map(Pid::id))
    }

    @Test
    fun `a rested PID is tried again rather than dropped for the session`() = runTest {
        val (scheduler, transport) = scheduler(backgroundScope)

        // Long enough for the misses to rest the PID and for the rest to expire once.
        val cycles = PidScheduler.REST_CYCLES + PidScheduler.MAX_MISSES + 2
        withTimeoutOrNull(CYCLE_MILLIS * cycles) { scheduler.run() }

        // A car connected at ignition-on answers NO DATA to oil temperature and fuel rate
        // and then answers both once it is running; the retry is what picks them up.
        assertTrue(
            "0111 was asked ${transport.countOf("0111")} times",
            transport.countOf("0111") > PidScheduler.MAX_MISSES,
        )
    }

    @Test
    fun `publishes every channel a multi value PID carries`() = runTest {
        val transport = FakeElmTransport.scripted(
            script = mapOf("0114" to "7E8 04 41 14 80 8A", "ATRV" to "12.6V"),
        )
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)
        val scheduler = PidScheduler(client, clock = { 0L }, cycleDelayMillis = CYCLE_MILLIS)
        scheduler.configure(setOf(0x14))
        scheduler.prioritize(setOf(sensorKey(0x14, 0)))

        withTimeoutOrNull(CYCLE_MILLIS / 2) { scheduler.run() }
        val readings = scheduler.snapshot.value.readings

        assertEquals(0.64, readings.getValue(sensorKey(0x14, 0)).value, 0.001)
        assertEquals(7.8125, readings.getValue(sensorKey(0x14, 1)).value, 0.001)
    }

    @Test
    fun `a channel the car says is not fitted is not published`() = runTest {
        val transport = FakeElmTransport.scripted(
            script = mapOf("0114" to "7E8 04 41 14 80 FF", "ATRV" to "12.6V"),
        )
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)
        val scheduler = PidScheduler(client, clock = { 0L }, cycleDelayMillis = CYCLE_MILLIS)
        scheduler.configure(setOf(0x14))
        scheduler.prioritize(setOf(sensorKey(0x14, 0)))

        withTimeoutOrNull(CYCLE_MILLIS / 2) { scheduler.run() }
        val readings = scheduler.snapshot.value.readings

        assertEquals(0.64, readings.getValue(sensorKey(0x14, 0)).value, 0.001)
        assertTrue(sensorKey(0x14, 1) !in readings)
    }

    @Test
    fun `a PID that keeps answering too few bytes to decode is rested too`() = runTest {
        // 0110 declares two data bytes and this car sends one, so every read fails to
        // decode. Without resting it, the truncated answer costs a timeout every cycle
        // for the whole session and never becomes a reading.
        val transport = FakeElmTransport.scripted(
            script = mapOf("0110" to "7E8 03 41 10 05", "ATRV" to "12.6V"),
        )
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)
        val scheduler = PidScheduler(client, clock = { 0L }, cycleDelayMillis = CYCLE_MILLIS)
        scheduler.configure(setOf(Pids.MAF_RATE))

        withTimeoutOrNull(CYCLE_MILLIS * 10) { scheduler.run() }

        assertEquals(PidScheduler.MAX_MISSES, transport.countOf("0110"))
    }

    private companion object {
        const val CYCLE_MILLIS = 100L
    }
}
