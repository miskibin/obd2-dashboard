package com.miskibin.obd2dashboard.obd

import com.miskibin.obd2dashboard.log.LogTag
import com.miskibin.obd2dashboard.log.ObdLog
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
 *
 * [onStep] is how the connect screen shows which command it is on. Every exchange is also
 * written to the connection log with its timing, because "it just sits there" is otherwise
 * impossible to tell apart from "the car answered `NO DATA` to `0100` in 40 ms".
 */
class ElmInitializer(
    private val session: ElmSession,
    private val config: ElmInitConfig = ElmInitConfig(),
    private val onStep: (String) -> Unit = {},
) {
    private val unsupported = mutableListOf<String>()
    private var lastProbeError: ElmError? = null

    suspend fun initialize(): InitOutcome {
        unsupported.clear()
        lastProbeError = null
        ObdLog.log(LogTag.ELM, "init start (preferred protocol ${config.preferredProtocol})")

        var resetWait = config.resetWaitMillis
        if (config.useWarmStart) {
            val warm = optional("ATWS", ElmSession.RESET_TIMEOUT_MILLIS)
            // A chip that does not know `ATWS` answers `?`. `ATZ` is the cold reset every
            // one of them knows, and it takes noticeably longer to come back.
            if (warm is ElmResponse.Failure && warm.error == ElmError.SyntaxError) {
                ObdLog.log(LogTag.ELM, "ATWS rejected — falling back to a cold ATZ")
                optional("ATZ", ElmSession.RESET_TIMEOUT_MILLIS)
                resetWait = maxOf(resetWait, COLD_RESET_WAIT_MILLIS)
            }
        } else {
            optional("ATZ", ElmSession.RESET_TIMEOUT_MILLIS)
        }
        delay(resetWait)

        if (!echoOff()) return failure("ATE0", ElmError.SyntaxError)
        settings().forEach { optional(it) }

        val preferred = config.preferredProtocol?.takeIf { it != ObdProtocol.Automatic }
        optional("ATSP${preferred?.atspDigit ?: "0"}")

        val identifier = optional("ATI").lines.firstOrNull()
        val voltage = ElmVoltage.parse(optional("ATRV").lines)

        var protocol = preferred
        if (!probe(if (preferred != null) PROBE_TIMEOUT_MILLIS else ElmSession.PROTOCOL_SEARCH_TIMEOUT_MILLIS)) {
            if (preferred == null) {
                return failure(SUPPORT_PROBE, lastProbeError ?: ElmError.UnableToConnect)
            }
            optional("ATSP0")
            if (!probe(ElmSession.PROTOCOL_SEARCH_TIMEOUT_MILLIS)) {
                return failure(SUPPORT_PROBE, lastProbeError ?: ElmError.UnableToConnect)
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

        ObdLog.log(
            LogTag.ELM,
            "init done: id=$identifier protocol=$protocol autoDetected=$autoDetected " +
                "voltage=$voltage rejected=$unsupported",
        )
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

    private fun failure(step: String, error: ElmError): InitOutcome.Failure {
        ObdLog.log(LogTag.ELM, "init FAILED at $step: $error")
        return InitOutcome.Failure(step, error)
    }

    private fun settings() = listOf(
        "ATL0",
        "ATS0",
        if (config.headersOn) "ATH1" else "ATH0",
        "ATAL",
        "ATAT1",
        "ATST${config.responseTimeoutHex}",
    )

    /**
     * The one command that has to succeed.
     *
     * Three attempts with a generous ceiling rather than two quick ones: a clone that has
     * just come back from `ATZ` regularly swallows the first command outright, and the
     * whole framing above depends on the echo being off.
     */
    private suspend fun echoOff(): Boolean {
        repeat(ECHO_OFF_ATTEMPTS) {
            val response = exchange("ATE0", ECHO_OFF_TIMEOUT_MILLIS)
            delay(config.interCommandDelayMillis)
            if (response is ElmResponse.Ok) return true
        }
        return false
    }

    /** The `0100` request that forces the protocol search to actually happen. */
    private suspend fun probe(timeoutMillis: Long): Boolean {
        val response = exchange(SUPPORT_PROBE, timeoutMillis)
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
        var response = exchange(command, timeoutMillis)
        if (response is ElmResponse.Failure && response.error != ElmError.SyntaxError) {
            delay(config.interCommandDelayMillis)
            response = exchange(command, timeoutMillis)
        }
        if (response is ElmResponse.Failure) unsupported += command
        delay(config.interCommandDelayMillis)
        return response
    }

    /** One request, announced to the UI and written to the log with how long it took. */
    private suspend fun exchange(command: String, timeoutMillis: Long): ElmResponse {
        onStep(command)
        val started = System.currentTimeMillis()
        val response = session.request(command, timeoutMillis)
        val elapsed = System.currentTimeMillis() - started
        val outcome = when (response) {
            is ElmResponse.Ok -> "OK ${response.lines}"
            is ElmResponse.Failure -> "FAILED ${response.error}"
        }
        ObdLog.log(LogTag.ELM, "$command → $outcome in $elapsed ms")
        return response
    }

    companion object {
        /** What the screen says before the first command has been sent. */
        const val FIRST_STEP = "ELM327"

        const val SUPPORT_PROBE = "0100"

        private const val ECHO_OFF_ATTEMPTS = 3
        private const val ECHO_OFF_TIMEOUT_MILLIS = 3_000L
        private const val PROBE_TIMEOUT_MILLIS = 8_000L

        /** A cold `ATZ` runs the chip's whole self-test before it answers again. */
        private const val COLD_RESET_WAIT_MILLIS = 3_000L
    }
}
