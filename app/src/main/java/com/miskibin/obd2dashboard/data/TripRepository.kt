package com.miskibin.obd2dashboard.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A finished trip recording on disk. */
data class Trip(
    val file: File,
    val startedAtMillis: Long,
    val sizeBytes: Long,
    val durationSeconds: Double,
) {
    val name: String get() = file.name
}

/**
 * Lists, shares and deletes the CSV recordings written by [TripRecorder].
 *
 * Files live in app-private storage and are handed to other apps through a
 * [FileProvider] grant, so no storage permission is ever needed.
 */
class TripRepository(context: Context) {

    private val appContext = context.applicationContext

    val directory: File get() = directoryOf(appContext)

    fun list(): List<Trip> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
            .orEmpty()
            .map { file ->
                Trip(
                    file = file,
                    startedAtMillis = startedAtOf(file),
                    sizeBytes = file.length(),
                    durationSeconds = durationOf(file),
                )
            }
            .sortedByDescending(Trip::startedAtMillis)

    fun delete(trip: Trip): Boolean = trip.file.delete()

    fun shareIntent(trip: Trip): Intent {
        val uri = FileProvider.getUriForFile(appContext, authority(appContext), trip.file)
        return Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, trip.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * When the recording started, taken from the name [TripRecorder] gave it — the file's
     * modification time is when it *stopped*, which is not what the list should say.
     */
    private fun startedAtOf(file: File): Long {
        val stamp = file.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        return runCatching {
            LocalDateTime.parse(stamp, NAME_FORMAT)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrElse { file.lastModified() }
    }

    /** Reads the `elapsed_s` value of the last row without loading the whole file. */
    private fun durationOf(file: File): Double =
        lastLine(file)?.let(CsvFormat::elapsedOf) ?: 0.0

    private fun lastLine(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { access ->
            var pointer = access.length() - 1
            if (pointer < 0) return null
            // Skip a trailing newline, then walk back to the previous one.
            val bytes = ArrayDeque<Byte>()
            while (pointer >= 0) {
                access.seek(pointer)
                val byte = access.readByte()
                if (byte == NEWLINE) {
                    if (bytes.isNotEmpty()) break
                } else if (byte != CARRIAGE_RETURN) {
                    bytes.addFirst(byte)
                }
                pointer--
            }
            if (bytes.isEmpty()) null else String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }.getOrNull()

    companion object {
        const val FILE_PREFIX = "trip-"
        const val FILE_SUFFIX = ".csv"
        const val MIME_TYPE = "text/csv"
        private const val DIRECTORY_NAME = "trips"
        private const val NEWLINE: Byte = '\n'.code.toByte()
        private const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()

        /** Must stay in step with [TripRecorder]'s file naming. */
        val NAME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

        fun directoryOf(context: Context): File =
            File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

        fun authority(context: Context): String = "${context.packageName}.files"
    }
}
