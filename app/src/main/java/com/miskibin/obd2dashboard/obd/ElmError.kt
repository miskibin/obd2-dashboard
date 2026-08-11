package com.miskibin.obd2dashboard.obd

/**
 * Status strings the ELM327 can emit instead of data. Matched before any hex parsing.
 *
 * @param requiresReinit the adapter lost its configuration; the full init sequence has
 *   to run again before polling can resume.
 * @param retryable the same request is worth sending again straight away.
 */
enum class ElmError(
    val requiresReinit: Boolean = false,
    val retryable: Boolean = false,
) {
    SyntaxError,
    NoData,
    UnableToConnect,
    Stopped(retryable = true),
    BusInitError(retryable = true),
    BusBusy(retryable = true),
    BusError,
    CanError,
    DataError(retryable = true),
    BufferFull,
    FatalCanError(requiresReinit = true),
    InternalError(requiresReinit = true),
    LowVoltageReset(requiresReinit = true),
    FeedbackError,
    ActivityAlert,
    LowPowerAlert(requiresReinit = true),
    Timeout(retryable = true),
    ;

    companion object {
        private val ERR_CODE = Regex("^ERR[0-9A-F]{2}$")

        fun match(line: String): ElmError? {
            val trimmed = line.trim().uppercase()
            if (trimmed.isEmpty()) return null
            val compact = trimmed.replace(" ", "").removePrefix("!").removePrefix("<")
            return when {
                trimmed == "?" -> SyntaxError
                compact.startsWith("NODATA") -> NoData
                compact.startsWith("UNABLETOCONNECT") -> UnableToConnect
                compact.startsWith("STOPPED") -> Stopped
                compact.startsWith("BUSINIT") -> if (compact.contains("ERROR")) BusInitError else null
                compact.startsWith("BUSBUSY") -> BusBusy
                compact.startsWith("BUSERROR") -> BusError
                compact.startsWith("CANERROR") -> CanError
                compact.contains("DATAERROR") -> DataError
                compact.startsWith("BUFFERFULL") -> BufferFull
                compact == "ERR94" -> FatalCanError
                ERR_CODE.matches(compact) -> InternalError
                compact.startsWith("LVRESET") -> LowVoltageReset
                compact.startsWith("FBERROR") -> FeedbackError
                compact.contains("ACTALERT") -> ActivityAlert
                compact.contains("LPALERT") -> LowPowerAlert
                else -> null
            }
        }

        /**
         * Progress chatter that carries no payload: the `SEARCHING...` banner of an
         * `ATSP0` protocol hunt and successful slow-init progress lines.
         */
        fun isInformational(line: String): Boolean {
            val compact = line.trim().uppercase().replace(" ", "")
            return compact.startsWith("SEARCHING") ||
                (compact.startsWith("BUSINIT") && !compact.contains("ERROR"))
        }
    }
}

/** Outcome of a single request/response exchange. */
sealed interface ElmResponse {

    val raw: String

    data class Ok(val lines: List<String>, override val raw: String) : ElmResponse

    data class Failure(val error: ElmError, override val raw: String) : ElmResponse
}

val ElmResponse.lines: List<String>
    get() = (this as? ElmResponse.Ok)?.lines.orEmpty()

val ElmResponse.errorOrNull: ElmError?
    get() = (this as? ElmResponse.Failure)?.error
