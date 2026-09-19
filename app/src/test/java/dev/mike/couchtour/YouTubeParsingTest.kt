package dev.mike.couchtour

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

/**
 * Decodes real `search.list` payloads (fixtures/youtube_search.json) and the defensive
 * cases around them — missing optionals must degrade to null, never throw. The fixture is
 * the macOS package's youtube_search.json, kept identical so both platforms exercise the
 * same payload shape (D253).
 */
class YouTubeParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `decodes a search fixture end to end`() {
        val response = json.decodeFromString<YouTubeSearchResponse>(fixture("youtube_search.json"))

        assertEquals(2, response.items.size)

        val expectedPublishedAtMs =
            OffsetDateTime.parse("2009-10-25T06:57:33Z").toInstant().toEpochMilli()

        val full = response.items.first()
        assertEquals("dQw4w9WgXcQ", full.id.videoId)
        assertEquals(
            "Rick Astley - Never Gonna Give You Up (Official Music Video)",
            full.snippet!!.title
        )
        assertEquals("2009-10-25T06:57:33Z", full.snippet!!.publishedAt)

        val video = full.toYouTubeVideo()
        assertEquals("dQw4w9WgXcQ", video.id)
        assertEquals(
            "Rick Astley - Never Gonna Give You Up (Official Music Video)",
            video.title
        )
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", video.thumbnailUrl)
        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", video.channelId)
        // search.list carries no contentDetails — duration only arrives via stream resolution.
        assertNull(video.durationMs)
        assertNull(video.audioStreamUrl)
        assertNull(video.videoStreamUrl)
        assertEquals(expectedPublishedAtMs, video.publishedAtMs)
    }

    @Test
    fun `maps missing optional snippet fields to null without throwing`() {
        val response = json.decodeFromString<YouTubeSearchResponse>(fixture("youtube_search.json"))

        val video = response.items.last().toYouTubeVideo()

        assertEquals("aBcDeFgHiJk", video.id)
        assertEquals("A Video Missing Some Fields", video.title)
        assertNull(video.thumbnailUrl)
        assertNull(video.publishedAtMs)
        assertNull(video.description)
        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", video.channelId, "channelId falls back to the snippet's own")
    }

    @Test
    fun `malformed publishedAt degrades to null instead of failing the listing`() {
        val payload = """
            {"items":[{"id":{"videoId":"x"},"snippet":{"title":"T","publishedAt":"not-a-date"}},
                      {"id":{},"snippet":{"title":"T2","publishedAt":"2009-10-25T06:57:33Z"}}]}
        """.trimIndent()
        val response = json.decodeFromString<YouTubeSearchResponse>(payload)

        assertEquals("T", response.items.first().snippet!!.title)
        assertNull("unparseable timestamp → null, not a throw", response.items.first().toYouTubeVideo().publishedAtMs)
        assertEquals(
            OffsetDateTime.parse("2009-10-25T06:57:33Z").toInstant().toEpochMilli(),
            response.items.last().toYouTubeVideo().publishedAtMs
        )
    }

    @Test
    fun `missing items key decodes to an empty list`() {
        val response = json.decodeFromString<YouTubeSearchResponse>("{}")
        assertEquals(0, response.items.size)

        val response2 = json.decodeFromString<YouTubeSearchResponse>("""{"pageInfo":{"totalResults":0}}""")
        assertEquals(0, response2.items.size)
    }

    @Test
    fun `a snippet missing entirely still yields the video id`() {
        val response = json.decodeFromString<YouTubeSearchResponse>(
            """{"items":[{"id":{"videoId":"bare-id"}}]}"""
        )

        val video = response.items.single().toYouTubeVideo()
        assertEquals("bare-id", video.id)
        assertEquals("", video.title)
        assertNull(video.thumbnailUrl)
    }

    @Test
    fun `prefers the largest thumbnail the payload carries`() {
        fun thumbs(vararg sizes: Pair<String, String>): YouTubeVideoDto {
            val t = YouTubeThumbnailsDto(
                high = sizes.firstOrNull { it.first == "high" }?.let { YouTubeThumbnailDto(it.second) },
                medium = sizes.firstOrNull { it.first == "medium" }?.let { YouTubeThumbnailDto(it.second) },
                default_ = sizes.firstOrNull { it.first == "default" }?.let { YouTubeThumbnailDto(it.second) },
            )
            return YouTubeVideoDto(
                id = YouTubeIdDto("v"),
                snippet = YouTubeSnippetDto(title = "T", thumbnails = t),
            )
        }

        val all = thumbs("default" to "d.jpg", "medium" to "m.jpg", "high" to "h.jpg")
            .toYouTubeVideo().thumbnailUrl
        val noHigh = thumbs("default" to "d.jpg", "medium" to "m.jpg")
            .toYouTubeVideo().thumbnailUrl
        val defaultOnly = thumbs("default" to "d.jpg").toYouTubeVideo().thumbnailUrl

        assertEquals("h.jpg", all)
        assertEquals("m.jpg", noHigh)
        assertEquals("d.jpg", defaultOnly)
    }

    @Test
    fun `channelId override wins over the snippet's own`() {
        val response = json.decodeFromString<YouTubeSearchResponse>(fixture("youtube_search.json"))

        val video = response.items.first().toYouTubeVideo(channelIdOverride = "UCoverride")
        assertEquals("UCoverride", video.channelId)
    }
}
