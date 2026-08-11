package com.miskibin.obd2dashboard.data

import android.content.Context
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Active(val file: File, val startedAtMillis: Long, val rows: Int) : RecordingState
}

/**
 * Streams every polled value to a CSV file while the driver holds the record button on.
 *
 * The column set is fixed when recording starts — from the PIDs the car reported as
 * supported — so the file stays a rectangle even though individual PIDs come and go
 * between polling cycles.
 */
class TripRecorder(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var job: Job? = null

    val isRecording: Boolean get() = _state.value is RecordingState.Active

    fun start(source: Flow<VehicleSnapshot>, metrics: List<MetricId>) {
        if (isRecording) return
        val columns = metrics.distinct().ifEmpty { Metrics.defaultTiles }
        val startedAt = clock()
        val file = File(TripRepository.directoryOf(appContext), fileNameFor(startedAt))
        job = scope.launch(Dispatchers.IO) { record(source, columns, file, startedAt) }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = RecordingState.Idle
    }

    private suspend fun record(
        source: Flow<VehicleSnapshot>,
        columns: List<MetricId>,
        file: File,
        startedAtMillis: Long,
    ) {
        file.parentFile?.mkdirs()
        val csvColumns = columns.map { CsvColumn(it.storageKey, Metrics[it]?.unit.orEmpty()) }
        var rows = 0
        var lastWriteMillis = 0L
        var writer: BufferedWriter? = null
        try {
            writer = file.bufferedWriter().also {
                it.appendLine(CsvFormat.header(csvColumns))
                it.flush()
            }
            _state.value = RecordingState.Active(file, startedAtMillis, rows = 0)
            source.collect { snapshot ->
                val now = clock()
                if (now - lastWriteMillis < MIN_ROW_INTERVAL_MILLIS) return@collect
                lastWriteMillis = now
                writer.appendLine(
                    CsvFormat.row(now, startedAtMillis, columns.map(snapshot::valueOf)),
                )
                rows++
                if (rows % FLUSH_EVERY_ROWS == 0) writer.flush()
                _state.value = RecordingState.Active(file, startedAtMillis, rows)
            }
        } finally {
            withContext(Dispatchers.IO) {
                runCatching {
                    writer?.flush()
                    writer?.close()
                }
                if (rows == 0) file.delete()
            }
        }
    }

    private companion object {
        const val MIN_ROW_INTERVAL_MILLIS = 250L
        const val FLUSH_EVERY_ROWS = 20

        val FILE_NAME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

        fun fileNameFor(startedAtMillis: Long): String {
            val stamp = FILE_NAME_FORMAT.format(
                Instant.ofEpochMilli(startedAtMillis).atZone(ZoneId.systemDefault()),
            )
            return "${TripRepository.FILE_PREFIX}$stamp${TripRepository.FILE_SUFFIX}"
        }
    }
}
