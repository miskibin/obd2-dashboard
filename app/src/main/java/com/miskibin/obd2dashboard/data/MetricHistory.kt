package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One point of a metric's trace. */
data class Sample(val timeMillis: Long, val value: Float)

/**
 * Rolling per-metric history behind the sparklines and the chart screen.
 *
 * The scheduler publishes a new snapshot several times a second, so the buffers are
 * rate-limited per metric and trimmed to [windowMillis]; anything longer belongs in a
 * trip recording, not in memory. Composables observe [revision] and then read [series],
 * which keeps the hot path free of per-frame map allocations.
 */
class MetricHistory(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val minIntervalMillis: Long = MIN_INTERVAL_MILLIS,
) {

    private val buffers = HashMap<MetricId, ArrayDeque<Sample>>()
    private val lock = Any()

    private val _revision = MutableStateFlow(0L)

    /** Bumped whenever any buffer changed; read it to make a composable recompose. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun record(snapshot: VehicleSnapshot, now: Long = System.currentTimeMillis()) {
        var changed = false
        synchronized(lock) {
            snapshot.presentMetrics().forEach { id ->
                val value = snapshot.valueOf(id) ?: return@forEach
                if (!value.isFinite()) return@forEach
                val buffer = buffers.getOrPut(id) { ArrayDeque() }
                val last = buffer.lastOrNull()
                if (last != null && now - last.timeMillis < minIntervalMillis) return@forEach
                buffer.addLast(Sample(now, value.toFloat()))
                changed = true
            }
            if (changed) {
                val cutoff = now - windowMillis
                buffers.values.forEach { buffer ->
                    while (buffer.isNotEmpty() && buffer.first().timeMillis < cutoff) buffer.removeFirst()
                }
            }
        }
        if (changed) _revision.value++
    }

    /** Samples of [id] no older than [windowMillis], oldest first. */
    fun series(id: MetricId, windowMillis: Long, now: Long = System.currentTimeMillis()): List<Sample> {
        val cutoff = now - windowMillis
        synchronized(lock) {
            val buffer = buffers[id] ?: return emptyList()
            return buffer.filter { it.timeMillis >= cutoff }
        }
    }

    fun clear() {
        synchronized(lock) { buffers.clear() }
        _revision.value++
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 10 * 60_000L
        const val MIN_INTERVAL_MILLIS = 100L
        const val SPARKLINE_WINDOW_MILLIS = 30_000L
    }
}
