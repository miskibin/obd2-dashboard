package com.miskibin.obd2dashboard.obd

/**
 * One assembled message from one responder. [header] is the `ATH1` prefix (`7E8`,
 * `486B10`, …) when it could be identified, null otherwise.
 */
data class ObdFrame(
    val header: String?,
    val data: List<Int>,
    val multiFrame: Boolean = false,
) {
    fun bytes(): IntArray = data.toIntArray()
}

/**
 * Turns the text lines of a response into per-ECU byte frames and pulls values out of
 * them by PID id.
 *
 * Nothing here indexes at a fixed offset: header length varies with the protocol, ECUs
 * omit PIDs they do not support, and ISO-TP splits long answers over several lines. The
 * data is always located by searching for the `0x40 + mode` marker and then walking
 * PID-by-PID using known byte counts.
 */
object ObdResponseParser {

    private const val HEX = "0123456789ABCDEF"

    fun frames(
        lines: List<String>,
        protocol: ObdProtocol = ObdProtocol.Automatic,
    ): List<ObdFrame> {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (line in lines) {
            val normalized = normalize(line) ?: continue
            val (header, body) = splitHeader(normalized, protocol)
            grouped.getOrPut(header.orEmpty()) { mutableListOf() } += body
        }
        return grouped.flatMap { (header, bodies) ->
            assemble(header.ifEmpty { null }, bodies)
        }
    }

    /**
     * Extracts the data bytes of every id in [requested] found in [frames].
     * [byteCountOf] returns how many data bytes a PID carries, or null if unknown —
     * an unknown PID stops the walk through that frame.
     */
    fun values(
        frames: List<ObdFrame>,
        mode: Int,
        requested: Collection<Int>,
        byteCountOf: (Int) -> Int? = Pids::byteCountOf,
    ): Map<Int, IntArray> {
        val marker = mode + RESPONSE_OFFSET
        val result = LinkedHashMap<Int, IntArray>()
        for (frame in frames) {
            var best: Map<Int, IntArray> = emptyMap()
            frame.data.forEachIndexed { index, byte ->
                if (byte == marker) {
                    val walked = walk(frame.data, index + 1, requested, byteCountOf)
                    if (walked.size > best.size) best = walked
                }
            }
            for ((pid, bytes) in best) if (pid !in result) result[pid] = bytes
        }
        return result
    }

    /** The bytes following the first occurrence of [marker] in any frame. */
    fun afterMarker(frames: List<ObdFrame>, vararg marker: Int): List<Int>? {
        for (frame in frames) {
            val at = indexOfSubList(frame.data, marker.toList())
            if (at >= 0) return frame.data.subList(at + marker.size, frame.data.size)
        }
        return null
    }

    /**
     * Union of the supported-PID bitmasks reported by every ECU that answered
     * `01<base>`; [base] is `0x00`, `0x20`, `0x40`, …
     */
    fun supportedPids(frames: List<ObdFrame>, base: Int): Set<Int> =
        supportedIds(frames, MODE_CURRENT_DATA, base)

    /**
     * The same bitmask walk for any service that publishes one.
     *
     * Mode 06 numbers its monitors in exactly the way mode 01 numbers its PIDs — `0600`
     * answers a four-byte mask, the last bit of which chains into `0620` — so the two
     * differ only in which response marker to look behind.
     */
    fun supportedIds(frames: List<ObdFrame>, mode: Int, base: Int): Set<Int> {
        val supported = sortedSetOf<Int>()
        for (frame in frames) {
            val mask = values(listOf(frame), mode, listOf(base)) { SUPPORT_MASK_BYTES }[base]
            if (mask != null && mask.size == SUPPORT_MASK_BYTES) supported += decodeSupportMask(base, mask)
        }
        return supported
    }

    /**
     * `41 00 BE 3E B8 11` → bit A7 is PID `base + 1`, descending to bit D0 =
     * `base + 0x20`.
     */
    fun decodeSupportMask(base: Int, mask: IntArray): Set<Int> {
        val bits = (mask[0].toLong() shl 24) or (mask[1].toLong() shl 16) or
            (mask[2].toLong() shl 8) or mask[3].toLong()
        return (1..32)
            .filter { (bits shr (32 - it)) and 1L == 1L }
            .map { base + it }
            .toSet()
    }

    /**
     * The last bit of a block means "the next block query is supported", so
     * `0100` chains into `0120`, `0140` and so on only while that bit is set.
     */
    fun nextSupportBlock(base: Int, supported: Set<Int>): Int? {
        val next = base + SUPPORT_BLOCK_SIZE
        return if (next in supported && next <= LAST_SUPPORT_BLOCK) next else null
    }

    private fun walk(
        data: List<Int>,
        start: Int,
        requested: Collection<Int>,
        byteCountOf: (Int) -> Int?,
    ): Map<Int, IntArray> {
        val found = LinkedHashMap<Int, IntArray>()
        var index = start
        while (index < data.size) {
            val pid = data[index]
            val count = byteCountOf(pid) ?: break
            if (index + 1 + count > data.size) break
            if (pid in requested) {
                found[pid] = data.subList(index + 1, index + 1 + count).toIntArray()
            }
            index += 1 + count
        }
        return found
    }

    private fun normalize(line: String): String? {
        val compact = line.uppercase().filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return null
        return if (compact.all { it in HEX || it == ':' }) compact else null
    }

    private fun splitHeader(line: String, protocol: ObdProtocol): Pair<String?, String> {
        val colon = line.indexOf(':')
        if (colon >= 0) {
            val prefix = line.substring(0, colon)
            return if (prefix.length > 1) {
                prefix.dropLast(1) to line.substring(colon - 1)
            } else {
                null to line
            }
        }
        val headerChars = when {
            protocol.headerChars > 0 -> protocol.headerChars
            line.length % 2 == 1 -> CAN_11_BIT_HEADER_CHARS
            else -> 0
        }
        return if (headerChars in 1 until line.length) {
            line.take(headerChars) to line.drop(headerChars)
        } else {
            null to line
        }
    }

    private fun assemble(header: String?, bodies: List<String>): List<ObdFrame> {
        val sequenced = bodies.filter { it.contains(':') }
        if (sequenced.isNotEmpty()) {
            val declared = bodies
                .firstOrNull { !it.contains(':') && it.length <= LENGTH_LINE_CHARS }
                ?.toIntOrNull(16)
            val payload = sequenced.flatMap { hexBytes(it.substringAfter(':')) }
            return listOf(ObdFrame(header, truncate(payload, declared), multiFrame = true))
        }

        val parsed = bodies.map(::hexBytes).filter { it.isNotEmpty() }
        val first = parsed.firstOrNull() ?: return emptyList()
        if (first.size >= 2 && (first[0] shr 4) == PCI_FIRST_FRAME) {
            val declared = ((first[0] and 0x0F) shl 8) or first[1]
            val payload = first.drop(2) + parsed.drop(1).flatMap { frame ->
                if ((frame[0] shr 4) == PCI_CONSECUTIVE_FRAME) frame.drop(1) else frame
            }
            return listOf(ObdFrame(header, truncate(payload, declared), multiFrame = true))
        }
        return parsed.map { ObdFrame(header, it) }
    }

    private fun truncate(payload: List<Int>, declared: Int?): List<Int> =
        if (declared != null && declared in 1..payload.size) payload.take(declared) else payload

    private fun hexBytes(text: String): List<Int> {
        val hex = text.filter { it in HEX }
        return (hex.length % 2 until hex.length step 2).map { hex.substring(it, it + 2).toInt(16) }
    }

    private fun indexOfSubList(data: List<Int>, marker: List<Int>): Int {
        if (marker.isEmpty() || marker.size > data.size) return -1
        for (start in 0..data.size - marker.size) {
            if ((marker.indices).all { data[start + it] == marker[it] }) return start
        }
        return -1
    }

    const val RESPONSE_OFFSET = 0x40
    const val SUPPORT_BLOCK_SIZE = 0x20
    const val LAST_SUPPORT_BLOCK = 0xE0

    private const val SUPPORT_MASK_BYTES = 4
    private const val CAN_11_BIT_HEADER_CHARS = 3
    private const val LENGTH_LINE_CHARS = 3
    private const val PCI_FIRST_FRAME = 1
    private const val PCI_CONSECUTIVE_FRAME = 2
}
