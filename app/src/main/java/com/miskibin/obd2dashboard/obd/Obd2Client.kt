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

/** Outcome of a single manufacturer-specific read; see [ExtendedPid]. */
sealed interface ExtendedRead {

    data class Value(val pid: ExtendedPid, val value: Double) : ExtendedRead

    /**
     * The car answered, and the answer was a refusal. [code] is the ISO 14229 response
     * code, which is what says whether asking again could ever help.
     */
    data class Refused(val code: Int) : ExtendedRead

    /** Nothing came back, or nothing that could be read as this parameter. */
    data object Silent : ExtendedRead
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

    // ---- mode 06 and mode 09 ----------------------------------------------------

    /**
     * Every on-board test result the car will part with.
     *
     * `0600` gives the mask of monitors this ECU implements and chains into `0620`,
     * `0640`, … exactly as `0100` does; each monitor is then asked for on its own, because
     * a monitor answers with a variable number of nine-byte records and there is no way to
     * batch that. Twenty-odd requests, once per session — which is why it is read at a
     * session boundary rather than in the polling loop.
     */
    suspend fun readMonitorTests(nowMillis: Long = System.currentTimeMillis()): MonitorTests =
        MonitorTests(
            tests = scanSupportedMonitors().flatMap { readMonitor(it) },
            capturedAtMillis = nowMillis,
        )

    /**
     * One monitor's test results, as a single request.
     *
     * Split out from [readMonitorTests] so a caller that shares the bus with a polling
     * loop can take the lock once per request rather than once per sweep: twenty monitors
     * behind one lock is twenty timeouts' worth of frozen gauges.
     */
    suspend fun readMonitor(mid: Int): List<MonitorTest> {
        val response = session.request(Mode06.request(mid), DTC_TIMEOUT_MILLIS)
        if (response !is ElmResponse.Ok) return emptyList()
        return Mode06.parse(ObdResponseParser.frames(response.lines, protocol))
            // An ECU that answers 06A2 with records for other monitors is answering
            // something else; only what was asked for is kept.
            .filter { it.mid == mid }
    }

    /** The monitor ids the car lists, walking the `0600`/`0620`/… chain. */
    suspend fun scanSupportedMonitors(): Set<Int> {
        val supported = sortedSetOf<Int>()
        var base: Int? = 0x00
        while (base != null) {
            val response = session.request(Mode06.supportRequest(base), PID_TIMEOUT_MILLIS)
            if (response !is ElmResponse.Ok) break
            val block = ObdResponseParser.supportedIds(
                ObdResponseParser.frames(response.lines, protocol),
                Mode06.MODE,
                base,
            )
            if (block.isEmpty()) break
            supported += block
            base = ObdResponseParser.nextSupportBlock(base, block)
        }
        // The block markers chain the query along; they are not monitors themselves.
        return supported.filterTo(sortedSetOf()) {
            it % ObdResponseParser.SUPPORT_BLOCK_SIZE != 0
        }
    }

    /** `0908`: how often each monitor has actually run. Null when the car does not track it. */
    suspend fun readPerformanceTracking(): PerformanceTracking? {
        val response = session.request(PerformanceTrackingDecoder.request, DTC_TIMEOUT_MILLIS)
        if (response !is ElmResponse.Ok) return null
        return PerformanceTrackingDecoder
            .parse(ObdResponseParser.frames(response.lines, protocol))
            ?.takeUnless(PerformanceTracking::isEmpty)
    }

    // ---- manufacturer-specific reads --------------------------------------------

    /**
     * Points the adapter at one module for the duration of [block].
     *
     * Two settings, both of which have to be put back. `ATSH` replaces the functional
     * broadcast address every Mode 01 request relies on, and `ATCRA` narrows the receive
     * filter to one address — leave either in place and the next `010C` goes to the wrong
     * ECU or its answer is filtered away, which looks exactly like a car that stopped
     * responding. The restore therefore happens in a `finally`, and restoring `ATCRA`
     * means clearing it rather than setting it to anything.
     */
    /**
     * Whether this session can address a single module at all.
     *
     * `ATSH 7DF` is the eleven-bit CAN functional address and nothing else. On the
     * twenty-nine-bit variants the broadcast address is `18DB33F1`, and on the five
     * pre-CAN protocols it is three bytes of something else again, so restoring `7DF`
     * afterwards would leave the adapter addressing an ECU that does not exist — every
     * Mode 01 request after it going nowhere for the rest of the session. Until each of
     * those has a default worth restoring, the extended parameters stay off them.
     */
    val canAddressModules: Boolean
        get() = protocol.isCan && protocol.headerChars == CAN_11_BIT_HEADER_CHARS

    suspend fun <T> withModule(header: String, receiveHeader: String?, block: suspend () -> T): T {
        session.request(SET_HEADER + header, ElmSession.AT_TIMEOUT_MILLIS)
        if (receiveHeader != null) {
            session.request(SET_RECEIVE_FILTER + receiveHeader, ElmSession.AT_TIMEOUT_MILLIS)
        }
        try {
            return block()
        } finally {
            if (receiveHeader != null) {
                session.request(SET_RECEIVE_FILTER, ElmSession.AT_TIMEOUT_MILLIS)
            }
            session.request(SET_HEADER + FUNCTIONAL_HEADER, ElmSession.AT_TIMEOUT_MILLIS)
        }
    }

    /**
     * One extended read. The caller is responsible for having entered [ExtendedPid.header]
     * with [withModule] first.
     *
     * A positive response repeats the identifier before the data — `62 04 15 …` — so the
     * payload is located by the marker *and* the two identifier bytes rather than by
     * offset, which is what keeps an answer to the previous request from being read as
     * this one.
     */
    suspend fun readExtended(pid: ExtendedPid): ExtendedRead {
        val response = session.request(pid.request, EXTENDED_TIMEOUT_MILLIS)
        if (response is ElmResponse.Failure) return ExtendedRead.Silent
        val frames = ObdResponseParser.frames(response.lines, protocol)
        val payload = ObdResponseParser.afterMarker(frames, pid.responseMarker, *pid.didBytes)
        if (payload != null && payload.size >= pid.bytes) {
            val value = pid.decode(payload.take(pid.bytes).toIntArray())
            return if (value.isFinite()) ExtendedRead.Value(pid, value) else ExtendedRead.Silent
        }
        val code = NegativeResponse.codeFor(frames, pid.service)
        return if (code != null) ExtendedRead.Refused(code) else ExtendedRead.Silent
    }

    /** What one read established about whether this car will ever answer [pid]. */
    suspend fun probeExtended(pid: ExtendedPid): ExtendedProbe = when (val read = readExtended(pid)) {
        is ExtendedRead.Value -> ExtendedProbe.Supported
        is ExtendedRead.Refused -> when {
            NegativeResponse.isPermanent(read.code) -> ExtendedProbe.Absent
            NegativeResponse.isTransient(read.code) -> ExtendedProbe.Retry
            else -> ExtendedProbe.Unknown
        }

        ExtendedRead.Silent -> ExtendedProbe.Unknown
    }

    private fun countDigit(hint: Int?): String =
        if (hint != null && hint in 1..MAX_COUNT_DIGIT) hint.toString(16).uppercase() else ""

    companion object {
        const val VOLTAGE_COMMAND = "ATRV"

        /** The functional address every legislated request is broadcast to. */
        const val FUNCTIONAL_HEADER = "7DF"

        const val SET_HEADER = "ATSH"

        /** `ATCRA<addr>` narrows the receive filter; `ATCRA` alone clears it again. */
        const val SET_RECEIVE_FILTER = "ATCRA"

        const val PID_TIMEOUT_MILLIS = 1_500L

        /** A module that has to go and fetch a value is slower than one reading a sensor. */
        const val EXTENDED_TIMEOUT_MILLIS = 2_000L

        /** `7E0`, `726`: the header length that `ATSH 7DF` is the broadcast address of. */
        const val CAN_11_BIT_HEADER_CHARS = 3
        const val DTC_TIMEOUT_MILLIS = 3_000L
        const val CLEAR_TIMEOUT_MILLIS = 5_000L
        const val VIN_TIMEOUT_MILLIS = 3_000L

        private const val MONITOR_STATUS_PID = 0x01
        private const val MONITOR_STATUS_BYTES = 4
        private const val DTC_BYTES = 2
        private const val MAX_COUNT_DIGIT = 0xF
    }
}
