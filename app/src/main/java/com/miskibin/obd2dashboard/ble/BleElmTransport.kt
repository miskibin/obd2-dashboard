package com.miskibin.obd2dashboard.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.miskibin.obd2dashboard.log.LogTag
import com.miskibin.obd2dashboard.log.ObdLog
import com.miskibin.obd2dashboard.obd.ElmTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class GattException(val status: Int, message: String) :
    IOException("$message: ${GattStatus.name(status)}")

/** Which GATT operation a pending completion belongs to. */
private enum class GattOp { Discover, Mtu, Descriptor, Write }

private class PendingOp(val kind: GattOp, val completion: CompletableDeferred<Unit>)

/**
 * [ElmTransport] over BLE GATT.
 *
 * Android allows exactly one outstanding GATT operation per connection, so connect,
 * MTU, service discovery, descriptor writes and every 20-byte write chunk are pushed
 * through a single mutex and resolved by their callback. Notifications are forwarded as
 * an unframed byte stream — a response routinely arrives as two or three of them.
 *
 * The order of the connect sequence is not a matter of taste. MTU negotiation before
 * service discovery makes a large share of clones drop the link; a `requestMtu` answer
 * arriving after its own timeout used to complete whatever operation was pending *next*,
 * which surfaced as a bogus "no compatible GATT profile"; and a timeout inside
 * `withTimeout` throws a `CancellationException`, which the layer above treated as the
 * driver cancelling and swallowed, leaving the UI on "Connecting" forever. All three are
 * fixed below and none of them is theoretical.
 */
@SuppressLint("MissingPermission")
class BleElmTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : ElmTransport {

    /**
     * An unlimited channel rather than a SharedFlow: the adapter's power-on banner arrives
     * within milliseconds of the CCCD write, and a SharedFlow with no subscriber yet drops
     * it — which costs the first command of every session.
     */
    private val received = Channel<ByteArray>(Channel.UNLIMITED)

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val operations = Mutex()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var channel: SerialChannel? = null

    @Volatile
    private var pendingOperation: PendingOp? = null

    @Volatile
    private var pendingConnect: CompletableDeferred<Unit>? = null

    @Volatile
    private var disconnected: CompletableDeferred<Unit>? = null

    @Volatile
    var negotiatedMtu: Int = DEFAULT_MTU
        private set

    val profileName: String? get() = channel?.profile

    override fun incoming(): Flow<ByteArray> = received.receiveAsFlow()

    override suspend fun open() {
        if (!hasConnectPermission()) throw SecurityException("BLUETOOTH_CONNECT not granted")
        var lastError: Throwable? = null
        repeat(CONNECT_ATTEMPTS) { index ->
            val attempt = index + 1
            try {
                connectOnce(attempt)
                return
            } catch (error: Exception) {
                lastError = error
                ObdLog.log(LogTag.BLE, "connect attempt $attempt failed: ${error.message}")
                teardown("attempt $attempt failed")
                if (attempt < CONNECT_ATTEMPTS) {
                    val backoff = RETRY_BASE_MILLIS shl index
                    ObdLog.log(LogTag.BLE, "retrying in $backoff ms")
                    delay(backoff)
                }
            }
        }
        throw lastError ?: IOException("Unable to connect to ${device.address}")
    }

    override suspend fun write(command: String) {
        val connection = gatt ?: throw IOException("Transport is closed")
        val target = channel?.write ?: throw IOException("No write characteristic resolved")
        val payload = (command + COMMAND_TERMINATOR).toByteArray(Charsets.US_ASCII)
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + MAX_CHUNK, payload.size)
            writeChunk(connection, target, payload.copyOfRange(offset, end))
            offset = end
        }
    }

    override suspend fun close() {
        teardown("closed by caller")
    }

    private suspend fun connectOnce(attempt: Int) {
        awaitBondSettled()

        // From the second attempt on: autoConnect, which queues the link with the
        // controller instead of racing a direct connect, and a cache flush, because a
        // stale service list is the most common cause of a 133 that repeats forever.
        val autoConnect = attempt > 1
        val ready = CompletableDeferred<Unit>()
        pendingConnect = ready
        disconnected = CompletableDeferred()
        ObdLog.log(
            LogTag.BLE,
            "connectGatt ${device.address} attempt $attempt autoConnect=$autoConnect " +
                "bond=${GattStatus.bondStateName(device.bondState)}",
        )
        val connection = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        gatt = connection
        if (attempt > 1) refreshCache(connection)

        withTimeoutOrIo(CONNECT_TIMEOUT_MILLIS, "connect") { ready.await() }
        ObdLog.log(LogTag.BLE, "link up, discovering services")

        withBondRetry("service discovery") {
            awaitOperation(GattOp.Discover) { connection.discoverServices() }
        }
        ObdLog.log(LogTag.BLE) { "services: ${describeServices(connection.services)}" }

        val resolved = ElmGattProfiles.resolveChannel(connection.services)
            ?: throw IOException("No ELM327-compatible GATT profile on ${device.address}")
        ObdLog.log(
            LogTag.BLE,
            "profile ${resolved.profile}: notify=${resolved.notify.uuid} write=${resolved.write.uuid}",
        )
        channel = resolved

        withBondRetry("notifications") { enableNotifications(connection, resolved.notify) }

        // Both of these are optimisations. A clone that answers neither still works, and a
        // clone that drops the link when asked is a clone this app has to survive — so they
        // run last, after the channel is already usable, and their failures are only logged.
        delay(POST_NOTIFY_SETTLE_MILLIS)
        runCatching { awaitOperation(GattOp.Mtu) { connection.requestMtu(PREFERRED_MTU) } }
            .onFailure { ObdLog.log(LogTag.BLE, "MTU request failed: ${it.message}") }
        runCatching {
            connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        }.onFailure { ObdLog.log(LogTag.BLE, "connection priority request failed: ${it.message}") }

        _connected.value = true
        ObdLog.log(LogTag.BLE, "transport ready, mtu=$negotiatedMtu")
    }

    /**
     * Enables notifications, tolerating a missing CCCD.
     *
     * A handful of clones expose a notify characteristic with no 0x2902 descriptor at all
     * and push notifications anyway once the local flag is set. Refusing to continue there
     * turns a working adapter into "no compatible profile".
     */
    private suspend fun enableNotifications(
        connection: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (!connection.setCharacteristicNotification(characteristic, true)) {
            throw IOException("Could not enable notifications locally")
        }
        val cccd = characteristic.getDescriptor(ElmGattProfiles.CLIENT_CHARACTERISTIC_CONFIG)
        if (cccd == null) {
            ObdLog.log(LogTag.BLE, "notify characteristic has no CCCD — continuing without it")
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        awaitOperation(GattOp.Descriptor) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33 returns a BluetoothStatusCodes value, not a GATT status.
                connection.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                connection.writeDescriptor(cccd)
            }
        }
        ObdLog.log(LogTag.BLE, "CCCD written")
    }

    private suspend fun writeChunk(
        connection: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ) {
        val writeType = if (characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        awaitOperation(GattOp.Write) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                connection.writeCharacteristic(characteristic, chunk, writeType) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                connection.writeCharacteristic(characteristic)
            }
        }
    }

    /** Runs one GATT operation and suspends until *its own* callback reports completion. */
    private suspend fun awaitOperation(kind: GattOp, start: () -> Boolean) = operations.withLock {
        val pending = PendingOp(kind, CompletableDeferred())
        pendingOperation = pending
        try {
            if (!start()) throw IOException("GATT operation $kind was rejected")
            withTimeoutOrIo(OPERATION_TIMEOUT_MILLIS, kind.name) { pending.completion.await() }
        } finally {
            pendingOperation = null
        }
    }

    /**
     * `withTimeout` throws [TimeoutCancellationException], which *is* a
     * `CancellationException`: every `catch (cancelled: CancellationException) { throw it }`
     * between here and the UI treats it as the driver having pressed cancel and stops
     * without reporting anything. Converting it at the source is what makes a timed-out
     * GATT operation surface as a failed connection rather than as silence.
     */
    private suspend fun <T> withTimeoutOrIo(millis: Long, what: String, block: suspend () -> T): T =
        try {
            withTimeout(millis) { block() }
        } catch (timeout: TimeoutCancellationException) {
            ObdLog.log(LogTag.BLE, "$what timed out after $millis ms")
            throw IOException("$what timed out after $millis ms", timeout)
        }

    /**
     * Retries [block] once after the adapter has finished bonding.
     *
     * Statuses 5, 15 and 137 mean "this needs authentication", and Android is at that
     * moment already showing the pairing dialog. Tearing the link down cancels it, so the
     * app pairs and drops in a loop; waiting and repeating the operation is what actually
     * gets past a dongle with an encrypted characteristic.
     */
    private suspend fun <T> withBondRetry(what: String, block: suspend () -> T): T = try {
        block()
    } catch (error: GattException) {
        if (!GattStatus.needsBonding(error.status)) throw error
        ObdLog.log(LogTag.BLE, "$what needs bonding (${GattStatus.name(error.status)}) — waiting")
        if (!awaitBonded()) throw error
        ObdLog.log(LogTag.BLE, "bonded, retrying $what")
        block()
    }

    /** A connect issued while the system is mid-pairing fails; this waits that out. */
    private suspend fun awaitBondSettled() {
        if (device.bondState != BluetoothDevice.BOND_BONDING) return
        ObdLog.log(LogTag.BLE, "device is bonding — waiting before connect")
        awaitBonded()
    }

    private suspend fun awaitBonded(): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        val bonded = withTimeoutOrNull(BOND_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        ObdLog.log(LogTag.BLE, "bond state ${GattStatus.bondStateName(state)}")
                        if (state == BluetoothDevice.BOND_BONDED ||
                            state == BluetoothDevice.BOND_NONE
                        ) {
                            runCatching { context.unregisterReceiver(this) }
                            if (continuation.isActive) {
                                continuation.resumeWith(
                                    Result.success(state == BluetoothDevice.BOND_BONDED),
                                )
                            }
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                continuation.invokeOnCancellation {
                    runCatching { context.unregisterReceiver(receiver) }
                }
            }
        }
        if (bonded == null) ObdLog.log(LogTag.BLE, "bonding timed out after $BOND_TIMEOUT_MILLIS ms")
        return bonded == true
    }

    /**
     * The hidden `BluetoothGatt.refresh()`.
     *
     * Android caches a device's service list across connections and never invalidates it for
     * a dongle that changes its GATT layout between firmware modes — which is exactly what a
     * dual-mode adapter does. There is no public API for this and there never has been.
     */
    private fun refreshCache(connection: BluetoothGatt) {
        val refreshed = runCatching {
            connection.javaClass.getMethod("refresh").invoke(connection) as? Boolean
        }.getOrNull()
        ObdLog.log(LogTag.BLE, "gatt.refresh() → $refreshed")
    }

    private fun finish(kind: GattOp, status: Int, what: String) {
        val pending = pendingOperation
        if (pending == null || pending.kind != kind) {
            // A callback for an operation that already timed out. Completing the *current*
            // pending operation with it would resolve the wrong deferred.
            ObdLog.log(LogTag.BLE, "late $what callback ignored (${GattStatus.name(status)})")
            return
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            pending.completion.complete(Unit)
        } else {
            ObdLog.log(LogTag.BLE, "$what failed: ${GattStatus.name(status)}")
            pending.completion.completeExceptionally(GattException(status, what))
        }
    }

    /**
     * Closes the link the way the stack wants it closed.
     *
     * `close()` without a preceding `disconnect()` leaves the controller holding the link
     * until it times out, and the next `connectGatt` then fails with 133 — permanently, as
     * far as the driver is concerned. The settle delay after `close()` is the other half of
     * the same problem: the client slot is not free the instant the call returns.
     */
    private suspend fun teardown(reason: String) {
        _connected.value = false
        channel = null
        val connection = gatt ?: return
        gatt = null
        ObdLog.log(LogTag.BLE, "teardown: $reason")
        val settled = disconnected
        runCatching { connection.disconnect() }
        if (settled != null) withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) { settled.await() }
        runCatching { connection.close() }
        delay(CLOSE_SETTLE_MILLIS)
        ObdLog.log(LogTag.BLE, "teardown complete")
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            ObdLog.log(
                LogTag.BLE,
                "onConnectionStateChange ${GattStatus.stateName(newState)} " +
                    GattStatus.name(status),
            )
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    _connected.value = false
                    disconnected?.complete(Unit)
                    pendingConnect?.completeExceptionally(GattException(status, "connect failed"))
                    pendingOperation?.completion
                        ?.completeExceptionally(GattException(status, "link lost"))
                }

                newState == BluetoothProfile.STATE_CONNECTED -> pendingConnect?.complete(Unit)

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    _connected.value = false
                    disconnected?.complete(Unit)
                    // Without this the connect deferred is never completed at all: the
                    // adapter accepting and immediately dropping the link used to cost
                    // three twelve-second timeouts per attempt with nothing on screen.
                    pendingConnect?.completeExceptionally(
                        GattException(status, "peer disconnected during connect"),
                    )
                    pendingOperation?.completion
                        ?.completeExceptionally(GattException(status, "disconnected"))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            finish(GattOp.Discover, status, "service discovery")

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            ObdLog.log(LogTag.BLE, "onMtuChanged $mtu ${GattStatus.name(status)}")
            finish(GattOp.Mtu, status, "MTU negotiation")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) = finish(GattOp.Descriptor, status, "descriptor write")

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) = finish(GattOp.Write, status, "characteristic write")

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            received.trySend(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            characteristic.value?.let { received.trySend(it.copyOf()) }
        }
    }

    companion object {
        /** ATT default is 23 bytes; clones routinely refuse to negotiate anything larger. */
        const val DEFAULT_MTU = 23

        /**
         * 185, not 517: the maximum a single LE data-length-extended packet carries. Asking
         * for 517 makes a noticeable share of clones drop the link instead of answering.
         */
        const val PREFERRED_MTU = 185

        /** Writes stay at 20 bytes whatever the MTU negotiation produced. */
        const val MAX_CHUNK = 20

        const val CONNECT_ATTEMPTS = 3
        const val RETRY_BASE_MILLIS = 1_000L
        const val CONNECT_TIMEOUT_MILLIS = 12_000L
        const val OPERATION_TIMEOUT_MILLIS = 6_000L
        const val BOND_TIMEOUT_MILLIS = 30_000L

        /** Let the CCCD write land before asking for anything else on the link. */
        private const val POST_NOTIFY_SETTLE_MILLIS = 200L
        private const val DISCONNECT_TIMEOUT_MILLIS = 1_000L
        private const val CLOSE_SETTLE_MILLIS = 1_500L

        private const val COMMAND_TERMINATOR = "\r"
    }
}
