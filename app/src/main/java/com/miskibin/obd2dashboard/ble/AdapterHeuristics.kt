package com.miskibin.obd2dashboard.ble

import java.util.UUID

/**
 * Deciding which of the devices in range is the OBD-II dongle.
 *
 * The name is the obvious signal and the weakest one: the Vgate iCar2's LE identity
 * advertises no name at all, which is exactly the device this app exists to talk to. So an
 * advertised serial-over-GATT service counts as evidence too — no headset, watch or tag
 * advertises FFF0 or 18F0, and a dongle that advertises one of them is a dongle whatever it
 * calls itself.
 *
 * Free of `android.*` on purpose, so the rule is unit tested rather than guessed at.
 */
object AdapterHeuristics {

    /** Substrings, not prefixes: clones ship names like `V-LINK BLE` and `OBDII-4.0`. */
    private val NAME_FRAGMENTS = listOf(
        "OBD",
        "ELM",
        "VLINK",
        "V-LINK",
        "VGATE",
        "VLINKER",
        "ICAR",
        "OBDLINK",
        "VEEPEAK",
        "KONNWEI",
        "BAFX",
        "LELINK",
        "SCANTOOL",
        "CARISTA",
    )

    /** Every serial-over-GATT service [ElmGattProfiles] knows how to drive. */
    val ADAPTER_SERVICE_UUIDS: Set<UUID> = ElmGattProfiles.serviceUuids

    fun looksLikeAdapter(name: String?, serviceUuids: Collection<UUID> = emptyList()): Boolean =
        matchesName(name) || serviceUuids.any { it in ADAPTER_SERVICE_UUIDS }

    private fun matchesName(name: String?): Boolean {
        val upper = name?.trim()?.uppercase().orEmpty()
        if (upper.isEmpty()) return false
        return NAME_FRAGMENTS.any { upper.contains(it) }
    }
}
