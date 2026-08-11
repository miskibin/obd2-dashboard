package com.miskibin.obd2dashboard.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val looksLikeAdapter: Boolean,
)

class BleScanFailedException(val errorCode: Int) : Exception("BLE scan failed with code $errorCode")

class BluetoothUnavailableException(message: String) : Exception(message)

/**
 * Unfiltered BLE scan with name matching done in the app.
 *
 * Most ELM327 dongles advertise nothing but a name — service UUIDs live in the scan
 * response or are not advertised at all — so filtering the scan by service would find
 * nothing.
 */
class BleScanner(private val context: Context) {

    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    val isBluetoothEnabled: Boolean get() = manager?.adapter?.isEnabled == true

    fun hasScanPermission(): Boolean =
        !needsRuntimeBluetoothPermissions() ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    fun hasConnectPermission(): Boolean =
        !needsRuntimeBluetoothPermissions() ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Emits every device seen; [DiscoveredDevice.looksLikeAdapter] flags the likely ones. */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        val adapter = manager?.adapter
            ?: throw BluetoothUnavailableException("No Bluetooth adapter on this device")
        if (!adapter.isEnabled) throw BluetoothUnavailableException("Bluetooth is turned off")
        if (!hasScanPermission()) throw SecurityException("BLUETOOTH_SCAN not granted")
        val scanner = adapter.bluetoothLeScanner
            ?: throw BluetoothUnavailableException("BLE scanner unavailable")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toDiscoveredDevice())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it.toDiscoveredDevice()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleScanFailedException(errorCode))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(emptyList(), settings, callback)

        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toDiscoveredDevice(): DiscoveredDevice {
        val name = scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
        return DiscoveredDevice(
            address = device.address,
            name = name,
            rssi = rssi,
            looksLikeAdapter = looksLikeAdapter(name),
        )
    }

    private fun needsRuntimeBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    companion object {
        private val NAME_PREFIXES = listOf(
            "OBD",
            "ELM",
            "VLINK",
            "V-LINK",
            "VGATE",
            "VLINKER",
            "IOS-VLINK",
        )

        fun looksLikeAdapter(name: String?): Boolean {
            val upper = name?.trim()?.uppercase() ?: return false
            return NAME_PREFIXES.any { upper.startsWith(it) || upper.contains(it) }
        }
    }
}
