package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.Diagnostics
import com.miskibin.obd2dashboard.obd.Dtc
import com.miskibin.obd2dashboard.obd.FreezeFrame
import com.miskibin.obd2dashboard.obd.FreezeFrames
import com.miskibin.obd2dashboard.obd.Monitor
import com.miskibin.obd2dashboard.obd.MonitorState
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.Readiness
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything the report prints, gathered before any formatting happens. */
data class MechanicReportData(
    val appName: String,
    val versionName: String,
    val generatedAtMillis: Long,
    val vin: String? = null,
    val diagnostics: Diagnostics? = null,
    val freezeFrame: FreezeFrame? = null,
    val batteryVoltage: Double? = null,
)

/**
 * Renders what the app read off a car as plain text a mechanic can be sent.
 *
 * Plain text on purpose: it survives being pasted into a messenger, an e-mail or a
 * garage's job sheet, which no PDF or screenshot does. The layout is fixed-width with a
 * single label column, so it still lines up in a monospaced chat window and degrades to
 * something readable in a proportional one.
 */
object MechanicReport {

    fun build(
        data: MechanicReportData,
        language: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine("${data.appName} ${data.versionName}".trim())
        appendLine(TITLE.forLanguage(language))
        appendLine(RULE)

        field(GENERATED, language, timestamp(data.generatedAtMillis, zone))
        field(VIN, language, data.vin ?: UNKNOWN.forLanguage(language))
        field(BATTERY, language, data.batteryVoltage?.let { VOLTS.format(Locale.ROOT, it) }
            ?: UNKNOWN.forLanguage(language))
        field(LAMP, language, lampState(data.diagnostics, language))

        codes(DTC_STORED, data.diagnostics?.stored.orEmpty(), language)
        codes(DTC_PENDING, data.diagnostics?.pending.orEmpty(), language)
        codes(DTC_PERMANENT, data.diagnostics?.permanent.orEmpty(), language)
        freezeFrame(data.freezeFrame, language)
        readiness(
            readiness = data.diagnostics?.monitorStatus?.readiness,
            // Same rule as the screen: "ready for inspection" is a claim about the whole
            // car, and a report is read by somebody who cannot see the lamp.
            blocked = data.diagnostics?.monitorStatus?.milOn == true ||
                data.diagnostics?.stored?.isNotEmpty() == true,
            language = language,
        )

        appendLine()
        appendLine(RULE)
        appendLine(FOOTER.forLanguage(language).format(data.appName))
    }

    // ---- sections ---------------------------------------------------------------

    private fun StringBuilder.codes(
        heading: LocalizedText,
        codes: List<Dtc>,
        language: String,
    ) {
        section(heading, language)
        if (codes.isEmpty()) {
            appendLine(INDENT + NONE.forLanguage(language))
            return
        }
        for (dtc in codes) {
            val description = DtcDescriptions.describe(dtc.code).forLanguage(language)
            val lines = wrap(description, WIDTH - DESCRIPTION_COLUMN)
            appendLine(INDENT + dtc.code.padEnd(DESCRIPTION_COLUMN - INDENT.length) + lines.first())
            lines.drop(1).forEach { appendLine(" ".repeat(DESCRIPTION_COLUMN) + it) }
        }
    }

    private fun StringBuilder.freezeFrame(frame: FreezeFrame?, language: String) {
        section(FREEZE_FRAME, language)
        if (frame == null || frame.isEmpty) {
            appendLine(INDENT + NO_FREEZE_FRAME.forLanguage(language))
            return
        }
        frame.triggerCode?.let {
            appendLine(INDENT + FROZEN_BY.forLanguage(language).format(it))
        }
        for (pid in FreezeFrames.pids) {
            val value = frame.values[pid.id] ?: continue
            val name = FREEZE_FRAME_LABELS[pid.id]?.forLanguage(language) ?: pid.name
            row(name, "${format(value, pid.unit)} ${pid.unit}".trim())
        }
    }

    private fun StringBuilder.readiness(readiness: Readiness?, blocked: Boolean, language: String) {
        section(READINESS, language)
        if (readiness == null) {
            appendLine(INDENT + UNKNOWN.forLanguage(language))
            return
        }
        for (monitor in readiness.supported) {
            row(MonitorNames[monitor.id].forLanguage(language), stateText(monitor, language))
        }
        if (readiness.supported.isEmpty()) appendLine(INDENT + NONE.forLanguage(language))
        appendLine()
        appendLine(
            INDENT + when {
                !readiness.ready ->
                    NOT_READY.forLanguage(language).format(readiness.incomplete.size)

                blocked -> BLOCKED.forLanguage(language)
                else -> READY.forLanguage(language)
            },
        )
    }

    // ---- layout -----------------------------------------------------------------

    private fun StringBuilder.section(heading: LocalizedText, language: String) {
        appendLine()
        appendLine(heading.forLanguage(language).uppercase(Locale.ROOT))
    }

    private fun StringBuilder.field(label: LocalizedText, language: String, value: String) {
        appendLine(label.forLanguage(language).padEnd(LABEL_COLUMN) + value)
    }

    /** A `  name ........ value` line, right-aligned so the numbers form a column. */
    private fun StringBuilder.row(name: String, value: String) {
        val room = (WIDTH - value.length - INDENT.length - 1).coerceAtLeast(0)
        val label = INDENT + name.take(room)
        appendLine(label + value.padStart((WIDTH - label.length).coerceAtLeast(1)))
    }

    private fun stateText(monitor: Monitor, language: String): String = when (monitor.state) {
        MonitorState.Complete -> COMPLETE.forLanguage(language)
        MonitorState.Incomplete -> INCOMPLETE.forLanguage(language)
        MonitorState.NotSupported -> NOT_SUPPORTED.forLanguage(language)
    }

    private fun lampState(diagnostics: Diagnostics?, language: String): String {
        val status = diagnostics?.monitorStatus ?: return UNKNOWN.forLanguage(language)
        val state = if (status.milOn) LAMP_ON else LAMP_OFF
        return state.forLanguage(language).format(status.dtcCount)
    }

    private fun timestamp(millis: Long, zone: ZoneId): String =
        TIMESTAMP.format(Instant.ofEpochMilli(millis).atZone(zone))

    private fun format(value: Double, unit: String): String =
        "%.${decimalsFor(unit)}f".format(Locale.ROOT, value)

    private fun decimalsFor(unit: String): Int = if (unit == "%") 1 else 0

    /** Greedy word wrap; a word longer than [width] simply overhangs rather than breaks. */
    private fun wrap(text: String, width: Int): List<String> {
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(' ')) {
            when {
                line.isEmpty() -> line.append(word)
                line.length + 1 + word.length <= width -> line.append(' ').append(word)
                else -> {
                    lines += line.toString()
                    line = StringBuilder(word)
                }
            }
        }
        lines += line.toString()
        return lines
    }

    // ---- wording ----------------------------------------------------------------

    private val TITLE = LocalizedText("Vehicle report", "Raport z pojazdu")
    private val GENERATED = LocalizedText("Generated", "Wygenerowano")
    private val VIN = LocalizedText("VIN", "VIN")
    /** `ATRV`: the adapter's reading at the socket, not a battery terminal measurement. */
    private val BATTERY = LocalizedText("Voltage (OBD)", "Napięcie (OBD)")
    private val LAMP = LocalizedText("Check engine", "Kontrolka silnika")
    private val LAMP_ON = LocalizedText("ON (%1\$d stored)", "ŚWIECI (zapisanych: %1\$d)")
    private val LAMP_OFF = LocalizedText("off (%1\$d stored)", "nie świeci (zapisanych: %1\$d)")
    private val UNKNOWN = LocalizedText("not read", "nie odczytano")
    private val NONE = LocalizedText("none", "brak")

    private val DTC_STORED = LocalizedText("Stored codes", "Kody zapisane")
    private val DTC_PENDING = LocalizedText("Pending codes", "Kody oczekujące")
    private val DTC_PERMANENT = LocalizedText("Permanent codes", "Kody trwałe")

    private val FREEZE_FRAME = LocalizedText("Freeze frame", "Zamrożona ramka danych")
    private val FROZEN_BY = LocalizedText("recorded for %1\$s", "zapisana dla %1\$s")
    private val NO_FREEZE_FRAME =
        LocalizedText("no freeze frame stored", "brak zapisanej ramki danych")

    private val READINESS = LocalizedText("Readiness monitors", "Testy gotowości")
    private val COMPLETE = LocalizedText("complete", "ukończony")
    private val INCOMPLETE = LocalizedText("NOT COMPLETE", "NIEUKOŃCZONY")
    private val NOT_SUPPORTED = LocalizedText("not supported", "nieobsługiwany")
    private val READY = LocalizedText("Ready for inspection", "Gotowy do badania")

    /** Every self-test has run, but the lamp or a stored code fails the car anyway. */
    private val BLOCKED = LocalizedText(
        "Self-tests complete, but a code or the lamp fails it",
        "Testy ukończone, ale kod lub kontrolka przesądzają o wyniku",
    )
    private val NOT_READY = LocalizedText(
        "%1\$d monitor(s) not ready",
        "Nieukończone testy: %1\$d",
    )

    private val FOOTER = LocalizedText(
        "Read over OBD-II with %1\$s.",
        "Odczyt przez OBD-II: %1\$s.",
    )

    /** Short labels for the freeze-frame parameters; the PID names are English only. */
    private val FREEZE_FRAME_LABELS: Map<Int, LocalizedText> = mapOf(
        Pids.ENGINE_RPM to LocalizedText("Engine RPM", "Obroty silnika"),
        Pids.VEHICLE_SPEED to LocalizedText("Vehicle speed", "Prędkość"),
        Pids.ENGINE_LOAD to LocalizedText("Calculated load", "Obliczone obciążenie"),
        Pids.COOLANT_TEMP to LocalizedText("Coolant temperature", "Temperatura płynu"),
        Pids.INTAKE_MAP to LocalizedText("Manifold pressure", "Ciśnienie w kolektorze"),
        Pids.INTAKE_AIR_TEMP to LocalizedText("Intake air temperature", "Temperatura dolotu"),
        Pids.SHORT_FUEL_TRIM_1 to LocalizedText("Short fuel trim b1", "Korekta krótka rz.1"),
        Pids.LONG_FUEL_TRIM_1 to LocalizedText("Long fuel trim b1", "Korekta długa rz.1"),
    )

    const val WIDTH = 44
    private const val LABEL_COLUMN = 18
    private const val DESCRIPTION_COLUMN = 9
    private const val INDENT = "  "
    private const val VOLTS = "%.1f V"
    private val RULE = "=".repeat(WIDTH)
    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
}
