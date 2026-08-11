/**
 * Transport layer.
 *
 * Device discovery, connection lifecycle and raw byte streams for BLE (and later
 * classic SPP) OBD-II adapters. Exposes a transport interface that the [com
 * .miskibin.obd2dashboard.obd] layer speaks to, so the protocol code can be tested
 * against a fake.
 */
package com.miskibin.obd2dashboard.ble
