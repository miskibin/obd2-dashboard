package com.miskibin.obd2dashboard.ble

/**
 * Which radio a device is reachable on.
 *
 * This is not a detail: the cheap dual-radio dongles (Vgate iCar2 and every clone of it)
 * expose a classic-SPP identity and a BLE identity with *different* MAC addresses, and only
 * one of the two talks to Android. A device found by an LE scan can only be opened with
 * GATT; a bonded classic device can only be opened with an RFCOMM socket.
 */
enum class DeviceKind {
    /** Bluetooth Low Energy, opened with `connectGatt`. */
    Le,

    /** Bluetooth Classic, opened with an RFCOMM/SPP socket. */
    Classic,
}

/**
 * One adapter candidate, from either an LE scan or the system's bonded-device list.
 *
 * [rssi] is `0` for bonded classic devices: pairing carries no signal strength, and the
 * list is sorted by whether a device looks like an adapter first anyway.
 */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val looksLikeAdapter: Boolean,
    val kind: DeviceKind = DeviceKind.Le,
    /** Paired in system Bluetooth settings — for classic devices, a precondition. */
    val bonded: Boolean = false,
)
