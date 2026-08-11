package com.miskibin.obd2dashboard.obd

/** How often a PID is worth asking for; see [PidScheduler]. */
enum class PidTier { Fast, Medium, Slow }

/**
 * A SAE J1979 Mode 01 parameter.
 *
 * [decode] receives exactly [bytes] data bytes (A, B, C, D…) as unsigned ints.
 */
data class Pid(
    val id: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val tier: PidTier,
    val decode: (IntArray) -> Double,
) {
    /** The hex request for this PID, e.g. `010C`. */
    val command: String get() = "%02X%02X".format(MODE_CURRENT_DATA, id)
}

const val MODE_CURRENT_DATA = 0x01
const val MODE_STORED_DTC = 0x03
const val MODE_CLEAR_DTC = 0x04
const val MODE_PENDING_DTC = 0x07
const val MODE_VEHICLE_INFO = 0x09
const val MODE_PERMANENT_DTC = 0x0A

private const val PERCENT = "%"
private const val CELSIUS = "°C"
private const val DEGREES = "°"
private const val KPA = "kPa"

private fun word(data: IntArray) = data[0] * 256.0 + data[1]

private fun signedWord(data: IntArray): Double {
    val raw = data[0] * 256 + data[1]
    return if (raw > 0x7FFF) (raw - 0x10000).toDouble() else raw.toDouble()
}

private fun ratio(data: IntArray) = data[0] * 100.0 / 255.0

private fun trim(data: IntArray) = data[0] * 100.0 / 128.0 - 100.0

private fun tempC(data: IntArray) = data[0] - 40.0

/** The Mode 01 PIDs this app knows how to decode, keyed by PID id. */
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
    const val CONTROL_MODULE_VOLTAGE = 0x42
    const val AMBIENT_AIR_TEMP = 0x46
    const val OIL_TEMP = 0x5C
    const val FUEL_RATE = 0x5E

    val entries: List<Pid> = listOf(
        Pid(ENGINE_LOAD, "Calculated engine load", PERCENT, 1, PidTier.Medium, ::ratio),
        Pid(COOLANT_TEMP, "Engine coolant temperature", CELSIUS, 1, PidTier.Medium, ::tempC),
        Pid(SHORT_FUEL_TRIM_1, "Short term fuel trim, bank 1", PERCENT, 1, PidTier.Medium, ::trim),
        Pid(LONG_FUEL_TRIM_1, "Long term fuel trim, bank 1", PERCENT, 1, PidTier.Medium, ::trim),
        Pid(0x08, "Short term fuel trim, bank 2", PERCENT, 1, PidTier.Slow, ::trim),
        Pid(0x09, "Long term fuel trim, bank 2", PERCENT, 1, PidTier.Slow, ::trim),
        Pid(0x0A, "Fuel pressure", KPA, 1, PidTier.Slow) { it[0] * 3.0 },
        Pid(INTAKE_MAP, "Intake manifold absolute pressure", KPA, 1, PidTier.Fast) { it[0].toDouble() },
        Pid(ENGINE_RPM, "Engine RPM", "rpm", 2, PidTier.Fast) { word(it) / 4.0 },
        Pid(VEHICLE_SPEED, "Vehicle speed", "km/h", 1, PidTier.Fast) { it[0].toDouble() },
        Pid(TIMING_ADVANCE, "Timing advance", DEGREES, 1, PidTier.Medium) { it[0] / 2.0 - 64.0 },
        Pid(INTAKE_AIR_TEMP, "Intake air temperature", CELSIUS, 1, PidTier.Medium, ::tempC),
        Pid(MAF_RATE, "MAF air flow rate", "g/s", 2, PidTier.Fast) { word(it) / 100.0 },
        Pid(THROTTLE_POSITION, "Throttle position", PERCENT, 1, PidTier.Fast, ::ratio),
        Pid(RUN_TIME, "Run time since engine start", "s", 2, PidTier.Slow, ::word),
        Pid(DISTANCE_WITH_MIL, "Distance travelled with MIL on", "km", 2, PidTier.Slow, ::word),
        Pid(0x22, "Fuel rail pressure", KPA, 2, PidTier.Slow) { 0.079 * word(it) },
        Pid(0x23, "Fuel rail gauge pressure", KPA, 2, PidTier.Slow) { 10.0 * word(it) },
        Pid(0x2C, "Commanded EGR", PERCENT, 1, PidTier.Slow, ::ratio),
        Pid(0x2E, "Commanded evaporative purge", PERCENT, 1, PidTier.Slow, ::ratio),
        Pid(FUEL_LEVEL, "Fuel tank level input", PERCENT, 1, PidTier.Slow, ::ratio),
        Pid(0x30, "Warm-ups since codes cleared", "count", 1, PidTier.Slow) { it[0].toDouble() },
        Pid(DISTANCE_SINCE_CLEARED, "Distance since codes cleared", "km", 2, PidTier.Slow, ::word),
        Pid(0x32, "Evap system vapour pressure", "Pa", 2, PidTier.Slow) { signedWord(it) / 4.0 },
        Pid(BAROMETRIC_PRESSURE, "Absolute barometric pressure", KPA, 1, PidTier.Medium) { it[0].toDouble() },
        Pid(0x3C, "Catalyst temperature B1S1", CELSIUS, 2, PidTier.Slow) { word(it) / 10.0 - 40.0 },
        Pid(0x3D, "Catalyst temperature B2S1", CELSIUS, 2, PidTier.Slow) { word(it) / 10.0 - 40.0 },
        Pid(0x3E, "Catalyst temperature B1S2", CELSIUS, 2, PidTier.Slow) { word(it) / 10.0 - 40.0 },
        Pid(0x3F, "Catalyst temperature B2S2", CELSIUS, 2, PidTier.Slow) { word(it) / 10.0 - 40.0 },
        Pid(CONTROL_MODULE_VOLTAGE, "Control module voltage", "V", 2, PidTier.Medium) { word(it) / 1000.0 },
        Pid(0x43, "Absolute load value", PERCENT, 2, PidTier.Slow) { word(it) * 100.0 / 255.0 },
        Pid(0x44, "Commanded air-fuel equivalence ratio", "λ", 2, PidTier.Slow) { 2.0 * word(it) / 65536.0 },
        Pid(0x45, "Relative throttle position", PERCENT, 1, PidTier.Medium, ::ratio),
        Pid(AMBIENT_AIR_TEMP, "Ambient air temperature", CELSIUS, 1, PidTier.Slow, ::tempC),
        Pid(0x47, "Absolute throttle position B", PERCENT, 1, PidTier.Slow, ::ratio),
        Pid(0x49, "Accelerator pedal position D", PERCENT, 1, PidTier.Slow, ::ratio),
        Pid(0x4A, "Accelerator pedal position E", PERCENT, 1, PidTier.Slow, ::ratio),
        Pid(0x4C, "Commanded throttle actuator", PERCENT, 1, PidTier.Slow, ::ratio),
        Pid(0x4D, "Time run with MIL on", "min", 2, PidTier.Slow, ::word),
        Pid(0x4E, "Time since trouble codes cleared", "min", 2, PidTier.Slow, ::word),
        Pid(0x52, "Ethanol fuel percentage", PERCENT, 1, PidTier.Slow, ::ratio),
        Pid(0x53, "Absolute evap system vapour pressure", KPA, 2, PidTier.Slow) { word(it) / 200.0 },
        Pid(0x59, "Fuel rail absolute pressure", KPA, 2, PidTier.Slow) { 10.0 * word(it) },
        Pid(0x5A, "Relative accelerator pedal position", PERCENT, 1, PidTier.Slow, ::ratio),
        Pid(0x5B, "Hybrid battery pack remaining life", PERCENT, 1, PidTier.Slow, ::ratio),
        Pid(OIL_TEMP, "Engine oil temperature", CELSIUS, 1, PidTier.Slow, ::tempC),
        Pid(0x5D, "Fuel injection timing", DEGREES, 2, PidTier.Slow) { word(it) / 128.0 - 210.0 },
        Pid(FUEL_RATE, "Engine fuel rate", "L/h", 2, PidTier.Medium) { word(it) / 20.0 },
        Pid(0x61, "Driver's demand engine torque", PERCENT, 1, PidTier.Slow) { it[0] - 125.0 },
        Pid(0x62, "Actual engine torque", PERCENT, 1, PidTier.Slow) { it[0] - 125.0 },
        Pid(0x63, "Engine reference torque", "N·m", 2, PidTier.Slow, ::word),
        Pid(0xA6, "Odometer", "km", 4, PidTier.Slow) {
            ((it[0].toLong() shl 24) or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()) / 10.0
        },
    )

    private val byId = entries.associateBy(Pid::id)

    operator fun get(id: Int): Pid? = byId[id]

    fun tier(tier: PidTier): List<Pid> = entries.filter { it.tier == tier }

    fun byteCountOf(id: Int): Int? = byId[id]?.bytes
}

/**
 * Values the dashboard shows that are not PIDs of their own but arithmetic on top of
 * ones that are.
 */
data class DerivedMetric(val key: String, val name: String, val unit: String)

object DerivedMetrics {

    val Boost = DerivedMetric("boost", "Boost pressure", KPA)
    val FuelRate = DerivedMetric("fuel_rate", "Fuel rate", "L/h")
    val FuelPer100Km = DerivedMetric("fuel_per_100km", "Fuel consumption", "L/100km")

    val all = listOf(Boost, FuelRate, FuelPer100Km)

    /** Barometric pressure at sea level, used when PID 33 is unsupported. */
    const val SEA_LEVEL_KPA = 101.3

    private const val STOICHIOMETRIC_AFR = 14.7
    private const val PETROL_DENSITY_G_PER_L = 820.0

    fun compute(values: Map<Int, Double>): Map<String, Double> {
        val derived = mutableMapOf<String, Double>()

        values[Pids.INTAKE_MAP]?.let { map ->
            derived[Boost.key] = map - (values[Pids.BAROMETRIC_PRESSURE] ?: SEA_LEVEL_KPA)
        }

        val fuelRate = values[Pids.FUEL_RATE]
            ?: values[Pids.MAF_RATE]?.let { it * 3600.0 / (STOICHIOMETRIC_AFR * PETROL_DENSITY_G_PER_L) }
        if (fuelRate != null) {
            derived[FuelRate.key] = fuelRate
            val speed = values[Pids.VEHICLE_SPEED]
            if (speed != null && speed > 0.0) derived[FuelPer100Km.key] = fuelRate * 100.0 / speed
        }

        return derived
    }
}
