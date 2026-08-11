package com.miskibin.obd2dashboard.data

import androidx.annotation.StringRes
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.obd.DerivedMetrics
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import java.util.Locale

/**
 * Stable identity of anything the UI can put on a tile or a chart.
 *
 * [storageKey] is what ends up in DataStore and in CSV headers, so it must never change
 * for a given metric once shipped.
 */
sealed interface MetricId {

    val storageKey: String

    /** A Mode 01 PID read straight off the bus. */
    data class Sensor(val pid: Int) : MetricId {
        override val storageKey: String get() = PID_PREFIX + "%02X".format(Locale.ROOT, pid)
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
) {
    /** English, locale-independent name used for CSV headers. */
    val exportKey: String get() = id.storageKey
}

object Metrics {

    val Rpm = MetricId.Sensor(Pids.ENGINE_RPM)
    val Speed = MetricId.Sensor(Pids.VEHICLE_SPEED)
    val CoolantTemp = MetricId.Sensor(Pids.COOLANT_TEMP)
    val IntakeAirTemp = MetricId.Sensor(Pids.INTAKE_AIR_TEMP)
    val Boost = MetricId.Derived(DerivedMetrics.Boost.key)
    val Battery = MetricId.Battery

    /** What a fresh install shows before the driver picks their own tiles. */
    val defaultTiles: List<MetricId> =
        listOf(Rpm, Speed, CoolantTemp, Boost, Battery, IntakeAirTemp)

    val catalog: List<Metric> = buildList {
        Pids.entries.forEach { pid ->
            add(
                Metric(
                    id = MetricId.Sensor(pid.id),
                    nameRes = nameResFor(pid.id),
                    unit = pid.unit,
                    decimals = decimalsFor(pid.unit),
                ),
            )
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

    private fun decimalsFor(unit: String): Int = when (unit) {
        "rpm", "km/h", "km", "s", "min", "count", "N·m", "Pa", "kPa", "°C", "°" -> 0
        "λ" -> 3
        else -> 1
    }

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
        0x49 -> R.string.metric_pid_49
        0x4A -> R.string.metric_pid_4a
        0x4C -> R.string.metric_pid_4c
        0x4D -> R.string.metric_pid_4d
        0x4E -> R.string.metric_pid_4e
        0x52 -> R.string.metric_pid_52
        0x53 -> R.string.metric_pid_53
        0x59 -> R.string.metric_pid_59
        0x5A -> R.string.metric_pid_5a
        0x5B -> R.string.metric_pid_5b
        0x5C -> R.string.metric_pid_5c
        0x5D -> R.string.metric_pid_5d
        0x5E -> R.string.metric_pid_5e
        0x61 -> R.string.metric_pid_61
        0x62 -> R.string.metric_pid_62
        0x63 -> R.string.metric_pid_63
        0xA6 -> R.string.metric_pid_a6
        else -> R.string.metric_unknown
    }
}

/** Current value of [id], or null when the car has not reported it yet. */
fun VehicleSnapshot.valueOf(id: MetricId): Double? = when (id) {
    is MetricId.Sensor -> readings[id.pid]?.value
    is MetricId.Derived -> derived[id.key]
    MetricId.Battery -> batteryVoltage
}

/** When [id] was last refreshed, used to dim readings that have gone stale. */
fun VehicleSnapshot.updatedAtOf(id: MetricId): Long = when (id) {
    is MetricId.Sensor -> readings[id.pid]?.timestampMillis ?: 0L
    else -> updatedAtMillis
}

/** Every metric the current snapshot actually carries a value for. */
fun VehicleSnapshot.presentMetrics(): List<MetricId> = buildList {
    readings.keys.forEach { add(MetricId.Sensor(it)) }
    derived.keys.forEach { add(MetricId.Derived(it)) }
    if (batteryVoltage != null) add(MetricId.Battery)
}
