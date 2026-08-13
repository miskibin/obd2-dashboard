package com.miskibin.obd2dashboard.obd

/** ISO 14229 `ReadDataByIdentifier`, the one manufacturer service that only reads. */
const val MODE_READ_DATA_BY_ID = 0x22

/**
 * `ReadDataByLocalIdentifier`, the KWP2000 ancestor of [MODE_READ_DATA_BY_ID].
 *
 * One byte of identifier instead of two, and one identifier answers a whole block of data
 * rather than a single value — forty to seventy bytes with a different reading at each
 * offset. Toyota's Denso control units and Hyundai's answer both services, and on the
 * older cars this is the only one they answer, which is why it is here: `21 51` reads an
 * oil temperature off a 2008 Corolla that has never heard of a UDS identifier.
 */
const val MODE_READ_LOCAL_ID = 0x21

/**
 * A marque, as far as the first three characters of a VIN can settle it.
 *
 * The world manufacturer identifier is the only thing about a car the app can be certain
 * of before it sends anything, and certainty is the whole requirement: an extended
 * identifier is a manufacturer's private address space, so the same number is a different
 * reading — or a refusal, or nothing at all — one badge over. Every code here is one the
 * research notes verified against vPIC or the Wikibooks WMI list; nothing is guessed from
 * the shape of a VIN.
 *
 * Opel is deliberately two marques rather than one. `W0L` is the General Motors car and
 * `W0V` the Stellantis one built on PSA underpinnings, and they share nothing that matters
 * here — not the module addresses, not the identifiers, not the ECU supplier.
 */
enum class Marque(val wmi: Set<String>) {
    Mazda(setOf("JM1", "JM7", "JMZ")),

    /** VW, Škoda, SEAT, Cupra and Audi, which share the module map and most identifiers. */
    VolkswagenGroup(
        setOf("WVW", "WVG", "WV1", "WV2", "VWV", "TMB", "VSS", "WAU", "WA1", "TRU", "WUA"),
    ),

    Toyota(
        setOf(
            "JT1", "JT2", "JT3", "JT4", "JT5", "JT6", "JT7", "JT8", "JTH", "JTJ",
            "SB1", "VNK", "NMT", "MR0", "TW1",
        ),
    ),

    /** Ford of Europe: Cologne and Saarlouis, Valencia, Britain, and Otosan in Turkey. */
    Ford(setOf("WF0", "VS6", "SFA", "NM0")),

    HyundaiKia(
        setOf(
            "KMH", "KMF", "KMJ", "TMA", "NLH", "MAL",
            "KNA", "KNB", "KNC", "KND", "KNE", "U5Y", "U6Y",
        ),
    ),

    RenaultDacia(setOf("VF1", "VF2", "X7L", "NM1", "UU1")),

    /** Opel and Vauxhall as General Motors built them, to about 2017. */
    OpelGm(setOf("W0L", "VXK", "VSX")),

    /** Opel as Stellantis builds it: PSA platform, PSA headers, PSA identifiers. */
    OpelPsa(setOf("W0V")),

    Nissan(setOf("SJN", "VSK", "JN1", "JN8", "MNT", "MDH")),

    Mercedes(setOf("WDB", "WDD", "WDC", "W1K", "W1N", "W1V", "WDF", "VSA", "WMX")),
    ;

    companion object {
        private val byWmi: Map<String, Marque> =
            entries.flatMap { marque -> marque.wmi.map { it to marque } }.toMap()

        fun of(wmi: String): Marque? = byWmi[wmi.uppercase()]
    }
}

/**
 * The car an extended parameter is being considered for.
 *
 * Deliberately little: which marque issued the VIN, which model year it carries, and what
 * the driver said is in the tank. The first two are what the VIN itself settles offline;
 * the third comes from the vehicle profile and is the only honest way to tell a TDI from a
 * TSI, since nothing in a European VIN says which engine was fitted.
 */
data class ExtendedVehicle(
    val vin: String? = null,
    val modelYear: Int? = null,
    val fuel: FuelType? = null,
) {

    /** Which marque issued this VIN, or null for one no table here knows. */
    val marque: Marque? get() = vin?.take(WMI_LENGTH)?.let(Marque::of)

    fun isA(marque: Marque): Boolean = this.marque == marque

    /** Mazda's three world manufacturer identifiers. */
    val isMazda: Boolean get() = isA(Marque.Mazda)

    /**
     * Whether the driver's profile says this car burns diesel.
     *
     * An unfilled profile is not a diesel. Everything gated on this — particulate filter
     * loads, injector corrections — is meaningless on a petrol engine and would be a row
     * of dashes on the dashboard, so the doubt is resolved towards asking nothing.
     */
    val isDiesel: Boolean get() = fuel == FuelType.Diesel

    /** Whether the model year is known to be within [range]; false when it is not known. */
    fun builtIn(range: IntRange): Boolean = modelYear?.let { it in range } == true

    /** Whether the car is known to be [year] or later; false when the year is not known. */
    fun builtSince(year: Int): Boolean = modelYear?.let { it >= year } == true

    private companion object {
        const val WMI_LENGTH = 3
    }
}

/**
 * One manufacturer-specific reading, addressed to one module.
 *
 * The fields at the top are the whole difference from a [Pid]: an extended parameter is not
 * broadcast to every ECU on `7DF`, it is asked of one module at [header], and a module
 * outside the `7Ex` engine range answers on an address the adapter is not listening to
 * unless [receiveHeader] has been set with `ATCRA` first. Get either wrong and the result
 * is `NO DATA` from a car that would have answered perfectly.
 *
 * [decode] is a Kotlin lambda rather than an interpreted formula string. The curated set is
 * small, every entry in it has been checked against a real response, and a formula
 * interpreter would buy nothing but the ability to ship an unchecked one.
 *
 * [id] is the stable identity of the *reading*, not of the request: two generations of the
 * same car report tyre pressure from different modules at different identifiers, and two
 * marques report oil temperature from different services entirely. To a driver that is one
 * parameter. They therefore share an id, and [applies] keeps at most one of them live on
 * any given car.
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
    /**
     * Whether the answer needs the adapter's flow control configured; see
     * [Obd2Client.withModule].
     *
     * True for the readings that live inside a long multi-frame block — a tyre pressure
     * forty bytes into a chassis module's answer — where an adapter left to its own devices
     * returns the first six bytes and stops.
     */
    val flowControl: Boolean = false,
    val applies: (ExtendedVehicle) -> Boolean,
    val decode: (IntArray) -> Double,
) {
    /**
     * The identifier as the request carries it: two bytes for service `22`, one for `21`.
     *
     * Also what a positive response repeats before the data, which is how the payload is
     * located rather than by counting bytes from the start of the frame.
     */
    val didBytes: IntArray
        get() = if (service == MODE_READ_LOCAL_ID) {
            intArrayOf(did and 0xFF)
        } else {
            intArrayOf(did shr Byte.SIZE_BITS, did and 0xFF)
        }

    /** The request, e.g. `220415` or `2151`. */
    val request: String
        get() = "%02X".format(service) + didBytes.joinToString("") { "%02X".format(it) }

    /**
     * The byte a positive response starts with.
     *
     * Computed rather than written down: every service answers with its own number plus
     * `0x40`, and hard-coding `62` next to a service field that can hold something else is
     * a bug waiting for the first non-`22` entry.
     */
    val responseMarker: Int get() = service + ObdResponseParser.RESPONSE_OFFSET

    /** True when the adapter has to be told which address to listen on for this module. */
    val needsReceiveFilter: Boolean get() = receiveHeader != null

    init {
        require(service != MODE_READ_LOCAL_ID || did <= 0xFF) {
            "a local identifier is one byte: $id asks for %04X".format(did)
        }
    }
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
 * Every entry was read off a real car — see `docs/research-mazda-extended-pids.md` and
 * `docs/research-multibrand-extended-pids.md` — and every one is a read. Nothing in this
 * table writes, changes a session, resets an adaptation or runs a routine, and nothing ever
 * should: an app that polls a running engine has no business sending anything a car cannot
 * ignore. That rule is why several marques ship thinner sets than their research section
 * lists, and why two ship nothing at all: the identifiers behind a `10 03` diagnostic
 * session are simply not taken.
 *
 * The per-marque tables live one file each. This object is what they have in common: the
 * name of every reading, and the list of them all.
 */
object ExtendedPids {

    // ---- the readings, by name ---------------------------------------------------
    //
    // A name is the identity of the reading and not of the request behind it, so a marque
    // that reports oil temperature from its instrument cluster and one that reports it
    // from a block read forty bytes long both land on OIL_TEMPERATURE and share a tile,
    // a chart line and a column in an export.

    const val OIL_PRESSURE = "ext_oil_pressure"
    const val OIL_TEMPERATURE = "ext_oil_temperature"
    const val OIL_LEVEL = "ext_oil_level"
    const val TRANSMISSION_FLUID_TEMPERATURE = "ext_atf_temperature"
    const val BOOST_PRESSURE = "ext_boost_pressure"
    const val CHARGE_AIR_TEMPERATURE = "ext_charge_air_temperature"
    const val CYLINDER_HEAD_TEMPERATURE = "ext_cylinder_head_temperature"
    const val WASTEGATE_DUTY = "ext_wastegate_duty"
    const val ENGINE_TORQUE = "ext_engine_torque"
    const val ODOMETER = "ext_odometer"

    /**
     * The gear the gearbox reports itself to be in, as a number.
     *
     * Not the shift indicator of `0165` — that is the gear the ECU would *like* — and not
     * the ratio of `01A4`. This is the transmission's own answer, and it is the only one
     * that is a measurement rather than an inference.
     */
    const val GEAR = "ext_gear"
    const val ALTERNATOR_POWER = "ext_alternator_power"
    const val AC_PRESSURE = "ext_ac_pressure"
    const val EGR_POSITION = "ext_egr_position"
    const val TURBO_VANE_POSITION = "ext_turbo_vane_position"

    const val BATTERY_SOC = "ext_battery_soc"
    const val BATTERY_HEALTH = "ext_battery_health"
    const val BATTERY_TEMPERATURE = "ext_battery_temperature"
    const val BATTERY_VOLTAGE = "ext_battery_voltage"
    const val BATTERY_CURRENT = "ext_battery_current"
    const val BATTERY_RESISTANCE = "ext_battery_resistance"

    const val DPF_SOOT_MEASURED = "ext_dpf_soot_measured"
    const val DPF_SOOT_CALCULATED = "ext_dpf_soot_calculated"
    const val DPF_ASH_MASS = "ext_dpf_ash_mass"
    const val DPF_DISTANCE_SINCE_REGEN = "ext_dpf_distance_since_regen"
    const val DPF_REGEN_INTERRUPTIONS = "ext_dpf_regen_interruptions"
    const val DPF_INLET_TEMPERATURE = "ext_dpf_inlet_temperature"
    const val DPF_OUTLET_TEMPERATURE = "ext_dpf_outlet_temperature"
    const val DPF_PRESSURE_DIFFERENCE = "ext_dpf_pressure_difference"

    const val HV_SOC = "ext_hv_soc"
    const val HV_HEALTH = "ext_hv_health"
    const val HV_VOLTAGE = "ext_hv_voltage"
    const val HV_CURRENT = "ext_hv_current"
    const val HV_ENERGY = "ext_hv_energy"
    const val INVERTER_TEMPERATURE = "ext_inverter_temperature"

    // The names of the families are [ExtendedNames]', because the marque tables need them
    // while this object is still building its list and cannot ask it for anything.

    /** Tyre positions, in the order the tables report them. */
    val WHEELS = ExtendedNames.WHEELS

    fun tyrePressureId(wheel: String) = ExtendedNames.tyrePressure(wheel)

    fun tyreTemperatureId(wheel: String) = ExtendedNames.tyreTemperature(wheel)

    /**
     * The deviation of one cylinder's injected quantity from the mean, by cylinder number.
     *
     * A family rather than four names, because what the reading *is* does not change
     * between cylinder one and cylinder four — only which injector it indicts.
     */
    fun injectionDeviationId(cylinder: Int) = ExtendedNames.injectionDeviation(cylinder)

    /**
     * Every entry, marque by marque, in the order the research notes recommend building
     * them: the largest European parc and the best-captured data first.
     */
    val entries: List<ExtendedPid> = buildList {
        addAll(MazdaPids.entries)
        addAll(VagPids.entries)
        addAll(ToyotaPids.entries)
        addAll(FordPids.entries)
        addAll(HyundaiKiaPids.entries)
        addAll(RenaultPids.entries)
        addAll(OpelPids.entries)
        addAll(NissanPids.entries)
        addAll(MercedesPids.entries)
    }

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
