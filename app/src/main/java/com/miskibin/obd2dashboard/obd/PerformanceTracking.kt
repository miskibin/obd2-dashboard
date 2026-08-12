package com.miskibin.obd2dashboard.obd

/**
 * The monitors in-use performance tracking counts, in the order `0908` reports them.
 *
 * Each one arrives as a pair: how many times the monitor ran to completion, and how many
 * times the car was driven in the conditions it needs. The ratio between them is the
 * closest thing OBD-II has to an answer for "is this self-test actually working, or has it
 * simply never had the chance to fail?" — which is the question behind a car that shows no
 * codes and still fails an emissions test.
 */
enum class TrackedMonitor {
    CatalystBank1,
    CatalystBank2,
    OxygenSensorBank1,
    OxygenSensorBank2,
    Egr,
    SecondaryAir,
    Evaporative,
    SecondaryOxygenSensorBank1,
    SecondaryOxygenSensorBank2,
}

/**
 * One monitor's pair of counters.
 *
 * [completions] can legitimately exceed nothing and [conditions] can legitimately be zero:
 * a car whose codes were cleared last week has run nothing yet, and saying "0 %" of that
 * would be a verdict on a monitor that has simply not been given a chance.
 */
data class MonitorCounts(
    val monitor: TrackedMonitor,
    val completions: Int,
    val conditions: Int,
) {
    /** The completion ratio, or null while the conditions for it have never come up. */
    val ratio: Double? get() = if (conditions > 0) completions.toDouble() / conditions else null

    /** True when the monitor has never run, which is different from having run and failed. */
    val neverRun: Boolean get() = completions == 0
}

/**
 * What `0908` said: two engine-wide counters and a pair per monitor.
 *
 * [obdConditions] is how often the general OBD monitoring conditions were met and
 * [ignitionCycles] how often the engine was started, which together are the denominator a
 * mechanic reads the rest against — five completions out of six ignition cycles is a
 * healthy monitor, five out of six hundred is not.
 */
data class PerformanceTracking(
    val obdConditions: Int? = null,
    val ignitionCycles: Int? = null,
    val monitors: List<MonitorCounts> = emptyList(),
) {
    val isEmpty: Boolean get() = obdConditions == null && monitors.isEmpty()
}

/**
 * Decodes `0908`, in-use performance tracking for spark-ignition vehicles.
 *
 * The response is a count of items followed by that many sixteen-bit counters in a fixed
 * order that the response itself does not state — there are no identifiers, only position.
 * A car that reports sixteen items has simply stopped early, so the list is read as far as
 * it goes and everything past that is absent rather than guessed at.
 *
 * Compression-ignition engines report a *different* order under `090B`, so the two must
 * never be decoded with the same table; only `0908` is read here.
 */
object PerformanceTrackingDecoder {

    const val PID = 0x08

    /** The request, `0908`. */
    val request: String get() = "%02X%02X".format(MODE_VEHICLE_INFO, PID)

    /**
     * The two engine-wide counters lead, and the monitors follow in pairs.
     *
     * Both halves of this are positional: OBDCOND and IGNCNTR are items 1 and 2, and every
     * item after them is alternately a completion and a conditions counter for the monitor
     * at that position in [ORDER].
     */
    val ORDER: List<TrackedMonitor> = listOf(
        TrackedMonitor.CatalystBank1,
        TrackedMonitor.CatalystBank2,
        TrackedMonitor.OxygenSensorBank1,
        TrackedMonitor.OxygenSensorBank2,
        TrackedMonitor.Egr,
        TrackedMonitor.SecondaryAir,
        TrackedMonitor.Evaporative,
        TrackedMonitor.SecondaryOxygenSensorBank1,
        TrackedMonitor.SecondaryOxygenSensorBank2,
    )

    private const val LEADING_COUNTERS = 2

    fun parse(frames: List<ObdFrame>): PerformanceTracking? {
        val payload = ObdResponseParser.afterMarker(
            frames,
            MODE_VEHICLE_INFO + ObdResponseParser.RESPONSE_OFFSET,
            PID,
        ) ?: return null
        val values = counters(payload)
        if (values.isEmpty()) return null

        val monitors = ORDER.mapIndexedNotNull { index, monitor ->
            val at = LEADING_COUNTERS + index * 2
            if (at + 1 >= values.size) return@mapIndexedNotNull null
            MonitorCounts(monitor, completions = values[at], conditions = values[at + 1])
        }
        return PerformanceTracking(
            obdConditions = values.getOrNull(0),
            ignitionCycles = values.getOrNull(1),
            monitors = monitors,
        )
    }

    /**
     * The sixteen-bit counters in [payload], with the leading item count consumed.
     *
     * The standard puts a count of items first, so the payload is an odd number of bytes.
     * Not every ECU sends it — an even payload is taken as counters all the way down,
     * which is the reading that cannot silently shift every value by one byte.
     */
    private fun counters(payload: List<Int>): List<Int> {
        val declared = payload.size % 2 == 1
        val body = if (declared) payload.drop(1) else payload
        val limit = if (declared) payload.first() else body.size / 2
        return (0 until minOf(limit, body.size / 2)).map { body[it * 2] * 256 + body[it * 2 + 1] }
    }
}
