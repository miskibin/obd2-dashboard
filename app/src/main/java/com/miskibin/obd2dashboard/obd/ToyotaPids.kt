package com.miskibin.obd2dashboard.obd

import com.miskibin.obd2dashboard.obd.ExtendedBytes.byte
import com.miskibin.obd2dashboard.obd.ExtendedBytes.sane
import com.miskibin.obd2dashboard.obd.ExtendedBytes.signedWord
import com.miskibin.obd2dashboard.obd.ExtendedBytes.word
import com.miskibin.obd2dashboard.obd.ExtendedUnits.AMPERE
import com.miskibin.obd2dashboard.obd.ExtendedUnits.CELSIUS
import com.miskibin.obd2dashboard.obd.ExtendedUnits.PERCENT
import com.miskibin.obd2dashboard.obd.ExtendedUnits.VOLT

/**
 * Toyota and Lexus — §6 of `docs/research-multibrand-extended-pids.md`.
 *
 * Denso control units answer two services, and the older one is the better bargain. Service
 * `21` takes a one-byte identifier and answers with a block forty to seventy bytes long
 * with a different reading at each offset, it works back to about 2004, and it is what a
 * 2009 Corolla will tell you about its oil when service `22` has never heard of the car.
 * So the engine and gearbox readings here are `21` and only the newer modules are asked in
 * `22`.
 *
 * One reading per request even so. A block that carries eight numbers is read eight times
 * if eight are wanted, because the scheduler's unit of work is a parameter and teaching it
 * to slice one answer into several was not worth doing for the two readings taken from each
 * block here.
 *
 * The hybrid block is gated by the probe rather than by the VIN: nothing in a European
 * Toyota VIN says whether the car is a hybrid, the inverter module simply does not answer
 * on a car that has no inverter, and a silence costs one request and is not remembered as a
 * refusal.
 */
internal object ToyotaPids {

    /** The engine ECU, in the service that predates UDS. */
    private const val ENGINE = "7E0"
    private const val ENGINE_RESPONSE = "7E8"

    /** The same engine, at its UDS address, which the MY2016-on cars answer. */
    private const val ENGINE_UDS = "700"
    private const val ENGINE_UDS_RESPONSE = "708"

    /** Inverter and hybrid battery. */
    private const val HYBRID = "7D2"
    private const val HYBRID_RESPONSE = "7DA"

    /** Service `22` on a Toyota is generally a MY2016-and-later affair. */
    private const val FIRST_UDS_YEAR = 2016

    /** `21 51`: the tenth byte of the block is the oil temperature. */
    private const val OIL_TEMPERATURE_BYTE = 9

    private const val TEMPERATURE_OFFSET = 40.0

    /** `21 82`: the gearbox sump, in two hundred and fifty-sixths of a degree. */
    private const val GEARBOX_DIVISOR = 256.0

    /** `22 1002`: twelve volts, in ten-thousandths of 12.207 of a count. */
    private const val VOLTAGE_SCALE = 12.207 / 10_000.0

    private const val PERCENT_PER_COUNT = 100.0 / 255.0

    /** `22 1F9A`: the pack voltage sits third and fourth in the answer, the current fifth and sixth. */
    private const val HV_VOLTAGE_BYTE = 2
    private const val HV_VOLTAGE_SCALE = 156.25 / 10_000.0
    private const val HV_CURRENT_BYTE = 4
    private const val HV_CURRENT_DIVISOR = 10.0

    private fun isToyota(vehicle: ExtendedVehicle) = vehicle.isA(Marque.Toyota)

    private fun isModernToyota(vehicle: ExtendedVehicle) =
        isToyota(vehicle) && vehicle.builtSince(FIRST_UDS_YEAR)

    val entries: List<ExtendedPid> = listOf(
        ExtendedPid(
            id = ExtendedPids.OIL_TEMPERATURE,
            header = ENGINE,
            receiveHeader = ENGINE_RESPONSE,
            service = MODE_READ_LOCAL_ID,
            did = 0x51,
            unit = CELSIUS,
            decimals = 0,
            bytes = OIL_TEMPERATURE_BYTE + 1,
            tier = PidTier.Medium,
            applies = ::isToyota,
            decode = { byte(it, OIL_TEMPERATURE_BYTE) - TEMPERATURE_OFFSET },
        ),
        ExtendedPid(
            id = ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE,
            header = ENGINE,
            receiveHeader = ENGINE_RESPONSE,
            service = MODE_READ_LOCAL_ID,
            did = 0x82,
            unit = CELSIUS,
            decimals = 0,
            bytes = 2,
            tier = PidTier.Slow,
            applies = ::isToyota,
            decode = {
                sane(
                    word(it) / GEARBOX_DIVISOR - TEMPERATURE_OFFSET,
                    ExtendedBytes.GEARBOX_TEMPERATURE,
                )
            },
        ),
        ExtendedPid(
            id = ExtendedPids.BATTERY_VOLTAGE,
            header = ENGINE_UDS,
            receiveHeader = ENGINE_UDS_RESPONSE,
            did = 0x1002,
            unit = VOLT,
            decimals = 2,
            bytes = 2,
            tier = PidTier.Slow,
            applies = ::isModernToyota,
            decode = { sane(word(it) * VOLTAGE_SCALE, ExtendedBytes.LOW_VOLTAGE) },
        ),
        hybrid(ExtendedPids.HV_SOC, 0x1F5B, PERCENT, 1, 1) {
            sane(byte(it) * PERCENT_PER_COUNT, ExtendedBytes.PERCENTAGE)
        },
        hybrid(ExtendedPids.HV_VOLTAGE, 0x1F9A, VOLT, 1, HV_VOLTAGE_BYTE + 2) {
            word(it, HV_VOLTAGE_BYTE) * HV_VOLTAGE_SCALE
        },
        hybrid(ExtendedPids.HV_CURRENT, 0x1F9A, AMPERE, 1, HV_CURRENT_BYTE + 2) {
            signedWord(it, HV_CURRENT_BYTE) / HV_CURRENT_DIVISOR
        },
        hybrid(ExtendedPids.INVERTER_TEMPERATURE, 0x10B2, CELSIUS, 0, 1) {
            byte(it) - TEMPERATURE_OFFSET
        },
    )

    /**
     * One reading from the hybrid system's own module.
     *
     * Flow control is configured for all of them: the module's answers are long, and an
     * adapter left to itself returns the first frame and stops — which for the state of
     * charge is the difference between a number and nothing.
     */
    private fun hybrid(
        id: String,
        did: Int,
        unit: String,
        decimals: Int,
        bytes: Int,
        decode: (IntArray) -> Double,
    ) = ExtendedPid(
        id = id,
        header = HYBRID,
        receiveHeader = HYBRID_RESPONSE,
        did = did,
        unit = unit,
        decimals = decimals,
        bytes = bytes,
        tier = PidTier.Medium,
        flowControl = true,
        applies = ::isToyota,
        decode = decode,
    )
}
