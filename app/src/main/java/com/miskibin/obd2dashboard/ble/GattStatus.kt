package com.miskibin.obd2dashboard.ble

/**
 * Names for the numbers `BluetoothGattCallback` reports.
 *
 * A log line reading "status 133" is worth nothing to whoever reads it in six months;
 * "GATT_ERROR (133)" at least says which of the dozen failure modes it was. Most of these
 * constants are not public API — they come from the AOSP stack's `gatt_api.h` — which is
 * exactly why they have to be written down somewhere.
 *
 * Free of `android.*` so it can be unit tested.
 */
object GattStatus {

    fun name(status: Int): String {
        val label = when (status) {
            0 -> "GATT_SUCCESS"
            1 -> "GATT_INVALID_HANDLE"
            2 -> "GATT_READ_NOT_PERMITTED"
            3 -> "GATT_WRITE_NOT_PERMITTED"
            5 -> "GATT_INSUFFICIENT_AUTHENTICATION"
            6 -> "GATT_REQUEST_NOT_SUPPORTED"
            7 -> "GATT_INVALID_OFFSET"
            8 -> "GATT_CONN_TIMEOUT (link supervision timeout — adapter out of range or asleep)"
            13 -> "GATT_INVALID_ATTRIBUTE_LENGTH"
            15 -> "GATT_INSUFFICIENT_ENCRYPTION"
            19 -> "GATT_CONN_TERMINATE_PEER_USER (the adapter hung up)"
            22 -> "GATT_CONN_TERMINATE_LOCAL_HOST (Android hung up)"
            34 -> "GATT_CONN_LMP_TIMEOUT"
            62 -> "GATT_CONN_FAIL_ESTABLISH (connection never came up)"
            133 -> "GATT_ERROR (the catch-all: stale cache, scan still running, or too many links)"
            137 -> "GATT_AUTH_FAIL"
            143 -> "GATT_CONN_CANCEL"
            147 -> "GATT_NO_RESOURCES (Android ran out of GATT client slots)"
            257 -> "GATT_FAILURE"
            else -> "GATT_UNKNOWN"
        }
        return "$label ($status)"
    }

    fun stateName(newState: Int): String = when (newState) {
        0 -> "DISCONNECTED"
        1 -> "CONNECTING"
        2 -> "CONNECTED"
        3 -> "DISCONNECTING"
        else -> "STATE_$newState"
    }

    /**
     * Statuses that mean "this attribute needs a bond", not "this link is broken".
     *
     * Tearing the link down on one of these is how an app ends up in a connect/pair/drop
     * loop: Android is already showing the pairing dialog, and closing the GATT client
     * cancels it.
     */
    fun needsBonding(status: Int): Boolean = status == 5 || status == 15 || status == 137

    fun scanFailureName(errorCode: Int): String = when (errorCode) {
        1 -> "SCAN_FAILED_ALREADY_STARTED"
        2 -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
        3 -> "SCAN_FAILED_INTERNAL_ERROR"
        4 -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
        5 -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
        6 -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY"
        else -> "SCAN_FAILED_$errorCode"
    }

    fun bondStateName(bondState: Int): String = when (bondState) {
        10 -> "BOND_NONE"
        11 -> "BOND_BONDING"
        12 -> "BOND_BONDED"
        else -> "BOND_$bondState"
    }

    /** `1`/`2`/`3` from `BluetoothDevice.getType()`. */
    fun deviceTypeName(type: Int): String = when (type) {
        1 -> "CLASSIC"
        2 -> "LE"
        3 -> "DUAL"
        else -> "UNKNOWN"
    }
}
