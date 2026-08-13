package com.miskibin.obd2dashboard.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.miskibin.obd2dashboard.ObdHolder
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.ble.ConnectionState
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.valueOf
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The handful of readings worth a glance at speed, as rows the Auto host draws.
 *
 * The car screen shows what the phone already knows: [ObdHolder]'s snapshot, read on a
 * slow tick rather than collected per sample. A dashboard that redrew at polling rate
 * would ask the host for more refreshes than templates are for; once a second is the
 * cadence a glance can use, and reading `.value` at draw time means a tick never shows
 * anything older than the tick itself.
 *
 * Not connected is a message, not an empty pane: connecting involves picking an adapter,
 * which is a parked-phone errand the car screen must not offer.
 */
class LiveDataScreen(carContext: CarContext) : Screen(carContext) {

    private var imperial = false

    init {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // A collector of its own rather than a re-read per tick: the preference
                // almost never changes, so the flow is quiet and the tick stays cheap.
                launch { ObdHolder.preferences.imperialUnits.collect { imperial = it } }
                while (isActive) {
                    invalidate()
                    delay(REFRESH_MILLIS)
                }
            }
        }
    }

    /**
     * Always a [PaneTemplate], whatever the connection is doing. The host counts a change
     * of template *type* as a navigation step and terminates the task after a handful of
     * them, so a screen that flipped to a message template while an unsteady adapter link
     * came and went would spend its whole step quota on one bad stretch of road.
     */
    override fun onGetTemplate(): Template {
        val connected = ObdHolder.connection.state.value is ConnectionState.Connected
        val snapshot = ObdHolder.connection.snapshot.value
        val rows = if (connected) ROWS.mapNotNull { id -> rowOf(id, snapshot) } else emptyList()
        val pane = Pane.Builder().apply {
            when {
                !connected -> addRow(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.car_app_disconnected_title))
                        .addText(carContext.getString(R.string.car_app_disconnected_text))
                        .build(),
                )
                // A pane is not allowed to be empty, and right after the handshake it
                // would be: the connection is up but no reading has arrived yet.
                rows.isEmpty() -> setLoading(true)
                else -> rows.forEach(::addRow)
            }
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle(carContext.getString(R.string.car_app_live_title))
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    /**
     * One reading as a row, or null for a metric this car has never answered for — a row
     * of dashes on a car screen is a question, and the pane simply skips it instead.
     */
    private fun rowOf(id: MetricId, snapshot: VehicleSnapshot): Row? {
        val metric = Metrics[id] ?: return null
        val raw = snapshot.valueOf(id) ?: return null
        val (value, unit) = converted(id, metric, raw)
        return Row.Builder()
            .setTitle(metric.label(carContext))
            .addText("${formatReading(value, metric.decimals)} $unit".trim())
            .build()
    }

    /** The one conversion the phone dashboard also does: speed into miles when asked. */
    private fun converted(id: MetricId, metric: Metric, raw: Double): Pair<Double, String> =
        if (id == Metrics.Speed && imperial) {
            raw * Metrics.MILES_PER_KM to carContext.getString(R.string.unit_mph)
        } else {
            raw to metric.unit
        }

    private companion object {
        /**
         * What earns a place on a screen read in half-second glances: how fast, how hard,
         * and the two temperatures that say whether the engine minds.
         */
        val ROWS = listOf(
            Metrics.Speed,
            Metrics.Rpm,
            MetricId.Sensor(Pids.COOLANT_TEMP),
            MetricId.Sensor(Pids.ENGINE_LOAD),
            MetricId.Battery,
        )

        const val REFRESH_MILLIS = 1_000L
    }
}
