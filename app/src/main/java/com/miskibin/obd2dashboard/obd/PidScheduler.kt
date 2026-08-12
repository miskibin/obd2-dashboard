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

/** One manufacturer-specific reading, keyed by [ExtendedPid.id] rather than by PID. */
data class ExtendedReading(
    val id: String,
    val unit: String,
    val value: Double,
    val timestampMillis: Long,
)

/** Everything the dashboard renders, replaced wholesale on every update. */
data class VehicleSnapshot(
    val readings: Map<Int, Reading> = emptyMap(),
    val derived: Map<String, Double> = emptyMap(),
    val extended: Map<String, ExtendedReading> = emptyMap(),
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

    /** The extended parameters the probe found on this car; empty on most cars. */
    private var extended: List<ExtendedPid> = emptyList()

    /** Extended id to when it was last asked for, for the ones with a minimum interval. */
    private val extendedReadAt = mutableMapOf<String, Long>()

    /** Which header group gets this cycle's one `ATSH`/`ATCRA` switch. */
    private var headerCursor = 0

    /**
     * Byte A of `0113`, once the car has answered it: which oxygen sensor positions exist.
     *
     * Kept because the support blocks over-report — a two-probe car routinely lists all
     * eight `0114`-`011B` — and this byte is the car's own answer to which of them are
     * real, bought for one request instead of six timeouts a sweep.
     */
    private var oxygenSensorMask: Int? = null

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
        oxygenSensorMask = null
    }

    /**
     * The manufacturer-specific parameters this car answered the connect-time probe for.
     *
     * Separate from [configure] because it arrives later: the probe needs the VIN, and the
     * VIN is read after the gauges are already live rather than before.
     */
    fun configureExtended(pids: List<ExtendedPid>) {
        extended = pids
        extendedReadAt.clear()
        headerCursor = 0
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

    /**
     * The extended parameters to read this cycle: one module, at most [MAX_EXTENDED_PER_CYCLE]
     * of them.
     *
     * Every module change costs an `ATSH` and, off the engine ECU, an `ATCRA` as well —
     * four AT commands and their round trips to fetch two numbers. Doing that for each
     * parameter in turn would put more traffic on the bus than the parameters are worth, so
     * a cycle serves one module and the modules take turns.
     */
    fun planExtended(forCycle: Long): List<ExtendedPid> {
        val now = clock()
        val due = extended.filter { isDue(it, forCycle, now) }.sortedBy(ExtendedPid::header)
        if (due.isEmpty()) return emptyList()
        val headers = due.map(ExtendedPid::header).distinct()
        val header = headers[headerCursor++ % headers.size]
        return due.filter { it.header == header }.take(MAX_EXTENDED_PER_CYCLE)
    }

    private fun isDue(pid: ExtendedPid, forCycle: Long, now: Long): Boolean {
        val cadence = when (pid.tier) {
            PidTier.Fast -> 1L
            PidTier.Medium -> MEDIUM_EVERY
            PidTier.Slow -> SLOW_EVERY
        }
        if (forCycle % cadence != 0L) return false
        val last = extendedReadAt[pid.id] ?: return true
        return now - last >= pid.minIntervalMillis
    }

    private suspend fun pollCycle() {
        val due = plan(cycle)
        val remaining = if (client.batchingEnabled) pollBatched(due) else due
        // Checked again here rather than only at planning time: `0113` is itself in the
        // plan, so the answer that says which oxygen sensors exist can arrive halfway
        // through the very sweep that would otherwise go on to ask after the ones that
        // do not.
        remaining.forEach { if (isFitted(it.id)) pollSingle(it) }
        pollExtended()
        if (cycle % SLOW_EVERY == 0L) client.readVoltage()?.let(::publishVoltage)
    }

    /**
     * Reads this cycle's module, and puts the adapter back the way Mode 01 needs it.
     *
     * The restore is [Obd2Client.withModule]'s job and it happens whatever the reads did,
     * because an `ATSH` left pointing at a body module is a dashboard that goes blank.
     */
    private suspend fun pollExtended() {
        val due = planExtended(cycle)
        if (due.isEmpty()) return
        val module = due.first()
        client.withModule(module.header, module.receiveHeader, due.any { it.flowControl }) {
            due.forEach { pid ->
                extendedReadAt[pid.id] = clock()
                val read = client.readExtended(pid)
                if (read is ExtendedRead.Value) publishExtended(pid, read.value)
            }
        }
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
        // The one PID that changes what gets polled rather than what gets shown.
        if (pid.id == Pids.O2_SENSORS_PRESENT && data.isNotEmpty()) oxygenSensorMask = data[0]
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

    private fun publishExtended(pid: ExtendedPid, value: Double) {
        val now = clock()
        _snapshot.update { current ->
            current.copy(
                extended = current.extended +
                    (pid.id to ExtendedReading(pid.id, pid.unit, value, now)),
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
        if (!isFitted(pid)) return false
        val restedAt = rested[pid] ?: return true
        if (cycle - restedAt < REST_CYCLES) return false
        rested.remove(pid)
        misses.remove(pid)
        return true
    }

    /**
     * Whether the car has said this oxygen sensor exists, for the PIDs that report one.
     *
     * Always true until `0113` has been answered, and always true for everything that is
     * not an oxygen sensor: absence of the map is not evidence of absence of the sensor.
     */
    private fun isFitted(pid: Int): Boolean {
        val mask = oxygenSensorMask ?: return true
        return Pids.o2SensorFitted(mask, pid) != false
    }

    companion object {
        const val MEDIUM_EVERY = 5L
        const val SLOW_EVERY = 20L
        const val SLOW_PER_CYCLE = 4
        const val MAX_MISSES = 3
        const val MAX_BATCH_SIZE = 6

        /** How many extended parameters one module gets per cycle; see [planExtended]. */
        const val MAX_EXTENDED_PER_CYCLE = 2
        const val DEFAULT_CYCLE_DELAY_MILLIS = 0L
        const val PAUSED_POLL_MILLIS = 250L

        /** How long a PID that kept answering `NO DATA` is left alone before one retry. */
        const val REST_CYCLES = 1_200L
    }
}
