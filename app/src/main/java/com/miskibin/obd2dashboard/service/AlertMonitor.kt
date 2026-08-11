package com.miskibin.obd2dashboard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.miskibin.obd2dashboard.MainActivity
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.AlertComparison
import com.miskibin.obd2dashboard.data.AlertEvent
import com.miskibin.obd2dashboard.data.AlertEvaluator
import com.miskibin.obd2dashboard.data.AlertRule
import com.miskibin.obd2dashboard.data.LocalePreference
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Locale

/**
 * Watches the live snapshot for threshold breaches and says so loudly.
 *
 * A breach is worth interrupting for, so it gets its own high-importance channel with
 * sound and vibration rather than riding along on the quiet connection notification. The
 * same events also go out on [events] for the in-app banner, because a driver looking at
 * the dashboard should not have to pull down the shade to learn the engine is boiling.
 *
 * All the "don't cry wolf" logic lives in [AlertEvaluator]; this class only wires it to
 * Android.
 */
class AlertMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val rules: Flow<List<AlertRule>>,
    private val snapshots: Flow<VehicleSnapshot>,
    private val evaluator: AlertEvaluator = AlertEvaluator(),
) {
    private val appContext = context.applicationContext

    private val _events = MutableSharedFlow<AlertEvent>(extraBufferCapacity = BUFFER)
    val events: SharedFlow<AlertEvent> = _events.asSharedFlow()

    private var nextId = FIRST_NOTIFICATION_ID

    fun start() {
        createChannel()
        combine(rules, snapshots.conflate()) { rules, snapshot -> rules to snapshot }
            .onEach { (rules, snapshot) ->
                evaluator.evaluate(rules, snapshot).forEach { event ->
                    _events.tryEmit(event)
                    notify(event)
                }
            }
            .launchIn(scope)
    }

    private fun notify(event: AlertEvent) {
        val localised = LocalePreference.wrap(appContext)
        val open = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(localised.getString(R.string.alert_notification_title))
            .setContentText(describe(localised, event))
            .setStyle(NotificationCompat.BigTextStyle().bigText(describe(localised, event)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        // Each alert gets its own id so a second one does not replace the first.
        runCatching { notificationManager().notify(nextId++, notification) }
    }

    private fun createChannel() {
        val localised = LocalePreference.wrap(appContext)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localised.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = localised.getString(R.string.alert_channel_description)
            enableVibration(true)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    companion object {
        const val CHANNEL_ID = "obd_alerts"
        private const val FIRST_NOTIFICATION_ID = 100
        private const val BUFFER = 8
    }
}

/** "Coolant temperature is 108 °C, above the 105 °C limit" — the one line both surfaces use. */
fun describe(context: Context, event: AlertEvent): String {
    val metric = Metrics[event.rule.metric]
    val name = context.getString(metric?.nameRes ?: R.string.metric_unknown)
    val decimals = metric?.decimals ?: 1
    val unit = metric?.unit.orEmpty()
    val template = when (event.rule.comparison) {
        AlertComparison.Above -> R.string.alert_above
        AlertComparison.Below -> R.string.alert_below
    }
    return context.getString(
        template,
        name,
        withUnit(event.value, decimals, unit),
        withUnit(event.rule.threshold, decimals, unit),
    )
}

private fun withUnit(value: Double, decimals: Int, unit: String): String =
    "%.${decimals}f %s".format(Locale.getDefault(), value, unit).trim()
