package com.miskibin.obd2dashboard.obd

import com.miskibin.obd2dashboard.obd.ExtendedBytes.quad
import com.miskibin.obd2dashboard.obd.ExtendedBytes.sane
import com.miskibin.obd2dashboard.obd.ExtendedBytes.word
import com.miskibin.obd2dashboard.obd.ExtendedUnits.BAR
import com.miskibin.obd2dashboard.obd.ExtendedUnits.CELSIUS
import com.miskibin.obd2dashboard.obd.ExtendedUnits.KM
import com.miskibin.obd2dashboard.obd.ExtendedUnits.NEWTON_METRE
import com.miskibin.obd2dashboard.obd.ExtendedUnits.VOLT
import com.miskibin.obd2dashboard.obd.ExtendedUnits.WATT

/**
 * Renault and Dacia — §10 of `docs/research-multibrand-extended-pids.md`.
 *
 * A small table with an idiom of its own: Renault control units report temperatures in
 * tenths of a kelvin and pressures in millibar, so every formula here ends in a subtraction
 * of 273 or a division by a thousand. Both marques share the engine ECU, which is why one
 * table covers a Mégane and a Duster.
 *
 * The oil temperature is a modelled figure rather than a measured one — the capture calls
 * it "estimated" and so does the reading's description. The 1.6 dCi's own identifier
 * `22 111F`, which is a real sensor, is shipped for Nissan instead: it is the same engine
 * in both cars, but only one of the two has nothing better, and offering a marque two
 * identifiers for one reading would mean asking both and publishing whichever answered
 * last.
 *
 * Nothing about the particulate filter is here. Renault's dCi filter data lives in CLIP and
 * has never been published as identifiers with formulas, and the gearbox temperature that
 * has been published needs a proprietary `10 C0` session this app will not send.
 */
internal object RenaultPids {

    private const val ENGINE = "7E0"
    private const val ENGINE_RESPONSE = "7E8"

    /** The ABS and stability module, which holds the odometer the cluster shows. */
    private const val CHASSIS = "740"
    private const val CHASSIS_RESPONSE = "760"

    private const val FIRST_UDS_YEAR = 2010

    /** Tenths of a kelvin, which is how everything thermal is reported. */
    private const val TENTHS = 10.0
    private const val ABSOLUTE_ZERO = 273.0

    private const val MILLIBAR_PER_BAR = 1000.0
    private const val HUNDREDTHS = 100.0

    /** Torque, in thirty-seconds of a newton metre, offset to allow for engine braking. */
    private const val TORQUE_DIVISOR = 32.0
    private const val TORQUE_OFFSET = 400.0

    private const val ALTERNATOR_SCALE = 10.0

    private val OIL_TEMPERATURE_RANGE = -40.0..180.0
    private val BOOST_RANGE = 0.0..5.0
    private val REFRIGERANT_RANGE = 0.0..40.0

    private fun isRenault(vehicle: ExtendedVehicle) =
        vehicle.isA(Marque.RenaultDacia) && vehicle.builtSince(FIRST_UDS_YEAR)

    val entries: List<ExtendedPid> = listOf(
        engine(ExtendedPids.OIL_TEMPERATURE, 0x2007, CELSIUS, 0, PidTier.Medium) {
            sane(word(it) / TENTHS - ABSOLUTE_ZERO, OIL_TEMPERATURE_RANGE)
        },
        engine(ExtendedPids.BOOST_PRESSURE, 0x2401, BAR, 2, PidTier.Fast) {
            sane(word(it) / MILLIBAR_PER_BAR, BOOST_RANGE)
        },
        engine(ExtendedPids.ENGINE_TORQUE, 0x2004, NEWTON_METRE, 0, PidTier.Fast) {
            word(it) / TORQUE_DIVISOR - TORQUE_OFFSET
        },
        engine(ExtendedPids.BATTERY_VOLTAGE, 0x2005, VOLT, 2, PidTier.Slow) {
            sane(word(it) / HUNDREDTHS, ExtendedBytes.LOW_VOLTAGE)
        },
        engine(ExtendedPids.ALTERNATOR_POWER, 0x2057, WATT, 0, PidTier.Medium) {
            word(it) * ALTERNATOR_SCALE
        },
        engine(ExtendedPids.AC_PRESSURE, 0x222A, BAR, 1, PidTier.Slow) {
            sane(word(it) / TENTHS, REFRIGERANT_RANGE)
        },
        ExtendedPid(
            id = ExtendedPids.ODOMETER,
            header = CHASSIS,
            receiveHeader = CHASSIS_RESPONSE,
            did = 0x4B06,
            unit = KM,
            decimals = 0,
            bytes = 4,
            tier = PidTier.Slow,
            minIntervalMillis = VagPids.ODOMETER_INTERVAL_MILLIS,
            flowControl = true,
            applies = ::isRenault,
            decode = { quad(it) },
        ),
    )

    private fun engine(
        id: String,
        did: Int,
        unit: String,
        decimals: Int,
        tier: PidTier,
        decode: (IntArray) -> Double,
    ) = ExtendedPid(
        id = id,
        header = ENGINE,
        receiveHeader = ENGINE_RESPONSE,
        did = did,
        unit = unit,
        decimals = decimals,
        bytes = 2,
        tier = tier,
        applies = ::isRenault,
        decode = decode,
    )
}
