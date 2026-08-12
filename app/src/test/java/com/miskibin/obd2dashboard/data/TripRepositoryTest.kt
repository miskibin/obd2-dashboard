package com.miskibin.obd2dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The simulation records drives that never happened. They are useful to look at while the
 * app is pretending, and are a lie the moment it is plugged into a car, so the listing is
 * asked which car it is listing for.
 */
class TripRepositoryTest {

    private val root = File.createTempFile("trips-", "").let { probe ->
        probe.delete()
        probe.mkdirs()
        probe.deleteOnExit()
        probe
    }

    private val demoRoot = File(root, "demo").apply { mkdirs() }

    private val repository = TripRepository { kind -> if (kind.demo) demoRoot else root }

    private fun write(directory: File, stamp: String, elapsed: Double = 12.5): File =
        File(directory, "${TripRepository.FILE_PREFIX}$stamp${TripRepository.FILE_SUFFIX}").apply {
            writeText(
                CsvFormat.header(listOf(CsvColumn("pid:0C", "rpm"))) + "\n" +
                    "2024-01-01T10:00:00.000,$elapsed,900\n",
            )
        }

    @Test
    fun `a real listing never reaches into the demo directory`() {
        write(root, "20240101-100000")
        write(demoRoot, "20240102-100000")

        val trips = repository.list(SessionKind.Real)

        assertEquals(listOf("trip-20240101-100000.csv"), trips.map(Trip::name))
        assertTrue(trips.all { it.kind == SessionKind.Real })
    }

    @Test
    fun `demo mode sees the simulated recordings alongside the real ones`() {
        write(root, "20240101-100000")
        write(demoRoot, "20240102-100000")

        val trips = repository.list(SessionKind.Demo)

        // Newest first, and each one says which car it came from.
        assertEquals(
            listOf("trip-20240102-100000.csv" to SessionKind.Demo, "trip-20240101-100000.csv" to SessionKind.Real),
            trips.map { it.name to it.kind },
        )
    }

    @Test
    fun `the demo directory is not itself mistaken for a recording`() {
        write(root, "20240101-100000")

        // The demo directory lives inside the real one, so a listing that only filtered by
        // name would pick it up.
        assertFalse(repository.list(SessionKind.Demo).any { it.file.isDirectory })
        assertEquals(1, repository.list(SessionKind.Real).size)
    }

    @Test
    fun `a recording still carries its start time and length whichever car made it`() {
        write(demoRoot, "20240102-101500", elapsed = 42.0)

        val trip = repository.list(SessionKind.Demo).single()

        assertEquals(42.0, trip.durationSeconds, 0.001)
        assertTrue(trip.sizeBytes > 0)
        assertTrue(trip.startedAtMillis > 0)
    }

    @Test
    fun `the real directory is where it always was, so nothing has to be migrated`() {
        // Old installs wrote straight into files/trips, and a CSV carries no record of
        // which car it came from — so those files stay exactly where they are and stay
        // listed as real drives. Only the demo directory is new.
        val files = File(root, "files")

        val real = TripRepository.directoryIn(files, SessionKind.Real)
        val demo = TripRepository.directoryIn(files, SessionKind.Demo)

        assertEquals(File(files, "trips"), real)
        assertEquals(File(real, "demo"), demo)
        assertTrue(demo.isDirectory)
    }
}
