package com.miskibin.obd2dashboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.miskibin.obd2dashboard.ble.ConnectionIssue
import com.miskibin.obd2dashboard.ble.ConnectionManager
import com.miskibin.obd2dashboard.ble.DeviceKind
import com.miskibin.obd2dashboard.ble.DiscoveredDevice
import com.miskibin.obd2dashboard.data.AppPreferences
import com.miskibin.obd2dashboard.data.Garage
import com.miskibin.obd2dashboard.log.LogTag
import com.miskibin.obd2dashboard.log.ObdLog
import com.miskibin.obd2dashboard.data.MetricHistory
import com.miskibin.obd2dashboard.data.TripRecorder
import com.miskibin.obd2dashboard.data.TripRepository
import com.miskibin.obd2dashboard.obd.FuelType
import com.miskibin.obd2dashboard.service.AlertMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the connection.
 *
 * The UI and [com.miskibin.obd2dashboard.service.ObdConnectionService] must talk to the
 * *same* [ConnectionManager] — a second one would open a second GATT link to a dongle
 * that only accepts one. A plain singleton is enough here; a DI container would be
 * ceremony around a single object graph that never varies.
 */
@SuppressLint("StaticFieldLeak") // Everything here is built from the Application context.
object ObdHolder {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var connection: ConnectionManager
        private set
    lateinit var preferences: AppPreferences
        private set
    lateinit var history: MetricHistory
        private set
    lateinit var recorder: TripRecorder
        private set
    lateinit var trips: TripRepository
        private set
    lateinit var alerts: AlertMonitor
        private set

    private var installed = false

    fun install(application: Application) {
        if (installed) return
        installed = true
        connection = ConnectionManager(application, scope)
        preferences = AppPreferences(application)
        history = MetricHistory()
        recorder = TripRecorder(application, scope)
        trips = TripRepository(application)
        alerts = AlertMonitor(
            context = application,
            scope = scope,
            rules = preferences.alertRules,
            snapshots = connection.snapshot,
        )

        scope.launch { connection.snapshot.collect(history::record) }
        scope.launch { preferences.pollingEnabled.collect(connection::setPollingEnabled) }
        // The fuel maths runs inside the polling loop, which has no idea which car it is
        // talking to; this is the one wire that tells it. Both sides move — the VIN when a
        // session opens, the garage when the profile is edited — so it is a combine rather
        // than a read at connect time, and editing the profile changes the gauges at once.
        scope.launch {
            combine(connection.vin, preferences.vehicles) { vin, garage ->
                Garage.find(garage, vin)?.fuel ?: FuelType.Default
            }.collect(connection::setFuelType)
        }
        alerts.start()
    }

    /**
     * Reconnects to the remembered adapter without asking anything of the driver — the
     * app is normally launched already sitting in a mount with the dongle plugged in.
     *
     * A missing permission is reported rather than returned as a bare `false`: silently
     * doing nothing here is how the app ends up looking broken to somebody who revoked
     * Bluetooth access in system settings and has no idea that is what they did.
     */
    suspend fun autoConnect(context: Context): Boolean {
        val saved = preferences.savedAdapter.first() ?: return false
        if (!hasConnectPermission(context)) {
            ObdLog.log(LogTag.CONN, "auto-connect skipped: BLUETOOTH_CONNECT not granted")
            connection.reportSetupIssue(
                ConnectionIssue.ConnectPermission,
                "BLUETOOTH_CONNECT not granted",
            )
            return false
        }
        ObdLog.log(
            LogTag.CONN,
            "auto-connecting to ${saved.address} (${if (saved.classic) "classic" else "LE"})",
        )
        connection.connect(
            DiscoveredDevice(
                address = saved.address,
                name = saved.name,
                rssi = 0,
                looksLikeAdapter = true,
                kind = if (saved.classic) DeviceKind.Classic else DeviceKind.Le,
                bonded = saved.classic,
            ),
        )
        return true
    }

    fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
