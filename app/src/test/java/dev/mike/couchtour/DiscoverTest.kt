package dev.mike.couchtour

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Home's Discover row (#834): pure pick logic plus a fake [MusicSource] for the fetch. */
class DiscoverTest {

    private val dead = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead")
    private fun phish(date: String) = ShowSummary(artist = PHISH, date = date)
    private fun gd(date: String) = ShowSummary(artist = dead, date = date)

    private val candidates = (1..30).map { phish("1997-11-${"%02d".format(it)}") } +
        (1..30).map { gd("1977-05-${"%02d".format(it)}") }

    @After fun reset() = Discover.resetCache()

    // ------------------------------------------------------------------ pickDiscoverShows

    @Test
    fun `pick is capped at the limit`() {
        assertEquals(MAX_DISCOVER_SHOWS, pickDiscoverShows(candidates, emptySet(), "2026-11-18").size)
    }

    @Test
    fun `pick is stable within a day and regardless of fan-out order`() {
        val a = pickDiscoverShows(candidates, emptySet(), "2026-11-18")
        val b = pickDiscoverShows(candidates.reversed(), emptySet(), "2026-11-18")
        assertEquals(a, b)
    }

    @Test
    fun `pick changes from one day to the next`() {
        assertNotEquals(
            pickDiscoverShows(candidates, emptySet(), "2026-11-18"),
            pickDiscoverShows(candidates, emptySet(), "2026-11-19"),
        )
    }

    @Test
    fun `started shows are excluded and the rest of the pick stays put`() {
        val before = pickDiscoverShows(candidates, emptySet(), "2026-11-18")
        val startedShow = before[2]
        val after = pickDiscoverShows(candidates, setOf(showId(startedShow)), "2026-11-18")

        assertFalse(startedShow in after)
        assertEquals(before - startedShow, after.take(MAX_DISCOVER_SHOWS - 1))
        assertEquals(MAX_DISCOVER_SHOWS, after.size)
    }

    @Test
    fun `started set built from progress keys excludes both backends`() {
        val started = playedShowIds(listOf(showQueueKey("1997-11-01"), recordingQueueKey("grateful-dead", "1977-05-01", "123")))
        val pick = pickDiscoverShows(candidates, started, "2026-11-18", limit = candidates.size)
        assertFalse(phish("1997-11-01") in pick)
        assertFalse(gd("1977-05-01") in pick)
        assertEquals(candidates.size - 2, pick.size)
    }

    @Test
    fun `duplicate candidates appear once`() {
        val pick = pickDiscoverShows(listOf(phish("1997-11-17"), phish("1997-11-17")), emptySet(), "2026-11-18")
        assertEquals(listOf(phish("1997-11-17")), pick)
    }

    @Test
    fun `everything started leaves an empty row`() {
        val started = candidates.map { showId(it) }.toSet()
        assertTrue(pickDiscoverShows(candidates, started, "2026-11-18").isEmpty())
    }

    // ------------------------------------------------------------------ topRatedShows / Discover

    private fun source(fail: Set<String> = emptySet(), onFetch: suspend () -> Unit = {}) = { _: Backend ->
        object : MusicSource {
            override val backend = Backend.PHISHIN
            override suspend fun artists() = emptyList<ArtistRef>()
            override suspend fun periods(artist: ArtistRef) = emptyList<PeriodRef>()
            override suspend fun shows(artist: ArtistRef, period: PeriodRef) = emptyList<ShowSummary>()
            override suspend fun show(artist: ArtistRef, date: String, recordingId: String?) = error("unused")
            override suspend fun search(term: String) = SearchHits()
            override suspend fun topShows(artist: ArtistRef): List<ShowSummary> {
                onFetch()
                if (artist.key in fail) throw IOException("boom")
                return candidates.filter { it.artist == artist }
            }
        }
    }

    @Test
    fun `one artist failing keeps the others but marks the result incomplete`() = runBlocking {
        val fetched = topRatedShows(listOf(PHISH, dead), source(fail = setOf(dead.key)))
        assertFalse(fetched.complete)
        assertEquals(candidates.filter { it.artist == PHISH }, fetched.value)
    }

    @Test
    fun `Relisten favorites past the cap don't participate`() = runBlocking {
        var calls = 0
        val many = (1..MAX_RELISTEN_ARTISTS + 3).map { ArtistRef(Backend.RELISTEN, "a$it", "A$it") }
        topRatedShows(many, source(onFetch = { calls++ }))
        assertEquals(MAX_RELISTEN_ARTISTS, calls)
    }

    @Test
    fun `load caches a complete day and refetches for a new day`() = runBlocking {
        var calls = 0
        val src = source(onFetch = { calls++ })
        Discover.load(listOf(PHISH), "2026-11-18", src)
        Discover.load(listOf(PHISH), "2026-11-18", src)
        assertEquals(1, calls)
        Discover.load(listOf(PHISH), "2026-11-19", src)
        assertEquals(2, calls)
    }

    @Test
    fun `load doesn't cache an incomplete result`() = runBlocking {
        Discover.load(listOf(PHISH, dead), "2026-11-18", source(fail = setOf(dead.key)))
        assertNull(Discover.cached)
    }

    @Test
    fun `concurrent loads deduplicate to one fan-out`() = runBlocking {
        var calls = 0
        val src = source(onFetch = { calls++; delay(50) })
        val jobs = List(2) { async { Discover.load(listOf(PHISH), "2026-11-18", src) } }
        val (a, b) = jobs.awaitAll()
        assertEquals(a, b)
        assertEquals(1, calls)
    }

    @Test
    fun `no favorites means no fetch`() = runBlocking {
        var calls = 0
        assertTrue(Discover.load(emptyList(), "2026-11-18", source(onFetch = { calls++ })).isEmpty())
        assertEquals(0, calls)
    }
}
