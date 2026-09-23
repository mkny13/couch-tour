package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping and logic tests for Google TV Now Playing & playback (#184, Part 3 of #9):
 * progress calculations, track subtitles, and continue listening history mapping.
 */
class TvNowPlayingTest {

    // ---------------------------------------------------- tvProgressFraction

    @Test
    fun `progress fraction returns zero when duration is zero or negative`() {
        assertEquals(0f, tvProgressFraction(positionMs = 1000L, durationMs = 0L), 0.001f)
        assertEquals(0f, tvProgressFraction(positionMs = 1000L, durationMs = -100L), 0.001f)
    }

    @Test
    fun `progress fraction returns zero when position is zero or negative`() {
        assertEquals(0f, tvProgressFraction(positionMs = 0L, durationMs = 60_000L), 0.001f)
        assertEquals(0f, tvProgressFraction(positionMs = -500L, durationMs = 60_000L), 0.001f)
    }

    @Test
    fun `progress fraction computes exact fraction during playback`() {
        assertEquals(0.5f, tvProgressFraction(positionMs = 30_000L, durationMs = 60_000L), 0.001f)
        assertEquals(0.25f, tvProgressFraction(positionMs = 15_000L, durationMs = 60_000L), 0.001f)
    }

    @Test
    fun `progress fraction coerces to 1 when position exceeds duration`() {
        assertEquals(1.0f, tvProgressFraction(positionMs = 70_000L, durationMs = 60_000L), 0.001f)
    }

    // ------------------------------------------------- tvFormatTrackSubtitle

    @Test
    fun `track subtitle joins artist, date, and venue when all present`() {
        val result = tvFormatTrackSubtitle(
            artistName = "Phish",
            showDate = "1997-11-17",
            venueName = "McNichols Sports Arena",
        )
        assertEquals("Phish · 1997-11-17 · McNichols Sports Arena", result)
    }

    @Test
    fun `track subtitle omits missing venue gracefully`() {
        val result = tvFormatTrackSubtitle(
            artistName = "Phish",
            showDate = "1997-11-17",
            venueName = "",
        )
        assertEquals("Phish · 1997-11-17", result)
    }

    @Test
    fun `track subtitle omits missing date gracefully`() {
        val result = tvFormatTrackSubtitle(
            artistName = "Phish",
            showDate = "",
            venueName = "Madison Square Garden",
        )
        assertEquals("Phish · Madison Square Garden", result)
    }

    @Test
    fun `track subtitle returns empty string when all components blank`() {
        val result = tvFormatTrackSubtitle("", "", "")
        assertEquals("", result)
    }

    // ----------------------------------------------- tvContinueListeningItems

    private fun progress(
        queueKey: String,
        title: String = queueKey.removePrefix("show:"),
        subtitle: String = "Denver, CO",
        trackTitle: String = "Ghost",
        finished: Boolean = false,
        deletedAt: Long? = null,
    ) = Progress(
        queueKey = queueKey,
        title = title,
        subtitle = subtitle,
        artUrl = null,
        trackIndex = 2,
        positionMs = 45_000L,
        trackTitle = trackTitle,
        updatedAt = 1000L,
        finished = finished,
        deletedAt = deletedAt,
    )

    @Test
    fun `continue listening excludes finished and deleted items`() {
        val list = listOf(
            progress("show:1997-11-17", finished = false),
            progress("show:1998-10-31", finished = true),
            progress("show:1999-12-31", deletedAt = 2000L),
            progress("show:2000-06-14", finished = false),
        )
        val items = tvContinueListeningItems(list)
        assertEquals(listOf("1997-11-17", "2000-06-14"), items.map { it.title })
    }

    @Test
    fun `continue listening formats subtitle with track title when present`() {
        val list = listOf(
            progress("show:1997-11-17", title = "1997-11-17", subtitle = "Denver, CO", trackTitle = "Ghost"),
        )
        val items = tvContinueListeningItems(list)
        assertEquals("Ghost · Denver, CO", items.single().subtitle)
    }

    @Test
    fun `continue listening falls back to show subtitle when track title is blank`() {
        val list = listOf(
            progress("show:1997-11-17", title = "1997-11-17", subtitle = "Denver, CO", trackTitle = ""),
        )
        val items = tvContinueListeningItems(list)
        assertEquals("Denver, CO", items.single().subtitle)
    }
}
