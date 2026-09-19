package dev.mike.couchtour

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Exercises YouTubeApi's outgoing requests against a local server — same reasoning as
 * ApiRequestTest: the URL shape and query parameters aren't visible from the DTOs alone,
 * and a wrong one either 400s or silently searches the wrong channel.
 */
class YouTubeRequestTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Mirrors the production path shape (…/youtube/v3/search).
        YouTubeApi.baseUrl = server.url("/youtube/v3/")
        YouTubeApi.apiKey = null
    }

    @After
    fun tearDown() {
        server.shutdown()
        YouTubeApi.baseUrl = "https://www.googleapis.com/youtube/v3".toHttpUrl()
        YouTubeApi.apiKey = null
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun take(): RecordedRequest = server.takeRequest()

    @Test
    fun `requests channel search under youtube v3`() = runBlocking {
        enqueue("""{"items":[]}""")

        YouTubeApi.search("UCDEPOd0RCvw8iSTqFpSBZLA")

        assertEquals(
            listOf("youtube", "v3", "search"),
            take().requestUrl!!.pathSegments
        )
    }

    @Test
    fun `sends the search list query parameters search list needs`() = runBlocking {
        enqueue("""{"items":[]}""")

        YouTubeApi.search("UCDEPOd0RCvw8iSTqFpSBZLA")

        val url = take().requestUrl!!
        assertEquals("snippet", url.queryParameter("part"))
        assertEquals("UCDEPOd0RCvw8iSTqFpSBZLA", url.queryParameter("channelId"))
        assertEquals("video", url.queryParameter("type"))
        assertEquals("50", url.queryParameter("maxResults"))
        assertEquals("date", url.queryParameter("order"))
    }

    @Test
    fun `omits the key entirely when no api key is set`() = runBlocking {
        enqueue("""{"items":[]}""")

        YouTubeApi.search("UCDEPOd0RCvw8iSTqFpSBZLA")

        assertNull(take().requestUrl!!.queryParameter("key"))
    }

    @Test
    fun `sends the api key as the key parameter, not a header`() = runBlocking {
        // A mix-up with phish.in's header-based auth would leak the key into a header
        // YouTube Data API ignores, then fail quota-less with a confusing 403.
        YouTubeApi.apiKey = "the-key"
        enqueue("""{"items":[]}""")

        YouTubeApi.search("UCDEPOd0RCvw8iSTqFpSBZLA")

        val request = take()
        assertEquals("the-key", request.requestUrl!!.queryParameter("key"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `requests json`() = runBlocking {
        enqueue("""{"items":[]}""")

        YouTubeApi.search("UCDEPOd0RCvw8iSTqFpSBZLA")

        assertTrue(take().getHeader("Accept")!!.startsWith("application/json"))
    }

    @Test
    fun `maps the response items into YouTubeVideo`() = runBlocking {
        enqueue("""{"items":[{"id":{"videoId":"dQw4w9WgXcQ"},"snippet":{"title":"Never Gonna"}}]}""")

        val videos = YouTubeApi.search("UCDEPOd0RCvw8iSTqFpSBZLA")

        assertEquals(1, videos.size)
        assertEquals("dQw4w9WgXcQ", videos.first().id)
        assertEquals("Never Gonna", videos.first().title)
    }

    @Test
    fun `raises the status code on a server error`() = runBlocking {
        enqueue("nope", code = 403)

        try {
            YouTubeApi.search("UCDEPOd0RCvw8iSTqFpSBZLA")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(403, e.code)
        }
    }
}
