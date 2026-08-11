package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.Reading
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {

    private var now = 0L

    private val coolant = AlertRule(
        id = "coolant",
        metric = MetricId.Sensor(Pids.COOLANT_TEMP),
        comparison = AlertComparison.Above,
        threshold = 105.0,
    )

    private val voltage = AlertRule(
        id = "voltage",
        metric = MetricId.Battery,
        comparison = AlertComparison.Below,
        threshold = 12.0,
        requiresEngineRunning = true,
    )

    private fun evaluator() = AlertEvaluator(clock = { now })

    private fun snapshot(
        coolantC: Double? = null,
        volts: Double? = null,
        rpm: Double? = null,
    ): VehicleSnapshot {
        val readings = buildMap {
            coolantC?.let { put(Pids.COOLANT_TEMP, Reading(Pids.COOLANT_TEMP, "coolant", "°C", it, now)) }
            rpm?.let { put(Pids.ENGINE_RPM, Reading(Pids.ENGINE_RPM, "rpm", "rpm", it, now)) }
        }
        return VehicleSnapshot(readings = readings, batteryVoltage = volts)
    }

    @Test
    fun `fires once when the value crosses the threshold`() {
        val evaluator = evaluator()

        assertTrue(evaluator.evaluate(listOf(coolant), snapshot(coolantC = 98.0)).isEmpty())

        val fired = evaluator.evaluate(listOf(coolant), snapshot(coolantC = 108.0))

        assertEquals(1, fired.size)
        assertEquals(coolant.id, fired.first().rule.id)
        assertEquals(108.0, fired.first().value, 0.001)
    }

    @Test
    fun `a value that stays breached does not fire again`() {
        val evaluator = evaluator()
        evaluator.evaluate(listOf(coolant), snapshot(coolantC = 108.0))

        // The polling loop pushes hundreds of these a minute.
        repeat(200) {
            now += 1_000
            assertTrue(evaluator.evaluate(listOf(coolant), snapshot(coolantC = 110.0)).isEmpty())
        }
    }

    @Test
    fun `re-arming needs the value back inside the margin, not just under the line`() {
        val evaluator = evaluator()
        evaluator.evaluate(listOf(coolant), snapshot(coolantC = 108.0))
        now += AlertEvaluator.MIN_INTERVAL_MILLIS

        // 104 is under 105 but inside the 2 % margin, so the rule stays disarmed.
        assertTrue(evaluator.evaluate(listOf(coolant), snapshot(coolantC = 104.0)).isEmpty())
        assertTrue(evaluator.evaluate(listOf(coolant), snapshot(coolantC = 106.0)).isEmpty())

        // 102 is below 105 - 2.1, which re-arms it.
        assertTrue(evaluator.evaluate(listOf(coolant), snapshot(coolantC = 102.0)).isEmpty())
        assertEquals(1, evaluator.evaluate(listOf(coolant), snapshot(coolantC = 106.0)).size)
    }

    @Test
    fun `a recovered rule still waits out the five-minute interval`() {
        val evaluator = evaluator()
        evaluator.evaluate(listOf(coolant), snapshot(coolantC = 108.0))

        now += 60_000
        evaluator.evaluate(listOf(coolant), snapshot(coolantC = 90.0))
        assertTrue(evaluator.evaluate(listOf(coolant), snapshot(coolantC = 108.0)).isEmpty())

        now += AlertEvaluator.MIN_INTERVAL_MILLIS
        evaluator.evaluate(listOf(coolant), snapshot(coolantC = 90.0))
        assertEquals(1, evaluator.evaluate(listOf(coolant), snapshot(coolantC = 108.0)).size)
    }

    @Test
    fun `low voltage is only an alert once the engine is turning`() {
        val evaluator = evaluator()

        // Ignition on, engine off: 11.8 V is what a healthy battery reads.
        assertTrue(evaluator.evaluate(listOf(voltage), snapshot(volts = 11.8, rpm = 0.0)).isEmpty())
        assertTrue(evaluator.evaluate(listOf(voltage), snapshot(volts = 11.8)).isEmpty())

        assertEquals(1, evaluator.evaluate(listOf(voltage), snapshot(volts = 11.8, rpm = 800.0)).size)
    }

    @Test
    fun `a disabled rule and a metric the car does not report stay quiet`() {
        val evaluator = evaluator()
        val off = coolant.copy(enabled = false)

        assertTrue(evaluator.evaluate(listOf(off), snapshot(coolantC = 130.0)).isEmpty())
        // Oil temperature is unsupported here, so it never reaches the snapshot.
        val oil = AlertRules.defaults.single { it.id == AlertRules.OIL_HIGH }
        assertTrue(evaluator.evaluate(listOf(oil), snapshot(coolantC = 130.0)).isEmpty())
    }

    @Test
    fun `several rules can trip on the same snapshot`() {
        val fired = evaluator().evaluate(
            listOf(coolant, voltage),
            snapshot(coolantC = 120.0, volts = 11.0, rpm = 900.0),
        )

        assertEquals(listOf("coolant", "voltage"), fired.map { it.rule.id })
    }

    @Test
    fun `defaults ship enabled and survive a storage round trip`() {
        val defaults = AlertRules.defaults

        assertEquals(3, defaults.size)
        assertTrue(defaults.all(AlertRule::enabled))
        assertEquals(105.0, defaults.single { it.id == AlertRules.COOLANT_HIGH }.threshold, 0.001)
        assertEquals(12.0, defaults.single { it.id == AlertRules.VOLTAGE_LOW }.threshold, 0.001)
        assertEquals(130.0, defaults.single { it.id == AlertRules.OIL_HIGH }.threshold, 0.001)

        val edited = defaults.map {
            if (it.id == AlertRules.COOLANT_HIGH) it.copy(threshold = 99.5, enabled = false) else it
        }

        assertEquals(edited, AlertRules.decode(AlertRules.encode(edited)))
    }

    @Test
    fun `unreadable storage falls back to the shipped rules`() {
        assertEquals(AlertRules.defaults, AlertRules.decode(null))
        assertEquals(AlertRules.defaults, AlertRules.decode(""))
        assertEquals(AlertRules.defaults, AlertRules.decode("nonsense"))
        // A rule that was dropped from a later build is simply ignored.
        assertEquals(AlertRules.defaults, AlertRules.decode("gone:true:1.0"))
    }
}
