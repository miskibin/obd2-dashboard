package com.miskibin.obd2dashboard.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import com.miskibin.obd2dashboard.obd.AdapterInfo
import com.miskibin.obd2dashboard.obd.Diagnostics
import com.miskibin.obd2dashboard.obd.ElmError
import com.miskibin.obd2dashboard.obd.ElmFatalException
import com.miskibin.obd2dashboard.obd.ElmInitConfig
import com.miskibin.obd2dashboard.obd.ElmInitializer
import com.miskibin.obd2dashboard.obd.ElmSession
import com.miskibin.obd2dashboard.obd.InitOutcome
import com.miskibin.obd2dashboard.obd.Obd2Client
import com.miskibin.obd2dashboard.obd.ObdProtocol
import com.miskibin.obd2dashboard.obd.PidScheduler
import com.miskibin.obd2dashboard.obd.PidTier
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val device: DiscoveredDevice) : ConnectionState
    data class Initializing(val step: String) : ConnectionState
    data class Connected(val device: DiscoveredDevice, val adapter: AdapterInfo) : ConnectionState
    data class Reconnecting(val attempt: Int, val delayMillis: Long) : ConnectionState
    data class Error(val reason: String, val elmError: ElmError? = null) : ConnectionState
}

/**
 * Owns the whole connection lifecycle: scan → GATT link → ELM327 init → PID polling,
 * plus the reconnect loop that a dongle which sleeps after 30 minutes makes unavoidable.
 */
class ConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scanner: BleScanner = BleScanner(context),
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val _diagnostics = MutableStateFlow<Diagnostics?>(null)
    val diagnostics: StateFlow<Diagnostics?> = _diagnostics.asStateFlow()

    private val _vin = MutableStateFlow<String?>(null)
    val vin: StateFlow<String?> = _vin.asStateFlow()

    /** PIDs the vehicle answered `0100`/`0120`/… for; empty until the first connect. */
    private val _supportedPids = MutableStateFlow<Set<Int>>(emptySet())
    val supportedPids: StateFlow<Set<Int>> = _supportedPids.asStateFlow()

    private var scanJob: Job? = null
    private var sessionJob: Job? = null
    private var mirrorJob: Job? = null
    private var keepAliveJob: Job? = null

    private var transport: BleElmTransport? = null
    private var session: ElmSession? = null
    private var client: Obd2Client? = null
    private var scheduler: PidScheduler? = null

    /** Remembered from the previous session so reconnects skip the protocol search. */
    var lastProtocol: ObdProtocol? = null
        private set

    private val _pollingEnabled = MutableStateFlow(true)
    val pollingEnabled: StateFlow<Boolean> = _pollingEnabled.asStateFlow()

    fun startScan() {
        if (scanJob?.isActive == true) return
        _devices.value = emptyList()
        _state.value = ConnectionState.Scanning
        scanJob = scope.launch {
            try {
                scanner.scan().collect { device ->
                    _devices.update { current ->
                        (current.filterNot { it.address == device.address } + device)
                            .sortedByDescending { it.looksLikeAdapter }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = ConnectionState.Error(error.message ?: "Scan failed")
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_state.value is ConnectionState.Scanning) _state.value = ConnectionState.Idle
    }

    fun connect(device: DiscoveredDevice) {
        stopScan()
        sessionJob?.cancel()
        sessionJob = scope.launch { runSession(device) }
    }

    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        tearDownSession()
        _state.value = ConnectionState.Idle
    }

    fun setPollingEnabled(enabled: Boolean) {
        _pollingEnabled.value = enabled
    }

    suspend fun refreshDiagnostics(): Diagnostics? {
        val active = client ?: return null
        val result = withScheduler { active.readDiagnostics() }
        _diagnostics.value = result
        return result
    }

    suspend fun clearDtcs(): Boolean {
        val active = client ?: return false
        val cleared = withScheduler { active.clearDtcs() }
        if (cleared) refreshDiagnostics()
        return cleared
    }

    suspend fun readVin(): String? {
        val active = client ?: return null
        return withScheduler { active.readVin() }.also { _vin.value = it }
    }

    private suspend fun <T> withScheduler(block: suspend () -> T): T =
        scheduler?.exclusive(block) ?: block()

    private suspend fun runSession(device: DiscoveredDevice) {
        var attempt = 0
        while (currentlyActive()) {
            try {
                openAndPoll(device)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                tearDownSession()
                attempt++
                if (attempt > MAX_RECONNECT_ATTEMPTS) {
                    _state.value = ConnectionState.Error(
                        reason = error.message ?: "Connection lost",
                        elmError = (error as? ElmFatalException)?.error,
                    )
                    return
                }
                val backoff = backoffMillis(attempt)
                _state.value = ConnectionState.Reconnecting(attempt, backoff)
                delay(backoff)
            }
        }
    }

    private suspend fun openAndPoll(device: DiscoveredDevice) {
        _state.value = ConnectionState.Connecting(device)
        val bluetooth = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: error("Bluetooth unavailable")
        val remote = bluetooth.adapter.getRemoteDevice(device.address)

        val newTransport = BleElmTransport(context, remote).also { transport = it }
        newTransport.open()

        val newSession = ElmSession(newTransport, scope).also { session = it }
        _state.value = ConnectionState.Initializing("ELM327")

        val outcome = ElmInitializer(
            session = newSession,
            config = ElmInitConfig(preferredProtocol = lastProtocol),
        ).initialize()
        val info = when (outcome) {
            is InitOutcome.Success -> outcome.info
            is InitOutcome.Failure -> throw ElmFatalException(outcome.error)
        }
        lastProtocol = info.protocol

        val newClient = Obd2Client(newSession, info.protocol).also { client = it }
        _state.value = ConnectionState.Initializing("supported PIDs")
        val supported = newClient.scanSupportedPids()
        _supportedPids.value = supported
        newClient.probeBatching(Pids.tier(PidTier.Fast).take(BATCH_PROBE_SIZE))

        val newScheduler = PidScheduler(
            client = newClient,
            pollingEnabled = { _pollingEnabled.value },
        ).also { scheduler = it }
        newScheduler.configure(supported)
        mirrorJob = scope.launch { newScheduler.snapshot.collect { _snapshot.value = it } }

        _state.value = ConnectionState.Connected(device, info)
        keepAliveJob = scope.launch { keepAlive(newClient, newScheduler) }
        newScheduler.run()
    }

    /**
     * The RS232 inactivity timer fires `ACT ALERT` and then low-power mode; `ATRV` is
     * cheap, never touches the bus, and keeps the adapter awake while polling is paused.
     */
    private suspend fun keepAlive(active: Obd2Client, owner: PidScheduler) {
        while (currentlyActive()) {
            delay(KEEP_ALIVE_MILLIS)
            if (_pollingEnabled.value) continue
            runCatching { owner.exclusive { active.readVoltage() } }
                .getOrNull()
                ?.let(owner::recordVoltage)
        }
    }

    private fun tearDownSession() {
        keepAliveJob?.cancel()
        mirrorJob?.cancel()
        keepAliveJob = null
        mirrorJob = null
        scheduler = null
        client = null
        session?.close()
        session = null
        val open = transport
        transport = null
        scope.launch { open?.close() }
    }

    private fun currentlyActive(): Boolean = scope.isActive

    private fun backoffMillis(attempt: Int): Long =
        minOf(BASE_BACKOFF_MILLIS shl (attempt - 1), MAX_BACKOFF_MILLIS)

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val BASE_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val KEEP_ALIVE_MILLIS = 5_000L
        const val BATCH_PROBE_SIZE = 3
    }
}
