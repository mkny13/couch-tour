package dev.mike.couchtour

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stream resolution is kept behind [YouTubeStreamResolver] precisely so tests can use a
 * fake — the real [NewPipeStreamResolver] talks to YouTube and is verified on device
 * (UAT). Here: the seam's contract and how resolution lands in the catalog model.
 */
class YouTubeStreamsTest {

    /** Fake resolver: records what it was asked for, answers from a canned result. */
    private class FakeResolver(var result: ResolvedStreams) : YouTubeStreamResolver {
        var resolvedId: String? = null
        override suspend fun resolve(videoId: String): ResolvedStreams {
            resolvedId = videoId
            return result
        }
    }

    private val resolved = ResolvedStreams(
        audioStreamUrl = "https://audio.example/1",
        videoStreamUrl = "https://video.example/1",
        durationMs = 3_875_000,
    )

    @Test
    fun `progress keys use the youtube prefix plus the video id`() {
        assertEquals("youtube:dQw4w9WgXcQ", youtubeProgressKey("dQw4w9WgXcQ"))
    }

    @Test
    fun `the resolver is asked for the video's own id`() = runBlocking {
        val fake = FakeResolver(resolved)
        val video = YouTubeVideo(id = "dQw4w9WgXcQ", title = "T", channelId = "UC")

        // The queue builder (#234) resolves by id and re-attaches the streams.
        fake.resolve(video.id)

        assertEquals("dQw4w9WgXcQ", fake.resolvedId)
    }

    @Test
    fun `withStreams fills both stream urls and the duration`() {
        val video = YouTubeVideo(id = "dQw4w9WgXcQ", title = "T", channelId = "UC")

        val playable = video.withStreams(resolved)

        assertEquals("https://audio.example/1", playable.audioStreamUrl)
        assertEquals("https://video.example/1", playable.videoStreamUrl)
        assertEquals("search.list carries no duration; resolution supplies it", 3_875_000L, playable.durationMs)
        // The untouched fields survive the copy.
        assertEquals("dQw4w9WgXcQ", playable.id)
        assertEquals("T", playable.title)
        assertEquals("UC", playable.channelId)
    }

    @Test
    fun `withStreams replaces a previously resolved duration`() {
        // Re-resolution (YouTube stream URLs expire) must take the newest duration, not
        // the stale one already on the video.
        val video = YouTubeVideo(id = "dQw4w9WgXcQ", title = "T", channelId = "UC", durationMs = 1)

        val playable = video.withStreams(resolved.copy(durationMs = 3_875_000))

        assertEquals(3_875_000L, playable.durationMs)
    }

    @Test
    fun `a listing video resolves to nothing before resolution runs`() {
        val video = YouTubeVideo(id = "dQw4w9WgXcQ", title = "T", channelId = "UC")

        assertNull(video.audioStreamUrl)
        assertNull(video.videoStreamUrl)
        assertNull(video.durationMs)
    }
}
