package com.miskibin.obd2dashboard.obd

/**
 * What a Mode 06 result is about, in the terms the screen speaks.
 *
 * Mode 06 is a flat list of numbered tests, and the numbering is the standard's own: a
 * monitor id, a test id inside it, and a scaling id. Nothing in the response says what any
 * of it means. This is the small part of that space the app claims to understand — the
 * tests that are worth a driver's attention and whose meaning is published — and
 * [MonitorKind.Other] is everything else, which is still shown, still compared against its
 * own limits, and honestly not named.
 */
enum class MonitorKind {
    /** Misfires counted on one cylinder, which is the whole reason to read Mode 06. */
    MisfireCylinder,

    /** Misfires counted across the engine. */
    MisfireGeneral,

    /** Catalyst oxygen storage capacity: how much oxygen the converter can still hold. */
    CatalystStorage,

    /** How long an oxygen sensor takes to swing between rich and lean. */
    OxygenSensorSwitch,

    /** The resistance of an oxygen sensor's heater element. */
    OxygenSensorHeater,

    Other,
}

/**
 * One on-board test result: the ECU's own measurement and the window it has to fall in.
 *
 * The value is only half of it. Mode 06 ships the limits alongside every reading, which is
 * what makes it different from live data: a catalyst storage figure means nothing on its
 * own and means a great deal when it is a tenth above the minimum the ECU will accept.
 *
 * Values are kept raw as well as scaled because the scaling identifier is a table this app
 * only partly holds; a test whose units are unknown is still a number with a pass mark.
 */
data class MonitorTest(
    val mid: Int,
    val tid: Int,
    val uasid: Int,
    val rawValue: Int,
    val rawMin: Int,
    val rawMax: Int,
) {
    val kind: MonitorKind
        get() = when {
            mid in Mode06.MISFIRE_CYLINDER_MIDS -> MonitorKind.MisfireCylinder
            mid == Mode06.MID_MISFIRE_GENERAL -> MonitorKind.MisfireGeneral
            mid in Mode06.CATALYST_MIDS && tid == Mode06.TID_CATALYST_STORAGE ->
                MonitorKind.CatalystStorage

            mid in Mode06.OXYGEN_SENSOR_MIDS && tid in Mode06.SWITCH_TIME_TIDS ->
                MonitorKind.OxygenSensorSwitch

            mid in Mode06.OXYGEN_HEATER_MIDS && tid == Mode06.TID_HEATER_RESISTANCE ->
                MonitorKind.OxygenSensorHeater

            else -> MonitorKind.Other
        }

    /** Which cylinder this counts misfires on, or null when it is not a per-cylinder test. */
    val cylinder: Int?
        get() = if (mid in Mode06.MISFIRE_CYLINDER_MIDS) {
            mid - Mode06.MISFIRE_CYLINDER_MIDS.first + 1
        } else {
            null
        }

    /**
     * Which bank or sensor position the test belongs to, counting from one, or null.
     *
     * Catalyst monitors are numbered per bank and oxygen sensor monitors per sensor, and
     * both are dense ranges, so the offset from the range's start is the number.
     */
    val position: Int?
        get() = when {
            mid in Mode06.CATALYST_MIDS -> mid - Mode06.CATALYST_MIDS.first + 1
            mid in Mode06.OXYGEN_SENSOR_MIDS -> mid - Mode06.OXYGEN_SENSOR_MIDS.first + 1
            mid in Mode06.OXYGEN_HEATER_MIDS -> mid - Mode06.OXYGEN_HEATER_MIDS.first + 1
            else -> null
        }

    /** How the raw counts turn into the unit [unit] names; 1.0 when nothing is known. */
    val scale: Double get() = Mode06.scaleOf(kind)

    val unit: String get() = Mode06.unitOf(kind, uasid)

    val value: Double get() = rawValue * scale
    val min: Double get() = rawMin * scale
    val max: Double get() = rawMax * scale

    /** The standard's own verdict: inside the window the ECU shipped with the reading. */
    val passed: Boolean get() = rawValue in rawMin..rawMax

    /**
     * How far through its allowed window the reading sits, 0 at the minimum and 1 at the
     * maximum, or null when the window has no width.
     *
     * This is what turns a catalyst storage figure into a warning worth acting on: the
     * number itself drifts down over a converter's life, and the point it is worth knowing
     * about is when it approaches the limit the ECU will set P0420 at, not when it crosses
     * it in a car park somewhere.
     */
    val headroom: Double?
        get() {
            val span = (rawMax - rawMin).toDouble()
            if (span <= 0.0) return null
            return (rawValue - rawMin) / span
        }
}

/** Everything one pass of Mode 06 returned, and when it was read. */
data class MonitorTests(
    val tests: List<MonitorTest> = emptyList(),
    val capturedAtMillis: Long = 0,
) {
    val isEmpty: Boolean get() = tests.isEmpty()

    /** The per-cylinder misfire counts, lowest cylinder first, one row per cylinder. */
    val misfires: List<MonitorTest>
        get() = tests.filter { it.kind == MonitorKind.MisfireCylinder }
            .groupBy { it.cylinder }
            // A cylinder reports both the running average and the current cycle; the
            // average is the one that describes the engine rather than the last minute.
            .mapNotNull { (_, rows) ->
                rows.firstOrNull { it.tid == Mode06.TID_MISFIRE_AVERAGE } ?: rows.firstOrNull()
            }
            .sortedBy { it.cylinder }

    val catalyst: List<MonitorTest>
        get() = tests.filter { it.kind == MonitorKind.CatalystStorage }.sortedBy { it.mid }

    val oxygenSensors: List<MonitorTest>
        get() = tests.filter {
            it.kind == MonitorKind.OxygenSensorSwitch || it.kind == MonitorKind.OxygenSensorHeater
        }.sortedWith(compareBy({ it.mid }, { it.tid }))

    val failed: List<MonitorTest> get() = tests.filterNot(MonitorTest::passed)
}

/**
 * Reads Mode 06, the service that carries what the ECU's own self-tests measured.
 *
 * Mode 01 says what the engine is doing; Mode 06 says what the monitors *found* — the
 * misfire counts per cylinder that no live PID exposes, and the catalyst storage figure
 * that goes on falling for months before it sets P0420. It is the only place either
 * number lives.
 *
 * The response is a run of nine-byte records, `MID TID UASID value min max` with each of
 * the last three a sixteen-bit big-endian word, repeated for as many tests as the monitor
 * has. Several records share one response, so the walk is by length rather than by
 * looking for markers.
 */
object Mode06 {

    const val MODE = 0x06

    /** Records are fixed width, which is what lets several share one response. */
    const val RECORD_BYTES = 9

    const val MID_MISFIRE_GENERAL = 0xA1

    /** `A2`-`AD`: cylinders 1 to 12, one monitor id each. */
    val MISFIRE_CYLINDER_MIDS = 0xA2..0xAD

    /** `21`/`22`: catalyst bank 1 and bank 2. */
    val CATALYST_MIDS = 0x21..0x22

    /** `01`-`08`: the eight oxygen sensor positions, in `0113` order. */
    val OXYGEN_SENSOR_MIDS = 0x01..0x08

    /** `41`-`48`: the heater of each of those eight sensors. */
    val OXYGEN_HEATER_MIDS = 0x41..0x48

    /** Misfire counts averaged over the last ten drive cycles. */
    const val TID_MISFIRE_AVERAGE = 0x0B

    /** Misfire counts for the cycle being driven now. */
    const val TID_MISFIRE_CURRENT = 0x0C

    val SWITCH_TIME_TIDS = 0x05..0x06

    const val TID_CATALYST_STORAGE = 0x82

    const val TID_HEATER_RESISTANCE = 0x80

    /** The scaling identifier for a plain count of events, which misfires are reported in. */
    const val UASID_COUNTS = 0x24

    /** The scaling identifier for a raw number with no unit at all. */
    const val UASID_RAW = 0x01

    private const val GRAMS_PER_COUNT = 0.01
    private const val SECONDS_PER_COUNT = 0.001

    /** The request for the bitmask of monitors this ECU implements, and its successors. */
    fun supportRequest(base: Int): String = "%02X%02X".format(MODE, base)

    fun request(mid: Int): String = "%02X%02X".format(MODE, mid)

    /**
     * The factor between a test's raw counts and the unit [unitOf] names.
     *
     * The standard's scaling table has some sixty entries and only a handful of them are
     * published anywhere this app could check, so the scaling is taken from the *test*
     * where the test is one this app knows — the catalyst storage figure is hundredths of
     * a gram, a switch time is milliseconds — and left at one otherwise. An unscaled
     * number next to its own pass limits is still a usable reading; a number multiplied by
     * a guessed factor is not.
     */
    fun scaleOf(kind: MonitorKind): Double = when (kind) {
        MonitorKind.MisfireCylinder, MonitorKind.MisfireGeneral -> 1.0
        MonitorKind.CatalystStorage -> GRAMS_PER_COUNT
        MonitorKind.OxygenSensorSwitch -> SECONDS_PER_COUNT
        // Everything else is left in the counts the ECU sent, whatever [uasid] claims.
        else -> 1.0
    }

    fun unitOf(kind: MonitorKind, uasid: Int): String = when (kind) {
        MonitorKind.MisfireCylinder, MonitorKind.MisfireGeneral -> COUNT
        MonitorKind.CatalystStorage -> GRAM
        MonitorKind.OxygenSensorSwitch -> SECOND
        else -> if (uasid == UASID_COUNTS) COUNT else ""
    }

    /**
     * Every test record in [frames].
     *
     * Records are read from just after the `46` marker to the end of the frame, nine bytes
     * at a time; a trailing part-record is dropped rather than padded, because a truncated
     * ISO-TP reassembly is exactly where a made-up limit would come from.
     */
    fun parse(frames: List<ObdFrame>): List<MonitorTest> {
        val marker = MODE + ObdResponseParser.RESPONSE_OFFSET
        val tests = mutableListOf<MonitorTest>()
        for (frame in frames) {
            val start = frame.data.indexOf(marker)
            if (start < 0) continue
            val payload = frame.data.subList(start + 1, frame.data.size)
            var index = 0
            while (index + RECORD_BYTES <= payload.size) {
                tests += MonitorTest(
                    mid = payload[index],
                    tid = payload[index + 1],
                    uasid = payload[index + 2],
                    rawValue = word(payload, index + 3),
                    rawMin = word(payload, index + 5),
                    rawMax = word(payload, index + 7),
                )
                index += RECORD_BYTES
            }
        }
        return tests
    }

    private fun word(data: List<Int>, at: Int): Int = data[at] * 256 + data[at + 1]

    private const val COUNT = "count"
    private const val GRAM = "g"
    private const val SECOND = "s"
}
