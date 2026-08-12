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

/**
 * One published number. [key] is a [sensorKey] — the PID for single-value parameters, the
 * PID plus a channel index for the ones that carry several.
 */
data class Reading(
    val key: Int,
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
 * walked round-robin a few PIDs at a time every twentieth, which keeps the gauges that move
 * fast smooth on a link that only sustains ~10 requests per second. On top of the tiers sits
 * whatever the driver is actually looking at: a parameter on a tile or on the chart is asked
 * for every cycle whichever tier it belongs to, because a value nobody can see does not need
 * to be fresh and a value somebody is watching does. An oxygen sensor swinging once a second
 * is unreadable at the slow tier's pace and fine at the fast one's.
 *
 * PIDs that answer `NO DATA` repeatedly are rested rather than dropped, because every miss
 * costs a full `AT ST` timeout but a miss is not proof of absence: a car sitting at
 * ignition-on answers `NO DATA` to oil temperature and fuel rate and then answers both
 * perfectly once the engine is running.
 */
class PidScheduler(
    private val client: Obd2Client,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cycleDelayMillis: Long = DEFAULT_CYCLE_DELAY_MILLIS,
    private val pollingEnabled: () -> Boolean = { true },
    /** Read per publish rather than held, so editing the profile takes effect mid-drive. */
    private val fuel: () -> FuelType = { FuelType.Default },
) {
    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val gate = Mutex()
    private val misses = mutableMapOf<Int, Int>()

    /** PID id to the cycle it was rested on; see [isActive]. */
    private val rested = mutableMapOf<Int, Long>()
    private var supported: Set<Int> = emptySet()

    /** Written from whichever thread the UI collects on, read by the polling loop. */
    @Volatile
    private var priority: Set<Int> = emptySet()
    private var slowCursor = 0
    private var cycle = 0L

    /** Restricts polling to the PIDs the vehicle reported through `0100`/`0120`/… */
    fun configure(supportedPids: Set<Int>) {
        supported = supportedPids
        misses.clear()
        rested.clear()
    }

    /**
     * The PIDs behind whatever is currently on screen, which are polled every cycle.
     *
     * Takes reading keys rather than PID ids so a caller can hand over the metrics it is
     * displaying without having to know which of them share a PID.
     */
    fun prioritize(keys: Set<Int>) {
        priority = keys.map(::keyPid).toSet()
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
        addAll(Pids.entries.filter { it.tier != PidTier.Fast && it.id in priority })
        if (forCycle % MEDIUM_EVERY == 0L) addAll(Pids.tier(PidTier.Medium))
        if (forCycle % SLOW_EVERY == 0L) addAll(nextSlowSlice())
    }.filter { isActive(it.id) }.distinctBy(Pid::id)

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
                val data = answered[pid.id]
                if (data == null) {
                    missing += pid
                } else {
                    misses.remove(pid.id)
                    publish(pid, data)
                }
            }
        }
        return missing
    }

    private suspend fun pollSingle(pid: Pid) {
        when (val read = client.readPid(pid)) {
            is PidRead.Value -> {
                misses.remove(pid.id)
                rested.remove(pid.id)
                publish(pid, read.data)
            }

            PidRead.Unsupported -> noteMiss(pid.id)

            is PidRead.Failed -> {
                if (read.error.requiresReinit) throw ElmFatalException(read.error)
                // An answer too short to decode is as useless as no answer, and asking
                // again every cycle spends a timeout each time to learn the same thing.
                // Cars do truncate the longer parameters — the catalogue now declares
                // payloads of five to nine bytes — so this has to rest like a miss.
                noteMiss(pid.id)
            }
        }
    }

    private fun noteMiss(pid: Int) {
        val count = (misses[pid] ?: 0) + 1
        misses[pid] = count
        if (count >= MAX_MISSES) rested[pid] = cycle
    }

    /**
     * Publishes every channel the response carries.
     *
     * A channel that decodes to a non-finite value is the standard's way of saying the
     * sensor is not fitted, so it is dropped rather than published as a reading of nothing.
     */
    private fun publish(pid: Pid, data: IntArray) {
        val now = clock()
        val fresh = pid.channels.mapNotNull { channel ->
            val value = channel.decode(data)
            if (!value.isFinite()) return@mapNotNull null
            val key = sensorKey(pid.id, channel.index)
            key to Reading(key, channel.name, channel.unit, value, now)
        }
        if (fresh.isEmpty()) return
        _snapshot.update { current ->
            val readings = current.readings + fresh
            current.copy(
                readings = readings,
                derived = DerivedMetrics.compute(readings.mapValues { it.value.value }, fuel()),
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

    /**
     * A PID is polled while the car lists it as supported and it is not resting.
     *
     * Resting is a pause, not a verdict. A PID that answered `NO DATA` [MAX_MISSES] times
     * is left alone for [REST_CYCLES] and then tried once more, which costs one timeout
     * every couple of minutes and is the difference between a car connected at
     * ignition-on losing oil temperature for the rest of the drive and picking it up as
     * soon as the engine runs.
     */
    private fun isActive(pid: Int): Boolean {
        if (supported.isNotEmpty() && pid !in supported) return false
        val restedAt = rested[pid] ?: return true
        if (cycle - restedAt < REST_CYCLES) return false
        rested.remove(pid)
        misses.remove(pid)
        return true
    }

    companion object {
        const val MEDIUM_EVERY = 5L
        const val SLOW_EVERY = 20L
        const val SLOW_PER_CYCLE = 4
        const val MAX_MISSES = 3
        const val MAX_BATCH_SIZE = 6
        const val DEFAULT_CYCLE_DELAY_MILLIS = 0L
        const val PAUSED_POLL_MILLIS = 250L

        /** How long a PID that kept answering `NO DATA` is left alone before one retry. */
        const val REST_CYCLES = 1_200L
    }
}
