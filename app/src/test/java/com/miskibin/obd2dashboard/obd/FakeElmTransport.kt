package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Scripted stand-in for a real adapter. Replies are pushed through an unlimited channel
 * so nothing is lost regardless of when the session starts pumping, and [chunkSize]
 * reproduces the way BLE notifications split a response into 20-byte pieces.
 */
class FakeElmTransport(
    private val chunkSize: Int = 512,
    private val responder: (String) -> String?,
) : ElmTransport {

    val commands = mutableListOf<String>()
    var opened = false
        private set
    var closed = false
        private set

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)

    override suspend fun open() {
        opened = true
    }

    override suspend fun write(command: String) {
        commands += command
        val reply = responder(command) ?: return
        reply.toByteArray(Charsets.ISO_8859_1).toList().chunked(chunkSize).forEach {
            channel.send(it.toByteArray())
        }
    }

    override fun incoming(): Flow<ByteArray> = channel.receiveAsFlow()

    override suspend fun close() {
        closed = true
    }

    fun countOf(prefix: String): Int = commands.count { it.startsWith(prefix) }

    companion object {

        const val PROMPT = "\r\r>"

        /**
         * Looks a command up in [script], ignoring the trailing response-count digit,
         * and answers `?` to anything unknown — exactly what a clone does.
         */
        fun scripted(
            chunkSize: Int = 512,
            script: Map<String, String>,
        ): FakeElmTransport {
            val normalized = script.mapKeys { it.key.uppercase() }
            return FakeElmTransport(chunkSize) { command ->
                val key = command.uppercase().filterNot(Char::isWhitespace)
                val reply = normalized[key] ?: normalized[key.dropLast(1)] ?: "?"
                reply + PROMPT
            }
        }
    }
}
