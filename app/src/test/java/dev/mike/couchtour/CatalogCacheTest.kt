package dev.mike.couchtour

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [TtlCache] in isolation — hit, miss, expiry (with an injected clock, since
 * [System.currentTimeMillis] can't be faked from a test), and the LRU eviction that bounds
 * memory once a cache is full (#61).
 */
class TtlCacheTest {

    @Test
    fun `a put value comes back on the next get`() {
        val cache = TtlCache<String, Int>(ttlMillis = 1000, maxEntries = 10)

        cache.put("a", 1)

        assertEquals(1, cache.get("a"))
    }

    @Test
    fun `a key that was never put misses`() {
        val cache = TtlCache<String, Int>(ttlMillis = 1000, maxEntries = 10)

        assertNull(cache.get("missing"))
    }

    @Test
    fun `an entry older than the ttl misses and is dropped`() {
        var now = 0L
        val cache = TtlCache<String, Int>(ttlMillis = 1000, maxEntries = 10, clock = { now })

        cache.put("a", 1)
        now = 1000 // exactly at the ttl boundary counts as expired, not fresh
        val result = cache.get("a")

        assertNull(result)
        assertEquals(0, cache.size)
    }

    @Test
    fun `an entry just under the ttl still hits`() {
        var now = 0L
        val cache = TtlCache<String, Int>(ttlMillis = 1000, maxEntries = 10, clock = { now })

        cache.put("a", 1)
        now = 999
        assertEquals(1, cache.get("a"))
    }

    @Test
    fun `clear drops every entry regardless of ttl`() {
        val cache = TtlCache<String, Int>(ttlMillis = 1000, maxEntries = 10)
        cache.put("a", 1)

        cache.clear()

        assertNull(cache.get("a"))
        assertEquals(0, cache.size)
    }

    @Test
    fun `putting past maxEntries evicts the least recently used entry, not the oldest inserted`() {
        val cache = TtlCache<String, Int>(ttlMillis = 60_000, maxEntries = 2)

        cache.put("a", 1)
        cache.put("b", 2)
        cache.get("a") // touch "a" so "b" becomes the least recently used
        cache.put("c", 3)

        assertEquals(1, cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(3, cache.get("c"))
        assertEquals(2, cache.size)
    }
}

/**
 * The caching layer #61 adds on top of the two [MusicSource]s — a second call for the same
 * artist/period/show is served from memory rather than hitting the network again, and
 * [RelistenCatalogSource.resetCache]/[PhishInSource.resetCache] force a real re-fetch, the
 * same test hook shape [RelistenCatalogSource.cachedArtists] already had for the artist list.
 */
class CatalogCacheHitTest {

    private lateinit var phishInServer: MockWebServer
    private lateinit var relistenServer: MockWebServer

    @Before
    fun setUp() {
        phishInServer = MockWebServer().apply { start() }
        relistenServer = MockWebServer().apply { start() }
        PhishInApi.baseUrl = phishInServer.url("/api/v2")
        RelistenApi.baseUrl = relistenServer.url("/api")
        PhishInSource.resetCache()
        RelistenCatalogSource.resetCache()
    }

    @After
    fun tearDown() {
        phishInServer.shutdown()
        relistenServer.shutdown()
        PhishInApi.baseUrl = "https://phish.in/api/v2".toHttpUrl()
        RelistenApi.baseUrl = "https://api.relisten.net/api".toHttpUrl()
        PhishInSource.resetCache()
        RelistenCatalogSource.resetCache()
    }

    @Test
    fun `PhishInSource periods is served from cache on the second call`() = runBlocking {
        phishInServer.enqueue(MockResponse().setBody("""[{"period":"1997","shows_with_audio_count":81}]"""))

        val first = PhishInSource.periods(PHISH)
        val second = PhishInSource.periods(PHISH)

        assertEquals(first, second)
        assertEquals(1, phishInServer.requestCount)
    }

    @Test
    fun `PhishInSource resetCache forces a real re-fetch`() = runBlocking {
        phishInServer.enqueue(MockResponse().setBody("""[{"period":"1997","shows_with_audio_count":81}]"""))
        PhishInSource.periods(PHISH)

        PhishInSource.resetCache()
        phishInServer.enqueue(MockResponse().setBody("""[{"period":"1998","shows_with_audio_count":40}]"""))
        val after = PhishInSource.periods(PHISH)

        assertEquals(2, phishInServer.requestCount)
        assertEquals(listOf(POPULAR_PERIOD_ID, "1998"), after.map { it.id })
    }

    @Test
    fun `PhishInSource shows is served from cache per period, keyed independently`() = runBlocking {
        phishInServer.enqueue(MockResponse().setBody("""{"shows":[{"date":"1997-11-17"}]}"""))
        phishInServer.enqueue(MockResponse().setBody("""{"shows":[{"date":"1998-11-14"}]}"""))

        PhishInSource.shows(PHISH, PeriodRef("1997", "1997"))
        PhishInSource.shows(PHISH, PeriodRef("1998", "1998"))
        val repeat1997 = PhishInSource.shows(PHISH, PeriodRef("1997", "1997"))
        val repeat1998 = PhishInSource.shows(PHISH, PeriodRef("1998", "1998"))

        assertEquals(2, phishInServer.requestCount)
        assertEquals("1997-11-17", repeat1997.first().date)
        assertEquals("1998-11-14", repeat1998.first().date)
    }

    @Test
    fun `PhishInSource show is served from cache on the second call`() = runBlocking {
        phishInServer.enqueue(MockResponse().setBody("""{"date":"1997-11-17","tracks":[]}"""))

        val first = PhishInSource.show(PHISH, "1997-11-17")
        val second = PhishInSource.show(PHISH, "1997-11-17")

        assertEquals(first, second)
        assertEquals(1, phishInServer.requestCount)
    }

    @Test
    fun `RelistenCatalogSource periods is served from cache on the second call`() = runBlocking {
        val artist = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead")
        relistenServer.enqueue(MockResponse().setBody("""[{"uuid":"y","year":"1977","show_count":57}]"""))

        val first = RelistenCatalogSource.periods(artist)
        val second = RelistenCatalogSource.periods(artist)

        assertEquals(first, second)
        assertEquals(1, relistenServer.requestCount)
    }

    @Test
    fun `RelistenCatalogSource periods caches separately per artist`() = runBlocking {
        val deadHead = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead")
        val goose = ArtistRef(Backend.RELISTEN, "goose", "Goose")
        relistenServer.enqueue(MockResponse().setBody("""[{"uuid":"y1","year":"1977","show_count":57}]"""))
        relistenServer.enqueue(MockResponse().setBody("""[{"uuid":"y2","year":"2023","show_count":40}]"""))

        val deadPeriods = RelistenCatalogSource.periods(deadHead)
        val goosePeriods = RelistenCatalogSource.periods(goose)

        assertEquals(2, relistenServer.requestCount)
        assertEquals("1977", deadPeriods.first().label)
        assertEquals("2023", goosePeriods.first().label)
    }

    @Test
    fun `RelistenCatalogSource shows is served from cache on the second call`() = runBlocking {
        val artist = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead")
        relistenServer.enqueue(MockResponse().setBody("""{"year":"1977","shows":[]}"""))

        val first = RelistenCatalogSource.shows(artist, PeriodRef("year-uuid", "1977"))
        val second = RelistenCatalogSource.shows(artist, PeriodRef("year-uuid", "1977"))

        assertEquals(first, second)
        assertEquals(1, relistenServer.requestCount)
    }

    @Test
    fun `RelistenCatalogSource show is served from cache on the second call`() = runBlocking {
        val artist = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead", hasSets = false)
        relistenServer.enqueue(
            MockResponse().setBody(
                """{"display_date":"1977-05-08","sources":[
                    {"uuid":"src-1","sets":[{"index":0,"name":"Set","tracks":[]}]}
                ]}"""
            )
        )

        val first = RelistenCatalogSource.show(artist, "1977-05-08")
        val second = RelistenCatalogSource.show(artist, "1977-05-08")

        assertEquals(first, second)
        assertEquals(1, relistenServer.requestCount)
    }

    @Test
    fun `RelistenCatalogSource resetCache forces a real re-fetch`() = runBlocking {
        relistenServer.enqueue(MockResponse().setBody("""[{"uuid":"u","slug":"phish","name":"Phish"}]"""))
        RelistenCatalogSource.artists()

        RelistenCatalogSource.resetCache()
        relistenServer.enqueue(MockResponse().setBody("""[{"uuid":"u2","slug":"phish","name":"Phish"}]"""))
        RelistenCatalogSource.artists()

        assertEquals(2, relistenServer.requestCount)
    }
}
