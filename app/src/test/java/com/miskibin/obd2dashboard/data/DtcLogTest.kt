package com.miskibin.obd2dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcLogTest {

    private val start = 1_700_000_000_000L

    @Test
    fun `first sighting records one occurrence`() {
        val log = DtcLog.merge(emptyList(), listOf("P0300"), previouslyPresent = emptySet(), nowMillis = start)

        assertEquals(1, log.size)
        assertEquals("P0300", log.first().code)
        assertEquals(1, log.first().occurrences)
        assertEquals(start, log.first().firstSeenAtMillis)
    }

    @Test
    fun `a code that merely stays stored does not count again`() {
        val first = DtcLog.merge(emptyList(), listOf("P0300"), emptySet(), start)
        val second = DtcLog.merge(first, listOf("P0300"), setOf("P0300"), start + 60_000)

        assertEquals(1, second.first().occurrences)
        assertEquals(start, second.first().firstSeenAtMillis)
        assertEquals(start + 60_000, second.first().lastSeenAtMillis)
    }

    @Test
    fun `a code that comes back after being absent counts again`() {
        val first = DtcLog.merge(emptyList(), listOf("P0300"), emptySet(), start)
        val returned = DtcLog.merge(first, listOf("P0300"), previouslyPresent = emptySet(), nowMillis = start + 600_000)

        assertEquals(2, returned.first().occurrences)
        assertEquals(start, returned.first().firstSeenAtMillis)
    }

    @Test
    fun `survives a round trip through storage`() {
        val log = DtcLog.merge(emptyList(), listOf("P0300", "P0171"), emptySet(), start)

        assertEquals(log, DtcLog.decode(DtcLog.encode(log)))
    }

    @Test
    fun `ignores storage it cannot read`() {
        assertEquals(emptyList<DtcObservation>(), DtcLog.decode("nonsense"))
        assertEquals(emptyList<DtcObservation>(), DtcLog.decode(null))
    }

    @Test
    fun `related codes keep the sign that says which came first`() {
        val log = listOf(
            DtcObservation("P0300", start, start, 1),
            DtcObservation("P0171", start - 18_000, start - 18_000, 1),
            DtcObservation("P0420", start + 4_000, start + 4_000, 1),
        )

        val related = DtcLog.relatedTo("P0300", log)

        assertEquals(listOf("P0171", "P0420"), related.map { it.code })
        assertEquals(-18L, related.first().offsetSeconds)
        assertTrue(related.first().earlier)
        assertFalse(related.last().earlier)
    }

    @Test
    fun `a code from another drive is not related`() {
        val log = listOf(
            DtcObservation("P0300", start, start, 1),
            DtcObservation("P0420", start - DtcLog.RELATED_WINDOW_MILLIS - 1, start - DtcLog.RELATED_WINDOW_MILLIS - 1, 1),
        )

        assertTrue(DtcLog.relatedTo("P0300", log).isEmpty())
    }

    @Test
    fun `highlights a change worth pointing at`() {
        assertTrue(DtcLog.isNotable(Metrics.EngineLoad, before = 44.0, at = 86.0))
        assertFalse(DtcLog.isNotable(Metrics.EngineLoad, before = 44.0, at = 46.0))
    }

    @Test
    fun `judges fuel trim by how far it moved, not by what fraction`() {
        // A trim going from +1.6 to +12.4 is the whole story of a lean fault, and yet as
        // a fraction of a small number every trim change looks enormous.
        assertTrue(DtcLog.isNotable(Metrics.ShortTrim, before = 1.6, at = 12.4))
        assertFalse(DtcLog.isNotable(Metrics.ShortTrim, before = 1.6, at = 3.0))
    }

    @Test
    fun `says nothing about a value it only has one side of`() {
        assertFalse(DtcLog.isNotable(Metrics.EngineLoad, before = null, at = 86.0))
        assertFalse(DtcLog.isNotable(Metrics.EngineLoad, before = 44.0, at = null))
    }
}
