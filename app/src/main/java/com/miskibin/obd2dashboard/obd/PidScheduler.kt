package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Reading(
    val pid: Int,
    val name: String,
    val unit: String,
    val value: Double,
    val timestampMillis: Long,
)

/** Everything the dashboard renders, replaced wholesale on every update. */
data class VehicleSnapshot(
    val readings: Map<Int, Reading> = emptyMap(),
    val derived: Map<String, Double> = emptyMap(),
    val batteryVoltage: Double? = null,
    val cycle: Long = 0,
    val updatedAtMillis: Long = 0,
) {
    operator fun get(pid: Int): Double? = readings[pid]?.value
}

/**
 * Priority polling loop.
 *
 * The fast tier goes out every cycle, the medium tier every fifth and the slow tier is
 * walked round-robin a couple of PIDs at a time every twentieth, which keeps the gauges
 * that move fast smooth on a link that only sustains ~10 requests per second. PIDs that
 * answer `NO DATA` repeatedly are dropped, because every miss costs a full `AT ST`
 * timeout.
 */
class PidScheduler(
    private val client: Obd2Client,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cycleDelayMillis: Long = DEFAULT_CYCLE_DELAY_MILLIS,
    private val pollingEnabled: () -> Boolean = { true },
) {
    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val gate = Mutex()
    private val misses = mutableMapOf<Int, Int>()
    private val disabled = mutableSetOf<Int>()
    private var supported: Set<Int> = emptySet()
    private var slowCursor = 0
    private var cycle = 0L

    /** Restricts polling to the PIDs the vehicle reported through `0100`/`0120`/… */
    fun configure(supportedPids: Set<Int>) {
        supported = supportedPids
        misses.clear()
        disabled.clear()
    }

    /** Lets a user-initiated request (DTC read, VIN, clear) cut in between cycles. */
    suspend fun <T> exclusive(block: suspend () -> T): T = gate.withLock { block() }

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            if (!pollingEnabled()) {
                delay(PAUSED_POLL_MILLIS)
                continue
            }
            gate.withLock { pollCycle() }
            cycle++
            if (cycleDelayMillis > 0) delay(cycleDelayMillis)
        }
    }

    /** Lets the keep-alive `ATRV` reach the same snapshot the gauges read. */
    fun recordVoltage(volts: Double) = publishVoltage(volts)

    fun plan(forCycle: Long): List<Pid> = buildList {
        addAll(Pids.tier(PidTier.Fast))
        if (forCycle % MEDIUM_EVERY == 0L) addAll(Pids.tier(PidTier.Medium))
        if (forCycle % SLOW_EVERY == 0L) addAll(nextSlowSlice())
    }.filter { isActive(it.id) }

    private suspend fun pollCycle() {
        val due = plan(cycle)
        val remaining = if (client.batchingEnabled) pollBatched(due) else due
        remaining.forEach { pollSingle(it) }
        if (cycle % SLOW_EVERY == 0L) client.readVoltage()?.let(::publishVoltage)
    }

    /** Returns the PIDs the batch did not answer, to be retried one at a time. */
    private suspend fun pollBatched(due: List<Pid>): List<Pid> {
        val missing = mutableListOf<Pid>()
        for (chunk in due.chunked(MAX_BATCH_SIZE)) {
            val answered = client.readBatch(chunk)
            chunk.forEach { pid ->
                val value = answered[pid.id]
                if (value == null) missing += pid else publish(pid, value)
            }
        }
        return missing
    }

    private suspend fun pollSingle(pid: Pid) {
        when (val read = client.readPid(pid)) {
            is PidRead.Value -> {
                misses.remove(pid.id)
                publish(pid, read.value)
            }

            PidRead.Unsupported -> {
                val count = (misses[pid.id] ?: 0) + 1
                misses[pid.id] = count
                if (count >= MAX_MISSES) disabled += pid.id
            }

            is PidRead.Failed -> if (read.error.requiresReinit) throw ElmFatalException(read.error)
        }
    }

    private fun publish(pid: Pid, value: Double) {
        val now = clock()
        _snapshot.update { current ->
            val readings = current.readings + (pid.id to Reading(pid.id, pid.name, pid.unit, value, now))
            current.copy(
                readings = readings,
                derived = DerivedMetrics.compute(readings.mapValues { it.value.value }),
                cycle = cycle,
                updatedAtMillis = now,
            )
        }
    }

    private fun publishVoltage(volts: Double) {
        _snapshot.update { it.copy(batteryVoltage = volts, updatedAtMillis = clock()) }
    }

    private fun nextSlowSlice(): List<Pid> {
        val slow = Pids.tier(PidTier.Slow).filter { isActive(it.id) }
        if (slow.isEmpty()) return emptyList()
        val slice = (0 until minOf(SLOW_PER_CYCLE, slow.size)).map { slow[(slowCursor + it) % slow.size] }
        slowCursor = (slowCursor + slice.size) % slow.size
        return slice
    }

    private fun isActive(pid: Int): Boolean =
        pid !in disabled && (supported.isEmpty() || pid in supported)

    companion object {
        const val MEDIUM_EVERY = 5L
        const val SLOW_EVERY = 20L
        const val SLOW_PER_CYCLE = 2
        const val MAX_MISSES = 3
        const val MAX_BATCH_SIZE = 6
        const val DEFAULT_CYCLE_DELAY_MILLIS = 0L
        const val PAUSED_POLL_MILLIS = 250L
    }
}
