package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ElmSessionTest {

    @Test
    fun `reassembles a response split across notifications`() = runTest {
        val transport = FakeElmTransport(chunkSize = 3) { "7E8 03 41 0C 1A F8\r\r>" }
        val session = ElmSession(transport, backgroundScope)

        val response = session.request("010C")

        assertEquals(listOf("7E8 03 41 0C 1A F8"), response.lines)
    }

    @Test
    fun `waits for the prompt before returning`() = runTest {
        val transport = FakeElmTransport(chunkSize = 4) { "41 0C 1A F8\r41 0C 1A F8\r\r>" }
        val session = ElmSession(transport, backgroundScope)

        val response = session.request("010C")

        assertEquals(2, response.lines.size)
    }

    @Test
    fun `strips the echoed command`() = runTest {
        val transport = FakeElmTransport { "ATE0\rOK\r\r>" }
        val session = ElmSession(transport, backgroundScope)

        assertEquals(listOf("OK"), session.request("ATE0").lines)
    }

    @Test
    fun `strips inserted NUL bytes`() = runTest {
        val transport = FakeElmTransport { "41\u00000C\u00001AF8\r>" }
        val session = ElmSession(transport, backgroundScope)

        assertEquals(listOf("410C1AF8"), session.request("010C").lines)
    }

    @Test
    fun `filters the SEARCHING banner`() = runTest {
        val transport = FakeElmTransport { "SEARCHING...\r41 00 BE 3E B8 11\r\r>" }
        val session = ElmSession(transport, backgroundScope)

        assertEquals(listOf("41 00 BE 3E B8 11"), session.request("0100").lines)
    }

    @Test
    fun `filters SEARCHING when it prefixes the answer`() = runTest {
        val transport = FakeElmTransport { "SEARCHING...41 00 BE 3E B8 11\r>" }
        val session = ElmSession(transport, backgroundScope)

        assertEquals(listOf("41 00 BE 3E B8 11"), session.request("0100").lines)
    }

    @Test
    fun `keeps bus init progress out of the payload`() = runTest {
        val transport = FakeElmTransport { "BUS INIT: OK\r41 0C 1A F8\r\r>" }
        val session = ElmSession(transport, backgroundScope)

        val response = session.request("010C")

        assertTrue(response is ElmResponse.Ok)
        assertEquals(listOf("41 0C 1A F8"), response.lines)
    }

    @Test
    fun `recognises every adapter error string`() = runTest {
        val errors = mapOf(
            "NO DATA" to ElmError.NoData,
            "UNABLE TO CONNECT" to ElmError.UnableToConnect,
            "STOPPED" to ElmError.Stopped,
            "BUS INIT: ERROR" to ElmError.BusInitError,
            "BUS BUSY" to ElmError.BusBusy,
            "BUS ERROR" to ElmError.BusError,
            "CAN ERROR" to ElmError.CanError,
            "DATA ERROR" to ElmError.DataError,
            "<DATA ERROR" to ElmError.DataError,
            "BUFFER FULL" to ElmError.BufferFull,
            "ERR94" to ElmError.FatalCanError,
            "ERR21" to ElmError.InternalError,
            "LV RESET" to ElmError.LowVoltageReset,
            "FB ERROR" to ElmError.FeedbackError,
            "!ACT ALERT" to ElmError.ActivityAlert,
            "!LP ALERT" to ElmError.LowPowerAlert,
            "?" to ElmError.SyntaxError,
        )

        for ((text, expected) in errors) {
            val session = ElmSession(FakeElmTransport { "$text\r\r>" }, backgroundScope)
            assertEquals(text, expected, session.request("010C").errorOrNull)
        }
    }

    @Test
    fun `flags the errors that invalidate the session settings`() {
        assertTrue(ElmError.FatalCanError.requiresReinit)
        assertTrue(ElmError.LowVoltageReset.requiresReinit)
        assertFalse(ElmError.NoData.requiresReinit)
    }

    @Test
    fun `reports a timeout when the prompt never arrives`() = runTest {
        val session = ElmSession(FakeElmTransport { null }, backgroundScope)

        assertEquals(ElmError.Timeout, session.request("010C", timeoutMillis = 500).errorOrNull)
    }

    @Test
    fun `serialises concurrent requests`() = runTest {
        val transport = FakeElmTransport.scripted(
            script = mapOf(
                "010C" to "41 0C 1A F8",
                "010D" to "41 0D 41",
            ),
        )
        val session = ElmSession(transport, backgroundScope)

        val rpm = async { session.request("010C") }
        val speed = async { session.request("010D") }

        assertEquals(listOf("41 0C 1A F8"), rpm.await().lines)
        assertEquals(listOf("41 0D 41"), speed.await().lines)
        assertEquals(listOf("010C", "010D"), transport.commands)
    }
}
