package dev.mike.couchtour

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The artist page's YouTube section, composed for real under Robolectric (#233). The
 * row/section states are simple enough that DTO-level tests would miss what users see:
 * a hidden section rendering nothing, a failure rendering an error rather than an
 * empty list, and a tap reporting the video id the navigation consumes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtistScreenTest {

    @get:Rule
    val compose = createComposeRule()

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

    private fun setContent(artist: ArtistRef, onVideoClick: (YouTubeVideo) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                LazyColumn {
                    youtubeSection(artist, onVideoClick)
                }
            }
        }
    }

    private fun enqueueVideos(vararg titles: String) {
        val items = titles.joinToString(",") { title ->
            """{"id":{"videoId":"${title.lowercase().replace(' ', '-')}"},
               "snippet":{"title":"$title","channelId":"UCDEPOd0RCvw8iSTqFpSBZLA"}}"""
        }
        server.enqueue(MockResponse().setBody("""{"items":[$items]}"""))
    }

    @Test
    fun `section renders the channel's video rows`() {
        YouTubeApi.apiKey = "test-key"
        enqueueVideos("It's Ice", "Kill Devil Falls")
        setContent(PHISH)

        compose.waitUntil { compose.onAllNodesWithText("It's Ice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Kill Devil Falls").assertExists()

        val path = server.takeRequest().requestUrl ?: throw AssertionError("no request URL")
        assertEquals("UCDEPOd0RCvw8iSTqFpSBZLA", path.queryParameter("channelId"))
    }

    @Test
    fun `tapping a video row reports the video id`() {
        YouTubeApi.apiKey = "test-key"
        enqueueVideos("It's Ice")
        var clicked: String? = null
        setContent(PHISH) { clicked = it.id }

        compose.waitUntil { compose.onAllNodesWithText("It's Ice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("It's Ice").performClick()
        assertEquals("it's-ice", clicked)
    }

    @Test
    fun `section hides entirely for an artist with no curated channel`() {
        YouTubeApi.apiKey = "test-key"
        val relisten = ArtistRef(Backend.RELISTEN, "phish", "Phish")
        setContent(relisten)

        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("YOUTUBE").fetchSemanticsNodes().isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `section hides entirely with no API key`() {
        setContent(PHISH)

        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("YOUTUBE").fetchSemanticsNodes().isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a fetch failure shows an inline error rather than nothing`() {
        YouTubeApi.apiKey = "test-key"
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        setContent(PHISH)

        compose.waitUntil {
            compose.onAllNodesWithText("Couldn't load", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("YOUTUBE").assertExists()
    }

    @Test
    fun `an empty channel shows a no-videos note rather than nothing`() {
        YouTubeApi.apiKey = "test-key"
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        setContent(PHISH)

        compose.waitUntil { compose.onAllNodesWithText("No videos.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("YOUTUBE").assertExists()
    }
}
