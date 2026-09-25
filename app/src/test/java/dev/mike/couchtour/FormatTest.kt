package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    /**
     * Maps a horizontal touch on a scrubber of [widthPx] to a position in the track.
     * Clamped, so a drag past either edge lands on the start or the end rather than
     * seeking out of bounds.
     *
     * Lives here rather than in Format.kt because no production caller needs it yet
     * (#218 dead-code pass); the mapping stays pinned so it can be lifted back out
     * unchanged when a scrubber needs it.
     */
    private fun positionAt(x: Float, widthPx: Int, durationMs: Long): Long {
        if (widthPx <= 0 || durationMs <= 0) return 0
        return (x / widthPx.toFloat() * durationMs).toLong().coerceIn(0L, durationMs)
    }

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
        // Sub-hour: m:ss
        assertEquals("0:00", formatCompactDuration(0))
        assertEquals("0:45", formatCompactDuration(45_000))
        assertEquals("1:06", formatCompactDuration(66_000))
        assertEquals("2:41", formatCompactDuration(161_000))
        // Hour-plus: h:mm (seconds dropped for compactness in show/set headers, matching KDoc)
        assertEquals("1:05", formatCompactDuration(3_930_000)) // 1h 5m 30s -> "1:05"
        assertEquals("1:06", formatCompactDuration(66 * 60 * 1000L)) // 66m -> "1:06"
        assertEquals("1:35", formatCompactDuration(95 * 60 * 1000L)) // 95m -> "1:35"
        assertEquals("2:41", formatCompactDuration(161 * 60 * 1000L)) // 161m -> "2:41"
    }

    @Test
    fun `formatShowDate accepts inputs matching Format swift port`() {
        assertEquals("1997-11-17", formatShowDate("1997-11-17"))
        assertEquals("1997-11-17", formatShowDate("1997/11/17"))
        assertEquals("1997-05-08", formatShowDate("1997-5-8"))
        assertEquals("1997-05-08", formatShowDate("1997/5/8"))
        assertEquals("1977-05-08", formatShowDate("May 8, 1977"))
    }

    @Test
    fun `formatShowDate rejects out of range month and day`() {
        // Bounded to 1..12 and 1..31, matching Format.swift D271 behaviour
        assertEquals("2020/99/99", formatShowDate("2020/99/99"))
        assertEquals("2020/13/01", formatShowDate("2020/13/01"))
        assertEquals("2020/01/32", formatShowDate("2020/01/32"))
        assertEquals("2020/00/15", formatShowDate("2020/00/15"))
        assertEquals("2020/05/00", formatShowDate("2020/05/00"))
        assertEquals("2020-13-1", formatShowDate("2020-13-1"))
        assertEquals("2020-1-32", formatShowDate("2020-1-32"))
        assertEquals("2020-99-99", formatShowDate("2020-99-99"))
    }

    @Test
    fun `formats remaining time with left suffix matching Format swift port`() {
        assertEquals("7:32 left", formatRemainingTime(positionMs = 312_000, durationMs = 764_000))
        assertEquals("0:00 left", formatRemainingTime(positionMs = 800_000, durationMs = 764_000))
        assertEquals("0:00 left", formatRemainingTime(positionMs = 0, durationMs = 0))
    }
}
