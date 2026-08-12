package com.miskibin.obd2dashboard.data

import androidx.annotation.StringRes
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.obd.DerivedMetrics
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.obd.keyPid
import com.miskibin.obd2dashboard.obd.sensorKey
import java.util.Locale

/**
 * Stable identity of anything the UI can put on a tile or a chart.
 *
 * [storageKey] is what ends up in DataStore and in CSV headers, so it must never change
 * for a given metric once shipped.
 */
sealed interface MetricId {

    val storageKey: String

    /**
     * A Mode 01 PID read straight off the bus.
     *
     * [key] is a [sensorKey]: the PID id itself for the single-value parameters, and the
     * PID plus a channel index for the ones that carry several numbers in one response.
     * Channel 0 encodes to the bare PID id, so every key that was ever written to a saved
     * tile list or a CSV header still parses to the same metric.
     */
    data class Sensor(val key: Int) : MetricId {
        /** The Mode 01 PID this reading is asked for with. */
        val pid: Int get() = keyPid(key)

        override val storageKey: String
            get() = PID_PREFIX + if (key <= 0xFF) {
                "%02X".format(Locale.ROOT, key)
            } else {
                "%04X".format(Locale.ROOT, key)
            }
    }

    /** A value computed from other PIDs, keyed by [com.miskibin.obd2dashboard.obd.DerivedMetric.key]. */
    data class Derived(val key: String) : MetricId {
        override val storageKey: String get() = DERIVED_PREFIX + key
    }

    /** Adapter-reported battery voltage (`ATRV`), which is not a PID. */
    data object Battery : MetricId {
        override val storageKey: String get() = BATTERY_KEY
    }

    companion object {
        private const val PID_PREFIX = "pid:"
        private const val DERIVED_PREFIX = "derived:"
        private const val BATTERY_KEY = "battery"

        fun parse(raw: String): MetricId? = when {
            raw == BATTERY_KEY -> Battery
            raw.startsWith(PID_PREFIX) ->
                raw.removePrefix(PID_PREFIX).toIntOrNull(radix = 16)?.let(::Sensor)

            raw.startsWith(DERIVED_PREFIX) ->
                raw.removePrefix(DERIVED_PREFIX).takeIf(String::isNotEmpty)?.let(::Derived)

            else -> null
        }
    }
}

/**
 * Presentation metadata for a [MetricId]: the localised label, the unit as shown next to
 * the value, and how many decimals reading it at a glance deserves.
 */
data class Metric(
    val id: MetricId,
    @param:StringRes val nameRes: Int,
    val unit: String,
    val decimals: Int,
    /**
     * Numbers substituted into [nameRes], for the parameters that come in families.
     *
     * A car can report eight oxygen sensors, six intake air temperature sensors and four
     * exhaust gas temperature sensors per bank; naming each one with its own string would
     * be a hundred resources that differ by a digit, in every language the app ships.
     */
    val nameArgs: List<Int> = emptyList(),
) {
    /** English, locale-independent name used for CSV headers. */
    val exportKey: String get() = id.storageKey
}

/**
 * The range a value sits in when nothing is wrong.
 *
 * Either end may be absent: oil temperature has a ceiling and no floor worth stating,
 * fuel trim has both, and most PIDs have neither.
 */
data class NormalBand(val min: Double?, val max: Double?) {
    val isEmpty: Boolean get() = min == null && max == null
}

/**
 * How the parameter picker files a metric.
 *
 * The catalogue is forty-odd PIDs long, which is a list nobody reads; grouped by what
 * part of the car the value comes from, it is four short lists that somebody diagnosing a
 * lean mixture can go straight to.
 */
enum class MetricGroup { Engine, Temperature, Mixture, Vehicle }

object Metrics {

    /**
     * The reading keys that carry a narrow-band or wide-range oxygen sensor voltage.
     *
     * Declared before [catalog], which reads it while it is being built — a property of an
     * `object` that is initialised later is still null at that point.
     */
    private val O2_VOLTAGE_KEYS: Set<Int> =
        (0x14..0x1B).map { sensorKey(it, 0) }.toSet() +
            (0x24..0x2B).map { sensorKey(it, 1) }.toSet()

    val Rpm = MetricId.Sensor(Pids.ENGINE_RPM)
    val Speed = MetricId.Sensor(Pids.VEHICLE_SPEED)
    val CoolantTemp = MetricId.Sensor(Pids.COOLANT_TEMP)
    val OilTemp = MetricId.Sensor(Pids.OIL_TEMP)
    val IntakeAirTemp = MetricId.Sensor(Pids.INTAKE_AIR_TEMP)
    val EngineLoad = MetricId.Sensor(Pids.ENGINE_LOAD)
    val Throttle = MetricId.Sensor(Pids.THROTTLE_POSITION)
    val Timing = MetricId.Sensor(Pids.TIMING_ADVANCE)
    val Maf = MetricId.Sensor(Pids.MAF_RATE)
    val ShortTrim = MetricId.Sensor(Pids.SHORT_FUEL_TRIM_1)
    val LongTrim = MetricId.Sensor(Pids.LONG_FUEL_TRIM_1)
    val Boost = MetricId.Derived(DerivedMetrics.Boost.key)
    val FuelPer100Km = MetricId.Derived(DerivedMetrics.FuelPer100Km.key)
    val Battery = MetricId.Battery

    /** What a fresh install shows before the driver picks their own tiles. */
    val defaultTiles: List<MetricId> =
        listOf(Rpm, Speed, CoolantTemp, Boost, Battery, IntakeAirTemp)

    val catalog: List<Metric> = buildList {
        Pids.entries.forEach { pid ->
            pid.channels.forEach { channel ->
                val key = sensorKey(pid.id, channel.index)
                val (nameRes, args) = nameOf(pid.id, channel.index)
                add(
                    Metric(
                        id = MetricId.Sensor(key),
                        nameRes = nameRes,
                        unit = channel.unit,
                        decimals = decimalsFor(key, channel.unit),
                        nameArgs = args,
                    ),
                )
            }
        }
        add(Metric(MetricId.Derived(DerivedMetrics.Boost.key), R.string.metric_boost, "kPa", 0))
        add(Metric(MetricId.Derived(DerivedMetrics.FuelRate.key), R.string.metric_fuel_rate, "L/h", 1))
        add(
            Metric(
                MetricId.Derived(DerivedMetrics.FuelPer100Km.key),
                R.string.metric_fuel_per_100km,
                "L/100km",
                1,
            ),
        )
        add(Metric(MetricId.Battery, R.string.metric_battery, "V", 1))
    }

    private val byId: Map<MetricId, Metric> = catalog.associateBy(Metric::id)

    operator fun get(id: MetricId): Metric? = byId[id]

    /**
     * What "normal" looks like, for the handful of values where an owner can be told.
     *
     * A number on its own only means something to somebody who already knows the engine —
     * 104 °C is either fine or the start of a bad afternoon depending on what it is. These
     * bands are what turn the row into an answer, and they are deliberately sparse: a band
     * is only shipped where one honestly exists across engines. Everything else shows the
     * reading and nothing more.
     */
    val normalBands: Map<MetricId, NormalBand> = mapOf(
        CoolantTemp to NormalBand(82.0, 98.0),
        OilTemp to NormalBand(80.0, 110.0),
        Battery to NormalBand(13.8, 14.4),
        MetricId.Sensor(Pids.SHORT_FUEL_TRIM_1) to NormalBand(-10.0, 10.0),
        MetricId.Sensor(Pids.LONG_FUEL_TRIM_1) to NormalBand(-10.0, 10.0),
    )

    /**
     * The band for [id], with the driver's own alert threshold substituted in.
     *
     * Where a rule exists it wins: the oil row should say "below 110 °C" when that is
     * where the driver set the warning, not where the default happened to sit.
     */
    fun bandFor(id: MetricId, rules: List<AlertRule>): NormalBand? {
        val base = normalBands[id]
        val rule = rules.firstOrNull { it.metric == id && it.enabled } ?: return base
        return when (rule.comparison) {
            AlertComparison.Above -> NormalBand(base?.min, rule.threshold)
            AlertComparison.Below -> NormalBand(rule.threshold, base?.max)
        }
    }

    fun byStorageKey(key: String): Metric? = MetricId.parse(key)?.let(::get)

    /**
     * The engine-speed bar reads against a redline the driver sets, because 8 000 is a
     * hot hatch and 4 500 is a diesel — the same bar drawn to a fixed scale would be
     * either useless or alarming on half the cars this app runs on.
     */
    const val REDLINE_DEFAULT = 8_000

    const val REDLINE_MIN = 5_000

    const val REDLINE_MAX = 9_000

    const val REDLINE_STEP = 250

    /**
     * Which of the four lists a metric belongs in.
     *
     * Grouped by where the number comes from rather than by PID order, because somebody
     * chasing a lean mixture wants the trims, the airflow and lambda next to each other —
     * they are consecutive in a diagnosis and scattered in the standard.
     */
    fun groupOf(id: MetricId): MetricGroup = when (id) {
        is MetricId.Battery -> MetricGroup.Vehicle
        is MetricId.Derived -> when (id.key) {
            DerivedMetrics.Boost.key -> MetricGroup.Engine
            else -> MetricGroup.Vehicle
        }

        is MetricId.Sensor -> when (id.pid) {
            Pids.COOLANT_TEMP, Pids.INTAKE_AIR_TEMP, Pids.OIL_TEMP, Pids.AMBIENT_AIR_TEMP,
            in 0x3C..0x3F, 0x67, 0x68, 0x6B, 0x77, 0x78, 0x79,
            -> MetricGroup.Temperature

            Pids.SHORT_FUEL_TRIM_1, Pids.LONG_FUEL_TRIM_1, 0x08, 0x09, Pids.MAF_RATE,
            in 0x14..0x1B, in 0x24..0x2B, in 0x34..0x3B, in 0x55..0x58,
            Pids.COMMANDED_AFR, 0x2C, 0x2D, 0x2E, 0x52, 0x53, 0x54, 0x66,
            -> MetricGroup.Mixture

            Pids.VEHICLE_SPEED, Pids.FUEL_LEVEL, Pids.RUN_TIME, Pids.DISTANCE_WITH_MIL,
            Pids.DISTANCE_SINCE_CLEARED, Pids.CONTROL_MODULE_VOLTAGE, 0xA6, 0x4D, 0x4E, 0x49,
            0x4A, 0x4B, 0x4C, Pids.FUEL_TYPE, Pids.TRANSMISSION_GEAR,
            -> MetricGroup.Vehicle

            else -> MetricGroup.Engine
        }
    }

    /**
     * How many decimals reading a value at a glance deserves.
     *
     * Almost always a property of the unit, with one exception worth the special case: an
     * oxygen sensor's voltage lives between 0 and 1.275 V and swings across that whole
     * range, so a single decimal would show a probe doing its job as a flat 0.1 → 0.9.
     */
    private fun decimalsFor(key: Int, unit: String): Int = when {
        key in O2_VOLTAGE_KEYS -> 3
        // A gear ratio is unitless but not an integer: rounded to none, an overdrive
        // ratio of 0.72 reads as 1 and a first gear of 3.55 reads as 4.
        keyPid(key) == Pids.TRANSMISSION_GEAR -> 2
        else -> when (unit) {
            "rpm", "km/h", "km", "s", "min", "count", "N·m", "Pa", "kPa", "°C", "°", "" -> 0
            "λ" -> 3
            "mA" -> 2
            else -> 1
        }
    }

    /**
     * The label for one channel, as a string resource and the numbers to fill it with.
     *
     * The sensor families are named from templates rather than one resource per sensor:
     * eight oxygen sensors with four channels between them would otherwise be dozens of
     * strings that differ only by a digit.
     */
    private fun nameOf(pid: Int, channel: Int): Pair<Int, List<Int>> = when (pid) {
        in 0x14..0x1B -> {
            val sensor = pid - 0x14 + 1
            val res = if (channel == 0) R.string.metric_o2_voltage else R.string.metric_o2_trim
            res to listOf(sensor)
        }

        in 0x24..0x2B -> {
            val sensor = pid - 0x24 + 1
            val res = if (channel == 0) R.string.metric_o2_lambda else R.string.metric_o2_voltage
            res to listOf(sensor)
        }

        in 0x34..0x3B -> {
            val sensor = pid - 0x34 + 1
            val res = if (channel == 0) R.string.metric_o2_lambda else R.string.metric_o2_current
            res to listOf(sensor)
        }

        // 0155/0157 are the short term trims and 0156/0158 the long term ones; each
        // carries two banks, and which two depends on the PID.
        0x55, 0x57 -> R.string.metric_secondary_trim_short to listOf(secondaryBank(pid, channel))
        0x56, 0x58 -> R.string.metric_secondary_trim_long to listOf(secondaryBank(pid, channel))

        0x66 -> R.string.metric_maf_sensor to listOf(channel + 1)
        0x67 -> R.string.metric_ect_sensor to listOf(channel + 1)
        0x68 -> R.string.metric_iat_sensor to listOf(channel / 3 + 1, channel % 3 + 1)
        0x6B -> R.string.metric_egr_temp to listOf(channel / 2 + 1, channel % 2 + 1)
        0x73 -> R.string.metric_exhaust_pressure to listOf(channel + 1)
        0x74 -> R.string.metric_turbo_speed to listOf(channel + 1)
        0x77 -> R.string.metric_charge_air_temp to listOf(channel / 2 + 1, channel % 2 + 1)
        0x78 -> R.string.metric_exhaust_gas_temp to listOf(1, channel + 1)
        0x79 -> R.string.metric_exhaust_gas_temp to listOf(2, channel + 1)
        else -> nameResFor(pid) to emptyList()
    }

    /** `0155` and `0156` report banks 1 and 3; `0157` and `0158` report banks 2 and 4. */
    private fun secondaryBank(pid: Int, channel: Int): Int =
        if (pid == 0x55 || pid == 0x56) 1 + channel * 2 else 2 + channel * 2

    @StringRes
    private fun nameResFor(pid: Int): Int = when (pid) {
        0x04 -> R.string.metric_pid_04
        0x05 -> R.string.metric_pid_05
        0x06 -> R.string.metric_pid_06
        0x07 -> R.string.metric_pid_07
        0x08 -> R.string.metric_pid_08
        0x09 -> R.string.metric_pid_09
        0x0A -> R.string.metric_pid_0a
        0x0B -> R.string.metric_pid_0b
        0x0C -> R.string.metric_pid_0c
        0x0D -> R.string.metric_pid_0d
        0x0E -> R.string.metric_pid_0e
        0x0F -> R.string.metric_pid_0f
        0x10 -> R.string.metric_pid_10
        0x11 -> R.string.metric_pid_11
        0x1F -> R.string.metric_pid_1f
        0x21 -> R.string.metric_pid_21
        0x22 -> R.string.metric_pid_22
        0x23 -> R.string.metric_pid_23
        0x2C -> R.string.metric_pid_2c
        0x2D -> R.string.metric_pid_2d
        0x2E -> R.string.metric_pid_2e
        0x2F -> R.string.metric_pid_2f
        0x30 -> R.string.metric_pid_30
        0x31 -> R.string.metric_pid_31
        0x32 -> R.string.metric_pid_32
        0x33 -> R.string.metric_pid_33
        0x3C -> R.string.metric_pid_3c
        0x3D -> R.string.metric_pid_3d
        0x3E -> R.string.metric_pid_3e
        0x3F -> R.string.metric_pid_3f
        0x42 -> R.string.metric_pid_42
        0x43 -> R.string.metric_pid_43
        0x44 -> R.string.metric_pid_44
        0x45 -> R.string.metric_pid_45
        0x46 -> R.string.metric_pid_46
        0x47 -> R.string.metric_pid_47
        0x48 -> R.string.metric_pid_48
        0x49 -> R.string.metric_pid_49
        0x4A -> R.string.metric_pid_4a
        0x4B -> R.string.metric_pid_4b
        0x4C -> R.string.metric_pid_4c
        0x4D -> R.string.metric_pid_4d
        0x4E -> R.string.metric_pid_4e
        0x51 -> R.string.metric_pid_51
        0x52 -> R.string.metric_pid_52
        0x53 -> R.string.metric_pid_53
        0x54 -> R.string.metric_pid_54
        0x59 -> R.string.metric_pid_59
        0x5A -> R.string.metric_pid_5a
        0x5B -> R.string.metric_pid_5b
        0x5C -> R.string.metric_pid_5c
        0x5D -> R.string.metric_pid_5d
        0x5E -> R.string.metric_pid_5e
        0x61 -> R.string.metric_pid_61
        0x62 -> R.string.metric_pid_62
        0x63 -> R.string.metric_pid_63
        0x9E -> R.string.metric_pid_9e
        0xA4 -> R.string.metric_pid_a4
        0xA6 -> R.string.metric_pid_a6
        else -> R.string.metric_unknown
    }
}

/** Current value of [id], or null when the car has not reported it yet. */
fun VehicleSnapshot.valueOf(id: MetricId): Double? = when (id) {
    is MetricId.Sensor -> readings[id.key]?.value
    is MetricId.Derived -> derived[id.key]
    MetricId.Battery -> batteryVoltage
}

/** When [id] was last refreshed, used to dim readings that have gone stale. */
fun VehicleSnapshot.updatedAtOf(id: MetricId): Long = when (id) {
    is MetricId.Sensor -> readings[id.key]?.timestampMillis ?: 0L
    else -> updatedAtMillis
}

/** Every metric the current snapshot actually carries a value for. */
fun VehicleSnapshot.presentMetrics(): List<MetricId> = buildList {
    readings.keys.forEach { add(MetricId.Sensor(it)) }
    derived.keys.forEach { add(MetricId.Derived(it)) }
    if (batteryVoltage != null) add(MetricId.Battery)
}
