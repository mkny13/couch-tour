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

    @Test
    fun `requireHttps is idempotent on already-https input`() {
        val validUrl = "https://example.com/image.png"
        val normalizedOnce = validUrl.requireHttps()
        val normalizedTwice = normalizedOnce.requireHttps()
        assertEquals(validUrl, normalizedOnce)
        assertEquals(validUrl, normalizedTwice)
    }

    @Test
    fun `waveform URL is normalized to https`() {
        val info = QueueInfo(key = "k", title = "t", subtitle = "s", art = null, artist = "Phish")
        val track = Track(
            id = 1,
            title = "Dangerous",
            mp3Url = "https://safe.com/audio.mp3",
            waveformImageUrl = "file:///sdcard/waveform.png",
            audioStatus = "complete"
        )
        val item = mediaItem(track, info)
        assertEquals("https://invalid", item.mediaMetadata.extras?.getString(Keys.WAVEFORM))
    }

    @Test
    fun `artwork URL is normalized to https`() {
        val info = QueueInfo(key = "k", title = "t", subtitle = "s", art = "content://media/external/images/1", artist = "Phish")
        val track = Track(
            id = 1,
            title = "Safe",
            mp3Url = "https://safe.com/stream.mp3",
            audioStatus = "complete"
        )
        val item = mediaItem(track, info)
        assertEquals("https://invalid", item.mediaMetadata.artworkUri?.toString())
        assertEquals("https://invalid", item.mediaMetadata.extras?.getString(Keys.QUEUE_ART))
    }
}
