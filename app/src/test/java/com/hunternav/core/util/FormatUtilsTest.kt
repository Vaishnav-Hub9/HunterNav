package com.hunternav.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun `short distances render as whole meters`() {
        assertEquals("60 m", FormatUtils.distance(60.0))
        assertEquals("180 m", FormatUtils.distance(180.0))
        assertEquals("420 m", FormatUtils.distance(420.0))
        assertEquals("850 m", FormatUtils.distance(850.0))
    }

    @Test
    fun `sub-ten-meter distances show as less-than`() {
        assertEquals("<10 m", FormatUtils.distance(4.0))
    }

    @Test
    fun `kilometer distances round for readability`() {
        assertEquals("1.2 km", FormatUtils.distance(1234.0))
        assertEquals("4.8 km", FormatUtils.distance(4800.0))
        assertEquals("15 km", FormatUtils.distance(15_400.0))
    }

    @Test
    fun `imperial distances use feet and miles`() {
        val hundredMeters = FormatUtils.distance(100.0, metric = false)
        assertTrue(hundredMeters.endsWith(" ft"))
        val twoMiles = FormatUtils.distance(1609.344 * 2, metric = false)
        assertEquals("2.0 mi", twoMiles)
    }

    @Test
    fun `durations render minutes and hours`() {
        assertEquals("<1 min", FormatUtils.duration(45.0))
        assertEquals("1 min", FormatUtils.duration(60.0))
        assertEquals("17 min", FormatUtils.duration(1020.0))
        assertEquals("1 h 05 min", FormatUtils.duration(3900.0))
    }

    @Test
    fun `eta is a wall clock string`() {
        val eta = FormatUtils.eta(1800.0, nowEpochMs = 0L)
        assertTrue(Regex("""\d{2}:\d{2}""").matches(eta))
    }
}
