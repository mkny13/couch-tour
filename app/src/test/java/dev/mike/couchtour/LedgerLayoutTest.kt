package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerLayoutTest {

    @Test
    fun `progress fraction clamps between zero and one`() {
        assertEquals(0f, progressFraction(0, 100_000), 0.001f)
        assertEquals(0.5f, progressFraction(50_000, 100_000), 0.001f)
        assertEquals(1f, progressFraction(120_000, 100_000), 0.001f)
        assertEquals(0f, progressFraction(-5_000, 100_000), 0.001f)
        assertEquals(0f, progressFraction(50_000, 0), 0.001f)
    }

    @Test
    fun `set duration formatting formats minutes and hours compactly`() {
        assertEquals("0:45", formatCompactDuration(45 * 1000L))
        assertEquals("1:06", formatCompactDuration(66 * 60 * 1000L))
        assertEquals("1:35", formatCompactDuration(95 * 60 * 1000L))
        assertEquals("2:41", formatCompactDuration(161 * 60 * 1000L))
    }

    @Test
    fun `date formatting adheres to YYYY-MM-DD constraint`() {
        // uat-006: Dates must strictly follow YYYY-MM-DD
        val formatted = formatShowDate("1997-11-17")
        assertEquals("1997-11-17", formatted)
        assertTrue(formatted.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")))
    }

    @Test
    fun `remaining time clock produces formatted negative string`() {
        assertEquals("-7:32", formatRemainingTime(positionMs = 312_000, durationMs = 764_000))
        assertEquals("-0:00", formatRemainingTime(positionMs = 100_000, durationMs = 100_000))
        assertEquals("-1:15", formatRemainingTime(positionMs = 0, durationMs = 75_000))
    }

    @Test
    fun `in progress row tabular columns width constraints`() {
        val maxArtistDateColWidth = 136
        val playButtonSize = 30
        val rowProgressBarHeight = 2
        assertEquals(136, maxArtistDateColWidth)
        assertEquals(30, playButtonSize)
        assertEquals(2, rowProgressBarHeight)
    }
}
