package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Request/response framing on top of an [ElmTransport].
 *
 * The ELM327 has no pipelining: a command sent before the `>` prompt aborts the one in
 * flight, so every exchange is serialized through a mutex. Inbound bytes are accumulated
 * until the prompt arrives, `0x00` filler is dropped (documented ELM327 quirk), and the
 * echo, `SEARCHING...` chatter and bus-init progress lines are removed before the caller
 * sees anything.
 */
class ElmSession(
    private val transport: ElmTransport,
    scope: CoroutineScope,
    private val defaultTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val mutex = Mutex()
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    private val buffer = StringBuilder()

    private val pump = scope.launch {
        transport.incoming().collect { chunks.send(it) }
    }

    suspend fun request(
        command: String,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): ElmResponse = mutex.withLock {
        discardStale()
        transport.write(command)
        val raw = withTimeoutOrNull(timeoutMillis) { readUntilPrompt() }
            ?: return@withLock ElmResponse.Failure(ElmError.Timeout, buffer.toString())
        parse(command, raw)
    }

    fun close() {
        pump.cancel()
        chunks.close()
    }

    private fun discardStale() {
        buffer.setLength(0)
        while (chunks.tryReceive().isSuccess) Unit
    }

    private suspend fun readUntilPrompt(): String {
        while (true) {
            val prompt = buffer.indexOf(PROMPT)
            if (prompt >= 0) {
                val response = buffer.substring(0, prompt)
                buffer.delete(0, prompt + 1)
                return response
            }
            append(chunks.receive())
        }
    }

    private fun append(chunk: ByteArray) {
        for (byte in chunk) {
            val value = byte.toInt() and 0xFF
            if (value != 0x00) buffer.append(value.toChar())
        }
    }

    private fun parse(command: String, raw: String): ElmResponse {
        val echo = command.filterNot(Char::isWhitespace).uppercase()
        val lines = raw.split('\r', '\n')
            .mapNotNull { stripNoise(it) }
            .filterNot { it.filterNot(Char::isWhitespace).uppercase() == echo }
        lines.firstNotNullOfOrNull { ElmError.match(it) }
            ?.let { return ElmResponse.Failure(it, raw) }
        return ElmResponse.Ok(lines, raw)
    }

    /** Returns the meaningful part of [line], or null when the whole line is chatter. */
    private fun stripNoise(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val withoutSearching = SEARCHING.replace(trimmed, "").trim()
        if (withoutSearching.isEmpty()) return null
        if (ElmError.isInformational(withoutSearching)) return null
        return withoutSearching
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 2_000L
        const val AT_TIMEOUT_MILLIS = 1_500L
        const val RESET_TIMEOUT_MILLIS = 5_000L

        /** A protocol search over all twelve protocols, 5-baud init attempts included. */
        const val PROTOCOL_SEARCH_TIMEOUT_MILLIS = 15_000L

        private const val PROMPT = ">"
        private val SEARCHING = Regex("SEARCHING\\.*", RegexOption.IGNORE_CASE)
    }
}
