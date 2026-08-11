package com.miskibin.obd2dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TripAnalyzerTest {

    private val rpmKey = Metrics.Rpm.storageKey
    private val speedKey = Metrics.Speed.storageKey
    private val oilKey = Metrics.OilTemp.storageKey

    /**
     * A minute of driving at a steady 60 km/h, with the oil over its limit for ten
     * seconds of it and one brush against the limiter.
     */
    private fun recording(): File {
        val header = "timestamp,elapsed_s,$rpmKey (rpm),$speedKey (km/h),$oilKey (°C)"
        val rows = (0..60).map { second ->
            val rpm = if (second == 30) 7_900 else 2_500
            val oil = if (second in 10..20) 115 else 95
            "2026-08-14T17:42:00.000,$second,$rpm,60,$oil"
        }
        return File.createTempFile("trip-", ".csv").apply {
            deleteOnExit()
            writeText((listOf(header) + rows).joinToString("\n"))
        }
    }

    private fun analyze(file: File = recording()) =
        TripAnalyzer.analyze(file, AlertRules.defaults, redline = 8_000)

    @Test
    fun `integrates road speed into a distance`() {
        // Sixty seconds at 60 km/h is a kilometre, and no PID reports that directly.
        assertEquals(1.0, analyze().distanceKm ?: 0.0, 0.05)
    }

    @Test
    fun `keeps the maximum of every column`() {
        val analysis = analyze()

        assertEquals(7_900.0, analysis.maxima.getValue(Metrics.Rpm), 0.001)
        assertEquals(115.0, analysis.maxima.getValue(Metrics.OilTemp), 0.001)
    }

    @Test
    fun `reports the length of the recording`() {
        assertEquals(60.0, analyze().durationSeconds, 0.001)
    }

    @Test
    fun `collapses a long breach into one event with its duration`() {
        val event = analyze().events.single { it.metric == Metrics.OilTemp }

        assertEquals(TripEventKind.ThresholdBreach, event.kind)
        assertEquals(10.0, event.startSeconds, 0.001)
        assertEquals(10.0, event.durationSeconds, 0.001)
        assertEquals(115.0, event.peak, 0.001)
        assertEquals(1, event.occurrences)
    }

    @Test
    fun `notices the limiter being reached`() {
        val event = analyze().events.single { it.kind == TripEventKind.Redline }

        assertEquals(30.0, event.startSeconds, 0.001)
        assertEquals(7_900.0, event.peak, 0.001)
    }

    @Test
    fun `has nothing to say about consumption the car never reported`() {
        assertNull(analyze().averageFuelPer100Km)
    }

    @Test
    fun `thins a long trace down to something drawable`() {
        val trace = analyze().traces.single { it.metric == Metrics.Rpm }

        assertTrue(trace.points.isNotEmpty())
        assertTrue(trace.points.size <= TripAnalyzer.TRACE_RESOLUTION + 1)
    }

    @Test
    fun `does not integrate over a gap in the polling`() {
        // The adapter dropped out for ten minutes; the car did not drive ten minutes at
        // 60 km/h while nobody was listening.
        val header = "timestamp,elapsed_s,$speedKey (km/h)"
        val file = File.createTempFile("trip-", ".csv").apply {
            deleteOnExit()
            writeText("$header\n2026-08-14T17:42:00.000,0,60\n2026-08-14T17:52:00.000,600,60")
        }

        val distance = TripAnalyzer.analyze(file).distanceKm ?: 0.0

        assertTrue("integrated $distance km across the gap", distance < 0.1)
    }

    @Test
    fun `returns nothing rather than failing on an unreadable file`() {
        val analysis = TripAnalyzer.analyze(File("does-not-exist.csv"))

        assertTrue(analysis.isEmpty)
        assertTrue(analysis.events.isEmpty())
    }
}
