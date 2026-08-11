package com.miskibin.obd2dashboard.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class GattProfilesTest {

    @Test
    fun `resolves FFF0 by its documented characteristics`() {
        val resolved = ElmGattProfiles.resolve(
            listOf(
                service(
                    "fff0",
                    notify("fff1"),
                    write("fff2"),
                ),
            ),
        )

        assertEquals(short("fff1"), resolved?.notify)
        assertEquals(short("fff2"), resolved?.write)
    }

    /**
     * The single most common bug in ELM327 BLE code is assuming the lower UUID writes.
     * If the properties are swapped, the resolution has to swap with them.
     */
    @Test
    fun `follows the properties rather than the numeric order`() {
        val resolved = ElmGattProfiles.resolve(
            listOf(
                service(
                    "fff0",
                    write("fff1"),
                    notify("fff2"),
                ),
            ),
        )

        assertEquals(short("fff2"), resolved?.notify)
        assertEquals(short("fff1"), resolved?.write)
    }

    @Test
    fun `resolves the Vgate 18F0 layout`() {
        val resolved = ElmGattProfiles.resolve(
            listOf(service("18f0", notify("2af0"), write("2af1"))),
        )

        assertEquals(short("2af0"), resolved?.notify)
        assertEquals(short("2af1"), resolved?.write)
    }

    @Test
    fun `uses one characteristic for both directions on HM-10`() {
        val resolved = ElmGattProfiles.resolve(
            listOf(service("ffe0", both("ffe1"))),
        )

        assertEquals(short("ffe1"), resolved?.notify)
        assertEquals(short("ffe1"), resolved?.write)
    }

    @Test
    fun `resolves Microchip transparent UART`() {
        val resolved = ElmGattProfiles.resolve(listOf(microchipService()))

        assertEquals("Microchip transparent UART", resolved?.label)
        assertEquals(MICROCHIP_NOTIFY, resolved?.notify)
        assertEquals(MICROCHIP_WRITE, resolved?.write)
    }

    /**
     * Microchip's *notify* characteristic also declares WRITE. Preferring a single
     * dual-role characteristic — which the resolver used to do for every profile — sends
     * every command into a characteristic the chip never reads, and the adapter simply
     * never answers.
     */
    @Test
    fun `never writes into the Microchip notify characteristic`() {
        val resolved = ElmGattProfiles.resolve(
            listOf(
                GattServiceInfo(
                    MICROCHIP_SERVICE,
                    listOf(
                        GattCharacteristicInfo(MICROCHIP_NOTIFY, canNotify = true, canWrite = true),
                        GattCharacteristicInfo(MICROCHIP_WRITE, canNotify = false, canWrite = true),
                    ),
                ),
            ),
        )

        assertEquals(MICROCHIP_NOTIFY, resolved?.notify)
        assertEquals(MICROCHIP_WRITE, resolved?.write)
        assertNotEquals(resolved?.notify, resolved?.write)
    }

    @Test
    fun `resolves Telit TIO`() {
        val resolved = ElmGattProfiles.resolve(
            listOf(
                GattServiceInfo(
                    short("fefb"),
                    listOf(
                        GattCharacteristicInfo(TELIT_NOTIFY, canNotify = true, canWrite = false),
                        GattCharacteristicInfo(TELIT_WRITE, canNotify = false, canWrite = true),
                    ),
                ),
            ),
        )

        assertEquals("Telit TIO", resolved?.label)
        assertEquals(TELIT_NOTIFY, resolved?.notify)
        assertEquals(TELIT_WRITE, resolved?.write)
    }

    @Test
    fun `resolves Nordic UART`() {
        val resolved = ElmGattProfiles.resolve(
            listOf(
                GattServiceInfo(
                    UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                    listOf(
                        GattCharacteristicInfo(
                            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
                            canNotify = true,
                            canWrite = false,
                        ),
                        GattCharacteristicInfo(
                            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                            canNotify = false,
                            canWrite = true,
                        ),
                    ),
                ),
            ),
        )

        assertEquals("Nordic UART", resolved?.label)
    }

    @Test
    fun `falls back to the properties inside a known service`() {
        val resolved = ElmGattProfiles.resolve(
            listOf(service("fff0", notify("abcd"), write("dcba"))),
        )

        assertTrue(resolved?.label?.contains("by properties") == true)
        assertEquals(short("abcd"), resolved?.notify)
        assertEquals(short("dcba"), resolved?.write)
    }

    @Test
    fun `takes an unknown service apart by properties when nothing else matches`() {
        val resolved = ElmGattProfiles.resolve(
            listOf(service("1234", notify("1235"), write("1236"))),
        )

        assertEquals("generic", resolved?.label)
        assertEquals(short("1235"), resolved?.notify)
        assertEquals(short("1236"), resolved?.write)
    }

    /**
     * A notify characteristic in one service and a write characteristic in another are not
     * a serial port. Pairing them produces a link that connects and then never answers,
     * which is the hardest failure of all to read.
     */
    @Test
    fun `never pairs characteristics from two different services`() {
        val resolved = ElmGattProfiles.resolve(
            listOf(
                service("1234", notify("1235")),
                service("5678", write("5679")),
            ),
        )

        assertNull(resolved)
    }

    @Test
    fun `ignores the standard services when guessing`() {
        val resolved = ElmGattProfiles.resolve(
            listOf(
                service("1800", notify("2a05"), write("2a06")),
                service("180f", notify("2a19"), write("2a1a")),
            ),
        )

        assertNull(resolved)
    }

    @Test
    fun `returns nothing when no service offers both directions`() {
        assertNull(ElmGattProfiles.resolve(emptyList()))
        assertNull(ElmGattProfiles.resolve(listOf(service("1234", notify("1235")))))
    }

    @Test
    fun `advertises every serial service it knows how to drive`() {
        assertTrue(short("fff0") in ElmGattProfiles.serviceUuids)
        assertTrue(short("18f0") in ElmGattProfiles.serviceUuids)
        assertTrue(short("ffe0") in ElmGattProfiles.serviceUuids)
        assertTrue(short("fefb") in ElmGattProfiles.serviceUuids)
        assertTrue(MICROCHIP_SERVICE in ElmGattProfiles.serviceUuids)
    }

    private fun microchipService() = GattServiceInfo(
        MICROCHIP_SERVICE,
        listOf(
            GattCharacteristicInfo(MICROCHIP_NOTIFY, canNotify = true, canWrite = false),
            GattCharacteristicInfo(MICROCHIP_WRITE, canNotify = false, canWrite = true),
        ),
    )

    private fun service(uuid: String, vararg characteristics: GattCharacteristicInfo) =
        GattServiceInfo(short(uuid), characteristics.toList())

    private fun notify(uuid: String) =
        GattCharacteristicInfo(short(uuid), canNotify = true, canWrite = false)

    private fun write(uuid: String) =
        GattCharacteristicInfo(short(uuid), canNotify = false, canWrite = true)

    private fun both(uuid: String) =
        GattCharacteristicInfo(short(uuid), canNotify = true, canWrite = true)

    private fun short(uuid: String): UUID =
        UUID.fromString("0000$uuid-0000-1000-8000-00805f9b34fb")

    private companion object {
        val MICROCHIP_SERVICE: UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
        val MICROCHIP_NOTIFY: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
        val MICROCHIP_WRITE: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
        val TELIT_NOTIFY: UUID = UUID.fromString("00000002-0000-1000-8000-008025000000")
        val TELIT_WRITE: UUID = UUID.fromString("00000001-0000-1000-8000-008025000000")
    }
}
