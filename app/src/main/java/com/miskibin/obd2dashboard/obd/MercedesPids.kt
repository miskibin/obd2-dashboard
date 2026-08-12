package com.miskibin.obd2dashboard.obd

import com.miskibin.obd2dashboard.obd.ExtendedBytes.byte
import com.miskibin.obd2dashboard.obd.ExtendedBytes.sane
import com.miskibin.obd2dashboard.obd.ExtendedUnits.CELSIUS

/**
 * Mercedes-Benz — §9 of `docs/research-multibrand-extended-pids.md`.
 *
 * One reading, which is one more than the captured data supports: the only Mercedes signal
 * set anywhere public is a G-Class's stability sensors. The 7G-Tronic gearbox temperature
 * below comes from two independent owner forums that agree on the identifier, the module
 * and the offset, and it is shipped for the same reason the range gate is: an automatic
 * that is cooking itself is worth knowing about, and a byte read at the wrong offset in a
 * twelve-byte answer will not land between −39 and 160 °C for long.
 *
 * Everything else a Mercedes owner would want is absent because nobody has published it.
 * Engine oil temperature is worth trying as the standard PID `01 5C` before hunting an
 * identifier — many of these cars answer it — and that path costs nothing extra, since the
 * app already asks every car what it supports.
 */
internal object MercedesPids {

    /** The gearbox control unit. */
    private const val GEARBOX = "7E1"

    /** The temperature sits twelfth in the answer, in whole degrees above −50. */
    private const val TEMPERATURE_BYTE = 11
    private const val TEMPERATURE_OFFSET = 50.0

    val entries: List<ExtendedPid> = listOf(
        ExtendedPid(
            id = ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE,
            header = GEARBOX,
            did = 0x2130,
            unit = CELSIUS,
            decimals = 0,
            bytes = TEMPERATURE_BYTE + 1,
            tier = PidTier.Slow,
            applies = { it.isA(Marque.Mercedes) },
            decode = {
                sane(
                    byte(it, TEMPERATURE_BYTE) - TEMPERATURE_OFFSET,
                    ExtendedBytes.GEARBOX_TEMPERATURE,
                )
            },
        ),
    )
}
