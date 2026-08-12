package com.miskibin.obd2dashboard.data

import android.content.Context
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Active(val file: File, val startedAtMillis: Long, val rows: Int) : RecordingState
}

/**
 * Streams every polled value to a CSV file while the driver holds the record button on.
 *
 * The recording follows the car rather than a list decided in advance: [TripWriter] takes
 * its columns from what each snapshot actually carries, so a parameter added to the chart
 * mid-drive, or one the car only starts answering for once it is warm, lands in the file
 * from the moment it appears. The [start] metrics are only a seed — they fix the order of
 * the columns the app already expects and put them in the header from the first row.
 */
class TripRecorder(
    private val directory: () -> File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where the file is written; only ever anything but [Dispatchers.IO] under test. */
    private val dispatcher: CoroutineContext = Dispatchers.IO,
) {

    constructor(
        context: Context,
        scope: CoroutineScope,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(directoryOf(context.applicationContext), scope, clock)

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var job: Job? = null

    val isRecording: Boolean get() = _state.value is RecordingState.Active

    fun start(source: Flow<VehicleSnapshot>, metrics: List<MetricId> = emptyList()) {
        if (isRecording) return
        val seed = metrics.distinct()
        val startedAt = clock()
        val file = File(directory(), fileNameFor(startedAt))
        // Published before the writer coroutine gets scheduled so a second tap on the
        // record button cannot start a second file.
        _state.value = RecordingState.Active(file, startedAt, rows = 0)
        job = scope.launch(dispatcher) { record(source, seed, file, startedAt) }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = RecordingState.Idle
    }

    private suspend fun record(
        source: Flow<VehicleSnapshot>,
        seed: List<MetricId>,
        file: File,
        startedAtMillis: Long,
    ) {
        val writer = TripWriter(file, startedAtMillis)
        var lastWriteMillis = 0L
        try {
            writer.open(seed)
            source.collect { snapshot ->
                // Every session opens on an empty snapshot, and a row carrying nothing but
                // a timestamp is not a sample of anything.
                if (snapshot.presentMetrics().isEmpty()) return@collect
                val now = clock()
                if (now - lastWriteMillis < MIN_ROW_INTERVAL_MILLIS) return@collect
                lastWriteMillis = now
                writer.append(snapshot, now)
                _state.value = RecordingState.Active(file, startedAtMillis, writer.rows)
            }
        } finally {
            // Stopping cancels this coroutine, so closing the file has to survive
            // cancellation or the last buffered rows — and the final header — would be lost.
            withContext(NonCancellable) { runCatching { writer.close() } }
        }
    }

    private companion object {
        const val MIN_ROW_INTERVAL_MILLIS = 250L

        /** Held as a lambda over the application context so no screen's context leaks in. */
        fun directoryOf(appContext: Context): () -> File = { TripRepository.directoryOf(appContext) }

        fun fileNameFor(startedAtMillis: Long): String {
            val stamp = TripRepository.NAME_FORMAT.format(
                Instant.ofEpochMilli(startedAtMillis).atZone(ZoneId.systemDefault()),
            )
            return "${TripRepository.FILE_PREFIX}$stamp${TripRepository.FILE_SUFFIX}"
        }
    }
}
