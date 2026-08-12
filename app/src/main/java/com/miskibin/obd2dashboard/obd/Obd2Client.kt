package com.miskibin.obd2dashboard.obd

/** Outcome of a single Mode 01 read. */
sealed interface PidRead {
    /**
     * [data] is the response's raw data bytes, kept alongside the decoded [value] because
     * a PID can carry more than one number and only the caller knows which of them it
     * wants; [value] is the primary channel, which for most PIDs is all there is.
     */
    data class Value(val pid: Pid, val data: IntArray, val lineCount: Int) : PidRead {
        val value: Double get() = pid.decode(data)

        // IntArray is compared by identity, which would make two reads of the same bytes
        // unequal; the generated equals/hashCode are overridden so the data class behaves.
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Value && pid == other.pid && data.contentEquals(other.data) &&
                lineCount == other.lineCount)

        override fun hashCode(): Int =
            (pid.hashCode() * 31 + data.contentHashCode()) * 31 + lineCount
    }

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
        return PidRead.Value(pid, data, lines.size)
    }

    /**
     * Up to six PIDs in one Mode 01 request; CAN only, and only after [probeBatching].
     *
     * Returns the raw data bytes per PID rather than decoded values, so a caller that
     * wants every channel of a multi-value PID can have them.
     */
    suspend fun readBatch(pids: List<Pid>): Map<Int, IntArray> {
        if (pids.isEmpty()) return emptyMap()
        val command = "%02X".format(MODE_CURRENT_DATA) + pids.joinToString("") { "%02X".format(it.id) }
        val response = session.request(command, PID_TIMEOUT_MILLIS)
        if (response is ElmResponse.Failure) return emptyMap()
        val frames = ObdResponseParser.frames(response.lines, protocol)
        val raw = ObdResponseParser.values(frames, MODE_CURRENT_DATA, pids.map(Pid::id))
        return raw.mapNotNull { (id, bytes) ->
            val pid = Pids[id] ?: return@mapNotNull null
            if (bytes.size < pid.bytes) null else id to bytes
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

    /**
     * Asks the car which PIDs it answers for, one support block at a time.
     *
     * The *first* `0100` gets the protocol-search ceiling rather than the ordinary PID one.
     * On a slow protocol — five-baud ISO 9141 init, or a CAN bus that has to be searched
     * for — the initializer's own probe can succeed while this one times out 1.5 s in, and
     * the result is a car that reports "Connected" with zero supported PIDs and a dashboard
     * that stays blank forever.
     */
    suspend fun scanSupportedPids(): Set<Int> {
        val supported = sortedSetOf<Int>()
        var base: Int? = 0x00
        var first = true
        while (base != null) {
            val timeout = if (first) {
                ElmSession.PROTOCOL_SEARCH_TIMEOUT_MILLIS
            } else {
                PID_TIMEOUT_MILLIS
            }
            first = false
            val response = session.request("%02X%02X".format(MODE_CURRENT_DATA, base), timeout)
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
     * Mode 02 frame [frame]: the values the ECU froze when it set a code.
     *
     * Every parameter is asked for on its own (`02 0C 00`), because the freeze frame is
     * read once per report rather than in a polling loop and a car that answers `NO DATA`
     * for one PID must still give up the rest. A car with nothing stored answers `NO DATA`
     * to all of them, which comes back as an empty [FreezeFrame].
     */
    suspend fun readFreezeFrame(frame: Int = FreezeFrames.FIRST_FRAME): FreezeFrame {
        val trigger = readFrameBytes(FreezeFrames.DTC_PID, frame, DTC_BYTES)
            ?.let { DtcDecoder.decode(it[0], it[1]) }
        val values = FreezeFrames.pids.mapNotNull { pid ->
            readFrameBytes(pid.id, frame, pid.bytes)?.let { pid.id to pid.decode(it) }
        }
        return FreezeFrame(trigger, values.toMap())
    }

    /**
     * A Mode 02 reply repeats the PID *and* the frame number before the data
     * (`42 0C 00 1F 40`), so the payload cannot be located by PID alone.
     */
    private suspend fun readFrameBytes(pid: Int, frame: Int, count: Int): IntArray? {
        val command = "%02X%02X%02X".format(MODE_FREEZE_FRAME, pid, frame)
        val response = session.request(command, PID_TIMEOUT_MILLIS)
        if (response !is ElmResponse.Ok) return null
        val frames = ObdResponseParser.frames(response.lines, protocol)
        val payload = ObdResponseParser.afterMarker(
            frames,
            MODE_FREEZE_FRAME + ObdResponseParser.RESPONSE_OFFSET,
            pid,
            frame,
        ) ?: return null
        return if (payload.size < count) null else payload.take(count).toIntArray()
    }

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
        private const val DTC_BYTES = 2
        private const val MAX_COUNT_DIGIT = 0xF
    }
}
