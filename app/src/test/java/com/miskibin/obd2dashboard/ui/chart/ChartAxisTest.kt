package com.miskibin.obd2dashboard.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartAxisTest {

    @Test
    fun `nice step snaps up to 1, 2 or 5 times a power of ten`() {
        assertEquals(1.0, ChartAxis.niceStep(0.9), DELTA)
        assertEquals(2.0, ChartAxis.niceStep(1.4), DELTA)
        assertEquals(5.0, ChartAxis.niceStep(4.2), DELTA)
        assertEquals(10.0, ChartAxis.niceStep(7.7), DELTA)
        assertEquals(200.0, ChartAxis.niceStep(150.0), DELTA)
        assertEquals(0.05, ChartAxis.niceStep(0.031), DELTA)
    }

    @Test
    fun `nice step never returns zero or a negative for degenerate input`() {
        assertEquals(1.0, ChartAxis.niceStep(0.0), DELTA)
        assertEquals(1.0, ChartAxis.niceStep(-5.0), DELTA)
        assertEquals(1.0, ChartAxis.niceStep(Double.NaN), DELTA)
    }

    @Test
    fun `range is widened to whole steps`() {
        val ticks = ChartAxis.ticks(min = 812.0, max = 3_140.0, targetCount = 5)
        assertEquals(500.0, ticks.step, DELTA)
        assertEquals(500.0, ticks.min, DELTA)
        assertEquals(3_500.0, ticks.max, DELTA)
        assertEquals(listOf(500.0, 1_000.0, 1_500.0, 2_000.0, 2_500.0, 3_000.0, 3_500.0), ticks.values)
    }

    @Test
    fun `ticks are evenly spaced by exactly one step`() {
        val ticks = ChartAxis.ticks(min = -12.5, max = 47.0)
        ticks.values.zipWithNext { low, high -> assertEquals(ticks.step, high - low, DELTA) }
        assertTrue(ticks.values.first() <= -12.5)
        assertTrue(ticks.values.last() >= 47.0)
    }

    @Test
    fun `a constant series is padded so its line lands in the middle`() {
        val ticks = ChartAxis.ticks(min = 88.0, max = 88.0)
        assertTrue(ticks.span > 0.0)
        assertEquals(0.5, ticks.fraction(88.0), 0.2)
    }

    @Test
    fun `a constant zero series still produces a usable range`() {
        val ticks = ChartAxis.ticks(min = 0.0, max = 0.0)
        assertTrue(ticks.span > 0.0)
        assertTrue(ticks.values.size >= 2)
    }

    @Test
    fun `no data falls back to a zero to one axis`() {
        val ticks = ChartAxis.ticksOf(emptyList())
        assertEquals(0.0, ticks.min, DELTA)
        assertEquals(1.0, ticks.max, DELTA)
    }

    @Test
    fun `a single point produces a padded range around it`() {
        val ticks = ChartAxis.ticksOf(listOf(42f))
        assertTrue(ticks.min <= 42.0)
        assertTrue(ticks.max >= 42.0)
        assertTrue(ticks.span > 0.0)
    }

    @Test
    fun `non-finite samples are ignored rather than poisoning the axis`() {
        val ticks = ChartAxis.ticksOf(listOf(10f, Float.NaN, 20f, Float.POSITIVE_INFINITY))
        assertTrue(ticks.min <= 10.0)
        assertTrue(ticks.max >= 20.0)
        assertTrue(ticks.max.isFinite())
    }

    @Test
    fun `fraction maps the axis ends to zero and one`() {
        val ticks = ChartAxis.ticks(0.0, 100.0)
        assertEquals(0.0, ticks.fraction(ticks.min), DELTA)
        assertEquals(1.0, ticks.fraction(ticks.max), DELTA)
    }

    @Test
    fun `label decimals follow the step size`() {
        assertEquals(0, ChartAxis.labelDecimals(500.0))
        assertEquals(0, ChartAxis.labelDecimals(1.0))
        assertEquals(1, ChartAxis.labelDecimals(0.5))
        assertEquals(2, ChartAxis.labelDecimals(0.05))
        assertEquals(0, ChartAxis.labelDecimals(0.0))
    }

    private companion object {
        const val DELTA = 1e-9
    }
}
