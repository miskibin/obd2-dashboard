package com.miskibin.obd2dashboard.obd

/**
 * The `7F` frame an ECU sends instead of an answer.
 *
 * The legislated services never use it — a car that does not support `010C` simply says
 * nothing and the adapter reports `NO DATA` — but the manufacturer-specific read service
 * (`22`) does, and its response code is the difference between "this car will never have
 * that sensor" and "ask again in a moment". Getting that wrong either burns a request per
 * connection forever on a DID the car has already refused, or writes off a DID that was
 * only busy.
 *
 * The layout is `7F <service> <code>`, where the service is the one that was *requested*
 * rather than the response marker — the only place in OBD-II where the request byte comes
 * back unchanged.
 */
object NegativeResponse {

    const val MARKER = 0x7F

    /** `serviceNotSupported`: this ECU does not implement the service at all. */
    const val SERVICE_NOT_SUPPORTED = 0x11

    /** `subFunctionNotSupported`. */
    const val SUB_FUNCTION_NOT_SUPPORTED = 0x12

    /** `requestOutOfRange`: the service exists and this identifier does not. */
    const val REQUEST_OUT_OF_RANGE = 0x31

    /** `busyRepeatRequest`: the ECU is doing something else; the request itself was fine. */
    const val BUSY_REPEAT_REQUEST = 0x21

    /** `conditionsNotCorrect`: right request, wrong moment — engine off, gearbox not ready. */
    const val CONDITIONS_NOT_CORRECT = 0x22

    /**
     * `requestCorrectlyReceived-ResponsePending`: the answer is coming, keep the channel
     * open. An ECU may send it several times before the real response.
     */
    const val RESPONSE_PENDING = 0x78

    private const val CODE_OFFSET = 2

    /** `7F <service> <code>`, and nothing else. */
    private const val LENGTH = 3

    /**
     * The response code [frames] carry for [service], or null when none of them is a
     * negative response to it.
     *
     * [RESPONSE_PENDING] is skipped rather than returned: it is a promise of an answer,
     * not an answer, and a caller asking "what did the car say" wants whatever came after.
     */
    fun codeFor(frames: List<ObdFrame>, service: Int): Int? = frames
        .mapNotNull { codeIn(it.data, service) }
        .firstOrNull { it != RESPONSE_PENDING }

    /**
     * True when every frame is a bare "response pending" and there is nothing else to read.
     *
     * This is what tells [ElmSession] to go on waiting for the same request rather than
     * handing the caller a `7F` and moving on, so it is asked of *every* response the
     * session parses — which is why it insists the frame be a negative response and
     * nothing else, rather than merely containing the bytes of one.
     *
     * Searching for a `7F` anywhere would misread ordinary data. `41 04 7F 05 78` is a
     * perfectly normal answer to `01 04 05` — engine load 49.8 %, coolant 80 °C — and
     * contains a `7F` two bytes ahead of a `78`. Treating that as a stall would throw the
     * reading away and then wait out the full timeout for a prompt that has already been
     * printed.
     */
    fun isPendingOnly(frames: List<ObdFrame>): Boolean =
        frames.isNotEmpty() && frames.all { payloadOf(it.data).let(::isPending) }

    private fun isPending(payload: List<Int>): Boolean = payload.size == LENGTH &&
        payload[0] == MARKER && payload[CODE_OFFSET] == RESPONSE_PENDING

    /**
     * The frame's bytes with a CAN single-frame length byte removed, when there is one.
     *
     * On CAN a three-byte negative response arrives as `03 7F 22 78`; on the older
     * protocols the same three bytes arrive alone. Anything else is returned untouched,
     * which makes it fail the length test above rather than be reinterpreted.
     */
    private fun payloadOf(data: List<Int>): List<Int> {
        val declared = data.firstOrNull() ?: return data
        return if (declared in 1 until data.size && data.size == declared + 1) data.drop(1) else data
    }

    /** Whether [code] means the identifier will never be answered by this ECU. */
    fun isPermanent(code: Int): Boolean = code == SERVICE_NOT_SUPPORTED ||
        code == SUB_FUNCTION_NOT_SUPPORTED || code == REQUEST_OUT_OF_RANGE

    /** Whether [code] means the request was sound and the moment was not. */
    fun isTransient(code: Int): Boolean =
        code == BUSY_REPEAT_REQUEST || code == CONDITIONS_NOT_CORRECT

    /**
     * The response code in one frame's bytes.
     *
     * [service] is checked when given, so a `7F 22 31` is not mistaken for a refusal of
     * some other service that happened to be in flight. The marker is searched for rather
     * than indexed at a fixed offset, because the frame still carries its ISO-TP length
     * byte and, on some protocols, a header the parser could not identify.
     */
    private fun codeIn(data: List<Int>, service: Int): Int? {
        for (index in data.indices) {
            // A `7F` too near the end cannot be the marker of this one, but a later
            // occurrence still can — a reassembled message can carry the byte as data.
            if (data[index] != MARKER || index + CODE_OFFSET >= data.size) continue
            if (data[index + 1] != service) continue
            return data[index + CODE_OFFSET]
        }
        return null
    }
}
