package dev.mike.couchtour

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [resolveLocalPlaylistTracks] is the one place a stored [LocalPlaylistTrackEntity] gets
 * turned back into something playable (#12, D161) — there's no fetch-by-id endpoint on
 * either backend, so this refetches the show/tape and finds the track inside it. Each
 * backend gets its own [MockWebServer], the same setup [SearchFanOutTest] uses.
 */
class LocalPlaylistResolveTest {

    private lateinit var phishInServer: MockWebServer
    private lateinit var relistenServer: MockWebServer

    @Before
    fun setUp() {
        phishInServer = MockWebServer().apply { start() }
        relistenServer = MockWebServer().apply { start() }
        PhishInApi.baseUrl = phishInServer.url("/api/v2")
        RelistenApi.baseUrl = relistenServer.url("/api")
        RelistenCatalogSource.cachedArtists = null
    }

    @After
    fun tearDown() {
        phishInServer.shutdown()
        relistenServer.shutdown()
        PhishInApi.baseUrl = "https://phish.in/api/v2".toHttpUrl()
        RelistenApi.baseUrl = "https://api.relisten.net/api".toHttpUrl()
        RelistenCatalogSource.cachedArtists = null
    }

    private fun phishRef(showDate: String, trackId: String, title: String) = LocalPlaylistTrackEntity(
        playlistId = "p1", position = 0, backend = Backend.PHISHIN.id,
        trackId = trackId, showDate = showDate, title = title,
    )

    private fun relistenRef(artistSlug: String, showDate: String, trackId: String, title: String) = LocalPlaylistTrackEntity(
        playlistId = "p1", position = 1, backend = Backend.RELISTEN.id,
        trackId = trackId, showDate = showDate, artistSlug = artistSlug, title = title,
    )

    @Test
    fun `resolves a phish-in track from its show`() = runBlocking {
        phishInServer.enqueue(
            MockResponse().setBody(
                """{"date":"1997-11-17","venue_name":"The Centrum","tracks":[
                    {"id":42,"title":"Tweezer","mp3_url":"http://x/tweezer.mp3","audio_status":"complete"}
                ]}"""
            )
        )

        val resolved = resolveLocalPlaylistTracks(listOf(phishRef("1997-11-17", "42", "Tweezer")))

        assertEquals(1, resolved.size)
        assertEquals("http://x/tweezer.mp3", resolved[0].url)
        assertEquals("Phish", resolved[0].artistName)
        // Not the (null, when nested in a show) Track field — the show's own date/venue.
        assertEquals("1997-11-17", resolved[0].showDate)
        assertEquals("The Centrum", resolved[0].venueName)
    }

    @Test
    fun `resolves a relisten track through its artist slug and tape`() = runBlocking {
        relistenServer.enqueue(
            MockResponse().setBody("""[{"uuid":"a1","slug":"goose","name":"Goose"}]""")
        )
        relistenServer.enqueue(
            MockResponse().setBody(
                """{"display_date":"2023-01-01","venue":{"name":"Venue X","location":"City Y"},
                    "sources":[{"uuid":"src1","sets":[{"index":0,"name":"Set 1","tracks":[
                        {"uuid":"track-uuid-1","title":"Jibberish","track_position":1,
                         "duration":300,"mp3_url":"http://x/jibberish.mp3"}
                    ]}]}]}"""
            )
        )

        val resolved = resolveLocalPlaylistTracks(
            listOf(relistenRef("goose", "2023-01-01", "track-uuid-1", "Jibberish"))
        )

        assertEquals(1, resolved.size)
        assertEquals("http://x/jibberish.mp3", resolved[0].url)
        assertEquals("Goose", resolved[0].artistName)
        assertEquals("Venue X", resolved[0].venueName)
    }

    @Test
    fun `a mixed playlist resolves both backends and keeps each track's own artist`() = runBlocking {
        phishInServer.enqueue(
            MockResponse().setBody(
                """{"date":"1997-11-17","venue_name":"The Centrum","tracks":[
                    {"id":42,"title":"Tweezer","mp3_url":"http://x/tweezer.mp3","audio_status":"complete"}
                ]}"""
            )
        )
        relistenServer.enqueue(
            MockResponse().setBody("""[{"uuid":"a1","slug":"goose","name":"Goose"}]""")
        )
        relistenServer.enqueue(
            MockResponse().setBody(
                """{"display_date":"2023-01-01","venue":{"name":"Venue X","location":"City Y"},
                    "sources":[{"uuid":"src1","sets":[{"index":0,"name":"Set 1","tracks":[
                        {"uuid":"track-uuid-1","title":"Jibberish","track_position":1,
                         "duration":300,"mp3_url":"http://x/jibberish.mp3"}
                    ]}]}]}"""
            )
        )

        val refs = listOf(
            phishRef("1997-11-17", "42", "Tweezer"),
            relistenRef("goose", "2023-01-01", "track-uuid-1", "Jibberish"),
        )
        val resolved = resolveLocalPlaylistTracks(refs)

        assertEquals(listOf("Tweezer", "Jibberish"), resolved.map { it.title })
        assertEquals(listOf("Phish", "Goose"), resolved.map { it.artistName })
    }

    @Test
    fun `a track no longer in its show is skipped, not a failure`() = runBlocking {
        phishInServer.enqueue(
            MockResponse().setBody(
                """{"date":"1997-11-17","venue_name":"The Centrum","tracks":[
                    {"id":99,"title":"Some Other Track","mp3_url":"http://x/other.mp3","audio_status":"complete"}
                ]}"""
            )
        )

        val resolved = resolveLocalPlaylistTracks(listOf(phishRef("1997-11-17", "42", "Tweezer")))

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `an unresolvable show fetch is skipped, not thrown`() = runBlocking {
        phishInServer.enqueue(MockResponse().setResponseCode(500))

        val resolved = resolveLocalPlaylistTracks(listOf(phishRef("1997-11-17", "42", "Tweezer")))

        assertTrue(resolved.isEmpty())
    }

    /**
     * A playlist spanning several distinct shows used to fetch them one at a time
     * ([resolveLocalPlaylistTracks] previously used `associateWith`, whose suspending
     * selector awaits each call before starting the next) — the root cause of a 30+ second
     * resume on a multi-show mixtape. Routing responses by request path (rather than
     * enqueue order) proves each show still resolves to its own track regardless of what
     * order the now-concurrent requests actually arrive in.
     */
    @Test
    fun `a playlist spanning several shows resolves each show correctly when fetched concurrently`() = runBlocking {
        val dates = listOf("1997-11-17", "1997-11-22", "1998-11-14")
        phishInServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val date = dates.first { request.path.orEmpty().endsWith(it) }
                return MockResponse().setBody(
                    """{"date":"$date","venue_name":"Venue $date","tracks":[
                        {"id":1,"title":"Track $date","mp3_url":"http://x/$date.mp3","audio_status":"complete"}
                    ]}"""
                )
            }
        }

        val refs = dates.map { phishRef(it, "1", "Track $it") }
        val resolved = resolveLocalPlaylistTracks(refs)

        assertEquals(dates.map { "Track $it" }, resolved.map { it.title })
        assertEquals(dates.map { "http://x/$it.mp3" }, resolved.map { it.url })
    }
}
