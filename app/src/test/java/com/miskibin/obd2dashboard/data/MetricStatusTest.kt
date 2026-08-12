package com.miskibin.obd2dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the dashboard is allowed to say about a reading.
 *
 * The distinction that matters here is the second step: a value just outside its band is
 * the car being asked to work, and a value far outside is the car in trouble, and the two
 * are told apart by a fraction of the band's own width so the rule means the same on a
 * five-degree band as on a fifty-degree one.
 */
class MetricStatusTest {

    private val coolant = NormalBand(82.0, 98.0)

    @Test
    fun `inside the band is normal`() {
        assertEquals(MetricStatus.Normal, coolant.statusOf(90.0))
        assertEquals(MetricStatus.Normal, coolant.statusOf(82.0))
        assertEquals(MetricStatus.Normal, coolant.statusOf(98.0))
    }

    @Test
    fun `just outside the band is a breach, far outside is severe`() {
        // The band is sixteen wide, so the far threshold sits 2.88 past either end.
        assertEquals(MetricStatus.Above, coolant.statusOf(100.0))
        assertEquals(MetricStatus.FarAbove, coolant.statusOf(103.0))
        assertEquals(MetricStatus.Below, coolant.statusOf(80.0))
        assertEquals(MetricStatus.FarBelow, coolant.statusOf(78.0))
    }

    @Test
    fun `severity is a fraction of the band, not a fixed number of units`() {
        val trim = NormalBand(-10.0, 10.0)
        // Two percent past the end of a twenty-wide band is still only a breach…
        assertEquals(MetricStatus.Above, trim.statusOf(12.0))
        // …while the same two units past a one-wide band is far out.
        assertEquals(MetricStatus.FarAbove, NormalBand(0.9, 1.1).statusOf(3.0))
    }

    @Test
    fun `a band with one end can say out but not how far`() {
        val ceiling = NormalBand(null, 110.0)
        assertEquals(MetricStatus.Normal, ceiling.statusOf(90.0))
        assertEquals(MetricStatus.Above, ceiling.statusOf(111.0))
        assertEquals(MetricStatus.Above, ceiling.statusOf(400.0))
    }

    @Test
    fun `no band, no value and no number mean no verdict`() {
        assertNull(null.statusOf(90.0))
        assertNull(NormalBand(null, null).statusOf(90.0))
        assertNull(coolant.statusOf(null))
        assertNull(coolant.statusOf(Double.NaN))
    }

    @Test
    fun `levels answer what the tile asks of them`() {
        assertEquals(false, MetricStatus.Normal.breached)
        assertEquals(true, MetricStatus.Below.breached)
        assertEquals(false, MetricStatus.Below.severe)
        assertEquals(true, MetricStatus.FarBelow.severe)
    }

    /**
     * The driver's own alert threshold is folded into the band before the verdict, so a
     * rule firing and a tile tinting are the same event rather than two that can disagree.
     */
    @Test
    fun `the driver's rule moves the band the verdict is made against`() {
        val rules = listOf(
            AlertRule(
                id = "coolant",
                metric = Metrics.CoolantTemp,
                comparison = AlertComparison.Above,
                threshold = 92.0,
            ),
        )
        val band = Metrics.bandFor(Metrics.CoolantTemp, rules)
        assertEquals(MetricStatus.Normal, band.statusOf(91.0))
        assertEquals(MetricStatus.Above, band.statusOf(93.0))
    }
}
