package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.MonitorCounts
import com.miskibin.obd2dashboard.obd.MonitorTest
import com.miskibin.obd2dashboard.obd.MonitorTests
import com.miskibin.obd2dashboard.obd.PerformanceTracking
import com.miskibin.obd2dashboard.obd.PerformanceTrackingDecoder

/**
 * One reading of everything the ECU's own self-tests hold, filed under the car it came
 * from.
 *
 * Mode 06 is read once per connection rather than polled, so a snapshot is a session
 * boundary: what the monitors said when the app arrived. Kept because the numbers only
 * mean anything as a series — a catalyst storage figure is a number, and the same figure
 * measured five times over a year is a diagnosis.
 */
data class MonitorSnapshot(
    val vin: String,
    val capturedAtMillis: Long,
    val tests: List<MonitorTest> = emptyList(),
    val performance: PerformanceTracking? = null,
) {
    val isEmpty: Boolean get() = tests.isEmpty() && performance == null

    val monitorTests: MonitorTests get() = MonitorTests(tests, capturedAtMillis)

    /**
     * Whether this snapshot carries everything [other] did.
     *
     * Not plain equality of the counters: the second half of a capture adds the
     * performance counters to a snapshot that had none, and that is the same reading
     * completed rather than a different one.
     */
    fun saysAsMuchAs(other: MonitorSnapshot): Boolean =
        performance == other.performance || other.performance == null
}

/**
 * The stored history of those snapshots, in the same flat encoding the rest of the
 * preferences use.
 *
 * Records are joined by [SEPARATOR], the fields of a record by [FIELD], the tests inside
 * one record by [ITEM] and the numbers inside one test by [PART]. Four levels is one more
 * than anything else here needs, which is the price of storing a table rather than a list;
 * it still beats taking a serialisation dependency for six integers repeated.
 */
object MonitorLog {

    /**
     * One store per [SessionKind], for the reason every other store is split: the
     * simulation reports misfires on cylinder 2, and they are fiction.
     */
    fun storageKey(kind: SessionKind): String = when (kind) {
        SessionKind.Real -> "monitor_log"
        SessionKind.Demo -> "monitor_log_demo"
    }

    /** How many readings of one car are kept. Enough to see a trend, not an archive. */
    const val MAX_PER_VEHICLE = 8

    /** A ceiling across all cars, so a workshop phone does not grow without bound. */
    const val MAX_ENTRIES = 40

    /**
     * Folds one reading into the stored log and hands back what to store.
     *
     * A snapshot with nothing in it is dropped rather than recorded: a car that does not
     * implement Mode 06 would otherwise fill the history with evidence of its silence.
     */
    fun recordInto(raw: String?, snapshot: MonitorSnapshot): String =
        encode(merge(decode(raw), snapshot))

    /**
     * Folds one reading in, replacing rather than repeating what it restates.
     *
     * Two things arrive as separate snapshots and are one reading. A capture comes in
     * halves — the test results and, a moment later, the performance counters — so the
     * same instant is offered twice, the second time with more in it. And a dongle that
     * sleeps and reconnects reads the monitors again minutes later, at a new instant with
     * identical numbers, because these numbers move over months.
     *
     * Kept apart, either would spend the whole history on one afternoon. So a snapshot
     * that says exactly what the newest stored one says takes its place, carrying the
     * later timestamp: the series then holds distinct readings, which is the only kind
     * worth plotting.
     */
    fun merge(log: List<MonitorSnapshot>, snapshot: MonitorSnapshot): List<MonitorSnapshot> {
        if (snapshot.isEmpty || snapshot.vin.isBlank()) return log
        val newest = log.filter { it.vin == snapshot.vin }.maxByOrNull(MonitorSnapshot::capturedAtMillis)
        val restates = { it: MonitorSnapshot ->
            it.capturedAtMillis == snapshot.capturedAtMillis ||
                (it === newest && it.tests == snapshot.tests && snapshot.saysAsMuchAs(it))
        }
        val forCar = (log.filter { it.vin == snapshot.vin && !restates(it) } + snapshot)
            .sortedByDescending(MonitorSnapshot::capturedAtMillis)
            .take(MAX_PER_VEHICLE)
        val others = log.filterNot { it.vin == snapshot.vin }
        return (forCar + others)
            .sortedByDescending(MonitorSnapshot::capturedAtMillis)
            .take(MAX_ENTRIES)
    }

    /** Every stored reading of one car, newest first. */
    fun historyFor(log: List<MonitorSnapshot>, vin: String?): List<MonitorSnapshot> {
        if (vin.isNullOrBlank()) return emptyList()
        return log.filter { it.vin == vin }.sortedByDescending(MonitorSnapshot::capturedAtMillis)
    }

    fun encode(log: List<MonitorSnapshot>): String = log.joinToString(SEPARATOR) { snapshot ->
        listOf(
            sanitise(snapshot.vin),
            snapshot.capturedAtMillis.toString(),
            snapshot.tests.joinToString(ITEM, transform = ::encodeTest),
            snapshot.performance?.let(::encodePerformance).orEmpty(),
        ).joinToString(FIELD)
    }

    fun decode(raw: String?): List<MonitorSnapshot> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEPARATOR).mapNotNull { entry ->
            val fields = entry.split(FIELD)
            if (fields.size < MIN_FIELDS) return@mapNotNull null
            val vin = fields[0].takeIf(String::isNotBlank) ?: return@mapNotNull null
            val captured = fields[1].toLongOrNull() ?: return@mapNotNull null
            MonitorSnapshot(
                vin = vin,
                capturedAtMillis = captured,
                tests = fields[2].split(ITEM).mapNotNull(::decodeTest),
                performance = fields.getOrNull(3)?.let(::decodePerformance),
            )
        }
    }

    private fun encodeTest(test: MonitorTest): String = listOf(
        test.mid,
        test.tid,
        test.uasid,
        test.rawValue,
        test.rawMin,
        test.rawMax,
    ).joinToString(PART)

    private fun decodeTest(raw: String): MonitorTest? {
        val parts = raw.split(PART).mapNotNull(String::toIntOrNull)
        if (parts.size != TEST_PARTS) return null
        return MonitorTest(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
    }

    /**
     * The performance counters as a flat run of numbers.
     *
     * Position carries the meaning here exactly as it does on the bus: the two engine-wide
     * counters first, then a completion and a conditions count per monitor in
     * [PerformanceTrackingDecoder.ORDER]. A monitor the car did not report is written as an
     * empty pair, so the positions of the ones after it do not shift.
     */
    private fun encodePerformance(performance: PerformanceTracking): String = buildList {
        add(performance.obdConditions?.toString().orEmpty())
        add(performance.ignitionCycles?.toString().orEmpty())
        PerformanceTrackingDecoder.ORDER.forEach { monitor ->
            val counts = performance.monitors.firstOrNull { it.monitor == monitor }
            add(counts?.completions?.toString().orEmpty())
            add(counts?.conditions?.toString().orEmpty())
        }
    }.joinToString(PART)

    private fun decodePerformance(raw: String): PerformanceTracking? {
        if (raw.isBlank()) return null
        val parts = raw.split(PART)
        val monitors = PerformanceTrackingDecoder.ORDER.mapIndexedNotNull { index, monitor ->
            val at = LEADING_COUNTERS + index * 2
            val completions = parts.getOrNull(at)?.toIntOrNull() ?: return@mapIndexedNotNull null
            val conditions = parts.getOrNull(at + 1)?.toIntOrNull() ?: return@mapIndexedNotNull null
            MonitorCounts(monitor, completions, conditions)
        }
        return PerformanceTracking(
            obdConditions = parts.getOrNull(0)?.toIntOrNull(),
            ignitionCycles = parts.getOrNull(1)?.toIntOrNull(),
            monitors = monitors,
        ).takeUnless(PerformanceTracking::isEmpty)
    }

    /** Nothing written into a field may be able to end its own record; see [Garage]. */
    private fun sanitise(value: String): String =
        value.filterNot { it.toString() in SEPARATORS }.trim()

    private const val SEPARATOR = "|"
    private const val FIELD = ";"
    private const val ITEM = ","
    private const val PART = ":"
    private val SEPARATORS = setOf(SEPARATOR, FIELD, ITEM, PART)

    private const val MIN_FIELDS = 3
    private const val TEST_PARTS = 6
    private const val LEADING_COUNTERS = 2
}
