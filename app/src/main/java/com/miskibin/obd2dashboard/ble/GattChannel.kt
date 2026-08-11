package com.miskibin.obd2dashboard.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService

/** The pair of live characteristics that make up a serial link over GATT. */
data class SerialChannel(
    val profile: String,
    val notify: BluetoothGattCharacteristic,
    val write: BluetoothGattCharacteristic,
)

/**
 * Adapts the platform's service list onto the plain model [ElmGattProfiles.resolve] works
 * on, then maps the answer back to the live characteristics.
 *
 * Everything that decides *which* characteristics to use lives on the other side of this
 * function, where it can be unit tested; this side only reads properties off objects that
 * only exist on a phone.
 */
fun ElmGattProfiles.resolveChannel(services: List<BluetoothGattService>): SerialChannel? {
    val model = services.map { service ->
        GattServiceInfo(
            uuid = service.uuid,
            characteristics = service.characteristics.map { characteristic ->
                GattCharacteristicInfo(
                    uuid = characteristic.uuid,
                    canNotify = characteristic.canNotify,
                    canWrite = characteristic.canWrite,
                )
            },
        )
    }
    val resolved = resolve(model) ?: return null
    val service = services.firstOrNull { it.uuid == resolved.service } ?: return null
    val notify = service.getCharacteristic(resolved.notify) ?: return null
    val write = service.getCharacteristic(resolved.write) ?: return null
    return SerialChannel(resolved.label, notify, write)
}

/** Every service, characteristic and property, for the connection log. */
fun describeServices(services: List<BluetoothGattService>): String =
    services.joinToString("; ") { service ->
        val characteristics = service.characteristics.joinToString(",") { characteristic ->
            val flags = buildString {
                if (characteristic.canNotify) append("N")
                if (characteristic.canWrite) append("W")
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    append("R")
                }
            }
            "${characteristic.uuid}[$flags]"
        }
        "${service.uuid}{$characteristics}"
    }

internal val BluetoothGattCharacteristic.canNotify: Boolean
    get() = properties and
        (
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE
            ) != 0

internal val BluetoothGattCharacteristic.canWrite: Boolean
    get() = properties and
        (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            ) != 0
