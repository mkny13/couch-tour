package dev.mike.couchtour

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The artist→channel seam and the YouTube backend's MusicSource wiring (#233). The
 * request/parse shapes themselves are YouTubeRequestTest/YouTubeParsingTest's job — this
 * covers what sits between the API client and the artist page: channel resolution, the
 * section's hide rules, and sourceFor dispatch.
 */
class YouTubeCatalogTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        YouTubeApi.baseUrl = server.url("/youtube/v3/")
        YouTubeApi.apiKey = null
    }

    @After
    fun tearDown() {
        server.shutdown()
        YouTubeApi.baseUrl = "https://www.googleapis.com/youtube/v3".toHttpUrl()
        YouTubeApi.apiKey = null
    }

    // ------------------------------------------------------------- channel resolution

    @Test
    fun `phish maps to the curated official channel`() {
        assertEquals("UCDEPOd0RCvw8iSTqFpSBZLA", YouTubeChannels.channel(PHISH))
    }

    @Test
    fun `unmapped artists have no channel`() {
        assertNull(YouTubeChannels.channel(ArtistRef(Backend.PHISHIN, "someone-else", "Someone")))
        assertNull(YouTubeChannels.channel(ArtistRef(Backend.RELISTEN, "phish", "Phish")))
    }

    @Test
    fun `section hides with no API key even for a mapped artist`() {
        assertNull(youtubeSectionChannel(PHISH))
    }

    @Test
    fun `section hides for a blank API key`() {
        YouTubeApi.apiKey = "   "
        assertNull(youtubeSectionChannel(PHISH))
    }

    @Test
    fun `section shows for a mapped artist with a key`() {
        YouTubeApi.apiKey = "test-key"
        assertEquals("UCDEPOd0RCvw8iSTqFpSBZLA", youtubeSectionChannel(PHISH))
    }

    @Test
    fun `section hides for an unmapped artist even with a key`() {
        YouTubeApi.apiKey = "test-key"
        assertNull(youtubeSectionChannel(ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead")))
    }

    // ------------------------------------------------------------- MusicSource wiring

    @Test
    fun `sourceFor dispatches youtube to YouTubeCatalogSource`() {
        assertSame(YouTubeCatalogSource, sourceFor(Backend.YOUTUBE))
    }

    @Test
    fun `browse methods answer empty - youtube is not a tape catalog`() = runBlocking {
        assertTrue(YouTubeCatalogSource.artists().isEmpty())
        assertTrue(YouTubeCatalogSource.periods(PHISH).isEmpty())
        assertTrue(YouTubeCatalogSource.shows(PHISH, PeriodRef("2024", "2024")).isEmpty())
        assertTrue(YouTubeCatalogSource.search("tubular").isEmpty())
        assertTrue(YouTubeCatalogSource.search("tubular").failed.isEmpty())
    }

    @Test
    fun `non-youtube sources keep the default empty youtubeContent`() = runBlocking {
        assertTrue(PhishInSource.youtubeContent(PHISH).isEmpty())
    }

    @Test
    fun `youtubeContent returns nothing for an unmapped artist without hitting the API`() = runBlocking {
        YouTubeApi.apiKey = "test-key"
        assertTrue(YouTubeCatalogSource.youtubeContent(ArtistRef(Backend.RELISTEN, "phish", "Phish")).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `youtubeContent returns nothing for a mapped artist without a key`() = runBlocking {
        assertTrue(YouTubeCatalogSource.youtubeContent(PHISH).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `youtubeContent lists the mapped channel's videos`() = runBlocking {
        YouTubeApi.apiKey = "test-key"
        server.enqueue(
            MockResponse().setBody(
                """
                {"items":[
                  {"id":{"videoId":"vid1"},"snippet":{"title":"It's Ice","channelId":"UCDEPOd0RCvw8iSTqFpSBZLA",
                    "publishedAt":"2024-07-01T12:00:00Z",
                    "thumbnails":{"high":{"url":"https://i.ytimg.com/vi/vid1/hqdefault.jpg"}}}},
                  {"id":{"videoId":"vid2"},"snippet":{"title":"Kill Devil Falls","channelId":"UCDEPOd0RCvw8iSTqFpSBZLA"}}
                ]}
                """.trimIndent()
            )
        )
        val videos = YouTubeCatalogSource.youtubeContent(PHISH)
        assertEquals(listOf("vid1", "vid2"), videos.map { it.id })
        assertEquals("It's Ice", videos[0].title)
        assertEquals("https://i.ytimg.com/vi/vid1/hqdefault.jpg", videos[0].thumbnailUrl)

        val path = server.takeRequest().requestUrl ?: throw AssertionError("no request URL")
        assertEquals("UCDEPOd0RCvw8iSTqFpSBZLA", path.queryParameter("channelId"))
        assertEquals("video", path.queryParameter("type"))
        assertEquals("test-key", path.queryParameter("key"))
    }

    @Test
    fun `youtubeContent surfaces API failures as a throw for the section's error state`() = runBlocking {
        YouTubeApi.apiKey = "test-key"
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        var thrown: Throwable? = null
        try {
            YouTubeCatalogSource.youtubeContent(PHISH)
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue(thrown is ApiException)
    }

    // ------------------------------------------------------- exhaustive-when branches

    @Test
    fun `youtube backend queue keys and share URLs stay sane`() {
        val artist = ArtistRef(Backend.YOUTUBE, "UCDEPOd0RCvw8iSTqFpSBZLA", "Phish")
        val summary = ShowSummary(artist = artist, date = "2024-07-01")
        val detail = ShowDetail(summary = summary)

        // A YouTube video's resume key is youtubeProgressKey (#234); a ShowDetail never
        // carries a YouTube artist, so there's nothing to resume here.
        assertNull(detail.queueKey)
        assertEquals("https://www.youtube.com/channel/UCDEPOd0RCvw8iSTqFpSBZLA", showShareUrl(artist, "2024-07-01"))
        assertNull(trackShareUrl(artist, "2024-07-01", "slug"))
    }
}
