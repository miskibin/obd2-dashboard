package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.ExtendedProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app remembers about which cars answer which manufacturer identifiers. */
class ExtendedSupportTest {

    private val mazda = "JM1BPBLM7K1234567"
    private val golf = "WVWZZZ1KZ8W123456"

    @Test
    fun `both verdicts that cannot change survive a round trip`() {
        val known = mapOf(
            ExtendedSupport.key(mazda, "oil") to ExtendedProbe.Supported,
            ExtendedSupport.key(mazda, "atf") to ExtendedProbe.Absent,
        )

        assertEquals(known, ExtendedSupport.decode(ExtendedSupport.encode(known)))
    }

    @Test
    fun `a verdict that could change is not written down`() {
        assertFalse(ExtendedSupport.isDurable(ExtendedProbe.Retry))
        assertFalse(ExtendedSupport.isDurable(ExtendedProbe.Unknown))
        assertTrue(ExtendedSupport.isDurable(ExtendedProbe.Supported))
        assertTrue(ExtendedSupport.isDurable(ExtendedProbe.Absent))

        // A module that was busy has said nothing about the car; writing that off as
        // "absent" would lose the parameter for good over one badly timed request.
        val remembered = ExtendedSupport.remember(emptyMap(), mazda, "oil", ExtendedProbe.Retry)
        assertEquals(emptyMap<String, ExtendedProbe>(), remembered)
    }

    @Test
    fun `what one car answered says nothing about another`() {
        var known = ExtendedSupport.remember(emptyMap(), mazda, "oil", ExtendedProbe.Supported)
        known = ExtendedSupport.remember(known, golf, "oil", ExtendedProbe.Absent)

        assertEquals(mapOf("oil" to ExtendedProbe.Supported), ExtendedSupport.forVin(known, mazda))
        assertEquals(mapOf("oil" to ExtendedProbe.Absent), ExtendedSupport.forVin(known, golf))
        assertEquals(emptyMap<String, ExtendedProbe>(), ExtendedSupport.forVin(known, vin = null))
    }

    @Test
    fun `storage it cannot read is ignored`() {
        assertEquals(emptyMap<String, ExtendedProbe>(), ExtendedSupport.decode(null))
        assertEquals(emptyMap<String, ExtendedProbe>(), ExtendedSupport.decode("garbage"))
        assertEquals(emptyMap<String, ExtendedProbe>(), ExtendedSupport.decode("a/b=7"))
    }

    @Test
    fun `the simulation's answers are kept away from the real garage`() {
        assertNotEquals(
            ExtendedSupport.storageKey(SessionKind.Real),
            ExtendedSupport.storageKey(SessionKind.Demo),
        )
    }
}
