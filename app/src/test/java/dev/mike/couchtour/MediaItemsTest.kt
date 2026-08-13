package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The queue builder that both phish.in and Relisten converge on. The trap this exists to
 * catch: `.setArtist("Phish")` was hardcoded before Relisten existed, which fed the
 * MediaSession the official Last.fm app scrobbles from (D50) — left alone, every Dead show
 * would scrobble as Phish. See MULTI-ARTIST-PLAN.md "Traps — each fails silently if missed".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaItemsTest {

    private val deadArtist = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead", hasSets = false, hasMultipleSources = true)

    private fun detail(
        artist: ArtistRef = deadArtist,
        recording: RecordingRef? = RecordingRef(id = "src-1", label = "SBD"),
        tracks: List<PlayableTrack> = listOf(
            PlayableTrack(
                id = "t1",
                title = "Minglewood Blues",
                durationMs = 325_000,
                url = "https://archive.org/a.mp3",
                showDate = "1977-05-08",
                venueName = "Barton Hall, Cornell University",
            )
        ),
    ) = ShowDetail(
        summary = ShowSummary(
            artist = artist,
            date = "1977-05-08",
            venue = "Barton Hall, Cornell University",
            location = "Ithaca, NY, USA",
        ),
        recording = recording,
        tracks = tracks,
    )

    // ------------------------------------------------------------------ artist

    @Test
    fun `a phish-in queue still publishes Phish as the artist`() {
        val info = QueueInfo(key = showQueueKey("1997-11-17"), title = "1997-11-17", subtitle = "McNichols Arena", art = null)
        val track = Track(id = 1, title = "Tweezer", mp3Url = "https://phish.in/a.mp3", audioStatus = "complete")

        assertEquals("Phish", mediaItem(track, info).mediaMetadata.artist)
    }

    @Test
    fun `a relisten queue publishes the show's own artist, not Phish`() {
        val items = recordingTrackItems(detail())
        assertEquals("Grateful Dead", items.first().mediaMetadata.artist)
    }

    // -------------------------------------------------------------- queue key

    @Test
    fun `keys the queue by artist, date, and the chosen recording`() {
        val items = recordingTrackItems(detail())
        assertEquals(
            "relisten:grateful-dead/1977-05-08/src-1",
            items.first().mediaMetadata.extras?.getString(Keys.QUEUE_KEY)
        )
    }

    @Test
    fun `a show with no chosen recording still builds a queue, just an unresumable one`() {
        // Better to let it play than to silently refuse (D42's tradeoff for shuffle).
        val items = recordingTrackItems(detail(recording = null))
        assertTrue(items.isNotEmpty())
        assertNull(items.first().mediaMetadata.extras?.getString(Keys.QUEUE_KEY))
    }

    // ----------------------------------------------------------------- labels

    @Test
    fun `the album is the show, the subtitle is the queue, same contract as phish-in`() {
        val meta = recordingTrackItems(detail()).first().mediaMetadata
        assertEquals("1977-05-08 · Barton Hall, Cornell University", meta.albumTitle)
        assertEquals("1977-05-08 · Barton Hall, Cornell University · Ithaca, NY, USA", meta.subtitle)
    }

    @Test
    fun `a phish-in and a relisten item are built by the same converging path`() {
        // D73: the phone and the Auto browse tree must produce byte-identical queues for the
        // same inputs, whichever backend they came from.
        val info = QueueInfo(key = "k", title = "t", subtitle = "s", art = "art.jpg", artist = "Grateful Dead")
        val track = PlayableTrack(id = "t1", title = "Song", durationMs = 1000, url = "https://a/1.mp3")

        val viaRecording = recordingMediaItem(track, info)
        assertEquals("Grateful Dead", viaRecording.mediaMetadata.artist)
        assertEquals("art.jpg", viaRecording.mediaMetadata.artworkUri.toString())
        assertEquals("t · s", viaRecording.mediaMetadata.subtitle)
    }
}
