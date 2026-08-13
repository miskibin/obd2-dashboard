package com.miskibin.obd2dashboard.data

import android.content.ClipData
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
    /** Which car it was recorded from; a [SessionKind.Demo] trip was never driven. */
    val kind: SessionKind = SessionKind.Real,
) {
    val name: String get() = file.name
}

/**
 * Lists, shares and deletes the CSV recordings written by [TripRecorder].
 *
 * Real drives and the simulation get a directory each, so a demo recording can never be
 * mistaken for a drive that happened: [list] is asked which car the app is talking to and
 * only reaches into the demo directory while it is the simulation. Nothing is hidden from
 * demo mode itself — a demo recording is still there, listed and openable, for as long as
 * the driver is in the mode that made it.
 *
 * Files live in app-private storage and are handed to other apps through a
 * [FileProvider] grant, so no storage permission is ever needed.
 */
class TripRepository(private val directoryOf: (SessionKind) -> File) {

    constructor(context: Context) : this(directoriesOf(context.applicationContext))

    fun directory(kind: SessionKind): File = directoryOf(kind)

    /**
     * The recordings a driver in [kind] should see.
     *
     * Real recordings are always listed — they are drives that happened, and demo mode is
     * no reason to pretend otherwise. Demo recordings are listed only from inside demo
     * mode.
     */
    fun list(kind: SessionKind = SessionKind.Real): List<Trip> {
        val kinds = if (kind.demo) listOf(SessionKind.Real, SessionKind.Demo) else listOf(SessionKind.Real)
        return kinds
            .flatMap { source -> filesIn(source).map { file -> tripOf(file, source) } }
            .sortedByDescending(Trip::startedAtMillis)
    }

    fun delete(trip: Trip): Boolean = trip.file.delete()

    /**
     * The CSV as a send intent — for the share sheet when [target] is null, or straight
     * into one assistant's app when it is not.
     *
     * A targeted send uses the MIME type the assistant's own filter was seen to accept,
     * because the type is what resolution matches on: `text/csv` at an app that only
     * declared `text/plain` is not a lenient delivery but an [android.content.ActivityNotFoundException].
     * The `ClipData` mirror of the stream is what the permission grant rides on for
     * receivers that read the clip rather than the extra.
     */
    fun shareIntent(
        context: Context,
        trip: Trip,
        target: AiAssistants.Assistant? = null,
        text: String? = null,
    ): Intent {
        val appContext = context.applicationContext
        val uri = FileProvider.getUriForFile(appContext, authority(appContext), trip.file)
        return Intent(Intent.ACTION_SEND).apply {
            type = target?.sendType ?: MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, trip.name)
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
            if (target != null) setPackage(target.packageName)
            clipData = ClipData.newRawUri(trip.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** `isFile` is what keeps the demo sub-directory out of the real listing. */
    private fun filesIn(kind: SessionKind): List<File> =
        directoryOf(kind).listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
            .orEmpty()
            .toList()

    private fun tripOf(file: File, kind: SessionKind) = Trip(
        file = file,
        startedAtMillis = startedAtOf(file),
        sizeBytes = file.length(),
        durationSeconds = durationOf(file),
        kind = kind,
    )

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

        /**
         * A sub-directory rather than a sibling, so the recordings that were already
         * there stay exactly where they are.
         *
         * Nothing is migrated on upgrade: a CSV written before this existed carries no
         * record of which car it came from, and guessing — by VIN, by name, by anything —
         * would quietly relabel real drives. Old recordings therefore all count as real,
         * which is what they were unless the driver was playing with the simulation, and
         * only new demo recordings are filed apart.
         */
        private const val DEMO_DIRECTORY_NAME = "demo"

        private const val NEWLINE: Byte = '\n'.code.toByte()
        private const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()

        /** Must stay in step with [TripRecorder]'s file naming. */
        val NAME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

        fun directoryOf(context: Context, kind: SessionKind = SessionKind.Real): File =
            directoryIn(context.filesDir, kind)

        /**
         * Where each kind's recordings live under a given files directory.
         *
         * Split out from [directoryOf] so the layout — and above all the fact that the
         * real directory is still the one old installs wrote to — can be checked without
         * an Android context.
         */
        fun directoryIn(filesDir: File, kind: SessionKind): File {
            val real = File(filesDir, DIRECTORY_NAME)
            val target = if (kind.demo) File(real, DEMO_DIRECTORY_NAME) else real
            return target.apply { mkdirs() }
        }

        /** Held as a lambda over the application context so no screen's context leaks in. */
        fun directoriesOf(appContext: Context): (SessionKind) -> File =
            { kind -> directoryOf(appContext, kind) }

        fun authority(context: Context): String = "${context.packageName}.files"
    }
}
