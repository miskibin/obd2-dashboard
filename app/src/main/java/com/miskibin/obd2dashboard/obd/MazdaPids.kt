package com.miskibin.obd2dashboard.obd

import com.miskibin.obd2dashboard.obd.ExtendedBytes.barFromPsi
import com.miskibin.obd2dashboard.obd.ExtendedBytes.sane
import com.miskibin.obd2dashboard.obd.ExtendedBytes.signedWord
import com.miskibin.obd2dashboard.obd.ExtendedBytes.word
import com.miskibin.obd2dashboard.obd.ExtendedUnits.BAR
import com.miskibin.obd2dashboard.obd.ExtendedUnits.CELSIUS
import com.miskibin.obd2dashboard.obd.ExtendedUnits.KPA

/**
 * Mazda, from `docs/research-mazda-extended-pids.md`.
 *
 * The oldest table here and the one the rest are shaped after. Its engine identifiers are
 * not Mazda's own invention: `22 0415` and `22 1E1C` are Ford identifiers, from the years
 * the two companies shared a powertrain control module, and they answer on a Focus for
 * exactly that reason — see [FordPids].
 */
internal object MazdaPids {

    /** Mazda's PCM, which answers on `7E8` like any engine ECU. */
    private const val PCM = "7E0"

    /** The transmission module. */
    private const val TCM = "7E1"

    /** The body module of a 2019-on Mazda 3 (BP), which carries its tyre pressures. */
    private const val BODY_BP = "726"
    private const val BODY_BP_RESPONSE = "72E"

    /** The same job on the 2014-2018 car (BM/BN), at a different address entirely. */
    private const val BODY_BM = "720"
    private const val BODY_BM_RESPONSE = "728"

    /** The BP generation of the Mazda 3, which is the one with tyre pressures on `726`. */
    val BP_YEARS = 2019..2030

    /** The BM/BN generation before it. */
    val BM_YEARS = 2014..2018

    /** `0xFFFF` in the oil pressure DID is the sensor saying it has nothing, not 65 535 kPa. */
    private const val INVALID_WORD = 0xFFFF

    private const val OIL_TEMPERATURE_DIVISOR = 100.0
    private const val TEMPERATURE_OFFSET = 40.0

    /**
     * The transmission temperature divisor.
     *
     * Published lists disagree between this and 16, which would put a cold gearbox at
     * 400 °C. The reading is therefore gated to a range a gearbox can actually be in, so a
     * car that turns out to use the other scaling shows nothing rather than something
     * alarming and wrong.
     */
    private const val FLUID_TEMPERATURE_DIVISOR = 80.0
    private val FLUID_TEMPERATURE_RANGE = -40.0..160.0

    /** Tyre pressure counts, both generations, converted to the unit the rest of the app uses. */
    private const val PSI_PER_COUNT = 0.2
    private const val BAR_PER_COUNT = 1373.0 / 100_000.0

    /** Tyre temperature is reported with a 50-degree offset rather than the usual 40. */
    private const val TYRE_TEMPERATURE_OFFSET = 50.0

    /** A tyre pressure asked for oftener than this is a request spent on a number that has not moved. */
    const val TYRE_INTERVAL_MILLIS = 15_000L

    private fun isMazda(vehicle: ExtendedVehicle) = vehicle.isMazda

    val entries: List<ExtendedPid> = buildList {
        add(
            ExtendedPid(
                id = ExtendedPids.OIL_PRESSURE,
                header = PCM,
                did = 0x0415,
                unit = KPA,
                decimals = 0,
                bytes = 2,
                tier = PidTier.Medium,
                applies = ::isMazda,
                decode = { if (word(it) == INVALID_WORD.toDouble()) NOT_USED else signedWord(it) },
            ),
        )
        add(
            ExtendedPid(
                id = ExtendedPids.OIL_TEMPERATURE,
                header = PCM,
                did = 0x1310,
                unit = CELSIUS,
                decimals = 0,
                bytes = 2,
                tier = PidTier.Medium,
                applies = ::isMazda,
                decode = { word(it) / OIL_TEMPERATURE_DIVISOR - TEMPERATURE_OFFSET },
            ),
        )
        add(
            ExtendedPid(
                id = ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE,
                header = TCM,
                did = 0x1E1C,
                unit = CELSIUS,
                decimals = 0,
                bytes = 2,
                tier = PidTier.Slow,
                applies = ::isMazda,
                decode = { sane(word(it) / FLUID_TEMPERATURE_DIVISOR, FLUID_TEMPERATURE_RANGE) },
            ),
        )
        ExtendedNames.WHEELS.forEachIndexed { index, wheel ->
            add(tyrePressure(wheel, BODY_BP, BODY_BP_RESPONSE, 0xD922 + index, BP_YEARS) {
                barFromPsi(it[0] * PSI_PER_COUNT)
            })
            add(tyreTemperature(wheel, BODY_BP, BODY_BP_RESPONSE, 0xD926 + index, BP_YEARS))
            add(tyrePressure(wheel, BODY_BM, BODY_BM_RESPONSE, 0x2A05 + index, BM_YEARS) {
                it[0] * BAR_PER_COUNT
            })
            add(tyreTemperature(wheel, BODY_BM, BODY_BM_RESPONSE, 0x2A0A + index, BM_YEARS))
        }
    }

    private fun tyrePressure(
        wheel: String,
        header: String,
        response: String,
        did: Int,
        years: IntRange,
        decode: (IntArray) -> Double,
    ) = ExtendedPid(
        id = ExtendedNames.tyrePressure(wheel),
        header = header,
        receiveHeader = response,
        did = did,
        unit = BAR,
        decimals = 2,
        bytes = 1,
        tier = PidTier.Slow,
        minIntervalMillis = TYRE_INTERVAL_MILLIS,
        applies = { it.isMazda && it.builtIn(years) },
        decode = decode,
    )

    private fun tyreTemperature(
        wheel: String,
        header: String,
        response: String,
        did: Int,
        years: IntRange,
    ) = ExtendedPid(
        id = ExtendedNames.tyreTemperature(wheel),
        header = header,
        receiveHeader = response,
        did = did,
        unit = CELSIUS,
        decimals = 0,
        bytes = 1,
        tier = PidTier.Slow,
        minIntervalMillis = TYRE_INTERVAL_MILLIS,
        applies = { it.isMazda && it.builtIn(years) },
        decode = { it[0] - TYRE_TEMPERATURE_OFFSET },
    )
}
