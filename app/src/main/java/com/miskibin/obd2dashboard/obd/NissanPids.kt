package com.miskibin.obd2dashboard.obd

import com.miskibin.obd2dashboard.obd.ExtendedBytes.barFromPsi
import com.miskibin.obd2dashboard.obd.ExtendedBytes.byte
import com.miskibin.obd2dashboard.obd.ExtendedBytes.sane
import com.miskibin.obd2dashboard.obd.ExtendedBytes.triple
import com.miskibin.obd2dashboard.obd.ExtendedBytes.word
import com.miskibin.obd2dashboard.obd.ExtendedUnits.BAR
import com.miskibin.obd2dashboard.obd.ExtendedUnits.CELSIUS
import com.miskibin.obd2dashboard.obd.ExtendedUnits.PERCENT
import com.miskibin.obd2dashboard.obd.ExtendedUnits.VOLT

/**
 * Nissan — §12.2 of `docs/research-multibrand-extended-pids.md`.
 *
 * A thin table, and honest about which car each row came from. The tyre pressures are an
 * Altima's and the electrical rows are a Leaf's; the oil temperature is the one reading
 * here that belongs to a European diesel, the 1.6 dCi R9M that Nissan and Renault share, and
 * it is gated on the fuel because on any other engine the identifier is either absent or
 * something else entirely.
 *
 * `VNV` — the Maubeuge plant Renault and Nissan share — is claimed by both marques in the
 * research and therefore by neither here. A VIN that could be either is not a VIN that
 * settles anything, and the whole point of the gate is certainty.
 */
internal object NissanPids {

    private const val ENGINE = "7E0"
    private const val ENGINE_RESPONSE = "7E8"

    /** The tyre pressure monitor. */
    private const val TPMS = "758"
    private const val TPMS_RESPONSE = "778"

    /** The vehicle control module, which reports the twelve-volt side. */
    private const val VCM = "797"
    private const val VCM_RESPONSE = "79A"

    /** The traction battery's own computer. */
    private const val HV_BATTERY = "79B"
    private const val HV_BATTERY_RESPONSE = "7BB"

    private const val TEMPERATURE_OFFSET = 50.0

    private const val TYRE_COUNTS_PER_PSI = 4.0

    /** `2201` onwards, in the order the capture records: front pair, then right rear. */
    private val TYRE_DIDS = mapOf("fl" to 0x0201, "fr" to 0x0202, "rr" to 0x0203, "rl" to 0x0204)

    private const val VOLTAGE_DIVISOR = 12.5

    /** `21 01`: the pack's charge is three bytes, thirty-two into the answer. */
    private const val HV_SOC_BYTE = 31
    private const val HV_SOC_DIVISOR = 8190.0
    private const val HV_SOC_OFFSET = 17.0

    /** `21 61`: the pack's health, third and fourth byte, in hundredths of a percent. */
    private const val HV_HEALTH_BYTE = 2
    private const val HUNDREDTHS = 100.0

    private val OIL_TEMPERATURE_RANGE = -40.0..180.0

    private fun isNissan(vehicle: ExtendedVehicle) = vehicle.isA(Marque.Nissan)

    val entries: List<ExtendedPid> = buildList {
        add(oilTemperature())
        addAll(tyres())
        add(batteryVoltage())
        add(hvCharge())
        add(hvHealth())
    }

    private fun oilTemperature() = ExtendedPid(
        id = ExtendedPids.OIL_TEMPERATURE,
        header = ENGINE,
        receiveHeader = ENGINE_RESPONSE,
        did = 0x111F,
        unit = CELSIUS,
        decimals = 0,
        bytes = 1,
        tier = PidTier.Medium,
        applies = { isNissan(it) && it.isDiesel },
        decode = { sane(byte(it) - TEMPERATURE_OFFSET, OIL_TEMPERATURE_RANGE) },
    )

    private fun tyres() = TYRE_DIDS.map { (wheel, did) ->
        ExtendedPid(
            id = ExtendedNames.tyrePressure(wheel),
            header = TPMS,
            receiveHeader = TPMS_RESPONSE,
            did = did,
            unit = BAR,
            decimals = 2,
            bytes = 1,
            tier = PidTier.Slow,
            minIntervalMillis = MazdaPids.TYRE_INTERVAL_MILLIS,
            applies = ::isNissan,
            decode = {
                sane(
                    barFromPsi(byte(it) / TYRE_COUNTS_PER_PSI),
                    ExtendedBytes.TYRE_PRESSURE,
                )
            },
        )
    }

    private fun batteryVoltage() = ExtendedPid(
        id = ExtendedPids.BATTERY_VOLTAGE,
        header = VCM,
        receiveHeader = VCM_RESPONSE,
        did = 0x1103,
        unit = VOLT,
        decimals = 2,
        bytes = 1,
        tier = PidTier.Slow,
        flowControl = true,
        applies = ::isNissan,
        decode = { sane(byte(it) / VOLTAGE_DIVISOR, ExtendedBytes.LOW_VOLTAGE) },
    )

    private fun hvCharge() = ExtendedPid(
        id = ExtendedPids.HV_SOC,
        header = HV_BATTERY,
        receiveHeader = HV_BATTERY_RESPONSE,
        service = MODE_READ_LOCAL_ID,
        did = 0x01,
        unit = PERCENT,
        decimals = 1,
        bytes = HV_SOC_BYTE + 3,
        tier = PidTier.Medium,
        flowControl = true,
        applies = ::isNissan,
        decode = {
            sane(triple(it, HV_SOC_BYTE) / HV_SOC_DIVISOR - HV_SOC_OFFSET, ExtendedBytes.PERCENTAGE)
        },
    )

    private fun hvHealth() = ExtendedPid(
        id = ExtendedPids.HV_HEALTH,
        header = HV_BATTERY,
        receiveHeader = HV_BATTERY_RESPONSE,
        service = MODE_READ_LOCAL_ID,
        did = 0x61,
        unit = PERCENT,
        decimals = 1,
        bytes = HV_HEALTH_BYTE + 2,
        tier = PidTier.Slow,
        flowControl = true,
        applies = ::isNissan,
        decode = {
            sane(word(it, HV_HEALTH_BYTE) / HUNDREDTHS, ExtendedBytes.PERCENTAGE)
        },
    )
}
