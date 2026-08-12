package com.miskibin.obd2dashboard.obd

import com.miskibin.obd2dashboard.obd.ExtendedBytes.barFromPsi
import com.miskibin.obd2dashboard.obd.ExtendedBytes.byte
import com.miskibin.obd2dashboard.obd.ExtendedBytes.sane
import com.miskibin.obd2dashboard.obd.ExtendedBytes.signedWord
import com.miskibin.obd2dashboard.obd.ExtendedBytes.word
import com.miskibin.obd2dashboard.obd.ExtendedUnits.BAR
import com.miskibin.obd2dashboard.obd.ExtendedUnits.CELSIUS
import com.miskibin.obd2dashboard.obd.ExtendedUnits.KPA
import com.miskibin.obd2dashboard.obd.ExtendedUnits.PERCENT

/**
 * Ford of Europe — §5 of `docs/research-multibrand-extended-pids.md`.
 *
 * The cheapest table in the set to be sure of, because it is half a table the app already
 * had: Ford and Mazda shared a powertrain control module for years and the identifiers went
 * with it, so `22 0415` is oil pressure and `22 1E1C` a gearbox temperature on a Focus for
 * the same reason they are on a Mazda 3. Those two rows are the Mazda rows with the badge
 * gate changed and one difference that matters, below.
 *
 * The particulate filter is not here. OBDb has no captured DPF signal for any Ford, and the
 * only public lead — `22 0579` and `22 057B` on a 2.0 TDCi — is a single forum post with no
 * second source. A wrong soot load is worse than no soot load, so it is left out.
 */
internal object FordPids {

    /** The powertrain control module. */
    private const val PCM = "7E0"
    private const val PCM_RESPONSE = "7E8"

    /** The body module, which owns the tyre pressures and the battery monitor. */
    private const val BODY = "726"
    private const val BODY_RESPONSE = "72E"

    /** Ford's European cars answer UDS from about 2009, the same cut-off the group's do. */
    private const val FIRST_UDS_YEAR = 2009

    /** `0xFFFF` in the oil pressure identifier is the sensor saying it has nothing. */
    private const val INVALID_WORD = 0xFFFF

    private const val OIL_TEMPERATURE_DIVISOR = 100.0
    private const val TEMPERATURE_OFFSET = 40.0

    /**
     * Where a Ford oil temperature can plausibly be.
     *
     * The scaling `22 1310` is read with here is Mazda's, verified on Mazda and not on a
     * Ford — the research found the identifier on a Ford Edge with its scaling unrecorded.
     * Shipping it gated means a Ford that turns out to answer in whole degrees produces
     * something impossible and therefore shows nothing, rather than showing 190 °C to
     * somebody with a perfectly healthy engine.
     */
    private val OIL_TEMPERATURE_RANGE = -40.0..150.0

    /** Sixteenths of a degree, signed on some models and unsigned on others. */
    private const val GEARBOX_DIVISOR = 16.0

    /** Sixty-fourths of a degree, which is how the PCM reports its own temperatures. */
    private const val SIXTY_FOURTHS = 64.0

    private const val WASTEGATE_FULL_SCALE = 32_768.0
    private const val PERCENT_FULL = 100.0

    /** Throttle inlet pressure, in psi: thirty-seven parts in 33 441 of a count. */
    private const val BOOST_NUMERATOR = 37.0
    private const val BOOST_DENOMINATOR = 33_441.0

    private const val TYRE_COUNTS_PER_PSI = 20.0

    /** `2813` onwards, in the order the captures record: front pair, then the rear outers. */
    private val TYRE_DIDS = mapOf("fl" to 0x2813, "fr" to 0x2814, "rr" to 0x2815, "rl" to 0x2816)

    private fun isFord(vehicle: ExtendedVehicle) =
        vehicle.isA(Marque.Ford) && vehicle.builtSince(FIRST_UDS_YEAR)

    val entries: List<ExtendedPid> = buildList {
        addAll(powertrain())
        add(batteryTemperature())
        addAll(tyres())
    }

    private fun powertrain() = listOf(
        pcm(ExtendedPids.OIL_PRESSURE, 0x0415, KPA, 0, 2, PidTier.Medium) {
            if (word(it) == INVALID_WORD.toDouble()) NOT_USED else signedWord(it)
        },
        pcm(ExtendedPids.OIL_TEMPERATURE, 0x1310, CELSIUS, 0, 2, PidTier.Medium) {
            sane(word(it) / OIL_TEMPERATURE_DIVISOR - TEMPERATURE_OFFSET, OIL_TEMPERATURE_RANGE)
        },
        // Sixteenths here against Mazda's eightieths, signed against unsigned by model, and
        // the same range gate settles all of it: the wrong reading of a warm gearbox is
        // either five times too high or two thousand degrees, and neither is in range.
        pcm(ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE, 0x1E1C, CELSIUS, 0, 2, PidTier.Slow) {
            sane(signedWord(it) / GEARBOX_DIVISOR, ExtendedBytes.GEARBOX_TEMPERATURE)
        },
        pcm(ExtendedPids.CHARGE_AIR_TEMPERATURE, 0x0461, CELSIUS, 0, 2, PidTier.Medium) {
            signedWord(it) / SIXTY_FOURTHS - TEMPERATURE_OFFSET
        },
        pcm(ExtendedPids.CYLINDER_HEAD_TEMPERATURE, 0x0334, CELSIUS, 0, 2, PidTier.Medium) {
            signedWord(it) / SIXTY_FOURTHS
        },
        pcm(ExtendedPids.WASTEGATE_DUTY, 0x0462, PERCENT, 0, 2, PidTier.Fast) {
            word(it) * PERCENT_FULL / WASTEGATE_FULL_SCALE
        },
        pcm(ExtendedPids.BOOST_PRESSURE, 0x033E, BAR, 2, 2, PidTier.Fast) {
            barFromPsi(word(it) * BOOST_NUMERATOR / BOOST_DENOMINATOR)
        },
    )

    private fun pcm(
        id: String,
        did: Int,
        unit: String,
        decimals: Int,
        bytes: Int,
        tier: PidTier,
        decode: (IntArray) -> Double,
    ) = ExtendedPid(
        id = id,
        header = PCM,
        receiveHeader = PCM_RESPONSE,
        did = did,
        unit = unit,
        decimals = decimals,
        bytes = bytes,
        tier = tier,
        applies = ::isFord,
        decode = decode,
    )

    /**
     * The battery monitor's temperature, and not its state of charge.
     *
     * The charge identifier `22 4028` has two published readings — a percentage of 255 on
     * one model and a plain percentage on another — and unlike a temperature or a pressure
     * both land inside the range a battery can be in. Nothing in the answer distinguishes
     * them, so it is not shipped.
     */
    private fun batteryTemperature() = ExtendedPid(
        id = ExtendedPids.BATTERY_TEMPERATURE,
        header = BODY,
        receiveHeader = BODY_RESPONSE,
        did = 0x4029,
        unit = CELSIUS,
        decimals = 0,
        bytes = 1,
        tier = PidTier.Slow,
        applies = ::isFord,
        decode = { byte(it) - TEMPERATURE_OFFSET },
    )

    private fun tyres() = TYRE_DIDS.map { (wheel, did) ->
        ExtendedPid(
            id = ExtendedNames.tyrePressure(wheel),
            header = BODY,
            receiveHeader = BODY_RESPONSE,
            did = did,
            unit = BAR,
            decimals = 2,
            bytes = 2,
            tier = PidTier.Slow,
            minIntervalMillis = MazdaPids.TYRE_INTERVAL_MILLIS,
            applies = ::isFord,
            decode = {
                sane(barFromPsi(word(it) / TYRE_COUNTS_PER_PSI), ExtendedBytes.TYRE_PRESSURE)
            },
        )
    }
}
