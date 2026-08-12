package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the extended parameters ride the polling loop: which module gets a given cycle, how
 * often each one is due, and what the adapter's headers look like on either side of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtendedSchedulingTest {

    private fun extended(
        id: String,
        header: String,
        did: Int,
        receiveHeader: String? = null,
        tier: PidTier = PidTier.Medium,
        minIntervalMillis: Long = 0,
    ) = ExtendedPid(
        id = id,
        header = header,
        receiveHeader = receiveHeader,
        did = did,
        unit = "kPa",
        decimals = 0,
        bytes = 1,
        tier = tier,
        minIntervalMillis = minIntervalMillis,
        applies = { true },
        decode = { it[0].toDouble() },
    )

    private val engine = extended("engine_a", "7E0", 0x0415)
    private val engineB = extended("engine_b", "7E0", 0x1310)
    private val engineC = extended("engine_c", "7E0", 0x0301)
    private val body = extended("body", "726", 0xD922, receiveHeader = "72E", tier = PidTier.Slow)

    private fun scheduler(
        scope: CoroutineScope,
        script: Map<String, String> = emptyMap(),
        now: () -> Long = { 0L },
    ): Pair<PidScheduler, FakeElmTransport> {
        val transport = FakeElmTransport.scripted(script = script)
        val client = Obd2Client(ElmSession(transport, scope), ObdProtocol.Can11Bit500)
        val scheduler = PidScheduler(client, clock = now, cycleDelayMillis = CYCLE_MILLIS)
        // A supported set with no real PID in it, so the Mode 01 plan comes out empty and
        // what reaches the transport is the extended traffic and nothing else.
        scheduler.configure(setOf(NO_SUCH_PID))
        return scheduler to transport
    }

    @Test
    fun `one cycle serves one module, and the modules take turns`() = runTest {
        val (scheduler, _) = scheduler(backgroundScope)
        scheduler.configureExtended(listOf(body, engine, engineB))

        // Cycle 20 is due for both tiers, so both modules want it; only one can have it,
        // because entering the other costs a second pair of AT commands.
        val first = scheduler.planExtended(20)
        assertEquals(listOf("726"), first.map(ExtendedPid::header).distinct())

        // And the one that waited gets the next cycle both are due on.
        val second = scheduler.planExtended(20)
        assertEquals(listOf("7E0"), second.map(ExtendedPid::header).distinct())
    }

    @Test
    fun `no more than two parameters ride one module switch`() = runTest {
        val (scheduler, _) = scheduler(backgroundScope)
        scheduler.configureExtended(listOf(engine, engineB, engineC))

        assertEquals(PidScheduler.MAX_EXTENDED_PER_CYCLE, scheduler.planExtended(5).size)
    }

    @Test
    fun `each tier keeps its own cadence`() = runTest {
        val (medium, _) = scheduler(backgroundScope)
        medium.configureExtended(listOf(engine))
        val (slow, _) = scheduler(backgroundScope)
        slow.configureExtended(listOf(body))

        assertEquals(emptyList<ExtendedPid>(), medium.planExtended(1))
        assertEquals(listOf(engine), medium.planExtended(PidScheduler.MEDIUM_EVERY))
        assertEquals(emptyList<ExtendedPid>(), slow.planExtended(PidScheduler.MEDIUM_EVERY))
        assertEquals(listOf(body), slow.planExtended(PidScheduler.SLOW_EVERY))
    }

    @Test
    fun `a parameter with a minimum interval is not asked for again inside it`() = runTest {
        var now = 0L
        val tyre = extended("tyre", "726", 0xD922, receiveHeader = "72E", minIntervalMillis = 15_000)
        val (scheduler, transport) = scheduler(
            backgroundScope,
            mapOf("22D922" to "72E 04 62 D9 22 A7"),
        ) { now }
        scheduler.configureExtended(listOf(tyre))

        withTimeoutOrNull(CYCLE_MILLIS * 21) { scheduler.run() }

        // Cycles 0, 5, 10, 15 and 20 are all due by tier, and no time has passed, so
        // exactly one of them may actually go out on the bus.
        assertEquals(1, transport.countOf("22D922"))
        now = 20_000
        assertEquals(listOf(tyre), scheduler.planExtended(PidScheduler.MEDIUM_EVERY))
    }

    @Test
    fun `the module is entered once and the default headers restored after it`() = runTest {
        val (scheduler, transport) = scheduler(
            backgroundScope,
            mapOf(
                "22D922" to "72E 04 62 D9 22 A7",
                "ATSH726" to "OK",
                "ATCRA72E" to "OK",
                "ATRV" to "12.6V",
            ),
        )
        scheduler.configureExtended(listOf(body))

        withTimeoutOrNull(CYCLE_MILLIS / 2) { scheduler.run() }

        // One switch in, the read, and both settings put back before anything else runs.
        assertEquals(
            listOf("ATSH726", "ATCRA72E", "22D922", "ATCRA", "ATSH7DF", "ATRV"),
            transport.commands,
        )
        assertEquals(0xA7.toDouble(), scheduler.snapshot.value.extended.getValue("body").value, 0.001)
    }

    @Test
    fun `a cycle that reads a long answer configures flow control once, around the reads`() =
        runTest {
            val chassis = extended("chassis", "7A0", 0xC00B, receiveHeader = "7A8")
                .copy(flowControl = true)
            val alsoChassis = extended("chassis_b", "7A0", 0xC00C, receiveHeader = "7A8")
            val (scheduler, transport) = scheduler(
                backgroundScope,
                mapOf(
                    "22C00B" to "7A8 04 62 C0 0B 20",
                    "22C00C" to "7A8 04 62 C0 0C 21",
                    "ATRV" to "12.6V",
                ),
            )
            // Two parameters on one module, only one of which needs the flow control: the
            // configuration belongs to the module switch, not to the parameter.
            scheduler.configureExtended(listOf(chassis, alsoChassis))

            withTimeoutOrNull(CYCLE_MILLIS / 2) { scheduler.run() }

            assertEquals(
                listOf(
                    "ATSH7A0", "ATCRA7A8", "ATFCSH7A0", "ATFCSD300000", "ATFCSM1",
                    "22C00B", "22C00C",
                    "ATFCSM0", "ATCRA", "ATSH7DF", "ATRV",
                ),
                transport.commands,
            )
        }

    @Test
    fun `a car with no extended parameters sends no extended traffic`() = runTest {
        val (scheduler, transport) = scheduler(backgroundScope, mapOf("ATRV" to "12.6V"))

        withTimeoutOrNull(CYCLE_MILLIS / 2) { scheduler.run() }

        assertEquals(listOf("ATRV"), transport.commands)
    }

    @Test
    fun `the oxygen sensor map stops the app asking after probes that are not fitted`() = runTest {
        // 0113 answers 03: bank 1 sensors 1 and 2 exist and the other six do not, even
        // though the support block claimed all eight.
        val transport = FakeElmTransport.scripted(
            script = mapOf(
                "0113" to "7E8 03 41 13 03",
                "0114" to "7E8 04 41 14 80 8A",
                "0115" to "7E8 04 41 15 60 FF",
                "ATRV" to "12.6V",
            ),
        )
        val client = Obd2Client(ElmSession(transport, backgroundScope), ObdProtocol.Can11Bit500)
        val scheduler = PidScheduler(client, clock = { 0L }, cycleDelayMillis = CYCLE_MILLIS)
        scheduler.configure((0x14..0x1B).toSet() + Pids.O2_SENSORS_PRESENT)

        withTimeoutOrNull(CYCLE_MILLIS * 61) { scheduler.run() }

        assertTrue("0113 was never asked for", transport.countOf("0113") > 0)
        assertTrue("0114 was never asked for", transport.countOf("0114") > 0)
        // Six sensors that do not exist would otherwise cost six timeouts every sweep.
        assertEquals(0, transport.countOf("0116"))
        assertEquals(0, transport.countOf("011B"))
    }

    private companion object {
        const val CYCLE_MILLIS = 100L

        /** A PID no catalogue entry has, so the Mode 01 plan comes out empty. */
        const val NO_SUCH_PID = 0xFE
    }
}
