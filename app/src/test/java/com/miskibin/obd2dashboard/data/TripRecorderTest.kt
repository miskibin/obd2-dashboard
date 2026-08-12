package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.DerivedMetrics
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.Reading
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The recorder is driven on [Dispatchers.Unconfined] so a snapshot published here is
 * written before the next line of the test runs; nothing in it sleeps or races.
 */
class TripRecorderTest {

    private val directory = File.createTempFile("trips-", "").let { probe ->
        probe.delete()
        probe.mkdirs()
        probe.deleteOnExit()
        probe
    }

    private var now = 1_700_000_000_000L

    private fun recorder() = TripRecorder(
        directory = { directory },
        scope = CoroutineScope(Dispatchers.Unconfined),
        // Each read is a second later, so nothing is dropped by the row rate limit.
        clock = { now.also { now += 1_000L } },
        dispatcher = Dispatchers.Unconfined,
    )

    private fun columnsOf(file: File): List<String> =
        CsvFormat.splitRow(file.readLines().first()).drop(2).map { it.substringBefore(" (") }

    private fun recordedFile(): File = directory.listFiles().orEmpty()
        .single { it.name.endsWith(TripRepository.FILE_SUFFIX) }

    @Test
    fun `records everything the car is answering for, not just the seeded columns`() {
        val source = MutableStateFlow(VehicleSnapshot())
        val recorder = recorder()

        // The driver's dashboard is the default six; the car is answering for far more.
        recorder.start(source, listOf(Metrics.Rpm, Metrics.Speed))
        source.value = snapshot(
            Pids.ENGINE_RPM to 900.0,
            Pids.COOLANT_TEMP to 84.0,
            Pids.INTAKE_MAP to 101.0,
        )
        recorder.stop()

        assertEquals(
            listOf("pid:0C", "pid:0D", "pid:05", "pid:0B", "derived:boost"),
            columnsOf(recordedFile()),
        )
    }

    @Test
    fun `a parameter charted mid-recording is recorded from the moment it appears`() {
        val source = MutableStateFlow(VehicleSnapshot())
        val recorder = recorder()

        recorder.start(source, listOf(Metrics.Rpm))
        source.value = snapshot(Pids.ENGINE_RPM to 900.0)
        // Half way through the drive the driver puts oil temperature on the chart, and
        // the scheduler starts polling it.
        source.value = snapshot(Pids.ENGINE_RPM to 2_400.0, Pids.OIL_TEMP to 96.0)
        recorder.stop()

        val file = recordedFile()
        assertEquals(listOf("pid:0C", "pid:5C"), columnsOf(file))
        assertEquals(96.0, TripAnalyzer.analyze(file).maxima.getValue(Metrics.OilTemp), 0.001)
    }

    @Test
    fun `an empty seed is no longer replaced by the default tiles`() {
        val source = MutableStateFlow(VehicleSnapshot())
        val recorder = recorder()

        recorder.start(source)
        source.value = snapshot(Pids.ENGINE_RPM to 900.0)
        recorder.stop()

        val columns = columnsOf(recordedFile())
        assertEquals(listOf("pid:0C"), columns)
        assertFalse(columns.contains(MetricId.Battery.storageKey))
    }

    @Test
    fun `the state carries the file and its row count while it runs`() {
        val source = MutableStateFlow(VehicleSnapshot())
        val recorder = recorder()

        recorder.start(source, listOf(Metrics.Rpm))
        source.value = snapshot(Pids.ENGINE_RPM to 900.0)
        val active = recorder.state.value as RecordingState.Active

        assertTrue(recorder.isRecording)
        assertEquals(1, active.rows)

        recorder.stop()
        assertEquals(RecordingState.Idle, recorder.state.value)
    }

    @Test
    fun `a recording the car never answered during leaves no file`() {
        val recorder = recorder()

        recorder.start(MutableStateFlow(VehicleSnapshot()), listOf(Metrics.Rpm))
        recorder.stop()

        assertTrue(directory.listFiles().orEmpty().toList().toString(), directory.listFiles().orEmpty().isEmpty())
    }

    private fun snapshot(vararg values: Pair<Int, Double>): VehicleSnapshot {
        val readings = values.associate { (pid, value) ->
            pid to Reading(
                key = pid,
                name = Pids[pid]?.name.orEmpty(),
                unit = Pids[pid]?.unit.orEmpty(),
                value = value,
                timestampMillis = now,
            )
        }
        return VehicleSnapshot(
            readings = readings,
            derived = DerivedMetrics.compute(readings.mapValues { it.value.value }),
        )
    }
}
