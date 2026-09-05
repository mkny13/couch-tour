package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `formats sub-minute durations`() {
        assertEquals("0:00", fmt(0))
        assertEquals("0:01", fmt(1_000))
        assertEquals("0:09", fmt(9_999))
        assertEquals("0:59", fmt(59_999))
    }

    @Test
    fun `pads seconds to two digits`() {
        assertEquals("1:00", fmt(60_000))
        assertEquals("1:05", fmt(65_000))
        assertEquals("12:46", fmt(766_000))
    }

    @Test
    fun `switches to hours only at the hour boundary`() {
        assertEquals("59:59", fmt(3_599_999))
        assertEquals("1:00:00", fmt(3_600_000))
        // A real show length, from 1997-02-13.
        assertEquals("3:04:16", fmt(11_056_537))
    }

    @Test
    fun `treats negative durations as zero rather than printing a negative clock`() {
        assertEquals("0:00", fmt(-1))
        assertEquals("0:00", fmt(-500_000))
    }

    @Test
    fun `pluralises only when the count is not one`() {
        assertEquals("show", plural(1, "show"))
        assertEquals("shows", plural(0, "show"))
        assertEquals("shows", plural(2, "show"))
        assertEquals("tracks", plural(99, "track"))
    }

    @Test
    fun `maps a scrubber touch to a track position`() {
        assertEquals(0L, positionAt(0f, 1000, 60_000))
        assertEquals(30_000L, positionAt(500f, 1000, 60_000))
        assertEquals(60_000L, positionAt(1000f, 1000, 60_000))
    }

    @Test
    fun `clamps scrubber touches that land outside the widget`() {
        assertEquals(0L, positionAt(-250f, 1000, 60_000))
        assertEquals(60_000L, positionAt(1500f, 1000, 60_000))
    }

    @Test
    fun `returns zero rather than dividing by zero before layout`() {
        // Both happen for real: width is 0 until measured, duration is 0 until prepared.
        assertEquals(0L, positionAt(120f, 0, 60_000))
        assertEquals(0L, positionAt(120f, 1000, 0))
    }

    @Test
    fun `formats compact duration sub-hour and hour-plus`() {
        assertEquals("0:00", formatCompactDuration(0))
        assertEquals("1:06", formatCompactDuration(66_000))
        assertEquals("2:41", formatCompactDuration(161_000))
        assertEquals("1:05", formatCompactDuration(3_930_000))
    }
}
