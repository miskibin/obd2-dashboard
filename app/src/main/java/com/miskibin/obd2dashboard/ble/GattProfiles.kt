/**
 * Transport layer.
 *
 * Device discovery, connection lifecycle and raw byte streams for BLE and classic SPP
 * OBD-II adapters. Implements the transport interface the [com.miskibin.obd2dashboard.obd]
 * layer speaks to, so the protocol code stays testable against a fake.
 */
package com.miskibin.obd2dashboard.ble

import java.util.UUID

/** What the resolver needs to know about one characteristic. */
data class GattCharacteristicInfo(
    val uuid: UUID,
    val canNotify: Boolean,
    val canWrite: Boolean,
)

/** What the resolver needs to know about one service. */
data class GattServiceInfo(
    val uuid: UUID,
    val characteristics: List<GattCharacteristicInfo>,
)

/** The pair of characteristics that make up a serial link over GATT, by UUID. */
data class ResolvedProfile(
    val label: String,
    val service: UUID,
    val notify: UUID,
    val write: UUID,
)

private data class GattProfile(
    val label: String,
    val service: UUID,
    val notify: UUID,
    val write: UUID,
    /**
     * Whether a single characteristic carrying both NOTIFY and WRITE may be used for both
     * directions.
     *
     * True only for the HM-10 family, where that is the actual design. It used to be tried
     * for every profile, which broke Microchip transparent UART: its *notify* characteristic
     * also advertises WRITE, so the shortcut matched it first and every command went into a
     * characteristic the chip never reads. That is a silent hang, not an error.
     */
    val dualRole: Boolean = false,
)

private fun uuid16(short: String): UUID =
    UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

/**
 * There is no standard "ELM327 BLE" profile — Vgate alone ships three GATT layouts, and
 * the clones add more. Known services are tried in order of how common they are, and
 * within a service the direction of a characteristic is decided by its **properties**,
 * never by the numeric order of its UUID (the FFF1/FFF2 mix-up is the single most
 * common bug in ELM327 BLE code: FFF1 notifies, FFF2 accepts writes).
 *
 * This object is free of `android.*`: [resolve] works on the plain [GattServiceInfo] model
 * so every branch below is unit tested, and `BluetoothGatt` is adapted onto it in
 * `GattChannel.kt`.
 */
object ElmGattProfiles {

    val CLIENT_CHARACTERISTIC_CONFIG: UUID = uuid16("2902")

    private val profiles = listOf(
        GattProfile("FFF0", uuid16("fff0"), uuid16("fff1"), uuid16("fff2")),
        GattProfile("18F0 (Vgate/vLinker)", uuid16("18f0"), uuid16("2af0"), uuid16("2af1")),
        GattProfile(
            "vLinker transparent UART",
            UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
            UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
            UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
        ),
        GattProfile("HM-10", uuid16("ffe0"), uuid16("ffe1"), uuid16("ffe1"), dualRole = true),
        // Microchip/ISSC BM70 & RN487x transparent UART, and its many relabelled clones.
        GattProfile(
            "Microchip transparent UART",
            UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455"),
            UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616"),
            UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3"),
        ),
        // Telit/Stollmann TIO, shipped by a few of the better-built dongles.
        GattProfile(
            "Telit TIO",
            uuid16("fefb"),
            UUID.fromString("00000002-0000-1000-8000-008025000000"),
            UUID.fromString("00000001-0000-1000-8000-008025000000"),
        ),
        GattProfile(
            "Nordic UART",
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
        ),
    )

    /** The services an advertisement can carry that mark the device as a serial dongle. */
    val serviceUuids: Set<UUID> = profiles.map { it.service }.toSet()

    private val standardServices = setOf(
        uuid16("1800"),
        uuid16("1801"),
        uuid16("180a"),
        uuid16("180f"),
    )

    fun resolve(services: List<GattServiceInfo>): ResolvedProfile? {
        for (profile in profiles) {
            val service = services.firstOrNull { it.uuid == profile.service } ?: continue
            val expectedNotify = service.characteristic(profile.notify)?.takeIf { it.canNotify }
            val expectedWrite = service.characteristic(profile.write)?.takeIf { it.canWrite }
            if (expectedNotify != null && expectedWrite != null) {
                return ResolvedProfile(
                    profile.label,
                    service.uuid,
                    expectedNotify.uuid,
                    expectedWrite.uuid,
                )
            }
            if (profile.dualRole) {
                val dual = service.characteristics.firstOrNull { it.canNotify && it.canWrite }
                if (dual != null) {
                    return ResolvedProfile(
                        "${profile.label} (dual role)",
                        service.uuid,
                        dual.uuid,
                        dual.uuid,
                    )
                }
            }
            val byProperties = service.byProperties(expectedNotify, expectedWrite)
            if (byProperties != null) {
                return ResolvedProfile(
                    "${profile.label} (by properties)",
                    service.uuid,
                    byProperties.first.uuid,
                    byProperties.second.uuid,
                )
            }
        }
        return generic(services)
    }

    /**
     * Last resort for a dongle with UUIDs nobody has seen before.
     *
     * Scoped to one service at a time: a notify characteristic from the battery service and
     * a write characteristic from a vendor service are not a serial port, and pairing them
     * produces a link that connects and then never answers.
     */
    private fun generic(services: List<GattServiceInfo>): ResolvedProfile? {
        for (service in services) {
            if (service.uuid in standardServices) continue
            val pair = service.byProperties(null, null) ?: continue
            return ResolvedProfile("generic", service.uuid, pair.first.uuid, pair.second.uuid)
        }
        return null
    }

    /**
     * Picks a notify and a write characteristic out of one service.
     *
     * A *different* characteristic is preferred for the write side, because a chip whose
     * notify characteristic also declares WRITE (Microchip does) still only reads commands
     * on its own write characteristic. Reusing the one characteristic is allowed only when
     * the service has nothing else that accepts writes — which is the genuine HM-10 layout.
     */
    private fun GattServiceInfo.byProperties(
        knownNotify: GattCharacteristicInfo?,
        knownWrite: GattCharacteristicInfo?,
    ): Pair<GattCharacteristicInfo, GattCharacteristicInfo>? {
        val notify = knownNotify ?: characteristics.firstOrNull { it.canNotify } ?: return null
        val write = knownWrite
            ?: characteristics.firstOrNull { it.canWrite && it.uuid != notify.uuid }
            ?: characteristics.firstOrNull { it.canWrite }
            ?: return null
        return notify to write
    }

    private fun GattServiceInfo.characteristic(uuid: UUID): GattCharacteristicInfo? =
        characteristics.firstOrNull { it.uuid == uuid }
}
