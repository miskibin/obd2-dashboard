package com.miskibin.obd2dashboard.car

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.miskibin.obd2dashboard.ObdHolder
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.ble.ConnectionState
import com.miskibin.obd2dashboard.data.AlertComparison
import com.miskibin.obd2dashboard.data.AlertRule
import com.miskibin.obd2dashboard.data.AlertRules
import com.miskibin.obd2dashboard.data.GearEstimator
import com.miskibin.obd2dashboard.data.GearReading
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.isBreached
import com.miskibin.obd2dashboard.data.reportedGear
import com.miskibin.obd2dashboard.data.updatedAtOf
import com.miskibin.obd2dashboard.data.valueOf
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The dashboard as a car screen: one drawn dial, and the readings that need words as rows.
 *
 * The design this follows is a full dashboard — a rev counter with the speed inside it, a
 * gear strip, live traces and an alert card. A projected app cannot draw a screen; the host
 * lays out templates in its own type and its own colours, and the only surface the app owns
 * is an image inside one. So the split is deliberate rather than a compromise made twice:
 * what a driver takes in at a glance — speed, revs, gear — is drawn by [CarGauge] and goes
 * in the pane's image, and what has to be *read* — the three temperatures and the load —
 * goes in rows, where the host can size them for the car it is running on.
 *
 * The car screen shows what the phone already knows: [ObdHolder]'s snapshot, read on a slow
 * tick rather than collected per sample. A dashboard that redrew at polling rate would ask
 * the host for more refreshes than templates are for; once a second is the cadence a glance
 * can use, and reading `.value` at draw time means a tick never shows anything older than
 * the tick itself.
 *
 * Not connected is a message, not an empty pane: connecting involves picking an adapter,
 * which is a parked-phone errand the car screen must not offer.
 */
class LiveDataScreen(carContext: CarContext) : Screen(carContext) {

    private var imperial = false
    private var redline = Metrics.REDLINE_DEFAULT
    private var rules: List<AlertRule> = AlertRules.defaults

    /**
     * The car screen's own estimator, and not a second reader of the phone's.
     *
     * The gear is worked out in the view model, which only exists while the phone's UI
     * does — and the usual way to use this screen is with the phone face down in a cradle.
     * Two estimators cost nothing: it is arithmetic over snapshots either side, with no
     * connection of its own.
     */
    private val gears = GearEstimator()
    private var gear: GearReading = GearReading.NONE

    /** The highest the engine has been this session, which is the footer's whole content. */
    private var sessionMaxRpm = 0

    /* The last card drawn, kept so a tick where nothing moved costs nothing. */
    private var lastFace: GaugeFace? = null
    private var lastImage: CarIcon? = null

    init {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Collectors of their own rather than a re-read per tick: these preferences
                // almost never change, so the flows are quiet and the tick stays cheap.
                launch { ObdHolder.preferences.imperialUnits.collect { imperial = it } }
                launch { ObdHolder.preferences.redline.collect { redline = it } }
                launch { ObdHolder.preferences.alertRules.collect { rules = it } }
                launch { watchGear() }
                launch { watchConnection() }
                while (isActive) {
                    invalidate()
                    delay(REFRESH_MILLIS)
                }
            }
        }
    }

    /**
     * Feeds the estimator every snapshot rather than every tick.
     *
     * It counts distinct measurements to decide a ratio has held still, so it has to see
     * what the adapter actually delivered; sampling it once a second would hand it three
     * copies of one reading and call that agreement.
     */
    private suspend fun watchGear() {
        ObdHolder.connection.snapshot.collect { snapshot ->
            val rpm = snapshot.valueOf(Metrics.Rpm)
            if (rpm != null && rpm > sessionMaxRpm) sessionMaxRpm = rpm.roundToInt()
            val estimated = gears.observe(
                rpm = rpm,
                speed = snapshot.valueOf(Metrics.Speed),
                rpmAtMillis = snapshot.updatedAtOf(Metrics.Rpm),
                speedAtMillis = snapshot.updatedAtOf(Metrics.Speed),
            )
            gear = snapshot.reportedGear() ?: estimated
        }
    }

    /** A session's worth of learning belongs to that session, exactly as on the phone. */
    private suspend fun watchConnection() {
        ObdHolder.connection.state.collect { state ->
            if (state is ConnectionState.Idle) {
                gears.reset()
                sessionMaxRpm = 0
                gear = GearReading.NONE
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
        val readings = if (connected) shownMetrics(snapshot) else emptyList()
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
                readings.isEmpty() -> setLoading(true)
                else -> {
                    readings.forEach { addRow(rowOf(it, snapshot)) }
                    setImage(image(snapshot, readings))
                }
            }
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle(carContext.getString(R.string.car_app_live_title))
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    /**
     * Which readings this car gets rows, decided by what it supports rather than by what
     * has landed.
     *
     * The host treats a pane whose rows changed as a new screen rather than a refresh, and
     * spends one of a handful of allowed steps on it. Oil temperature is the case that
     * matters: a car at ignition-on answers `NO DATA` for it and then answers perfectly
     * once the engine runs, so a list built from arrived values would rebuild itself the
     * moment the driver turned the key. The supported-PID list is known at connect time and
     * does not move after it.
     */
    private fun shownMetrics(snapshot: VehicleSnapshot): List<Metric> {
        val supported = ObdHolder.connection.supportedPids.value
        return ROWS.mapNotNull(Metrics::get).filter { metric ->
            when (val id = metric.id) {
                // The adapter's own voltage is not a PID and is never listed as supported.
                MetricId.Battery -> true
                is MetricId.Sensor -> id.pid in supported || snapshot.valueOf(id) != null
                else -> false
            }
        }.take(MAX_ROWS)
    }

    /**
     * One reading as a row, with the value coloured by the driver's own alert rule.
     *
     * The design says a reading past its line goes amber and one well past it goes red;
     * the line is the rule the driver set in Settings, so the car screen and the phone
     * cannot disagree about what "too hot" means.
     */
    private fun rowOf(metric: Metric, snapshot: VehicleSnapshot): Row {
        val raw = snapshot.valueOf(metric.id)
        val text = if (raw == null) {
            SpannableString(carContext.getString(R.string.car_app_no_reading))
        } else {
            val (value, unit) = converted(metric, raw)
            val rule = breach(metric, raw)
            val colour = when {
                rule == null -> null
                severe(rule, raw) -> CarColor.RED
                else -> CarColor.YELLOW
            }
            SpannableString("${formatReading(value, metric.decimals)} $unit".trim()).also {
                if (colour != null) {
                    it.setSpan(
                        ForegroundCarColorSpan.create(colour),
                        0,
                        it.length,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        return Row.Builder()
            .setTitle(metric.label(carContext))
            .addText(text)
            .build()
    }

    /** The drawn card, rebuilt only when something on it would look different. */
    private fun image(snapshot: VehicleSnapshot, readings: List<Metric>): CarIcon {
        val face = faceOf(snapshot, readings)
        val cached = lastImage
        if (cached != null && face == lastFace) return cached
        val image = CarIcon.Builder(IconCompat.createWithBitmap(CarGauge.render(face))).build()
        lastFace = face
        lastImage = image
        return image
    }

    private fun faceOf(snapshot: VehicleSnapshot, readings: List<Metric>): GaugeFace {
        val speedMetric = Metrics[Metrics.Speed]
        val rawSpeed = snapshot.valueOf(Metrics.Speed)
        val speed = rawSpeed?.let { raw ->
            if (imperial) raw * Metrics.MILES_PER_KM else raw
        }
        val speedUnit = when {
            imperial -> carContext.getString(R.string.unit_mph)
            else -> speedMetric?.unit.orEmpty()
        }
        // Rounded to ten because the last digit of a tachometer never stops moving, and a
        // card that redrew for it would be a new bitmap across the binder every tick.
        val rpm = snapshot.valueOf(Metrics.Rpm)?.let { (it / 10).roundToInt() * 10 }
        val nearRedline = rpm != null && rpm >= redline * NEAR_REDLINE

        return GaugeFace(
            speed = speed?.let { formatReading(it, 0) } ?: NO_VALUE,
            speedUnit = speedUnit.uppercase(Locale.getDefault()),
            revs = rpm?.toString() ?: NO_VALUE,
            revsUnit = Metrics[Metrics.Rpm]?.unit.orEmpty(),
            rpm = rpm,
            redline = redline,
            gear = gear.gear,
            gearCount = GearEstimator.MAX_GEARS,
            gearLabel = carContext.getString(
                if (gear.measured) R.string.car_app_gear else R.string.car_app_gear_estimated,
            ).uppercase(Locale.getDefault()),
            note = when {
                nearRedline -> carContext.getString(R.string.car_app_near_redline)
                sessionMaxRpm > 0 ->
                    carContext.getString(R.string.car_app_session_max, sessionMaxRpm)

                else -> ""
            },
            redlineNote = carContext.getString(R.string.car_app_redline_short, redlineLabel()),
            alert = alertOf(snapshot, readings),
        )
    }

    /**
     * The worst rule the car is currently on the wrong side of, as the design's banner.
     *
     * Only over the readings that have a row: a warning about something the screen is not
     * showing would be a number the driver has no way to look at.
     */
    private fun alertOf(snapshot: VehicleSnapshot, readings: List<Metric>): GaugeFace.Alert? {
        val worst = readings.mapNotNull { metric ->
            val raw = snapshot.valueOf(metric.id) ?: return@mapNotNull null
            val rule = breach(metric, raw) ?: return@mapNotNull null
            Triple(metric, raw, rule)
        }.maxByOrNull { (_, raw, rule) -> if (severe(rule, raw)) 1 else 0 } ?: return null

        val (metric, raw, rule) = worst
        val (value, unit) = converted(metric, raw)
        val threshold = "${formatReading(rule.threshold, metric.decimals)} ${metric.unit}".trim()
        return GaugeFace.Alert(
            title = "${metric.label(carContext)} " +
                "${formatReading(value, metric.decimals)} $unit".trim(),
            body = carContext.getString(
                when (rule.comparison) {
                    AlertComparison.Above -> R.string.car_app_alert_above
                    AlertComparison.Below -> R.string.car_app_alert_below
                },
                threshold,
            ),
            severe = severe(rule, raw),
        )
    }

    /** The enabled rule this reading is currently breaching, or null. */
    private fun breach(metric: Metric, raw: Double): AlertRule? =
        rules.firstOrNull { it.enabled && it.metric == metric.id && it.isBreached(raw) }

    /**
     * Whether a breach is the design's second level rather than its first.
     *
     * The design draws amber for a reading that has just crossed its line and red for one
     * that is well past it, at a twelfth of the band beyond. The band here is the range the
     * rule's own editor offers, which is the only statement in the app of how wide the
     * interesting span of that reading is.
     */
    private fun severe(rule: AlertRule, raw: Double): Boolean {
        val margin = (rule.range.endInclusive - rule.range.start) * SEVERE_FRACTION
        return when (rule.comparison) {
            AlertComparison.Above -> raw > rule.threshold + margin
            AlertComparison.Below -> raw < rule.threshold - margin
        }
    }

    /** The one conversion the phone dashboard also does: speed into miles when asked. */
    private fun converted(metric: Metric, raw: Double): Pair<Double, String> =
        if (metric.id == Metrics.Speed && imperial) {
            raw * Metrics.MILES_PER_KM to carContext.getString(R.string.unit_mph)
        } else {
            raw to metric.unit
        }

    /** 8 000 as "8k" and 6 500 as "6.5k", which is how a redline is spoken about. */
    private fun redlineLabel(): String {
        val thousands = redline / 1_000.0
        val decimals = if (redline % 1_000 == 0) 0 else 1
        return String.format(Locale.getDefault(), "%.${decimals}fk", thousands)
    }

    private companion object {
        /**
         * What earns a row on a screen read in half-second glances: the two temperatures
         * that say whether the engine minds, the voltage that says whether it will start
         * again, and how hard it is being asked to work.
         *
         * Speed, revs and gear are not here — they are the drawn card.
         */
        val ROWS = listOf(
            MetricId.Sensor(Pids.OIL_TEMP),
            MetricId.Sensor(Pids.COOLANT_TEMP),
            MetricId.Battery,
            MetricId.Sensor(Pids.ENGINE_LOAD),
        )

        /** What a pane will hold; more rows than this are silently dropped by the host. */
        const val MAX_ROWS = 4

        const val REFRESH_MILLIS = 1_000L

        /** Where the dial turns red, matching the phone's rev bar. */
        const val NEAR_REDLINE = 0.92

        /** How far past its line a reading has to be to stop being merely a warning. */
        const val SEVERE_FRACTION = 0.12

        const val NO_VALUE = "—"
    }
}
