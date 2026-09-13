package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityValidatorTest {

    @Test
    fun `requireHttps passes through https URLs`() {
        val validUrl = "https://example.com/stream.mp3"
        assertEquals(validUrl, validUrl.requireHttps())
    }

    @Test
    fun `requireHttps rejects http URLs`() {
        val httpUrl = "http://example.com/stream.mp3"
        assertEquals("https://invalid", httpUrl.requireHttps())
    }

    @Test
    fun `requireHttps rejects content and file URLs`() {
        assertEquals("https://invalid", "file:///etc/passwd".requireHttps())
        assertEquals("https://invalid", "content://media/external/audio/media/1".requireHttps())
    }

    @Test
    fun `requireHttps passes through empty strings`() {
        assertEquals("", "".requireHttps())
        assertEquals("   ", "   ".requireHttps())
    }

    @Test
    fun `coreMediaItem drops dangerous urls`() {
        val info = QueueInfo(key = "k", title = "t", subtitle = "s", art = null, artist = "Phish")
        val track = Track(
            id = 1,
            title = "Dangerous",
            mp3Url = "file:///sdcard/malicious.mp3",
            audioStatus = "complete"
        )
        
        val item = mediaItem(track, info)
        assertEquals("https://invalid", item.localConfiguration?.uri.toString())
    }

    @Test
    fun `Cast sender drops dangerous urls`() {
        val info = QueueInfo(key = "k", title = "t", subtitle = "s", art = null, artist = "Phish")
        val track = Track(
            id = 1,
            title = "Dangerous",
            mp3Url = "http://insecure.com/stream.mp3",
            audioStatus = "complete"
        )
        
        val item = mediaItem(track, info)
        val castItem = CastItemConverter().toMediaQueueItem(item)
        assertEquals("https://invalid", castItem.media?.contentUrl)
    }
}
