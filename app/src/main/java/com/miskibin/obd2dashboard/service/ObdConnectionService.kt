package com.miskibin.obd2dashboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.miskibin.obd2dashboard.MainActivity
import com.miskibin.obd2dashboard.ObdHolder
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.ble.ConnectionState
import com.miskibin.obd2dashboard.data.LocalePreference
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.valueOf
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps the BLE link and the polling loop alive while the app is not in front.
 *
 * The service does not own a connection of its own — it holds the process up around the
 * one in [ObdHolder], which is the same instance the UI reads from. Without it Android
 * would freeze the polling coroutines the moment the driver switched to navigation.
 */
class ObdConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var localised: Context

    override fun onCreate() {
        super.onCreate()
        localised = LocalePreference.wrap(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ObdHolder.connection.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(notification(ObdHolder.connection.state.value, ObdHolder.connection.snapshot.value))
        observeState()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Rebuilds the notification at most once a second: the scheduler publishes several
     * snapshots per second and the shade cannot usefully show that.
     */
    private fun observeState() {
        combine(
            ObdHolder.connection.state,
            ObdHolder.connection.snapshot,
        ) { state, snapshot -> state to snapshot }
            .conflate()
            .onEach { (state, snapshot) ->
                notificationManager().notify(NOTIFICATION_ID, notification(state, snapshot))
                delay(NOTIFICATION_REFRESH_MILLIS)
            }
            .launchIn(scope)
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(state: ConnectionState, snapshot: VehicleSnapshot): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ObdConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titleFor(state))
            .setContentText(liveValues(snapshot))
            .setContentIntent(open)
            .addAction(0, localised.getString(R.string.action_disconnect), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun titleFor(state: ConnectionState): String = when (state) {
        is ConnectionState.Connected -> if (state.demo) {
            localised.getString(R.string.status_demo)
        } else {
            localised.getString(R.string.status_connected_to, state.device.name ?: state.device.address)
        }

        is ConnectionState.Connecting -> localised.getString(R.string.status_connecting)
        is ConnectionState.Initializing -> localised.getString(R.string.status_initializing, state.step)
        is ConnectionState.Reconnecting ->
            localised.getString(R.string.status_reconnecting, state.attempt)

        ConnectionState.Scanning -> localised.getString(R.string.status_scanning)
        is ConnectionState.Error -> localised.getString(R.string.status_error)
        ConnectionState.Idle -> localised.getString(R.string.status_disconnected)
    }

    /** RPM and coolant: the two numbers worth glancing at from the shade. */
    private fun liveValues(snapshot: VehicleSnapshot): String {
        val parts = listOfNotNull(
            snapshot.valueOf(Metrics.Rpm)?.let {
                localised.getString(R.string.notification_rpm, it.toInt())
            },
            snapshot.valueOf(Metrics.CoolantTemp)?.let {
                localised.getString(R.string.notification_coolant, it.toInt())
            },
        )
        return if (parts.isEmpty()) {
            localised.getString(R.string.notification_waiting_for_data)
        } else {
            parts.joinToString(SEPARATOR)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            localised.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = localised.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "obd_connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.miskibin.obd2dashboard.STOP"
        private const val NOTIFICATION_REFRESH_MILLIS = 1_000L
        private const val SEPARATOR = "  ·  "

        fun start(context: Context) {
            val intent = Intent(context, ObdConnectionService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ObdConnectionService::class.java))
        }
    }
}
