package com.miskibin.obd2dashboard.data

import androidx.annotation.StringRes
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.obd.Assumption
import com.miskibin.obd2dashboard.obd.DerivedMetrics
import com.miskibin.obd2dashboard.obd.ExtendedPids
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.Provenance
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

    /**
     * A manufacturer-specific reading, keyed by [com.miskibin.obd2dashboard.obd.ExtendedPid.id].
     *
     * Identified by name rather than by number because the number is not the identity: two
     * generations of the same car report tyre pressure from different modules at different
     * identifiers, and to everything above this they are the same reading.
     */
    data class Extended(val id: String) : MetricId {
        override val storageKey: String get() = EXTENDED_PREFIX + id
    }

    /** Adapter-reported battery voltage (`ATRV`), which is not a PID. */
    data object Battery : MetricId {
        override val storageKey: String get() = BATTERY_KEY
    }

    companion object {
        private const val PID_PREFIX = "pid:"
        private const val DERIVED_PREFIX = "derived:"
        private const val EXTENDED_PREFIX = "ext:"
        private const val BATTERY_KEY = "battery"

        fun parse(raw: String): MetricId? = when {
            raw == BATTERY_KEY -> Battery
            raw.startsWith(PID_PREFIX) ->
                raw.removePrefix(PID_PREFIX).toIntOrNull(radix = 16)?.let(::Sensor)

            raw.startsWith(DERIVED_PREFIX) ->
                raw.removePrefix(DERIVED_PREFIX).takeIf(String::isNotEmpty)?.let(::Derived)

            raw.startsWith(EXTENDED_PREFIX) ->
                raw.removePrefix(EXTENDED_PREFIX).takeIf(String::isNotEmpty)?.let(::Extended)

            else -> null
        }
    }
}

/**
 * Presentation metadata for a [MetricId]: the localised label, the unit as shown next to
 * the value, and how many decimals reading it at a glance deserves.
 *
 * [hintRes] is the tile's one line of plain language: what the number means to somebody
 * who has never seen the acronym before. "Air flow (MAF) 22 g/s" tells a mechanic
 * something and an owner nothing; "how much air is going in" is the same reading with the
 * jargon paid for.
 *
 * [descriptionRes] is the paragraph behind that line, for the driver who tapped the tile
 * because the subtitle made them curious: what the sensor physically measures, why it is
 * worth looking at, and what a typical or a worrying value looks like. The hint has to fit
 * under a name on a tile, so it can only ever gesture at the answer; this is the answer.
 */
data class Metric(
    val id: MetricId,
    @param:StringRes val nameRes: Int,
    @param:StringRes val hintRes: Int,
    @param:StringRes val descriptionRes: Int,
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

    /** The width of the band, when both ends are stated. */
    val span: Double? get() = if (min != null && max != null) max - min else null
}

/**
 * How far outside normal a reading is, in the three steps the dashboard colours for.
 *
 * Two steps rather than one because "over" is not one thing: a coolant temperature a
 * degree past ninety-eight is a car that has just been asked to climb a hill, and one
 * fifteen degrees past it is a car to stop. The threshold between them is a fraction of
 * the band's own width, so it means the same on a five-degree band as on a fifty-degree
 * one; where only one end of normal is published there is no width to measure against,
 * and the reading can only be said to be out, not how far.
 */
enum class MetricStatus(@param:StringRes val labelRes: Int, val level: Int) {
    Normal(R.string.metric_state_normal, 0),
    Above(R.string.metric_state_above, 1),
    Below(R.string.metric_state_below, 1),
    FarAbove(R.string.metric_state_far_above, 2),
    FarBelow(R.string.metric_state_far_below, 2),
    ;

    /** Outside the band at all — the tile tints, the zone lights up. */
    val breached: Boolean get() = level > 0

    /** Far enough outside to be told in the loudest colour the app has. */
    val severe: Boolean get() = level > 1
}

/** How far past a band's end a value goes before it counts as far out, as a fraction. */
private const val FAR_FRACTION = 0.18

/**
 * Where [value] sits against this band, or null when there is nothing to say.
 *
 * A metric with no published band gets no verdict: "normal" about a range nobody defined
 * would be the app inventing a reassurance it cannot back.
 */
fun NormalBand?.statusOf(value: Double?): MetricStatus? {
    if (this == null || isEmpty || value == null || !value.isFinite()) return null
    val width = span?.takeIf { it > 0.0 }
    val over = max?.let { value - it }?.takeIf { it > 0.0 }
    if (over != null) {
        return if (width != null && over / width > FAR_FRACTION) MetricStatus.FarAbove
        else MetricStatus.Above
    }
    val under = min?.let { it - value }?.takeIf { it > 0.0 }
    if (under != null) {
        return if (width != null && under / width > FAR_FRACTION) MetricStatus.FarBelow
        else MetricStatus.Below
    }
    return MetricStatus.Normal
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

    /**
     * The id prefixes that mark a tyre reading, which the four wheels share a paragraph on.
     *
     * Declared up here with [O2_VOLTAGE_KEYS] and for the same reason: [catalog] reads them
     * while it is being built, and a property of an `object` initialised further down is
     * still null at that point.
     */
    private val TYRE_PRESSURE_PREFIX = ExtendedPids.tyrePressureId("")
    private val TYRE_TEMPERATURE_PREFIX = ExtendedPids.tyreTemperatureId("")

    /** The four injector corrections, which share a subtitle and a paragraph. */
    private val INJECTION_DEVIATION_PREFIX = ExtendedPids.injectionDeviationId(0).dropLast(1)

    /** The manufacturer-specific readings that are a temperature, whatever they are of. */
    private val EXTENDED_TEMPERATURES = setOf(
        ExtendedPids.OIL_TEMPERATURE,
        ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE,
        ExtendedPids.CHARGE_AIR_TEMPERATURE,
        ExtendedPids.CYLINDER_HEAD_TEMPERATURE,
        ExtendedPids.DPF_INLET_TEMPERATURE,
        ExtendedPids.DPF_OUTLET_TEMPERATURE,
        ExtendedPids.INVERTER_TEMPERATURE,
        ExtendedPids.BATTERY_TEMPERATURE,
    )

    /** The ones that describe the vehicle rather than its engine: batteries, distance. */
    private val EXTENDED_VEHICLE = setOf(
        ExtendedPids.ODOMETER,
        ExtendedPids.BATTERY_SOC,
        ExtendedPids.BATTERY_HEALTH,
        ExtendedPids.BATTERY_VOLTAGE,
        ExtendedPids.BATTERY_CURRENT,
        ExtendedPids.BATTERY_RESISTANCE,
        ExtendedPids.HV_SOC,
        ExtendedPids.HV_HEALTH,
        ExtendedPids.HV_VOLTAGE,
        ExtendedPids.HV_CURRENT,
        ExtendedPids.HV_ENERGY,
    )

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
                        hintRes = hintOf(pid.id, channel.index),
                        descriptionRes = descriptionOf(pid.id, channel.index),
                        unit = channel.unit,
                        decimals = decimalsFor(key, channel.unit),
                        nameArgs = args,
                    ),
                )
            }
        }
        add(
            Metric(
                MetricId.Derived(DerivedMetrics.Boost.key),
                R.string.metric_boost,
                R.string.metric_hint_boost,
                R.string.metric_desc_boost,
                "kPa",
                0,
            ),
        )
        add(
            Metric(
                MetricId.Derived(DerivedMetrics.FuelRate.key),
                R.string.metric_fuel_rate,
                R.string.metric_hint_fuel_rate,
                R.string.metric_desc_fuel_rate,
                "L/h",
                1,
            ),
        )
        add(
            Metric(
                MetricId.Derived(DerivedMetrics.FuelPer100Km.key),
                R.string.metric_fuel_per_100km,
                R.string.metric_hint_fuel_per_100km,
                R.string.metric_desc_fuel_per_100km,
                "L/100km",
                1,
            ),
        )
        add(
            Metric(
                MetricId.Battery,
                R.string.metric_battery,
                R.string.metric_hint_battery,
                R.string.metric_desc_battery,
                "V",
                1,
            ),
        )
        // The manufacturer-specific readings sit in the same catalogue as everything else,
        // so a tile, a chart line and a recording column reach them by the ordinary route.
        // They are only ever *offered* on a car whose probe answered for them; see
        // Metric.isAvailable.
        ExtendedPids.metrics.forEach { pid ->
            add(
                Metric(
                    id = MetricId.Extended(pid.id),
                    nameRes = extendedNameRes(pid.id),
                    hintRes = extendedHintRes(pid.id),
                    descriptionRes = extendedDescriptionRes(pid.id),
                    unit = pid.unit,
                    decimals = pid.decimals,
                ),
            )
        }
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
     *
     * They are a *general* guide and not this car's specification, and the sheet says so.
     * Nothing in OBD2 reports what a given engine's normal coolant temperature is, and the
     * figures differ by a good ten degrees between a thermostat that opens at 87 °C and one
     * that opens at 105 — so a driver whose car runs hotter than the band by design needs
     * to know the band came from the app rather than from the engine.
     */
    val normalBands: Map<MetricId, NormalBand> = mapOf(
        CoolantTemp to NormalBand(82.0, 98.0),
        OilTemp to NormalBand(80.0, 110.0),
        // ATRV is the voltage at the OBD socket, which is the resting battery with the
        // engine off and the charging system with it running. One band has to cover both,
        // or every key-on reading would be tinted for a battery that is perfectly healthy;
        // a charging system that has actually stopped charging is the engine-running alert
        // rule's job, not this band's.
        Battery to NormalBand(12.2, 14.8),
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

    /**
     * Whether the band shown for [id] is the driver's own threshold rather than the app's
     * general guide, which is the difference between "you asked to be told past 110 °C" and
     * "engines of this sort usually sit under 110 °C".
     */
    fun bandIsDriverSet(id: MetricId, rules: List<AlertRule>): Boolean =
        rules.any { it.metric == id && it.enabled }

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

        // Filed by what the number is about rather than by which marque reports it: a
        // driver looking for a gearbox temperature is looking among temperatures, and it is
        // no business of theirs that on their car it comes from an identifier and on the
        // next one from a block read.
        is MetricId.Extended -> when {
            id.id in EXTENDED_TEMPERATURES -> MetricGroup.Temperature
            id.id.startsWith(TYRE_TEMPERATURE_PREFIX) -> MetricGroup.Temperature
            id.id in EXTENDED_VEHICLE -> MetricGroup.Vehicle
            id.id.startsWith(TYRE_PRESSURE_PREFIX) -> MetricGroup.Vehicle
            else -> MetricGroup.Engine
        }

        is MetricId.Sensor -> when (id.pid) {
            Pids.COOLANT_TEMP, Pids.INTAKE_AIR_TEMP, Pids.OIL_TEMP, Pids.AMBIENT_AIR_TEMP,
            in 0x3C..0x3F, 0x67, 0x68, 0x6B, 0x77, 0x78, 0x79,
            -> MetricGroup.Temperature

            Pids.SHORT_FUEL_TRIM_1, Pids.LONG_FUEL_TRIM_1, 0x08, 0x09, Pids.MAF_RATE,
            in 0x14..0x1B, in 0x24..0x2B, in 0x34..0x3B, in 0x55..0x58,
            Pids.COMMANDED_EQUIV_RATIO, 0x2C, 0x2D, 0x2E, 0x52, 0x53, 0x54, 0x66,
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
        // Two fuel systems, named by their number rather than by two near-identical strings.
        Pids.FUEL_SYSTEM_STATUS -> R.string.metric_fuel_system to listOf(channel + 1)

        // `0165` packs five unrelated things behind one support byte, so each channel is a
        // metric in its own right rather than a numbered member of a family.
        Pids.AUXILIARY_IO -> auxiliaryNameRes(channel) to emptyList()

        Pids.FUEL_RATE_MASS -> {
            val res = if (channel == 0) R.string.metric_pid_9d else R.string.metric_pid_9d_vehicle
            res to emptyList()
        }

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

    /**
     * The plain-language subtitle for one channel; see [Metric.hintRes].
     *
     * Follows the same shape as [nameOf] because the same channels need explaining: which
     * of a wideband probe's two numbers you are looking at is the whole difference between
     * "1.000 is the ideal mixture" and "pump current from a wideband probe".
     */
    @StringRes
    private fun hintOf(pid: Int, channel: Int): Int = when (pid) {
        Pids.FUEL_SYSTEM_STATUS -> R.string.metric_hint_pid_03
        Pids.AUXILIARY_IO -> auxiliaryHintRes(channel)
        Pids.FUEL_RATE_MASS ->
            if (channel == 0) R.string.metric_hint_pid_9d else R.string.metric_hint_pid_9d_vehicle

        in 0x14..0x1B ->
            if (channel == 0) R.string.metric_hint_o2_voltage else R.string.metric_hint_o2_trim

        // The second channel of a wide-range sensor is the probe's own signal voltage, not
        // the 0.1–0.9 V switch a narrow-band sensor makes, so it gets its own subtitle.
        in 0x24..0x2B ->
            if (channel == 0) R.string.metric_hint_o2_lambda
            else R.string.metric_hint_o2_wide_voltage

        in 0x34..0x3B ->
            if (channel == 0) R.string.metric_hint_o2_lambda else R.string.metric_hint_o2_current

        0x55, 0x57 -> R.string.metric_hint_secondary_trim_short
        0x56, 0x58 -> R.string.metric_hint_secondary_trim_long
        0x66 -> R.string.metric_hint_maf_sensor
        0x67 -> R.string.metric_hint_ect_sensor
        0x68 -> R.string.metric_hint_iat_sensor
        0x6B -> R.string.metric_hint_egr_temp
        0x73 -> R.string.metric_hint_exhaust_pressure
        0x74 -> R.string.metric_hint_turbo_speed
        0x77 -> R.string.metric_hint_charge_air_temp
        0x78, 0x79 -> R.string.metric_hint_exhaust_gas_temp
        else -> hintResFor(pid)
    }

    /**
     * The paragraph explaining one channel; see [Metric.descriptionRes].
     *
     * Families share one description rather than getting one per sensor: what an oxygen
     * sensor is does not change between the second one and the sixth, and the channel's own
     * nuance — which bank it sits on, that a wide-range probe's second channel is a signal
     * voltage and not the narrow-band switch — is written into the text instead. Splitting
     * it per sensor would be dozens of paragraphs that differ by a digit, in every language.
     */
    @StringRes
    private fun descriptionOf(pid: Int, channel: Int): Int = when (pid) {
        Pids.FUEL_SYSTEM_STATUS -> R.string.metric_desc_pid_03
        Pids.AUXILIARY_IO -> auxiliaryDescriptionRes(channel)
        // Both channels are a fuel flow by mass; what differs is whose, which the two
        // paragraphs say. Sharing one would leave a driver wondering why the car reports
        // the same number twice — which on most cars it does, and for a reason.
        Pids.FUEL_RATE_MASS ->
            if (channel == 0) R.string.metric_desc_pid_9d else R.string.metric_desc_pid_9d_vehicle

        in 0x14..0x1B ->
            if (channel == 0) R.string.metric_desc_o2_voltage else R.string.metric_desc_o2_trim

        in 0x24..0x2B ->
            if (channel == 0) R.string.metric_desc_o2_lambda
            else R.string.metric_desc_o2_wide_voltage

        in 0x34..0x3B ->
            if (channel == 0) R.string.metric_desc_o2_lambda else R.string.metric_desc_o2_current

        0x55, 0x57 -> R.string.metric_desc_secondary_trim_short
        0x56, 0x58 -> R.string.metric_desc_secondary_trim_long
        0x66 -> R.string.metric_desc_maf_sensor
        0x67 -> R.string.metric_desc_ect_sensor
        0x68 -> R.string.metric_desc_iat_sensor
        0x6B -> R.string.metric_desc_egr_temp
        0x73 -> R.string.metric_desc_exhaust_pressure
        0x74 -> R.string.metric_desc_turbo_speed
        0x77 -> R.string.metric_desc_charge_air_temp
        0x78, 0x79 -> R.string.metric_desc_exhaust_gas_temp
        else -> descriptionResFor(pid)
    }

    /**
     * The five channels of `0165`, which share a PID and nothing else.
     *
     * Written out per channel rather than derived, because the recommended gear is a shift
     * indicator, the glow plug lamp is a diesel warning light and the power take-off is
     * fitted to a van — three unrelated things that happen to be bit-encoded into one
     * two-byte answer.
     */
    @StringRes
    private fun auxiliaryNameRes(channel: Int): Int = when (channel) {
        AUX_RECOMMENDED_GEAR -> R.string.metric_pid_65_gear
        AUX_GLOW_PLUG -> R.string.metric_pid_65_glow_plug
        AUX_MANUAL_NEUTRAL -> R.string.metric_pid_65_manual_neutral
        AUX_AUTO_NEUTRAL -> R.string.metric_pid_65_auto_neutral
        else -> R.string.metric_pid_65_pto
    }

    @StringRes
    private fun auxiliaryHintRes(channel: Int): Int = when (channel) {
        AUX_RECOMMENDED_GEAR -> R.string.metric_hint_pid_65_gear
        AUX_GLOW_PLUG -> R.string.metric_hint_pid_65_glow_plug
        AUX_MANUAL_NEUTRAL, AUX_AUTO_NEUTRAL -> R.string.metric_hint_pid_65_neutral
        else -> R.string.metric_hint_pid_65_pto
    }

    @StringRes
    private fun auxiliaryDescriptionRes(channel: Int): Int = when (channel) {
        AUX_RECOMMENDED_GEAR -> R.string.metric_desc_pid_65_gear
        AUX_GLOW_PLUG -> R.string.metric_desc_pid_65_glow_plug
        AUX_MANUAL_NEUTRAL, AUX_AUTO_NEUTRAL -> R.string.metric_desc_pid_65_neutral
        else -> R.string.metric_desc_pid_65_pto
    }

    private const val AUX_RECOMMENDED_GEAR = 0
    private const val AUX_GLOW_PLUG = 1
    private const val AUX_MANUAL_NEUTRAL = 2
    private const val AUX_AUTO_NEUTRAL = 3

    /**
     * The name of one manufacturer-specific reading.
     *
     * Each tyre gets a name of its own — "front left" is not a number that could be
     * substituted into a template — while the sentence explaining what a tyre pressure is
     * is written once and shared by all four, the same way the oxygen sensor family shares
     * one paragraph.
     */
    @StringRes
    private fun extendedNameRes(id: String): Int = when (id) {
        ExtendedPids.OIL_PRESSURE -> R.string.metric_ext_oil_pressure
        ExtendedPids.OIL_TEMPERATURE -> R.string.metric_ext_oil_temperature
        ExtendedPids.OIL_LEVEL -> R.string.metric_ext_oil_level
        ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE -> R.string.metric_ext_atf_temperature
        ExtendedPids.BOOST_PRESSURE -> R.string.metric_ext_boost_pressure
        ExtendedPids.CHARGE_AIR_TEMPERATURE -> R.string.metric_ext_charge_air_temperature
        ExtendedPids.CYLINDER_HEAD_TEMPERATURE -> R.string.metric_ext_cylinder_head_temperature
        ExtendedPids.WASTEGATE_DUTY -> R.string.metric_ext_wastegate_duty
        ExtendedPids.ENGINE_TORQUE -> R.string.metric_ext_engine_torque
        ExtendedPids.ODOMETER -> R.string.metric_ext_odometer
        ExtendedPids.ALTERNATOR_POWER -> R.string.metric_ext_alternator_power
        ExtendedPids.AC_PRESSURE -> R.string.metric_ext_ac_pressure
        ExtendedPids.EGR_POSITION -> R.string.metric_ext_egr_position
        ExtendedPids.TURBO_VANE_POSITION -> R.string.metric_ext_turbo_vane_position
        ExtendedPids.BATTERY_SOC -> R.string.metric_ext_battery_soc
        ExtendedPids.BATTERY_HEALTH -> R.string.metric_ext_battery_health
        ExtendedPids.BATTERY_TEMPERATURE -> R.string.metric_ext_battery_temperature
        ExtendedPids.BATTERY_VOLTAGE -> R.string.metric_ext_battery_voltage
        ExtendedPids.BATTERY_CURRENT -> R.string.metric_ext_battery_current
        ExtendedPids.BATTERY_RESISTANCE -> R.string.metric_ext_battery_resistance
        ExtendedPids.DPF_SOOT_MEASURED -> R.string.metric_ext_dpf_soot_measured
        ExtendedPids.DPF_SOOT_CALCULATED -> R.string.metric_ext_dpf_soot_calculated
        ExtendedPids.DPF_ASH_MASS -> R.string.metric_ext_dpf_ash_mass
        ExtendedPids.DPF_DISTANCE_SINCE_REGEN -> R.string.metric_ext_dpf_distance_since_regen
        ExtendedPids.DPF_REGEN_INTERRUPTIONS -> R.string.metric_ext_dpf_regen_interruptions
        ExtendedPids.DPF_INLET_TEMPERATURE -> R.string.metric_ext_dpf_inlet_temperature
        ExtendedPids.DPF_OUTLET_TEMPERATURE -> R.string.metric_ext_dpf_outlet_temperature
        ExtendedPids.DPF_PRESSURE_DIFFERENCE -> R.string.metric_ext_dpf_pressure_difference
        ExtendedPids.HV_SOC -> R.string.metric_ext_hv_soc
        ExtendedPids.HV_HEALTH -> R.string.metric_ext_hv_health
        ExtendedPids.HV_VOLTAGE -> R.string.metric_ext_hv_voltage
        ExtendedPids.HV_CURRENT -> R.string.metric_ext_hv_current
        ExtendedPids.HV_ENERGY -> R.string.metric_ext_hv_energy
        ExtendedPids.INVERTER_TEMPERATURE -> R.string.metric_ext_inverter_temperature
        ExtendedPids.tyrePressureId(FRONT_LEFT) -> R.string.metric_ext_tyre_pressure_fl
        ExtendedPids.tyrePressureId(FRONT_RIGHT) -> R.string.metric_ext_tyre_pressure_fr
        ExtendedPids.tyrePressureId(REAR_LEFT) -> R.string.metric_ext_tyre_pressure_rl
        ExtendedPids.tyrePressureId(REAR_RIGHT) -> R.string.metric_ext_tyre_pressure_rr
        ExtendedPids.tyreTemperatureId(FRONT_LEFT) -> R.string.metric_ext_tyre_temperature_fl
        ExtendedPids.tyreTemperatureId(FRONT_RIGHT) -> R.string.metric_ext_tyre_temperature_fr
        ExtendedPids.tyreTemperatureId(REAR_LEFT) -> R.string.metric_ext_tyre_temperature_rl
        ExtendedPids.tyreTemperatureId(REAR_RIGHT) -> R.string.metric_ext_tyre_temperature_rr
        ExtendedPids.injectionDeviationId(1) -> R.string.metric_ext_injection_deviation_1
        ExtendedPids.injectionDeviationId(2) -> R.string.metric_ext_injection_deviation_2
        ExtendedPids.injectionDeviationId(3) -> R.string.metric_ext_injection_deviation_3
        ExtendedPids.injectionDeviationId(4) -> R.string.metric_ext_injection_deviation_4
        else -> R.string.metric_unknown
    }

    /**
     * The plain-language subtitle for one manufacturer-specific reading.
     *
     * The families — four tyres, four injectors — share one line, the same way the oxygen
     * sensors do: what a tyre pressure *is* does not change between the front left and the
     * rear right, and writing it four times would be four strings that differ by a corner,
     * in every language the app ships.
     */
    @StringRes
    private fun extendedHintRes(id: String): Int = when {
        id.startsWith(TYRE_PRESSURE_PREFIX) -> R.string.metric_hint_ext_tyre_pressure
        id.startsWith(TYRE_TEMPERATURE_PREFIX) -> R.string.metric_hint_ext_tyre_temperature
        id.startsWith(INJECTION_DEVIATION_PREFIX) -> R.string.metric_hint_ext_injection_deviation
        else -> when (id) {
            ExtendedPids.OIL_PRESSURE -> R.string.metric_hint_ext_oil_pressure
            ExtendedPids.OIL_TEMPERATURE -> R.string.metric_hint_ext_oil_temperature
            ExtendedPids.OIL_LEVEL -> R.string.metric_hint_ext_oil_level
            ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE -> R.string.metric_hint_ext_atf_temperature
            ExtendedPids.BOOST_PRESSURE -> R.string.metric_hint_ext_boost_pressure
            ExtendedPids.CHARGE_AIR_TEMPERATURE -> R.string.metric_hint_ext_charge_air_temperature
            ExtendedPids.CYLINDER_HEAD_TEMPERATURE ->
                R.string.metric_hint_ext_cylinder_head_temperature

            ExtendedPids.WASTEGATE_DUTY -> R.string.metric_hint_ext_wastegate_duty
            ExtendedPids.ENGINE_TORQUE -> R.string.metric_hint_ext_engine_torque
            ExtendedPids.ODOMETER -> R.string.metric_hint_ext_odometer
            ExtendedPids.ALTERNATOR_POWER -> R.string.metric_hint_ext_alternator_power
            ExtendedPids.AC_PRESSURE -> R.string.metric_hint_ext_ac_pressure
            ExtendedPids.EGR_POSITION -> R.string.metric_hint_ext_egr_position
            ExtendedPids.TURBO_VANE_POSITION -> R.string.metric_hint_ext_turbo_vane_position
            ExtendedPids.BATTERY_SOC -> R.string.metric_hint_ext_battery_soc
            ExtendedPids.BATTERY_HEALTH -> R.string.metric_hint_ext_battery_health
            ExtendedPids.BATTERY_TEMPERATURE -> R.string.metric_hint_ext_battery_temperature
            ExtendedPids.BATTERY_VOLTAGE -> R.string.metric_hint_ext_battery_voltage
            ExtendedPids.BATTERY_CURRENT -> R.string.metric_hint_ext_battery_current
            ExtendedPids.BATTERY_RESISTANCE -> R.string.metric_hint_ext_battery_resistance
            ExtendedPids.DPF_SOOT_MEASURED -> R.string.metric_hint_ext_dpf_soot_measured
            ExtendedPids.DPF_SOOT_CALCULATED -> R.string.metric_hint_ext_dpf_soot_calculated
            ExtendedPids.DPF_ASH_MASS -> R.string.metric_hint_ext_dpf_ash_mass
            ExtendedPids.DPF_DISTANCE_SINCE_REGEN ->
                R.string.metric_hint_ext_dpf_distance_since_regen

            ExtendedPids.DPF_REGEN_INTERRUPTIONS -> R.string.metric_hint_ext_dpf_regen_interruptions
            ExtendedPids.DPF_INLET_TEMPERATURE -> R.string.metric_hint_ext_dpf_inlet_temperature
            ExtendedPids.DPF_OUTLET_TEMPERATURE -> R.string.metric_hint_ext_dpf_outlet_temperature
            ExtendedPids.DPF_PRESSURE_DIFFERENCE -> R.string.metric_hint_ext_dpf_pressure_difference
            ExtendedPids.HV_SOC -> R.string.metric_hint_ext_hv_soc
            ExtendedPids.HV_HEALTH -> R.string.metric_hint_ext_hv_health
            ExtendedPids.HV_VOLTAGE -> R.string.metric_hint_ext_hv_voltage
            ExtendedPids.HV_CURRENT -> R.string.metric_hint_ext_hv_current
            ExtendedPids.HV_ENERGY -> R.string.metric_hint_ext_hv_energy
            ExtendedPids.INVERTER_TEMPERATURE -> R.string.metric_hint_ext_inverter_temperature
            else -> R.string.metric_hint_unknown
        }
    }

    @StringRes
    private fun extendedDescriptionRes(id: String): Int = when {
        id.startsWith(TYRE_PRESSURE_PREFIX) -> R.string.metric_desc_ext_tyre_pressure
        id.startsWith(TYRE_TEMPERATURE_PREFIX) -> R.string.metric_desc_ext_tyre_temperature
        id.startsWith(INJECTION_DEVIATION_PREFIX) -> R.string.metric_desc_ext_injection_deviation
        else -> when (id) {
            ExtendedPids.OIL_PRESSURE -> R.string.metric_desc_ext_oil_pressure
            ExtendedPids.OIL_TEMPERATURE -> R.string.metric_desc_ext_oil_temperature
            ExtendedPids.OIL_LEVEL -> R.string.metric_desc_ext_oil_level
            ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE -> R.string.metric_desc_ext_atf_temperature
            ExtendedPids.BOOST_PRESSURE -> R.string.metric_desc_ext_boost_pressure
            ExtendedPids.CHARGE_AIR_TEMPERATURE -> R.string.metric_desc_ext_charge_air_temperature
            ExtendedPids.CYLINDER_HEAD_TEMPERATURE ->
                R.string.metric_desc_ext_cylinder_head_temperature

            ExtendedPids.WASTEGATE_DUTY -> R.string.metric_desc_ext_wastegate_duty
            ExtendedPids.ENGINE_TORQUE -> R.string.metric_desc_ext_engine_torque
            ExtendedPids.ODOMETER -> R.string.metric_desc_ext_odometer
            ExtendedPids.ALTERNATOR_POWER -> R.string.metric_desc_ext_alternator_power
            ExtendedPids.AC_PRESSURE -> R.string.metric_desc_ext_ac_pressure
            ExtendedPids.EGR_POSITION -> R.string.metric_desc_ext_egr_position
            ExtendedPids.TURBO_VANE_POSITION -> R.string.metric_desc_ext_turbo_vane_position
            ExtendedPids.BATTERY_SOC -> R.string.metric_desc_ext_battery_soc
            ExtendedPids.BATTERY_HEALTH -> R.string.metric_desc_ext_battery_health
            ExtendedPids.BATTERY_TEMPERATURE -> R.string.metric_desc_ext_battery_temperature
            ExtendedPids.BATTERY_VOLTAGE -> R.string.metric_desc_ext_battery_voltage
            ExtendedPids.BATTERY_CURRENT -> R.string.metric_desc_ext_battery_current
            ExtendedPids.BATTERY_RESISTANCE -> R.string.metric_desc_ext_battery_resistance
            ExtendedPids.DPF_SOOT_MEASURED -> R.string.metric_desc_ext_dpf_soot_measured
            ExtendedPids.DPF_SOOT_CALCULATED -> R.string.metric_desc_ext_dpf_soot_calculated
            ExtendedPids.DPF_ASH_MASS -> R.string.metric_desc_ext_dpf_ash_mass
            ExtendedPids.DPF_DISTANCE_SINCE_REGEN ->
                R.string.metric_desc_ext_dpf_distance_since_regen

            ExtendedPids.DPF_REGEN_INTERRUPTIONS -> R.string.metric_desc_ext_dpf_regen_interruptions
            ExtendedPids.DPF_INLET_TEMPERATURE -> R.string.metric_desc_ext_dpf_inlet_temperature
            ExtendedPids.DPF_OUTLET_TEMPERATURE -> R.string.metric_desc_ext_dpf_outlet_temperature
            ExtendedPids.DPF_PRESSURE_DIFFERENCE -> R.string.metric_desc_ext_dpf_pressure_difference
            ExtendedPids.HV_SOC -> R.string.metric_desc_ext_hv_soc
            ExtendedPids.HV_HEALTH -> R.string.metric_desc_ext_hv_health
            ExtendedPids.HV_VOLTAGE -> R.string.metric_desc_ext_hv_voltage
            ExtendedPids.HV_CURRENT -> R.string.metric_desc_ext_hv_current
            ExtendedPids.HV_ENERGY -> R.string.metric_desc_ext_hv_energy
            ExtendedPids.INVERTER_TEMPERATURE -> R.string.metric_desc_ext_inverter_temperature
            else -> R.string.metric_desc_unknown
        }
    }


    private const val FRONT_LEFT = "fl"
    private const val FRONT_RIGHT = "fr"
    private const val REAR_LEFT = "rl"
    private const val REAR_RIGHT = "rr"

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
        0x13 -> R.string.metric_pid_13
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
        0x8E -> R.string.metric_pid_8e
        0x9E -> R.string.metric_pid_9e
        0xA4 -> R.string.metric_pid_a4
        0xA6 -> R.string.metric_pid_a6
        else -> R.string.metric_unknown
    }

    /** The plain-language line under the name; see [Metric.hintRes]. */
    @StringRes
    private fun hintResFor(pid: Int): Int = when (pid) {
        0x04 -> R.string.metric_hint_pid_04
        0x05 -> R.string.metric_hint_pid_05
        0x06 -> R.string.metric_hint_pid_06
        0x07 -> R.string.metric_hint_pid_07
        0x08 -> R.string.metric_hint_pid_08
        0x09 -> R.string.metric_hint_pid_09
        0x0A -> R.string.metric_hint_pid_0a
        0x0B -> R.string.metric_hint_pid_0b
        0x0C -> R.string.metric_hint_pid_0c
        0x0D -> R.string.metric_hint_pid_0d
        0x0E -> R.string.metric_hint_pid_0e
        0x0F -> R.string.metric_hint_pid_0f
        0x10 -> R.string.metric_hint_pid_10
        0x11 -> R.string.metric_hint_pid_11
        0x13 -> R.string.metric_hint_pid_13
        0x1F -> R.string.metric_hint_pid_1f
        0x21 -> R.string.metric_hint_pid_21
        0x22 -> R.string.metric_hint_pid_22
        0x23 -> R.string.metric_hint_pid_23
        0x2C -> R.string.metric_hint_pid_2c
        0x2D -> R.string.metric_hint_pid_2d
        0x2E -> R.string.metric_hint_pid_2e
        0x2F -> R.string.metric_hint_pid_2f
        0x30 -> R.string.metric_hint_pid_30
        0x31 -> R.string.metric_hint_pid_31
        0x32 -> R.string.metric_hint_pid_32
        0x33 -> R.string.metric_hint_pid_33
        0x3C -> R.string.metric_hint_pid_3c
        0x3D -> R.string.metric_hint_pid_3d
        0x3E -> R.string.metric_hint_pid_3e
        0x3F -> R.string.metric_hint_pid_3f
        0x42 -> R.string.metric_hint_pid_42
        0x43 -> R.string.metric_hint_pid_43
        0x44 -> R.string.metric_hint_pid_44
        0x45 -> R.string.metric_hint_pid_45
        0x46 -> R.string.metric_hint_pid_46
        0x47 -> R.string.metric_hint_pid_47
        0x48 -> R.string.metric_hint_pid_48
        0x49 -> R.string.metric_hint_pid_49
        0x4A -> R.string.metric_hint_pid_4a
        0x4B -> R.string.metric_hint_pid_4b
        0x4C -> R.string.metric_hint_pid_4c
        0x4D -> R.string.metric_hint_pid_4d
        0x4E -> R.string.metric_hint_pid_4e
        0x51 -> R.string.metric_hint_pid_51
        0x52 -> R.string.metric_hint_pid_52
        0x53 -> R.string.metric_hint_pid_53
        0x54 -> R.string.metric_hint_pid_54
        0x59 -> R.string.metric_hint_pid_59
        0x5A -> R.string.metric_hint_pid_5a
        0x5B -> R.string.metric_hint_pid_5b
        0x5C -> R.string.metric_hint_pid_5c
        0x5D -> R.string.metric_hint_pid_5d
        0x5E -> R.string.metric_hint_pid_5e
        0x61 -> R.string.metric_hint_pid_61
        0x62 -> R.string.metric_hint_pid_62
        0x63 -> R.string.metric_hint_pid_63
        0x8E -> R.string.metric_hint_pid_8e
        0x9E -> R.string.metric_hint_pid_9e
        0xA4 -> R.string.metric_hint_pid_a4
        0xA6 -> R.string.metric_hint_pid_a6
        else -> R.string.metric_hint_unknown
    }

    /** The paragraph behind the subtitle; see [Metric.descriptionRes]. */
    @StringRes
    private fun descriptionResFor(pid: Int): Int = when (pid) {
        0x04 -> R.string.metric_desc_pid_04
        0x05 -> R.string.metric_desc_pid_05
        0x06 -> R.string.metric_desc_pid_06
        0x07 -> R.string.metric_desc_pid_07
        0x08 -> R.string.metric_desc_pid_08
        0x09 -> R.string.metric_desc_pid_09
        0x0A -> R.string.metric_desc_pid_0a
        0x0B -> R.string.metric_desc_pid_0b
        0x0C -> R.string.metric_desc_pid_0c
        0x0D -> R.string.metric_desc_pid_0d
        0x0E -> R.string.metric_desc_pid_0e
        0x0F -> R.string.metric_desc_pid_0f
        0x10 -> R.string.metric_desc_pid_10
        0x11 -> R.string.metric_desc_pid_11
        0x13 -> R.string.metric_desc_pid_13
        0x1F -> R.string.metric_desc_pid_1f
        0x21 -> R.string.metric_desc_pid_21
        0x22 -> R.string.metric_desc_pid_22
        0x23 -> R.string.metric_desc_pid_23
        0x2C -> R.string.metric_desc_pid_2c
        0x2D -> R.string.metric_desc_pid_2d
        0x2E -> R.string.metric_desc_pid_2e
        0x2F -> R.string.metric_desc_pid_2f
        0x30 -> R.string.metric_desc_pid_30
        0x31 -> R.string.metric_desc_pid_31
        0x32 -> R.string.metric_desc_pid_32
        0x33 -> R.string.metric_desc_pid_33
        0x3C -> R.string.metric_desc_pid_3c
        0x3D -> R.string.metric_desc_pid_3d
        0x3E -> R.string.metric_desc_pid_3e
        0x3F -> R.string.metric_desc_pid_3f
        0x42 -> R.string.metric_desc_pid_42
        0x43 -> R.string.metric_desc_pid_43
        0x44 -> R.string.metric_desc_pid_44
        0x45 -> R.string.metric_desc_pid_45
        0x46 -> R.string.metric_desc_pid_46
        0x47 -> R.string.metric_desc_pid_47
        0x48 -> R.string.metric_desc_pid_48
        0x49 -> R.string.metric_desc_pid_49
        0x4A -> R.string.metric_desc_pid_4a
        0x4B -> R.string.metric_desc_pid_4b
        0x4C -> R.string.metric_desc_pid_4c
        0x4D -> R.string.metric_desc_pid_4d
        0x4E -> R.string.metric_desc_pid_4e
        0x51 -> R.string.metric_desc_pid_51
        0x52 -> R.string.metric_desc_pid_52
        0x53 -> R.string.metric_desc_pid_53
        0x54 -> R.string.metric_desc_pid_54
        0x59 -> R.string.metric_desc_pid_59
        0x5A -> R.string.metric_desc_pid_5a
        0x5B -> R.string.metric_desc_pid_5b
        0x5C -> R.string.metric_desc_pid_5c
        0x5D -> R.string.metric_desc_pid_5d
        0x5E -> R.string.metric_desc_pid_5e
        0x61 -> R.string.metric_desc_pid_61
        0x62 -> R.string.metric_desc_pid_62
        0x63 -> R.string.metric_desc_pid_63
        0x8E -> R.string.metric_desc_pid_8e
        0x9E -> R.string.metric_desc_pid_9e
        0xA4 -> R.string.metric_desc_pid_a4
        0xA6 -> R.string.metric_desc_pid_a6
        else -> R.string.metric_desc_unknown
    }
}

/** Current value of [id], or null when the car has not reported it yet. */
fun VehicleSnapshot.valueOf(id: MetricId): Double? = when (id) {
    is MetricId.Sensor -> readings[id.key]?.value
    is MetricId.Derived -> derived[id.key]?.value
    is MetricId.Extended -> extended[id.id]?.value
    MetricId.Battery -> batteryVoltage
}

/**
 * When [id] was last refreshed, used to dim readings that have gone stale.
 *
 * Every kind of value dates itself, and none of them by the snapshot: an extended parameter
 * is read every few cycles at best and a tyre pressure once a quarter minute, the adapter
 * voltage once every twenty cycles, and a derived value is only ever as fresh as the oldest
 * reading behind it. Falling back on the snapshot's own timestamp — which moves whenever
 * *anything* arrives — is how a value that had stopped being reported went on looking live.
 */
fun VehicleSnapshot.updatedAtOf(id: MetricId): Long = when (id) {
    is MetricId.Sensor -> readings[id.key]?.timestampMillis ?: 0L
    is MetricId.Derived -> derived[id.key]?.timestampMillis ?: 0L
    is MetricId.Extended -> extended[id.id]?.timestampMillis ?: 0L
    MetricId.Battery -> batteryAtMillis
}

/**
 * How old a reading of [id] may get before the dashboard stops trusting it.
 *
 * One threshold for everything would be wrong in both directions now that each value dates
 * itself honestly. Whatever is on a tile or a chart line is polled every cycle whatever
 * tier it belongs to, so three seconds without an answer is a link in trouble; the adapter
 * voltage is asked for once every twentieth cycle and a tyre pressure once a quarter
 * minute, and dimming those after three seconds would leave two tiles permanently greyed
 * out for working exactly as designed.
 *
 * So the threshold is the app's own promise about the value: how often it even asks,
 * doubled, with a floor at the three seconds a live gauge is judged by.
 */
fun staleAfterMillis(id: MetricId): Long = when (id) {
    // Prioritised the moment they are shown, so they answer every cycle or not at all.
    is MetricId.Sensor -> LIVE_STALE_MILLIS

    // As old as the slowest input behind it, and the inputs are not all prioritised: boost
    // rides on a barometric pressure that comes round once every five cycles.
    is MetricId.Derived -> DERIVED_STALE_MILLIS

    is MetricId.Extended -> maxOf(
        SLOW_STALE_MILLIS,
        (ExtendedPids[id.id]?.minIntervalMillis ?: 0L) * STALE_INTERVAL_FACTOR,
    )

    // `ATRV` rides along with the slow tier's sweep.
    MetricId.Battery -> SLOW_STALE_MILLIS
}

/** Whether [id]'s value is old enough that the dashboard should stop presenting it as now. */
fun VehicleSnapshot.isStale(id: MetricId, nowMillis: Long): Boolean =
    nowMillis - updatedAtOf(id) > staleAfterMillis(id)

/** What a value the app polls every cycle is judged by; see [staleAfterMillis]. */
const val LIVE_STALE_MILLIS = 3_000L

private const val DERIVED_STALE_MILLIS = 10_000L
private const val SLOW_STALE_MILLIS = 40_000L
private const val STALE_INTERVAL_FACTOR = 2L

/**
 * What the app is claiming by showing [id]: a reading, a computation, or a computation
 * that only closed because a constant was supplied.
 *
 * Only the derived values can be anything but [Provenance.Measured], and which one they are
 * depends on what the car answered this second — a fuel rate is measured on a car with PID
 * 5E, derived on one with a wide-range probe, and assumed on one where the mixture or the
 * fuel type had to come from somewhere else.
 */
fun VehicleSnapshot.provenanceOf(id: MetricId): Provenance = when (id) {
    is MetricId.Derived -> derived[id.key]?.provenance ?: Provenance.Derived
    else -> Provenance.Measured
}

/** The constant that had to be supplied for [id], or null when none was. */
fun VehicleSnapshot.assumptionOf(id: MetricId): Assumption? = when (id) {
    is MetricId.Derived -> derived[id.key]?.assumption
    else -> null
}

/** Every metric the current snapshot actually carries a value for. */
fun VehicleSnapshot.presentMetrics(): List<MetricId> = buildList {
    readings.keys.forEach { add(MetricId.Sensor(it)) }
    derived.keys.forEach { add(MetricId.Derived(it)) }
    extended.keys.forEach { add(MetricId.Extended(it)) }
    if (batteryVoltage != null) add(MetricId.Battery)
}
