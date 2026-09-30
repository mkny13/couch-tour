package dev.mike.couchtour

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home screen's "On this date" section (#13). Pure functions and a fake [MusicSource],
 * the same split [CatalogTest] uses for [pickRandomShow] — no network call needed to check
 * either the date matching or the request-bounding logic.
 */
class OnThisDateTest {

    // ------------------------------------------------------------------ monthDay

    @Test
    fun `monthDay extracts month and day from a well-formed date`() {
        assertEquals("11-17", monthDay("1997-11-17"))
    }

    @Test
    fun `monthDay is null for a malformed date`() {
        assertNull(monthDay("11-17-1997"))
        assertNull(monthDay("not-a-date"))
        assertNull(monthDay("abcd-ef-gh"))
    }

    @Test
    fun `monthDay is null for an empty string`() {
        assertNull(monthDay(""))
    }

    // ------------------------------------------------------------------ showsOnAnniversary

    private fun show(date: String) = ShowSummary(artist = PHISH, date = date)

    @Test
    fun `showsOnAnniversary matches the same month and day across several years`() {
        val shows = listOf(show("1995-11-17"), show("1997-11-17"), show("2003-11-17"))
        assertEquals(shows, showsOnAnniversary(shows, today = "2026-11-17"))
    }

    @Test
    fun `showsOnAnniversary excludes today's own year`() {
        val shows = listOf(show("2026-11-17"), show("1997-11-17"))
        assertEquals(listOf(show("1997-11-17")), showsOnAnniversary(shows, today = "2026-11-17"))
    }

    @Test
    fun `showsOnAnniversary excludes near misses on day or month`() {
        val shows = listOf(show("1997-11-16"), show("1997-10-17"), show("1997-11-17"))
        assertEquals(listOf(show("1997-11-17")), showsOnAnniversary(shows, today = "2026-11-17"))
    }

    @Test
    fun `showsOnAnniversary on a leap day matches only other leap years`() {
        val shows = listOf(show("2020-02-29"), show("2024-02-29"))
        assertEquals(shows, showsOnAnniversary(shows, today = "2028-02-29"))
    }

    @Test
    fun `showsOnAnniversary is empty for a malformed today`() {
        val shows = listOf(show("1997-11-17"))
        assertEquals(emptyList<ShowSummary>(), showsOnAnniversary(shows, today = "not-a-date"))
    }

    // ------------------------------------------------------------------ phishInRanges

    @Test
    fun `phishInRanges batches consecutive periods under the cap`() {
        val periods = listOf(
            PeriodRef("2020", "2020", showCount = 4),
            PeriodRef("2021", "2021", showCount = 35),
            PeriodRef("2022", "2022", showCount = 47),
        )
        val ranges = phishInRanges(periods, cap = 900)
        assertEquals(listOf(PeriodRef("2020-2022", "2020-2022", showCount = 86)), ranges)
    }

    @Test
    fun `phishInRanges starts a new batch when the cap would be exceeded`() {
        val periods = listOf(
            PeriodRef("2020", "2020", showCount = 600),
            PeriodRef("2021", "2021", showCount = 600),
        )
        val ranges = phishInRanges(periods, cap = 900)
        assertEquals(
            listOf(
                PeriodRef("2020-2020", "2020-2020", showCount = 600),
                PeriodRef("2021-2021", "2021-2021", showCount = 600),
            ),
            ranges,
        )
    }

    @Test
    fun `phishInRanges carries an already-ranged period's own span`() {
        val periods = listOf(PeriodRef("1983-1987", "1983-1987", showCount = 34))
        val ranges = phishInRanges(periods, cap = 900)
        assertEquals(listOf(PeriodRef("1983-1987", "1983-1987", showCount = 34)), ranges)
    }

    @Test
    fun `phishInRanges gives a single oversized year its own batch`() {
        val periods = listOf(PeriodRef("1994", "1994", showCount = 1200))
        val ranges = phishInRanges(periods, cap = 900)
        assertEquals(listOf(PeriodRef("1994-1994", "1994-1994", showCount = 1200)), ranges)
    }

    @Test
    fun `phishInRanges ignores a period that isn't a year or year range`() {
        val periods = listOf(PeriodRef(POPULAR_PERIOD_ID, POPULAR_PERIOD_LABEL, showCount = 100))
        assertTrue(phishInRanges(periods).isEmpty())
    }

    @Test
    fun `phishInRanges produces non-overlapping ascending batches from unsorted or gapped periods`() {
        val periods = listOf(
            PeriodRef("2022", "2022", showCount = 10),
            PeriodRef("1989", "1989", showCount = 10),
            PeriodRef("1993", "1993", showCount = 10),
            PeriodRef("1990", "1990", showCount = 10),
            PeriodRef("2021", "2021", showCount = 10),
        )
        val ranges = phishInRanges(periods, cap = 900)
        // 1989-1990 contiguous; 1993 gapped; 2021-2022 contiguous.
        assertEquals(
            listOf(
                PeriodRef("1989-1990", "1989-1990", showCount = 20),
                PeriodRef("1993-1993", "1993-1993", showCount = 10),
                PeriodRef("2021-2022", "2021-2022", showCount = 20),
            ),
            ranges,
        )
        // Assert no year appears in two batches
        val allYears = ranges.flatMap { r ->
            val parts = r.id.split("-").map { it.toInt() }
            (parts[0]..parts[1]).toList()
        }
        assertEquals(allYears.distinct(), allYears)
    }

    // ------------------------------------------------------------------ pickAnniversaryShows

    @Test
    fun `pickAnniversaryShows caps at the limit and sorts newest first`() {
        val shows = (1990..2020).map { show("$it-11-17") }
        val picked = pickAnniversaryShows(shows, limit = 8, random = Random(1))
        assertEquals(8, picked.size)
        assertEquals(picked.sortedByDescending { it.date }, picked)
    }

    @Test
    fun `pickAnniversaryShows returns everything when under the limit`() {
        val shows = listOf(show("1997-11-17"), show("2003-11-17"))
        val picked = pickAnniversaryShows(shows, limit = 8, random = Random(1))
        assertEquals(2, picked.size)
    }

    // ------------------------------------------------------------------ showsOnDate

    private class FakeSource(
        private val backendId: Backend,
        private val periodsByArtist: Map<String, List<PeriodRef>> = emptyMap(),
        private val showsByPeriod: Map<String, List<ShowSummary>> = emptyMap(),
        private val showsOnDateByArtist: Map<String, List<ShowSummary>> = emptyMap(),
        private val failing: Set<String> = emptySet(),
    ) : MusicSource {
        override val backend = backendId
        var periodsCalled = false
        var showsCalled = false
        val showsOnDateCalls = mutableListOf<Triple<String, Int, Int>>()

        override suspend fun artists() = emptyList<ArtistRef>()
        override suspend fun periods(artist: ArtistRef): List<PeriodRef> {
            periodsCalled = true
            if (artist.id in failing) error("boom")
            return periodsByArtist.getValue(artist.id)
        }
        override suspend fun shows(artist: ArtistRef, period: PeriodRef): List<ShowSummary> {
            showsCalled = true
            return showsByPeriod.getValue(period.id)
        }
        override suspend fun show(artist: ArtistRef, date: String, recordingId: String?) =
            error("not used by showsOnDate")
        override suspend fun search(term: String) = SearchHits()
        override suspend fun showsOnDate(artist: ArtistRef, month: Int, day: Int): List<ShowSummary> {
            showsOnDateCalls.add(Triple(artist.id, month, day))
            if (artist.id in failing) error("boom")
            return showsOnDateByArtist[artist.id].orEmpty()
        }
    }

    @Test
    fun `showsOnDate finds a phish_in match via the range-batched period`() = runBlocking {
        val phishSource = FakeSource(
            Backend.PHISHIN,
            periodsByArtist = mapOf("phish" to listOf(PeriodRef("1996", "1996", showCount = 71))),
            showsByPeriod = mapOf("1996-1996" to listOf(show("1996-11-17"), show("1996-06-01"))),
        )
        val result = showsOnDate(listOf(PHISH), today = "2026-11-17") {
            when (it) {
                Backend.PHISHIN -> phishSource
                Backend.RELISTEN -> error("not used")
                Backend.YOUTUBE -> error("not used")
            }
        }
        assertEquals(listOf(show("1996-11-17")), result)
    }

    @Test
    fun `showsOnDate returns old-year Relisten anniversaries, excludes today's year, and never calls periods or shows`() = runBlocking {
        val moe = ArtistRef(Backend.RELISTEN, "moe", "moe.")
        val shows = listOf(
            ShowSummary(artist = moe, date = "1995-09-29"),
            ShowSummary(artist = moe, date = "1998-09-29"),
            ShowSummary(artist = moe, date = "2001-09-29"),
            ShowSummary(artist = moe, date = "2007-09-29"),
            ShowSummary(artist = moe, date = "2013-09-29"),
            ShowSummary(artist = moe, date = "2026-09-29"), // Today's year, must be excluded
        )
        val source = FakeSource(
            Backend.RELISTEN,
            showsOnDateByArtist = mapOf("moe" to shows),
        )
        val result = showsOnDate(listOf(moe), today = "2026-09-29") { source }

        assertEquals(listOf("2013-09-29", "2007-09-29", "2001-09-29", "1998-09-29", "1995-09-29"), result.map { it.date })
        org.junit.Assert.assertFalse(source.periodsCalled)
        org.junit.Assert.assertFalse(source.showsCalled)
        assertEquals(listOf(Triple("moe", 9, 29)), source.showsOnDateCalls)
    }

    @Test
    fun `showsOnDate caps at ten relisten artists`() = runBlocking {
        val artists = (1..12).map { ArtistRef(Backend.RELISTEN, "artist-$it", "Artist $it") }
        val source = FakeSource(
            Backend.RELISTEN,
            showsOnDateByArtist = artists.associate { it.id to listOf(ShowSummary(artist = it, date = "2000-09-29")) },
        )
        val result = showsOnDate(artists, today = "2026-09-29") { source }

        assertEquals(10, source.showsOnDateCalls.size)
        assertEquals((1..10).map { "artist-$it" }, source.showsOnDateCalls.map { it.first })
        assertEquals(8, result.size) // Capped at MAX_ANNIVERSARY_SHOWS (8)
    }

    @Test
    fun `showsOnDate ignores a favorited artist whose fetch fails`() = runBlocking {
        val ok = ArtistRef(Backend.RELISTEN, "ok", "Ok Artist")
        val bad = ArtistRef(Backend.RELISTEN, "bad", "Bad Artist")
        val source = FakeSource(
            Backend.RELISTEN,
            showsOnDateByArtist = mapOf("ok" to listOf(ShowSummary(artist = ok, date = "2020-11-17"))),
            failing = setOf("bad"),
        )
        val result = showsOnDate(listOf(ok, bad), today = "2026-11-17") { source }
        assertEquals(listOf(ShowSummary(artist = ok, date = "2020-11-17")), result)
    }

    @Test
    fun `showsOnDate returns empty for no favorites`() = runBlocking {
        val result = showsOnDate(emptyList(), today = "2026-11-17") { error("not used") }
        assertEquals(emptyList<ShowSummary>(), result)
    }

    // ------------------------------------------------------------------ OnThisDate cache

    @Test
    fun `OnThisDate cache key changes with the date and the favorite set`() {
        val a = ArtistRef(Backend.RELISTEN, "a", "A")
        val b = ArtistRef(Backend.RELISTEN, "b", "B")
        val key1 = OnThisDate.cacheKey(listOf(a), "2026-11-17")
        val key2 = OnThisDate.cacheKey(listOf(a), "2026-11-18")
        val key3 = OnThisDate.cacheKey(listOf(a, b), "2026-11-17")
        assertTrue(key1 != key2)
        assertTrue(key1 != key3)
        // Order-independent: the same favorite set produces the same key regardless of order.
        assertEquals(OnThisDate.cacheKey(listOf(a, b), "2026-11-17"), OnThisDate.cacheKey(listOf(b, a), "2026-11-17"))
    }

    @Test
    fun `OnThisDate load returns empty without hitting the network for no favorites`() = runBlocking {
        OnThisDate.cached = null
        val result = OnThisDate.load(emptyList(), today = "2026-11-17")
        assertEquals(emptyList<ShowSummary>(), result)
        OnThisDate.cached = null
    }

    @Test
    fun `concurrent OnThisDate load calls deduplicate to one fan-out`() = runBlocking {
        OnThisDate.cached = null
        var fetchCount = 0
        val fakeSource = object : MusicSource {
            override val backend = Backend.PHISHIN
            override suspend fun artists() = emptyList<ArtistRef>()
            override suspend fun periods(artist: ArtistRef): List<PeriodRef> {
                fetchCount++
                delay(50)
                return listOf(PeriodRef("1996", "1996", showCount = 1))
            }
            override suspend fun shows(artist: ArtistRef, period: PeriodRef): List<ShowSummary> =
                listOf(ShowSummary(artist = PHISH, date = "1996-11-17"))
            override suspend fun show(artist: ArtistRef, date: String, recordingId: String?) = error("unused")
            override suspend fun search(term: String) = SearchHits()
        }

        val favs = listOf(PHISH)
        val job1 = async { OnThisDate.load(favs, "2026-11-17", source = { fakeSource }) }
        val job2 = async { OnThisDate.load(favs, "2026-11-17", source = { fakeSource }) }
        val (res1, res2) = awaitAll(job1, job2)

        assertEquals(res1, res2)
        assertEquals(1, fetchCount)
        OnThisDate.cached = null
    }
}
