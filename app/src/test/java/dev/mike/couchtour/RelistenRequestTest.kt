package dev.mike.couchtour

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Exercises RelistenApi's outgoing requests against a local server — same reasoning as
 * ApiRequestTest: the URL shapes aren't visible from the DTOs alone.
 */
class RelistenRequestTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        RelistenApi.baseUrl = server.url("/api")
        RelistenCatalogSource.cachedArtists = null
    }

    @After
    fun tearDown() {
        server.shutdown()
        RelistenApi.baseUrl = "https://api.relisten.net/api".toHttpUrl()
        RelistenCatalogSource.cachedArtists = null
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun take(): RecordedRequest = server.takeRequest()

    @Test
    fun `requests the artist list under v3`() = runBlocking {
        enqueue("[]")

        RelistenApi.artists()

        assertEquals(listOf("api", "v3", "artists"), take().requestUrl!!.pathSegments)
    }

    @Test
    fun `requests years under the artist's v3 path`() = runBlocking {
        enqueue("[]")

        RelistenApi.years("artist-uuid")

        assertEquals(
            listOf("api", "v3", "artists", "artist-uuid", "years"),
            take().requestUrl!!.pathSegments
        )
    }

    @Test
    fun `requests a single year by artist and year uuid`() = runBlocking {
        enqueue("""{"year":"1977","shows":[]}""")

        RelistenApi.year("artist-uuid", "year-uuid")

        assertEquals(
            listOf("api", "v3", "artists", "artist-uuid", "years", "year-uuid"),
            take().requestUrl!!.pathSegments
        )
    }

    @Test
    fun `requests a show by artist slug and date under v2, not v3`() = runBlocking {
        // The per-show endpoint with every tape hasn't moved to v3 (see the plan's API
        // table) — getting this wrong 404s every show page.
        enqueue("""{"display_date":"1977-05-08","sources":[]}""")

        RelistenApi.show("grateful-dead", "1977-05-08")

        assertEquals(
            listOf("api", "v2", "artists", "grateful-dead", "shows", "1977-05-08"),
            take().requestUrl!!.pathSegments
        )
    }

    @Test
    fun `sends no auth header, because Relisten needs none`() = runBlocking {
        enqueue("[]")

        RelistenApi.artists()

        val request = take()
        assertNull(request.getHeader("X-Auth-Token"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `requests json`() = runBlocking {
        enqueue("[]")

        RelistenApi.artists()

        assertEquals("application/json", take().getHeader("Accept"))
    }

    @Test
    fun `raises the status code on a server error`() = runBlocking {
        enqueue("nope", code = 500)

        try {
            RelistenApi.artists()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertFalse(e.unauthorized)
        }
    }

    @Test
    fun `parses a real response shape through the live request path`() = runBlocking {
        enqueue(
            """[{"uuid":"u","slug":"phish","name":"Phish","show_count":1,
                "features":{"sets":true,"multiple_sources":false}}]"""
        )

        val artists = RelistenApi.artists()

        assertEquals(1, artists.size)
        assertTrue(artists[0].features.sets)
    }

    // ----------------------------------------------------------- RelistenSource

    @Test
    fun `RelistenCatalogSource caches the artist list after the first call`() = runBlocking {
        enqueue("""[{"uuid":"u","slug":"phish","name":"Phish"}]""")

        val first = RelistenCatalogSource.artists()
        val second = RelistenCatalogSource.artists()

        assertEquals(first, second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `RelistenCatalogSource periods and shows delegate through the slug, not a uuid lookup`() = runBlocking {
        // /years and /years/{yearUuid} both accept the slug directly (confirmed live), so
        // this must not try to resolve one through an extra artists() round trip.
        val artist = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead")
        enqueue("""[{"uuid":"y","year":"1977","show_count":57}]""")

        val periods = RelistenCatalogSource.periods(artist)

        assertEquals("grateful-dead", take().requestUrl!!.pathSegments[3])
        assertEquals("1977", periods.first().label)
    }

    @Test
    fun `RelistenCatalogSource show returns the default tape as a queue-keyable detail`() = runBlocking {
        val artist = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead", hasSets = false)
        enqueue(
            """{"display_date":"1977-05-08","sources":[
                {"uuid":"src-1","avg_rating_weighted":9.0,"sets":[
                    {"index":0,"name":"Set","tracks":[
                        {"uuid":"t1","title":"Minglewood Blues","duration":10,"mp3_url":"https://a/1.mp3"}
                    ]}
                ]}
            ]}"""
        )

        val detail = RelistenCatalogSource.show(artist, "1977-05-08")

        assertEquals("relisten:grateful-dead/1977-05-08/src-1", detail.queueKey)
        assertEquals("Minglewood Blues", detail.tracks.first().title)
    }
}
