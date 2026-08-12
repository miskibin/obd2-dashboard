package com.miskibin.obd2dashboard.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import com.miskibin.obd2dashboard.data.SessionKind
import com.miskibin.obd2dashboard.log.LogTag
import com.miskibin.obd2dashboard.log.ObdLog
import com.miskibin.obd2dashboard.obd.AdapterInfo
import com.miskibin.obd2dashboard.obd.DemoElmTransport
import com.miskibin.obd2dashboard.obd.Diagnostics
import com.miskibin.obd2dashboard.obd.ElmError
import com.miskibin.obd2dashboard.obd.ElmFatalException
import com.miskibin.obd2dashboard.obd.ElmInitConfig
import com.miskibin.obd2dashboard.obd.ElmInitializer
import com.miskibin.obd2dashboard.obd.ElmSession
import com.miskibin.obd2dashboard.obd.ElmTransport
import com.miskibin.obd2dashboard.obd.FreezeFrame
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Where the connection is, in enough detail to put on screen.
 *
 * [Connecting.attempt] and [Initializing.step] are not decoration: a driver watching
 * "Connecting…" for four minutes has no way to tell a dongle that is asleep from an app
 * that has hung, and the difference between "attempt 2 of 3" and "ATZ" is the difference
 * between waiting and giving up.
 */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val device: DiscoveredDevice, val attempt: Int = 1) : ConnectionState
    data class Initializing(val step: String) : ConnectionState

    /** [demo] marks the simulated vehicle, which must never be mistaken for a real car. */
    data class Connected(
        val device: DiscoveredDevice,
        val adapter: AdapterInfo,
        val demo: Boolean = false,
    ) : ConnectionState

    data class Reconnecting(val attempt: Int, val delayMillis: Long) : ConnectionState

    /**
     * [step] is the ELM327 command that failed, [issue] is a precondition the driver can
     * fix themselves — the screen turns the second one into a button.
     */
    data class Error(
        val reason: String,
        val elmError: ElmError? = null,
        val step: String? = null,
        val issue: ConnectionIssue? = null,
    ) : ConnectionState
}

/**
 * Owns the whole connection lifecycle: scan → radio link → ELM327 init → PID polling,
 * plus the reconnect loop that a dongle which sleeps after 30 minutes makes unavoidable.
 *
 * The radio is chosen per device rather than per app: [DeviceKind.Classic] devices get an
 * RFCOMM socket and [DeviceKind.Le] devices get GATT. Everything above [ElmTransport] is
 * identical for both.
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

    /** True once a scan has run its full course, so the screen can say "nothing found". */
    private val _scanFinished = MutableStateFlow(false)
    val scanFinished: StateFlow<Boolean> = _scanFinished.asStateFlow()

    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val _diagnostics = MutableStateFlow<Diagnostics?>(null)
    val diagnostics: StateFlow<Diagnostics?> = _diagnostics.asStateFlow()

    private val _vin = MutableStateFlow<String?>(null)
    val vin: StateFlow<String?> = _vin.asStateFlow()

    private val _freezeFrame = MutableStateFlow<FreezeFrame?>(null)
    val freezeFrame: StateFlow<FreezeFrame?> = _freezeFrame.asStateFlow()

    /** PIDs the vehicle answered `0100`/`0120`/… for; empty until the first connect. */
    private val _supportedPids = MutableStateFlow<Set<Int>>(emptySet())
    val supportedPids: StateFlow<Set<Int>> = _supportedPids.asStateFlow()

    /**
     * Which car the app is talking to right now, and the one thing everything that
     * persists is filed under.
     *
     * [ConnectionState.Connected.demo] says the same thing, but only once the session is
     * up: a recording started while the simulation is still initialising, or a code read
     * on the way down, would have nothing to ask. This is [SessionKind.Demo] from the
     * moment [connectDemo] begins until that session is torn down, and [SessionKind.Real]
     * at every other moment — including while idle, so demo artefacts stop being visible
     * the moment the driver leaves demo mode.
     */
    private val _sessionKind = MutableStateFlow(SessionKind.Real)
    val sessionKind: StateFlow<SessionKind> = _sessionKind.asStateFlow()

    private var scanJob: Job? = null
    private var sessionJob: Job? = null
    private var mirrorJob: Job? = null
    private var keepAliveJob: Job? = null

    private var transport: ElmTransport? = null
    private var session: ElmSession? = null
    private var client: Obd2Client? = null
    private var scheduler: PidScheduler? = null

    /** Remembered from the previous session so reconnects skip the protocol search. */
    var lastProtocol: ObdProtocol? = null
        private set

    /** What the last session was, so a change of car can wipe what belonged to the old one. */
    private var previousKind: SessionKind? = null

    private val _pollingEnabled = MutableStateFlow(true)
    val pollingEnabled: StateFlow<Boolean> = _pollingEnabled.asStateFlow()

    fun startScan() {
        if (scanJob?.isActive == true) return
        if (sessionInFlight()) {
            ObdLog.log(LogTag.CONN, "scan request ignored: a session is already in flight")
            return
        }
        _scanFinished.value = false
        _devices.value = emptyList()
        setState(ConnectionState.Scanning)
        scanJob = scope.launch {
            try {
                // The paired list first: a classic dongle is reachable the moment the
                // screen opens and will never turn up in the LE scan that follows.
                _devices.value = runCatching { scanner.bondedDevices() }.getOrDefault(emptyList())
                withTimeoutOrNull(BleScanner.SCAN_DURATION_MILLIS) {
                    scanner.scan().collect(::merge)
                }
                _scanFinished.value = true
                ObdLog.log(LogTag.SCAN, "scan finished with ${_devices.value.size} device(s)")
                if (_state.value is ConnectionState.Scanning) setState(ConnectionState.Idle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setState(
                    ConnectionState.Error(
                        reason = error.message ?: "Scan failed",
                        issue = (error as? ConnectionSetupException)?.issue,
                    ),
                )
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_state.value is ConnectionState.Scanning) setState(ConnectionState.Idle)
    }

    /**
     * A device seen twice keeps the identity that can actually be opened.
     *
     * A dual-radio dongle answers an LE scan under a second address; if the classic one is
     * already listed from the bonded set, the LE sighting only refreshes its signal.
     */
    private fun merge(device: DiscoveredDevice) {
        _devices.update { current ->
            val existing = current.firstOrNull { it.address == device.address }
            when {
                existing == null -> current + device
                existing.kind == DeviceKind.Classic -> current.map {
                    if (it.address != device.address) {
                        it
                    } else {
                        it.copy(
                            rssi = device.rssi,
                            looksLikeAdapter = it.looksLikeAdapter || device.looksLikeAdapter,
                        )
                    }
                }

                else -> current.filterNot { it.address == device.address } + device
            }
        }
    }

    fun connect(device: DiscoveredDevice) {
        val previous = sessionJob
        previous?.cancel()
        sessionJob = scope.launch {
            // The previous session's socket has to be gone before the next one opens:
            // a connectGatt issued against an unclosed client is a permanent 133.
            previous?.join()
            stopScanAndSettle()
            guardedSession(device, SessionKind.Real)
        }
    }

    /**
     * Starts the built-in simulated vehicle. It swaps [DemoElmTransport] in for the radio
     * and then runs the identical init, parser and scheduler pipeline, so demo mode needs
     * no permissions, no Bluetooth and no foreground service — and is never remembered as
     * an adapter, because there is nothing to reconnect to.
     */
    fun connectDemo() {
        val previous = sessionJob
        previous?.cancel()
        sessionJob = scope.launch {
            previous?.join()
            stopScanAndSettle()
            guardedSession(DEMO_DEVICE, SessionKind.Demo)
        }
    }

    /**
     * The job reference is deliberately *kept* after cancelling: the next [connect] joins
     * it, so the radio the old session held is released before the new one asks for it.
     */
    fun disconnect() {
        ObdLog.log(LogTag.CONN, "disconnect requested")
        sessionJob?.cancel()
        setState(ConnectionState.Idle)
    }

    /** Cancels a connection attempt and leaves the screen able to say why nothing happened. */
    fun cancelConnect() {
        ObdLog.log(LogTag.CONN, "connection attempt cancelled by the driver")
        sessionJob?.cancel()
        setState(ConnectionState.Idle)
    }

    /** Lets a caller outside the session loop (auto-connect) put a precondition on screen. */
    fun reportSetupIssue(issue: ConnectionIssue, reason: String) {
        setState(ConnectionState.Error(reason, issue = issue))
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

    /** Null when the car has no frame stored, which is the normal answer with no codes. */
    suspend fun readFreezeFrame(): FreezeFrame? {
        val active = client ?: return null
        val frame = withScheduler { active.readFreezeFrame() }.takeIf { !it.isEmpty }
        _freezeFrame.value = frame
        return frame
    }

    suspend fun clearDtcs(): Boolean {
        val active = client ?: return false
        val cleared = withScheduler { active.clearDtcs() }
        // Mode 04 erases the freeze frame along with the codes that owned it.
        if (cleared) {
            _freezeFrame.value = null
            refreshDiagnostics()
        }
        return cleared
    }

    suspend fun readVin(): String? {
        val active = client ?: return null
        return withScheduler { active.readVin() }.also { _vin.value = it }
    }

    private suspend fun <T> withScheduler(block: suspend () -> T): T =
        scheduler?.exclusive(block) ?: block()

    /**
     * Runs a session and guarantees the radio is released afterwards, whether the session
     * ended, failed or was cancelled from the connect screen.
     */
    private suspend fun guardedSession(device: DiscoveredDevice, kind: SessionKind) {
        // The kind is published before anything is opened, so a recording or a code read
        // started during initialisation is already filed under the right car.
        if (previousKind != null && previousKind != kind) clearSessionData()
        previousKind = kind
        _sessionKind.value = kind
        try {
            if (!kind.demo && !preflight()) return
            runSession(device, kind)
        } finally {
            withContext(NonCancellable) { tearDownSession("session ended") }
            // A demo session leaves nothing on screen. The readings, the codes and the
            // frozen frame it produced are all fiction, and the moment the driver steps
            // out of demo mode there is no car they describe. A real session's last
            // readings are kept, because they do describe one.
            if (kind.demo) clearSessionData()
            // Back to Real while idle, so nothing demo-scoped stays visible.
            _sessionKind.value = SessionKind.Real
        }
    }

    /**
     * Drops everything a session read, because whatever comes next is a different car.
     *
     * Called on a change of kind and when a demo session ends — never on a reconnect to
     * the same car, where the codes and the VIN already on screen still describe the car
     * in front of the driver and wiping them would make a dropped link look like a car
     * that had suddenly gone quiet.
     */
    private fun clearSessionData() {
        ObdLog.log(LogTag.CONN, "clearing what the last car said")
        _snapshot.value = VehicleSnapshot()
        _diagnostics.value = null
        _freezeFrame.value = null
        _vin.value = null
        _supportedPids.value = emptySet()
    }

    /** The two conditions that make a connect pointless before it is attempted. */
    private fun preflight(): Boolean {
        if (!scanner.hasAdapter) {
            setState(
                ConnectionState.Error("No Bluetooth adapter", issue = ConnectionIssue.NoAdapter),
            )
            return false
        }
        if (!scanner.isBluetoothEnabled) {
            setState(
                ConnectionState.Error("Bluetooth is off", issue = ConnectionIssue.BluetoothOff),
            )
            return false
        }
        if (!scanner.hasConnectPermission()) {
            setState(
                ConnectionState.Error(
                    "BLUETOOTH_CONNECT not granted",
                    issue = ConnectionIssue.ConnectPermission,
                ),
            )
            return false
        }
        return true
    }

    /**
     * A scan still running when `connectGatt` is called is the classic cause of status 133,
     * and `stopScan` is asynchronous — so the scan is not only cancelled but waited for.
     */
    private suspend fun stopScanAndSettle() {
        val running = scanJob
        scanJob = null
        if (running == null) return
        running.cancel()
        running.join()
        ObdLog.log(LogTag.CONN, "scan stopped, settling for $SCAN_SETTLE_MILLIS ms")
        delay(SCAN_SETTLE_MILLIS)
    }

    private suspend fun runSession(device: DiscoveredDevice, kind: SessionKind) {
        var attempt = 0
        while (currentlyActive()) {
            try {
                openAndPoll(device, kind, attempt + 1)
                return
            } catch (error: Exception) {
                // A `withTimeout` that expires throws a CancellationException. Treating
                // every one of those as "the driver pressed cancel" is what used to make a
                // failed connect end in silence with the UI stuck on "Connecting".
                if (error is CancellationException && !coroutineContext.isActive) throw error
                ObdLog.log(LogTag.CONN, "session failed: ${error.javaClass.simpleName}: ${error.message}")
                tearDownSession("session failed")
                attempt++
                if (attempt > MAX_RECONNECT_ATTEMPTS) {
                    val fatal = error as? ElmFatalException
                    setState(
                        ConnectionState.Error(
                            reason = error.message ?: "Connection lost",
                            elmError = fatal?.error,
                            step = fatal?.step,
                            issue = (error as? ConnectionSetupException)?.issue
                                ?: (error as? SecurityException)?.let { ConnectionIssue.ConnectPermission },
                        ),
                    )
                    return
                }
                val backoff = backoffMillis(attempt)
                setState(ConnectionState.Reconnecting(attempt, backoff))
                delay(backoff)
            }
        }
    }

    private suspend fun openAndPoll(device: DiscoveredDevice, kind: SessionKind, attempt: Int) {
        val demo = kind.demo
        setState(ConnectionState.Connecting(device, attempt))

        val newTransport = (if (demo) DemoElmTransport() else transportFor(device))
            .also { transport = it }
        newTransport.open()

        val newSession = ElmSession(newTransport, scope).also { session = it }
        setState(ConnectionState.Initializing(ElmInitializer.FIRST_STEP))

        val outcome = ElmInitializer(
            session = newSession,
            config = if (demo) DEMO_INIT_CONFIG else ElmInitConfig(preferredProtocol = lastProtocol),
            onStep = { step -> setState(ConnectionState.Initializing(step)) },
        ).initialize()
        val info = when (outcome) {
            is InitOutcome.Success -> outcome.info
            is InitOutcome.Failure -> throw ElmFatalException(outcome.error, outcome.step)
        }
        // A protocol the simulation picked says nothing about the car in the driveway.
        if (!demo) lastProtocol = info.protocol

        val newClient = Obd2Client(newSession, info.protocol).also { client = it }
        setState(ConnectionState.Initializing(SUPPORTED_PIDS_STEP))
        val supported = newClient.scanSupportedPids()
        _supportedPids.value = supported
        newClient.probeBatching(Pids.tier(PidTier.Fast).take(BATCH_PROBE_SIZE))

        val newScheduler = PidScheduler(
            client = newClient,
            pollingEnabled = { _pollingEnabled.value },
        ).also { scheduler = it }
        newScheduler.configure(supported)
        mirrorJob = scope.launch { newScheduler.snapshot.collect { _snapshot.value = it } }

        setState(ConnectionState.Connected(device, info, demo))
        keepAliveJob = scope.launch { keepAlive(newClient, newScheduler) }

        coroutineScope {
            // Without this the link can drop silently: the scheduler goes on asking, every
            // request times out, and the UI keeps saying "Connected" until somebody looks.
            val linkWatch = launch {
                newTransport.connected.first { !it }
                throw IOException("Link to ${device.address} dropped")
            }
            newScheduler.run()
            linkWatch.cancel()
        }
    }

    /** BLE or classic, decided by what the device actually is rather than by what the app is. */
    private fun transportFor(device: DiscoveredDevice): ElmTransport {
        val bluetooth = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: error("Bluetooth unavailable")
        val adapter = bluetooth.adapter ?: error("Bluetooth unavailable")
        val remote = adapter.getRemoteDevice(device.address)
        ObdLog.log(
            LogTag.CONN,
            "opening ${device.kind} transport to ${device.address} (${device.name ?: "unnamed"})",
        )
        return when (device.kind) {
            DeviceKind.Classic -> SppElmTransport(adapter, remote)
            DeviceKind.Le -> BleElmTransport(context, remote)
        }
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

    /**
     * Releases everything, and *waits* for the radio to be released.
     *
     * This used to close the transport on a launched coroutine, which meant the next
     * connect attempt regularly raced an unclosed GATT client — permanent status 133 with
     * no way out but killing the app.
     */
    private suspend fun tearDownSession(reason: String) {
        keepAliveJob?.cancel()
        mirrorJob?.cancel()
        keepAliveJob = null
        mirrorJob = null
        scheduler = null
        client = null
        session?.close()
        session = null
        val open = transport ?: return
        transport = null
        ObdLog.log(LogTag.CONN, "tearing down transport: $reason")
        withContext(NonCancellable) { runCatching { open.close() } }
    }

    private fun setState(state: ConnectionState) {
        if (_state.value == state) return
        ObdLog.log(LogTag.CONN, "state → ${describe(state)}")
        _state.value = state
    }

    private fun describe(state: ConnectionState): String = when (state) {
        ConnectionState.Idle -> "Idle"
        ConnectionState.Scanning -> "Scanning"
        is ConnectionState.Connecting -> "Connecting(${state.device.address}, attempt ${state.attempt})"
        is ConnectionState.Initializing -> "Initializing(${state.step})"
        is ConnectionState.Connected -> "Connected(${state.device.address}, ${state.adapter.protocol})"
        is ConnectionState.Reconnecting -> "Reconnecting(${state.attempt}, ${state.delayMillis} ms)"
        is ConnectionState.Error -> "Error(${state.reason}, step=${state.step}, issue=${state.issue})"
    }

    private fun sessionInFlight(): Boolean = when (_state.value) {
        is ConnectionState.Connecting,
        is ConnectionState.Initializing,
        is ConnectionState.Connected,
        is ConnectionState.Reconnecting,
        -> sessionJob?.isActive == true

        else -> false
    }

    private fun currentlyActive(): Boolean = scope.isActive

    private fun backoffMillis(attempt: Int): Long =
        minOf(BASE_BACKOFF_MILLIS shl (attempt - 1), MAX_BACKOFF_MILLIS)

    private companion object {
        val DEMO_DEVICE = DiscoveredDevice(
            address = "demo",
            name = "Demo",
            rssi = 0,
            looksLikeAdapter = false,
        )

        /** The simulation has no baud rate to settle and no clone quirks to wait out. */
        val DEMO_INIT_CONFIG = ElmInitConfig(resetWaitMillis = 200, interCommandDelayMillis = 0)

        const val MAX_RECONNECT_ATTEMPTS = 5
        const val BASE_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val KEEP_ALIVE_MILLIS = 5_000L
        const val BATCH_PROBE_SIZE = 3

        /** Let the LE scanner actually stop before the radio is asked to connect. */
        const val SCAN_SETTLE_MILLIS = 500L

        const val SUPPORTED_PIDS_STEP = "supported PIDs"
    }
}
