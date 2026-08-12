package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GarageTest {

    private val golf = Vehicle(
        vin = "WVWZZZ1KZ8W123456",
        name = "Golf",
        fuel = FuelType.Diesel,
        displacementLitres = 1.9,
        ratedPowerKw = 77,
        kerbMassKg = 1385,
        tankLitres = 55,
    )

    @Test
    fun `survives a round trip`() {
        assertEquals(listOf(golf), Garage.decode(Garage.encode(listOf(golf))))
    }

    @Test
    fun `keeps unset fields unset`() {
        val bare = Vehicle(vin = "TMBJJ7NE0J0123456")
        val restored = Garage.decode(Garage.encode(listOf(bare))).single()
        assertEquals(bare, restored)
        assertNull(restored.fuel)
        assertTrue(restored.isBlank)
    }

    @Test
    fun `holds more than one car`() {
        val skoda = Vehicle(vin = "TMBJJ7NE0J0123456", fuel = FuelType.Petrol)
        assertEquals(listOf(golf, skoda), Garage.decode(Garage.encode(listOf(golf, skoda))))
    }

    /** A name is free text, and free text must not be able to end its own record. */
    @Test
    fun `strips the separators out of a name the driver typed`() {
        val awkward = golf.copy(name = "Golf | 1.9 : TDI")
        assertEquals("Golf  1.9  TDI", Garage.decode(Garage.encode(listOf(awkward))).single().name)
    }

    /**
     * The VIN is not typed — it is reassembled from raw ECU bytes and mapped straight to
     * characters, so a garbled `0902` reply can put a separator in it. Unguarded, that
     * splits the record and takes the rest of the garage with it.
     */
    @Test
    fun `a garbled VIN cannot split its own record`() {
        val garbled = Vehicle(vin = "WVW:ZZ1|Z8W123456", tankLitres = 55)
        val restored = Garage.decode(Garage.encode(listOf(garbled, golf)))

        assertEquals(2, restored.size)
        assertEquals("WVWZZ1Z8W123456", restored.first().vin)
        assertEquals(55, restored.first().tankLitres)
        assertEquals(golf, restored.last())
    }

    @Test
    fun `merge replaces the car with this VIN and leaves the rest alone`() {
        val skoda = Vehicle(vin = "TMBJJ7NE0J0123456")
        val updated = golf.copy(tankLitres = 60)

        val garage = Garage.merge(listOf(golf, skoda), updated)

        assertEquals(listOf(updated, skoda), garage)
    }

    @Test
    fun `merge appends a car the garage has not seen`() {
        val skoda = Vehicle(vin = "TMBJJ7NE0J0123456")
        assertEquals(listOf(golf, skoda), Garage.merge(listOf(golf), skoda))
    }

    /** A car with no profile yet gets a blank one to fill in, not nothing. */
    @Test
    fun `profileFor offers a blank profile for an unknown VIN`() {
        val profile = Garage.profileFor(listOf(golf), "TMBJJ7NE0J0123456")
        assertEquals(Vehicle(vin = "TMBJJ7NE0J0123456"), profile)
    }

    @Test
    fun `profileFor has nothing to offer without a VIN`() {
        assertNull(Garage.profileFor(listOf(golf), null))
    }

    /**
     * The simulation reports a plausible-looking VIN, and the car it names does not exist.
     * Sharing a store with the real garage would put it in the driver's garage and save
     * their edits against it.
     */
    @Test
    fun `files demo cars away from real ones`() {
        assertNotEquals(
            Garage.storageKey(SessionKind.Real),
            Garage.storageKey(SessionKind.Demo),
        )
    }

    /** An upgrading driver keeps the garage they had. */
    @Test
    fun `keeps the original key for real cars`() {
        assertEquals("vehicles", Garage.storageKey(SessionKind.Real))
    }

    @Test
    fun `decodes an empty or absent store as an empty garage`() {
        assertEquals(emptyList<Vehicle>(), Garage.decode(null))
        assertEquals(emptyList<Vehicle>(), Garage.decode(""))
    }

    /** A record written before a field existed reads as far as it goes. */
    @Test
    fun `tolerates a record shorter than the current format`() {
        val restored = Garage.decode("WVWZZZ1KZ8W123456:Golf:diesel").single()
        assertEquals("Golf", restored.name)
        assertEquals(FuelType.Diesel, restored.fuel)
        assertNull(restored.tankLitres)
    }
}
