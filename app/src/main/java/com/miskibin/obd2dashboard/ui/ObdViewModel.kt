package com.miskibin.obd2dashboard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miskibin.obd2dashboard.ObdHolder
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.ble.ConnectionState
import com.miskibin.obd2dashboard.ble.DeviceKind
import com.miskibin.obd2dashboard.ble.DiscoveredDevice
import com.miskibin.obd2dashboard.data.AlertRule
import com.miskibin.obd2dashboard.data.AlertRules
import com.miskibin.obd2dashboard.data.AppTheme
import com.miskibin.obd2dashboard.data.DtcLog
import com.miskibin.obd2dashboard.data.DtcObservation
import com.miskibin.obd2dashboard.data.FaultContext
import com.miskibin.obd2dashboard.data.Garage
import com.miskibin.obd2dashboard.data.GearEstimator
import com.miskibin.obd2dashboard.data.GearReading
import com.miskibin.obd2dashboard.data.MechanicReport
import com.miskibin.obd2dashboard.data.MechanicReportData
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.SavedAdapter
import com.miskibin.obd2dashboard.data.SessionKind
import com.miskibin.obd2dashboard.data.Trip
import com.miskibin.obd2dashboard.data.TripAnalyzer
import com.miskibin.obd2dashboard.data.TripEntry
import com.miskibin.obd2dashboard.data.Vehicle
import com.miskibin.obd2dashboard.data.VinDecoder
import com.miskibin.obd2dashboard.data.VinFacts
import com.miskibin.obd2dashboard.data.presentMetrics
import com.miskibin.obd2dashboard.data.valueOf
import com.miskibin.obd2dashboard.obd.DerivedMetrics
import com.miskibin.obd2dashboard.obd.Dtc
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.service.ObdConnectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Whether the first-run connection screen or the dashboard should open first. */
sealed interface Startup {
    data object Loading : Startup
    data class Ready(val hasSavedAdapter: Boolean) : Startup
}

/** Which long-running diagnostics request, if any, is in flight. */
enum class DtcOperation { Reading, Clearing, Reporting }

/**
 * The single view model behind all four screens.
 *
 * There is exactly one connection in the process, so splitting this per screen would
 * only duplicate the same flows behind four lifecycles.
 */
class ObdViewModel(application: Application) : AndroidViewModel(application) {

    private val connection = ObdHolder.connection
    private val preferences = ObdHolder.preferences
    private val recorder = ObdHolder.recorder

    val history = ObdHolder.history

    val connectionState: StateFlow<ConnectionState> = connection.state
    val devices: StateFlow<List<DiscoveredDevice>> = connection.devices
    val scanFinished: StateFlow<Boolean> = connection.scanFinished
    val snapshot = connection.snapshot
    val diagnostics = connection.diagnostics
    val supportedPids: StateFlow<Set<Int>> = connection.supportedPids
    val vin: StateFlow<String?> = connection.vin
    val recording = recorder.state
    val alertEvents = ObdHolder.alerts.events

    val alertRules: StateFlow<List<AlertRule>> =
        preferences.alertRules.stateIn(viewModelScope, SharingStarted.Eagerly, AlertRules.defaults)

    val savedAdapter: StateFlow<SavedAdapter?> =
        preferences.savedAdapter.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val startup: StateFlow<Startup> = preferences.savedAdapter
        .map { Startup.Ready(it != null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Startup.Loading)

    val pollingEnabled: StateFlow<Boolean> =
        preferences.pollingEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val redline: StateFlow<Int> = preferences.redline
        .stateIn(viewModelScope, SharingStarted.Eagerly, Metrics.REDLINE_DEFAULT)

    val imperialUnits: StateFlow<Boolean> = preferences.imperialUnits
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Which ground the app is drawn on, as chosen in Settings. */
    val theme: StateFlow<AppTheme> = preferences.theme
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppTheme.System)

    /** Which car the app is talking to; everything session-scoped is filed under it. */
    val sessionKind: StateFlow<SessionKind> = connection.sessionKind

    /**
     * When each code was first and last seen, since the car will not say — for the car
     * that is connected.
     *
     * Both logs are collected and one is chosen, rather than switching flows, so leaving
     * demo mode swaps the screen back to the real history without a frame of the wrong one.
     */
    val dtcLog: StateFlow<List<DtcObservation>> = combine(
        connection.sessionKind,
        preferences.dtcLog(SessionKind.Real),
        preferences.dtcLog(SessionKind.Demo),
    ) { kind, real, demo -> if (kind.demo) demo else real }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The profile of whichever car is plugged in, blank until the driver fills it in.
     *
     * Null only while there is no VIN to key it on — no car connected, or one whose ECU
     * will not answer mode 09. The vehicle screen has nothing to show in that case and says
     * so rather than offering a form that would be saved against nothing.
     */
    val vehicle: StateFlow<Vehicle?> = combine(
        connection.sessionKind,
        connection.vin,
        preferences.vehicles(SessionKind.Real),
        preferences.vehicles(SessionKind.Demo),
    ) { kind, vin, real, demo ->
        Garage.profileFor(if (kind.demo) demo else real, vin)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** What the VIN says on its own, with no network and nothing leaving the phone. */
    val vinFacts: StateFlow<VinFacts?> = connection.vin
        .map(VinDecoder::decode)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The ECU snapshot behind an expanded fault code, when the car had one stored. */
    val freezeFrame = connection.freezeFrame

    /**
     * Tile order lives in memory while the driver drags, and is written back once the
     * gesture ends — a DataStore round trip per frame would make the drag stutter.
     */
    private val _tiles = MutableStateFlow(Metrics.defaultTiles)
    val tiles: StateFlow<List<MetricId>> = _tiles.asStateFlow()

    private val _chartMetrics = MutableStateFlow<List<MetricId>>(emptyList())
    val chartMetrics: StateFlow<List<MetricId>> = _chartMetrics.asStateFlow()

    private val _dtcOperation = MutableStateFlow<DtcOperation?>(null)
    val dtcOperation: StateFlow<DtcOperation?> = _dtcOperation.asStateFlow()

    private val _trips = MutableStateFlow<List<TripEntry>>(emptyList())
    val trips: StateFlow<List<TripEntry>> = _trips.asStateFlow()

    /**
     * The highest revs of this connection, which is what "max today" on the dashboard
     * means: it is reset when the adapter is, because it describes a session and not a
     * day the app was not running for.
     */
    private val _sessionMaxRpm = MutableStateFlow<Double?>(null)
    val sessionMaxRpm: StateFlow<Double?> = _sessionMaxRpm.asStateFlow()

    private val gearEstimator = GearEstimator()

    private val _gear = MutableStateFlow(GearReading(moving = false, gear = null, ratio = null))
    val gear: StateFlow<GearReading> = _gear.asStateFlow()

    /** What the app itself saw around each code it watched appear, this session only. */
    private val _faultContexts = MutableStateFlow<Map<String, FaultContext>>(emptyMap())
    val faultContexts: StateFlow<Map<String, FaultContext>> = _faultContexts.asStateFlow()

    /** A finished report waiting to be handed to the share sheet, or null. */
    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    init {
        viewModelScope.launch {
            _tiles.value = preferences.tiles.first()
            // An empty selection means the driver has never chosen; the chart opens on
            // the first few values they kept on the dashboard rather than on nothing.
            _chartMetrics.value = preferences.chartMetrics.first()
                .ifEmpty { _tiles.value.take(DEFAULT_CHART_SERIES) }
        }
        viewModelScope.launch {
            connection.state.collect { state ->
                // The demo has no adapter to come back to, so it is never remembered.
                if (state is ConnectionState.Connected && !state.demo) {
                    // The radio is remembered along with the address: reconnecting to a
                    // dual-radio dongle over the wrong one hangs.
                    preferences.saveAdapter(
                        address = state.device.address,
                        name = state.device.name,
                        classic = state.device.kind == DeviceKind.Classic,
                    )
                }
                if (state is ConnectionState.Idle) {
                    _sessionMaxRpm.value = null
                    gearEstimator.reset()
                }
            }
        }
        viewModelScope.launch {
            connection.snapshot.collect { snapshot ->
                val rpm = snapshot.valueOf(Metrics.Rpm)
                if (rpm != null && rpm > (_sessionMaxRpm.value ?: 0.0)) _sessionMaxRpm.value = rpm
                _gear.value = gearEstimator.observe(rpm, snapshot.valueOf(Metrics.Speed))
            }
        }
        viewModelScope.launch {
            // What the app itself saw around a code belongs to the car that set it, and
            // so does the list of recordings: both are re-read from scratch when the car
            // changes rather than carried across.
            // `drop(1)` because the first value is only "this is the car we are on"; the
            // list is loaded when the trips screen opens, and re-reading every recording
            // at launch would be work nobody asked for.
            connection.sessionKind.drop(1).collect {
                _faultContexts.value = emptyMap()
                _sessionMaxRpm.value = null
                gearEstimator.reset()
                refreshTrips()
            }
        }
        viewModelScope.launch { watchCodes() }
    }

    // ---- fault history ----------------------------------------------------------

    /**
     * Keeps the log of when each code was seen, and grabs the context around new ones.
     *
     * The capture has to happen here rather than when the fault screen opens: the history
     * it reads is a ten-minute ring buffer, so by the time somebody taps a code the
     * seconds around it may already have scrolled out of memory.
     */
    private suspend fun watchCodes() {
        var previous = emptySet<String>()
        connection.diagnostics.collect { diagnostics ->
            val codes = diagnostics?.all.orEmpty().map(Dtc::code).distinct()
            if (codes.isEmpty()) {
                previous = emptySet()
                return@collect
            }
            val now = System.currentTimeMillis()
            // Read here rather than captured: the log a sighting lands in is decided by
            // the car that is connected at the moment of the sighting.
            preferences.recordDtcSightings(connection.sessionKind.value, codes, previous, now)

            codes.filter { it !in previous && it !in _faultContexts.value }.forEach { code ->
                _faultContexts.update { it + (code to captureContext(code, now)) }
                // The rest of the window has not been driven yet, so it is filled in once
                // it has been.
                viewModelScope.launch {
                    delay(DtcLog.TIMELINE_WINDOW_MILLIS / 2)
                    val completed = captureContext(code, now).copy(
                        traces = tracesAround(now + DtcLog.TIMELINE_WINDOW_MILLIS / 2),
                        complete = true,
                    )
                    _faultContexts.update { contexts ->
                        if (code in contexts) contexts + (code to completed) else contexts
                    }
                }
            }
            previous = codes.toSet()
        }
    }

    private fun captureContext(code: String, atMillis: Long): FaultContext {
        val snapshot = connection.snapshot.value
        val before = DtcLog.SNAPSHOT_METRICS.mapNotNull { metric ->
            val target = atMillis - DtcLog.BEFORE_OFFSET_MILLIS
            val samples = history.series(metric, DtcLog.BEFORE_OFFSET_MILLIS * BEFORE_SPAN, atMillis)
            val sample = samples.minByOrNull { abs(it.timeMillis - target) } ?: return@mapNotNull null
            if (abs(sample.timeMillis - target) > DtcLog.BEFORE_OFFSET_MILLIS) return@mapNotNull null
            metric to sample.value.toDouble()
        }.toMap()
        val at = DtcLog.SNAPSHOT_METRICS.mapNotNull { metric ->
            snapshot.valueOf(metric)?.let { metric to it }
        }.toMap()
        return FaultContext(
            code = code,
            detectedAtMillis = atMillis,
            traces = tracesAround(atMillis),
            before = before,
            at = at,
        )
    }

    private fun tracesAround(endMillis: Long) = DtcLog.TIMELINE_METRICS.associateWith { metric ->
        history.series(metric, DtcLog.TIMELINE_WINDOW_MILLIS, endMillis)
    }

    // ---- connection -------------------------------------------------------------

    fun startScan() = connection.startScan()

    fun stopScan() = connection.stopScan()

    fun connect(device: DiscoveredDevice) {
        ObdConnectionService.start(getApplication())
        connection.connect(device)
    }

    /**
     * The simulated vehicle runs entirely inside the app-scoped connection: there is no
     * link to keep alive in the background, so it needs no foreground service either.
     */
    fun connectDemo() = connection.connectDemo()

    fun disconnect() {
        connection.disconnect()
        ObdConnectionService.stop(getApplication())
    }

    /** Abandons an attempt in progress without forgetting the adapter. */
    fun cancelConnect() {
        connection.cancelConnect()
        ObdConnectionService.stop(getApplication())
    }

    /** Silently reconnects to the remembered adapter; a no-op when there is none. */
    fun autoConnect() {
        if (connection.state.value != ConnectionState.Idle) return
        viewModelScope.launch {
            if (ObdHolder.autoConnect(getApplication())) {
                ObdConnectionService.start(getApplication())
            }
        }
    }

    fun forgetAdapter() {
        viewModelScope.launch {
            disconnect()
            preferences.forgetAdapter()
        }
    }

    fun setPollingEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setPollingEnabled(enabled) }
    }

    fun setRedline(rpm: Int) {
        viewModelScope.launch { preferences.setRedline(rpm) }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { preferences.setTheme(theme) }
    }

    /** Saved into the garage of whichever car is connected, so demo edits stay in demo. */
    fun saveVehicle(vehicle: Vehicle) {
        val kind = connection.sessionKind.value
        viewModelScope.launch { preferences.saveVehicle(kind, vehicle) }
    }

    // ---- tiles ------------------------------------------------------------------

    fun moveTile(from: Int, to: Int) {
        _tiles.update { current ->
            if (from !in current.indices || to !in current.indices) return@update current
            current.toMutableList().apply { add(to, removeAt(from)) }
        }
        commitTiles()
    }

    fun toggleUnits() {
        val imperial = !imperialUnits.value
        viewModelScope.launch { preferences.setImperialUnits(imperial) }
    }

    fun commitTiles() {
        val tiles = _tiles.value
        viewModelScope.launch { preferences.setTiles(tiles) }
    }

    fun addTile(id: MetricId) {
        _tiles.update { if (id in it) it else it + id }
        commitTiles()
    }

    fun removeTile(id: MetricId) {
        _tiles.update { it - id }
        commitTiles()
    }

    // ---- charts -----------------------------------------------------------------

    fun toggleChartMetric(id: MetricId) {
        _chartMetrics.update { current ->
            when {
                id in current -> current - id
                current.size >= MAX_CHART_SERIES -> current.drop(1) + id
                else -> current + id
            }
        }
        val metrics = _chartMetrics.value
        viewModelScope.launch { preferences.setChartMetrics(metrics) }
    }

    // ---- diagnostics ------------------------------------------------------------

    fun readCodes() {
        if (_dtcOperation.value != null) return
        _dtcOperation.value = DtcOperation.Reading
        viewModelScope.launch {
            // The frame belongs to a code the driver is about to look at, so it is read
            // in the same trip to the car rather than on the first tap of a row.
            runCatching {
                connection.refreshDiagnostics()
                connection.readFreezeFrame()
            }
            _dtcOperation.value = null
        }
    }

    fun clearCodes() {
        if (_dtcOperation.value != null) return
        _dtcOperation.value = DtcOperation.Clearing
        viewModelScope.launch {
            runCatching { connection.clearDtcs() }
            _dtcOperation.value = null
        }
    }

    // ---- mechanic report --------------------------------------------------------

    /**
     * Re-reads everything the report quotes before writing it: a report is shown to
     * somebody who was not there, so it must not carry a code the car has since cleared.
     */
    fun buildReport(language: String) {
        if (_dtcOperation.value != null) return
        _dtcOperation.value = DtcOperation.Reporting
        viewModelScope.launch {
            runCatching {
                connection.refreshDiagnostics()
                connection.readFreezeFrame()
                if (connection.vin.value == null) connection.readVin()
            }
            _report.value = MechanicReport.build(
                data = MechanicReportData(
                    appName = getApplication<Application>().getString(R.string.app_name),
                    versionName = versionName(),
                    generatedAtMillis = System.currentTimeMillis(),
                    vin = connection.vin.value,
                    diagnostics = connection.diagnostics.value,
                    freezeFrame = connection.freezeFrame.value,
                    batteryVoltage = connection.snapshot.value.batteryVoltage,
                ),
                language = language,
            )
            _dtcOperation.value = null
        }
    }

    fun consumeReport() {
        _report.value = null
    }

    private fun versionName(): String = runCatching {
        val context = getApplication<Application>()
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    // ---- alerts -----------------------------------------------------------------

    fun setAlertRule(rule: AlertRule) {
        viewModelScope.launch { preferences.setAlertRule(rule) }
    }

    fun restoreDefaultAlerts() {
        viewModelScope.launch { preferences.restoreDefaultAlertRules() }
    }

    // ---- trip recording ---------------------------------------------------------

    fun toggleRecording() {
        if (recorder.isRecording) {
            recorder.stop()
            refreshTrips()
        } else {
            recorder.start(connection.snapshot, recordableMetrics(), connection.sessionKind.value)
        }
    }

    /**
     * Lists the recordings, then reads each one back for its distance, fuel and events.
     *
     * The list appears first with nothing but names on it and fills in as the files are
     * parsed, because a driver with forty recordings should not watch a spinner while the
     * app reads forty files to tell them what it already knows: that there are forty.
     */
    fun refreshTrips() {
        viewModelScope.launch {
            // Demo recordings are listed only from inside demo mode; real drives always.
            val kind = connection.sessionKind.value
            val files = withContext(Dispatchers.IO) { ObdHolder.trips.list(kind) }
            _trips.value = files.map { TripEntry(it, analysis = null) }
            val rules = alertRules.value
            val limit = redline.value
            val analysed = withContext(Dispatchers.IO) {
                files.map { trip -> TripEntry(trip, TripAnalyzer.analyze(trip.file, rules, limit)) }
            }
            _trips.value = analysed
        }
    }

    fun deleteTrip(trip: Trip) {
        ObdHolder.trips.delete(trip)
        refreshTrips()
    }

    fun shareIntentFor(trip: Trip) = ObdHolder.trips.shareIntent(getApplication(), trip)

    /**
     * The columns a recording opens with, in the order they are written.
     *
     * Everything the driver is looking at comes first — the dashboard tiles and the chart
     * series — then every PID the car answers for *and* the app can decode, then the
     * values computed on top of them. This is only a seed: [com.miskibin.obd2dashboard.data.TripWriter]
     * adds any further metric the moment a snapshot carries one, so a parameter charted
     * mid-drive is recorded from that point rather than missed. It used to fall back to
     * the tiles alone whenever the car had not answered `0100` yet, which is how a drive
     * spent watching a dozen parameters ended up as a file of the six default ones.
     */
    private fun recordableMetrics(): List<MetricId> = buildList {
        addAll(_tiles.value)
        addAll(_chartMetrics.value)
        addAll(connection.snapshot.value.presentMetrics())
        addAll(supportedPids.value.filter { Pids[it] != null }.map(MetricId::Sensor))
        addAll(DerivedMetrics.all.map { MetricId.Derived(it.key) })
        add(MetricId.Battery)
    }.distinct()

    companion object {
        const val MAX_CHART_SERIES = 6

        /** How wide a window the "before" reading may be picked from, in half-offsets. */
        private const val BEFORE_SPAN = 2

        /** How many of the driver's tiles the chart starts with. */
        private const val DEFAULT_CHART_SERIES = 3
    }
}
