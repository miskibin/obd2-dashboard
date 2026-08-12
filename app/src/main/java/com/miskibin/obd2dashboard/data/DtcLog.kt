package com.miskibin.obd2dashboard.data

import kotlin.math.abs

/**
 * When the app first and last saw a code, and how often it has come back.
 *
 * OBD2 itself carries none of this: mode 03 answers with a list of codes and nothing
 * else — no timestamp, no counter, no order. The ECU knows when it set a code and will
 * not say. So the app keeps its own log, which is honest about what it is: not "when the
 * fault happened" but "when this app saw it", which for a code that appears mid-drive
 * with the app connected is the same second, and for one that was already stored when the
 * adapter was plugged in is simply the first time anybody looked.
 */
data class DtcObservation(
    val code: String,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
    /** How many times the code has come back after being absent from a read. */
    val occurrences: Int,
) {
    /** True when the app was watching as the code appeared, rather than finding it stored. */
    val seenLive: Boolean get() = occurrences > 1 || firstSeenAtMillis != lastSeenAtMillis
}

/** Another code that showed up around the same time, and how far from it. */
data class RelatedCode(val code: String, val offsetSeconds: Long) {
    val earlier: Boolean get() = offsetSeconds < 0
}

/**
 * One row of the before-and-during table.
 *
 * [before] is what the app had recorded a few seconds earlier and is null when it was not
 * recording; [at] comes from the ECU's own frozen frame where there is one, and from the
 * app's history otherwise.
 */
data class FaultRow(
    val metric: MetricId,
    val before: Double?,
    val at: Double?,
    /** Whether the value moved far enough to be worth pointing at. */
    val notable: Boolean,
)

/**
 * What the app itself recorded around the moment a code appeared.
 *
 * This only exists for codes that showed up while the app was connected and watching —
 * the history it is cut from is a ten-minute ring buffer in memory, not a file. That
 * limitation is deliberate and is stated on the screen: a mechanic reading "no recording
 * from that moment" knows to trust only the ECU's frozen frame.
 */
data class FaultContext(
    val code: String,
    val detectedAtMillis: Long,
    val traces: Map<MetricId, List<Sample>>,
    val before: Map<MetricId, Double>,
    val at: Map<MetricId, Double>,
    /**
     * False until the half-minute after the detection has actually elapsed.
     *
     * A code is noticed the moment it appears, when only the time before it exists; the
     * context is captured again once the rest of the window has been driven, so the chart
     * grows from "the half-minute up to the fault" into the ±30 s the screen promises
     * rather than pretending to have the future.
     */
    val complete: Boolean = false,
) {
    val hasTimeline: Boolean get() = traces.any { it.value.size > 1 }
}

object DtcLog {

    /** The window either side of a code in which another code counts as related. */
    const val RELATED_WINDOW_MILLIS = 5 * 60 * 1_000L

    /** How far back the "before" column reaches. */
    const val BEFORE_OFFSET_MILLIS = 5_000L

    /** How much of the trace either side of the detection the fault screen plots. */
    const val TIMELINE_WINDOW_MILLIS = 60_000L

    /** The parameters the fault timeline draws, in drawing order. */
    val TIMELINE_METRICS: List<MetricId> = listOf(Metrics.Rpm, Metrics.EngineLoad, Metrics.Speed)

    /** The rows of the before-and-during table, in the order a mechanic reads them. */
    val SNAPSHOT_METRICS: List<MetricId> = listOf(
        Metrics.Rpm,
        Metrics.Speed,
        Metrics.EngineLoad,
        Metrics.Throttle,
        Metrics.CoolantTemp,
        Metrics.Battery,
        Metrics.ShortTrim,
        Metrics.LongTrim,
        Metrics.Timing,
        Metrics.Maf,
    )

    /**
     * Codes seen close enough to [code] to be worth naming, nearest first.
     *
     * Order matters more than proximity here: which code appeared first is the thing a
     * mechanic uses to tell a cause from its symptom, so the offset keeps its sign.
     */
    fun relatedTo(code: String, log: List<DtcObservation>): List<RelatedCode> {
        val subject = log.firstOrNull { it.code == code } ?: return emptyList()
        return log.asSequence()
            .filter { it.code != code }
            .mapNotNull { other ->
                val delta = other.lastSeenAtMillis - subject.lastSeenAtMillis
                if (abs(delta) > RELATED_WINDOW_MILLIS) return@mapNotNull null
                RelatedCode(other.code, delta / 1_000)
            }
            .sortedBy(RelatedCode::offsetSeconds)
            .toList()
    }

    /** A change worth highlighting: a fifth of the reading, or any sign flip on a trim. */
    fun isNotable(metric: MetricId, before: Double?, at: Double?): Boolean {
        if (before == null || at == null) return false
        val delta = abs(at - before)
        if (metric == Metrics.ShortTrim || metric == Metrics.LongTrim) return delta >= TRIM_DELTA
        val scale = maxOf(abs(before), abs(at))
        if (scale < EPSILON) return false
        return delta / scale >= NOTABLE_FRACTION
    }

    // ---- storage ----------------------------------------------------------------

    /**
     * The preference each session kind's log lives under.
     *
     * Two keys rather than a flag on each entry: a flag would still put the simulation's
     * P0420 in the same list as the car's, one `filter` away from being shown to a
     * mechanic. Separate strings mean a demo session physically cannot write into the real
     * history — [recordInto] is handed one log and has no way to reach the other.
     *
     * [SessionKind.Real] keeps the original key, so a driver upgrading keeps the history
     * they had.
     */
    fun storageKey(kind: SessionKind): String = when (kind) {
        SessionKind.Real -> "dtc_log"
        SessionKind.Demo -> "dtc_log_demo"
    }

    /**
     * Folds one read of the car into a stored log and hands back the log to store again.
     *
     * The whole round trip lives here so that the only thing the preference layer decides
     * is *which* string to pass in.
     */
    fun recordInto(
        raw: String?,
        codes: Collection<String>,
        previouslyPresent: Set<String>,
        nowMillis: Long,
    ): String = encode(merge(decode(raw), codes, previouslyPresent, nowMillis))

    fun encode(log: List<DtcObservation>): String = log.joinToString(SEPARATOR) {
        listOf(it.code, it.firstSeenAtMillis, it.lastSeenAtMillis, it.occurrences).joinToString(FIELD)
    }

    fun decode(raw: String?): List<DtcObservation> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEPARATOR).mapNotNull { entry ->
            val parts = entry.split(FIELD)
            if (parts.size != FIELD_COUNT) return@mapNotNull null
            DtcObservation(
                code = parts[0].takeIf(String::isNotBlank) ?: return@mapNotNull null,
                firstSeenAtMillis = parts[1].toLongOrNull() ?: return@mapNotNull null,
                lastSeenAtMillis = parts[2].toLongOrNull() ?: return@mapNotNull null,
                occurrences = parts[3].toIntOrNull() ?: return@mapNotNull null,
            )
        }
    }

    /**
     * Folds one read of the car into the log.
     *
     * [previouslyPresent] is what the last read returned, so a code that has simply stayed
     * stored between two reads does not count as having happened again — only a code that
     * was gone and came back does.
     */
    fun merge(
        log: List<DtcObservation>,
        codes: Collection<String>,
        previouslyPresent: Set<String>,
        nowMillis: Long,
    ): List<DtcObservation> {
        val byCode = log.associateBy(DtcObservation::code).toMutableMap()
        codes.distinct().forEach { code ->
            val existing = byCode[code]
            byCode[code] = when {
                existing == null -> DtcObservation(code, nowMillis, nowMillis, occurrences = 1)
                code in previouslyPresent -> existing.copy(lastSeenAtMillis = nowMillis)
                else -> existing.copy(
                    lastSeenAtMillis = nowMillis,
                    occurrences = existing.occurrences + 1,
                )
            }
        }
        return byCode.values.sortedByDescending(DtcObservation::lastSeenAtMillis).take(MAX_ENTRIES)
    }

    private const val SEPARATOR = "|"
    private const val FIELD = ":"
    private const val FIELD_COUNT = 4

    /** Enough for any plausible history; the log is a convenience, not an archive. */
    private const val MAX_ENTRIES = 60

    private const val NOTABLE_FRACTION = 0.2
    private const val TRIM_DELTA = 5.0
    private const val EPSILON = 1e-6
}
