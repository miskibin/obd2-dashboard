package com.miskibin.obd2dashboard.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-marque extended tables: what each decoder makes of a real answer, which car
 * each entry is offered to, and the rules the whole table has to keep.
 *
 * The worked examples are taken from the tables in
 * `docs/research-multibrand-extended-pids.md` — an entry that decodes its own capture
 * wrongly is the one bug this table can have that nothing else would ever catch, because a
 * wrong number looks exactly like a right one on a dashboard.
 */
class ExtendedBrandPidTest {

    // ---- representative cars ------------------------------------------------------

    private val golfDiesel = ExtendedVehicle("WVWZZZAUZJW123456", 2018, FuelType.Diesel)
    private val golfPetrol = golfDiesel.copy(fuel = FuelType.Petrol)
    private val golfUnknownFuel = golfDiesel.copy(fuel = null)
    private val golfMk5 = golfDiesel.copy(modelYear = 2007)
    private val octavia = ExtendedVehicle("TMBJJ7NE4G0123456", 2016, FuelType.Diesel)
    private val corolla = ExtendedVehicle("SB1KZ3JE60E123456", 2014)
    private val focus = ExtendedVehicle("WF05XXGCC5JR12345", 2018, FuelType.Petrol)
    private val ceed = ExtendedVehicle("U5YH5814AJL123456", 2018, FuelType.Diesel)
    private val sportage2022 = ExtendedVehicle("U5YPH811ANL123456", 2022, FuelType.Petrol)
    private val duster = ExtendedVehicle("UU1HSDCEG54123456", 2016, FuelType.Diesel)
    private val astraJ = ExtendedVehicle("W0LPD6EC4B1234567", 2012, FuelType.Diesel)
    private val corsaE = ExtendedVehicle("W0V0XEP68P4123456", 2023)
    private val qashqai = ExtendedVehicle("SJNFAAJ11U1234567", 2016, FuelType.Diesel)
    private val cClass = ExtendedVehicle("WDD2040471A123456", 2014, FuelType.Diesel)
    private val mazda3 = ExtendedVehicle("JM1BPBLM7K1234567", 2019, FuelType.Petrol)

    /** The one entry with this id that [vehicle] would actually be asked. */
    private fun pidFor(vehicle: ExtendedVehicle, id: String): ExtendedPid {
        val matches = ExtendedPids.candidatesFor(vehicle).filter { it.id == id }
        assertEquals("one entry per reading per car: $id", 1, matches.size)
        return matches.single()
    }

    private fun decode(vehicle: ExtendedVehicle, id: String, vararg bytes: Int): Double =
        pidFor(vehicle, id).decode(bytes)

    /** A payload of [size] bytes with [values] placed at their offsets. */
    private fun block(size: Int, vararg values: Pair<Int, Int>): IntArray {
        val data = IntArray(size)
        values.forEach { (at, value) -> data[at] = value }
        return data
    }

    // ---- Volkswagen group ---------------------------------------------------------

    @Test
    fun `the group's cluster oil temperature carries a fifty-eight degree offset`() {
        // `714 / 22 202F`, formula A − 58: 0x8C = 140 → 82 °C.
        assertEquals(82.0, decode(golfDiesel, ExtendedPids.OIL_TEMPERATURE, 0x8C), 0.001)
        assertEquals("714", pidFor(golfDiesel, ExtendedPids.OIL_TEMPERATURE).header)
    }

    @Test
    fun `the group's oil level and boost decode to millimetres and bar`() {
        // `22 11BA`, (A*256+B)/250: 0x1B58 = 7000 → 28 mm.
        assertEquals(28.0, decode(golfDiesel, ExtendedPids.OIL_LEVEL, 0x1B, 0x58), 0.001)
        // `22 1057`, millibar: 0x0898 = 2200 → 2.2 bar absolute.
        assertEquals(2.2, decode(golfDiesel, ExtendedPids.BOOST_PRESSURE, 0x08, 0x98), 0.001)
    }

    @Test
    fun `the battery sensor's charge, temperature, voltage and resistance`() {
        assertEquals(87.0, decode(golfDiesel, ExtendedPids.BATTERY_SOC, 87), 0.001)
        assertEquals(19.0, decode(golfDiesel, ExtendedPids.BATTERY_TEMPERATURE, 59), 0.001)
        // `22 2A07`, (A*256+B)/1000 + 4: 0x2134 = 8500 → 12.5 V.
        assertEquals(12.5, decode(golfDiesel, ExtendedPids.BATTERY_VOLTAGE, 0x21, 0x34), 0.001)
        // `22 2A0E`, a fifth of a count: 60 → 12 mΩ.
        assertEquals(12.0, decode(golfDiesel, ExtendedPids.BATTERY_RESISTANCE, 60), 0.001)
        // A resistance no battery has is a decoding that does not apply to this car.
        assertFalse(decode(golfDiesel, ExtendedPids.BATTERY_RESISTANCE, 250).isFinite())
    }

    @Test
    fun `both battery current decodings ship, and only the plausible one answers`() {
        val variants = ExtendedPids.candidatesFor(golfDiesel)
            .filter { it.id == ExtendedPids.BATTERY_CURRENT }
        val short = variants.single { it.bytes == 3 }
        val long = variants.single { it.bytes == 4 }

        // 24-bit: (A*65536+B*256+C)/1000 − 3000. 0x2DC6C0 = 3 000 000 → 0 A.
        assertEquals(0.0, short.decode(intArrayOf(0x2D, 0xC6, 0xC0)), 0.001)
        assertEquals(-45.0, short.decode(intArrayOf(0x2D, 0x16, 0xF8)), 0.001)
        // 32-bit signed milliamps: 0xFFFF3CB0 = −50 000 → −50 A.
        assertEquals(-50.0, long.decode(intArrayOf(0xFF, 0xFF, 0x3C, 0xB0)), 0.001)

        // The same four bytes read the other way is thousands of amps, and is withheld.
        assertFalse(short.decode(intArrayOf(0xFF, 0xFF, 0x3C)).isFinite())
        assertFalse(long.decode(intArrayOf(0x2D, 0xC6, 0xC0, 0x00)).isFinite())
    }

    @Test
    fun `the DSG fluid temperature refuses the reading the other encoding always gives`() {
        val atf = pidFor(golfDiesel, ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE)

        assertEquals(85.0, atf.decode(intArrayOf(125)), 0.001)
        // A gearbox answering with a signed sixteen-bit value hands back a high byte of
        // zero at every temperature it can reach, i.e. exactly −40 °C forever.
        assertFalse(atf.decode(intArrayOf(0)).isFinite())
    }

    @Test
    fun `the particulate filter block decodes soot, ash, distance and temperatures`() {
        // `22 1ABE`, hundredths of a gram: 0x0BB8 = 3000 → 30.00 g.
        assertEquals(30.0, decode(golfDiesel, ExtendedPids.DPF_SOOT_MEASURED, 0x0B, 0xB8), 0.001)
        assertEquals(24.5, decode(golfDiesel, ExtendedPids.DPF_SOOT_CALCULATED, 0x09, 0x92), 0.001)
        // `22 1ABD`, four bytes scaled to grams of ash.
        assertEquals(
            51.2,
            decode(golfDiesel, ExtendedPids.DPF_ASH_MASS, 0x19, 0x99, 0x99, 0x9A),
            0.1,
        )
        // `22 1ABA`, tenths of a kilometre: 0x0BB8 = 3000 → 300 km.
        assertEquals(
            300.0,
            decode(golfDiesel, ExtendedPids.DPF_DISTANCE_SINCE_REGEN, 0x0B, 0xB8),
            0.001,
        )
        assertEquals(3.0, decode(golfDiesel, ExtendedPids.DPF_REGEN_INTERRUPTIONS, 3), 0.001)
        // `22 11B2`, tenths of a kelvin: 0x1D4C = 7500 → 750.0 − 273.1 = 476.9 °C.
        assertEquals(
            476.9,
            decode(golfDiesel, ExtendedPids.DPF_INLET_TEMPERATURE, 0x1D, 0x4C),
            0.001,
        )
        assertEquals(
            226.9,
            decode(golfDiesel, ExtendedPids.DPF_OUTLET_TEMPERATURE, 0x13, 0x88),
            0.001,
        )
    }

    @Test
    fun `the injector corrections keep the firing order the capture recorded`() {
        val byCylinder = (1..4).associateWith {
            pidFor(golfDiesel, ExtendedPids.injectionDeviationId(it)).did
        }

        // 1-3-4-2, not 1-2-3-4. Sorting these into ascending identifiers would accuse the
        // wrong injector, which is the whole reason the reading exists.
        assertEquals(mapOf(1 to 0x10FF, 2 to 0x1105, 3 to 0x1100, 4 to 0x1104), byCylinder)
        assertEquals(
            -1.5,
            decode(golfDiesel, ExtendedPids.injectionDeviationId(2), 0xFF, 0x6A),
            0.001,
        )
    }

    @Test
    fun `the group's tyre pressures come off the direct sensors, sixth byte in`() {
        val pressure = pidFor(golfDiesel, ExtendedPids.tyrePressureId("fl"))

        assertEquals(0x18A1, pressure.did)
        assertTrue(pressure.flowControl)
        // Six bytes of answer, the pressure in fortieths of a bar in the last of them.
        assertEquals(2.30, pressure.decode(block(6, 5 to 92)), 0.001)
        // A byte read at the wrong offset is not a tyre pressure.
        assertFalse(pressure.decode(block(6, 5 to 250)).isFinite())
    }

    @Test
    fun `a pre-2009 group car is asked nothing, because it does not speak this protocol`() {
        // Golf V and its platform siblings talk KWP2000 over TP2.0, which this app's
        // adapter flow cannot reach at all.
        assertEquals(emptyList<ExtendedPid>(), ExtendedPids.candidatesFor(golfMk5))
    }

    @Test
    fun `Skoda inherits the group's table, because it is the same car underneath`() {
        val skoda = ExtendedPids.candidatesFor(octavia).map(ExtendedPid::id).toSet()
        val volkswagen = ExtendedPids.candidatesFor(golfDiesel).map(ExtendedPid::id).toSet()

        assertEquals(volkswagen, skoda)
    }

    // ---- Toyota -------------------------------------------------------------------

    @Test
    fun `Toyota's oil temperature is the tenth byte of a block read`() {
        val oil = pidFor(corolla, ExtendedPids.OIL_TEMPERATURE)

        // Service 21, one identifier byte, and the answer is a block: `21 51` → J − 40.
        assertEquals(MODE_READ_LOCAL_ID, oil.service)
        assertEquals("2151", oil.request)
        assertEquals(0x61, oil.responseMarker)
        assertEquals(90.0, oil.decode(block(10, 9 to 130)), 0.001)
    }

    @Test
    fun `Toyota's gearbox temperature is two hundred and fifty-sixths of a degree`() {
        val atf = pidFor(corolla, ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE)

        assertEquals("2182", atf.request)
        // 0x5000 = 20480 → 80 − 40 = 40 °C.
        assertEquals(40.0, atf.decode(intArrayOf(0x50, 0x00)), 0.001)
        // 0x7800 = 30720 → 120 − 40 = 80 °C.
        assertEquals(80.0, atf.decode(intArrayOf(0x78, 0x00)), 0.001)
    }

    @Test
    fun `Toyota's hybrid readings are asked of the inverter, with flow control`() {
        val soc = pidFor(corolla, ExtendedPids.HV_SOC)
        val voltage = pidFor(corolla, ExtendedPids.HV_VOLTAGE)
        val current = pidFor(corolla, ExtendedPids.HV_CURRENT)

        assertEquals("7D2", soc.header)
        assertTrue(soc.flowControl && voltage.flowControl && current.flowControl)
        assertEquals(60.0, soc.decode(intArrayOf(153)), 0.5)
        // `22 1F9A`: pack voltage third and fourth byte, current fifth and sixth.
        assertEquals(230.0, voltage.decode(block(4, 2 to 0x39, 3 to 0x80)), 0.5)
        assertEquals(-4.8, current.decode(block(6, 4 to 0xFF, 5 to 0xD0)), 0.001)
    }

    @Test
    fun `Toyota's twelve-volt reading needs a model year the UDS service reaches`() {
        assertNull(
            ExtendedPids.candidatesFor(corolla)
                .firstOrNull { it.id == ExtendedPids.BATTERY_VOLTAGE },
        )
        val newer = corolla.copy(modelYear = 2019)
        assertEquals(
            13.8,
            decode(newer, ExtendedPids.BATTERY_VOLTAGE, 0x2C, 0x2D),
            0.05,
        )
    }

    // ---- Ford ---------------------------------------------------------------------

    @Test
    fun `Ford is offered the identifiers Mazda already reads, on Ford's own gate`() {
        val fordIds = ExtendedPids.candidatesFor(focus).map(ExtendedPid::did).toSet()

        // The shared powertrain module means a shared identifier space; only the gate and
        // the gearbox scaling differ.
        assertTrue(0x0415 in fordIds && 0x1310 in fordIds && 0x1E1C in fordIds)
        assertEquals(380.0, decode(focus, ExtendedPids.OIL_PRESSURE, 0x01, 0x7C), 0.001)
        assertFalse(decode(focus, ExtendedPids.OIL_PRESSURE, 0xFF, 0xFF).isFinite())
    }

    @Test
    fun `Ford's gearbox temperature is sixteenths, and Mazda's is eightieths`() {
        val ford = pidFor(focus, ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE)
        val mazda = pidFor(mazda3, ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE)

        assertEquals(ford.did, mazda.did)
        // 0x0640 = 1600: 100 °C in sixteenths, 20 °C in eightieths. Same identifier, same
        // bytes, two different cars — which is why the two entries are separate.
        assertEquals(100.0, ford.decode(intArrayOf(0x06, 0x40)), 0.001)
        assertEquals(20.0, mazda.decode(intArrayOf(0x06, 0x40)), 0.001)
    }

    @Test
    fun `Ford's oil temperature is withheld when the borrowed scaling makes nonsense`() {
        // Mazda's hundredths-of-a-degree scaling, verified on Mazda and not on a Ford. A
        // car that answers in whole degrees produces something no engine reaches.
        assertEquals(85.5, decode(focus, ExtendedPids.OIL_TEMPERATURE, 0x31, 0x06), 0.001)
        assertFalse(decode(focus, ExtendedPids.OIL_TEMPERATURE, 0x5A, 0x00).isFinite())
    }

    @Test
    fun `Ford's boost, charge air and head temperature decode to the app's own units`() {
        // `22 033E`, psi on the wire and bar on the tile: 0x4000 = 16384 → 18.13 psi.
        assertEquals(1.25, decode(focus, ExtendedPids.BOOST_PRESSURE, 0x40, 0x00), 0.01)
        // `22 0461`, sixty-fourths of a degree above −40: 0x0F00 = 3840 → 60 − 40 = 20 °C.
        assertEquals(20.0, decode(focus, ExtendedPids.CHARGE_AIR_TEMPERATURE, 0x0F, 0x00), 0.001)
        // `22 0334`, sixty-fourths: 0x1900 = 6400 → 100 °C.
        assertEquals(100.0, decode(focus, ExtendedPids.CYLINDER_HEAD_TEMPERATURE, 0x19, 0x00), 0.001)
        // `22 0462`, a fraction of 32 768: half travel is 50 %.
        assertEquals(50.0, decode(focus, ExtendedPids.WASTEGATE_DUTY, 0x40, 0x00), 0.001)
    }

    @Test
    fun `Ford's tyre pressures are twentieths of a psi from the body module`() {
        val pressure = pidFor(focus, ExtendedPids.tyrePressureId("fl"))

        assertEquals("726", pressure.header)
        assertEquals("72E", pressure.receiveHeader)
        // 0x028A = 650 → 32.5 psi → 2.24 bar.
        assertEquals(2.24, pressure.decode(intArrayOf(0x02, 0x8A)), 0.01)
    }

    // ---- Hyundai and Kia ----------------------------------------------------------

    @Test
    fun `Hyundai and Kia read the oil temperature at one offset under two services`() {
        val modern = pidFor(ceed, ExtendedPids.OIL_TEMPERATURE)
        val legacy = pidFor(ceed.copy(modelYear = 2011), ExtendedPids.OIL_TEMPERATURE)

        assertEquals(MODE_READ_DATA_BY_ID, modern.service)
        assertEquals("22E001", modern.request)
        assertEquals(MODE_READ_LOCAL_ID, legacy.service)
        assertEquals("2101", legacy.request)
        // B[34] × 0.75 − 48: 184 → 90 °C, whichever service asked.
        assertEquals(90.0, modern.decode(block(35, 34 to 184)), 0.001)
        assertEquals(90.0, legacy.decode(block(35, 34 to 184)), 0.001)
    }

    @Test
    fun `the Hyundai tyre layout follows the model year, not a guess`() {
        val newer = pidFor(sportage2022, ExtendedPids.tyrePressureId("fr"))
        val older = pidFor(ceed, ExtendedPids.tyrePressureId("fr"))

        // Same module, same identifier, the front right eight bytes in on one and nine on
        // the other. Reading the wrong one returns a temperature byte as a pressure.
        assertEquals(newer.did, older.did)
        assertEquals(2.21, newer.decode(block(20, 9 to 160)), 0.01)
        assertEquals(2.21, older.decode(block(20, 8 to 160)), 0.01)
        assertNotEquals(newer.bytes, older.bytes)
        // A car whose VIN did not settle a year is asked for neither layout.
        assertNull(
            ExtendedPids.candidatesFor(ceed.copy(modelYear = null))
                .firstOrNull { it.id == ExtendedPids.tyrePressureId("fr") },
        )
    }

    @Test
    fun `Hyundai's boost, battery and gearbox readings decode from their blocks`() {
        // `22 E021`, absolute kilopascals eight bytes in, shown in bar.
        assertEquals(
            1.62,
            pidFor(ceed, ExtendedPids.BOOST_PRESSURE).decode(block(9, 7 to 0x4C, 8 to 0x4B)),
            0.01,
        )
        assertEquals(
            88.0,
            pidFor(ceed, ExtendedPids.BATTERY_SOC).decode(block(41, 40 to 88)),
            0.001,
        )
        assertEquals(
            96.0,
            pidFor(ceed, ExtendedPids.BATTERY_HEALTH).decode(block(42, 41 to 96)),
            0.001,
        )
        // `21 A0`, the fourteenth byte of the gearbox block, in whole degrees above −40.
        assertEquals(
            75.0,
            pidFor(ceed, ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE).decode(block(14, 13 to 115)),
            0.001,
        )
    }

    @Test
    fun `Hyundai's one diesel reading is the distance since the filter was burnt clean`() {
        val distance = pidFor(ceed, ExtendedPids.DPF_DISTANCE_SINCE_REGEN)

        assertEquals("2103", distance.request)
        // Four bytes of metres, fifty-four into the block: 250 000 m → 250 km.
        assertEquals(
            250.0,
            distance.decode(block(57, 53 to 0x00, 54 to 0x03, 55 to 0xD0, 56 to 0x90)),
            0.001,
        )
    }

    // ---- Renault and Dacia --------------------------------------------------------

    @Test
    fun `Renault reports temperatures in tenths of a kelvin and pressures in millibar`() {
        // `22 2007`, (A*256+B)/10 − 273: 0x0E4E = 3662 → 366.2 − 273 = 93.2 °C.
        assertEquals(93.2, decode(duster, ExtendedPids.OIL_TEMPERATURE, 0x0E, 0x4E), 0.001)
        // `22 2401`, millibar: 0x0708 = 1800 → 1.8 bar.
        assertEquals(1.8, decode(duster, ExtendedPids.BOOST_PRESSURE, 0x07, 0x08), 0.001)
        // `22 2005`, hundredths of a volt.
        assertEquals(14.2, decode(duster, ExtendedPids.BATTERY_VOLTAGE, 0x05, 0x8C), 0.001)
        // `22 2004`, thirty-seconds of a newton metre, offset by 400 for engine braking.
        assertEquals(-40.0, decode(duster, ExtendedPids.ENGINE_TORQUE, 0x2D, 0x00), 0.001)
        assertEquals(1500.0, decode(duster, ExtendedPids.ALTERNATOR_POWER, 0x00, 0x96), 0.001)
        assertEquals(12.5, decode(duster, ExtendedPids.AC_PRESSURE, 0x00, 0x7D), 0.001)
    }

    @Test
    fun `Renault's odometer is read from the stability module, with flow control`() {
        val odometer = pidFor(duster, ExtendedPids.ODOMETER)

        assertEquals("740", odometer.header)
        assertTrue(odometer.flowControl)
        assertEquals(180_000.0, odometer.decode(intArrayOf(0x00, 0x02, 0xBF, 0x20)), 0.001)
    }

    // ---- Opel, which is two cars ---------------------------------------------------

    @Test
    fun `the two Opels share not one identifier, and no VIN can be both`() {
        val gm = ExtendedPids.candidatesFor(astraJ)
        val psa = ExtendedPids.candidatesFor(corsaE)

        assertTrue(gm.isNotEmpty() && psa.isNotEmpty())
        val shared = gm.map(ExtendedPid::did).toSet() intersect psa.map(ExtendedPid::did).toSet()
        assertEquals(emptySet<Int>(), shared)
        assertEquals(setOf("7E0"), gm.map(ExtendedPid::header).toSet())
        assertEquals(setOf("6B4"), psa.map(ExtendedPid::header).toSet())
        // W0L is the General Motors car and W0V the Stellantis one; the badge is the same
        // and nothing else is.
        assertEquals(Marque.OpelGm, astraJ.marque)
        assertEquals(Marque.OpelPsa, corsaE.marque)
    }

    @Test
    fun `the GM-era Opel reads its filter, its EGR valve and its turbo vanes`() {
        assertEquals(
            420.0,
            decode(astraJ, ExtendedPids.DPF_DISTANCE_SINCE_REGEN, 0x01, 0xA4),
            0.001,
        )
        // `22 20F4`, a signed byte of kilopascals.
        assertEquals(-3.0, decode(astraJ, ExtendedPids.DPF_PRESSURE_DIFFERENCE, 0xFD), 0.001)
        // `22 20F8`, tenths of a degree above −40: 0x1770 = 6000 → 600 − 40 = 560 °C.
        assertEquals(560.0, decode(astraJ, ExtendedPids.DPF_INLET_TEMPERATURE, 0x17, 0x70), 0.001)
        assertEquals(50.0, decode(astraJ, ExtendedPids.EGR_POSITION, 128), 0.5)
        assertEquals(100.0, decode(astraJ, ExtendedPids.TURBO_VANE_POSITION, 255), 0.001)
    }

    @Test
    fun `the PSA-era Opel reads its traction battery in the PSA scalings`() {
        // `22 D410`, 512ths of a percent: 0x9800 / 512 = 76 %.
        assertEquals(76.0, decode(corsaE, ExtendedPids.HV_SOC, 0x98, 0x00), 0.01)
        // `22 D815`, sixteenths of a volt.
        assertEquals(400.0, decode(corsaE, ExtendedPids.HV_VOLTAGE, 0x19, 0x00), 0.01)
        // `22 D860`, the health second and third byte in.
        assertEquals(94.0, decode(corsaE, ExtendedPids.HV_HEALTH, 0x00, 0x05, 0xE0), 0.01)
        assertEquals(45.0, decode(corsaE, ExtendedPids.HV_ENERGY, 0x0B, 0x40), 0.01)
        assertEquals(
            42_000.0,
            decode(corsaE, ExtendedPids.ODOMETER, 0x02, 0x80, 0xDE, 0x80),
            0.001,
        )
    }

    // ---- Nissan and Mercedes ------------------------------------------------------

    @Test
    fun `Nissan's oil temperature is the diesel's own sensor, not a modelled figure`() {
        val oil = pidFor(qashqai, ExtendedPids.OIL_TEMPERATURE)

        assertEquals(0x111F, oil.did)
        assertEquals(95.0, oil.decode(intArrayOf(145)), 0.001)
        // On the petrol car of the same name there is no verified identifier at all.
        assertNull(
            ExtendedPids.candidatesFor(qashqai.copy(fuel = FuelType.Petrol))
                .firstOrNull { it.id == ExtendedPids.OIL_TEMPERATURE },
        )
    }

    @Test
    fun `Nissan's tyres are quarter-psi counts and its pack readings need flow control`() {
        assertEquals(2.24, decode(qashqai, ExtendedPids.tyrePressureId("fl"), 130), 0.01)
        val soc = pidFor(qashqai, ExtendedPids.HV_SOC)
        assertTrue(soc.flowControl)
        assertEquals(MODE_READ_LOCAL_ID, soc.service)
        assertEquals(
            64.5,
            soc.decode(block(34, 31 to 0x0A, 32 to 0x30, 33 to 0x00)),
            0.1,
        )
        assertEquals(
            88.5,
            pidFor(qashqai, ExtendedPids.HV_HEALTH).decode(block(4, 2 to 0x22, 3 to 0x92)),
            0.01,
        )
    }

    @Test
    fun `Mercedes ships one reading, gated to what a gearbox can be`() {
        val entries = ExtendedPids.candidatesFor(cClass)

        assertEquals(listOf(ExtendedPids.TRANSMISSION_FLUID_TEMPERATURE), entries.map(ExtendedPid::id))
        // The twelfth byte of the answer, in whole degrees above −50.
        assertEquals(95.0, entries.single().decode(block(12, 11 to 145)), 0.001)
        // The offset is corroborated by owners rather than captured, so a byte that turns
        // out to be somewhere else in the answer shows nothing.
        assertFalse(entries.single().decode(block(12, 11 to 250)).isFinite())
    }

    // ---- the rules the whole table keeps -------------------------------------------

    @Test
    fun `a VIN is only ever offered its own marque's table`() {
        val cars = mapOf(
            Marque.VolkswagenGroup to golfDiesel,
            Marque.Toyota to corolla,
            Marque.Ford to focus,
            Marque.HyundaiKia to ceed,
            Marque.RenaultDacia to duster,
            Marque.OpelGm to astraJ,
            Marque.OpelPsa to corsaE,
            Marque.Nissan to qashqai,
            Marque.Mercedes to cClass,
            Marque.Mazda to mazda3,
        )
        val tables = mapOf(
            Marque.VolkswagenGroup to VagPids.entries,
            Marque.Toyota to ToyotaPids.entries,
            Marque.Ford to FordPids.entries,
            Marque.HyundaiKia to HyundaiKiaPids.entries,
            Marque.RenaultDacia to RenaultPids.entries,
            Marque.OpelGm to OpelPids.entries,
            Marque.OpelPsa to OpelPids.entries,
            Marque.Nissan to NissanPids.entries,
            Marque.Mercedes to MercedesPids.entries,
            Marque.Mazda to MazdaPids.entries,
        )

        cars.forEach { (marque, car) ->
            val candidates = ExtendedPids.candidatesFor(car)
            assertTrue("$marque should have entries", candidates.isNotEmpty())
            val own = tables.getValue(marque)
            assertTrue(
                "$marque was offered another marque's identifiers",
                candidates.all { it in own },
            )
            // A reading may be offered twice on one car only where the two are rival
            // decodings of one request — the battery current, where the published formulas
            // disagree and the car settles it by answering. Two *different* requests
            // filling one tile would be two modules taking turns to overwrite each other.
            candidates.groupBy(ExtendedPid::id).forEach { (id, rivals) ->
                assertEquals(
                    "$id is filled from more than one request on $marque",
                    1,
                    rivals.map { Triple(it.header, it.service, it.did) }.distinct().size,
                )
            }
        }
    }

    @Test
    fun `an unknown VIN, and no VIN at all, are sent nothing`() {
        // Honda, Fiat, Peugeot, Volvo, BMW: marques whose data this app does not have or
        // cannot address. A car it does not recognise is a car it does not talk to.
        listOf("JHMFK1670GX123456", "ZFA33400009123456", "VF3CC8HZ0FT123456", "YV1MS382342123456")
            .forEach { vin ->
                assertEquals(
                    emptyList<ExtendedPid>(),
                    ExtendedPids.candidatesFor(ExtendedVehicle(vin, 2016, FuelType.Diesel)),
                )
            }
        assertEquals(emptyList<ExtendedPid>(), ExtendedPids.candidatesFor(ExtendedVehicle()))
    }

    @Test
    fun `no world manufacturer identifier is claimed by two marques`() {
        val claims = Marque.entries.flatMap { marque -> marque.wmi.map { it to marque } }

        assertEquals(claims.size, claims.map { it.first }.distinct().size)
        // Maubeuge builds for Renault and for Nissan, and is therefore claimed by neither:
        // a VIN that could be either settles nothing.
        assertNull(Marque.of("VNV"))
    }

    @Test
    fun `the diesel-only readings wait for the driver to say it is a diesel`() {
        val dieselOnly = setOf(
            ExtendedPids.DPF_SOOT_MEASURED,
            ExtendedPids.DPF_SOOT_CALCULATED,
            ExtendedPids.DPF_ASH_MASS,
            ExtendedPids.DPF_DISTANCE_SINCE_REGEN,
            ExtendedPids.DPF_REGEN_INTERRUPTIONS,
            ExtendedPids.DPF_INLET_TEMPERATURE,
            ExtendedPids.DPF_OUTLET_TEMPERATURE,
            ExtendedPids.injectionDeviationId(1),
        )

        assertTrue(ExtendedPids.candidatesFor(golfDiesel).map(ExtendedPid::id).containsAll(dieselOnly))
        listOf(golfPetrol, golfUnknownFuel).forEach { car ->
            val offered = ExtendedPids.candidatesFor(car).map(ExtendedPid::id).toSet()
            assertEquals(emptySet<String>(), offered intersect dieselOnly)
            // The readings that are nothing to do with the fuel are still offered.
            assertTrue(ExtendedPids.OIL_TEMPERATURE in offered)
            assertTrue(ExtendedPids.BATTERY_SOC in offered)
        }
    }

    @Test
    fun `nothing in the table needs a diagnostic session opened first`() {
        // The research proposes `10 03` to reach a further set of identifiers — half of the
        // group's filter data, all of the Fiat and Jeep engine data. This app does not send
        // `10` to a running car, so those identifiers are absent rather than shipped
        // half-working, and this is the test that keeps them absent.
        val sessionGated = setOf(0x114E, 0x114F, 0x1044, 0x14F5, 0x1156, 0x1153, 0x18E4, 0x18E2)

        assertEquals(
            emptySet<Int>(),
            ExtendedPids.entries.map(ExtendedPid::did).toSet() intersect sessionGated,
        )
        assertTrue(
            ExtendedPids.entries.all {
                it.service == MODE_READ_DATA_BY_ID || it.service == MODE_READ_LOCAL_ID
            },
        )
        assertTrue(ExtendedPids.entries.none { it.request.startsWith("10") })
    }

    @Test
    fun `every entry is eleven-bit CAN, with no prepended address byte`() {
        // BMW's tester-plus-extended-address framing and the twenty-nine-bit addressing
        // Honda and Fiat use are both absent by choice: the adapter flow here restores
        // `ATSH 7DF` afterwards, which is the eleven-bit broadcast address and nothing else.
        ExtendedPids.entries.forEach { pid ->
            assertEquals("${pid.id} at ${pid.header}", 3, pid.header.length)
            assertTrue(pid.header.all { it in "0123456789ABCDEF" })
            pid.receiveHeader?.let { assertEquals(3, it.length) }
        }
    }

    @Test
    fun `a marque's table can be the first thing the app ever touches`() {
        // The table of all entries is built by asking each marque's table for its own, so a
        // marque table that asked back would deadlock the two objects' initialisation — and
        // it would do it only when that table happened to be touched first, which is a
        // crash that depends on which screen the app opened on. Loaded fresh, in the order
        // that provokes it, each one has to stand up on its own.
        val classpath = System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .map { java.io.File(it).toURI().toURL() }

        listOf("VagPids", "ToyotaPids", "MazdaPids", "OpelPids").forEach { table ->
            java.net.URLClassLoader(classpath.toTypedArray(), null).use { loader ->
                val loaded = loader.loadClass("com.miskibin.obd2dashboard.obd.$table")
                val instance = loaded.getDeclaredField("INSTANCE").apply { isAccessible = true }
                val entries = loaded.getDeclaredMethod("getEntries")
                    .apply { isAccessible = true }
                    .invoke(instance.get(null)) as List<*>
                assertTrue("$table came up empty", entries.isNotEmpty())
            }
        }
    }

    @Test
    fun `two marques reporting one reading agree on what it is measured in`() {
        // The catalogue takes a reading's unit and precision from whichever entry it meets
        // first, so a tyre pressure in bar on a Ford and in psi on a Mazda would show one
        // of them under the other's label.
        ExtendedPids.entries.groupBy(ExtendedPid::id).forEach { (id, variants) ->
            assertEquals("$id disagrees about its unit", 1, variants.map { it.unit }.distinct().size)
            assertEquals(
                "$id disagrees about its precision",
                1,
                variants.map { it.decimals }.distinct().size,
            )
        }
    }

    @Test
    fun `a local identifier is one byte on the wire and two hex digits in the request`() {
        val local = ExtendedPids.entries.filter { it.service == MODE_READ_LOCAL_ID }

        assertTrue(local.isNotEmpty())
        local.forEach {
            assertEquals("${it.id}: ${it.request}", 4, it.request.length)
            assertEquals(1, it.didBytes.size)
            assertTrue(it.did <= 0xFF)
        }
        assertTrue(ExtendedPids.entries.filter { it.service == MODE_READ_DATA_BY_ID }.all {
            it.request.length == 6 && it.didBytes.size == 2
        })
    }
}
