/**
 * The connection log.
 *
 * A Bluetooth failure in a car park is not reproducible at a desk: the adapter, the phone,
 * the ignition state and the ten seconds of GATT callbacks that led to the hang are all
 * gone by the time anybody can attach a debugger. So every step of scan → link → ELM327
 * init writes one line here, and the driver can hand the whole buffer over from Settings.
 *
 * Nothing in this package imports `android.*`: the ring buffer is plain Kotlin so the
 * protocol layer may log too, and so it can be unit tested. [ObdLog.echo] is where the
 * application wires `android.util.Log` in.
 */
package com.miskibin.obd2dashboard.log

/** One logged line. [timeMillis] is wall-clock time, so the log can be read next to a trip. */
data class ObdLogEntry(val timeMillis: Long, val tag: String, val message: String)

object ObdLog {

    /**
     * Roughly two full connect attempts including a service dump — long enough that the
     * first failure is still in the buffer after the app has retried, short enough to hold
     * in memory and paste into a message.
     */
    const val CAPACITY = 500

    /** Where a line also goes on a device: set by the application to `android.util.Log`. */
    @Volatile
    var echo: ((ObdLogEntry) -> Unit)? = null

    /** Replaceable so tests get deterministic timestamps. */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    private val lock = Any()
    private val entries = ArrayDeque<ObdLogEntry>(CAPACITY)

    fun log(tag: String, message: String) {
        val entry = ObdLogEntry(clock(), tag, message)
        synchronized(lock) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(entry)
        }
        echo?.invoke(entry)
    }

    /** Lazily formatted: a service dump is expensive to build and usually not read. */
    inline fun log(tag: String, message: () -> String) = log(tag, message())

    fun entries(): List<ObdLogEntry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    /** The whole buffer as the plain text the share sheet sends. */
    fun dump(format: (ObdLogEntry) -> String = ::defaultFormat): String =
        entries().joinToString("\n", transform = format)

    /**
     * `12:04:31.882 BLE  onConnectionStateChange …`
     *
     * Formatted by hand rather than through `SimpleDateFormat`, because this file may not
     * touch the platform and because the only thing the reader needs is the offset between
     * two lines.
     */
    fun defaultFormat(entry: ObdLogEntry): String {
        val millisOfDay = Math.floorMod(entry.timeMillis, DAY_MILLIS)
        val hours = millisOfDay / HOUR_MILLIS
        val minutes = millisOfDay % HOUR_MILLIS / MINUTE_MILLIS
        val seconds = millisOfDay % MINUTE_MILLIS / 1_000
        val millis = millisOfDay % 1_000
        return "%02d:%02d:%02d.%03d %-5s %s".format(hours, minutes, seconds, millis, entry.tag, entry.message)
    }

    private const val DAY_MILLIS = 86_400_000L
    private const val HOUR_MILLIS = 3_600_000L
    private const val MINUTE_MILLIS = 60_000L
}

/** The tags the log is grouped by, so a reader can tell a scan line from an ELM327 line. */
object LogTag {
    const val SCAN = "SCAN"
    const val BLE = "BLE"
    const val SPP = "SPP"
    const val CONN = "CONN"
    const val ELM = "ELM"
}
