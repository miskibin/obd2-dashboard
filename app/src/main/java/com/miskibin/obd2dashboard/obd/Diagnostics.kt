package com.miskibin.obd2dashboard.obd

/** The three readable DTC stores. */
enum class DtcKind(val mode: Int) {
    Stored(MODE_STORED_DTC),
    Pending(MODE_PENDING_DTC),
    Permanent(MODE_PERMANENT_DTC),
    ;

    val request: String get() = "%02X".format(mode)
    val responseByte: Int get() = mode + ObdResponseParser.RESPONSE_OFFSET
}

/** A trouble code plus the ECU header that reported it, when headers are on. */
data class Dtc(val code: String, val kind: DtcKind, val ecu: String? = null)

/** `0101` byte A: lamp state and the confirmed-code counter. */
data class MonitorStatus(
    val milOn: Boolean,
    val dtcCount: Int,
    val readinessBytes: List<Int> = emptyList(),
)

data class Diagnostics(
    val stored: List<Dtc> = emptyList(),
    val pending: List<Dtc> = emptyList(),
    val permanent: List<Dtc> = emptyList(),
    val monitorStatus: MonitorStatus? = null,
) {
    val all: List<Dtc> get() = stored + pending + permanent
}

object DtcDecoder {

    private const val SYSTEMS = "PCBU"

    /** Returns null for the `00 00` padding pairs, which are not code P0000. */
    fun decode(a: Int, b: Int): String? {
        if (a == 0 && b == 0) return null
        val system = SYSTEMS[(a shr 6) and 0x03]
        val second = (a shr 4) and 0x03
        val third = a and 0x0F
        val fourth = (b shr 4) and 0x0F
        val fifth = b and 0x0F
        return "%c%d%X%X%X".format(system, second, third, fourth, fifth)
    }

    /**
     * Decodes a `43`/`47`/`4A` response.
     *
     * On CAN the response byte is followed by a count of codes; the other protocols go
     * straight into pairs padded with `00 00`. Every ECU that answered contributes its
     * own frame and codes are tagged with the header that carried them.
     */
    fun parse(frames: List<ObdFrame>, kind: DtcKind, isCan: Boolean): List<Dtc> {
        val codes = LinkedHashSet<Dtc>()
        for (frame in frames) {
            val start = frame.data.indexOf(kind.responseByte)
            if (start < 0) continue
            var payload = frame.data.subList(start + 1, frame.data.size)
            var expected: Int? = null
            if (isCan && payload.isNotEmpty()) {
                expected = payload.first()
                payload = payload.subList(1, payload.size)
            }
            val decoded = payload.chunked(2)
                .filter { it.size == 2 }
                .mapNotNull { decode(it[0], it[1]) }
            val limited = if (expected != null && expected in 1..decoded.size) {
                decoded.take(expected)
            } else {
                decoded
            }
            limited.forEach { codes += Dtc(it, kind, frame.header) }
        }
        return codes.toList()
    }

    fun parseMonitorStatus(data: IntArray): MonitorStatus = MonitorStatus(
        milOn = (data[0] and 0x80) != 0,
        dtcCount = data[0] and 0x7F,
        readinessBytes = data.drop(1),
    )

    /** Mode 04 answers a bare `44` on success; anything else means nothing was cleared. */
    fun isClearAccepted(lines: List<String>): Boolean =
        lines.any { it.uppercase().filterNot(Char::isWhitespace).contains("44") }
}

object VinDecoder {

    private const val VIN_LENGTH = 17

    /**
     * Decodes an `0902` reply.
     *
     * CAN delivers one reassembled ISO-TP message (`49 02 01` + 17 ASCII bytes); the
     * older protocols send several `49 02 <seq>` lines of four bytes each, which are
     * ordered by their sequence byte and stripped of leading `00` filler.
     */
    fun decode(frames: List<ObdFrame>): String? {
        val single = frames.firstOrNull { it.multiFrame }
            ?: frames.singleOrNull { it.data.contains(MODE_VEHICLE_INFO + ObdResponseParser.RESPONSE_OFFSET) }
        if (single != null) {
            val payload = ObdResponseParser.afterMarker(
                listOf(single),
                MODE_VEHICLE_INFO + ObdResponseParser.RESPONSE_OFFSET,
                0x02,
            )
            val ascii = payload?.drop(1)?.filter { it != 0x00 }
            if (ascii != null && ascii.size >= VIN_LENGTH) return toAscii(ascii.takeLast(VIN_LENGTH))
        }

        val ordered = frames.mapNotNull { frame ->
            val payload = ObdResponseParser.afterMarker(
                listOf(frame),
                MODE_VEHICLE_INFO + ObdResponseParser.RESPONSE_OFFSET,
                0x02,
            ) ?: return@mapNotNull null
            if (payload.isEmpty()) null else payload.first() to payload.drop(1)
        }
        if (ordered.isEmpty()) return null
        val bytes = ordered.sortedBy { it.first }.flatMap { it.second }.filter { it != 0x00 }
        return if (bytes.size >= VIN_LENGTH) toAscii(bytes.takeLast(VIN_LENGTH)) else null
    }

    private fun toAscii(bytes: List<Int>): String =
        bytes.map { it.toChar() }.joinToString("")
}

object ElmVoltage {

    private val WITH_UNIT = Regex("(\\d+(?:\\.\\d+)?)\\s*V", RegexOption.IGNORE_CASE)
    private val BARE_DECIMAL = Regex("^(\\d+\\.\\d+)$")

    /** Parses an `ATRV` reply such as `12.6V`; clones sometimes drop the unit. */
    fun parse(text: String): Double? {
        val trimmed = text.trim()
        val match = WITH_UNIT.find(trimmed) ?: BARE_DECIMAL.find(trimmed) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    fun parse(lines: List<String>): Double? = lines.firstNotNullOfOrNull { parse(it) }
}
