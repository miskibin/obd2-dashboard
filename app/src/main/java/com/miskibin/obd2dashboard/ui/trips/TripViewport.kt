package com.miskibin.obd2dashboard.ui.trips

import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.TripEvent
import com.miskibin.obd2dashboard.data.TripPoint
import com.miskibin.obd2dashboard.data.TripTrace
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The slice of a recording the trip chart is drawing, in seconds from its start.
 *
 * A drive is half an hour and the thing worth looking at is four seconds of it, so the
 * chart cannot only ever show the whole file. Everything about the window — how far it can
 * be pushed, how small it can get, where a pinch leaves it — is arithmetic, and it lives
 * here rather than inside a gesture handler so it can be checked without a touchscreen.
 *
 * The window is always inside the recording: pushing past either end stops at the end
 * rather than scrolling into empty time, and a pinch can never shrink it below
 * [MIN_SPAN_SECONDS], where a trace thinned for drawing has nothing left to say.
 */
data class TripViewport(val startSeconds: Double, val endSeconds: Double) {

    val spanSeconds: Double get() = (endSeconds - startSeconds).coerceAtLeast(0.0)

    val centerSeconds: Double get() = (startSeconds + endSeconds) / 2.0

    /** Where [seconds] sits across the window, 0 at its start and 1 at its end. */
    fun fractionOf(seconds: Double): Double =
        if (spanSeconds <= 0.0) 0.0 else (seconds - startSeconds) / spanSeconds

    /** The inverse: the moment a fraction of the way across the window. */
    fun secondsAt(fraction: Double): Double = startSeconds + spanSeconds * fraction

    operator fun contains(seconds: Double): Boolean =
        seconds >= startSeconds && seconds <= endSeconds

    /** True while the window is still the whole recording, which is what "not zoomed" means. */
    fun isWholeTrip(durationSeconds: Double): Boolean =
        spanSeconds >= durationSeconds - EDGE_EPSILON

    /**
     * Puts the window back inside the recording, keeping its length.
     *
     * A pan that ran off the end shifts back rather than shrinking, because a window that
     * changed size when it hit the edge would make the chart lurch under the thumb.
     */
    fun clampedTo(durationSeconds: Double): TripViewport {
        if (durationSeconds <= 0.0) return TripViewport(0.0, 0.0)
        val span = spanSeconds.coerceIn(minSpanOf(durationSeconds), durationSeconds)
        val start = startSeconds.coerceIn(0.0, durationSeconds - span)
        return TripViewport(start, start + span)
    }

    /**
     * A pinch: [scale] above one zooms in, and the moment under [focusFraction] — the
     * centroid of the two fingers — stays where it is on the screen.
     */
    fun zoomedBy(scale: Double, focusFraction: Double, durationSeconds: Double): TripViewport {
        if (!scale.isFinite() || scale <= 0.0 || spanSeconds <= 0.0) return clampedTo(durationSeconds)
        val anchor = secondsAt(focusFraction.coerceIn(0.0, 1.0))
        val span = (spanSeconds / scale).coerceIn(minSpanOf(durationSeconds), durationSeconds)
        val start = anchor - (anchor - startSeconds) * (span / spanSeconds)
        return TripViewport(start, start + span).clampedTo(durationSeconds)
    }

    /** A drag, in fractions of the visible window: positive moves later into the drive. */
    fun pannedBy(fraction: Double, durationSeconds: Double): TripViewport {
        if (!fraction.isFinite()) return clampedTo(durationSeconds)
        val shift = spanSeconds * fraction
        return TripViewport(startSeconds + shift, endSeconds + shift).clampedTo(durationSeconds)
    }

    companion object {
        /**
         * The tightest window the chart will go to.
         *
         * A trace is thinned to a few hundred points before it ever reaches a screen, so
         * below a few seconds the plot stops resolving anything and starts drawing a
         * smooth curve through two readings.
         */
        const val MIN_SPAN_SECONDS = 5.0

        /**
         * The air an event gets around itself when the chart is sent to it.
         *
         * A quarter of a minute either side: a temperature that went over its limit is
         * only readable against the two minutes of driving that took it there.
         */
        const val EVENT_SPAN_SECONDS = 30.0

        fun whole(durationSeconds: Double) = TripViewport(0.0, durationSeconds.coerceAtLeast(0.0))

        /** A window of [spanSeconds] centred on a moment, pushed inside the recording. */
        fun around(centerSeconds: Double, spanSeconds: Double, durationSeconds: Double) =
            TripViewport(centerSeconds - spanSeconds / 2.0, centerSeconds + spanSeconds / 2.0)
                .clampedTo(durationSeconds)

        fun minSpanOf(durationSeconds: Double): Double =
            MIN_SPAN_SECONDS.coerceAtMost(durationSeconds.coerceAtLeast(0.0))

        private const val EDGE_EPSILON = 0.001
    }
}

/**
 * What a trip opens with when nobody has chosen anything.
 *
 * A recording now carries every parameter that was being polled, and all of them at once
 * is the plot the driver complained about. So: whatever earned a badge — that is why the
 * screen was opened — then the three a drive is normally read by, and no more than
 * [limit] of them. Everything else is a tap away in the picker.
 */
fun defaultTripSeries(
    traces: List<TripTrace>,
    events: List<TripEvent>,
    limit: Int = TRIP_SERIES_DEFAULT,
): List<MetricId> {
    val recorded = traces.map(TripTrace::metric)
    val wanted = (events.map(TripEvent::metric) + LEADING_TRIP_METRICS)
        .distinct()
        .filter(recorded::contains)
    return (wanted.ifEmpty { recorded }).take(limit.coerceAtLeast(0))
}

/**
 * The points inside the window, plus the one either side of it.
 *
 * Without the neighbours a zoomed trace stops short of both edges and reads as a gap in
 * the recording; with them the line runs off the plot the way it actually did.
 */
fun TripTrace.pointsIn(viewport: TripViewport): List<TripPoint> {
    if (points.isEmpty()) return emptyList()
    if (points.last().seconds < viewport.startSeconds) return emptyList()
    if (points.first().seconds > viewport.endSeconds) return emptyList()
    val from = points.indexOfLast { it.seconds < viewport.startSeconds }.coerceAtLeast(0)
    val to = points.indexOfFirst { it.seconds > viewport.endSeconds }
        .let { if (it < 0) points.lastIndex else it }
    return points.subList(from, to + 1)
}

/** The nearest reading to a moment, which is what a cursor on a thinned trace can honestly say. */
fun TripTrace.valueAt(seconds: Double): Double? =
    points.minByOrNull { abs(it.seconds - seconds) }?.value

/**
 * The maximum of every trace within the window.
 *
 * The trip's own maxima are computed from every sample in the file and stay the right
 * answer for the whole drive; this is the same question asked of what is on the screen,
 * and it can only be as precise as the thinned trace it reads.
 */
fun List<TripTrace>.maximaIn(viewport: TripViewport): Map<MetricId, Double> = buildMap {
    this@maximaIn.forEach { trace ->
        val peak = trace.points
            .filter { it.seconds >= viewport.startSeconds && it.seconds <= viewport.endSeconds }
            .maxOfOrNull(TripPoint::value)
        if (peak != null) put(trace.metric, peak)
    }
}

/** The three labels under the plot: the start of the window, its middle and its end. */
fun tripAxisLabels(viewport: TripViewport): List<String> =
    listOf(0.0, 0.5, 1.0).map { formatElapsed(viewport.secondsAt(it), viewport.spanSeconds) }

/**
 * Time into the drive, at the precision the window deserves.
 *
 * Minutes and seconds normally, tenths once the window is down to a minute — at that zoom
 * the whole point is which second something happened on, and three labels reading `2:31`
 * would say nothing about where the plot begins and ends.
 */
fun formatElapsed(seconds: Double, spanSeconds: Double): String {
    val tenths = (seconds.coerceAtLeast(0.0) * 10).roundToLong()
    val whole = tenths / 10
    val minutes = whole / 60
    val remainder = whole % 60
    return if (spanSeconds <= FINE_SPAN_SECONDS) {
        "%d:%02d.%d".format(Locale.getDefault(), minutes, remainder, tenths % 10)
    } else {
        "%d:%02d".format(Locale.getDefault(), minutes, remainder)
    }
}

/** As many traces as a plot can carry, and as many as the palette has distinct colours. */
const val TRIP_SERIES_DEFAULT = 4

/** Under this the axis counts in tenths of a second. */
private const val FINE_SPAN_SECONDS = 60.0

/** How a drive is read when nothing in it went wrong: how fast, how hard, how hot. */
private val LEADING_TRIP_METRICS = listOf(Metrics.Speed, Metrics.Rpm, Metrics.CoolantTemp)
