package com.miskibin.obd2dashboard.obd

import com.miskibin.obd2dashboard.obd.ExtendedBytes.barFromPsi
import com.miskibin.obd2dashboard.obd.ExtendedBytes.byte
import com.miskibin.obd2dashboard.obd.ExtendedBytes.quad
import com.miskibin.obd2dashboard.obd.ExtendedBytes.sane
import com.miskibin.obd2dashboard.obd.ExtendedBytes.word
import com.miskibin.obd2dashboard.obd.ExtendedUnits.BAR
import com.miskibin.obd2dashboard.obd.ExtendedUnits.CELSIUS
import com.miskibin.obd2dashboard.obd.ExtendedUnits.KM
import com.miskibin.obd2dashboard.obd.ExtendedUnits.PERCENT
import com.miskibin.obd2dashboard.obd.ExtendedUnits.VOLT

/**
 * Hyundai and Kia — §7 of `docs/research-multibrand-extended-pids.md`.
 *
 * Two services again, and this time they carry the same data: the engine ECU mirrors its
 * old block reads into identifiers `E001`, `E002`, `E004`, so `22 E001` and `21 01` are one
 * answer under two numbers. The newer form is asked of cars from 2014 and the older of the
 * ones before, split by model year rather than probed, so that no car is ever offered the
 * same reading twice.
 *
 * The tyre pressures are split the same way and for a sharper reason. The monitoring module
 * moved its four pressures to different offsets in the same answer around 2021, and reading
 * a 2019 car with the newer layout does not fail — it returns a *temperature* byte as a
 * pressure. Model year decides, and where the VIN did not settle a year neither layout is
 * asked for.
 */
internal object HyundaiKiaPids {

    private const val ENGINE = "7E0"
    private const val ENGINE_RESPONSE = "7E8"

    private const val GEARBOX = "7E1"
    private const val GEARBOX_RESPONSE = "7E9"

    /** Tyre pressure monitoring. */
    private const val TPMS = "7A0"
    private const val TPMS_RESPONSE = "7A8"

    /** The instrument cluster, which reports the twelve-volt supply it sees. */
    private const val CLUSTER = "7C6"
    private const val CLUSTER_RESPONSE = "7CE"

    /** The high-voltage battery management system on the hybrids and the EVs. */
    private const val BMS = "7E4"
    private const val BMS_RESPONSE = "7EC"

    /** The first model year of the mirrored `E0xx` identifiers. */
    private const val FIRST_MIRRORED_YEAR = 2014
    private val LEGACY_YEARS = 1996..FIRST_MIRRORED_YEAR - 1

    /** The model year the monitoring module rearranged its answer. */
    private const val FIRST_NEW_TPMS_YEAR = 2021
    private val OLD_TPMS_YEARS = 1996..FIRST_NEW_TPMS_YEAR - 1

    /** Both forms of the engine block put the oil temperature at the same offset. */
    private const val OIL_TEMPERATURE_BYTE = 34
    private const val OIL_TEMPERATURE_SCALE = 0.75
    private const val OIL_TEMPERATURE_OFFSET = 48.0

    /** The gearbox block's fourteenth byte, in whole degrees above −40. */
    private const val GEARBOX_TEMPERATURE_BYTE = 13
    private const val TEMPERATURE_OFFSET = 40.0

    /** `22 E021`: the manifold pressure sits eighth and ninth, in absolute kilopascals. */
    private const val BOOST_BYTE = 7
    private const val BOOST_SCALE = 0.008291
    private const val KPA_PER_BAR = 100.0

    /** `22 E004`: the auxiliary battery's charge, then its health. */
    private const val BATTERY_SOC_BYTE = 40
    private const val BATTERY_HEALTH_BYTE = 41

    /** `22 B002`: the cluster's supply voltage, in twelve-point-eighths of a volt. */
    private const val CLUSTER_VOLTAGE_BYTE = 5
    private const val CLUSTER_VOLTAGE_DIVISOR = 12.8

    /** `22 0101`: the pack's charge, in half percentages. */
    private const val HV_SOC_BYTE = 4
    private const val HV_SOC_SCALE = 0.5

    /** `21 03`: the distance since the filter last burnt itself clean, in metres. */
    private const val REGEN_DISTANCE_BYTE = 53
    private const val METRES_PER_KM = 1000.0

    private const val TYRE_COUNTS_PER_PSI = 5.0

    /** Where each wheel's pressure sits in the monitoring module's answer, by era. */
    private val NEW_TYRE_BYTES = mapOf("fl" to 4, "fr" to 9, "rl" to 14, "rr" to 19)
    private val OLD_TYRE_BYTES = mapOf("fl" to 4, "fr" to 8, "rl" to 16, "rr" to 12)

    private fun isHyundaiKia(vehicle: ExtendedVehicle) = vehicle.isA(Marque.HyundaiKia)

    val entries: List<ExtendedPid> = buildList {
        addAll(engine())
        add(gearbox())
        add(clusterVoltage())
        add(highVoltageBattery())
        addAll(tyres())
    }

    private fun engine() = listOf(
        // The same reading under both services, split by year so that only one of them is
        // ever live on a car.
        engineBlock(
            id = ExtendedPids.OIL_TEMPERATURE,
            service = MODE_READ_DATA_BY_ID,
            did = 0xE001,
            unit = CELSIUS,
            decimals = 0,
            bytes = OIL_TEMPERATURE_BYTE + 1,
            applies = { isHyundaiKia(it) && it.builtSince(FIRST_MIRRORED_YEAR) },
            decode = ::oilTemperature,
        ),
        engineBlock(
            id = ExtendedPids.OIL_TEMPERATURE,
            service = MODE_READ_LOCAL_ID,
            did = 0x01,
            unit = CELSIUS,
            decimals = 0,
            bytes = OIL_TEMPERATURE_BYTE + 1,
            applies = { isHyundaiKia(it) && it.builtIn(LEGACY_YEARS) },
            decode = ::oilTemperature,
        ),
        engineBlock(
            id = ExtendedPids.BOOST_PRESSURE,
            service = MODE_READ_DATA_BY_ID,
            did = 0xE021,
            unit = BAR,
            decimals = 2,
            bytes = BOOST_BYTE + 2,
            tier = PidTier.Fast,
            applies = ::isHyundaiKia,
            decode = { word(it, BOOST_BYTE) * BOOST_SCALE / KPA_PER_BAR },
        ),
        engineBlock(
            id = ExtendedPids.BATTERY_SOC,
            service = MODE_READ_DATA_BY_ID,
            did = 0xE004,
            unit = PERCENT,
            decimals = 0,
            bytes = BATTERY_SOC_BYTE + 1,
            applies = ::isHyundaiKia,
            decode = { sane(byte(it, BATTERY_SOC_BYTE), ExtendedBytes.PERCENTAGE) },
        ),
        engineBlock(
            id = ExtendedPids.BATTERY_HEALTH,
            service = MODE_READ_DATA_BY_ID,
            did = 0xE004,
            unit = PERCENT,
            decimals = 0,
            bytes = BATTERY_HEALTH_BYTE + 1,
            applies = ::isHyundaiKia,
            decode = { sane(byte(it, BATTERY_HEALTH_BYTE), ExtendedBytes.PERCENTAGE) },
        ),
        // The one diesel row the research could verify. There is no soot mass in grams for
        // this marque anywhere public, so there is none here either.
        engineBlock(
            id = ExtendedPids.DPF_DISTANCE_SINCE_REGEN,
            service = MODE_READ_LOCAL_ID,
            did = 0x03,
            unit = KM,
            decimals = 0,
            bytes = REGEN_DISTANCE_BYTE + 4,
            applies = { isHyundaiKia(it) && it.isDiesel },
            decode = { quad(it, REGEN_DISTANCE_BYTE) / METRES_PER_KM },
        ),
    )

    private fun oilTemperature(data: IntArray): Double =
        byte(data, OIL_TEMPERATURE_BYTE) * OIL_TEMPERATURE_SCALE - OIL_TEMPERATURE_OFFSET

    private fun engineBlock(
        id: String,
        service: Int,
        did: Int,
        unit: String,
        decimals: Int,
        bytes: Int,
        tier: PidTier = PidTier.Slow,
        applies: (ExtendedVehicle) -> Boolean,
        decode: (IntArray) -> Double,
    ) = ExtendedPid(
        id = id,
        header = ENGINE,
        receiveHeader = ENGINE_RESPONSE,
        service = service,
        did = did,
        unit = unit,
        decimals = decimals,
        bytes = bytes,
        tier = tier,
        applies = applies,
        decode = decode,
    )

    private fun gearbox() = ExtendedPid(
        id = ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE,
        header = GEARBOX,
        receiveHeader = GEARBOX_RESPONSE,
        service = MODE_READ_LOCAL_ID,
        did = 0xA0,
        unit = CELSIUS,
        decimals = 0,
        bytes = GEARBOX_TEMPERATURE_BYTE + 1,
        tier = PidTier.Slow,
        applies = ::isHyundaiKia,
        decode = {
            sane(
                byte(it, GEARBOX_TEMPERATURE_BYTE) - TEMPERATURE_OFFSET,
                ExtendedBytes.GEARBOX_TEMPERATURE,
            )
        },
    )

    private fun clusterVoltage() = ExtendedPid(
        id = ExtendedPids.BATTERY_VOLTAGE,
        header = CLUSTER,
        receiveHeader = CLUSTER_RESPONSE,
        did = 0xB002,
        unit = VOLT,
        decimals = 2,
        bytes = CLUSTER_VOLTAGE_BYTE + 1,
        tier = PidTier.Slow,
        flowControl = true,
        applies = ::isHyundaiKia,
        decode = {
            sane(byte(it, CLUSTER_VOLTAGE_BYTE) / CLUSTER_VOLTAGE_DIVISOR, ExtendedBytes.LOW_VOLTAGE)
        },
    )

    private fun highVoltageBattery() = ExtendedPid(
        id = ExtendedPids.HV_SOC,
        header = BMS,
        receiveHeader = BMS_RESPONSE,
        did = 0x0101,
        unit = PERCENT,
        decimals = 1,
        bytes = HV_SOC_BYTE + 1,
        tier = PidTier.Medium,
        flowControl = true,
        applies = ::isHyundaiKia,
        decode = { sane(byte(it, HV_SOC_BYTE) * HV_SOC_SCALE, ExtendedBytes.PERCENTAGE) },
    )

    private fun tyres() = buildList {
        addAll(tyreSet(NEW_TYRE_BYTES) { it.builtSince(FIRST_NEW_TPMS_YEAR) })
        addAll(tyreSet(OLD_TYRE_BYTES) { it.builtIn(OLD_TPMS_YEARS) })
    }

    private fun tyreSet(offsets: Map<String, Int>, era: (ExtendedVehicle) -> Boolean) =
        offsets.map { (wheel, offset) ->
            ExtendedPid(
                id = ExtendedNames.tyrePressure(wheel),
                header = TPMS,
                receiveHeader = TPMS_RESPONSE,
                did = 0xC00B,
                unit = BAR,
                decimals = 2,
                bytes = offset + 1,
                tier = PidTier.Slow,
                minIntervalMillis = MazdaPids.TYRE_INTERVAL_MILLIS,
                flowControl = true,
                applies = { isHyundaiKia(it) && era(it) },
                decode = {
                    sane(
                        barFromPsi(byte(it, offset) / TYRE_COUNTS_PER_PSI),
                        ExtendedBytes.TYRE_PRESSURE,
                    )
                },
            )
        }
}
