package com.miskibin.obd2dashboard.obd

/**
 * The Mode 02 snapshot the ECU stored at the instant it set a code.
 *
 * [triggerCode] is PID 02 (DTCFRZF), the code that caused the frame; [values] holds the
 * decoded Mode 01 parameters, keyed by PID id. A car with nothing stored answers
 * `NO DATA` to every request, which lands here as an [isEmpty] frame.
 */
data class FreezeFrame(
    val triggerCode: String? = null,
    val values: Map<Int, Double> = emptyMap(),
) {
    val isEmpty: Boolean get() = triggerCode == null && values.isEmpty()
}

object FreezeFrames {

    /** PID 02 of Mode 02: the DTC that caused the frame, encoded like any other code. */
    const val DTC_PID = 0x02

    /** Frame 00 — the only one a generic tool can count on being there. */
    const val FIRST_FRAME = 0x00

    /**
     * The parameters a mechanic reads first, in the order the report prints them: what
     * the engine was doing, how hot it was, and how far the mixture had been corrected.
     */
    val pids: List<Pid> = listOfNotNull(
        Pids[Pids.ENGINE_RPM],
        Pids[Pids.VEHICLE_SPEED],
        Pids[Pids.ENGINE_LOAD],
        Pids[Pids.COOLANT_TEMP],
        Pids[Pids.INTAKE_MAP],
        Pids[Pids.INTAKE_AIR_TEMP],
        Pids[Pids.SHORT_FUEL_TRIM_1],
        Pids[Pids.LONG_FUEL_TRIM_1],
    )
}
