package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import java.io.BufferedWriter
import java.io.File

/**
 * Writes one recording as CSV, with a column set that grows to match the car.
 *
 * The column set used to be frozen when recording started, which meant a parameter the
 * driver put on the chart after pressing record — or one the car only began answering for
 * once it warmed up — never reached the file. Here every snapshot is asked what it
 * carries, and anything new becomes a column from that row on.
 *
 * Rows written before a column appeared are simply shorter than the ones after it, which
 * every CSV reader treats as trailing empty fields. The header is the one line that has to
 * name the full set, so if columns did turn up late it is rewritten in [close] — a single
 * streamed pass over a file that has already been closed, rather than buffering a whole
 * drive in memory to keep a rectangle.
 */
class TripWriter(private val file: File, private val startedAtMillis: Long) {

    private val columns = mutableListOf<MetricId>()
    private val known = HashSet<MetricId>()
    private var headerColumns = 0
    private var writer: BufferedWriter? = null

    var rows: Int = 0
        private set

    /** The columns written so far, in file order. */
    val columnIds: List<MetricId> get() = columns.toList()

    val isOpen: Boolean get() = writer != null

    /**
     * Creates the file and writes the header.
     *
     * [seed] is what the app already knows it will be polling — the driver's tiles, the
     * chart series and every supported PID — so the common case writes one header and
     * never touches it again.
     */
    fun open(seed: List<MetricId> = emptyList()) {
        if (writer != null) return
        file.parentFile?.mkdirs()
        seed.forEach(::track)
        val out = file.bufferedWriter()
        out.appendLine(CsvFormat.header(columns.map(::columnOf)))
        out.flush()
        headerColumns = columns.size
        writer = out
    }

    /** Appends one sample row, adopting any metric the snapshot carries for the first time. */
    fun append(snapshot: VehicleSnapshot, atMillis: Long) {
        val out = writer ?: return
        snapshot.presentMetrics().forEach(::track)
        out.appendLine(CsvFormat.row(atMillis, startedAtMillis, columns.map(snapshot::valueOf)))
        rows++
        if (rows % FLUSH_EVERY_ROWS == 0) out.flush()
    }

    /**
     * Flushes and closes the file, deleting a recording that never got a row and
     * rewriting the header when the column set outgrew it.
     */
    fun close() {
        val out = writer ?: return
        writer = null
        runCatching {
            out.flush()
            out.close()
        }
        if (rows == 0) {
            file.delete()
            return
        }
        if (columns.size > headerColumns) {
            runCatching { rewriteHeader() }
            headerColumns = columns.size
        }
    }

    private fun track(id: MetricId) {
        if (known.add(id)) columns += id
    }

    /**
     * A column's header.
     *
     * Every derived metric is marked, whatever it happened to be computed from on the row
     * being written: the flag describes the column, and a fuel rate that was measured for
     * the first minute of a drive and estimated for the rest is still a column a reader has
     * to treat as an estimate.
     */
    private fun columnOf(id: MetricId) = CsvColumn(
        key = id.storageKey,
        unit = Metrics[id]?.unit.orEmpty(),
        estimated = id is MetricId.Derived,
    )

    /**
     * Replaces the first line with the full column set.
     *
     * Written to a sibling file and moved into place, so a failure half way through
     * leaves the recording it started from intact rather than a truncated one.
     */
    private fun rewriteHeader() {
        val parent = file.parentFile ?: return
        val temp = File(parent, file.name + TEMP_SUFFIX)
        temp.bufferedWriter().use { out ->
            out.appendLine(CsvFormat.header(columns.map(::columnOf)))
            file.bufferedReader().use { reader ->
                // The old header is the one line that is replaced rather than copied.
                reader.readLine()
                while (true) {
                    val line = reader.readLine() ?: break
                    out.appendLine(line)
                }
            }
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private companion object {
        const val FLUSH_EVERY_ROWS = 20
        const val TEMP_SUFFIX = ".part"
    }
}
