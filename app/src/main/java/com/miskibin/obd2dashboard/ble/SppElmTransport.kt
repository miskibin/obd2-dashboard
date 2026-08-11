package com.miskibin.obd2dashboard.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.miskibin.obd2dashboard.log.LogTag
import com.miskibin.obd2dashboard.log.ObdLog
import com.miskibin.obd2dashboard.obd.ElmTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * [ElmTransport] over classic Bluetooth RFCOMM — the Serial Port Profile.
 *
 * This is the transport most OBD-II dongles sold as "Bluetooth 4.0" actually want to be
 * driven over. The dual-radio units (Vgate iCar2 and its clones) carry two independent
 * identities with two different MAC addresses: a classic SPP one, which pairs with PIN 1234
 * and is what every Android scan tool uses, and a BLE one meant for iOS, which advertises
 * nameless and — depending on the firmware — accepts a GATT connection and then never
 * answers a command. An app that only speaks BLE fights the second one forever, which is
 * precisely the hang this class exists to end.
 *
 * SPP has no MTU, no characteristics, no CCCD and no 133: a socket, a blocking `read`, and
 * a blocking `write`. All three run on [Dispatchers.IO] — `connect()` alone can block for
 * twelve seconds, and doing that anywhere else is both a StrictMode violation and a frozen
 * frame on screen.
 */
@SuppressLint("MissingPermission")
class SppElmTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
) : ElmTransport {

    private val received = Channel<ByteArray>(Channel.UNLIMITED)

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val writes = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var pump: Job? = null

    override fun incoming(): Flow<ByteArray> = received.receiveAsFlow()

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            ObdLog.log(
                LogTag.SPP,
                "opening ${device.address} name=${deviceName()} " +
                    "type=${GattStatus.deviceTypeName(device.type)} " +
                    "bond=${GattStatus.bondStateName(device.bondState)}",
            )
            // Discovery starves the RFCOMM connect of radio time and is the documented
            // reason a connect that should take a second takes twelve and then fails.
            runCatching { adapter.cancelDiscovery() }

            var lastError: Throwable? = null
            val strategies = strategies()
            strategies.forEachIndexed { index, strategy ->
                val candidate = runCatching { strategy.open(device) }.getOrElse { error ->
                    ObdLog.log(LogTag.SPP, "${strategy.label}: could not create socket: ${error.message}")
                    lastError = error
                    return@forEachIndexed
                }
                try {
                    ObdLog.log(LogTag.SPP, "connecting via ${strategy.label}")
                    candidate.connect()
                    socket = candidate
                    _connected.value = true
                    startPump(candidate)
                    ObdLog.log(LogTag.SPP, "connected via ${strategy.label}")
                    return@withContext
                } catch (error: IOException) {
                    lastError = error
                    ObdLog.log(LogTag.SPP, "${strategy.label} failed: ${error.message}")
                    // A socket that failed to connect is not reusable and holds the
                    // channel until it is closed, so the next attempt would fail too.
                    runCatching { candidate.close() }
                    if (index < strategies.lastIndex) delay(RETRY_DELAY_MILLIS)
                }
            }
            throw lastError?.let { IOException("SPP connect failed: ${it.message}", it) }
                ?: IOException("Unable to open an SPP socket to ${device.address}")
        }
    }

    override suspend fun write(command: String) = writes.withLock {
        val stream = socket?.outputStream ?: throw IOException("Transport is closed")
        val payload = (command + COMMAND_TERMINATOR).toByteArray(Charsets.US_ASCII)
        withContext(Dispatchers.IO) {
            stream.write(payload)
            stream.flush()
        }
    }

    override suspend fun close() {
        if (socket == null && pump == null) return
        ObdLog.log(LogTag.SPP, "closing")
        _connected.value = false
        val open = socket
        socket = null
        // The socket is closed *before* the pump is cancelled: a coroutine blocked inside
        // InputStream.read() does not notice cancellation, only the stream going away.
        withContext(NonCancellable + Dispatchers.IO) { runCatching { open?.close() } }
        pump?.cancel()
        pump = null
        scope.cancel()
    }

    /**
     * A blocking read loop, which is the only kind RFCOMM offers.
     *
     * Chunks are forwarded exactly as they arrive — [com.miskibin.obd2dashboard.obd.ElmSession]
     * does the `>`-framing, and a serial stream has no other boundary to respect.
     */
    private fun startPump(open: BluetoothSocket) {
        pump = scope.launch {
            val buffer = ByteArray(READ_BUFFER)
            try {
                val input = open.inputStream
                while (isActive) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        ObdLog.log(LogTag.SPP, "stream reached end of file")
                        break
                    }
                    if (read > 0) received.trySend(buffer.copyOf(read))
                }
            } catch (error: IOException) {
                ObdLog.log(LogTag.SPP, "read pump stopped: ${error.message}")
            } finally {
                _connected.value = false
            }
        }
    }

    private fun deviceName(): String = runCatching { device.name }.getOrNull() ?: "(unnamed)"

    /**
     * The three ways to get an RFCOMM socket, in the order they are worth trying.
     *
     * The secure one is correct and works with a properly paired adapter. The insecure one
     * gets past clones whose SDP record advertises a service they then refuse to
     * authenticate. The last one asks for channel 1 through a method that has never been
     * public API and is the only thing that works on dongles with no SDP record at all —
     * which is a large share of the ten-euro ones.
     */
    private fun strategies() = listOf(
        SocketStrategy("secure RFCOMM") { it.createRfcommSocketToServiceRecord(SPP_UUID) },
        SocketStrategy("insecure RFCOMM") { it.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        SocketStrategy("reflection, channel 1") { target ->
            val method = target.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(target, 1) as BluetoothSocket
        },
    )

    private class SocketStrategy(
        val label: String,
        val open: (BluetoothDevice) -> BluetoothSocket,
    )

    companion object {
        /** The well-known Serial Port Profile UUID; every ELM327 clone answers on it. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val COMMAND_TERMINATOR = "\r"
        private const val READ_BUFFER = 1_024
        private const val RETRY_DELAY_MILLIS = 500L
    }
}
