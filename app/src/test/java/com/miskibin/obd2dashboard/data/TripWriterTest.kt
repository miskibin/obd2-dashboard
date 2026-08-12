package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.DerivedMetrics
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.Reading
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class TripWriterTest {

    private val start = 1_700_000_000_000L

    @Test
    fun `the header names the seeded columns with their units`() {
        val file = tempFile()
        val writer = TripWriter(file, start)

        writer.open(listOf(Metrics.Rpm, Metrics.Speed, MetricId.Battery))
        writer.append(snapshot(Pids.ENGINE_RPM to 900.0), start)
        writer.close()

        assertEquals(
            listOf("timestamp", "elapsed_s", "pid:0C (rpm)", "pid:0D (km/h)", "battery (V)"),
            CsvFormat.splitRow(file.readLines().first()),
        )
    }

    @Test
    fun `a metric the car only answers for later becomes a column of its own`() {
        val file = tempFile()
        val writer = TripWriter(file, start)

        writer.open(listOf(Metrics.Rpm))
        writer.append(snapshot(Pids.ENGINE_RPM to 900.0), start)
        // The driver adds oil temperature to the chart, so the scheduler starts polling it.
        writer.append(
            snapshot(Pids.ENGINE_RPM to 1_500.0, Pids.OIL_TEMP to 96.0),
            start + 1_000L,
        )
        writer.close()

        val lines = file.readLines()
        assertEquals(
            listOf("timestamp", "elapsed_s", "pid:0C (rpm)", "pid:5C (°C)"),
            CsvFormat.splitRow(lines.first()),
        )
        // The row written before the column existed keeps its own, shorter shape rather
        // than claiming a value it never had.
        assertEquals(listOf("900"), CsvFormat.splitRow(lines[1]).drop(2))
        assertEquals(listOf("1500", "96"), CsvFormat.splitRow(lines[2]).drop(2))
    }

    @Test
    fun `a recording that grew mid-file still reads back as a trip`() {
        val file = tempFile()
        val writer = TripWriter(file, start)

        writer.open(listOf(Metrics.Rpm))
        writer.append(snapshot(Pids.ENGINE_RPM to 900.0), start)
        (1..60).forEach { second ->
            writer.append(
                snapshot(Pids.ENGINE_RPM to 2_000.0, Pids.VEHICLE_SPEED to 60.0),
                start + second * 1_000L,
            )
        }
        writer.close()

        val analysis = TripAnalyzer.analyze(file)
        assertEquals(1.0, analysis.distanceKm ?: 0.0, 0.05)
        assertEquals(2_000.0, analysis.maxima.getValue(Metrics.Rpm), 0.001)
        assertEquals(60.0, analysis.maxima.getValue(Metrics.Speed), 0.001)
    }

    @Test
    fun `derived values and the adapter's battery voltage are columns too`() {
        val file = tempFile()
        val writer = TripWriter(file, start)

        writer.open()
        writer.append(
            VehicleSnapshot(
                readings = emptyMap(),
                derived = mapOf(DerivedMetrics.Boost.key to 42.0),
                batteryVoltage = 13.9,
            ),
            start,
        )
        writer.close()

        assertEquals(
            listOf(MetricId.Derived(DerivedMetrics.Boost.key), MetricId.Battery),
            writer.columnIds,
        )
        assertEquals(
            listOf("timestamp", "elapsed_s", "derived:boost (kPa)", "battery (V)"),
            CsvFormat.splitRow(file.readLines().first()),
        )
    }

    @Test
    fun `with no seed at all the columns are whatever the car answered`() {
        val file = tempFile()
        val writer = TripWriter(file, start)

        writer.open()
        writer.append(snapshot(Pids.COOLANT_TEMP to 88.0), start)
        writer.close()

        assertEquals(listOf(MetricId.Sensor(Pids.COOLANT_TEMP)), writer.columnIds)
        assertEquals(2, file.readLines().size)
    }

    @Test
    fun `a recording nothing was ever written to leaves no file behind`() {
        val file = tempFile()
        val writer = TripWriter(file, start)

        writer.open(listOf(Metrics.Rpm))
        writer.close()

        assertFalse(file.exists())
    }

    @Test
    fun `the rewrite leaves no scratch file next to the recording`() {
        val file = tempFile()
        val writer = TripWriter(file, start)

        writer.open()
        writer.append(snapshot(Pids.ENGINE_RPM to 900.0), start)
        writer.close()

        val left = file.parentFile?.listFiles().orEmpty().map { it.name }
        assertEquals(listOf(file.name), left)
    }

    @Test
    fun `a writer that was never opened writes nothing`() {
        val file = tempFile()
        val writer = TripWriter(file, start)

        writer.append(snapshot(Pids.ENGINE_RPM to 900.0), start)

        assertEquals(0, writer.rows)
        assertFalse(writer.isOpen)
        assertFalse(file.exists())
    }

    private fun tempFile(): File {
        val directory = File.createTempFile("trips-", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe
        }
        directory.deleteOnExit()
        return File(directory, "trip-20231114-221320.csv").apply { deleteOnExit() }
    }

    private fun snapshot(vararg values: Pair<Int, Double>): VehicleSnapshot = VehicleSnapshot(
        readings = values.associate { (pid, value) ->
            pid to Reading(
                key = pid,
                name = Pids[pid]?.name.orEmpty(),
                unit = Pids[pid]?.unit.orEmpty(),
                value = value,
                timestampMillis = start,
            )
        },
    )
}
