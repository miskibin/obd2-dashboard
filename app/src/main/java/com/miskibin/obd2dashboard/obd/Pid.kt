package com.miskibin.obd2dashboard.obd

/** How often a PID is worth asking for; see [PidScheduler]. */
enum class PidTier { Fast, Medium, Slow }

/**
 * One number carried by a PID.
 *
 * Most PIDs carry exactly one, but the standard packs several into a single response often
 * enough that it cannot be treated as the exception: an oxygen sensor reports its voltage
 * *and* the trim being applied to it, a wide-range sensor reports lambda *and* current, and
 * the sensor-array PIDs (`0166`, `0167`, …) lead with a bitmask and then list every sensor
 * fitted. Splitting the response into channels is what lets all of those reach the
 * dashboard instead of only their first byte.
 *
 * [decode] returns [NOT_USED] when the response says this channel is not fitted, which is
 * a different thing from the car not answering at all.
 */
data class PidChannel(
    val index: Int,
    val name: String,
    val unit: String,
    val decode: (IntArray) -> Double,
)

/**
 * A SAE J1979 Mode 01 parameter.
 *
 * [bytes] is the data-byte count the standard gives it, which is what walks a multi-PID
 * response frame apart — getting it wrong desynchronises every PID after it in the frame,
 * so a PID is only listed here once its length is known, never guessed.
 */
data class Pid(
    val id: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val tier: PidTier,
    val channels: List<PidChannel>,
) {
    /** The hex request for this PID, e.g. `010C`. */
    val command: String get() = "%02X%02X".format(MODE_CURRENT_DATA, id)

    /** The primary channel's value, which for a single-value PID is the whole answer. */
    val decode: (IntArray) -> Double get() = channels[0].decode
}

const val MODE_CURRENT_DATA = 0x01
const val MODE_FREEZE_FRAME = 0x02
const val MODE_STORED_DTC = 0x03
const val MODE_CLEAR_DTC = 0x04
const val MODE_PENDING_DTC = 0x07
const val MODE_VEHICLE_INFO = 0x09
const val MODE_PERMANENT_DTC = 0x0A

/**
 * What a channel reports when the response says the sensor is not fitted.
 *
 * The standard signals this in-band — `0xFF` in an oxygen sensor's trim byte, a clear bit
 * in a sensor array's leading bitmask — so it has to be told apart from a value. Nothing
 * downstream ever publishes it: [PidScheduler] drops non-finite readings, which is what
 * keeps a four-sensor PID on a two-sensor car from inventing two more.
 */
val NOT_USED = Double.NaN

private const val NOT_USED_BYTE = 0xFF

private const val PERCENT = "%"
private const val CELSIUS = "°C"
private const val DEGREES = "°"
private const val KPA = "kPa"
private const val VOLT = "V"
private const val LAMBDA = "λ"
private const val MILLIAMP = "mA"
private const val GRAMS_PER_SECOND = "g/s"
private const val RPM = "rpm"
private const val COUNT = "count"

/** A bit-encoded channel that reads as on or off rather than as a quantity. */
private const val FLAG = ""

private const val ON = 1.0
private const val OFF = 0.0

private fun word(data: IntArray) = data[0] * 256.0 + data[1]

/** The unsigned 16-bit value starting at [at]. */
private fun wordAt(data: IntArray, at: Int) = data[at] * 256.0 + data[at + 1]

private fun signedWord(data: IntArray): Double {
    val raw = data[0] * 256 + data[1]
    return if (raw > 0x7FFF) (raw - 0x10000).toDouble() else raw.toDouble()
}

private fun ratio(data: IntArray) = data[0] * 100.0 / 255.0

private fun trim(data: IntArray) = data[0] * 100.0 / 128.0 - 100.0

/** A percentage centred on zero, as every fuel-trim style byte is encoded. */
private fun trimAt(data: IntArray, at: Int) = data[at] * 100.0 / 128.0 - 100.0

private fun tempC(data: IntArray) = data[0] - 40.0

private fun tempCAt(data: IntArray, at: Int) = data[at] - 40.0

/**
 * Whether sensor [sensor] (counting from zero) is fitted, per the bitmask in byte A that
 * the sensor-array PIDs lead with.
 */
private fun fitted(data: IntArray, sensor: Int) = (data[0] shr sensor) and 1 == 1

/** Bit [index] of byte [at], as the on/off number a bit-encoded channel publishes. */
private fun flagAt(data: IntArray, at: Int, index: Int): Double =
    if ((data[at] shr index) and 1 == 1) ON else OFF

/** A single-value PID, where the channel is just the PID itself. */
private fun pid(
    id: Int,
    name: String,
    unit: String,
    bytes: Int,
    tier: PidTier,
    decode: (IntArray) -> Double,
) = Pid(id, name, unit, bytes, tier, listOf(PidChannel(0, name, unit, decode)))

/**
 * Reading key for one channel of one PID.
 *
 * Channel 0 keeps the bare PID id, so every key that has ever been written to a saved tile
 * list or a CSV header still means what it meant.
 */
fun sensorKey(pid: Int, channel: Int): Int = pid or (channel shl 8)

/** The Mode 01 PID a reading key belongs to. */
fun keyPid(key: Int): Int = key and 0xFF

/** Which of that PID's channels a reading key refers to. */
fun keyChannel(key: Int): Int = key shr 8

/**
 * The Mode 01 PIDs this app knows how to decode, keyed by PID id.
 *
 * Every formula here is SAE J1979; a PID that the standard defines but whose layout is
 * ambiguous between revisions is deliberately absent rather than approximated, because a
 * plausible wrong number on a dashboard is worse than a missing one.
 */
object Pids {

    const val FUEL_SYSTEM_STATUS = 0x03
    const val ENGINE_LOAD = 0x04
    const val COOLANT_TEMP = 0x05
    const val SHORT_FUEL_TRIM_1 = 0x06
    const val LONG_FUEL_TRIM_1 = 0x07
    const val INTAKE_MAP = 0x0B
    const val ENGINE_RPM = 0x0C
    const val VEHICLE_SPEED = 0x0D
    const val TIMING_ADVANCE = 0x0E
    const val INTAKE_AIR_TEMP = 0x0F
    const val MAF_RATE = 0x10
    const val THROTTLE_POSITION = 0x11
    const val O2_SENSORS_PRESENT = 0x13
    const val RUN_TIME = 0x1F
    const val DISTANCE_WITH_MIL = 0x21
    const val FUEL_LEVEL = 0x2F
    const val DISTANCE_SINCE_CLEARED = 0x31
    const val BAROMETRIC_PRESSURE = 0x33
    const val CONTROL_MODULE_VOLTAGE = 0x42
    const val COMMANDED_EQUIV_RATIO = 0x44
    const val AMBIENT_AIR_TEMP = 0x46
    const val FUEL_TYPE = 0x51
    const val OIL_TEMP = 0x5C
    const val FUEL_RATE = 0x5E
    const val AUXILIARY_IO = 0x65
    const val FRICTION_TORQUE = 0x8E
    const val FUEL_RATE_MASS = 0x9D
    const val TRANSMISSION_GEAR = 0xA4

    /** `0114`–`011B`: narrow-band oxygen sensors 1-8, voltage and the trim applied to it. */
    private val NARROW_BAND_O2 = 0x14..0x1B

    /** `0124`–`012B`: wide-range sensors 1-8, reporting lambda and a voltage. */
    private val WIDE_RANGE_O2_VOLTAGE = 0x24..0x2B

    /** `0134`–`013B`: the same sensors on cars that report a current instead. */
    private val WIDE_RANGE_O2_CURRENT = 0x34..0x3B

    private fun narrowBandO2(id: Int): Pid {
        val sensor = id - NARROW_BAND_O2.first + 1
        return Pid(
            id = id,
            name = "Oxygen sensor $sensor",
            unit = VOLT,
            bytes = 2,
            tier = PidTier.Slow,
            channels = listOf(
                PidChannel(0, "Oxygen sensor $sensor voltage", VOLT) { it[0] / 200.0 },
                PidChannel(1, "Oxygen sensor $sensor short term fuel trim", PERCENT) {
                    // 0xFF is the standard's "this sensor is not used in the trim
                    // calculation", not a 99% correction.
                    if (it[1] == NOT_USED_BYTE) NOT_USED else trimAt(it, 1)
                },
            ),
        )
    }

    private fun wideRangeO2Voltage(id: Int): Pid {
        val sensor = id - WIDE_RANGE_O2_VOLTAGE.first + 1
        return Pid(
            id = id,
            name = "Oxygen sensor $sensor lambda",
            unit = LAMBDA,
            bytes = 4,
            tier = PidTier.Slow,
            channels = listOf(
                PidChannel(0, "Oxygen sensor $sensor lambda", LAMBDA) {
                    2.0 * wordAt(it, 0) / 65_536.0
                },
                PidChannel(1, "Oxygen sensor $sensor voltage", VOLT) {
                    8.0 * wordAt(it, 2) / 65_536.0
                },
            ),
        )
    }

    private fun wideRangeO2Current(id: Int): Pid {
        val sensor = id - WIDE_RANGE_O2_CURRENT.first + 1
        return Pid(
            id = id,
            name = "Oxygen sensor $sensor lambda",
            unit = LAMBDA,
            bytes = 4,
            tier = PidTier.Slow,
            channels = listOf(
                PidChannel(0, "Oxygen sensor $sensor lambda", LAMBDA) {
                    2.0 * wordAt(it, 0) / 65_536.0
                },
                PidChannel(1, "Oxygen sensor $sensor current", MILLIAMP) {
                    wordAt(it, 2) / 256.0 - 128.0
                },
            ),
        )
    }

    /** `0155`–`0158`: secondary sensor trims, each byte a different bank. */
    private fun secondaryTrim(id: Int, name: String, firstBank: Int, secondBank: Int) = Pid(
        id = id,
        name = "$name, bank $firstBank",
        unit = PERCENT,
        bytes = 2,
        tier = PidTier.Slow,
        channels = listOf(
            PidChannel(0, "$name, bank $firstBank", PERCENT) { trimAt(it, 0) },
            PidChannel(1, "$name, bank $secondBank", PERCENT) { trimAt(it, 1) },
        ),
    )

    /**
     * `0103`: which loop each fuel system is running in, as the enumeration the standard
     * defines (2 = closed loop, 4 = open loop under load, and so on).
     *
     * Byte B describes a second fuel system "if it exists", and a car with one system
     * answers zero there — which is also the code for "the engine is off". The two cannot
     * be told apart, so the second channel is only published when it carries something
     * other than zero: a bank that is genuinely off is better missing than invented.
     */
    private fun fuelSystemStatus() = Pid(
        id = FUEL_SYSTEM_STATUS,
        name = "Fuel system 1 status",
        unit = FLAG,
        bytes = 2,
        tier = PidTier.Medium,
        channels = listOf(
            PidChannel(0, "Fuel system 1 status", FLAG) { it[0].toDouble() },
            PidChannel(1, "Fuel system 2 status", FLAG) {
                if (it[1] == 0) NOT_USED else it[1].toDouble()
            },
        ),
    )

    /**
     * `0165`: the bits the ECU exposes for the driver-facing auxiliaries.
     *
     * Byte A says which of the five the car implements and byte B carries their values, so
     * every channel is gated on its own support bit rather than published as a zero the
     * car never claimed. The gear in the top nibble of B is the gear the ECU is *asking
     * for* — the shift indicator on the dash — and not the gear the box is in; that one is
     * `01A4`.
     */
    private fun auxiliaryIo() = Pid(
        id = AUXILIARY_IO,
        name = "Recommended gear",
        unit = FLAG,
        bytes = 2,
        tier = PidTier.Medium,
        channels = listOf(
            PidChannel(0, "Recommended gear", FLAG) {
                if (fitted(it, AUX_GEAR_BIT)) (it[1] shr Byte.SIZE_BITS / 2).toDouble() else NOT_USED
            },
            PidChannel(1, "Glow plug lamp", FLAG) {
                if (fitted(it, AUX_GLOW_PLUG_BIT)) flagAt(it, 1, AUX_GLOW_PLUG_BIT) else NOT_USED
            },
            PidChannel(2, "Manual gearbox in neutral", FLAG) {
                if (fitted(it, AUX_MANUAL_NEUTRAL_BIT)) flagAt(it, 1, AUX_MANUAL_NEUTRAL_BIT) else NOT_USED
            },
            PidChannel(3, "Automatic gearbox in neutral", FLAG) {
                if (fitted(it, AUX_AUTO_NEUTRAL_BIT)) flagAt(it, 1, AUX_AUTO_NEUTRAL_BIT) else NOT_USED
            },
            PidChannel(4, "Power take-off active", FLAG) {
                if (fitted(it, AUX_PTO_BIT)) flagAt(it, 1, AUX_PTO_BIT) else NOT_USED
            },
        ),
    )

    /**
     * `019D`: fuel flow by mass, which is what `015E`'s litres per hour are computed from.
     *
     * Four bytes carrying two rates: what the engine is burning and what the whole vehicle
     * is, which differ on a car with a fuel-fired heater or a second consumer.
     */
    private fun fuelRateMass() = Pid(
        id = FUEL_RATE_MASS,
        name = "Engine fuel rate by mass",
        unit = GRAMS_PER_SECOND,
        bytes = 4,
        tier = PidTier.Medium,
        channels = listOf(
            PidChannel(0, "Engine fuel rate by mass", GRAMS_PER_SECOND) {
                wordAt(it, 0) / FUEL_RATE_MASS_DIVISOR
            },
            PidChannel(1, "Vehicle fuel rate by mass", GRAMS_PER_SECOND) {
                wordAt(it, 2) / FUEL_RATE_MASS_DIVISOR
            },
        ),
    )

    /** Bit positions of byte A of `0165`, which byte B repeats as values. */
    private const val AUX_PTO_BIT = 0
    private const val AUX_AUTO_NEUTRAL_BIT = 1
    private const val AUX_MANUAL_NEUTRAL_BIT = 2
    private const val AUX_GLOW_PLUG_BIT = 3
    private const val AUX_GEAR_BIT = 4

    private const val FUEL_RATE_MASS_DIVISOR = 50.0

    /** Percent-torque bytes are centred on 125, so 125 is nought and 100 is −25 %. */
    private const val TORQUE_OFFSET = 125.0

    val entries: List<Pid> = buildList {
        add(fuelSystemStatus())
        add(pid(ENGINE_LOAD, "Calculated engine load", PERCENT, 1, PidTier.Medium, ::ratio))
        add(pid(COOLANT_TEMP, "Engine coolant temperature", CELSIUS, 1, PidTier.Medium, ::tempC))
        add(pid(SHORT_FUEL_TRIM_1, "Short term fuel trim, bank 1", PERCENT, 1, PidTier.Medium, ::trim))
        add(pid(LONG_FUEL_TRIM_1, "Long term fuel trim, bank 1", PERCENT, 1, PidTier.Medium, ::trim))
        add(pid(0x08, "Short term fuel trim, bank 2", PERCENT, 1, PidTier.Slow, ::trim))
        add(pid(0x09, "Long term fuel trim, bank 2", PERCENT, 1, PidTier.Slow, ::trim))
        add(pid(0x0A, "Fuel pressure", KPA, 1, PidTier.Slow) { it[0] * 3.0 })
        add(pid(INTAKE_MAP, "Intake manifold absolute pressure", KPA, 1, PidTier.Fast) { it[0].toDouble() })
        add(pid(ENGINE_RPM, "Engine RPM", RPM, 2, PidTier.Fast) { word(it) / 4.0 })
        add(pid(VEHICLE_SPEED, "Vehicle speed", "km/h", 1, PidTier.Fast) { it[0].toDouble() })
        add(pid(TIMING_ADVANCE, "Timing advance", DEGREES, 1, PidTier.Medium) { it[0] / 2.0 - 64.0 })
        add(pid(INTAKE_AIR_TEMP, "Intake air temperature", CELSIUS, 1, PidTier.Medium, ::tempC))
        add(pid(MAF_RATE, "MAF air flow rate", GRAMS_PER_SECOND, 2, PidTier.Fast) { word(it) / 100.0 })
        add(pid(THROTTLE_POSITION, "Throttle position", PERCENT, 1, PidTier.Fast, ::ratio))
        add(pid(O2_SENSORS_PRESENT, "Oxygen sensors fitted", COUNT, 1, PidTier.Slow) {
            Integer.bitCount(it[0]).toDouble()
        })
        NARROW_BAND_O2.forEach { add(narrowBandO2(it)) }
        add(pid(RUN_TIME, "Run time since engine start", "s", 2, PidTier.Slow, ::word))
        add(pid(DISTANCE_WITH_MIL, "Distance travelled with MIL on", "km", 2, PidTier.Slow, ::word))
        add(pid(0x22, "Fuel rail pressure", KPA, 2, PidTier.Slow) { 0.079 * word(it) })
        add(pid(0x23, "Fuel rail gauge pressure", KPA, 2, PidTier.Slow) { 10.0 * word(it) })
        WIDE_RANGE_O2_VOLTAGE.forEach { add(wideRangeO2Voltage(it)) }
        add(pid(0x2C, "Commanded EGR", PERCENT, 1, PidTier.Slow, ::ratio))
        add(pid(0x2D, "EGR error", PERCENT, 1, PidTier.Slow, ::trim))
        add(pid(0x2E, "Commanded evaporative purge", PERCENT, 1, PidTier.Slow, ::ratio))
        add(pid(FUEL_LEVEL, "Fuel tank level input", PERCENT, 1, PidTier.Slow, ::ratio))
        add(pid(0x30, "Warm-ups since codes cleared", "count", 1, PidTier.Slow) { it[0].toDouble() })
        add(pid(DISTANCE_SINCE_CLEARED, "Distance since codes cleared", "km", 2, PidTier.Slow, ::word))
        add(pid(0x32, "Evap system vapour pressure", "Pa", 2, PidTier.Slow) { signedWord(it) / 4.0 })
        add(pid(BAROMETRIC_PRESSURE, "Absolute barometric pressure", KPA, 1, PidTier.Medium) { it[0].toDouble() })
        WIDE_RANGE_O2_CURRENT.forEach { add(wideRangeO2Current(it)) }
        add(pid(0x3C, "Catalyst temperature B1S1", CELSIUS, 2, PidTier.Slow) { word(it) / 10.0 - 40.0 })
        add(pid(0x3D, "Catalyst temperature B2S1", CELSIUS, 2, PidTier.Slow) { word(it) / 10.0 - 40.0 })
        add(pid(0x3E, "Catalyst temperature B1S2", CELSIUS, 2, PidTier.Slow) { word(it) / 10.0 - 40.0 })
        add(pid(0x3F, "Catalyst temperature B2S2", CELSIUS, 2, PidTier.Slow) { word(it) / 10.0 - 40.0 })
        add(pid(CONTROL_MODULE_VOLTAGE, "Control module voltage", VOLT, 2, PidTier.Medium) { word(it) / 1000.0 })
        add(pid(0x43, "Absolute load value", PERCENT, 2, PidTier.Slow) { word(it) * 100.0 / 255.0 })
        add(pid(COMMANDED_EQUIV_RATIO, "Commanded air-fuel equivalence ratio", LAMBDA, 2, PidTier.Medium) {
            2.0 * word(it) / 65_536.0
        })
        add(pid(0x45, "Relative throttle position", PERCENT, 1, PidTier.Medium, ::ratio))
        add(pid(AMBIENT_AIR_TEMP, "Ambient air temperature", CELSIUS, 1, PidTier.Slow, ::tempC))
        add(pid(0x47, "Absolute throttle position B", PERCENT, 1, PidTier.Slow, ::ratio))
        add(pid(0x48, "Absolute throttle position C", PERCENT, 1, PidTier.Slow, ::ratio))
        add(pid(0x49, "Accelerator pedal position D", PERCENT, 1, PidTier.Slow, ::ratio))
        add(pid(0x4A, "Accelerator pedal position E", PERCENT, 1, PidTier.Slow, ::ratio))
        add(pid(0x4B, "Accelerator pedal position F", PERCENT, 1, PidTier.Slow, ::ratio))
        add(pid(0x4C, "Commanded throttle actuator", PERCENT, 1, PidTier.Slow, ::ratio))
        add(pid(0x4D, "Time run with MIL on", "min", 2, PidTier.Slow, ::word))
        add(pid(0x4E, "Time since trouble codes cleared", "min", 2, PidTier.Slow, ::word))
        add(pid(FUEL_TYPE, "Fuel type", "", 1, PidTier.Slow) { it[0].toDouble() })
        add(pid(0x52, "Ethanol fuel percentage", PERCENT, 1, PidTier.Slow, ::ratio))
        add(pid(0x53, "Absolute evap system vapour pressure", KPA, 2, PidTier.Slow) { word(it) / 200.0 })
        add(pid(0x54, "Evap system vapour pressure", "Pa", 2, PidTier.Slow) { word(it) - 32_767.0 })
        add(secondaryTrim(0x55, "Short term secondary oxygen sensor trim", 1, 3))
        add(secondaryTrim(0x56, "Long term secondary oxygen sensor trim", 1, 3))
        add(secondaryTrim(0x57, "Short term secondary oxygen sensor trim", 2, 4))
        add(secondaryTrim(0x58, "Long term secondary oxygen sensor trim", 2, 4))
        add(pid(0x59, "Fuel rail absolute pressure", KPA, 2, PidTier.Slow) { 10.0 * word(it) })
        add(pid(0x5A, "Relative accelerator pedal position", PERCENT, 1, PidTier.Slow, ::ratio))
        add(pid(0x5B, "Hybrid battery pack remaining life", PERCENT, 1, PidTier.Slow, ::ratio))
        add(pid(OIL_TEMP, "Engine oil temperature", CELSIUS, 1, PidTier.Slow, ::tempC))
        add(pid(0x5D, "Fuel injection timing", DEGREES, 2, PidTier.Slow) { word(it) / 128.0 - 210.0 })
        add(pid(FUEL_RATE, "Engine fuel rate", "L/h", 2, PidTier.Medium) { word(it) / 20.0 })
        add(pid(0x61, "Driver's demand engine torque", PERCENT, 1, PidTier.Slow) { it[0] - 125.0 })
        add(pid(0x62, "Actual engine torque", PERCENT, 1, PidTier.Slow) { it[0] - 125.0 })
        add(pid(0x63, "Engine reference torque", "N·m", 2, PidTier.Slow, ::word))
        add(auxiliaryIo())
        add(
            Pid(0x66, "MAF sensor A", GRAMS_PER_SECOND, 5, PidTier.Slow, listOf(
                PidChannel(0, "MAF sensor A", GRAMS_PER_SECOND) {
                    if (fitted(it, 0)) wordAt(it, 1) / 32.0 else NOT_USED
                },
                PidChannel(1, "MAF sensor B", GRAMS_PER_SECOND) {
                    if (fitted(it, 1)) wordAt(it, 3) / 32.0 else NOT_USED
                },
            )),
        )
        add(
            Pid(0x67, "Engine coolant temperature sensor 1", CELSIUS, 3, PidTier.Slow, listOf(
                PidChannel(0, "Engine coolant temperature sensor 1", CELSIUS) {
                    if (fitted(it, 0)) tempCAt(it, 1) else NOT_USED
                },
                PidChannel(1, "Engine coolant temperature sensor 2", CELSIUS) {
                    if (fitted(it, 1)) tempCAt(it, 2) else NOT_USED
                },
            )),
        )
        add(
            Pid(0x68, "Intake air temperature sensor 1", CELSIUS, 7, PidTier.Slow, listOf(
                PidChannel(0, "Intake air temperature, bank 1 sensor 1", CELSIUS) {
                    if (fitted(it, 0)) tempCAt(it, 1) else NOT_USED
                },
                PidChannel(1, "Intake air temperature, bank 1 sensor 2", CELSIUS) {
                    if (fitted(it, 1)) tempCAt(it, 2) else NOT_USED
                },
                PidChannel(2, "Intake air temperature, bank 1 sensor 3", CELSIUS) {
                    if (fitted(it, 2)) tempCAt(it, 3) else NOT_USED
                },
                PidChannel(3, "Intake air temperature, bank 2 sensor 1", CELSIUS) {
                    if (fitted(it, 3)) tempCAt(it, 4) else NOT_USED
                },
                PidChannel(4, "Intake air temperature, bank 2 sensor 2", CELSIUS) {
                    if (fitted(it, 4)) tempCAt(it, 5) else NOT_USED
                },
                PidChannel(5, "Intake air temperature, bank 2 sensor 3", CELSIUS) {
                    if (fitted(it, 5)) tempCAt(it, 6) else NOT_USED
                },
            )),
        )
        add(
            Pid(0x6B, "EGR temperature, bank 1", CELSIUS, 5, PidTier.Slow, listOf(
                PidChannel(0, "EGR temperature, bank 1 sensor 1", CELSIUS) {
                    if (fitted(it, 0)) tempCAt(it, 1) else NOT_USED
                },
                PidChannel(1, "EGR temperature, bank 1 sensor 2", CELSIUS) {
                    if (fitted(it, 1)) tempCAt(it, 2) else NOT_USED
                },
                PidChannel(2, "EGR temperature, bank 2 sensor 1", CELSIUS) {
                    if (fitted(it, 2)) tempCAt(it, 3) else NOT_USED
                },
                PidChannel(3, "EGR temperature, bank 2 sensor 2", CELSIUS) {
                    if (fitted(it, 3)) tempCAt(it, 4) else NOT_USED
                },
            )),
        )
        add(
            Pid(0x73, "Exhaust pressure, bank 1", KPA, 5, PidTier.Slow, listOf(
                PidChannel(0, "Exhaust pressure, bank 1", KPA) {
                    if (fitted(it, 0)) wordAt(it, 1) / 128.0 else NOT_USED
                },
                PidChannel(1, "Exhaust pressure, bank 2", KPA) {
                    if (fitted(it, 1)) wordAt(it, 3) / 128.0 else NOT_USED
                },
            )),
        )
        add(
            Pid(0x74, "Turbocharger A speed", RPM, 5, PidTier.Slow, listOf(
                PidChannel(0, "Turbocharger A speed", RPM) {
                    if (fitted(it, 0)) wordAt(it, 1) else NOT_USED
                },
                PidChannel(1, "Turbocharger B speed", RPM) {
                    if (fitted(it, 1)) wordAt(it, 3) else NOT_USED
                },
            )),
        )
        add(
            Pid(0x77, "Charge air cooler temperature, bank 1 sensor 1", CELSIUS, 5, PidTier.Slow, listOf(
                PidChannel(0, "Charge air cooler temperature, bank 1 sensor 1", CELSIUS) {
                    if (fitted(it, 0)) tempCAt(it, 1) else NOT_USED
                },
                PidChannel(1, "Charge air cooler temperature, bank 1 sensor 2", CELSIUS) {
                    if (fitted(it, 1)) tempCAt(it, 2) else NOT_USED
                },
                PidChannel(2, "Charge air cooler temperature, bank 2 sensor 1", CELSIUS) {
                    if (fitted(it, 2)) tempCAt(it, 3) else NOT_USED
                },
                PidChannel(3, "Charge air cooler temperature, bank 2 sensor 2", CELSIUS) {
                    if (fitted(it, 3)) tempCAt(it, 4) else NOT_USED
                },
            )),
        )
        add(exhaustGasTemperature(0x78, bank = 1))
        add(exhaustGasTemperature(0x79, bank = 2))
        add(pid(FRICTION_TORQUE, "Engine friction torque", PERCENT, 1, PidTier.Slow) {
            it[0] - TORQUE_OFFSET
        })
        add(fuelRateMass())
        add(pid(0x9E, "Engine exhaust flow rate", "kg/h", 2, PidTier.Slow) { word(it) / 5.0 })
        add(
            Pid(TRANSMISSION_GEAR, "Transmission actual gear", "", 4, PidTier.Slow, listOf(
                PidChannel(0, "Transmission actual gear", "") {
                    if (fitted(it, 0)) wordAt(it, 2) / 1_000.0 else NOT_USED
                },
            )),
        )
        add(pid(0xA6, "Odometer", "km", 4, PidTier.Slow) {
            ((it[0].toLong() shl 24) or (it[1].toLong() shl 16) or
                (it[2].toLong() shl 8) or it[3].toLong()) / 10.0
        })
    }

    /**
     * `0178` / `0179`: four exhaust gas temperature sensors behind one bitmask, which is
     * what a diesel owner watching a regeneration is actually looking at.
     */
    private fun exhaustGasTemperature(id: Int, bank: Int) = Pid(
        id = id,
        name = "Exhaust gas temperature, bank $bank sensor 1",
        unit = CELSIUS,
        bytes = 9,
        tier = PidTier.Slow,
        channels = (0 until EGT_SENSORS).map { sensor ->
            PidChannel(sensor, "Exhaust gas temperature, bank $bank sensor ${sensor + 1}", CELSIUS) {
                if (fitted(it, sensor)) wordAt(it, 1 + sensor * 2) / 10.0 - 40.0 else NOT_USED
            }
        },
    )

    private const val EGT_SENSORS = 4

    /**
     * Whether the sensor a given oxygen-sensor PID reports on is physically fitted,
     * according to the bitmask `0113` answered with.
     *
     * `0113` lists eight sensor positions — bank 1 sensors 1-4 in bits 0-3, bank 2 sensors
     * 1-4 in bits 4-7 — and the three families of oxygen-sensor PIDs are laid out in the
     * same order, so bit *n* governs `0114 + n`, `0124 + n` and `0134 + n` alike. Returns
     * null for a PID that is not an oxygen sensor, which is the caller's cue to leave it
     * alone: this bitmask says nothing about anything else.
     *
     * It matters because the support blocks routinely over-report. A four-cylinder car
     * with two probes commonly lists all eight `0114`-`011B` as supported and then answers
     * `NO DATA` to six of them, which is six timeouts per slow-tier sweep spent learning
     * something the car already said in one byte.
     */
    fun o2SensorFitted(mask: Int, pid: Int): Boolean? {
        val sensor = when (pid) {
            in NARROW_BAND_O2 -> pid - NARROW_BAND_O2.first
            in WIDE_RANGE_O2_VOLTAGE -> pid - WIDE_RANGE_O2_VOLTAGE.first
            in WIDE_RANGE_O2_CURRENT -> pid - WIDE_RANGE_O2_CURRENT.first
            else -> return null
        }
        return (mask shr sensor) and 1 == 1
    }

    private val byId = entries.associateBy(Pid::id)

    operator fun get(id: Int): Pid? = byId[id]

    fun tier(tier: PidTier): List<Pid> = entries.filter { it.tier == tier }

    fun byteCountOf(id: Int): Int? = byId[id]?.bytes

    /** Every channel of every known PID, as the reading keys they publish under. */
    val sensorKeys: List<Int> = entries.flatMap { pid ->
        pid.channels.map { sensorKey(pid.id, it.index) }
    }

    /** The channel a reading key refers to, or null when nothing decodes that key. */
    fun channelOf(key: Int): PidChannel? =
        byId[keyPid(key)]?.channels?.firstOrNull { it.index == keyChannel(key) }
}

/**
 * Values the dashboard shows that are not PIDs of their own but arithmetic on top of
 * ones that are.
 */
data class DerivedMetric(val key: String, val name: String, val unit: String)


object DerivedMetrics {

    val Boost = DerivedMetric("boost", "Boost pressure", "kPa")
    val FuelRate = DerivedMetric("fuel_rate", "Fuel rate", "L/h")
    val FuelPer100Km = DerivedMetric("fuel_per_100km", "Fuel consumption", "L/100km")

    val all = listOf(Boost, FuelRate, FuelPer100Km)

    /** Barometric pressure at sea level, used when PID 33 is unsupported. */
    const val SEA_LEVEL_KPA = 101.3

    private const val SECONDS_PER_HOUR = 3600.0

    /**
     * Below this a λ reading is a placeholder, not a mixture.
     *
     * An ECU that does not really implement PID 44 still answers it, usually with zero, and
     * dividing by that produces an infinite fuel rate. The upper end needs no guard: the
     * PID's own encoding saturates at 2.0, which is also why the fuel rate of a diesel at
     * idle — genuinely leaner than that — is still overstated, just no longer by a factor
     * of five.
     */
    private const val MIN_CREDIBLE_LAMBDA = 0.5

    /**
     * The derived values as plain numbers, for callers that only need the arithmetic.
     *
     * @param fuel what is being burnt, which decides how much of it a given mass of air is.
     */
    fun compute(
        values: Map<Int, Double>,
        fuel: FuelType = FuelType.Default,
    ): Map<String, Double> = computeAll(values, fuel).mapValues { it.value.value }

    /**
     * The derived values with what each one is worth: whether every input came off the bus,
     * which constant had to be supplied when one did not, and how old the oldest input is.
     *
     * @param fuel what is being burnt; null when the driver has not said, which is not the
     * same as petrol even though petrol is what the maths then runs on.
     * @param timestamps when each reading key was last refreshed, so a derived value can be
     * dimmed on the age of what it was computed from rather than on the moment it was
     * computed.
     */
    fun computeAll(
        values: Map<Int, Double>,
        fuel: FuelType? = null,
        timestamps: Map<Int, Long> = emptyMap(),
    ): Map<String, DerivedValue> {
        val burnt = fuel ?: FuelType.Default
        val derived = mutableMapOf<String, DerivedValue>()

        fun age(sources: List<Int>): Long =
            sources.mapNotNull(timestamps::get).minOrNull() ?: 0L

        values[Pids.INTAKE_MAP]?.let { map ->
            // Boost is manifold pressure above the air outside, and on a car that does not
            // report PID 33 the app has no idea what the air outside is doing. Sea level is
            // the least wrong constant available and it is wrong by about a kilopascal per
            // hundred metres of altitude, so the value is published as assumed rather than
            // quietly offered as a measurement of the turbo.
            val ambient = values[Pids.BAROMETRIC_PRESSURE]
            val sources = listOfNotNull(Pids.INTAKE_MAP, ambient?.let { Pids.BAROMETRIC_PRESSURE })
            derived[Boost.key] = DerivedValue(
                value = map - (ambient ?: SEA_LEVEL_KPA),
                provenance = if (ambient != null) Provenance.Derived else Provenance.Assumed,
                assumption = if (ambient != null) null else Assumption.SeaLevelPressure,
                timestampMillis = age(sources),
            )
        }

        // PID 5E when the car has it, and the air flow converted when it does not — which
        // is most cars. The conversion is air mass ÷ (air per litre × λ): the ratio alone
        // would assume every engine burns everything it breathes, which is true of a petrol
        // engine under closed loop and of nothing else.
        //
        // λ is taken from a wide-range sensor before the commanded ratio, because the
        // commanded value is what the ECU asked for and the sensor is what actually
        // happened. It also has range the commanded PID does not: 0144 saturates at 2.0,
        // so a diesel at idle reads as λ=2 there and as its real λ>5 here.
        val reported = values[Pids.FUEL_RATE]
        val rate: DerivedValue? = when {
            reported != null -> DerivedValue(
                value = reported,
                // The ECU's own figure, passed through untouched: a measurement, not a guess.
                provenance = Provenance.Measured,
                timestampMillis = age(listOf(Pids.FUEL_RATE)),
            )

            else -> values[Pids.MAF_RATE]?.let { maf ->
                val lambdaKey = lambdaSource(values)
                val lambda = lambdaKey?.let(values::getValue) ?: burnt.nominalLambda
                DerivedValue(
                    value = maf * SECONDS_PER_HOUR / (burnt.airMassPerLitre * lambda),
                    // Two separate ways this can stop being a straight computation, and the
                    // fuel type is the one that costs the most: an unfilled profile runs a
                    // diesel's air flow through petrol's chemistry and overstates it by
                    // about four fifths.
                    provenance = if (fuel == null || lambdaKey == null) {
                        Provenance.Assumed
                    } else {
                        Provenance.Derived
                    },
                    assumption = when {
                        fuel == null -> Assumption.FuelTypeUnset
                        lambdaKey == null -> Assumption.NominalLambda
                        else -> null
                    },
                    timestampMillis = age(listOfNotNull(Pids.MAF_RATE, lambdaKey)),
                )
            }
        }

        if (rate != null) {
            derived[FuelRate.key] = rate
            val speed = values[Pids.VEHICLE_SPEED]
            if (speed != null && speed > 0.0) {
                derived[FuelPer100Km.key] = rate.copy(
                    value = rate.value * 100.0 / speed,
                    // Dividing a measured fuel rate by a measured speed is still arithmetic
                    // the app did rather than a number the car reported.
                    provenance = if (rate.provenance == Provenance.Assumed) {
                        Provenance.Assumed
                    } else {
                        Provenance.Derived
                    },
                    timestampMillis = minOf(
                        rate.timestampMillis,
                        timestamps[Pids.VEHICLE_SPEED] ?: rate.timestampMillis,
                    ),
                )
            }
        }

        return derived
    }


    /**
     * Which reading the mixture should be taken from, or null when the car reports none.
     *
     * A wide-range sensor first: sensor 1 of each set is the pre-catalyst probe, which is
     * the one that describes what the engine burned, while the post-catalyst sensors are
     * reading exhaust that has already been through the converter. The commanded ratio is
     * the fallback, and it is only believed above [MIN_CREDIBLE_LAMBDA] — an ECU that does
     * not really implement PID 44 still answers it, usually with zero.
     *
     * Returns the key rather than the value so the caller can tell a mixture the car
     * reported from the nominal one it had to fall back on, and can date the result by it.
     */
    private fun lambdaSource(values: Map<Int, Double>): Int? = when {
        values[WIDE_RANGE_LAMBDA]?.let { it > 0.0 } == true -> WIDE_RANGE_LAMBDA
        values[WIDE_RANGE_LAMBDA_CURRENT]?.let { it > 0.0 } == true -> WIDE_RANGE_LAMBDA_CURRENT
        values[Pids.COMMANDED_EQUIV_RATIO]?.let { it >= MIN_CREDIBLE_LAMBDA } == true ->
            Pids.COMMANDED_EQUIV_RATIO

        else -> null
    }

    /** `0124`/`0134`: the pre-catalyst wide-range probe, in the two encodings it comes in. */
    private const val WIDE_RANGE_LAMBDA = 0x24
    private const val WIDE_RANGE_LAMBDA_CURRENT = 0x34

}
