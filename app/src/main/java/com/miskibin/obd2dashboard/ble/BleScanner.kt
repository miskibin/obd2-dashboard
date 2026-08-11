package com.miskibin.obd2dashboard.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import com.miskibin.obd2dashboard.log.LogTag
import com.miskibin.obd2dashboard.log.ObdLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/** Why a connection attempt cannot start, in terms the UI can offer an action for. */
enum class ConnectionIssue {
    BluetoothOff,
    LocationOff,
    ScanPermission,
    ConnectPermission,
    ScanFailed,
    NoAdapter,
}

/** Carries [issue] so the screen can show a button rather than a sentence. */
open class ConnectionSetupException(
    val issue: ConnectionIssue,
    message: String,
) : Exception(message)

class BleScanFailedException(val errorCode: Int) : ConnectionSetupException(
    ConnectionIssue.ScanFailed,
    GattStatus.scanFailureName(errorCode),
)

class BluetoothUnavailableException(
    issue: ConnectionIssue,
    message: String,
) : ConnectionSetupException(issue, message)

/**
 * Finding the adapter, on both radios.
 *
 * An LE scan is only half the answer. Classic Bluetooth devices never appear in one, and
 * the dual-radio dongles this app is used with expose their *working* identity on the
 * classic side — paired in system settings, invisible to every LE scan ever run. So the
 * bonded-device list is merged in as a first-class source rather than treated as a cache of
 * previous scans.
 *
 * The LE scan itself stays unfiltered: most dongles advertise nothing but a name, so
 * filtering by service UUID would return an empty list. Matching is done in the app, on the
 * name *and* on whatever services the advertisement happened to carry.
 */
class BleScanner(private val context: Context) {

    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var lastScanStartMillis = 0L

    val isBluetoothEnabled: Boolean get() = manager?.adapter?.isEnabled == true

    val hasAdapter: Boolean get() = manager?.adapter != null

    fun hasScanPermission(): Boolean =
        !needsRuntimeBluetoothPermissions() ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    fun hasConnectPermission(): Boolean =
        !needsRuntimeBluetoothPermissions() ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Up to Android 11 a BLE scan silently returns nothing when location services are off —
     * no error, no callback, just an empty list, which is indistinguishable from "no adapter
     * in the car" unless the app checks.
     */
    fun isLocationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) return true
        val locations = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return true
        return LocationManagerCompat.isLocationEnabled(locations)
    }

    /**
     * Everything paired in system Bluetooth settings.
     *
     * This is where an `Android-VLink` lives: bonded, classic, and reachable — and until it
     * is listed here, unreachable from this app no matter how long the LE scan runs.
     */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<DiscoveredDevice> {
        val adapter = manager?.adapter ?: return emptyList()
        if (!hasConnectPermission()) return emptyList()
        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        ObdLog.log(LogTag.SCAN, "bonded devices: ${bonded.size}")
        return bonded.map { device ->
            val name = runCatching { device.name }.getOrNull()
            val type = runCatching { device.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
            ObdLog.log(
                LogTag.SCAN,
                "  bonded ${device.address} \"$name\" type=${GattStatus.deviceTypeName(type)}",
            )
            DiscoveredDevice(
                address = device.address,
                name = name,
                rssi = 0,
                looksLikeAdapter = AdapterHeuristics.looksLikeAdapter(name),
                // DUAL means both radios answer; classic is the one an Android OBD-II app
                // gets a working ELM327 out of, so it wins.
                kind = if (type == BluetoothDevice.DEVICE_TYPE_LE) DeviceKind.Le else DeviceKind.Classic,
                bonded = true,
            )
        }
    }

    /** Emits every device seen; [DiscoveredDevice.looksLikeAdapter] flags the likely ones. */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        val adapter = manager?.adapter
            ?: throw BluetoothUnavailableException(
                ConnectionIssue.NoAdapter,
                "No Bluetooth adapter on this device",
            )
        if (!adapter.isEnabled) {
            throw BluetoothUnavailableException(ConnectionIssue.BluetoothOff, "Bluetooth is turned off")
        }
        if (!hasScanPermission()) {
            throw BluetoothUnavailableException(
                ConnectionIssue.ScanPermission,
                "BLUETOOTH_SCAN not granted",
            )
        }
        if (!isLocationEnabled()) {
            throw BluetoothUnavailableException(
                ConnectionIssue.LocationOff,
                "Location services are off",
            )
        }
        val scanner = adapter.bluetoothLeScanner
            ?: throw BluetoothUnavailableException(
                ConnectionIssue.NoAdapter,
                "BLE scanner unavailable",
            )

        // Android kills an app that starts five scans in thirty seconds — silently, by
        // never delivering a result again until the window rolls over.
        val sinceLast = System.currentTimeMillis() - lastScanStartMillis
        if (lastScanStartMillis != 0L && sinceLast < MIN_SCAN_INTERVAL_MILLIS) {
            val wait = MIN_SCAN_INTERVAL_MILLIS - sinceLast
            ObdLog.log(LogTag.SCAN, "throttling: waiting $wait ms before starting another scan")
            delay(wait)
        }

        ObdLog.log(
            LogTag.SCAN,
            "scan start: sdk=${Build.VERSION.SDK_INT} enabled=${adapter.isEnabled} " +
                "scanPermission=${hasScanPermission()} connectPermission=${hasConnectPermission()} " +
                "location=${isLocationEnabled()}",
        )

        val seen = mutableSetOf<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toDiscoveredDevice(seen))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it.toDiscoveredDevice(seen)) }
            }

            override fun onScanFailed(errorCode: Int) {
                ObdLog.log(LogTag.SCAN, "onScanFailed ${GattStatus.scanFailureName(errorCode)}")
                close(BleScanFailedException(errorCode))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        lastScanStartMillis = System.currentTimeMillis()
        scanner.startScan(emptyList(), settings, callback)

        awaitClose {
            ObdLog.log(LogTag.SCAN, "scan stopped")
            runCatching { scanner.stopScan(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toDiscoveredDevice(seen: MutableSet<String>): DiscoveredDevice {
        val name = scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
        val services: List<UUID> = scanRecord?.serviceUuids.orEmpty().map { it.uuid }
        if (seen.add(device.address)) {
            ObdLog.log(
                LogTag.SCAN,
                "seen ${device.address} \"$name\" rssi=$rssi services=${services.joinToString()}",
            )
        }
        return DiscoveredDevice(
            address = device.address,
            name = name,
            rssi = rssi,
            looksLikeAdapter = AdapterHeuristics.looksLikeAdapter(name, services),
            kind = DeviceKind.Le,
            bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }
                .getOrDefault(false),
        )
    }

    private fun needsRuntimeBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    companion object {
        /** Android's own limit is five starts per thirty seconds; this stays well under it. */
        const val MIN_SCAN_INTERVAL_MILLIS = 6_000L

        /** A dongle in the socket answers within seconds; after this it is not there. */
        const val SCAN_DURATION_MILLIS = 30_000L
    }
}
