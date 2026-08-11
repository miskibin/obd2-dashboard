/**
 * OBD-II protocol layer.
 *
 * Owns everything that is transport-independent: ELM327 AT command handling, PID
 * definitions, request framing and response parsing into typed readings.
 * Nothing here knows about Bluetooth, and nothing here may import `android.*` —
 * the whole layer is exercised by plain JVM unit tests.
 */
package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.flow.Flow

/**
 * A byte pipe to an ELM327 adapter. Implementations exist per radio (BLE today,
 * classic SPP / WiFi later); all `>`-framing, echo stripping and NUL filtering lives
 * above this in [ElmSession].
 */
interface ElmTransport {

    suspend fun open()

    /** Sends [command] followed by the CR terminator the ELM327 expects. */
    suspend fun write(command: String)

    /** Raw inbound chunks, in arrival order. Never split on any protocol boundary. */
    fun incoming(): Flow<ByteArray>

    suspend fun close()
}

/** Thrown when the adapter reset itself and every session setting is back to default. */
class ElmFatalException(val error: ElmError) : Exception("ELM327 fatal condition: $error")
