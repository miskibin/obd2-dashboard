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
    const val RUN_TIME = 0x1F
    const val DISTANCE_WITH_MIL = 0x21
    const val FUEL_LEVEL = 0x2F
    const val DISTANCE_SINCE_CLEARED = 0x31
    const val BAROMETRIC_PRESSURE = 0x33
    const val COMMANDED_AFR = 0x44
    const val CONTROL_MODULE_VOLTAGE = 0x42
    const val AMBIENT_AIR_TEMP = 0x46
    const val FUEL_TYPE = 0x51
    const val OIL_TEMP = 0x5C
    const val FUEL_RATE = 0x5E
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

    val entries: List<Pid> = buildList {
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
        add(pid(COMMANDED_AFR, "Commanded air-fuel equivalence ratio", LAMBDA, 2, PidTier.Medium) {
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

/**
 * What the engine is burning, which is what turns an air flow into a fuel flow.
 *
 * [stoichiometricAfr] and [densityGramsPerLitre] are the two constants the mass-airflow
 * estimate needs, and they are not close to interchangeable: running a diesel's air flow
 * through petrol's numbers is how a 6 L/100km car reads as 20.
 */
private data class FuelProfile(
    val stoichiometricAfr: Double,
    val densityGramsPerLitre: Double,
    /**
     * Whether an unmeasured mixture may be assumed stoichiometric. True for spark
     * ignition, which spends almost all of its life in closed loop at λ≈1, and false for
     * compression ignition, which is always lean by a factor the app cannot guess.
     */
    val assumeClosedLoop: Boolean,
)

object DerivedMetrics {

    val Boost = DerivedMetric("boost", "Boost pressure", "kPa")
    val FuelRate = DerivedMetric("fuel_rate", "Fuel rate", "L/h")
    val FuelPer100Km = DerivedMetric("fuel_per_100km", "Fuel consumption", "L/100km")

    val all = listOf(Boost, FuelRate, FuelPer100Km)

    /** Barometric pressure at sea level, used when PID 33 is unsupported. */
    const val SEA_LEVEL_KPA = 101.3

    /**
     * Fuel densities and stoichiometric ratios by PID `0151` code.
     *
     * Petrol is 745 g/L at 15 °C per EN 228, not the 820 g/L this used to use — that is
     * diesel's density, and charging it to petrol understated every estimated
     * consumption figure by about a tenth.
     */
    private val PETROL = FuelProfile(14.7, 745.0, assumeClosedLoop = true)
    private val DIESEL = FuelProfile(14.5, 832.0, assumeClosedLoop = false)
    private val ETHANOL = FuelProfile(9.8, 785.0, assumeClosedLoop = true)
    private val METHANOL = FuelProfile(6.4, 792.0, assumeClosedLoop = true)
    private val LPG = FuelProfile(15.6, 540.0, assumeClosedLoop = true)

    private val fuelProfiles: Map<Int, FuelProfile> = buildMap {
        listOf(1, 9, 17).forEach { put(it, PETROL) }
        listOf(2, 10).forEach { put(it, METHANOL) }
        listOf(3, 11, 18).forEach { put(it, ETHANOL) }
        listOf(4, 19, 23).forEach { put(it, DIESEL) }
        listOf(5, 7, 12, 14).forEach { put(it, LPG) }
    }

    private const val SECONDS_PER_HOUR = 3_600.0

    fun compute(values: Map<Int, Double>): Map<String, Double> {
        val derived = mutableMapOf<String, Double>()

        values[Pids.INTAKE_MAP]?.let { map ->
            derived[Boost.key] = map - (values[Pids.BAROMETRIC_PRESSURE] ?: SEA_LEVEL_KPA)
        }

        val fuelRate = values[Pids.FUEL_RATE] ?: estimateFuelRate(values)
        if (fuelRate != null) {
            derived[FuelRate.key] = fuelRate
            val speed = values[Pids.VEHICLE_SPEED]
            if (speed != null && speed > 0.0) derived[FuelPer100Km.key] = fuelRate * 100.0 / speed
        }

        return derived
    }

    /**
     * Litres per hour worked back from the air the engine is breathing.
     *
     * Air flow only becomes fuel flow once the mixture is known, so the mixture is read
     * rather than assumed wherever the car reports it: a wide-range sensor first, the
     * commanded equivalence ratio second. Only a spark-ignition engine is allowed to fall
     * back on λ=1, and a compression-ignition engine that reports no mixture at all gets
     * no estimate — a blank is honest, and the number that used to appear there was not.
     */
    private fun estimateFuelRate(values: Map<Int, Double>): Double? {
        val maf = values[Pids.MAF_RATE] ?: return null
        val fuel = fuelProfileOf(values) ?: return null
        val lambda = measuredLambda(values)
            ?: values[Pids.COMMANDED_AFR]
            ?: if (fuel.assumeClosedLoop) 1.0 else return null
        if (lambda <= 0.0) return null

        val fuelGramsPerSecond = maf / (lambda * fuel.stoichiometricAfr)
        return fuelGramsPerSecond * SECONDS_PER_HOUR / fuel.densityGramsPerLitre
    }

    /**
     * Lambda as a wide-range sensor actually measured it.
     *
     * Sensor 1 of each set is the pre-catalyst probe, which is the one that describes what
     * the engine burned; the post-catalyst sensors are reading exhaust that has already
     * been through the converter.
     */
    private fun measuredLambda(values: Map<Int, Double>): Double? =
        (values[0x24] ?: values[0x34])?.takeIf { it > 0.0 }

    /** Petrol when the car does not say, which is what all but the diesels are. */
    private fun fuelProfileOf(values: Map<Int, Double>): FuelProfile? {
        val code = values[Pids.FUEL_TYPE]?.toInt() ?: return PETROL
        // An electric or otherwise non-liquid drivetrain has no litres per hour to report.
        return fuelProfiles[code]
    }
}
