/**
 * Transport layer.
 *
 * Device discovery, connection lifecycle and raw byte streams for BLE (and later
 * classic SPP) OBD-II adapters. Implements the transport interface the
 * [com.miskibin.obd2dashboard.obd] layer speaks to, so the protocol code stays testable
 * against a fake.
 */
package com.miskibin.obd2dashboard.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID

/** The pair of characteristics that make up a serial link over GATT. */
data class SerialChannel(
    val profile: String,
    val notify: BluetoothGattCharacteristic,
    val write: BluetoothGattCharacteristic,
)

private data class GattProfile(
    val label: String,
    val service: UUID,
    val notify: UUID,
    val write: UUID,
)

private fun uuid16(short: String): UUID =
    UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

/**
 * There is no standard "ELM327 BLE" profile — Vgate alone ships three GATT layouts, and
 * the clones add more. Known services are tried in order of how common they are, and
 * within a service the direction of a characteristic is decided by its **properties**,
 * never by the numeric order of its UUID (the FFF1/FFF2 mix-up is the single most
 * common bug in ELM327 BLE code: FFF1 notifies, FFF2 accepts writes).
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
        GattProfile("HM-10", uuid16("ffe0"), uuid16("ffe1"), uuid16("ffe1")),
        GattProfile(
            "Nordic UART",
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
        ),
    )

    private val standardServices = setOf(
        uuid16("1800"),
        uuid16("1801"),
        uuid16("180a"),
        uuid16("180f"),
    )

    fun resolve(services: List<BluetoothGattService>): SerialChannel? {
        for (profile in profiles) {
            val service = services.firstOrNull { it.uuid == profile.service } ?: continue
            val expectedNotify = service.getCharacteristic(profile.notify)?.takeIf { it.canNotify }
            val expectedWrite = service.getCharacteristic(profile.write)?.takeIf { it.canWrite }
            if (expectedNotify != null && expectedWrite != null) {
                return SerialChannel(profile.label, expectedNotify, expectedWrite)
            }
            val dualRole = service.characteristics.firstOrNull { it.canNotify && it.canWrite }
            if (dualRole != null) {
                return SerialChannel("${profile.label} (dual role)", dualRole, dualRole)
            }
            val notify = expectedNotify ?: service.characteristics.firstOrNull { it.canNotify }
            val write = expectedWrite ?: service.characteristics.firstOrNull { it.canWrite }
            if (notify != null && write != null) {
                return SerialChannel("${profile.label} (by properties)", notify, write)
            }
        }
        return generic(services)
    }

    private fun generic(services: List<BluetoothGattService>): SerialChannel? {
        val characteristics = services
            .filterNot { it.uuid in standardServices }
            .flatMap { it.characteristics }
        val notify = characteristics.firstOrNull { it.canNotify } ?: return null
        val write = characteristics.firstOrNull { it.canWrite } ?: return null
        return SerialChannel("generic", notify, write)
    }

    private val BluetoothGattCharacteristic.canNotify: Boolean
        get() = properties and
            (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    private val BluetoothGattCharacteristic.canWrite: Boolean
        get() = properties and
            (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
}
