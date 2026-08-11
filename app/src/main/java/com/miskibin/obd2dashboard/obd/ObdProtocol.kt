package com.miskibin.obd2dashboard.obd

/**
 * `ATSP` / `ATDPN` protocol numbers.
 *
 * @param headerChars number of hex characters `ATH1` prepends to every response line;
 *   0 means "unknown, detect per line".
 */
enum class ObdProtocol(
    val number: Int,
    val headerChars: Int,
    val isCan: Boolean,
) {
    Automatic(0x0, 0, false),
    J1850Pwm(0x1, 6, false),
    J1850Vpw(0x2, 6, false),
    Iso9141(0x3, 6, false),
    Kwp2000SlowInit(0x4, 6, false),
    Kwp2000FastInit(0x5, 6, false),
    Can11Bit500(0x6, 3, true),
    Can29Bit500(0x7, 8, true),
    Can11Bit250(0x8, 3, true),
    Can29Bit250(0x9, 8, true),
    J1939(0xA, 8, true),
    User1Can(0xB, 3, true),
    User2Can(0xC, 3, true),
    ;

    /** The digit to send back in `ATSP<n>` to lock this protocol in. */
    val atspDigit: String get() = number.toString(16).uppercase()

    companion object {
        fun fromNumber(number: Int): ObdProtocol? = entries.firstOrNull { it.number == number }

        /**
         * Parses an `ATDPN` reply such as `A6` (auto-detected protocol 6) or `6`.
         * Returns the protocol and whether it was reached through the auto search.
         */
        fun parseDpn(text: String): Pair<ObdProtocol, Boolean>? {
            val compact = text.trim().uppercase().filterNot { it.isWhitespace() }
            if (compact.isEmpty()) return null
            val auto = compact.startsWith("A")
            val digit = compact.removePrefix("A").firstOrNull() ?: return null
            val number = digit.digitToIntOrNull(16) ?: return null
            return fromNumber(number)?.let { it to auto }
        }
    }
}
