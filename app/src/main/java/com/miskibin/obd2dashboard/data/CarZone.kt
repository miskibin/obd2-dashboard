package com.miskibin.obd2dashboard.data

import androidx.annotation.StringRes
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.obd.DerivedMetrics
import com.miskibin.obd2dashboard.obd.ExtendedPids
import com.miskibin.obd2dashboard.obd.MonitorTest
import com.miskibin.obd2dashboard.obd.MonitorTests
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.obd.sensorKey

/**
 * Where the number on a place of the car comes from.
 *
 * Almost always a metric out of the catalogue, which is what makes a zone tappable: the
 * chip opens the same sheet the tile would. The one exception is the misfire count, which
 * no live PID carries — it exists only in the ECU's own self-test results — and which is
 * therefore worth showing even though it has no sheet of its own to open.
 */
sealed interface ZoneSource {

    /** A reading the catalogue knows, shown and opened like any other. */
    data class Reading(val id: MetricId) : ZoneSource

    /** The worst per-cylinder misfire count of the last Mode 06 read. */
    data object Misfires : ZoneSource
}

/**
 * A place on the drawing of the car, and the readings that can stand there.
 *
 * Each zone names the reading that physically belongs to that part of the car and then the
 * nearest thing beside it, because a generic OBD2 car answers a different subset of the
 * standard from the next one: the airflow sensor sits in the same pipe as the intake
 * temperature sensor, and either answers the question the intake is asking. The first
 * candidate the connected car can actually answer for wins; a zone whose car answers for
 * none of them is not drawn at all, however the driver has ticked it.
 *
 * [storageKey] ends up in DataStore, so it must never change for a zone once shipped.
 */
enum class CarZone(
    val storageKey: String,
    @param:StringRes val nameRes: Int,
    val sources: List<ZoneSource>,
) {
    /** The block itself, in the middle of the bay. */
    Engine(
        storageKey = "engine",
        nameRes = R.string.car_zone_engine,
        sources = readings(
            MetricId.Sensor(Pids.ENGINE_LOAD),
            MetricId.Sensor(ABSOLUTE_LOAD),
            MetricId.Sensor(Pids.TIMING_ADVANCE),
        ),
    ),

    /** The throttle body, between the intake and the head. */
    Throttle(
        storageKey = "throttle",
        nameRes = R.string.car_zone_throttle,
        sources = readings(
            MetricId.Sensor(Pids.THROTTLE_POSITION),
            MetricId.Sensor(RELATIVE_THROTTLE),
            MetricId.Sensor(ABSOLUTE_THROTTLE_B),
        ),
    ),

    /** The fuel rail across the head, with an injector under each dot. */
    Fuel(
        storageKey = "fuel",
        nameRes = R.string.car_zone_fuel,
        sources = readings(
            MetricId.Sensor(FUEL_RAIL_GAUGE_PRESSURE),
            MetricId.Sensor(FUEL_RAIL_PRESSURE),
            MetricId.Derived(DerivedMetrics.FuelRate.key),
            MetricId.Sensor(Pids.FUEL_LEVEL),
        ),
    ),

    /** The coil pack. The misfire count is the only number that is really about it. */
    Ignition(
        storageKey = "ignition",
        nameRes = R.string.car_zone_ignition,
        sources = listOf(
            ZoneSource.Misfires,
            ZoneSource.Reading(MetricId.Sensor(Pids.TIMING_ADVANCE)),
        ),
    ),

    /** The airbox and the duct out of it. */
    Intake(
        storageKey = "intake",
        nameRes = R.string.car_zone_intake,
        sources = readings(
            MetricId.Sensor(Pids.MAF_RATE),
            MetricId.Sensor(MAF_SENSOR),
            MetricId.Sensor(Pids.INTAKE_AIR_TEMP),
            MetricId.Sensor(Pids.INTAKE_MAP),
        ),
    ),

    /** The radiator, across the nose. */
    Coolant(
        storageKey = "coolant",
        nameRes = R.string.car_zone_coolant,
        sources = readings(
            MetricId.Sensor(Pids.COOLANT_TEMP),
            MetricId.Sensor(ECT_SENSOR),
        ),
    ),

    /** The battery in the corner of the bay. */
    Battery(
        storageKey = "battery",
        nameRes = R.string.car_zone_battery,
        sources = readings(
            MetricId.Battery,
            MetricId.Sensor(Pids.CONTROL_MODULE_VOLTAGE),
        ),
    ),

    /** The sump under the block. Its pressure is manufacturer-specific; its heat need not be. */
    Oil(
        storageKey = "oil",
        nameRes = R.string.car_zone_oil,
        sources = readings(
            MetricId.Extended(ExtendedPids.OIL_PRESSURE),
            MetricId.Extended(ExtendedPids.OIL_TEMPERATURE),
            MetricId.Sensor(Pids.OIL_TEMP),
        ),
    ),

    /** The gearbox behind the engine. */
    Transmission(
        storageKey = "transmission",
        nameRes = R.string.car_zone_transmission,
        sources = readings(
            MetricId.Extended(ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE),
            MetricId.Sensor(Pids.TRANSMISSION_GEAR),
        ),
    ),

    /** The exhaust, with the probe hanging off it. */
    Exhaust(
        storageKey = "exhaust",
        nameRes = R.string.car_zone_exhaust,
        sources = readings(
            MetricId.Sensor(sensorKey(WIDE_OXYGEN_SENSOR_1, 0)),
            MetricId.Sensor(sensorKey(OXYGEN_SENSOR_1, 0)),
            MetricId.Sensor(CATALYST_TEMP_B1S1),
        ),
    ),

    TyreFrontLeft("tyre_fl", R.string.car_zone_tyre_fl, tyre(FRONT_LEFT)),
    TyreFrontRight("tyre_fr", R.string.car_zone_tyre_fr, tyre(FRONT_RIGHT)),
    TyreRearLeft("tyre_rl", R.string.car_zone_tyre_rl, tyre(REAR_LEFT)),
    TyreRearRight("tyre_rr", R.string.car_zone_tyre_rr, tyre(REAR_RIGHT)),
    ;

    /**
     * The first of this zone's readings the connected car can answer for, or null.
     *
     * "Can answer for" is deliberately stricter here than in the chart picker: a place on
     * the drawing that lights up merely because support is not known yet would be a car
     * covered in chips that never fill in. Either the value is already in the snapshot, or
     * the car listed the PID as supported, or the connect-time probe saw it answer for the
     * manufacturer-specific one.
     */
    fun sourceOn(
        snapshot: VehicleSnapshot,
        supportedPids: Set<Int>,
        supportedExtended: Set<String>,
        misfire: MisfireReading?,
    ): ZoneSource? = sources.firstOrNull { source ->
        when (source) {
            ZoneSource.Misfires -> misfire != null
            is ZoneSource.Reading -> Metrics[source.id] != null && (
                snapshot.valueOf(source.id) != null ||
                    isSupported(source.id, supportedPids, supportedExtended)
                )
        }
    }

    companion object {

        /**
         * What a fresh install draws: the four places every car has something to say about.
         *
         * The rest are off until the driver asks for them — fourteen chips on one drawing
         * is a diagram of chips rather than of a car, and most of them would be blank on
         * most cars anyway.
         */
        val DEFAULTS: Set<CarZone> = setOf(Engine, Coolant, Battery, Oil)

        /** Mode 06 reports misfires as a plain count of events. */
        const val MISFIRE_UNIT = "count"

        private val byKey: Map<String, CarZone> = entries.associateBy(CarZone::storageKey)

        fun parse(key: String): CarZone? = byKey[key]

        fun encode(zones: Set<CarZone>): String =
            entries.filter { it in zones }.joinToString(SEPARATOR, transform = CarZone::storageKey)

        fun decode(raw: String): Set<CarZone> =
            raw.split(SEPARATOR).mapNotNull(::parse).toSet()

        private const val SEPARATOR = "|"

        private fun isSupported(
            id: MetricId,
            supportedPids: Set<Int>,
            supportedExtended: Set<String>,
        ): Boolean = when (id) {
            is MetricId.Sensor -> id.pid in supportedPids
            is MetricId.Extended -> id.id in supportedExtended
            // A derived value exists once the PIDs behind it do, and the adapter's own
            // voltage is not a PID at all: both are only ever known by having arrived.
            else -> false
        }
    }
}

/**
 * The worst misfire count Mode 06 reported, with the ECU's own verdict on it.
 *
 * The count alone is not a diagnosis — a handful of misfires over ten drive cycles is a
 * cold start, not a fault — so the pass mark the ECU ships alongside every Mode 06 reading
 * is carried with it and is what decides whether the zone goes amber.
 */
data class MisfireReading(val count: Double, val passed: Boolean)

/** The worst per-cylinder misfire count of these results, or null when there are none. */
fun MonitorTests?.worstMisfire(): MisfireReading? {
    val rows = this?.misfires.orEmpty()
    val worst = rows.maxByOrNull(MonitorTest::value) ?: return null
    return MisfireReading(worst.value, rows.all(MonitorTest::passed))
}

/** One place on the drawing that has something to show, and what it is showing. */
data class CarZoneBinding(val zone: CarZone, val source: ZoneSource) {

    /** The catalogue entry behind the reading, or null for the misfire count. */
    val metric: Metric? get() = (source as? ZoneSource.Reading)?.let { Metrics[it.id] }

    /** The metric the chip opens, or null when there is no sheet to open. */
    val opens: MetricId? get() = (source as? ZoneSource.Reading)?.id

    val unit: String get() = metric?.unit ?: CarZone.MISFIRE_UNIT

    val decimals: Int get() = metric?.decimals ?: 0

    fun valueIn(snapshot: VehicleSnapshot, misfire: MisfireReading?): Double? = when (source) {
        ZoneSource.Misfires -> misfire?.count
        is ZoneSource.Reading -> snapshot.valueOf(source.id)
    }
}

/**
 * The zones the driver has asked for that this car can actually fill, in drawing order.
 *
 * Both the diagram and the card around it read this: when it comes back empty there is
 * nothing to draw, and the card collapses rather than sitting there as an empty husk.
 */
fun carZoneBindings(
    selected: Set<CarZone>,
    snapshot: VehicleSnapshot,
    supportedPids: Set<Int>,
    supportedExtended: Set<String>,
    misfire: MisfireReading?,
): List<CarZoneBinding> = CarZone.entries
    .filter { it in selected }
    .mapNotNull { zone ->
        zone.sourceOn(snapshot, supportedPids, supportedExtended, misfire)
            ?.let { CarZoneBinding(zone, it) }
    }

private fun readings(vararg ids: MetricId): List<ZoneSource> = ids.map(ZoneSource::Reading)

private fun tyre(wheel: String): List<ZoneSource> = readings(
    MetricId.Extended(ExtendedPids.tyrePressureId(wheel)),
    MetricId.Extended(ExtendedPids.tyreTemperatureId(wheel)),
)

private const val FRONT_LEFT = "fl"
private const val FRONT_RIGHT = "fr"
private const val REAR_LEFT = "rl"
private const val REAR_RIGHT = "rr"

/* The PIDs a zone falls back to, which have no name of their own in [Pids]. */
private const val FUEL_RAIL_PRESSURE = 0x22
private const val FUEL_RAIL_GAUGE_PRESSURE = 0x23
private const val ABSOLUTE_LOAD = 0x43
private const val RELATIVE_THROTTLE = 0x45
private const val ABSOLUTE_THROTTLE_B = 0x47
private const val CATALYST_TEMP_B1S1 = 0x3C
private const val OXYGEN_SENSOR_1 = 0x14
private const val WIDE_OXYGEN_SENSOR_1 = 0x24
private const val MAF_SENSOR = 0x66
private const val ECT_SENSOR = 0x67
