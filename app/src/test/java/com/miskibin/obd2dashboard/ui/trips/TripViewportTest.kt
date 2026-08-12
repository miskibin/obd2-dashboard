package com.miskibin.obd2dashboard.ui.trips

import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.TripEvent
import com.miskibin.obd2dashboard.data.TripEventKind
import com.miskibin.obd2dashboard.data.TripPoint
import com.miskibin.obd2dashboard.data.TripTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ten minutes of driving, sampled once a second. */
private const val DURATION = 600.0

class TripViewportTest {

    private val whole = TripViewport.whole(DURATION)

    private fun trace(metric: MetricId, values: List<Pair<Double, Double>>) =
        TripTrace(metric, values.map { (seconds, value) -> TripPoint(seconds, value) })

    private fun event(metric: MetricId, start: Double, end: Double) = TripEvent(
        kind = TripEventKind.ThresholdBreach,
        metric = metric,
        startSeconds = start,
        endSeconds = end,
        peak = 1.0,
        occurrences = 1,
    )

    @Test
    fun `a fresh viewport is the whole recording`() {
        assertEquals(0.0, whole.startSeconds, 0.001)
        assertEquals(DURATION, whole.endSeconds, 0.001)
        assertTrue(whole.isWholeTrip(DURATION))
    }

    @Test
    fun `zooming keeps the moment under the fingers where it was`() {
        // Pinching at a quarter across should leave that instant a quarter across.
        val zoomed = whole.zoomedBy(scale = 4.0, focusFraction = 0.25, durationSeconds = DURATION)

        assertEquals(150.0, zoomed.secondsAt(0.25), 0.001)
        assertEquals(150.0, zoomed.spanSeconds, 0.001)
        assertFalse(zoomed.isWholeTrip(DURATION))
    }

    @Test
    fun `zooming out never shows more than the recording`() {
        val zoomed = TripViewport(100.0, 200.0).zoomedBy(0.01, 0.5, DURATION)

        assertEquals(0.0, zoomed.startSeconds, 0.001)
        assertEquals(DURATION, zoomed.endSeconds, 0.001)
    }

    @Test
    fun `zooming in stops where a thinned trace stops saying anything`() {
        val zoomed = whole.zoomedBy(scale = 10_000.0, focusFraction = 0.5, durationSeconds = DURATION)

        assertEquals(TripViewport.MIN_SPAN_SECONDS, zoomed.spanSeconds, 0.001)
    }

    @Test
    fun `a pinch at the edge of the plot stays inside the recording`() {
        val zoomed = whole.zoomedBy(scale = 4.0, focusFraction = 1.0, durationSeconds = DURATION)

        assertEquals(450.0, zoomed.startSeconds, 0.001)
        assertEquals(DURATION, zoomed.endSeconds, 0.001)
    }

    @Test
    fun `panning moves the window without resizing it`() {
        val window = TripViewport(100.0, 160.0)

        val panned = window.pannedBy(0.5, DURATION)

        assertEquals(130.0, panned.startSeconds, 0.001)
        assertEquals(60.0, panned.spanSeconds, 0.001)
    }

    @Test
    fun `panning past the end stops at the end rather than shrinking`() {
        val panned = TripViewport(540.0, 600.0).pannedBy(5.0, DURATION)

        assertEquals(540.0, panned.startSeconds, 0.001)
        assertEquals(600.0, panned.endSeconds, 0.001)
    }

    @Test
    fun `panning before the start stops at zero`() {
        val panned = TripViewport(10.0, 70.0).pannedBy(-5.0, DURATION)

        assertEquals(0.0, panned.startSeconds, 0.001)
        assertEquals(60.0, panned.spanSeconds, 0.001)
    }

    @Test
    fun `an event near the start opens a full window from the beginning`() {
        // Half a minute centred on the fifth second cannot start at −10 s.
        val around = TripViewport.around(5.0, TripViewport.EVENT_SPAN_SECONDS, DURATION)

        assertEquals(0.0, around.startSeconds, 0.001)
        assertEquals(TripViewport.EVENT_SPAN_SECONDS, around.spanSeconds, 0.001)
        assertTrue(300.0 !in around)
    }

    @Test
    fun `an event in a recording shorter than the window shows the whole recording`() {
        val around = TripViewport.around(4.0, TripViewport.EVENT_SPAN_SECONDS, durationSeconds = 8.0)

        assertEquals(0.0, around.startSeconds, 0.001)
        assertEquals(8.0, around.endSeconds, 0.001)
    }

    @Test
    fun `a recording with no length is a window with no length`() {
        val empty = TripViewport.whole(0.0).zoomedBy(4.0, 0.5, 0.0)

        assertEquals(0.0, empty.spanSeconds, 0.001)
        assertTrue(empty.isWholeTrip(0.0))
    }

    @Test
    fun `fractions and seconds are inverses of each other`() {
        val window = TripViewport(120.0, 180.0)

        assertEquals(0.5, window.fractionOf(150.0), 0.001)
        assertEquals(150.0, window.secondsAt(0.5), 0.001)
    }
}

class TripSeriesSelectionTest {

    private val boost = MetricId.Derived("boost")

    private fun trace(metric: MetricId) =
        TripTrace(metric, listOf(TripPoint(0.0, 1.0), TripPoint(1.0, 2.0)))

    private fun breach(metric: MetricId) = TripEvent(
        kind = TripEventKind.ThresholdBreach,
        metric = metric,
        startSeconds = 10.0,
        endSeconds = 20.0,
        peak = 120.0,
        occurrences = 1,
    )

    @Test
    fun `a recording of everything opens on the few a drive is read by`() {
        val traces = listOf(
            trace(boost),
            trace(Metrics.Speed),
            trace(Metrics.Rpm),
            trace(Metrics.CoolantTemp),
            trace(Metrics.IntakeAirTemp),
            trace(Metrics.EngineLoad),
        )

        assertEquals(
            listOf(Metrics.Speed, Metrics.Rpm, Metrics.CoolantTemp),
            defaultTripSeries(traces, events = emptyList()),
        )
    }

    @Test
    fun `whatever earned a badge is on the chart before anything else`() {
        val traces = listOf(
            trace(Metrics.Speed),
            trace(Metrics.Rpm),
            trace(Metrics.CoolantTemp),
            trace(Metrics.OilTemp),
        )

        val selected = defaultTripSeries(traces, listOf(breach(Metrics.OilTemp)))

        assertEquals(Metrics.OilTemp, selected.first())
        assertEquals(4, selected.size)
    }

    @Test
    fun `never more than the plot can carry`() {
        val traces = listOf(
            trace(Metrics.Speed),
            trace(Metrics.Rpm),
            trace(Metrics.CoolantTemp),
            trace(Metrics.OilTemp),
            trace(boost),
        )
        val events = listOf(breach(Metrics.OilTemp), breach(boost))

        assertEquals(TRIP_SERIES_DEFAULT, defaultTripSeries(traces, events).size)
    }

    @Test
    fun `a badge on a parameter the recording never carried is not plotted`() {
        val traces = listOf(trace(Metrics.Speed))

        assertEquals(
            listOf(Metrics.Speed),
            defaultTripSeries(traces, listOf(breach(Metrics.OilTemp))),
        )
    }

    @Test
    fun `a recording of nothing familiar still opens on something`() {
        val traces = listOf(trace(boost), trace(MetricId.Derived("gear")))

        assertEquals(traces.map(TripTrace::metric), defaultTripSeries(traces, emptyList()))
    }

    @Test
    fun `an empty recording selects nothing`() {
        assertTrue(defaultTripSeries(emptyList(), emptyList()).isEmpty())
    }
}

class TripWindowStatsTest {

    private val trace = TripTrace(
        metric = Metrics.Rpm,
        points = (0..60).map { second -> TripPoint(second.toDouble(), 1_000.0 + second * 100) },
    )
    private val speed = TripTrace(
        metric = Metrics.Speed,
        points = (0..60).map { second -> TripPoint(second.toDouble(), 60.0) },
    )

    @Test
    fun `the maxima follow the window rather than the whole drive`() {
        val maxima = listOf(trace, speed).maximaIn(TripViewport(10.0, 20.0))

        assertEquals(3_000.0, maxima.getValue(Metrics.Rpm), 0.001)
        assertEquals(60.0, maxima.getValue(Metrics.Speed), 0.001)
    }

    @Test
    fun `a window with no readings in it reports no maximum`() {
        val maxima = listOf(trace).maximaIn(TripViewport(100.0, 200.0))

        assertTrue(maxima.isEmpty())
    }

    @Test
    fun `the visible points reach past both edges so the line does not stop short`() {
        val visible = trace.pointsIn(TripViewport(10.5, 20.5))

        assertEquals(10.0, visible.first().seconds, 0.001)
        assertEquals(21.0, visible.last().seconds, 0.001)
    }

    @Test
    fun `a window at the very start begins at the first reading`() {
        val visible = trace.pointsIn(TripViewport(0.0, 5.0))

        assertEquals(0.0, visible.first().seconds, 0.001)
        assertEquals(6.0, visible.last().seconds, 0.001)
    }

    @Test
    fun `a trace that ends before the window is not drawn at all`() {
        assertTrue(trace.pointsIn(TripViewport(100.0, 200.0)).isEmpty())
    }

    @Test
    fun `the cursor reads the nearest reading it has`() {
        assertEquals(2_500.0, trace.valueAt(15.4) ?: 0.0, 0.001)
        assertNull(TripTrace(Metrics.Rpm, emptyList()).valueAt(15.0))
    }
}

class TripAxisLabelTest {

    @Test
    fun `a whole trip counts in minutes and seconds`() {
        assertEquals(
            listOf("0:00", "5:00", "10:00"),
            tripAxisLabels(TripViewport.whole(600.0)),
        )
    }

    @Test
    fun `zoomed into a minute the axis counts in tenths`() {
        assertEquals(
            listOf("2:00.0", "2:15.0", "2:30.0"),
            tripAxisLabels(TripViewport(120.0, 150.0)),
        )
    }

    @Test
    fun `past an hour the minutes keep counting rather than wrapping`() {
        assertEquals("75:30", formatElapsed(4_530.0, spanSeconds = 600.0))
    }
}
