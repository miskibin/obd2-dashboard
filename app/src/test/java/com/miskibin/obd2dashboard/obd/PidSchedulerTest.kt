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
        val scheduler = PidScheduler(client, cycleDelayMillis = CYCLE_MILLIS) { false }

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

    private companion object {
        const val CYCLE_MILLIS = 100L
    }
}
