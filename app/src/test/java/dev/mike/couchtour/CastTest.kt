package dev.mike.couchtour

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The queue items, and what survives the trip to a Chromecast and back.
 *
 * Casting can't be exercised without a device, but the part that silently breaks it can:
 * a missing MIME type, or extras that don't come home. Both are invisible locally, because
 * ExoPlayer needs neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastTest {

    private val track = Track(
        id = 42,
        title = "Tweezer",
        duration = 1_200_000,
        audioStatus = "complete",
        mp3Url = "https://phish.in/blob/tweezer.mp3",
        waveformImageUrl = "https://phish.in/blob/tweezer.png",
        showDate = "1997-11-17",
        venueName = "McNichols Arena",
        showAlbumCoverUrl = "https://phish.in/blob/cover.jpg",
    )

    private val info = QueueInfo(
        key = showQueueKey("1997-11-17"),
        title = "1997-11-17",
        subtitle = "McNichols Arena · Denver, CO",
        art = "https://phish.in/blob/cover.jpg",
    )

    @Test
    fun `every queue item declares a mime type`() {
        // Cast's item converter rejects an item with no content type, so an item built
        // without one plays locally and throws the moment it reaches a Chromecast.
        assertEquals(MimeTypes.AUDIO_MPEG, mediaItem(track, info).localConfiguration?.mimeType)
    }

    @Test
    fun `custom data carries the queue across the wire`() {
        val custom = castCustomData(mediaItem(track, info))
        val extras = castExtras(custom)

        assertEquals("show:1997-11-17", extras.getString(Keys.QUEUE_KEY))
        assertEquals("1997-11-17", extras.getString(Keys.QUEUE_TITLE))
        assertEquals("McNichols Arena · Denver, CO", extras.getString(Keys.QUEUE_SUBTITLE))
        assertEquals("https://phish.in/blob/cover.jpg", extras.getString(Keys.QUEUE_ART))
        assertEquals("https://phish.in/blob/tweezer.png", extras.getString(Keys.WAVEFORM))
    }

    @Test
    fun `an ephemeral queue stays ephemeral on the other side`() {
        // Shuffle carries no queue key (D42), and inventing one on the way back would
        // start recording a position that can never be resumed.
        val shuffle = QueueInfo(key = null, title = "My tracks", subtitle = "shuffled", art = null)
        val extras = castExtras(castCustomData(mediaItem(track, shuffle)))

        assertNull(extras.getString(Keys.QUEUE_KEY))
        assertEquals("My tracks", extras.getString(Keys.QUEUE_TITLE))
    }

    @Test
    fun `custom data survives absent extras rather than inventing empty strings`() {
        assertNull(castExtras(null).getString(Keys.QUEUE_KEY))
    }

    @Test
    fun `an excerpt is still clipped locally`() {
        // A receiver plays whole files, so clipping is lost when casting — but it must not
        // be lost from the item itself, which is what the local player uses.
        val entry = PlaylistEntry(track = track, startsAtSecond = 60, endsAtSecond = 180)
        val clipping = mediaItem(track, info, entry).clippingConfiguration

        assertEquals(60_000L, clipping.startPositionMs)
        assertEquals(180_000L, clipping.endPositionMs)
    }

    @Test
    fun `the album stays the show and the subtitle stays the queue`() {
        // D50 / D51, restated here because the queue builder moved out of the view model.
        val meta = mediaItem(track, info).mediaMetadata

        assertEquals("1997-11-17 · McNichols Arena", meta.albumTitle)
        assertEquals("1997-11-17 · McNichols Arena · Denver, CO", meta.subtitle)
        assertNotNull(meta.artworkUri)
    }

    // ------------------------------------------------------- FLAC Cast fallback (#27)

    @Test
    fun `a FLAC media item falls back to mp3 URL for Cast and restores FLAC when returning`() {
        val flacTrack = PlayableTrack(
            id = "t-flac",
            title = "Scarlet Begonias",
            durationMs = 400_000,
            url = "https://archive.org/scarlet.mp3",
            flacUrl = "https://archive.org/scarlet.flac",
            showDate = "1977-05-08",
            venueName = "Barton Hall",
        )
        val flacInfo = QueueInfo(key = "k", title = "t", subtitle = "s", art = null, artist = "Grateful Dead")
        val localMediaItem = recordingMediaItem(flacTrack, flacInfo)

        val converter = CastItemConverter()
        val castQueueItem = converter.toMediaQueueItem(localMediaItem)
        val castInfo = castQueueItem.media

        assertNotNull(castInfo)
        assertEquals(MimeTypes.AUDIO_MPEG, castInfo?.contentType)
        assertEquals("https://archive.org/scarlet.mp3", castInfo?.contentUrl)

        val restoredMediaItem = converter.toMediaItem(castQueueItem)
        assertEquals("https://archive.org/scarlet.flac", restoredMediaItem.localConfiguration?.uri.toString())
        assertEquals(MimeTypes.AUDIO_FLAC, restoredMediaItem.localConfiguration?.mimeType)
    }
}
