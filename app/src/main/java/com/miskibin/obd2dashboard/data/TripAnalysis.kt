package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.DerivedMetrics
import java.io.File

/**
 * A recording plus whatever has been read back out of it.
 *
 * [analysis] is null while the file is still being parsed, which is what lets the trip
 * list appear immediately and fill in behind itself.
 */
data class TripEntry(val trip: Trip, val analysis: TripAnalysis?)

/** Something worth a badge on the trip: a threshold crossed, or the limiter reached. */
data class TripEvent(
    val kind: TripEventKind,
    val metric: MetricId,
    /** Seconds from the start of the recording. */
    val startSeconds: Double,
    val endSeconds: Double,
    /** The furthest the value got while the event lasted. */
    val peak: Double,
    val occurrences: Int,
) {
    val durationSeconds: Double get() = (endSeconds - startSeconds).coerceAtLeast(0.0)
}

enum class TripEventKind {
    /** A value spent time on the wrong side of one of the driver's alert rules. */
    ThresholdBreach,

    /** Engine speed reached the configured redline. */
    Redline,
}

/** One parameter's trace through a trip, already thinned for drawing. */
data class TripTrace(val metric: MetricId, val points: List<TripPoint>)

data class TripPoint(val seconds: Double, val value: Double)

/**
 * Everything the trip list and the trip screen need, read back out of the recording.
 *
 * The CSV is the only record of a drive — nothing about a trip is kept in a database — so
 * the summary is derived from the file each time. That keeps a recording a self-contained
 * artefact the driver can mail to somebody, at the cost of a parse per trip; the parse is
 * a single streamed pass and never holds more than the thinned traces in memory.
 */
data class TripAnalysis(
    val distanceKm: Double?,
    val durationSeconds: Double,
    val averageFuelPer100Km: Double?,
    val maxima: Map<MetricId, Double>,
    val events: List<TripEvent>,
    val traces: List<TripTrace>,
) {
    val isEmpty: Boolean get() = durationSeconds <= 0.0 && traces.all { it.points.isEmpty() }

    companion object {
        val EMPTY = TripAnalysis(null, 0.0, null, emptyMap(), emptyList(), emptyList())
    }
}

/**
 * Reads a recording back into something a screen can draw.
 *
 * Distance and fuel are integrated rather than read: no generic PID reports trip distance
 * or trip consumption, but road speed and fuel rate sampled ten times a second integrate
 * to both. The result is close enough to a trip computer to be worth showing and, like
 * everything else here, is only ever as good as what the car answered.
 */
object TripAnalyzer {

    /**
     * The parameters that lead the trip chart when the recording has them.
     *
     * Everything else in the file follows in the order it was recorded — a recording is
     * whatever the driver was watching, and pinning the trace list to four metrics is how
     * a drive spent charting a dozen came back looking like the defaults.
     */
    val TRACE_METRICS: List<MetricId> = listOf(
        Metrics.Speed,
        Metrics.OilTemp,
        Metrics.CoolantTemp,
        Metrics.Rpm,
    )

    /** How many points a trace keeps; more than this is invisible on a phone. */
    const val TRACE_RESOLUTION = 320

    fun analyze(
        file: File,
        alertRules: List<AlertRule> = AlertRules.defaults,
        redline: Int = Metrics.REDLINE_DEFAULT,
    ): TripAnalysis = runCatching { read(file, alertRules, redline) }.getOrDefault(TripAnalysis.EMPTY)

    private fun read(file: File, alertRules: List<AlertRule>, redline: Int): TripAnalysis {
        var columns: Map<MetricId, Int> = emptyMap()
        var elapsed = 0.0
        var previousElapsed = 0.0
        var distanceKm = 0.0
        var litres = 0.0
        var sawSpeed = false
        var sawFuelRate = false
        val maxima = HashMap<MetricId, Double>()
        // Every column gets a trace, so the buffers are decimated as they fill rather
        // than held whole: a long drive with forty parameters on it would otherwise be
        // a million points in memory before anything got thinned.
        val traceValues = LinkedHashMap<MetricId, TraceBuffer>()
        val breachTracker = BreachTracker(alertRules, redline)

        file.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val fields = CsvFormat.splitRow(line)
                if (index == 0) {
                    columns = headerColumns(fields)
                    return@forEachIndexed
                }
                previousElapsed = elapsed
                elapsed = fields.getOrNull(ELAPSED_FIELD)?.toDoubleOrNull() ?: return@forEachIndexed
                val step = (elapsed - previousElapsed).coerceIn(0.0, MAX_STEP_SECONDS)

                columns.forEach { (metric, column) ->
                    val value = fields.getOrNull(column)?.toDoubleOrNull() ?: return@forEach
                    if (!value.isFinite()) return@forEach

                    val current = maxima[metric]
                    if (current == null || value > current) maxima[metric] = value
                    traceValues.getOrPut(metric) { TraceBuffer() }.add(TripPoint(elapsed, value))
                    breachTracker.observe(metric, value, elapsed)

                    when (metric) {
                        Metrics.Speed -> {
                            sawSpeed = true
                            distanceKm += value * step / 3_600.0
                        }

                        FUEL_RATE -> {
                            sawFuelRate = true
                            litres += value * step / 3_600.0
                        }

                        else -> Unit
                    }
                }
            }
        }

        val distance = distanceKm.takeIf { sawSpeed && it > 0.0 }
        return TripAnalysis(
            distanceKm = distance,
            durationSeconds = elapsed,
            averageFuelPer100Km = when {
                sawFuelRate && distance != null && distance > MIN_DISTANCE_KM ->
                    litres * 100.0 / distance

                else -> null
            },
            maxima = maxima,
            events = breachTracker.finish(elapsed),
            traces = tracesOf(traceValues),
        )
    }

    /** The leading metrics first, then every other column in the order it was recorded. */
    private fun tracesOf(buffers: Map<MetricId, TraceBuffer>): List<TripTrace> {
        val order = TRACE_METRICS.filter(buffers::containsKey) +
            buffers.keys.filterNot(TRACE_METRICS::contains)
        return order.mapNotNull { metric ->
            val points = thin(buffers.getValue(metric).points())
            if (points.isEmpty()) null else TripTrace(metric, points)
        }
    }

    /** Maps the header's `pid:0C (rpm)` columns onto metric ids, ignoring the rest. */
    private fun headerColumns(fields: List<String>): Map<MetricId, Int> = buildMap {
        fields.forEachIndexed { index, field ->
            if (index <= ELAPSED_FIELD) return@forEachIndexed
            val key = field.substringBefore(" (").trim()
            val id = MetricId.parse(key) ?: return@forEachIndexed
            put(id, index)
        }
    }

    /**
     * Keeps every nth point rather than averaging.
     *
     * An averaged trace hides the spike that the whole screen exists to show; dropping
     * points can miss one, but the event badges are computed from every sample, so
     * nothing that mattered is decided by what survived the thinning.
     */
    private fun thin(points: List<TripPoint>): List<TripPoint> {
        if (points.size <= TRACE_RESOLUTION) return points
        val stride = points.size.toDouble() / TRACE_RESOLUTION
        return (0 until TRACE_RESOLUTION).map { index -> points[(index * stride).toInt()] } +
            points.last()
    }

    /** How many points a buffer holds before it halves itself; see [TraceBuffer]. */
    internal const val TRACE_BUFFER_LIMIT = TRACE_RESOLUTION * 2

    private val FUEL_RATE = MetricId.Derived(DerivedMetrics.FuelRate.key)

    private const val ELAPSED_FIELD = 1

    /**
     * A gap longer than this is a pause in polling, not time spent driving — integrating
     * over it would invent kilometres the car never covered.
     */
    private const val MAX_STEP_SECONDS = 5.0

    private const val MIN_DISTANCE_KM = 0.1
}

/**
 * A trace under construction, kept to a fixed size however long the drive was.
 *
 * Every recorded parameter now gets a trace, so the parse can no longer afford to hold
 * every sample of every column until the end. When a buffer fills it drops every second
 * point and starts taking every second sample instead — the same decimation the finished
 * trace gets, applied early, so memory is bounded by the number of columns rather than by
 * the length of the drive. The most recent point is always kept so a trace still ends
 * where the recording did.
 */
private class TraceBuffer(private val limit: Int = TripAnalyzer.TRACE_BUFFER_LIMIT) {

    private val kept = ArrayList<TripPoint>()
    private var stride = 1
    private var skipped = 0
    private var last: TripPoint? = null

    fun add(point: TripPoint) {
        last = point
        if (skipped + 1 < stride) {
            skipped++
            return
        }
        skipped = 0
        kept += point
        if (kept.size > limit) compact()
    }

    fun points(): List<TripPoint> {
        val tail = last ?: return emptyList()
        return if (kept.lastOrNull() == tail) kept.toList() else kept + tail
    }

    private fun compact() {
        var write = 0
        var read = 0
        while (read < kept.size) {
            kept[write++] = kept[read]
            read += 2
        }
        while (kept.size > write) kept.removeAt(kept.lastIndex)
        stride *= 2
        skipped = 0
    }
}

/**
 * Turns a stream of readings into episodes.
 *
 * A temperature that sits above its limit for forty seconds is one event, not four
 * hundred; the tracker holds an episode open while the value stays on the wrong side and
 * closes it when the value comes back, so what the trip screen lists is what a person
 * would call a thing that happened.
 */
private class BreachTracker(alertRules: List<AlertRule>, private val redline: Int) {

    private data class Open(
        val kind: TripEventKind,
        val metric: MetricId,
        val start: Double,
        var end: Double,
        var peak: Double,
    )

    private val rules = alertRules.filter(AlertRule::enabled)
    private val open = HashMap<MetricId, Open>()
    private val closed = mutableListOf<TripEvent>()

    fun observe(metric: MetricId, value: Double, elapsed: Double) {
        val breached = when {
            metric == Metrics.Rpm -> value >= redline * REDLINE_FRACTION
            else -> rules.any { it.metric == metric && it.isBreached(value) }
        }
        val kind = if (metric == Metrics.Rpm) TripEventKind.Redline else TripEventKind.ThresholdBreach
        val current = open[metric]
        when {
            breached && current == null ->
                open[metric] = Open(kind, metric, elapsed, elapsed, value)

            breached && current != null -> {
                current.end = elapsed
                if (value > current.peak) current.peak = value
            }

            !breached && current != null -> {
                closed += current.toEvent()
                open.remove(metric)
            }
        }
    }

    /** Closes anything still open — a recording can stop mid-event. */
    fun finish(elapsed: Double): List<TripEvent> {
        open.values.forEach { entry ->
            entry.end = maxOf(entry.end, elapsed)
            closed += entry.toEvent()
        }
        open.clear()
        return merge(closed)
    }

    private fun Open.toEvent() = TripEvent(kind, metric, start, end, peak, occurrences = 1)

    /**
     * Collapses repeats of the same thing into one badge.
     *
     * "Hit the limiter twice" is one fact about a drive; two identical badges is the same
     * fact printed twice.
     */
    private fun merge(events: List<TripEvent>): List<TripEvent> = events
        .groupBy { it.metric to it.kind }
        .map { (_, group) ->
            group.first().copy(
                endSeconds = group.maxOf(TripEvent::endSeconds),
                peak = group.maxOf(TripEvent::peak),
                occurrences = group.size,
            )
        }
        .sortedBy(TripEvent::startSeconds)

    private companion object {
        /** Close enough to the limiter to count as having reached it. */
        const val REDLINE_FRACTION = 0.97
    }
}
