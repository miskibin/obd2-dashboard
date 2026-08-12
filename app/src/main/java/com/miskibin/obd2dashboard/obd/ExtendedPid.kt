package com.miskibin.obd2dashboard.obd

/** ISO 14229 `ReadDataByIdentifier`, the one manufacturer service that only reads. */
const val MODE_READ_DATA_BY_ID = 0x22

/**
 * The car an extended parameter is being considered for.
 *
 * Only what the VIN itself settles, offline: which marque issued it and which model year it
 * carries. That is deliberately little. An extended DID is a manufacturer's private
 * address space and the same number means different things — or nothing — on a different
 * marque, so the gate has to be something the app can be certain of before it sends
 * anything, and the VIN is the only such thing.
 */
data class ExtendedVehicle(val vin: String? = null, val modelYear: Int? = null) {

    /** Mazda's three world manufacturer identifiers. */
    val isMazda: Boolean get() = vin?.take(WMI_LENGTH)?.uppercase() in MAZDA_WMI

    /** Whether the model year is known to be within [range]; false when it is not known. */
    fun builtIn(range: IntRange): Boolean = modelYear?.let { it in range } == true

    private companion object {
        const val WMI_LENGTH = 3
        val MAZDA_WMI = setOf("JM1", "JM7", "JMZ")
    }
}

/**
 * One manufacturer-specific reading, addressed to one module.
 *
 * The four fields at the top are the whole difference from a [Pid]: an extended parameter
 * is not broadcast to every ECU on `7DF`, it is asked of one module at [header], and a
 * module outside the `7Ex` engine range answers on an address the adapter is not listening
 * to unless [receiveHeader] has been set with `ATCRA` first. Get either wrong and the
 * result is `NO DATA` from a car that would have answered perfectly.
 *
 * [decode] is a Kotlin lambda rather than an interpreted formula string. The curated set is
 * small, every entry in it has been checked against a real response, and a formula
 * interpreter would buy nothing but the ability to ship an unchecked one.
 *
 * [id] is the stable identity of the *reading*, not of the request: two generations of the
 * same car report tyre pressure from different modules at different DIDs, and to a driver
 * that is one parameter. They therefore share an id, and [applies] keeps at most one of
 * them live on any given car.
 */
data class ExtendedPid(
    val id: String,
    val header: String,
    val receiveHeader: String? = null,
    val service: Int = MODE_READ_DATA_BY_ID,
    val did: Int,
    val unit: String,
    val decimals: Int,
    val bytes: Int,
    val tier: PidTier,
    /** The shortest gap worth asking again after; tyre pressures move in minutes, not tenths. */
    val minIntervalMillis: Long = 0,
    val applies: (ExtendedVehicle) -> Boolean,
    val decode: (IntArray) -> Double,
) {
    /** The request, e.g. `220415`. */
    val request: String get() = "%02X%04X".format(service, did)

    /**
     * The byte a positive response starts with.
     *
     * Computed rather than written down: every service answers with its own number plus
     * `0x40`, and hard-coding `62` next to a service field that can hold something else is
     * a bug waiting for the first non-`22` entry.
     */
    val responseMarker: Int get() = service + ObdResponseParser.RESPONSE_OFFSET

    /** The two DID bytes that a positive response repeats before the data. */
    val didBytes: IntArray get() = intArrayOf(did shr Byte.SIZE_BITS, did and 0xFF)

    /** True when the adapter has to be told which address to listen on for this module. */
    val needsReceiveFilter: Boolean get() = receiveHeader != null
}

/** What a probe of one extended parameter established. */
enum class ExtendedProbe {
    /** The car answered with data. Worth remembering: it will answer again. */
    Supported,

    /**
     * The car refused in a way that cannot change — the service, the sub-function or the
     * identifier does not exist here. Worth remembering too, so it is never asked again.
     */
    Absent,

    /** Busy, or the conditions were wrong. Nothing learned; ask again later this session. */
    Retry,

    /** Silence. Nothing learned and nothing remembered; ask again on the next connection. */
    Unknown,
}

/**
 * The extended parameters this app will ask for, and the cars it will ask.
 *
 * Every entry here was read off a real car — see `docs/research-mazda-extended-pids.md` —
 * and every one is a read. Nothing in this table writes, changes a session, resets an
 * adaptation or runs a routine, and nothing ever should: an app that polls a running
 * engine has no business sending anything a car cannot ignore.
 */
object ExtendedPids {

    /** Mazda's PCM, which answers on `7E8` like any engine ECU. */
    private const val PCM = "7E0"

    /** The transmission module. */
    private const val TCM = "7E1"

    /** The body module of a 2019-on Mazda 3 (BP), which carries its tyre pressures. */
    private const val BODY_BP = "726"
    private const val BODY_BP_RESPONSE = "72E"

    /** The same job on the 2014-2018 car (BM/BN), at a different address entirely. */
    private const val BODY_BM = "720"
    private const val BODY_BM_RESPONSE = "728"

    const val OIL_PRESSURE = "mazda_oil_pressure"
    const val OIL_TEMPERATURE = "mazda_oil_temperature"
    const val TRANSMISSION_FLUID_TEMPERATURE = "mazda_atf_temperature"

    /** Tyre positions, in the order both generations report them. */
    val WHEELS = listOf("fl", "fr", "rl", "rr")

    fun tyrePressureId(wheel: String) = "mazda_tyre_pressure_$wheel"

    fun tyreTemperatureId(wheel: String) = "mazda_tyre_temperature_$wheel"

    /** The BP generation of the Mazda 3, which is the one with tyre pressures on `726`. */
    val BP_YEARS = 2019..2030

    /** The BM/BN generation before it. */
    val BM_YEARS = 2014..2018

    private const val CELSIUS = "°C"
    private const val KPA = "kPa"
    private const val BAR = "bar"

    /** `0xFFFF` in the oil pressure DID is the sensor saying it has nothing, not 65 535 kPa. */
    private const val INVALID_WORD = 0xFFFF

    private const val OIL_TEMPERATURE_DIVISOR = 100.0
    private const val TEMPERATURE_OFFSET = 40.0

    /**
     * The transmission temperature divisor.
     *
     * Published lists disagree between this and 16, which would put a cold gearbox at
     * 400 °C. The reading is therefore gated to a range a gearbox can actually be in, so a
     * car that turns out to use the other scaling shows nothing rather than something
     * alarming and wrong.
     */
    private const val FLUID_TEMPERATURE_DIVISOR = 80.0
    private val FLUID_TEMPERATURE_RANGE = -40.0..160.0

    /** Tyre pressure counts, both generations, converted to the unit the rest of the app uses. */
    private const val PSI_PER_COUNT = 0.2
    private const val BAR_PER_PSI = 0.0689475729
    private const val BAR_PER_COUNT = 1373.0 / 100_000.0

    /** Tyre temperature is reported with a 50-degree offset rather than the usual 40. */
    private const val TYRE_TEMPERATURE_OFFSET = 50.0

    /** A tyre pressure asked for oftener than this is a request spent on a number that has not moved. */
    private const val TYRE_INTERVAL_MILLIS = 15_000L

    private fun word(data: IntArray) = data[0] * 256 + data[1]

    private fun signedWord(data: IntArray): Double {
        val raw = word(data)
        return if (raw > 0x7FFF) (raw - 0x10000).toDouble() else raw.toDouble()
    }

    private fun isMazda(vehicle: ExtendedVehicle) = vehicle.isMazda

    val entries: List<ExtendedPid> = buildList {
        add(
            ExtendedPid(
                id = OIL_PRESSURE,
                header = PCM,
                did = 0x0415,
                unit = KPA,
                decimals = 0,
                bytes = 2,
                tier = PidTier.Medium,
                applies = ::isMazda,
                decode = { if (word(it) == INVALID_WORD) NOT_USED else signedWord(it) },
            ),
        )
        add(
            ExtendedPid(
                id = OIL_TEMPERATURE,
                header = PCM,
                did = 0x1310,
                unit = CELSIUS,
                decimals = 0,
                bytes = 2,
                tier = PidTier.Medium,
                applies = ::isMazda,
                decode = { word(it) / OIL_TEMPERATURE_DIVISOR - TEMPERATURE_OFFSET },
            ),
        )
        add(
            ExtendedPid(
                id = TRANSMISSION_FLUID_TEMPERATURE,
                header = TCM,
                did = 0x1E1C,
                unit = CELSIUS,
                decimals = 0,
                bytes = 2,
                tier = PidTier.Slow,
                applies = ::isMazda,
                decode = {
                    val celsius = word(it) / FLUID_TEMPERATURE_DIVISOR
                    if (celsius in FLUID_TEMPERATURE_RANGE) celsius else NOT_USED
                },
            ),
        )
        WHEELS.forEachIndexed { index, wheel ->
            add(tyrePressure(wheel, BODY_BP, BODY_BP_RESPONSE, 0xD922 + index, BP_YEARS) {
                it[0] * PSI_PER_COUNT * BAR_PER_PSI
            })
            add(tyreTemperature(wheel, BODY_BP, BODY_BP_RESPONSE, 0xD926 + index, BP_YEARS))
            add(tyrePressure(wheel, BODY_BM, BODY_BM_RESPONSE, 0x2A05 + index, BM_YEARS) {
                it[0] * BAR_PER_COUNT
            })
            add(tyreTemperature(wheel, BODY_BM, BODY_BM_RESPONSE, 0x2A0A + index, BM_YEARS))
        }
    }

    private fun tyrePressure(
        wheel: String,
        header: String,
        response: String,
        did: Int,
        years: IntRange,
        decode: (IntArray) -> Double,
    ) = ExtendedPid(
        id = tyrePressureId(wheel),
        header = header,
        receiveHeader = response,
        did = did,
        unit = BAR,
        decimals = 2,
        bytes = 1,
        tier = PidTier.Slow,
        minIntervalMillis = TYRE_INTERVAL_MILLIS,
        applies = { it.isMazda && it.builtIn(years) },
        decode = decode,
    )

    private fun tyreTemperature(
        wheel: String,
        header: String,
        response: String,
        did: Int,
        years: IntRange,
    ) = ExtendedPid(
        id = tyreTemperatureId(wheel),
        header = header,
        receiveHeader = response,
        did = did,
        unit = CELSIUS,
        decimals = 0,
        bytes = 1,
        tier = PidTier.Slow,
        minIntervalMillis = TYRE_INTERVAL_MILLIS,
        applies = { it.isMazda && it.builtIn(years) },
        decode = { it[0] - TYRE_TEMPERATURE_OFFSET },
    )

    /**
     * The parameters worth probing on [vehicle].
     *
     * A car this table knows nothing about gets an empty list and is never sent a single
     * extended request — which is the point of gating on the VIN rather than probing
     * everything and seeing what sticks.
     */
    fun candidatesFor(vehicle: ExtendedVehicle): List<ExtendedPid> =
        entries.filter { it.applies(vehicle) }

    /** Every distinct reading the table can produce, whatever car it came from. */
    val metrics: List<ExtendedPid> = entries.distinctBy(ExtendedPid::id)

    operator fun get(id: String): ExtendedPid? = metrics.firstOrNull { it.id == id }
}
