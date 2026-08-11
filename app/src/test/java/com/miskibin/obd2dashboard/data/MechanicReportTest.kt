package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.Diagnostics
import com.miskibin.obd2dashboard.obd.Dtc
import com.miskibin.obd2dashboard.obd.DtcKind
import com.miskibin.obd2dashboard.obd.FreezeFrame
import com.miskibin.obd2dashboard.obd.MonitorStatus
import com.miskibin.obd2dashboard.obd.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MechanicReportTest {

    private val utc = ZoneId.of("UTC")

    private val full = MechanicReportData(
        appName = "OBD2 Dashboard",
        versionName = "0.1.0",
        generatedAtMillis = GENERATED_AT,
        vin = "WVWZZZ1KZ8W123456",
        diagnostics = Diagnostics(
            stored = listOf(Dtc("P0420", DtcKind.Stored), Dtc("P0301", DtcKind.Stored)),
            pending = listOf(Dtc("P0171", DtcKind.Pending)),
            monitorStatus = MonitorStatus(
                milOn = true,
                dtcCount = 2,
                readinessBytes = listOf(0x07, 0x65, 0x05),
            ),
        ),
        freezeFrame = FreezeFrame(
            triggerCode = "P0420",
            values = mapOf(
                Pids.ENGINE_RPM to 2100.0,
                Pids.VEHICLE_SPEED to 84.0,
                Pids.COOLANT_TEMP to 92.0,
                Pids.SHORT_FUEL_TRIM_1 to 3.9,
            ),
        ),
        batteryVoltage = 14.2,
    )

    private fun lines(language: String, data: MechanicReportData = full): List<String> =
        MechanicReport.build(data, language, utc).lines()

    /** Headings are the only all-caps, letters-only lines the report writes. */
    private fun headingsOf(report: String): List<String> =
        report.lines().filter { HEADING.matches(it) }

    /** Matches a `label   value` line without pinning the exact column width. */
    private fun hasField(report: String, label: String, value: String): Boolean =
        Regex("^${Regex.escape(label)} +${Regex.escape(value)}$", RegexOption.MULTILINE)
            .containsMatchIn(report)

    @Test
    fun `the english report carries every section in order`() {
        val report = MechanicReport.build(full, "en", utc)

        assertEquals(
            listOf(
                "STORED CODES",
                "PENDING CODES",
                "PERMANENT CODES",
                "FREEZE FRAME",
                "READINESS MONITORS",
            ),
            headingsOf(report),
        )
        assertTrue(report.startsWith("OBD2 Dashboard 0.1.0\nVehicle report\n"))
        assertTrue(hasField(report, "Generated", "2025-08-11 12:00"))
        assertTrue(hasField(report, "VIN", "WVWZZZ1KZ8W123456"))
        assertTrue(hasField(report, "Battery", "14.2 V"))
        assertTrue(hasField(report, "Check engine", "ON (2 stored)"))
    }

    @Test
    fun `codes are printed with their descriptions and empty stores say so`() {
        val report = MechanicReport.build(full, "en", utc)

        assertTrue(report.contains("P0420  Catalyst system efficiency below"))
        assertTrue(report.contains("P0301  Cylinder 1 misfire detected"))
        assertTrue(report.contains("P0171  System too lean, bank 1"))
        // A description too long for one line wraps under the code column.
        assertTrue(report.contains("\n         threshold, bank 1"))
        // No permanent codes were read, and the report says that rather than staying blank.
        assertTrue(report.contains("PERMANENT CODES\n  none"))
    }

    @Test
    fun `freeze frame values are printed with their units`() {
        val report = MechanicReport.build(full, "en", utc)

        assertTrue(report.contains("recorded for P0420"))
        assertTrue(report.contains("2100 rpm"))
        assertTrue(report.contains("84 km/h"))
        assertTrue(report.contains("92 °C"))
        assertTrue(report.contains("3.9 %"))
        // Nothing is invented for the parameters the ECU did not store.
        assertFalse(report.contains("Manifold pressure"))
    }

    @Test
    fun `the readiness table names every supported monitor and the verdict`() {
        val report = MechanicReport.build(full, "en", utc)

        assertTrue(report.contains("Misfire"))
        assertTrue(report.contains("Catalyst"))
        assertTrue(report.contains("Evaporative system"))
        assertTrue(report.contains("NOT COMPLETE"))
        assertTrue(report.contains("2 monitor(s) not ready"))
        assertFalse(report.contains("Ready for inspection"))
        // Monitors the car does not run are left out entirely.
        assertFalse(report.contains("Secondary air"))
    }

    @Test
    fun `a car that has finished every test reads as ready`() {
        val ready = full.copy(
            diagnostics = Diagnostics(
                monitorStatus = MonitorStatus(false, 0, listOf(0x07, 0x65, 0x00)),
            ),
            freezeFrame = null,
        )
        val report = MechanicReport.build(ready, "en", utc)

        assertTrue(report.contains("Ready for inspection"))
        assertTrue(hasField(report, "Check engine", "off (0 stored)"))
        assertTrue(report.contains("no freeze frame stored"))
        assertTrue(report.contains("STORED CODES\n  none"))
    }

    @Test
    fun `the polish report has the same shape in polish words`() {
        val polish = MechanicReport.build(full, "pl", utc)

        assertEquals(5, headingsOf(polish).size)
        assertEquals(
            listOf("KODY ZAPISANE", "KODY OCZEKUJĄCE", "KODY TRWAŁE"),
            headingsOf(polish).take(3),
        )
        assertTrue(polish.contains("ZAMROŻONA RAMKA DANYCH"))
        assertTrue(polish.contains("TESTY GOTOWOŚCI"))
        assertTrue(hasField(polish, "Wygenerowano", "2025-08-11 12:00"))
        assertTrue(hasField(polish, "Kontrolka silnika", "ŚWIECI (zapisanych: 2)"))
        assertTrue(polish.contains("Sprawność katalizatora poniżej"))
        assertTrue(polish.contains("Układ par paliwa (EVAP)"))
        assertTrue(polish.contains("Nieukończone testy: 2"))
        assertTrue(polish.contains("zapisana dla P0420"))
        assertFalse(polish.contains("not ready"))
        assertFalse(polish.contains("Catalyst"))
    }

    @Test
    fun `an empty read still produces a readable report`() {
        val report = MechanicReport.build(
            MechanicReportData("OBD2 Dashboard", "0.1.0", GENERATED_AT),
            "en",
            utc,
        )

        assertTrue(hasField(report, "VIN", "not read"))
        assertTrue(hasField(report, "Battery", "not read"))
        assertTrue(hasField(report, "Check engine", "not read"))
        assertTrue(report.contains("READINESS MONITORS\n  not read"))
    }

    @Test
    fun `every line fits the fixed width`() {
        for (language in listOf("en", "pl")) {
            lines(language).forEach {
                assertTrue("$language: \"$it\" is ${it.length} chars", it.length <= MechanicReport.WIDTH)
            }
        }
    }

    private companion object {
        /** 2025-08-11 12:00 UTC. */
        const val GENERATED_AT = 1_754_913_600_000L

        val HEADING = Regex("^[\\p{Lu} /]{4,}$")
    }
}
