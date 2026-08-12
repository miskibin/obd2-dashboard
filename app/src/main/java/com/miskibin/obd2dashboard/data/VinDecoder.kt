package com.miskibin.obd2dashboard.data

/** What the VIN says about itself, before anybody is asked and before anything is sent. */
data class VinFacts(
    val manufacturer: String? = null,
    val country: String? = null,
    val modelYear: Int? = null,
) {
    val isEmpty: Boolean get() = manufacturer == null && country == null && modelYear == null
}

/**
 * Reads the parts of a VIN that are standardised, offline.
 *
 * The obvious alternative is NHTSA's free vPIC service, and it was measured against real
 * European VINs before this was written: Škoda (`TMB`), Citroën (`VF7`), SEAT (`VSS`) and
 * Nissan Europe (`SJN`) are not in its registry at all, and an Audi built in 2011 came back
 * as a 1981 model because the year character was decoded without the manufacturer's own
 * table. It is a record of what was submitted to the US DOT, and a car sold in Poland was
 * not. Add a 2.5-second round trip and a VIN — which identifies one car and, in practice,
 * its owner — leaving the phone, and a bundled table wins on coverage, latency and privacy
 * at once. See `docs/research-vehicle-metadata.md`.
 *
 * Only the three fields the standard actually fixes are read: ISO 3780 assigns the world
 * manufacturer identifier in characters 1–3 and the region in characters 1–2, and ISO 3779
 * puts the model year in character 10. Everything past that — model, engine, trim — is the
 * manufacturer's own private encoding, different for every marque, and is what the driver
 * fills in on the vehicle screen instead.
 */
object VinDecoder {

    const val LENGTH = 17

    /**
     * The VIN alphabet, in the order the standard's own ranges run in.
     *
     * I, O and Q are excluded everywhere in a VIN so they cannot be confused with 1, 0 and
     * 0. The digits follow the letters and 0 comes last, which is what makes a published
     * range like `TW–T1` (Portugal) or `V6–V0` (Estonia) a contiguous run.
     */
    private const val ALPHABET = "ABCDEFGHJKLMNPRSTUVWXYZ1234567890"

    /**
     * The year character's 30-value cycle, starting at 1980.
     *
     * The letters run A–Y for 1980–2000 and the digits 1–9 for 2001–2009, then the whole
     * thing repeats. Z and 0 are not used for the year at all.
     */
    private const val YEAR_CYCLE = "ABCDEFGHJKLMNPRSTVWXY123456789"

    private const val YEAR_EPOCH = 1980
    private const val YEAR_PERIOD = 30

    fun decode(vin: String?, currentYear: Int = thisYear()): VinFacts? {
        val clean = vin?.trim()?.uppercase() ?: return null
        if (clean.length != LENGTH) return null
        if (clean.any { it !in ALPHABET }) return null
        val facts = VinFacts(
            manufacturer = MANUFACTURERS[clean.take(WMI_LENGTH)],
            country = countryOf(clean),
            modelYear = modelYear(clean[YEAR_POSITION], currentYear),
        )
        return facts.takeUnless(VinFacts::isEmpty)
    }

    /**
     * Resolves the year character to the most recent year it can mean.
     *
     * The character repeats every thirty years, so `B` is 1981, 2011 and 2041 at once. The
     * app only ever meets it on a car with an OBD2 port, which did not exist before the
     * mid-nineties, so the newest reading that is not in the future is right for every car
     * this will run against. One year of headroom is allowed because a model year starts
     * during the calendar year before it.
     */
    private fun modelYear(code: Char, currentYear: Int): Int? {
        val index = YEAR_CYCLE.indexOf(code).takeIf { it >= 0 } ?: return null
        var year = YEAR_EPOCH + index
        while (year + YEAR_PERIOD <= currentYear + 1) year += YEAR_PERIOD
        return year
    }

    private fun countryOf(vin: String): String? {
        val first = vin[0]
        val second = vin[1]
        return REGIONS.firstOrNull { region ->
            region.prefix == first && second.rank() in region.from.rank()..region.to.rank()
        }?.country
    }

    private fun Char.rank(): Int = ALPHABET.indexOf(this)

    private fun thisYear(): Int = java.time.Year.now().value

    private const val WMI_LENGTH = 3

    /** Character 10, zero-indexed. */
    private const val YEAR_POSITION = 9

    /** One published assignment of a second-character range to the country of assembly. */
    private data class Region(val prefix: Char, val from: Char, val to: Char, val country: String)

    private fun region(range: String, country: String) =
        Region(range[0], range[1], range[4], country)

    /**
     * ISO 3780's region table, trimmed to the places a car in Europe is built.
     *
     * An unlisted range gives no country rather than a guess: "assembled somewhere" is not
     * worth a line on the screen, and a wrong country is worse than a missing one.
     */
    private val REGIONS: List<Region> = listOf(
        region("AA-AH", "South Africa"),
        region("JA-J0", "Japan"),
        region("KL-KR", "South Korea"),
        region("LA-L0", "China"),
        region("MA-ME", "India"),
        region("MF-MK", "Indonesia"),
        region("ML-MR", "Thailand"),
        region("NL-NR", "Türkiye"),
        region("SA-SM", "United Kingdom"),
        region("SN-ST", "Germany"),
        region("SU-SZ", "Poland"),
        region("TA-TH", "Switzerland"),
        region("TJ-TP", "Czechia"),
        region("TR-TV", "Hungary"),
        region("TW-T1", "Portugal"),
        region("UH-UM", "Denmark"),
        region("UN-UT", "Ireland"),
        region("UU-UZ", "Romania"),
        region("U5-U7", "Slovakia"),
        region("VA-VE", "Austria"),
        region("VF-VR", "France"),
        region("VS-VW", "Spain"),
        region("VX-V2", "Serbia"),
        region("V3-V5", "Croatia"),
        region("V6-V0", "Estonia"),
        region("WA-W0", "Germany"),
        region("XA-XE", "Bulgaria"),
        region("XF-XK", "Greece"),
        region("XL-XR", "Netherlands"),
        region("XS-XW", "Russia"),
        region("XX-X2", "Luxembourg"),
        region("X3-X0", "Russia"),
        region("YA-YE", "Belgium"),
        region("YF-YK", "Finland"),
        region("YS-YW", "Sweden"),
        region("YX-Y2", "Norway"),
        region("Y6-Y0", "Ukraine"),
        region("ZA-ZR", "Italy"),
        region("ZX-Z2", "Slovenia"),
        region("1A-10", "United States"),
        region("2A-20", "Canada"),
        region("3A-3W", "Mexico"),
        region("4A-40", "United States"),
        region("5A-50", "United States"),
        region("6A-6W", "Australia"),
        region("8A-8E", "Argentina"),
        region("8F-8K", "Chile"),
        region("9A-9E", "Brazil"),
        region("93-99", "Brazil"),
    )

    /**
     * World manufacturer identifiers, weighted towards what is on a European road.
     *
     * A marque appears more than once because the identifier is issued per plant and per
     * vehicle class, not per brand: Volkswagen's cars, vans and SUVs carry three different
     * codes, and an Audi from Ingolstadt and one from Győr carry two. A code that is not
     * here yields no manufacturer at all — the screen then says so, and the driver names
     * the car themselves, which is a better outcome than confidently printing the wrong
     * marque.
     */
    private val MANUFACTURERS: Map<String, String> = mapOf(
        // Volkswagen Group
        "WVW" to "Volkswagen",
        "WV1" to "Volkswagen",
        "WV2" to "Volkswagen",
        "WVG" to "Volkswagen",
        "1VW" to "Volkswagen",
        "3VW" to "Volkswagen",
        "9BW" to "Volkswagen",
        "XW8" to "Volkswagen",
        "WAU" to "Audi",
        "WA1" to "Audi",
        "WUA" to "Audi",
        "TRU" to "Audi",
        "WP0" to "Porsche",
        "WP1" to "Porsche",
        "TMB" to "Škoda",
        "VSS" to "SEAT",

        // BMW
        "WBA" to "BMW",
        "WBS" to "BMW M",
        "WBX" to "BMW",
        "WBY" to "BMW i",
        "5UX" to "BMW",
        "WMW" to "MINI",
        "SCA" to "Rolls-Royce",

        // Mercedes-Benz
        "WDB" to "Mercedes-Benz",
        "WDC" to "Mercedes-Benz",
        "WDD" to "Mercedes-Benz",
        "WDF" to "Mercedes-Benz",
        "W1K" to "Mercedes-Benz",
        "W1N" to "Mercedes-Benz",
        "W1V" to "Mercedes-Benz",
        "VSA" to "Mercedes-Benz",
        "WME" to "smart",

        // Stellantis and its predecessors
        "VF1" to "Renault",
        "VF2" to "Renault",
        "X7L" to "Renault",
        "UU1" to "Dacia",
        "UU2" to "Dacia",
        "VF3" to "Peugeot",
        "VR3" to "Peugeot",
        "VF7" to "Citroën",
        "VR7" to "Citroën",
        "VR1" to "DS",
        "W0L" to "Opel",
        "W0V" to "Opel",
        "VXK" to "Vauxhall",
        "ZFA" to "Fiat",
        "ZFC" to "Fiat",
        "ZAR" to "Alfa Romeo",
        "ZLA" to "Lancia",
        "ZAM" to "Maserati",
        "ZFF" to "Ferrari",
        "ZHW" to "Lamborghini",

        // Ford
        "WF0" to "Ford",
        "1FA" to "Ford",
        "1FM" to "Ford",
        "1FT" to "Ford",
        "3FA" to "Ford",

        // Japanese marques, including their European plants
        "JTD" to "Toyota",
        "JTE" to "Toyota",
        "JTM" to "Toyota",
        "JTH" to "Lexus",
        "SB1" to "Toyota",
        "NMT" to "Toyota",
        "VNK" to "Toyota",
        "JHM" to "Honda",
        "JHL" to "Honda",
        "SHH" to "Honda",
        "SHS" to "Honda",
        "JN1" to "Nissan",
        "JN6" to "Nissan",
        "JN8" to "Nissan",
        "SJN" to "Nissan",
        "VSK" to "Nissan",
        "JM1" to "Mazda",
        "JM7" to "Mazda",
        "JMZ" to "Mazda",
        "JMB" to "Mitsubishi",
        "JMY" to "Mitsubishi",
        "JF1" to "Subaru",
        "JF2" to "Subaru",
        "JS2" to "Suzuki",
        "JS3" to "Suzuki",
        "TSM" to "Suzuki",
        "JDA" to "Daihatsu",
        "JAA" to "Isuzu",
        "JAC" to "Isuzu",

        // Korean marques, including their European plants
        "KMH" to "Hyundai",
        "TMA" to "Hyundai",
        "NLH" to "Hyundai",
        "KNA" to "Kia",
        "KNB" to "Kia",
        "KND" to "Kia",
        "KNE" to "Kia",
        "U5Y" to "Kia",
        "U6Y" to "Kia",
        "KL1" to "Chevrolet",
        "KL3" to "Chevrolet",

        // Nordic and British
        "YV1" to "Volvo",
        "YV4" to "Volvo",
        "LYV" to "Volvo",
        "YS3" to "Saab",
        "YS2" to "Scania",
        "SAL" to "Land Rover",
        "SAJ" to "Jaguar",
        "SCB" to "Bentley",
        "SCC" to "Lotus",
        "SCF" to "Aston Martin",

        // Central and eastern Europe
        "SUP" to "FSO",
        "XTA" to "Lada",
        "WMA" to "MAN",
        "WJM" to "Iveco",
        "ZCF" to "Iveco",

        // Electric-era entrants
        "5YJ" to "Tesla",
        "7SA" to "Tesla",
        "LRW" to "Tesla",
        "XP7" to "Tesla",
        "LSJ" to "MG",
        "LSV" to "Volkswagen",
    )
}
