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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.miskibin.obd2dashboard.obd.ElmTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException

class GattException(val status: Int, message: String) : IOException("$message (status $status)")

/**
 * [ElmTransport] over BLE GATT.
 *
 * Android allows exactly one outstanding GATT operation per connection, so connect,
 * MTU, service discovery, descriptor writes and every 20-byte write chunk are pushed
 * through a single mutex and resolved by their callback. Notifications are forwarded as
 * an unframed byte stream — a response routinely arrives as two or three of them.
 */
@SuppressLint("MissingPermission")
class BleElmTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : ElmTransport {

    private val received = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = INCOMING_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val operations = Mutex()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var channel: SerialChannel? = null

    @Volatile
    private var pendingOperation: CompletableDeferred<Unit>? = null

    @Volatile
    private var pendingConnect: CompletableDeferred<Unit>? = null

    @Volatile
    var negotiatedMtu: Int = DEFAULT_MTU
        private set

    val profileName: String? get() = channel?.profile

    override fun incoming(): Flow<ByteArray> = received.asSharedFlow()

    override suspend fun open() {
        if (!hasConnectPermission()) throw SecurityException("BLUETOOTH_CONNECT not granted")
        var lastError: Throwable? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            try {
                connectOnce()
                return
            } catch (error: Exception) {
                lastError = error
                teardown()
                if (attempt < CONNECT_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
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
        teardown()
    }

    private suspend fun connectOnce() {
        val connected = CompletableDeferred<Unit>()
        pendingConnect = connected
        val connection = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        gatt = connection
        withTimeout(CONNECT_TIMEOUT_MILLIS) { connected.await() }

        runCatching {
            connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        }
        runCatching { awaitOperation { connection.requestMtu(PREFERRED_MTU) } }

        awaitOperation { connection.discoverServices() }
        val resolved = ElmGattProfiles.resolve(connection.services)
            ?: throw IOException("No ELM327-compatible GATT profile on ${device.address}")
        channel = resolved
        enableNotifications(connection, resolved.notify)
        _connected.value = true
    }

    private suspend fun enableNotifications(
        connection: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (!connection.setCharacteristicNotification(characteristic, true)) {
            throw IOException("Could not enable notifications locally")
        }
        val cccd = characteristic.getDescriptor(ElmGattProfiles.CLIENT_CHARACTERISTIC_CONFIG)
            ?: throw IOException("Notify characteristic has no CCCD")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        awaitOperation {
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
        awaitOperation {
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

    /** Runs one GATT operation and suspends until its callback reports completion. */
    private suspend fun awaitOperation(start: () -> Boolean) = operations.withLock {
        val completion = CompletableDeferred<Unit>()
        pendingOperation = completion
        try {
            if (!start()) throw IOException("GATT operation was rejected")
            withTimeout(OPERATION_TIMEOUT_MILLIS) { completion.await() }
        } finally {
            pendingOperation = null
        }
    }

    private fun finish(status: Int, what: String) {
        val pending = pendingOperation ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) pending.complete(Unit)
        else pending.completeExceptionally(GattException(status, what))
    }

    private fun teardown() {
        _connected.value = false
        channel = null
        val connection = gatt ?: return
        gatt = null
        runCatching { connection.disconnect() }
        runCatching { connection.close() }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    _connected.value = false
                    pendingConnect?.completeExceptionally(GattException(status, "connect failed"))
                    pendingOperation?.completeExceptionally(GattException(status, "link lost"))
                }

                newState == BluetoothProfile.STATE_CONNECTED -> pendingConnect?.complete(Unit)

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    _connected.value = false
                    pendingOperation?.completeExceptionally(GattException(status, "disconnected"))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            finish(status, "service discovery")

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            finish(status, "MTU negotiation")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) = finish(status, "descriptor write")

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) = finish(status, "characteristic write")

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            received.tryEmit(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            characteristic.value?.let { received.tryEmit(it.copyOf()) }
        }
    }

    companion object {
        /** ATT default is 23 bytes; clones routinely refuse to negotiate anything larger. */
        const val DEFAULT_MTU = 23
        const val PREFERRED_MTU = 517

        /** Writes stay at 20 bytes whatever the MTU negotiation produced. */
        const val MAX_CHUNK = 20

        const val CONNECT_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 500L
        const val CONNECT_TIMEOUT_MILLIS = 12_000L
        const val OPERATION_TIMEOUT_MILLIS = 6_000L

        private const val COMMAND_TERMINATOR = "\r"
        private const val INCOMING_BUFFER = 256
    }
}
