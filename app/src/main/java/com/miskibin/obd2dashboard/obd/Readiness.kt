package com.miskibin.obd2dashboard.obd

/** Which set of non-continuous monitors `0101` bytes C and D describe (bit B3). */
enum class IgnitionType { Spark, Compression }

enum class MonitorState { NotSupported, Incomplete, Complete }

/** Every readiness monitor SAE J1979 defines, across both ignition types. */
enum class MonitorId {
    // Continuous, reported by byte B on every vehicle.
    Misfire,
    FuelSystem,
    Components,

    // Non-continuous, spark ignition (petrol).
    Catalyst,
    HeatedCatalyst,
    EvaporativeSystem,
    SecondaryAirSystem,
    AcRefrigerant,
    OxygenSensor,
    OxygenSensorHeater,
    EgrSystem,

    // Non-continuous, compression ignition (diesel).
    NmhcCatalyst,
    NoxAftertreatment,
    BoostPressure,
    ExhaustGasSensor,
    ParticulateFilter,
    EgrVvtSystem,
}

data class Monitor(val id: MonitorId, val state: MonitorState)

/**
 * What an inspection station reads off the car: which self-tests this ECU runs and which
 * of them have finished since the codes were last cleared.
 */
data class Readiness(val ignition: IgnitionType, val monitors: List<Monitor>) {

    val supported: List<Monitor> get() = monitors.filter { it.state != MonitorState.NotSupported }

    val incomplete: List<Monitor> get() = monitors.filter { it.state == MonitorState.Incomplete }

    /** A car passes the readiness part of an emissions test only when nothing is pending. */
    val ready: Boolean get() = incomplete.isEmpty()
}

/**
 * Decodes bytes B, C and D of `0101`.
 *
 * Byte B carries the three continuous monitors twice over: bits 0-2 say whether each is
 * supported, bits 4-6 whether it is still *incomplete*. Bit 3 picks how C and D are read.
 * C is then the supported mask of the non-continuous monitors and D their incomplete mask
 * — a set bit in D means "not finished yet", which is the opposite of the intuition.
 */
object ReadinessDecoder {

    private val CONTINUOUS = listOf(MonitorId.Misfire, MonitorId.FuelSystem, MonitorId.Components)

    private val SPARK = listOf(
        MonitorId.Catalyst,
        MonitorId.HeatedCatalyst,
        MonitorId.EvaporativeSystem,
        MonitorId.SecondaryAirSystem,
        MonitorId.AcRefrigerant,
        MonitorId.OxygenSensor,
        MonitorId.OxygenSensorHeater,
        MonitorId.EgrSystem,
    )

    /** Bits 2 and 4 are reserved on compression ignition, hence the holes. */
    private val COMPRESSION = listOf(
        MonitorId.NmhcCatalyst,
        MonitorId.NoxAftertreatment,
        null,
        MonitorId.BoostPressure,
        null,
        MonitorId.ExhaustGasSensor,
        MonitorId.ParticulateFilter,
        MonitorId.EgrVvtSystem,
    )

    /** [bytes] are the B, C, D bytes of `0101`; null when the ECU sent fewer than three. */
    fun parse(bytes: List<Int>): Readiness? {
        if (bytes.size < BYTES_NEEDED) return null
        val (b, c, d) = bytes
        val ignition =
            if (b and COMPRESSION_BIT != 0) IgnitionType.Compression else IgnitionType.Spark

        val monitors = buildList {
            CONTINUOUS.forEachIndexed { bit, id ->
                add(Monitor(id, stateOf(b.bit(bit), b.bit(bit + INCOMPLETE_SHIFT))))
            }
            val nonContinuous = if (ignition == IgnitionType.Spark) SPARK else COMPRESSION
            nonContinuous.forEachIndexed { bit, id ->
                if (id != null) add(Monitor(id, stateOf(c.bit(bit), d.bit(bit))))
            }
        }
        return Readiness(ignition, monitors)
    }

    private fun stateOf(supported: Boolean, incomplete: Boolean): MonitorState = when {
        !supported -> MonitorState.NotSupported
        incomplete -> MonitorState.Incomplete
        else -> MonitorState.Complete
    }

    private fun Int.bit(index: Int): Boolean = (this shr index) and 1 == 1

    private const val BYTES_NEEDED = 3
    private const val COMPRESSION_BIT = 0x08
    private const val INCOMPLETE_SHIFT = 4
}
