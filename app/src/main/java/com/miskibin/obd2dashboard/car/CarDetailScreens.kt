package com.miskibin.obd2dashboard.car

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.miskibin.obd2dashboard.ObdHolder
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.AlertComparison
import com.miskibin.obd2dashboard.data.AlertRule
import com.miskibin.obd2dashboard.data.DtcDescriptions
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.MonitorNames
import com.miskibin.obd2dashboard.obd.Dtc
import com.miskibin.obd2dashboard.obd.DtcKind
import com.miskibin.obd2dashboard.obd.FreezeFrame
import com.miskibin.obd2dashboard.obd.FreezeFrames
import com.miskibin.obd2dashboard.obd.MonitorState
import com.miskibin.obd2dashboard.obd.Readiness
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.label
import kotlinx.coroutines.launch

/*
 * The screens a row on the car dashboard leads to.
 *
 * Each is deliberately a leaf: it is pushed, read, and dismissed with the back button. The
 * host allows a task only a handful of navigation steps, so nothing here pushes anything
 * else, and the three that only display something hold no state at all.
 */

/** What a stored code means, in the language the head unit is running in. */
class CarFaultScreen(carContext: CarContext, private val dtc: Dtc) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val language = carContext.resources.configuration.locales[0].language
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(dtc.code)
                    .addText(DtcDescriptions.describe(dtc.code).forLanguage(language))
                    .build(),
            )
            .addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_app_fault_status))
                    .addText(
                        carContext.getString(
                            when (dtc.kind) {
                                DtcKind.Stored -> R.string.car_app_fault_stored_long
                                DtcKind.Pending -> R.string.car_app_fault_pending_long
                                DtcKind.Permanent -> R.string.car_app_fault_permanent_long
                            },
                        ),
                    )
                    .build(),
            )
        dtc.ecu?.let { ecu ->
            pane.addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_app_fault_ecu))
                    .addText(ecu)
                    .build(),
            )
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle(dtc.code)
            .setHeaderAction(Action.BACK)
            .build()
    }
}

/** Which of the emissions self-tests the car has finished since the codes were last cleared. */
class CarMonitorsScreen(
    carContext: CarContext,
    private val readiness: Readiness,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val language = carContext.resources.configuration.locales[0].language
        val list = ItemList.Builder()
        readiness.supported.forEach { monitor ->
            val complete = monitor.state == MonitorState.Complete
            val text = carContext.getString(
                if (complete) {
                    R.string.car_app_monitor_complete
                } else {
                    R.string.car_app_monitor_incomplete
                },
            )
            list.addItem(
                Row.Builder()
                    .setTitle(MonitorNames[monitor.id].forLanguage(language))
                    .addText(
                        if (complete) {
                            text
                        } else {
                            SpannableString(text).also {
                                it.setSpan(
                                    ForegroundCarColorSpan.create(CarColor.YELLOW),
                                    0,
                                    it.length,
                                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                                )
                            }
                        },
                    )
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle(carContext.getString(R.string.car_app_monitors_title))
            .setHeaderAction(Action.BACK)
            .build()
    }
}

/** What the engine was doing at the instant the ECU set a code. */
class CarFreezeFrameScreen(
    carContext: CarContext,
    private val frame: FreezeFrame,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        // In the order a mechanic reads them rather than by PID number, and only the ones
        // this car actually filled in.
        FreezeFrames.pids.forEach { pid ->
            val raw = frame.values[pid.id] ?: return@forEach
            val metric = Metrics[MetricId.Sensor(pid.id)]
            list.addItem(
                Row.Builder()
                    .setTitle(metric?.label(carContext) ?: pid.name)
                    .addText(
                        "${formatReading(raw, metric?.decimals ?: 0)} ${pid.unit}".trim(),
                    )
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle(
                frame.triggerCode?.let {
                    carContext.getString(R.string.car_app_freeze_for_code, it)
                } ?: carContext.getString(R.string.car_app_freeze_title),
            )
            .setHeaderAction(Action.BACK)
            .build()
    }
}

/** The alert lines the driver set on the phone, so the car screen can be trusted to agree. */
class CarThresholdsScreen(
    carContext: CarContext,
    private val rules: List<AlertRule>,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        rules.filter { it.enabled }.forEach { rule ->
            val metric = Metrics[rule.metric] ?: return@forEach
            val threshold =
                "${formatReading(rule.threshold, metric.decimals)} ${metric.unit}".trim()
            list.addItem(
                Row.Builder()
                    .setTitle(metric.label(carContext))
                    .addText(
                        carContext.getString(
                            when (rule.comparison) {
                                AlertComparison.Above -> R.string.car_app_alert_above
                                AlertComparison.Below -> R.string.car_app_alert_below
                            },
                            threshold,
                        ),
                    )
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle(carContext.getString(R.string.car_app_session_thresholds))
            .setHeaderAction(Action.BACK)
            .build()
    }
}

/**
 * Confirms erasing the stored codes.
 *
 * Asked rather than done, because clearing is not undoable and costs more than the codes:
 * it resets the readiness monitors too, and a car that has not since completed a drive
 * cycle fails an emissions inspection on that alone.
 */
class CarClearCodesScreen(carContext: CarContext) : Screen(carContext) {

    private var working = false

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(carContext.getString(R.string.car_app_clear_text))
            .setTitle(carContext.getString(R.string.car_app_clear_title))
            .setHeaderAction(Action.BACK)
            .setLoading(working)
            .apply {
                if (!working) {
                    addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.car_app_cancel))
                            .setOnClickListener { screenManager.pop() }
                            .build(),
                    )
                    addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.car_app_clear_confirm))
                            .setOnClickListener(::clear)
                            .build(),
                    )
                }
            }
            .build()

    private fun clear() {
        if (working) return
        working = true
        invalidate()
        lifecycleScope.launch {
            val cleared = ObdHolder.connection.clearDtcs()
            CarToast.makeText(
                carContext,
                carContext.getString(
                    if (cleared) R.string.car_app_clear_done else R.string.car_app_clear_failed,
                ),
                CarToast.LENGTH_LONG,
            ).show()
            screenManager.pop()
        }
    }
}
