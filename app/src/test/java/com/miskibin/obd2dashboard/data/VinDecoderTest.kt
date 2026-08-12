package com.miskibin.obd2dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VinDecoderTest {

    /** Fixed rather than the real clock, so the year cases do not expire. */
    private fun decode(vin: String) = VinDecoder.decode(vin, currentYear = 2026)

    @Test
    fun `reads make, country and year off a European VIN`() {
        val facts = decode("WVWZZZ1KZ8W123456")!!
        assertEquals("Volkswagen", facts.manufacturer)
        assertEquals("Germany", facts.country)
        assertEquals(2008, facts.modelYear)
    }

    /**
     * The four marques NHTSA's free decoder returns nothing at all for; they are the reason
     * this table exists rather than a network call. See `docs/research-vehicle-metadata.md`.
     */
    @Test
    fun `covers the European marques a US registry does not`() {
        assertEquals("Škoda", decode("TMBJJ7NE0J0123456")?.manufacturer)
        assertEquals("Citroën", decode("VF7DDNFPWDJ123456")?.manufacturer)
        assertEquals("SEAT", decode("VSSZZZ6JZ9R123456")?.manufacturer)
        assertEquals("Nissan", decode("SJNFAAJ11U1234567")?.manufacturer)
    }

    @Test
    fun `places each of them in the right country`() {
        assertEquals("Czechia", decode("TMBJJ7NE0J0123456")?.country)
        assertEquals("France", decode("VF7DDNFPWDJ123456")?.country)
        assertEquals("Spain", decode("VSSZZZ6JZ9R123456")?.country)
        assertEquals("United Kingdom", decode("SJNFAAJ11U1234567")?.country)
    }

    /**
     * The year character repeats every thirty years, so `B` means 1981, 2011 and 2041 at
     * once. A car with an OBD2 port to plug into is never the first of those — vPIC dated
     * this exact VIN to 1981 — and is never the last, because it does not exist yet.
     */
    @Test
    fun `resolves the year cycle to the car that can be plugged in`() {
        assertEquals(2011, decode("WAUZZZ8K7BA123456")?.modelYear)
        assertEquals(2020, decode("WAUZZZ8K7LA123456")?.modelYear)
        assertEquals(2009, decode("WAUZZZ8K79A123456")?.modelYear)
    }

    /**
     * A model year opens during the previous calendar year, so one year ahead is allowed
     * and two are not: in 2026, `V` is read as the 2027 model it may well be, and `W` falls
     * back to 1998 rather than reaching for a 2028 that cannot exist yet.
     */
    @Test
    fun `accepts next year's models and not the year after`() {
        assertEquals(2027, VinDecoder.decode("WVWZZZ1KZVW123456", currentYear = 2026)?.modelYear)
        assertEquals(1998, VinDecoder.decode("WVWZZZ1KZWW123456", currentYear = 2026)?.modelYear)
    }

    /**
     * Position 9 is a check digit in North America and a filler everywhere else, and vPIC
     * duly reports a valid European VIN as having a bad one. Nothing here validates it.
     */
    @Test
    fun `does not reject a European VIN over its check digit`() {
        assertNotNull(decode("WVWZZZ1KZ8W123456"))
    }

    @Test
    fun `refuses anything that is not a VIN`() {
        assertNull(decode(""))
        assertNull(decode("WVWZZZ1KZ8W12345"))
        assertNull(decode("WVWZZZ1KZ8W1234567"))
        assertNull(VinDecoder.decode(null))
        // I, O and Q are excluded from the VIN alphabet so they cannot be read as 1 and 0.
        assertNull(decode("WVWZZZ1KZ8W12345O"))
    }

    @Test
    fun `is case and whitespace insensitive`() {
        assertEquals("Volkswagen", decode("  wvwzzz1kz8w123456 ")?.manufacturer)
    }

    /** A code that is not in the table yields no marque, never a guessed one. */
    @Test
    fun `says nothing rather than guessing an unknown manufacturer`() {
        val facts = decode("ZZZZZZ1KZ8W123456")!!
        assertNull(facts.manufacturer)
        assertEquals(2008, facts.modelYear)
    }
}
