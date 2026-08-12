package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.FuelType

/**
 * The car itself, as opposed to the dongle plugged into it.
 *
 * Everything here is a *constant* of the vehicle: none of it changes while driving, none of
 * it is on the OBD bus, and all of it is what turns a reading into a judgement. The app is
 * not short of numbers — it reads forty of them ten times a second — it is short of the
 * handful of facts that say what those numbers mean. Air flow is only fuel flow once the
 * fuel is known; a tank percentage is only a range once the tank's size is; a rate of
 * acceleration is only power once the mass is.
 *
 * Keyed by VIN rather than by adapter, because the VIN is the only identity that belongs to
 * the car. One dongle moved between two cars is two profiles, which is right; two dongles in
 * one car is one profile, which is also right.
 *
 * Every field but the VIN is nullable and means "the driver has not said". [fuel] in
 * particular is not defaulted here: the fuel maths falls back to [FuelType.Default] on its
 * own, and keeping the difference visible is what lets the screen ask instead of pretending
 * a car it knows nothing about runs on petrol.
 */
data class Vehicle(
    val vin: String,
    val name: String? = null,
    val fuel: FuelType? = null,
    val displacementLitres: Double? = null,
    val ratedPowerKw: Int? = null,
    val kerbMassKg: Int? = null,
    val tankLitres: Int? = null,
) {
    /** True while the driver has filled in nothing at all. */
    val isBlank: Boolean
        get() = name == null && fuel == null && displacementLitres == null &&
            ratedPowerKw == null && kerbMassKg == null && tankLitres == null

    /** What the app calls this car when it has to call it something short. */
    fun label(facts: VinFacts?): String? =
        name?.takeIf(String::isNotBlank) ?: facts?.manufacturer
}

/**
 * Every car the app has been plugged into, stored as one line each.
 *
 * The same flat encoding the rest of the preferences use: records joined by [SEPARATOR],
 * fields by [FIELD], absent values as an empty field. A serialisation dependency would buy
 * nothing for seven scalars, and the format survives a field being appended — a shorter
 * record is read as far as it goes and the rest stays unset.
 */
object Garage {

    /**
     * Which store a session's cars belong in.
     *
     * The simulation reports a VIN like any other car, and it is a plausible-looking one.
     * Left in the same store it would put a car nobody owns into the driver's garage, and a
     * profile edited while looking at the demo would be saved against it — so demo cars are
     * filed under their own key, the way demo trips and demo code sightings already are.
     * [SessionKind.Real] keeps the plain key.
     */
    fun storageKey(kind: SessionKind): String = when (kind) {
        SessionKind.Real -> "vehicles"
        SessionKind.Demo -> "vehicles_demo"
    }

    fun find(vehicles: List<Vehicle>, vin: String?): Vehicle? =
        vin?.let { wanted -> vehicles.firstOrNull { it.vin == wanted } }

    /** The stored profile, or a blank one to fill in, for whichever car is plugged in. */
    fun profileFor(vehicles: List<Vehicle>, vin: String?): Vehicle? =
        vin?.let { find(vehicles, it) ?: Vehicle(vin = it) }

    /** Replaces the profile for this VIN, or appends it, keeping the rest untouched. */
    fun merge(vehicles: List<Vehicle>, vehicle: Vehicle): List<Vehicle> =
        if (vehicles.none { it.vin == vehicle.vin }) {
            vehicles + vehicle
        } else {
            vehicles.map { if (it.vin == vehicle.vin) vehicle else it }
        }

    fun encode(vehicles: List<Vehicle>): String = vehicles.joinToString(SEPARATOR) { vehicle ->
        listOf(
            sanitise(vehicle.vin),
            vehicle.name?.let(::sanitise).orEmpty(),
            vehicle.fuel?.storageKey.orEmpty(),
            vehicle.displacementLitres?.toString().orEmpty(),
            vehicle.ratedPowerKw?.toString().orEmpty(),
            vehicle.kerbMassKg?.toString().orEmpty(),
            vehicle.tankLitres?.toString().orEmpty(),
        ).joinToString(FIELD)
    }

    fun decode(raw: String?): List<Vehicle> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEPARATOR).mapNotNull { entry ->
            val parts = entry.split(FIELD)
            val vin = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            Vehicle(
                vin = vin,
                name = parts.getOrNull(1)?.takeIf(String::isNotBlank),
                fuel = FuelType.fromKey(parts.getOrNull(2)),
                displacementLitres = parts.getOrNull(3)?.toDoubleOrNull(),
                ratedPowerKw = parts.getOrNull(4)?.toIntOrNull(),
                kerbMassKg = parts.getOrNull(5)?.toIntOrNull(),
                tankLitres = parts.getOrNull(6)?.toIntOrNull(),
            )
        }
    }

    /**
     * Nothing written into a field may be able to end its own record.
     *
     * This guards the VIN as well as the name, and the VIN is the one that needs it: it is
     * not typed but reassembled from raw ECU bytes and mapped straight to characters, so a
     * garbled `0902` reply can put a separator anywhere in it. A name is merely free text.
     */
    private fun sanitise(value: String): String =
        value.filterNot { it.toString() == SEPARATOR || it.toString() == FIELD }.trim()

    private const val SEPARATOR = "|"
    private const val FIELD = ":"
}
