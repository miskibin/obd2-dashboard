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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A byte pipe to an ELM327 adapter. Implementations exist per radio (BLE GATT and classic
 * SPP today, WiFi later); all `>`-framing, echo stripping and NUL filtering lives above
 * this in [ElmSession].
 */
interface ElmTransport {

    suspend fun open()

    /** Sends [command] followed by the CR terminator the ELM327 expects. */
    suspend fun write(command: String)

    /** Raw inbound chunks, in arrival order. Never split on any protocol boundary. */
    fun incoming(): Flow<ByteArray>

    /**
     * Whether the radio link is up.
     *
     * A dropped link is otherwise invisible until the next command times out, which on a
     * paused polling loop can be minutes of the UI claiming "Connected". Transports with
     * nothing to report (the simulation, the test fake) inherit a link that is simply
     * always up.
     */
    val connected: StateFlow<Boolean> get() = ALWAYS_CONNECTED

    suspend fun close()

    companion object {
        private val ALWAYS_CONNECTED: StateFlow<Boolean> = MutableStateFlow(true)
    }
}

/**
 * Thrown when the adapter reset itself and every session setting is back to default, or
 * when the init sequence could not get past one of its steps.
 *
 * [step] is the command that failed (`ATE0`, `0100`), which is the difference between a UI
 * that says "connection problem" and one that says what the car did not answer.
 */
class ElmFatalException(
    val error: ElmError,
    val step: String? = null,
) : Exception("ELM327 fatal condition: $error" + step?.let { " at $it" }.orEmpty())
