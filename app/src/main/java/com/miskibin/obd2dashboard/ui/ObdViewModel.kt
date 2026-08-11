package com.miskibin.obd2dashboard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miskibin.obd2dashboard.ObdHolder
import com.miskibin.obd2dashboard.ble.ConnectionState
import com.miskibin.obd2dashboard.ble.DiscoveredDevice
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.SavedAdapter
import com.miskibin.obd2dashboard.data.Trip
import com.miskibin.obd2dashboard.obd.DerivedMetrics
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.service.ObdConnectionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether the first-run connection screen or the dashboard should open first. */
sealed interface Startup {
    data object Loading : Startup
    data class Ready(val hasSavedAdapter: Boolean) : Startup
}

/** Which long-running diagnostics request, if any, is in flight. */
enum class DtcOperation { Reading, Clearing }

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
    val snapshot = connection.snapshot
    val diagnostics = connection.diagnostics
    val supportedPids: StateFlow<Set<Int>> = connection.supportedPids
    val recording = recorder.state

    val savedAdapter: StateFlow<SavedAdapter?> =
        preferences.savedAdapter.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val startup: StateFlow<Startup> = preferences.savedAdapter
        .map { Startup.Ready(it != null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Startup.Loading)

    val pollingEnabled: StateFlow<Boolean> =
        preferences.pollingEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

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

    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    val trips: StateFlow<List<Trip>> = _trips.asStateFlow()

    init {
        viewModelScope.launch {
            _tiles.value = preferences.tiles.first()
            _chartMetrics.value = preferences.chartMetrics.first()
        }
        viewModelScope.launch {
            connection.state.collect { state ->
                // The demo has no adapter to come back to, so it is never remembered.
                if (state is ConnectionState.Connected && !state.demo) {
                    preferences.saveAdapter(state.device.address, state.device.name)
                }
            }
        }
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

    // ---- tiles ------------------------------------------------------------------

    fun moveTile(from: Int, to: Int) {
        _tiles.update { current ->
            if (from !in current.indices || to !in current.indices) return@update current
            current.toMutableList().apply { add(to, removeAt(from)) }
        }
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
        // Never leave the chart pointing at a metric the driver just removed.
        if (id in _chartMetrics.value) toggleChartMetric(id)
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
            runCatching { connection.refreshDiagnostics() }
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

    // ---- trip recording ---------------------------------------------------------

    fun toggleRecording() {
        if (recorder.isRecording) {
            recorder.stop()
            refreshTrips()
        } else {
            recorder.start(connection.snapshot, recordableMetrics())
        }
    }

    fun refreshTrips() {
        viewModelScope.launch { _trips.value = ObdHolder.trips.list() }
    }

    fun deleteTrip(trip: Trip) {
        ObdHolder.trips.delete(trip)
        refreshTrips()
    }

    fun shareIntentFor(trip: Trip) = ObdHolder.trips.shareIntent(trip)

    /**
     * Every PID the car answers for *and* the app can decode, plus the values computed on
     * top of them. Supported-but-undecodable PIDs would only add permanently empty
     * columns to the CSV.
     */
    private fun recordableMetrics(): List<MetricId> {
        val supported = supportedPids.value.filter { Pids[it] != null }.map(MetricId::Sensor)
        val derived = DerivedMetrics.all.map { MetricId.Derived(it.key) }
        val columns = supported + derived + MetricId.Battery
        return if (supported.isEmpty()) _tiles.value else columns
    }

    companion object {
        const val MAX_CHART_SERIES = 3
    }
}
