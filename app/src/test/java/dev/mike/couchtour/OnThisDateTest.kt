package dev.mike.couchtour

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

    // ------------------------------------------------------------------ relistenYearBudget

    @Test
    fun `relistenYearBudget splits the budget across artists`() {
        assertEquals(12, relistenYearBudget(1))
        assertEquals(4, relistenYearBudget(3))
        assertEquals(2, relistenYearBudget(5))
    }

    @Test
    fun `relistenYearBudget is zero for no artists`() {
        assertEquals(0, relistenYearBudget(0))
    }

    // ------------------------------------------------------------------ showsOnDate

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
            error("not used by showsOnDate")
        override suspend fun search(term: String) = SearchHits()
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
            }
        }
        assertEquals(listOf(show("1996-11-17")), result)
    }

    @Test
    fun `showsOnDate caps at three relisten artists and splits the year budget`() = runBlocking {
        val artists = (1..5).map { ArtistRef(Backend.RELISTEN, "artist-$it", "Artist $it") }
        val years = (2000..2025).map { PeriodRef(it.toString(), it.toString(), showCount = 10) }
        var maxPeriodsRequested = 0
        val relistenSource = object : MusicSource {
            override val backend = Backend.RELISTEN
            override suspend fun artists() = emptyList<ArtistRef>()
            override suspend fun periods(artist: ArtistRef) = years
            override suspend fun shows(artist: ArtistRef, period: PeriodRef): List<ShowSummary> {
                maxPeriodsRequested++
                return emptyList()
            }
            override suspend fun show(artist: ArtistRef, date: String, recordingId: String?) = error("unused")
            override suspend fun search(term: String) = SearchHits()
        }
        showsOnDate(artists, today = "2026-11-17") { relistenSource }
        // Only the first MAX_RELISTEN_ARTISTS participate, each fetching relistenYearBudget(3) years.
        val expectedYearsPerArtist = relistenYearBudget(MAX_RELISTEN_ARTISTS)
        assertEquals(MAX_RELISTEN_ARTISTS * expectedYearsPerArtist, maxPeriodsRequested)
    }

    @Test
    fun `showsOnDate ignores a favorited artist whose fetch fails`() = runBlocking {
        val ok = ArtistRef(Backend.RELISTEN, "ok", "Ok Artist")
        val bad = ArtistRef(Backend.RELISTEN, "bad", "Bad Artist")
        val period = PeriodRef("2020", "2020", showCount = 10)
        val source = FakeSource(
            Backend.RELISTEN,
            periodsByArtist = mapOf("ok" to listOf(period)),
            showsByPeriod = mapOf("2020" to listOf(ShowSummary(artist = ok, date = "2020-11-17"))),
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
}
