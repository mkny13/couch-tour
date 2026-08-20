package dev.mike.couchtour

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home screen's "Next Couch Tour stop" row (#22). Pure functions and a fake [MusicSource],
 * the same split [OnThisDateTest] uses — no network call needed to check tour derivation, the
 * unplayed filter, or the request-bounding logic.
 */
class NextStopTest {

    // ------------------------------------------------------------------ recentPeriods

    @Test
    fun `recentPeriods takes the latest two by label, most recent first`() {
        val periods = listOf(
            PeriodRef("uuid-2018", "2018"),
            PeriodRef("uuid-2020", "2020"),
            PeriodRef("uuid-2019", "2019"),
        )
        assertEquals(listOf(PeriodRef("uuid-2020", "2020"), PeriodRef("uuid-2019", "2019")), recentPeriods(periods))
    }

    @Test
    fun `recentPeriods drops a non-year label`() {
        val periods = listOf(PeriodRef(POPULAR_PERIOD_ID, POPULAR_PERIOD_LABEL), PeriodRef("2020", "2020"))
        assertEquals(listOf(PeriodRef("2020", "2020")), recentPeriods(periods))
    }

    @Test
    fun `recentPeriods returns fewer than count when there aren't enough`() {
        assertEquals(listOf(PeriodRef("2020", "2020")), recentPeriods(listOf(PeriodRef("2020", "2020"))))
    }

    // ------------------------------------------------------------------ currentTourShows

    @Test
    fun `currentTourShows picks the most recent show's tour`() {
        val shows = listOf(
            ShowSummary(artist = PHISH, date = "2025-07-01", tourName = "Summer Tour"),
            ShowSummary(artist = PHISH, date = "2025-07-05", tourName = "Summer Tour"),
            ShowSummary(artist = PHISH, date = "2025-03-01", tourName = "Spring Tour"),
        )
        assertEquals(
            listOf(
                ShowSummary(artist = PHISH, date = "2025-07-01", tourName = "Summer Tour"),
                ShowSummary(artist = PHISH, date = "2025-07-05", tourName = "Summer Tour"),
            ),
            currentTourShows(shows),
        )
    }

    @Test
    fun `currentTourShows includes a New Year run's December half`() {
        val shows = listOf(
            ShowSummary(artist = PHISH, date = "2024-12-30", tourName = "NYE Run"),
            ShowSummary(artist = PHISH, date = "2024-12-31", tourName = "NYE Run"),
            ShowSummary(artist = PHISH, date = "2025-01-01", tourName = "NYE Run"),
        )
        assertEquals(shows, currentTourShows(shows))
    }

    @Test
    fun `currentTourShows is empty when the latest show has no tour name`() {
        val shows = listOf(ShowSummary(artist = PHISH, date = "2025-07-01", tourName = null))
        assertTrue(currentTourShows(shows).isEmpty())
    }

    @Test
    fun `currentTourShows treats both backends' Not Part of a Tour sentinel as no tour`() {
        // Confirmed live against both APIs: neither leaves tourName blank for a standalone
        // show, they both send this literal string. Without filtering it out, an inactive
        // artist's whole untoured archive reads as one fake "tour" spanning years.
        val shows = listOf(
            ShowSummary(artist = PHISH, date = "2025-07-01", tourName = "Not Part of a Tour"),
            ShowSummary(artist = PHISH, date = "1994-02-25", tourName = "Not Part of a Tour"),
        )
        assertTrue(currentTourShows(shows).isEmpty())
    }

    @Test
    fun `currentTourShows is empty for no shows`() {
        assertTrue(currentTourShows(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------ showId / playedShowIds

    @Test
    fun `showId round-trips a phish_in show through its queue key`() {
        val show = ShowSummary(artist = PHISH, date = "1997-11-17")
        assertEquals(showQueueKey("1997-11-17"), showId(show))
    }

    @Test
    fun `playedShowIds matches a phish_in finished key`() {
        val played = playedShowIds(listOf(showQueueKey("1997-11-17")))
        assertEquals(setOf(showQueueKey("1997-11-17")), played)
    }

    @Test
    fun `playedShowIds matches any tape of a Relisten show, not just the one played`() {
        val goose = ArtistRef(Backend.RELISTEN, "goose", "Goose")
        val show = ShowSummary(artist = goose, date = "2023-05-06")
        // Played against tape "abc"; the identity that matters drops the tape id entirely.
        val played = playedShowIds(listOf(recordingQueueKey("goose", "2023-05-06", "abc")))
        assertEquals(setOf(showId(show)), played)
    }

    @Test
    fun `playedShowIds ignores playlist and local-playlist keys`() {
        val played = playedShowIds(listOf(playlistQueueKey("some-slug"), localPlaylistQueueKey("42")))
        assertTrue(played.isEmpty())
    }

    @Test
    fun `playedShowIds ignores a malformed key`() {
        assertTrue(playedShowIds(listOf("not-a-real-key")).isEmpty())
    }

    // ------------------------------------------------------------------ oldestUnplayed

    @Test
    fun `oldestUnplayed picks the single oldest candidate across artists`() {
        val goose = ArtistRef(Backend.RELISTEN, "goose", "Goose")
        val candidates = listOf(
            ShowSummary(artist = PHISH, date = "2025-07-05"),
            ShowSummary(artist = goose, date = "2025-06-01"),
            ShowSummary(artist = PHISH, date = "2025-08-01"),
        )
        assertEquals(ShowSummary(artist = goose, date = "2025-06-01"), oldestUnplayed(candidates, played = emptySet()))
    }

    @Test
    fun `oldestUnplayed skips shows with a finished progress row`() {
        val candidates = listOf(
            ShowSummary(artist = PHISH, date = "2025-06-01"),
            ShowSummary(artist = PHISH, date = "2025-07-05"),
        )
        val played = setOf(showId(candidates[0]))
        assertEquals(candidates[1], oldestUnplayed(candidates, played))
    }

    @Test
    fun `oldestUnplayed breaks a same-date tie by artist key`() {
        val a = ArtistRef(Backend.RELISTEN, "a-artist", "A")
        val z = ArtistRef(Backend.RELISTEN, "z-artist", "Z")
        val candidates = listOf(ShowSummary(artist = z, date = "2025-06-01"), ShowSummary(artist = a, date = "2025-06-01"))
        assertEquals(ShowSummary(artist = a, date = "2025-06-01"), oldestUnplayed(candidates, played = emptySet()))
    }

    @Test
    fun `oldestUnplayed is null when everything is played`() {
        val show = ShowSummary(artist = PHISH, date = "2025-06-01")
        assertNull(oldestUnplayed(listOf(show), played = setOf(showId(show))))
    }

    @Test
    fun `oldestUnplayed is null for no candidates`() {
        assertNull(oldestUnplayed(emptyList(), played = emptySet()))
    }

    // ------------------------------------------------------------------ currentTours

    private class FakeSource(
        private val backendId: Backend,
        private val periodsByArtist: Map<String, List<PeriodRef>>,
        private val showsByPeriod: Map<String, List<ShowSummary>>,
        private val failing: Set<String> = emptySet(),
    ) : MusicSource {
        override val backend = backendId
        override suspend fun artists() = emptyList<ArtistRef>()
        override suspend fun periods(artist: ArtistRef): List<PeriodRef> {
            if (artist.id in failing) error("boom")
            return periodsByArtist.getValue(artist.id)
        }
        override suspend fun shows(artist: ArtistRef, period: PeriodRef) = showsByPeriod.getValue(period.id)
        override suspend fun show(artist: ArtistRef, date: String, recordingId: String?) =
            error("not used by currentTours")
        override suspend fun search(term: String) = SearchHits()
    }

    @Test
    fun `currentTours finds a phish_in artist's current tour across its latest two periods`() = runBlocking {
        val source = FakeSource(
            Backend.PHISHIN,
            periodsByArtist = mapOf(
                "phish" to listOf(PeriodRef("2024", "2024"), PeriodRef("2025", "2025"), PeriodRef("2023", "2023")),
            ),
            showsByPeriod = mapOf(
                "2025" to listOf(ShowSummary(artist = PHISH, date = "2025-07-05", tourName = "Summer Tour")),
                "2024" to listOf(ShowSummary(artist = PHISH, date = "2024-12-31", tourName = "NYE Run")),
            ),
        )
        val result = currentTours(listOf(PHISH)) { source }
        assertEquals(listOf(ShowSummary(artist = PHISH, date = "2025-07-05", tourName = "Summer Tour")), result)
    }

    @Test
    fun `currentTours caps at three artists per backend`() = runBlocking {
        val artists = (1..5).map { ArtistRef(Backend.RELISTEN, "artist-$it", "Artist $it") }
        var periodsRequested = 0
        val source = object : MusicSource {
            override val backend = Backend.RELISTEN
            override suspend fun artists() = emptyList<ArtistRef>()
            override suspend fun periods(artist: ArtistRef): List<PeriodRef> {
                periodsRequested++
                return emptyList()
            }
            override suspend fun shows(artist: ArtistRef, period: PeriodRef) = emptyList<ShowSummary>()
            override suspend fun show(artist: ArtistRef, date: String, recordingId: String?) = error("unused")
            override suspend fun search(term: String) = SearchHits()
        }
        currentTours(artists) { source }
        assertEquals(MAX_TOUR_ARTISTS, periodsRequested)
    }

    @Test
    fun `currentTours ignores a favorited artist whose fetch fails`() = runBlocking {
        val ok = ArtistRef(Backend.RELISTEN, "ok", "Ok Artist")
        val bad = ArtistRef(Backend.RELISTEN, "bad", "Bad Artist")
        val period = PeriodRef("uuid-2025", "2025")
        val source = FakeSource(
            Backend.RELISTEN,
            periodsByArtist = mapOf("ok" to listOf(period)),
            showsByPeriod = mapOf(
                "uuid-2025" to listOf(ShowSummary(artist = ok, date = "2025-07-05", tourName = "Fall Tour")),
            ),
            failing = setOf("bad"),
        )
        val result = currentTours(listOf(ok, bad)) { source }
        assertEquals(listOf(ShowSummary(artist = ok, date = "2025-07-05", tourName = "Fall Tour")), result)
    }

    @Test
    fun `currentTours returns empty for no favorites`() = runBlocking {
        val result = currentTours(emptyList()) { error("not used") }
        assertEquals(emptyList<ShowSummary>(), result)
    }

    // ------------------------------------------------------------------ NextStop cache

    @Test
    fun `NextStop cache key changes with the date and the favorite set`() {
        val a = ArtistRef(Backend.RELISTEN, "a", "A")
        val b = ArtistRef(Backend.RELISTEN, "b", "B")
        val key1 = NextStop.cacheKey(listOf(a), "2026-11-17")
        val key2 = NextStop.cacheKey(listOf(a), "2026-11-18")
        val key3 = NextStop.cacheKey(listOf(a, b), "2026-11-17")
        assertTrue(key1 != key2)
        assertTrue(key1 != key3)
        // Order-independent: the same favorite set produces the same key regardless of order.
        assertEquals(NextStop.cacheKey(listOf(a, b), "2026-11-17"), NextStop.cacheKey(listOf(b, a), "2026-11-17"))
    }

    @Test
    fun `NextStop load returns empty without hitting the network for no favorites`() = runBlocking {
        NextStop.cached = null
        val result = NextStop.load(emptyList(), today = "2026-11-17")
        assertEquals(emptyList<ShowSummary>(), result)
        NextStop.cached = null
    }
}
