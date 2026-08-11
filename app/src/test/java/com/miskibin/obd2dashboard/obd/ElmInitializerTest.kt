package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ElmInitializerTest {

    private val healthyAdapter = mapOf(
        "ATWS" to "ELM327 v2.1",
        "ATE0" to "OK",
        "ATL0" to "OK",
        "ATS0" to "OK",
        "ATH1" to "OK",
        "ATAL" to "OK",
        "ATAT1" to "OK",
        "ATST32" to "OK",
        "ATSP0" to "OK",
        "ATI" to "ELM327 v2.1",
        "ATRV" to "12.6V",
        "0100" to "SEARCHING...\r41 00 BE 3E B8 11",
        "ATDPN" to "A6",
        "ATSP6" to "OK",
    )

    @Test
    fun `runs the documented sequence and locks the discovered protocol`() = runTest {
        val transport = FakeElmTransport.scripted(script = healthyAdapter)
        val outcome = ElmInitializer(ElmSession(transport, backgroundScope)).initialize()

        assertTrue(outcome is InitOutcome.Success)
        val info = (outcome as InitOutcome.Success).info
        assertEquals("ELM327 v2.1", info.identifier)
        assertEquals(12.6, info.batteryVoltage!!, 0.001)
        assertEquals(ObdProtocol.Can11Bit500, info.protocol)
        assertTrue(info.autoDetected)
        assertEquals(
            listOf(
                "ATWS", "ATE0", "ATL0", "ATS0", "ATH1", "ATAL", "ATAT1", "ATST32",
                "ATSP0", "ATI", "ATRV", "0100", "ATDPN", "ATSP6",
            ),
            transport.commands,
        )
    }

    @Test
    fun `tolerates a clone that rejects optional commands`() = runTest {
        val transport = FakeElmTransport.scripted(
            script = healthyAdapter - setOf("ATAT1", "ATST32", "ATAL"),
        )
        val outcome = ElmInitializer(ElmSession(transport, backgroundScope)).initialize()

        assertTrue(outcome is InitOutcome.Success)
        assertEquals(
            listOf("ATAL", "ATAT1", "ATST32"),
            (outcome as InitOutcome.Success).info.unsupportedCommands,
        )
    }

    @Test
    fun `fails when echo cannot be turned off`() = runTest {
        val transport = FakeElmTransport.scripted(script = healthyAdapter - "ATE0")
        val outcome = ElmInitializer(ElmSession(transport, backgroundScope)).initialize()

        assertTrue(outcome is InitOutcome.Failure)
        assertEquals("ATE0", (outcome as InitOutcome.Failure).step)
    }

    @Test
    fun `fails when the vehicle never answers the probe`() = runTest {
        val transport = FakeElmTransport.scripted(
            script = healthyAdapter + ("0100" to "UNABLE TO CONNECT"),
        )
        val outcome = ElmInitializer(ElmSession(transport, backgroundScope)).initialize()

        assertTrue(outcome is InitOutcome.Failure)
        assertEquals(ElmError.UnableToConnect, (outcome as InitOutcome.Failure).error)
    }

    @Test
    fun `skips the protocol search when a protocol is remembered`() = runTest {
        val transport = FakeElmTransport.scripted(script = healthyAdapter + ("ATSP6" to "OK"))
        val outcome = ElmInitializer(
            session = ElmSession(transport, backgroundScope),
            config = ElmInitConfig(preferredProtocol = ObdProtocol.Can11Bit500),
        ).initialize()

        assertTrue(outcome is InitOutcome.Success)
        val info = (outcome as InitOutcome.Success).info
        assertEquals(ObdProtocol.Can11Bit500, info.protocol)
        assertFalse(info.autoDetected)
        assertFalse("ATSP0" in transport.commands)
        assertFalse("ATDPN" in transport.commands)
    }

    @Test
    fun `falls back to the auto search when the remembered protocol fails`() = runTest {
        var probes = 0
        val transport = FakeElmTransport { command ->
            val key = command.uppercase()
            probes += if (key == "0100") 1 else 0
            val reply = when {
                key == "0100" && probes == 1 -> "UNABLE TO CONNECT"
                else -> healthyAdapter[key] ?: "OK"
            }
            reply + FakeElmTransport.PROMPT
        }
        val outcome = ElmInitializer(
            session = ElmSession(transport, backgroundScope),
            config = ElmInitConfig(preferredProtocol = ObdProtocol.Can29Bit500),
        ).initialize()

        assertTrue(outcome is InitOutcome.Success)
        assertEquals(ObdProtocol.Can11Bit500, (outcome as InitOutcome.Success).info.protocol)
        assertTrue("ATSP7" in transport.commands)
        assertTrue("ATSP0" in transport.commands)
    }

    /**
     * `ATWS` is a v1.4+ command. A clone that predates it — or reimplements only half the
     * set — answers `?`, and the sequence has to fall back to the cold reset every ELM327
     * has always understood instead of carrying on against a chip that was never reset.
     */
    @Test
    fun `falls back to a cold ATZ when the adapter does not know ATWS`() = runTest {
        val transport = FakeElmTransport.scripted(
            script = healthyAdapter - "ATWS" + ("ATZ" to "ELM327 v1.5"),
        )
        val outcome = ElmInitializer(ElmSession(transport, backgroundScope)).initialize()

        assertTrue(outcome is InitOutcome.Success)
        assertEquals(listOf("ATWS", "ATZ", "ATE0"), transport.commands.take(3))
    }

    @Test
    fun `does not reset twice when the adapter accepts ATWS`() = runTest {
        val transport = FakeElmTransport.scripted(script = healthyAdapter)
        ElmInitializer(ElmSession(transport, backgroundScope)).initialize()

        assertFalse("ATZ" in transport.commands)
    }

    /** A clone that has just come back from a reset regularly swallows the first command. */
    @Test
    fun `retries echo off before giving up on it`() = runTest {
        var attempts = 0
        val transport = FakeElmTransport { command ->
            val key = command.uppercase()
            val reply = if (key == "ATE0") {
                attempts++
                if (attempts < 3) "?" else "OK"
            } else {
                healthyAdapter[key] ?: "OK"
            }
            reply + FakeElmTransport.PROMPT
        }
        val outcome = ElmInitializer(ElmSession(transport, backgroundScope)).initialize()

        assertTrue(outcome is InitOutcome.Success)
        assertEquals(3, transport.countOf("ATE0"))
    }

    @Test
    fun `reports which step failed so the screen can name it`() = runTest {
        val transport = FakeElmTransport.scripted(
            script = healthyAdapter + ("0100" to "UNABLE TO CONNECT"),
        )
        val outcome = ElmInitializer(ElmSession(transport, backgroundScope)).initialize()

        assertEquals("0100", (outcome as InitOutcome.Failure).step)
        assertEquals(ElmError.UnableToConnect, outcome.error)
    }

    @Test
    fun `announces every command it is on`() = runTest {
        val steps = mutableListOf<String>()
        val transport = FakeElmTransport.scripted(script = healthyAdapter)
        ElmInitializer(
            session = ElmSession(transport, backgroundScope),
            onStep = { steps += it },
        ).initialize()

        assertEquals("ATWS", steps.first())
        assertTrue("ATE0" in steps)
        assertTrue("0100" in steps)
    }

    @Test
    fun `parses the ATDPN protocol readback`() {
        assertEquals(ObdProtocol.Can11Bit500 to true, ObdProtocol.parseDpn("A6"))
        assertEquals(ObdProtocol.Can11Bit500 to false, ObdProtocol.parseDpn("6"))
        assertEquals(ObdProtocol.J1939 to false, ObdProtocol.parseDpn(" a "))
    }
}
