package com.miskibin.obd2dashboard.obd

/**
 * The names of the readings that come in families, and the wheels they are named after.
 *
 * Here rather than on [ExtendedPids] for one reason, and it is not tidiness: [ExtendedPids]
 * builds its list by asking every marque's table for its entries, and those tables need
 * these names to write themselves. A table that reached back into [ExtendedPids] for a name
 * would be asking a half-built object for something it has not made yet — which is an
 * initialisation deadlock, resolved by the JVM handing over a null and by the first marque
 * table touched deciding whether the app starts. The single-reading names next door are
 * `const`, compile to their own text and are safe to reach for from anywhere.
 */
internal object ExtendedNames {

    /** Tyre positions, in the order the tables report them. */
    val WHEELS = listOf("fl", "fr", "rl", "rr")

    fun tyrePressure(wheel: String) = "ext_tyre_pressure_$wheel"

    fun tyreTemperature(wheel: String) = "ext_tyre_temperature_$wheel"

    fun injectionDeviation(cylinder: Int) = "ext_injection_deviation_$cylinder"
}

/**
 * The units the extended tables report in, written once so that two marques reporting the
 * same reading cannot disagree about what it is measured in.
 *
 * Where a capture is in a unit the rest of the app does not use, the conversion happens in
 * the decoder rather than here: a tyre pressure that changed from psi to bar depending on
 * which badge is on the car would be worse than the arithmetic.
 */
internal object ExtendedUnits {
    const val CELSIUS = "°C"
    const val KPA = "kPa"
    const val BAR = "bar"
    const val PERCENT = "%"
    const val VOLT = "V"
    const val AMPERE = "A"
    const val MILLIOHM = "mΩ"
    const val GRAM = "g"
    const val KM = "km"
    const val MILLIMETRE = "mm"
    const val MILLIGRAM_PER_STROKE = "mg/str"
    const val NEWTON_METRE = "N·m"
    const val WATT = "W"
    const val KILOWATT_HOUR = "kWh"
    const val COUNT = "count"
}

/**
 * Reading numbers out of a response payload, and refusing to publish the impossible ones.
 *
 * The payload of an extended read is a block of bytes with a value at a known offset, and
 * the published tables name those offsets by letter — `A` is the first byte, `J` the tenth,
 * `B[34]` the thirty-fifth. Everything here indexes from zero instead, because a decoder
 * that says `data[9]` next to a table that says `J` is one off-by-one away from a plausible
 * wrong answer, and the letters do not survive past `Z` anyway.
 */
internal object ExtendedBytes {

    fun byte(data: IntArray, index: Int = 0): Double = data[index].toDouble()

    /** The signed reading of a single byte: `0xFF` is −1, not 255. */
    fun signedByte(data: IntArray, index: Int = 0): Double {
        val raw = data[index]
        return if (raw > Byte.MAX_VALUE) (raw - BYTE_RANGE).toDouble() else raw.toDouble()
    }

    fun word(data: IntArray, index: Int = 0): Double =
        (data[index] * WORD_SCALE + data[index + 1]).toDouble()

    fun signedWord(data: IntArray, index: Int = 0): Double {
        val raw = word(data, index)
        return if (raw > SIGNED_WORD_LIMIT) raw - WORD_RANGE else raw
    }

    fun triple(data: IntArray, index: Int = 0): Double =
        word(data, index) * WORD_SCALE + data[index + 2]

    fun quad(data: IntArray, index: Int = 0): Double =
        triple(data, index) * WORD_SCALE + data[index + 3]

    fun signedQuad(data: IntArray, index: Int = 0): Double {
        val raw = quad(data, index)
        return if (raw > SIGNED_QUAD_LIMIT) raw - QUAD_RANGE else raw
    }

    /**
     * [value] if a car could actually be reporting it, and nothing at all if it could not.
     *
     * The one honest answer to a published formula that two sources disagree about. Where
     * the disagreement is a factor — sixteenths against eightieths of a degree, milliamps
     * against amps — the wrong decoding lands somewhere no car has ever been, and a
     * dashboard that shows nothing is better than one that shows 800 °C of gearbox oil to
     * somebody about to tow a caravan.
     */
    fun sane(value: Double, range: ClosedFloatingPointRange<Double>): Double =
        if (value in range) value else NOT_USED

    /** Bar, from the pounds per square inch several of the tables report tyres in. */
    fun barFromPsi(psi: Double): Double = psi * BAR_PER_PSI

    /**
     * What a gearbox can actually be, for the three marques whose fluid temperature has two
     * published decodings.
     *
     * The lower end excludes −40 exactly rather than including it, which is not fussiness:
     * where the alternative decoding is a sixteen-bit one read as a byte, its high byte is
     * zero for every temperature under 216 °C, so a car using it answers `A − 40` = −40 °C
     * dead every single time. Excluding the one value it can produce is what tells the two
     * apart.
     */
    val GEARBOX_TEMPERATURE = -39.0..160.0

    /** What a tyre can be at, in bar, before the reading is a byte read at the wrong offset. */
    val TYRE_PRESSURE = 0.5..5.0

    /** What a twelve-volt system can be at, running or resting. */
    val LOW_VOLTAGE = 6.0..18.0

    /** What a charge percentage can be. */
    val PERCENTAGE = 0.0..100.0

    private const val BYTE_RANGE = 0x100
    private const val WORD_SCALE = 0x100
    private const val WORD_RANGE = 65_536.0
    private const val SIGNED_WORD_LIMIT = 32_767.0
    private const val QUAD_RANGE = 4_294_967_296.0
    private const val SIGNED_QUAD_LIMIT = 2_147_483_647.0
    private const val BAR_PER_PSI = 0.0689475729
}
