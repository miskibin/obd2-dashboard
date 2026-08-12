package com.miskibin.obd2dashboard.obd

import com.miskibin.obd2dashboard.obd.ExtendedBytes.byte
import com.miskibin.obd2dashboard.obd.ExtendedBytes.quad
import com.miskibin.obd2dashboard.obd.ExtendedBytes.sane
import com.miskibin.obd2dashboard.obd.ExtendedBytes.signedQuad
import com.miskibin.obd2dashboard.obd.ExtendedBytes.signedWord
import com.miskibin.obd2dashboard.obd.ExtendedBytes.triple
import com.miskibin.obd2dashboard.obd.ExtendedBytes.word
import com.miskibin.obd2dashboard.obd.ExtendedUnits.AMPERE
import com.miskibin.obd2dashboard.obd.ExtendedUnits.BAR
import com.miskibin.obd2dashboard.obd.ExtendedUnits.CELSIUS
import com.miskibin.obd2dashboard.obd.ExtendedUnits.COUNT
import com.miskibin.obd2dashboard.obd.ExtendedUnits.GRAM
import com.miskibin.obd2dashboard.obd.ExtendedUnits.KM
import com.miskibin.obd2dashboard.obd.ExtendedUnits.MILLIGRAM_PER_STROKE
import com.miskibin.obd2dashboard.obd.ExtendedUnits.MILLIMETRE
import com.miskibin.obd2dashboard.obd.ExtendedUnits.MILLIOHM
import com.miskibin.obd2dashboard.obd.ExtendedUnits.PERCENT
import com.miskibin.obd2dashboard.obd.ExtendedUnits.VOLT

/**
 * Volkswagen, Audi, Škoda, SEAT and Cupra — §4 of `docs/research-multibrand-extended-pids.md`.
 *
 * The largest table here, for the reason it is built first: the group's cars are the
 * largest slice of the Polish parc, they share one module map across every badge, and the
 * captures behind these identifiers are the richest of any marque in the research.
 *
 * Two gates apply to everything below. The first is the model year: a Golf V or an Octavia
 * II talks KWP2000 over TP2.0, which is not this protocol at all and which our adapter flow
 * cannot reach, so nothing is sent to a car built before 2009. The second is the fuel: the
 * particulate filter block and the injector corrections exist only on a diesel, and the
 * driver's profile is the only thing that says so.
 *
 * What is missing is missing on purpose. Roughly half of the group's particulate-filter
 * identifiers — soot mass at `114E`/`114F`, the surface temperature at `1044`, the
 * differential pressure at `14F5` — answer only after `10 03` puts the ECU into an extended
 * diagnostic session, and this app does not send `10` to a running car. Those readings are
 * absent rather than approximated.
 */
internal object VagPids {

    /** Engine, and everything that hangs off the engine ECU. */
    private const val ENGINE = "7E0"
    private const val ENGINE_RESPONSE = "7E8"

    /** DSG, S-tronic and the torque-converter automatics. */
    private const val GEARBOX = "7E1"
    private const val GEARBOX_RESPONSE = "7E9"

    /** The instrument cluster, which owns the odometer and a modelled oil temperature. */
    private const val CLUSTER = "714"
    private const val CLUSTER_RESPONSE = "77E"

    /**
     * The intelligent battery sensor on the negative terminal.
     *
     * The best twelve-volt telemetry of any marque in the research, and the part of this
     * table that applies to every car in the group regardless of engine, gearbox or fuel.
     */
    private const val BATTERY = "710"
    private const val BATTERY_RESPONSE = "77A"

    /** Direct tyre pressure monitoring, where it is fitted rather than derived from ABS. */
    private const val TPMS = "70B"

    /** MY2009 is the first year the group's cars answer UDS rather than TP2.0. */
    private const val FIRST_UDS_YEAR = 2009

    private fun isVag(vehicle: ExtendedVehicle) =
        vehicle.isA(Marque.VolkswagenGroup) && vehicle.builtSince(FIRST_UDS_YEAR)

    private fun isVagDiesel(vehicle: ExtendedVehicle) = isVag(vehicle) && vehicle.isDiesel

    /**
     * The cluster's oil temperature offset.
     *
     * 58, not the 60 every other marque uses and not the 40 the app's own standard PID
     * uses. That is what the captures show across seven models, and correcting it to a
     * rounder number would be inventing a reading.
     */
    private const val CLUSTER_OIL_OFFSET = 58.0

    private const val TEMPERATURE_OFFSET = 40.0
    private const val OIL_LEVEL_DIVISOR = 250.0
    private const val MILLIBAR_PER_BAR = 1000.0
    private const val HUNDREDTHS = 100.0
    private const val TENTHS = 10.0

    /** Kelvin, tenths, as the group's exhaust-side temperatures are reported. */
    private const val ABSOLUTE_ZERO = 273.1

    private const val ASH_MASS_SCALE = 0.0011921 / 10_000.0

    /** The sensor's own current scaling, and the shift that centres it on zero. */
    private const val CURRENT_SCALE = 10.0 / 10_000.0
    private const val CURRENT_OFFSET = 3000.0
    private const val THOUSANDTHS = 1000.0

    /**
     * What a car's battery current can actually be.
     *
     * Two mutually incompatible decodings of `2A09` are in circulation — a 24-bit one and a
     * 32-bit one — and the research's advice is to send both and keep whichever lands
     * somewhere a car could be. Both are shipped for exactly that: a starter draws a few
     * hundred amps for a second and an alternator gives back under two hundred, so the
     * wrong reading of the two comes out in the thousands and is thrown away here.
     */
    private val BATTERY_CURRENT_RANGE = -250.0..250.0

    /** A twelve-volt battery's internal resistance, healthy or dying, in milliohms. */
    private val BATTERY_RESISTANCE_RANGE = 2.0..30.0

    private const val RESISTANCE_PER_COUNT = 0.2

    private const val VOLTAGE_FLOOR = 4.0

    /** The four injector corrections, by cylinder, in the order the captures map them. */
    private val INJECTOR_DIDS = mapOf(1 to 0x10FF, 2 to 0x1105, 3 to 0x1100, 4 to 0x1104)

    private const val TYRE_PRESSURE_BYTE = 5
    private const val TYRE_COUNTS_PER_BAR = 40.0

    /** `18A0` is the *right* front wheel, `18A1` the left. The order is the capture's. */
    private val TYRE_DIDS = mapOf("fr" to 0x18A0, "fl" to 0x18A1, "rl" to 0x18A2, "rr" to 0x18A3)

    val entries: List<ExtendedPid> = buildList {
        addAll(cluster())
        addAll(engine())
        addAll(injectors())
        addAll(battery())
        add(gearbox())
        addAll(particulateFilter())
        addAll(tyres())
    }

    /**
     * The cluster's own readings.
     *
     * Its oil temperature is the group's most widely captured, and on an engine without a
     * real oil temperature sensor it is a modelled figure rather than a measured one — which
     * the reading's own description says, because a driver comparing it against a dipstick
     * thermometer deserves to know.
     */
    private fun cluster() = listOf(
        ExtendedPid(
            id = ExtendedPids.OIL_TEMPERATURE,
            header = CLUSTER,
            receiveHeader = CLUSTER_RESPONSE,
            did = 0x202F,
            unit = CELSIUS,
            decimals = 0,
            bytes = 1,
            tier = PidTier.Medium,
            applies = ::isVag,
            decode = { byte(it) - CLUSTER_OIL_OFFSET },
        ),
        ExtendedPid(
            id = ExtendedPids.ODOMETER,
            header = CLUSTER,
            receiveHeader = CLUSTER_RESPONSE,
            did = 0x2203,
            unit = KM,
            decimals = 0,
            bytes = 3,
            tier = PidTier.Slow,
            minIntervalMillis = ODOMETER_INTERVAL_MILLIS,
            applies = ::isVag,
            decode = { triple(it) },
        ),
    )

    private fun engine() = listOf(
        ExtendedPid(
            id = ExtendedPids.OIL_LEVEL,
            header = ENGINE,
            receiveHeader = ENGINE_RESPONSE,
            did = 0x11BA,
            unit = MILLIMETRE,
            decimals = 0,
            bytes = 2,
            tier = PidTier.Slow,
            applies = ::isVag,
            decode = { word(it) / OIL_LEVEL_DIVISOR },
        ),
        ExtendedPid(
            id = ExtendedPids.BOOST_PRESSURE,
            header = ENGINE,
            receiveHeader = ENGINE_RESPONSE,
            did = 0x1057,
            unit = BAR,
            decimals = 2,
            bytes = 2,
            tier = PidTier.Fast,
            applies = ::isVag,
            decode = { word(it) / MILLIBAR_PER_BAR },
        ),
    )

    /**
     * How far each injector's delivered quantity sits from the mean, in milligrams a stroke.
     *
     * The most useful thing a common-rail diesel will tell you and the reason this table
     * bothers with a per-cylinder family: one injector drifting while the other three sit
     * still is a rough idle explained months before it becomes a fault code.
     *
     * The identifier order is not the cylinder order. The capture maps `10FF` to cylinder 1,
     * `1105` to 2, `1100` to 3 and `1104` to 4 — the firing order 1-3-4-2 written out — and
     * tidying that into ascending identifiers would silently accuse the wrong injector.
     */
    private fun injectors() = INJECTOR_DIDS.map { (cylinder, did) ->
        ExtendedPid(
            id = ExtendedNames.injectionDeviation(cylinder),
            header = ENGINE,
            receiveHeader = ENGINE_RESPONSE,
            did = did,
            unit = MILLIGRAM_PER_STROKE,
            decimals = 2,
            bytes = 2,
            tier = PidTier.Slow,
            applies = ::isVagDiesel,
            decode = { signedWord(it) / HUNDREDTHS },
        )
    }

    private fun battery() = listOf(
        batteryPid(ExtendedPids.BATTERY_SOC, 0x2A0C, PERCENT, 0, 1) { byte(it) },
        batteryPid(ExtendedPids.BATTERY_TEMPERATURE, 0x2A0B, CELSIUS, 0, 1) {
            byte(it) - TEMPERATURE_OFFSET
        },
        batteryPid(ExtendedPids.BATTERY_VOLTAGE, 0x2A07, VOLT, 2, 2) {
            word(it) / THOUSANDTHS + VOLTAGE_FLOOR
        },
        // The two published decodings of the resistance — twenty hundredths of a count and
        // a signed fifth of one — are the same arithmetic over every value a battery can
        // produce, so there is one entry rather than two.
        batteryPid(ExtendedPids.BATTERY_RESISTANCE, 0x2A0E, MILLIOHM, 1, 1) {
            sane(byte(it) * RESISTANCE_PER_COUNT, BATTERY_RESISTANCE_RANGE)
        },
        batteryPid(ExtendedPids.BATTERY_CURRENT, 0x2A09, AMPERE, 1, 3) {
            sane(triple(it) * CURRENT_SCALE - CURRENT_OFFSET, BATTERY_CURRENT_RANGE)
        },
        batteryPid(ExtendedPids.BATTERY_CURRENT, 0x2A09, AMPERE, 1, 4) {
            sane(signedQuad(it) / THOUSANDTHS, BATTERY_CURRENT_RANGE)
        },
    )

    private fun batteryPid(
        id: String,
        did: Int,
        unit: String,
        decimals: Int,
        bytes: Int,
        decode: (IntArray) -> Double,
    ) = ExtendedPid(
        id = id,
        header = BATTERY,
        receiveHeader = BATTERY_RESPONSE,
        did = did,
        unit = unit,
        decimals = decimals,
        bytes = bytes,
        tier = PidTier.Slow,
        applies = ::isVag,
        decode = decode,
    )

    /**
     * The gearbox fluid temperature, gated to a range a gearbox can be in.
     *
     * Some S-tronic and DSG units answer `2104` with a signed sixteen-bit value instead of
     * the byte the rest use. Read as a byte, that encoding hands back its high byte, which
     * is zero at every temperature a gearbox reaches — so it reads exactly −40 °C forever,
     * and the range excludes exactly −40 to catch it.
     */
    private fun gearbox() = ExtendedPid(
        id = ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE,
        header = GEARBOX,
        receiveHeader = GEARBOX_RESPONSE,
        did = 0x2104,
        unit = CELSIUS,
        decimals = 0,
        bytes = 1,
        tier = PidTier.Slow,
        applies = ::isVag,
        decode = { sane(byte(it) - TEMPERATURE_OFFSET, ExtendedBytes.GEARBOX_TEMPERATURE) },
    )

    /**
     * The particulate filter, on the identifiers that answer without a session change.
     *
     * Soot measured against soot calculated is the pair worth having: the first is what the
     * differential pressure sensor sees, the second what the ECU's model believes, and a
     * lasting gap between them is a filter or a sensor that has stopped telling the truth.
     */
    private fun particulateFilter() = listOf(
        dieselPid(ExtendedPids.DPF_SOOT_MEASURED, 0x1ABE, GRAM, 2, 2) {
            signedWord(it) / HUNDREDTHS
        },
        dieselPid(ExtendedPids.DPF_SOOT_CALCULATED, 0x2609, GRAM, 2, 2) {
            signedWord(it) / HUNDREDTHS
        },
        dieselPid(ExtendedPids.DPF_ASH_MASS, 0x1ABD, GRAM, 1, 4) { quad(it) * ASH_MASS_SCALE },
        dieselPid(ExtendedPids.DPF_DISTANCE_SINCE_REGEN, 0x1ABA, KM, 0, 2) { word(it) / TENTHS },
        dieselPid(ExtendedPids.DPF_REGEN_INTERRUPTIONS, 0x1AC9, COUNT, 0, 1) { byte(it) },
        dieselPid(ExtendedPids.DPF_INLET_TEMPERATURE, 0x11B2, CELSIUS, 0, 2) {
            word(it) / TENTHS - ABSOLUTE_ZERO
        },
        dieselPid(ExtendedPids.DPF_OUTLET_TEMPERATURE, 0x10F9, CELSIUS, 0, 2) {
            word(it) / TENTHS - ABSOLUTE_ZERO
        },
    )

    private fun dieselPid(
        id: String,
        did: Int,
        unit: String,
        decimals: Int,
        bytes: Int,
        decode: (IntArray) -> Double,
    ) = ExtendedPid(
        id = id,
        header = ENGINE,
        receiveHeader = ENGINE_RESPONSE,
        did = did,
        unit = unit,
        decimals = decimals,
        bytes = bytes,
        tier = PidTier.Slow,
        applies = ::isVagDiesel,
        decode = decode,
    )

    /**
     * The four tyre pressures, from the direct sensors where they are fitted.
     *
     * A long answer with the pressure six bytes in, so the adapter's flow control has to be
     * configured or only the first frame comes back. Cars with indirect monitoring — the
     * ABS-derived kind, which is most of the group's smaller models — refuse the identifier
     * outright, and that refusal is remembered.
     */
    private fun tyres() = TYRE_DIDS.map { (wheel, did) ->
        ExtendedPid(
            id = ExtendedNames.tyrePressure(wheel),
            header = TPMS,
            did = did,
            unit = BAR,
            decimals = 2,
            bytes = TYRE_PRESSURE_BYTE + 1,
            tier = PidTier.Slow,
            minIntervalMillis = MazdaPids.TYRE_INTERVAL_MILLIS,
            flowControl = true,
            applies = ::isVag,
            decode = {
                sane(
                    byte(it, TYRE_PRESSURE_BYTE) / TYRE_COUNTS_PER_BAR,
                    ExtendedBytes.TYRE_PRESSURE,
                )
            },
        )
    }

    /** An odometer that has moved within a minute is a car that has gone half a mile. */
    const val ODOMETER_INTERVAL_MILLIS = 60_000L
}
