package com.miskibin.obd2dashboard.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One column of a trip recording: a stable key, the unit it was logged in, and whether the
 * app worked the numbers out rather than reading them off the car.
 *
 * The mark goes in the header because a recording outlives the screen it was made on. A
 * mechanic opening the file in a spreadsheet a week later has no tile to tap, and a column
 * of consumption figures derived from air flow looks exactly like one the ECU reported.
 */
data class CsvColumn(val key: String, val unit: String, val estimated: Boolean = false) {
    /** e.g. `pid:0C (rpm)`, or `derived:boost (kPa, estimated)` for a computed one. */
    val header: String
        get() {
            val note = listOf(unit, if (estimated) ESTIMATED else "")
                .filter(String::isNotBlank)
                .joinToString(", ")
            return if (note.isBlank()) key else "$key ($note)"
        }

    private companion object {
        /**
         * English, like the rest of a CSV header.
         *
         * [TripAnalyzer][com.miskibin.obd2dashboard.data.TripAnalyzer] reads a column back
         * by everything before the first " (", so this never has to be parsed — and a file
         * written by an older build still loads.
         */
        const val ESTIMATED = "estimated"
    }
}

/**
 * Writes trip recordings as plain RFC 4180 CSV.
 *
 * Numbers always use a dot as the decimal separator and a comma as the field separator
 * regardless of the phone's locale, otherwise a recording made on a Polish phone would
 * not open in any spreadsheet the driver later mails it to.
 */
object CsvFormat {

    const val SEPARATOR = ','
    const val TIMESTAMP_COLUMN = "timestamp"
    const val ELAPSED_COLUMN = "elapsed_s"

    private const val MAX_DECIMALS = 3
    private val ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT)

    fun header(columns: List<CsvColumn>): String =
        (listOf(TIMESTAMP_COLUMN, ELAPSED_COLUMN) + columns.map(CsvColumn::header))
            .joinToString(SEPARATOR.toString(), transform = ::escape)

    /**
     * A single sample row. [values] must line up with the columns passed to [header];
     * a null is written as an empty field so gaps stay distinguishable from zeros.
     */
    fun row(timestampMillis: Long, startedAtMillis: Long, values: List<Double?>, zone: ZoneId = ZoneId.systemDefault()): String {
        val elapsed = (timestampMillis - startedAtMillis) / 1000.0
        val fields = buildList {
            add(timestamp(timestampMillis, zone))
            add(number(elapsed))
            values.forEach { add(number(it)) }
        }
        return fields.joinToString(SEPARATOR.toString(), transform = ::escape)
    }

    fun timestamp(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        ISO_LOCAL.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** Up to three decimals, trailing zeros trimmed; null and non-finite become empty. */
    fun number(value: Double?): String {
        if (value == null || !value.isFinite()) return ""
        val text = "%.${MAX_DECIMALS}f".format(Locale.ROOT, value)
        if (!text.contains('.')) return text
        return text.trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
    }

    /** Quotes a field only when it would otherwise break the row. */
    fun escape(field: String): String {
        val needsQuotes = field.any { it == SEPARATOR || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    /** Reads the `elapsed_s` field back out of a written row; used to size a recording. */
    fun elapsedOf(row: String): Double? {
        val fields = splitRow(row)
        return fields.getOrNull(1)?.toDoubleOrNull()
    }

    /** Minimal RFC 4180 splitter, enough to read back rows this object wrote. */
    fun splitRow(row: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < row.length) {
            val char = row[index]
            when {
                inQuotes && char == '"' && row.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index++
                }

                char == '"' -> inQuotes = !inQuotes
                char == SEPARATOR && !inQuotes -> {
                    fields += current.toString()
                    current.setLength(0)
                }

                else -> current.append(char)
            }
            index++
        }
        fields += current.toString()
        return fields
    }
}
