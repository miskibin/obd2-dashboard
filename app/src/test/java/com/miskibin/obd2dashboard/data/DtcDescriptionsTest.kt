package com.miskibin.obd2dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDescriptionsTest {

    @Test
    fun `a known code resolves in both languages`() {
        val text = DtcDescriptions.describe("P0420")
        assertEquals("Catalyst system efficiency below threshold, bank 1", text.en)
        assertEquals("Sprawność katalizatora poniżej progu, rząd 1", text.pl)
    }

    @Test
    fun `lookup is case insensitive and tolerates surrounding whitespace`() {
        assertEquals(DtcDescriptions.describe("P0301"), DtcDescriptions.describe(" p0301 "))
    }

    @Test
    fun `forLanguage picks Polish only for pl`() {
        val text = DtcDescriptions.describe("P0171")
        assertEquals(text.pl, text.forLanguage("pl"))
        assertEquals(text.pl, text.forLanguage("PL"))
        assertEquals(text.en, text.forLanguage("en"))
        assertEquals(text.en, text.forLanguage("de"))
    }

    @Test
    fun `an unlisted generic powertrain code falls back to its category`() {
        assertNull(DtcDescriptions.lookup("P0999"))
        assertEquals(DtcDescriptions.POWERTRAIN_GENERIC, DtcDescriptions.describe("P0999"))
        assertEquals(DtcDescriptions.POWERTRAIN_GENERIC, DtcDescriptions.describe("P2999"))
    }

    @Test
    fun `a manufacturer specific code says so instead of guessing`() {
        assertEquals(DtcDescriptions.POWERTRAIN_MANUFACTURER, DtcDescriptions.describe("P1234"))
        assertEquals(DtcDescriptions.POWERTRAIN_MANUFACTURER, DtcDescriptions.describe("P3456"))
        assertNotEquals(DtcDescriptions.POWERTRAIN_GENERIC, DtcDescriptions.describe("P1234"))
    }

    @Test
    fun `chassis, body and network codes fall back to their own systems`() {
        assertEquals(DtcDescriptions.CHASSIS_GENERIC, DtcDescriptions.describe("C0999"))
        assertEquals(DtcDescriptions.CHASSIS_MANUFACTURER, DtcDescriptions.describe("C1999"))
        assertEquals(DtcDescriptions.BODY_GENERIC, DtcDescriptions.describe("B0999"))
        assertEquals(DtcDescriptions.BODY_MANUFACTURER, DtcDescriptions.describe("B1999"))
        assertEquals(DtcDescriptions.NETWORK_GENERIC, DtcDescriptions.describe("U0999"))
        assertEquals(DtcDescriptions.NETWORK_MANUFACTURER, DtcDescriptions.describe("U1999"))
    }

    @Test
    fun `garbage that is not a code at all resolves to unknown`() {
        assertEquals(DtcDescriptions.UNKNOWN, DtcDescriptions.describe(""))
        assertEquals(DtcDescriptions.UNKNOWN, DtcDescriptions.describe("X"))
        assertEquals(DtcDescriptions.UNKNOWN, DtcDescriptions.describe("1234"))
    }

    @Test
    fun `the table covers the codes an owner is most likely to meet`() {
        val staples = listOf(
            "P0100", "P0113", "P0128", "P0135", "P0171", "P0300", "P0301", "P0335",
            "P0401", "P0420", "P0430", "P0442", "P0455", "P0505", "P0562", "P0700",
            "P0741", "P2096", "U0100", "C0035", "B0001",
        )
        staples.forEach { code ->
            assertNotNull("expected a generic description for $code", DtcDescriptions.lookup(code))
        }
        assertTrue(
            "table should carry at least 120 codes, had ${DtcDescriptions.knownCodeCount}",
            DtcDescriptions.knownCodeCount >= 120,
        )
    }

    @Test
    fun `every description is filled in for both languages`() {
        listOf("P0011", "P0420", "P0740", "U0155").forEach { code ->
            val text = DtcDescriptions.describe(code)
            assertTrue(text.en.isNotBlank())
            assertTrue(text.pl.isNotBlank())
            assertNotEquals(text.en, text.pl)
        }
    }

    private fun assertNotNull(message: String, value: Any?) =
        assertTrue(message, value != null)
}
