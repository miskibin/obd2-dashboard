package com.miskibin.obd2dashboard.obd

/**
 * How much of a claim the app is making by putting a number on the screen.
 *
 * The dashboard used to draw a reading off the bus and a figure the app worked out for
 * itself in exactly the same type, in the same place, with the same unit beside it. Both
 * are worth showing; they are not worth showing as the same kind of fact. A coolant
 * temperature is what a sensor said. A fuel consumption on a car without PID 5E is a
 * quotient of an air flow, a fuel density and a mixture, any of which the app may have had
 * to supply itself.
 *
 * So every value carries which of these it is, and the UI is required to say so wherever
 * the value appears — on the tile, in the sheet, in the export.
 */
enum class Provenance {
    /** A sensor reading, decoded and shown as the car sent it. */
    Measured,

    /** Arithmetic on measured values, all of which the car actually reported. */
    Derived,

    /** Arithmetic that only closed because the app supplied a constant; see [Assumption]. */
    Assumed,
    ;

    /** Whether the number needs the estimate mark next to it. */
    val estimated: Boolean get() = this != Measured
}

/**
 * A constant the app had to put in because the car would not.
 *
 * Each one is a specific, nameable substitution with a known direction of error, which is
 * what makes it printable: "estimated" on its own tells a driver to distrust the number
 * without telling them why or by how much.
 */
enum class Assumption {
    /** No barometric pressure (PID 33), so boost is measured against 101.3 kPa. */
    SeaLevelPressure,

    /** No fuel type in the profile, so the fuel maths ran as if the tank held petrol. */
    FuelTypeUnset,

    /** Neither a wide-range probe nor a commanded ratio, so a nominal mixture was used. */
    NominalLambda,
}

/**
 * One value the app worked out, with everything needed to be honest about it.
 *
 * [timestampMillis] is the age of the *oldest* reading it was computed from, not the moment
 * the arithmetic ran. That distinction is the whole point: the derived values used to be
 * recomputed on every publish out of the whole reading map, so a boost pressure worked out
 * from a manifold pressure that had stopped answering a minute ago was restamped as fresh
 * on the next engine-speed frame and never dimmed.
 */
data class DerivedValue(
    val value: Double,
    val provenance: Provenance,
    val assumption: Assumption? = null,
    val timestampMillis: Long = 0,
)
