package com.miskibin.obd2dashboard.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattStatusTest {

    @Test
    fun `names the statuses a failing dongle actually produces`() {
        assertTrue(GattStatus.name(133).startsWith("GATT_ERROR"))
        assertTrue(GattStatus.name(8).startsWith("GATT_CONN_TIMEOUT"))
        assertTrue(GattStatus.name(19).startsWith("GATT_CONN_TERMINATE_PEER_USER"))
        assertTrue(GattStatus.name(22).startsWith("GATT_CONN_TERMINATE_LOCAL_HOST"))
        assertTrue(GattStatus.name(147).startsWith("GATT_NO_RESOURCES"))
    }

    @Test
    fun `always carries the raw number as well as the name`() {
        assertTrue(GattStatus.name(133).contains("(133)"))
        assertEquals("GATT_UNKNOWN (4242)", GattStatus.name(4242))
    }

    @Test
    fun `recognises the statuses that mean a bond is missing`() {
        assertTrue(GattStatus.needsBonding(5))
        assertTrue(GattStatus.needsBonding(15))
        assertTrue(GattStatus.needsBonding(137))
        assertFalse(GattStatus.needsBonding(0))
        assertFalse(GattStatus.needsBonding(133))
    }

    @Test
    fun `names connection states, bond states and device types`() {
        assertEquals("CONNECTED", GattStatus.stateName(2))
        assertEquals("DISCONNECTED", GattStatus.stateName(0))
        assertEquals("BOND_BONDED", GattStatus.bondStateName(12))
        assertEquals("CLASSIC", GattStatus.deviceTypeName(1))
        assertEquals("LE", GattStatus.deviceTypeName(2))
        assertEquals("DUAL", GattStatus.deviceTypeName(3))
    }

    @Test
    fun `names the scan failure codes`() {
        assertEquals("SCAN_FAILED_ALREADY_STARTED", GattStatus.scanFailureName(1))
        assertEquals("SCAN_FAILED_SCANNING_TOO_FREQUENTLY", GattStatus.scanFailureName(6))
    }
}
