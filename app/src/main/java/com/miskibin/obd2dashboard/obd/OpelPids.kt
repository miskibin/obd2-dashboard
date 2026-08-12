package com.miskibin.obd2dashboard.obd

import com.miskibin.obd2dashboard.obd.ExtendedBytes.byte
import com.miskibin.obd2dashboard.obd.ExtendedBytes.quad
import com.miskibin.obd2dashboard.obd.ExtendedBytes.sane
import com.miskibin.obd2dashboard.obd.ExtendedBytes.signedByte
import com.miskibin.obd2dashboard.obd.ExtendedBytes.word
import com.miskibin.obd2dashboard.obd.ExtendedUnits.CELSIUS
import com.miskibin.obd2dashboard.obd.ExtendedUnits.KILOWATT_HOUR
import com.miskibin.obd2dashboard.obd.ExtendedUnits.KM
import com.miskibin.obd2dashboard.obd.ExtendedUnits.KPA
import com.miskibin.obd2dashboard.obd.ExtendedUnits.PERCENT
import com.miskibin.obd2dashboard.obd.ExtendedUnits.VOLT

/**
 * Opel and Vauxhall — §11 of `docs/research-multibrand-extended-pids.md`.
 *
 * Two cars under one badge, and the split is the whole point of this table. A `W0L` car is
 * an Astra J or K that General Motors built, with a GM engine controller answering `22 3xxx`
 * on `7E0`; a `W0V` car is a Corsa F or a Mokka B that Stellantis builds on PSA
 * underpinnings, where the addresses are `6xx`, the identifiers are `D4xx` and `D8xx`, and
 * not one row from the other era answers. The two sets below share no identifier and no
 * module, and a VIN can only ever match one of them.
 *
 * The GM rows are the only ones in this file that are not from a captured signal set — they
 * come from two community lists that agree with each other, and their descriptions say so.
 * What did not survive that standard is the soot accumulation at `22 336A`: it is published
 * both as a percentage of 255 and as a plain percentage, both readings land inside 0–100,
 * and nothing in the answer says which car you are on. A soot load that is wrong by a factor
 * of two and a half is worse than none.
 *
 * The PSA-era rows are a battery-electric Corsa's, which is all OBDb has for the platform.
 * Nothing is verified for the PSA-era petrol and diesel cars — no oil temperature, no
 * filter, no tyres — so nothing is offered for them.
 */
internal object OpelPids {

    /** The General Motors engine controller. */
    private const val GM_ENGINE = "7E0"
    private const val GM_ENGINE_RESPONSE = "7E8"

    /** The PSA-era battery management computer. */
    private const val PSA_BATTERY = "6B4"
    private const val PSA_BATTERY_RESPONSE = "694"

    private const val PERCENT_PER_COUNT = 100.0 / 255.0

    /** The GM filter's inlet temperature, in tenths of a degree above −40. */
    private const val TENTHS = 0.1
    private const val TEMPERATURE_OFFSET = 40.0

    /** The PSA battery computer's scalings: 512ths of a percent, 16ths of a volt. */
    private const val SOC_DIVISOR = 512.0
    private const val VOLTAGE_DIVISOR = 16.0
    private const val ENERGY_DIVISOR = 64.0
    private const val METRES_PER_KM = 1000.0

    /** The health percentage sits second and third in the answer, not first. */
    private const val HEALTH_BYTE = 1

    private val PACK_VOLTAGE_RANGE = 0.0..600.0

    private fun isGmOpel(vehicle: ExtendedVehicle) = vehicle.isA(Marque.OpelGm)

    private fun isGmOpelDiesel(vehicle: ExtendedVehicle) = isGmOpel(vehicle) && vehicle.isDiesel

    private fun isPsaOpel(vehicle: ExtendedVehicle) = vehicle.isA(Marque.OpelPsa)

    val entries: List<ExtendedPid> = generalMotors() + stellantis()

    /**
     * The 1.6 CDTI's filter, its exhaust gas valve and its turbo vanes.
     *
     * All diesel-gated, because that is the engine the community lists were written for and
     * the only one the readings mean anything on.
     */
    private fun generalMotors() = listOf(
        gm(ExtendedPids.DPF_DISTANCE_SINCE_REGEN, 0x3039, KM, 0, 2) { word(it) },
        gm(ExtendedPids.DPF_PRESSURE_DIFFERENCE, 0x20F4, KPA, 0, 1) { signedByte(it) },
        gm(ExtendedPids.DPF_INLET_TEMPERATURE, 0x20F8, CELSIUS, 0, 2) {
            word(it) * TENTHS - TEMPERATURE_OFFSET
        },
        gm(ExtendedPids.EGR_POSITION, 0x1152, PERCENT, 0, 1) { byte(it) * PERCENT_PER_COUNT },
        gm(ExtendedPids.TURBO_VANE_POSITION, 0x1543, PERCENT, 0, 1) {
            byte(it) * PERCENT_PER_COUNT
        },
    )

    private fun gm(
        id: String,
        did: Int,
        unit: String,
        decimals: Int,
        bytes: Int,
        decode: (IntArray) -> Double,
    ) = ExtendedPid(
        id = id,
        header = GM_ENGINE,
        receiveHeader = GM_ENGINE_RESPONSE,
        did = did,
        unit = unit,
        decimals = decimals,
        bytes = bytes,
        tier = PidTier.Slow,
        applies = ::isGmOpelDiesel,
        decode = decode,
    )

    /** The Corsa-e's traction battery, and the odometer that sits on the same module. */
    private fun stellantis() = listOf(
        psa(ExtendedPids.HV_SOC, 0xD410, PERCENT, 1, 2) {
            sane(word(it) / SOC_DIVISOR, ExtendedBytes.PERCENTAGE)
        },
        psa(ExtendedPids.HV_VOLTAGE, 0xD815, VOLT, 1, 2) {
            sane(word(it) / VOLTAGE_DIVISOR, PACK_VOLTAGE_RANGE)
        },
        psa(ExtendedPids.HV_HEALTH, 0xD860, PERCENT, 1, HEALTH_BYTE + 2) {
            sane(word(it, HEALTH_BYTE) / VOLTAGE_DIVISOR, ExtendedBytes.PERCENTAGE)
        },
        psa(ExtendedPids.HV_ENERGY, 0xD865, KILOWATT_HOUR, 1, 2) { word(it) / ENERGY_DIVISOR },
        psa(ExtendedPids.ODOMETER, 0xD49C, KM, 0, 4) { quad(it) / METRES_PER_KM },
    )

    private fun psa(
        id: String,
        did: Int,
        unit: String,
        decimals: Int,
        bytes: Int,
        decode: (IntArray) -> Double,
    ) = ExtendedPid(
        id = id,
        header = PSA_BATTERY,
        receiveHeader = PSA_BATTERY_RESPONSE,
        did = did,
        unit = unit,
        decimals = decimals,
        bytes = bytes,
        tier = PidTier.Slow,
        flowControl = true,
        applies = ::isPsaOpel,
        decode = decode,
    )
}
