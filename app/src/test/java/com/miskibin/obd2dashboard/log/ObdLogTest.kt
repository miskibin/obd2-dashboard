package com.miskibin.obd2dashboard.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ObdLogTest {

    @Before
    fun reset() {
        ObdLog.echo = null
        ObdLog.clear()
    }

    @After
    fun restore() {
        ObdLog.echo = null
        ObdLog.clock = System::currentTimeMillis
        ObdLog.clear()
    }

    @Test
    fun `keeps the newest entries and drops the oldest`() {
        repeat(ObdLog.CAPACITY + 50) { ObdLog.log(LogTag.BLE, "line $it") }

        val entries = ObdLog.entries()
        assertEquals(ObdLog.CAPACITY, entries.size)
        assertEquals("line 50", entries.first().message)
        assertEquals("line ${ObdLog.CAPACITY + 49}", entries.last().message)
    }

    @Test
    fun `records the tag and the time each line was written`() {
        ObdLog.clock = { 1_234L }
        ObdLog.log(LogTag.ELM, "ATE0 → OK")

        val entry = ObdLog.entries().single()
        assertEquals(LogTag.ELM, entry.tag)
        assertEquals(1_234L, entry.timeMillis)
        assertEquals("ATE0 → OK", entry.message)
    }

    @Test
    fun `formats a line with the time of day and the tag`() {
        // 12:04:31.882 UTC on the epoch day.
        ObdLog.clock = { 12 * 3_600_000L + 4 * 60_000L + 31_000L + 882L }
        ObdLog.log(LogTag.SPP, "connected")

        assertEquals("12:04:31.882 SPP   connected", ObdLog.dump())
    }

    @Test
    fun `joins the whole buffer for the share sheet`() {
        ObdLog.clock = { 0L }
        ObdLog.log(LogTag.SCAN, "one")
        ObdLog.log(LogTag.SCAN, "two")

        assertEquals(2, ObdLog.dump().lines().size)
        assertTrue(ObdLog.dump().endsWith("two"))
    }

    @Test
    fun `clear empties the buffer`() {
        ObdLog.log(LogTag.CONN, "something")
        ObdLog.clear()

        assertTrue(ObdLog.entries().isEmpty())
        assertEquals("", ObdLog.dump())
    }

    @Test
    fun `forwards every line to the echo`() {
        val echoed = mutableListOf<String>()
        ObdLog.echo = { echoed += it.message }

        ObdLog.log(LogTag.BLE, "first")
        ObdLog.log(LogTag.BLE, "second")

        assertEquals(listOf("first", "second"), echoed)
    }

    /**
     * GATT callbacks arrive on a binder thread, the session runs on a dispatcher and the
     * viewer reads from the main thread — so all three touch the buffer at once.
     */
    @Test
    fun `survives concurrent writers and readers`() {
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { thread ->
            pool.execute {
                start.await()
                repeat(perThread) {
                    ObdLog.log(LogTag.BLE, "$thread-$it")
                    ObdLog.entries()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(ObdLog.CAPACITY, ObdLog.entries().size)
    }
}
