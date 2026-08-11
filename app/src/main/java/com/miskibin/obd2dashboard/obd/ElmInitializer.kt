package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.delay

data class ElmInitConfig(
    /** Warm start keeps the baud rate; some units only come back cleanly from `ATZ`. */
    val useWarmStart: Boolean = true,
    val resetWaitMillis: Long = 1_500,
    /** Clones drop commands issued immediately after `OK`. */
    val interCommandDelayMillis: Long = 60,
    val headersOn: Boolean = true,
    /** `AT ST` value in units of 4 ms; `32` ≈ 200 ms. */
    val responseTimeoutHex: String = "32",
    /** Protocol remembered for this vehicle, tried before falling back to `ATSP0`. */
    val preferredProtocol: ObdProtocol? = null,
)

data class AdapterInfo(
    val identifier: String?,
    val batteryVoltage: Double?,
    val protocol: ObdProtocol,
    val autoDetected: Boolean,
    val unsupportedCommands: List<String>,
)

sealed interface InitOutcome {
    data class Success(val info: AdapterInfo) : InitOutcome
    data class Failure(val step: String, val error: ElmError) : InitOutcome
}

/**
 * Brings a freshly connected adapter into a known state.
 *
 * Every command except `ATE0` is allowed to fail: clones answer `?` to perfectly legal
 * commands and there is no reliable way to tell a real chip from a reimplementation, so
 * the sequence records what was rejected and carries on. Echo off is the one thing the
 * framing above depends on.
 */
class ElmInitializer(
    private val session: ElmSession,
    private val config: ElmInitConfig = ElmInitConfig(),
) {
    private val unsupported = mutableListOf<String>()
    private var lastProbeError: ElmError? = null

    suspend fun initialize(): InitOutcome {
        unsupported.clear()
        lastProbeError = null

        optional(if (config.useWarmStart) "ATWS" else "ATZ", ElmSession.RESET_TIMEOUT_MILLIS)
        delay(config.resetWaitMillis)
        if (!echoOff()) return InitOutcome.Failure("ATE0", ElmError.SyntaxError)
        settings().forEach { optional(it) }

        val preferred = config.preferredProtocol?.takeIf { it != ObdProtocol.Automatic }
        optional("ATSP${preferred?.atspDigit ?: "0"}")

        val identifier = optional("ATI").lines.firstOrNull()
        val voltage = ElmVoltage.parse(optional("ATRV").lines)

        var protocol = preferred
        if (!probe(if (preferred != null) PROBE_TIMEOUT_MILLIS else ElmSession.PROTOCOL_SEARCH_TIMEOUT_MILLIS)) {
            if (preferred == null) {
                return InitOutcome.Failure("0100", lastProbeError ?: ElmError.UnableToConnect)
            }
            optional("ATSP0")
            if (!probe(ElmSession.PROTOCOL_SEARCH_TIMEOUT_MILLIS)) {
                return InitOutcome.Failure("0100", lastProbeError ?: ElmError.UnableToConnect)
            }
            protocol = null
        }

        var autoDetected = false
        if (protocol == null) {
            val readback = ObdProtocol.parseDpn(optional("ATDPN").lines.firstOrNull().orEmpty())
            protocol = readback?.first ?: ObdProtocol.Automatic
            autoDetected = readback?.second ?: true
            if (protocol != ObdProtocol.Automatic) optional("ATSP${protocol.atspDigit}")
        }

        return InitOutcome.Success(
            AdapterInfo(
                identifier = identifier,
                batteryVoltage = voltage,
                protocol = protocol,
                autoDetected = autoDetected,
                unsupportedCommands = unsupported.toList(),
            ),
        )
    }

    private fun settings() = listOf(
        "ATL0",
        "ATS0",
        if (config.headersOn) "ATH1" else "ATH0",
        "ATAL",
        "ATAT1",
        "ATST${config.responseTimeoutHex}",
    )

    private suspend fun echoOff(): Boolean {
        repeat(ECHO_OFF_ATTEMPTS) {
            val response = session.request("ATE0", ElmSession.AT_TIMEOUT_MILLIS)
            delay(config.interCommandDelayMillis)
            if (response is ElmResponse.Ok) return true
        }
        return false
    }

    /** The `0100` request that forces the protocol search to actually happen. */
    private suspend fun probe(timeoutMillis: Long): Boolean {
        val response = session.request(SUPPORT_PROBE, timeoutMillis)
        delay(config.interCommandDelayMillis)
        lastProbeError = response.errorOrNull
        if (response !is ElmResponse.Ok) return false
        val frames = ObdResponseParser.frames(response.lines)
        return ObdResponseParser.supportedPids(frames, 0x00).isNotEmpty()
    }

    private suspend fun optional(
        command: String,
        timeoutMillis: Long = ElmSession.AT_TIMEOUT_MILLIS,
    ): ElmResponse {
        var response = session.request(command, timeoutMillis)
        if (response is ElmResponse.Failure && response.error != ElmError.SyntaxError) {
            delay(config.interCommandDelayMillis)
            response = session.request(command, timeoutMillis)
        }
        if (response is ElmResponse.Failure) unsupported += command
        delay(config.interCommandDelayMillis)
        return response
    }

    private companion object {
        const val ECHO_OFF_ATTEMPTS = 2
        const val PROBE_TIMEOUT_MILLIS = 8_000L
        const val SUPPORT_PROBE = "0100"
    }
}
