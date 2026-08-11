package com.miskibin.obd2dashboard.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AdapterHeuristicsTest {

    @Test
    fun `matches the names dongles actually ship with`() {
        listOf(
            "OBDII",
            "obdii",
            "V-LINK",
            "Android-VLink",
            "IOS-Vlink",
            "vLinker FS",
            "ELM327-BT",
            "Vgate iCar Pro",
            "KONNWEI KW902",
        ).forEach { name ->
            assertTrue(name, AdapterHeuristics.looksLikeAdapter(name))
        }
    }

    @Test
    fun `does not match the rest of the car park`() {
        listOf(
            "Galaxy Buds",
            "Mi Band 5",
            "Tile",
            "JBL GO",
            null,
            "",
            "   ",
        ).forEach { name ->
            assertFalse(name.orEmpty(), AdapterHeuristics.looksLikeAdapter(name))
        }
    }

    /**
     * The adapter this app was written for advertises no name at all on its LE side. The
     * advertised service is the only thing that distinguishes it from a beacon.
     */
    @Test
    fun `matches a nameless device that advertises a serial service`() {
        assertTrue(
            AdapterHeuristics.looksLikeAdapter(null, listOf(uuid16("fff0"))),
        )
        assertTrue(
            AdapterHeuristics.looksLikeAdapter("", listOf(uuid16("18f0"))),
        )
        assertTrue(
            AdapterHeuristics.looksLikeAdapter(
                null,
                listOf(UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")),
            ),
        )
    }

    @Test
    fun `does not match a nameless device advertising something else`() {
        assertFalse(
            AdapterHeuristics.looksLikeAdapter(null, listOf(uuid16("180d"), uuid16("180f"))),
        )
    }

    private fun uuid16(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
}
