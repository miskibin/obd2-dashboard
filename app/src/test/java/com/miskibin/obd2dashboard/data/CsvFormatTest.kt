package com.miskibin.obd2dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class CsvFormatTest {

    @Test
    fun `header names the timestamp, the elapsed time and every column with its unit`() {
        val header = CsvFormat.header(
            listOf(CsvColumn("pid:0C", "rpm"), CsvColumn("battery", "V")),
        )
        assertEquals("timestamp,elapsed_s,pid:0C (rpm),battery (V)", header)
    }

    @Test
    fun `a column without a unit is not given empty brackets`() {
        assertEquals("timestamp,elapsed_s,pid:1F", CsvFormat.header(listOf(CsvColumn("pid:1F", ""))))
    }

    @Test
    fun `a row carries the elapsed seconds and one field per column`() {
        val start = 1_700_000_000_000L
        val row = CsvFormat.row(
            timestampMillis = start + 2_500L,
            startedAtMillis = start,
            values = listOf(1_234.0, null, 13.85),
            zone = UTC,
        )
        val fields = CsvFormat.splitRow(row)
        assertEquals(5, fields.size)
        assertEquals("2.5", fields[1])
        assertEquals("1234", fields[2])
        assertEquals("", fields[3])
        assertEquals("13.85", fields[4])
    }

    @Test
    fun `numbers always use a dot regardless of the phone's locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        try {
            assertEquals("13.85", CsvFormat.number(13.85))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `numbers are trimmed to at most three decimals with no trailing zeros`() {
        assertEquals("0", CsvFormat.number(0.0))
        assertEquals("88", CsvFormat.number(88.0))
        assertEquals("1.5", CsvFormat.number(1.5))
        assertEquals("0.333", CsvFormat.number(1.0 / 3.0))
        assertEquals("-12.25", CsvFormat.number(-12.25))
    }

    @Test
    fun `missing and non-finite values become empty fields, not zeros`() {
        assertEquals("", CsvFormat.number(null))
        assertEquals("", CsvFormat.number(Double.NaN))
        assertEquals("", CsvFormat.number(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `only fields that would break the row are quoted`() {
        assertEquals("plain", CsvFormat.escape("plain"))
        assertEquals("\"a,b\"", CsvFormat.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvFormat.escape("say \"hi\""))
        assertEquals("\"two\nlines\"", CsvFormat.escape("two\nlines"))
    }

    @Test
    fun `a quoted column name survives a round trip`() {
        val header = CsvFormat.header(listOf(CsvColumn("odd,name", "km/h")))
        assertEquals(listOf("timestamp", "elapsed_s", "odd,name (km/h)"), CsvFormat.splitRow(header))
    }

    @Test
    fun `the timestamp is written in local time to millisecond precision`() {
        assertEquals("2023-11-14T22:13:20.000", CsvFormat.timestamp(1_700_000_000_000L, UTC))
    }

    @Test
    fun `elapsed seconds can be read back out of a row`() {
        val start = 1_700_000_000_000L
        val row = CsvFormat.row(start + 90_000L, start, listOf(1.0), UTC)
        assertEquals(90.0, CsvFormat.elapsedOf(row)!!, 1e-9)
    }

    @Test
    fun `a header line has no elapsed value to read`() {
        assertNull(CsvFormat.elapsedOf(CsvFormat.header(listOf(CsvColumn("pid:0C", "rpm")))))
    }

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
    }
}
