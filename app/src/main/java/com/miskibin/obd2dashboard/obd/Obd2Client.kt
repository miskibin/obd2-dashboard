package com.miskibin.obd2dashboard.obd

/** Outcome of a single Mode 01 read. */
sealed interface PidRead {
    data class Value(val pid: Pid, val value: Double, val lineCount: Int) : PidRead
    data object Unsupported : PidRead
    data class Failed(val error: ElmError) : PidRead
}

/**
 * Speaks OBD-II on top of [ElmSession]: builds requests, hands responses to
 * [ObdResponseParser] and returns decoded values.
 *
 * Response line counts are learned on the first read of each PID and replayed as the
 * trailing count digit (`010C1`) afterwards, which removes the `AT ST` idle wait that
 * otherwise dominates the round trip.
 */
class Obd2Client(
    private val session: ElmSession,
    var protocol: ObdProtocol = ObdProtocol.Automatic,
) {
    private val lineCounts = mutableMapOf<Int, Int>()

    var batchingEnabled: Boolean = false
        private set

    fun lineCountOf(pid: Int): Int? = lineCounts[pid]

    suspend fun readPid(pid: Pid): PidRead {
        val hint = lineCounts[pid.id]
        val response = session.request(pid.command + countDigit(hint), PID_TIMEOUT_MILLIS)
        if (response is ElmResponse.Failure) {
            return if (response.error == ElmError.NoData) PidRead.Unsupported
            else PidRead.Failed(response.error)
        }
        val lines = response.lines
        val frames = ObdResponseParser.frames(lines, protocol)
        val data = ObdResponseParser.values(frames, MODE_CURRENT_DATA, listOf(pid.id))[pid.id]
            ?: return PidRead.Failed(ElmError.DataError)
        if (data.size < pid.bytes) return PidRead.Failed(ElmError.DataError)
        if (hint == null && lines.isNotEmpty()) lineCounts[pid.id] = lines.size
        return PidRead.Value(pid, pid.decode(data), lines.size)
    }

    /** Up to six PIDs in one Mode 01 request; CAN only, and only after [probeBatching]. */
    suspend fun readBatch(pids: List<Pid>): Map<Int, Double> {
        if (pids.isEmpty()) return emptyMap()
        val command = "%02X".format(MODE_CURRENT_DATA) + pids.joinToString("") { "%02X".format(it.id) }
        val response = session.request(command, PID_TIMEOUT_MILLIS)
        if (response is ElmResponse.Failure) return emptyMap()
        val frames = ObdResponseParser.frames(response.lines, protocol)
        val raw = ObdResponseParser.values(frames, MODE_CURRENT_DATA, pids.map(Pid::id))
        return raw.mapNotNull { (id, bytes) ->
            val pid = Pids[id] ?: return@mapNotNull null
            if (bytes.size < pid.bytes) null else id to pid.decode(bytes)
        }.toMap()
    }

    /**
     * Clones that truncate requests to the first two bytes silently degrade a batch to a
     * single-PID query, so batching is only enabled once a probe comes back complete.
     */
    suspend fun probeBatching(pids: List<Pid>): Boolean {
        batchingEnabled = false
        if (!protocol.isCan || pids.size < 2) return false
        val answered = readBatch(pids)
        batchingEnabled = pids.all { it.id in answered }
        return batchingEnabled
    }

    suspend fun scanSupportedPids(): Set<Int> {
        val supported = sortedSetOf<Int>()
        var base: Int? = 0x00
        while (base != null) {
            val response = session.request("%02X%02X".format(MODE_CURRENT_DATA, base), PID_TIMEOUT_MILLIS)
            if (response !is ElmResponse.Ok) break
            val block = ObdResponseParser.supportedPids(
                ObdResponseParser.frames(response.lines, protocol),
                base,
            )
            if (block.isEmpty()) break
            supported += block
            base = ObdResponseParser.nextSupportBlock(base, block)
        }
        return supported
    }

    suspend fun readVoltage(): Double? {
        val response = session.request(VOLTAGE_COMMAND, ElmSession.AT_TIMEOUT_MILLIS)
        return ElmVoltage.parse(response.lines)
    }

    suspend fun readMonitorStatus(): MonitorStatus? {
        val response = session.request("0101", PID_TIMEOUT_MILLIS)
        if (response !is ElmResponse.Ok) return null
        val frames = ObdResponseParser.frames(response.lines, protocol)
        val data = ObdResponseParser.values(frames, MODE_CURRENT_DATA, listOf(MONITOR_STATUS_PID)) {
            if (it == MONITOR_STATUS_PID) MONITOR_STATUS_BYTES else Pids.byteCountOf(it)
        }[MONITOR_STATUS_PID] ?: return null
        return DtcDecoder.parseMonitorStatus(data)
    }

    suspend fun readDtcs(kind: DtcKind): List<Dtc> {
        val response = session.request(kind.request, DTC_TIMEOUT_MILLIS)
        if (response is ElmResponse.Failure) return emptyList()
        val frames = ObdResponseParser.frames(response.lines, protocol)
        return DtcDecoder.parse(frames, kind, protocol.isCan)
    }

    suspend fun readDiagnostics(): Diagnostics = Diagnostics(
        stored = readDtcs(DtcKind.Stored),
        pending = readDtcs(DtcKind.Pending),
        permanent = readDtcs(DtcKind.Permanent),
        monitorStatus = readMonitorStatus(),
    )

    /**
     * Mode 04. The ECU can take well over a second to answer, so the response ceiling is
     * raised for the duration and restored afterwards.
     */
    suspend fun clearDtcs(): Boolean {
        session.request("ATSTFF", ElmSession.AT_TIMEOUT_MILLIS)
        val response = session.request("%02X".format(MODE_CLEAR_DTC), CLEAR_TIMEOUT_MILLIS)
        session.request("ATST32", ElmSession.AT_TIMEOUT_MILLIS)
        return response is ElmResponse.Ok && DtcDecoder.isClearAccepted(response.lines)
    }

    suspend fun readVin(): String? {
        val response = session.request("0902", VIN_TIMEOUT_MILLIS)
        if (response !is ElmResponse.Ok) return null
        return VinDecoder.decode(ObdResponseParser.frames(response.lines, protocol))
    }

    private fun countDigit(hint: Int?): String =
        if (hint != null && hint in 1..MAX_COUNT_DIGIT) hint.toString(16).uppercase() else ""

    companion object {
        const val VOLTAGE_COMMAND = "ATRV"
        const val PID_TIMEOUT_MILLIS = 1_500L
        const val DTC_TIMEOUT_MILLIS = 3_000L
        const val CLEAR_TIMEOUT_MILLIS = 5_000L
        const val VIN_TIMEOUT_MILLIS = 3_000L

        private const val MONITOR_STATUS_PID = 0x01
        private const val MONITOR_STATUS_BYTES = 4
        private const val MAX_COUNT_DIGIT = 0xF
    }
}
