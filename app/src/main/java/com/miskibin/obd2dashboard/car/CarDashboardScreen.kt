package com.miskibin.obd2dashboard.car

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.OnClickListener
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
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
import com.miskibin.obd2dashboard.data.DtcDescriptions
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.MetricHistory
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.MonitorNames
import com.miskibin.obd2dashboard.data.RecordingState
import com.miskibin.obd2dashboard.data.isBreached
import com.miskibin.obd2dashboard.data.valueOf
import com.miskibin.obd2dashboard.obd.Diagnostics
import com.miskibin.obd2dashboard.obd.Dtc
import com.miskibin.obd2dashboard.obd.DtcKind
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The app on the car's own screen: three tabs over the same live connection.
 *
 * The design this follows starts from what the car already tells the driver. A Mazda has a
 * speedometer and a tachometer of its own, so neither is here; what earns a place is what
 * the dash cannot say — how hot the oil actually is, what the alternator is doing, how far
 * the mixture has been corrected — and what the ECU has stored away.
 *
 * Everything except each tile's picture is drawn by the host in its own type and its own
 * colours, which is what keeps a projected app legal to glance at. The picture is the one
 * surface the app owns, and [CarTile] fills it with the reading, its last half minute and
 * the driver's own threshold.
 *
 * The screen is a second reader of [ObdHolder]'s snapshot, never a second connection: the
 * phone side owns the adapter whether or not its UI exists.
 */
class CarDashboardScreen(carContext: CarContext) : Screen(carContext) {

    private enum class CarTab(
        val contentId: String,
        val titleRes: Int,
        val iconRes: Int,
    ) {
        Live("live", R.string.car_app_tab_live, R.drawable.ic_car_gauge),
        Faults("faults", R.string.car_app_tab_faults, R.drawable.ic_car_fault),
        Session("session", R.string.car_app_tab_session, R.drawable.ic_car_record),
    }

    private var tab = CarTab.Live
    private var rules: List<AlertRule> = AlertRules.defaults

    /**
     * How many tiles this host will show, settled once.
     *
     * The library guarantees a grid takes at least six, which is the design's layout, but a
     * head unit is free to allow fewer. Asked once at construction rather than per tick: a
     * grid whose item count changed would count as a new screen against the host's handful
     * of allowed navigation steps rather than as a refresh of this one.
     */
    private val tiles: List<TileSpec> = TILES.take(
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
            .coerceIn(1, TILES.size),
    )

    private val rowLimit: Int = carContext.getCarService(ConstraintManager::class.java)
        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        .coerceAtLeast(MIN_ROWS)

    /** The last picture sent for each tile, so a tick that changed nothing costs nothing. */
    private val drawn = HashMap<MetricId, Pair<TileFace, CarIcon>>()

    init {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ObdHolder.preferences.alertRules.collect { rules = it } }
                while (isActive) {
                    invalidate()
                    delay(REFRESH_MILLIS)
                }
            }
        }
    }

    /**
     * Tabs where the host has them, and the live grid alone where it does not.
     *
     * [TabTemplate] needs car API level 6. Below that the honest thing is to show the one
     * tab that is worth the drive — faults and session controls are errands, and an errand
     * the driver cannot reach in the car is one they will do on the phone anyway.
     */
    override fun onGetTemplate(): Template {
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_6) {
            return GridTemplate.Builder()
                .setTitle(carContext.getString(R.string.car_app_live_title))
                .setHeaderAction(Action.APP_ICON)
                .setSingleList(liveList())
                .build()
        }
        val callback = object : TabTemplate.TabCallback {
            override fun onTabSelected(contentId: String) {
                tab = CarTab.entries.firstOrNull { it.contentId == contentId } ?: CarTab.Live
                invalidate()
            }
        }
        return TabTemplate.Builder(callback)
            .setHeaderAction(Action.APP_ICON)
            .apply { CarTab.entries.forEach { addTab(tabOf(it)) } }
            .setActiveTabContentId(tab.contentId)
            .setTabContents(TabContents.Builder(contentOf(tab)).build())
            .build()
    }

    private fun tabOf(entry: CarTab): Tab = Tab.Builder()
        .setContentId(entry.contentId)
        .setTitle(carContext.getString(entry.titleRes))
        .setIcon(icon(entry.iconRes))
        .build()

    private fun contentOf(entry: CarTab): Template = when (entry) {
        CarTab.Live -> GridTemplate.Builder().setSingleList(liveList()).build()
        CarTab.Faults -> faultsTemplate()
        CarTab.Session -> GridTemplate.Builder().setSingleList(sessionList()).build()
    }

    /* ---------------------------------------------------------------- live ---- */

    private fun liveList(): ItemList {
        val snapshot = ObdHolder.connection.snapshot.value
        val list = ItemList.Builder()
        tiles.forEach { spec -> Metrics[spec.metric]?.let { list.addItem(tile(spec, it, snapshot)) } }
        return list.build()
    }

    /**
     * One reading as a grid item: the app's picture, the host's label.
     *
     * The tile is drawn from the driver's own alert rule rather than from a constant, so
     * the line on the card and the line that raises a notification are the same line.
     */
    private fun tile(spec: TileSpec, metric: Metric, snapshot: VehicleSnapshot): GridItem {
        val raw = snapshot.valueOf(metric.id)
        val rule = rules.firstOrNull { it.enabled && it.metric == metric.id }
        val level = level(rule, raw)
        val face = TileFace(
            value = raw?.let { formatReading(it, metric.decimals) }
                ?: carContext.getString(R.string.car_app_no_reading),
            unit = metric.unit,
            trace = trace(metric.id),
            mark = (rule?.threshold ?: spec.fallbackMark)?.toFloat(),
            level = level,
            dark = carContext.isDarkMode,
        )
        val item = GridItem.Builder()
            .setTitle(metric.label(carContext))
            .setImage(image(metric.id, face), GridItem.IMAGE_TYPE_LARGE)
        subtitle(metric, raw, rule, level)?.let { item.setText(it) }
        return item.build()
    }

    /**
     * The line under the reading: where its threshold is, or that it is on the wrong side
     * of it. A reading with no rule gets no line — a tile that said "in range" about a
     * number nobody set a range for would be inventing reassurance.
     */
    private fun subtitle(
        metric: Metric,
        raw: Double?,
        rule: AlertRule?,
        level: TileLevel,
    ): CharSequence? {
        if (raw == null) return carContext.getString(R.string.car_app_tile_waiting)
        if (rule == null) return null
        val threshold = "${formatReading(rule.threshold, metric.decimals)} ${metric.unit}".trim()
        if (level == TileLevel.Normal) {
            return carContext.getString(R.string.car_app_tile_threshold, threshold)
        }
        val text = carContext.getString(
            when (rule.comparison) {
                AlertComparison.Above -> R.string.car_app_alert_above
                AlertComparison.Below -> R.string.car_app_alert_below
            },
            threshold,
        )
        val colour = if (level == TileLevel.Bad) CarColor.RED else CarColor.YELLOW
        return SpannableString(text).also {
            it.setSpan(
                ForegroundCarColorSpan.create(colour),
                0,
                it.length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /**
     * The last half minute of a reading, thinned to what a tile can show.
     *
     * The history buffer takes a sample every tenth of a second, which is three hundred
     * points across a hundred and twenty pixels; resampling across the whole window rather
     * than taking the newest thirty keeps the trace about the half minute it claims to be.
     */
    private fun trace(id: MetricId): List<Float> {
        val samples = ObdHolder.history.series(id, MetricHistory.SPARKLINE_WINDOW_MILLIS)
        if (samples.size <= TRACE_SAMPLES) return samples.map { it.value }
        return List(TRACE_SAMPLES) { index ->
            samples[index * (samples.size - 1) / (TRACE_SAMPLES - 1)].value
        }
    }

    private fun image(id: MetricId, face: TileFace): CarIcon {
        drawn[id]?.let { (last, icon) -> if (last == face) return icon }
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(CarTile.render(face))).build()
        drawn[id] = face to icon
        return icon
    }

    /* -------------------------------------------------------------- faults ---- */

    private fun faultsTemplate(): Template {
        val connected = ObdHolder.connection.state.value is ConnectionState.Connected
        val diagnostics = ObdHolder.connection.diagnostics.value
        if (connected && diagnostics == null) {
            return ListTemplate.Builder().setLoading(true).build()
        }
        val list = ItemList.Builder()
        if (!connected) {
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_app_disconnected_title))
                    .addText(carContext.getString(R.string.car_app_disconnected_text))
                    .build(),
            )
            return ListTemplate.Builder().setSingleList(list.build()).build()
        }
        val codes = diagnostics!!.all
        if (codes.isEmpty()) {
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_app_faults_none_title))
                    .addText(carContext.getString(R.string.car_app_faults_none_text))
                    .build(),
            )
        } else {
            // Two places held back for the rows that are always worth having.
            codes.take(rowLimit - TRAILING_ROWS).forEach { list.addItem(faultRow(it)) }
        }
        list.addItem(readinessRow(diagnostics))
        list.addItem(freezeRow())
        return ListTemplate.Builder().setSingleList(list.build()).build()
    }

    private fun faultRow(dtc: Dtc): Row {
        val described = DtcDescriptions.describe(dtc.code).forLanguage(language())
        val kind = carContext.getString(
            when (dtc.kind) {
                DtcKind.Stored -> R.string.car_app_fault_stored
                DtcKind.Pending -> R.string.car_app_fault_pending
                DtcKind.Permanent -> R.string.car_app_fault_permanent
            },
        )
        // Pending means the ECU has seen it once and is not yet sure; the design gives that
        // amber and everything the ECU has committed to red.
        val colour = if (dtc.kind == DtcKind.Pending) CarColor.YELLOW else CarColor.RED
        val text = SpannableString(listOfNotNull(kind, dtc.ecu).joinToString(SEPARATOR)).also {
            it.setSpan(
                ForegroundCarColorSpan.create(colour),
                0,
                kind.length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
            )
        }
        return Row.Builder()
            .setTitle("${dtc.code}$SEPARATOR$described")
            .addText(text)
            .setImage(icon(R.drawable.ic_car_fault), Row.IMAGE_TYPE_SMALL)
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(CarFaultScreen(carContext, dtc)) }
            .build()
    }

    /**
     * How much of the emissions self-test the car has finished.
     *
     * Worth its own row because it is the one answer an owner cannot get from the dash and
     * needs before an inspection: cleared codes take a drive cycle to come back, and a car
     * that has not finished one fails on the spot.
     */
    private fun readinessRow(diagnostics: Diagnostics): Row {
        val readiness = diagnostics.monitorStatus?.readiness
        val row = Row.Builder().setImage(icon(R.drawable.ic_car_list), Row.IMAGE_TYPE_SMALL)
        if (readiness == null) {
            return row
                .setTitle(carContext.getString(R.string.car_app_readiness_title_unknown))
                .addText(carContext.getString(R.string.car_app_readiness_unknown))
                .build()
        }
        val supported = readiness.supported.size
        val done = supported - readiness.incomplete.size
        val text = if (readiness.ready) {
            carContext.getString(R.string.car_app_readiness_ready)
        } else {
            readiness.incomplete.joinToString(SHORT_SEPARATOR) {
                MonitorNames[it.id].forLanguage(language())
            }
        }
        return row
            .setTitle(carContext.getString(R.string.car_app_readiness_title, done, supported))
            .addText(text)
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(CarMonitorsScreen(carContext, readiness)) }
            .build()
    }

    private fun freezeRow(): Row {
        val frame = ObdHolder.connection.freezeFrame.value
        val row = Row.Builder()
            .setTitle(carContext.getString(R.string.car_app_freeze_title))
            .setImage(icon(R.drawable.ic_car_frame), Row.IMAGE_TYPE_SMALL)
        if (frame == null || frame.isEmpty) {
            return row.addText(carContext.getString(R.string.car_app_freeze_none)).build()
        }
        return row
            .addText(
                frame.triggerCode?.let {
                    carContext.getString(R.string.car_app_freeze_for_code, it)
                } ?: carContext.getString(R.string.car_app_freeze_stored),
            )
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(CarFreezeFrameScreen(carContext, frame)) }
            .build()
    }

    /* ------------------------------------------------------------- session ---- */

    private fun sessionList(): ItemList {
        val recording = ObdHolder.recorder.state.value as? RecordingState.Active
        return ItemList.Builder()
            .addItem(
                action(
                    iconRes = R.drawable.ic_car_record,
                    title = carContext.getString(R.string.car_app_session_record),
                    text = recording?.let {
                        carContext.getString(
                            R.string.car_app_session_recording,
                            elapsed(System.currentTimeMillis() - it.startedAtMillis),
                        )
                    } ?: carContext.getString(R.string.car_app_session_record_idle),
                    tint = if (recording != null) CarColor.RED else CarColor.DEFAULT,
                    onClick = ::toggleRecording,
                ),
            )
            .addItem(
                action(
                    iconRes = R.drawable.ic_car_frame,
                    title = carContext.getString(R.string.car_app_session_freeze),
                    text = carContext.getString(R.string.car_app_session_freeze_sub),
                    onClick = ::readFreezeFrame,
                ),
            )
            .addItem(
                action(
                    iconRes = R.drawable.ic_car_erase,
                    title = carContext.getString(R.string.car_app_session_clear),
                    text = carContext.getString(R.string.car_app_session_parked_only),
                    // Erasing codes is a workshop action, and the host is the only thing
                    // that actually knows whether the car is moving.
                    parkedOnly = true,
                    onClick = { screenManager.push(CarClearCodesScreen(carContext)) },
                ),
            )
            .addItem(
                action(
                    iconRes = R.drawable.ic_car_cog,
                    title = carContext.getString(R.string.car_app_session_thresholds),
                    text = carContext.getString(
                        R.string.car_app_session_thresholds_sub,
                        rules.count { it.enabled },
                    ),
                    parkedOnly = true,
                    onClick = { screenManager.push(CarThresholdsScreen(carContext, rules)) },
                ),
            )
            .build()
    }

    private fun action(
        iconRes: Int,
        title: String,
        text: String,
        tint: CarColor = CarColor.DEFAULT,
        parkedOnly: Boolean = false,
        onClick: () -> Unit,
    ): GridItem = GridItem.Builder()
        .setTitle(title)
        .setText(text)
        .setImage(icon(iconRes, tint), GridItem.IMAGE_TYPE_ICON)
        .setOnClickListener(
            if (parkedOnly) {
                ParkedOnlyOnClickListener.create(onClick)
            } else {
                OnClickListener(onClick)
            },
        )
        .build()

    private fun toggleRecording() {
        val recorder = ObdHolder.recorder
        if (recorder.isRecording) {
            recorder.stop()
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_app_session_record_stopped),
                CarToast.LENGTH_SHORT,
            ).show()
        } else {
            recorder.start(
                source = ObdHolder.connection.snapshot,
                metrics = tiles.map { it.metric },
                kind = ObdHolder.connection.sessionKind.value,
            )
        }
        invalidate()
    }

    /**
     * Asks the ECU for the snapshot it stored when it set a code, then shows it.
     *
     * A read rather than a look at what is already held: the frame is fetched once when a
     * session opens, and a driver reaching for this after a warning light wants what the
     * car has now.
     */
    private fun readFreezeFrame() {
        lifecycleScope.launch {
            val frame = ObdHolder.connection.readFreezeFrame()
            if (frame == null || frame.isEmpty) {
                CarToast.makeText(
                    carContext,
                    carContext.getString(R.string.car_app_freeze_none),
                    CarToast.LENGTH_LONG,
                ).show()
            } else {
                screenManager.push(CarFreezeFrameScreen(carContext, frame))
            }
        }
    }

    /* --------------------------------------------------------------- shared ---- */

    private fun icon(res: Int, tint: CarColor = CarColor.DEFAULT): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(tint).build()

    private fun language(): String =
        carContext.resources.configuration.locales[0].language

    /**
     * Which of the design's three levels a reading is at.
     *
     * Amber the moment it crosses the driver's line, red once it is well past — an eighth
     * of the band the rule's own editor offers, which is the app's only statement of how
     * wide the interesting span of that reading is.
     */
    private fun level(rule: AlertRule?, raw: Double?): TileLevel {
        if (rule == null || raw == null || !rule.isBreached(raw)) return TileLevel.Normal
        val margin = (rule.range.endInclusive - rule.range.start) * SEVERE_FRACTION
        val severe = when (rule.comparison) {
            AlertComparison.Above -> raw > rule.threshold + margin
            AlertComparison.Below -> raw < rule.threshold - margin
        }
        return if (severe) TileLevel.Bad else TileLevel.Warn
    }

    private fun elapsed(millis: Long): String {
        val seconds = (millis / 1_000L).coerceAtLeast(0)
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
    }

    /** A tile, and the line to draw on it when the driver has set no rule of their own. */
    private data class TileSpec(val metric: MetricId, val fallbackMark: Double? = null)

    private companion object {

        /**
         * The six the design settles on: everything the instrument cluster already shows is
         * absent, and what is left is what the car knows but never says.
         *
         * Fuel trim's line is zero because zero is what it means — the correction the ECU
         * is applying to keep the mixture right, and how far from nothing it has drifted is
         * the whole reading.
         */
        val TILES = listOf(
            TileSpec(Metrics.FuelPer100Km),
            TileSpec(Metrics.OilTemp),
            TileSpec(Metrics.CoolantTemp),
            TileSpec(Metrics.IntakeAirTemp),
            TileSpec(Metrics.Battery),
            TileSpec(Metrics.LongTrim, fallbackMark = 0.0),
        )

        /** What the design's tile holds without the trace turning into a comb. */
        const val TRACE_SAMPLES = 30

        /** The readiness and freeze-frame rows, which keep their places whatever else lands. */
        const val TRAILING_ROWS = 2

        /** Every host allows at least this many list rows. */
        const val MIN_ROWS = 6

        /**
         * The design's own cadence. The host throttles refreshes anyway, and a tile redrawn
         * per poll would be six bitmaps a second crossing a binder to say the same thing.
         */
        const val REFRESH_MILLIS = 2_000L

        /** How far past its line a reading has to be to stop being merely a warning. */
        const val SEVERE_FRACTION = 0.125

        const val SEPARATOR = " · "
        const val SHORT_SEPARATOR = ", "
    }
}
