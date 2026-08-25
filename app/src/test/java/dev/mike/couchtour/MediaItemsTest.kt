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

    // ------------------------------------------------------- local playlists (#12)

    @Test
    fun `a mixed local playlist scrobbles each track under its own artist, not the queue's`() {
        // The trap this exists to catch (like the class doc's phish.in-only one, but for a
        // queue that can hold both backends at once): every other queue shares one
        // QueueInfo.artist for all its items, so a mixed playlist needs a real per-track
        // override rather than accidentally scrobbling a Dead track as whichever artist
        // happened to be first, or as the "Phish" default.
        val resolved = listOf(
            ResolvedLocalTrack(
                id = "1", title = "Tweezer", url = "https://phish.in/a.mp3", waveformUrl = null,
                showDate = "1997-11-17", venueName = "McNichols Arena", artUrl = null, artistName = "Phish",
            ),
            ResolvedLocalTrack(
                id = "t1", title = "Scarlet Begonias", url = "https://archive.org/b.mp3", waveformUrl = null,
                showDate = "1977-05-08", venueName = "Barton Hall", artUrl = null, artistName = "Grateful Dead",
            ),
        )

        val items = localPlaylistTrackItems("p1", "Key Jams", resolved)

        assertEquals(listOf("Phish", "Grateful Dead"), items.map { it.mediaMetadata.artist })
    }

    @Test
    fun `a local playlist keys its queue by its own id`() {
        val resolved = listOf(
            ResolvedLocalTrack(
                id = "1", title = "Tweezer", url = "https://phish.in/a.mp3", waveformUrl = null,
                showDate = null, venueName = null, artUrl = null, artistName = "Phish",
            )
        )

        val items = localPlaylistTrackItems("p1", "Key Jams", resolved)

        assertEquals(
            "local-playlist:p1",
            items.first().mediaMetadata.extras?.getString(Keys.QUEUE_KEY),
        )
    }

    // ------------------------------------------------------- like metadata (#63)

    @Test
    fun `a phish-in media item carries track ID, backend, and liked metadata in extras`() {
        val info = QueueInfo(key = showQueueKey("1997-11-17"), title = "1997-11-17", subtitle = "McNichols Arena", art = null)
        val track = Track(id = 42, title = "Ghost", mp3Url = "https://phish.in/a.mp3", likedByUser = true, likesCount = 15)

        val item = mediaItem(track, info)
        val extras = item.mediaMetadata.extras

        assertEquals("42", item.mediaId)
        assertEquals("42", extras?.getString(Keys.TRACK_ID))
        assertEquals(Backend.PHISHIN.id, extras?.getString(Keys.BACKEND))
        assertEquals(true, extras?.getBoolean(Keys.LIKED))
        assertEquals(15, extras?.getInt(Keys.LIKES_COUNT))
    }

    @Test
    fun `a relisten media item carries track ID and relisten backend in extras`() {
        val items = recordingTrackItems(detail())
        val item = items.first()
        val extras = item.mediaMetadata.extras

        assertEquals("t1", item.mediaId)
        assertEquals("t1", extras?.getString(Keys.TRACK_ID))
        assertEquals(Backend.RELISTEN.id, extras?.getString(Keys.BACKEND))
    }

    // ------------------------------------------------------- FLAC streaming (#27)

    @Test
    fun `a relisten track with flac_url sets FLAC mime type and preserves mp3 fallback in extras`() {
        val flacTrack = PlayableTrack(
            id = "t-flac",
            title = "Scarlet Begonias",
            durationMs = 400_000,
            url = "https://archive.org/scarlet.mp3",
            flacUrl = "https://archive.org/scarlet.flac",
            showDate = "1977-05-08",
            venueName = "Barton Hall",
        )
        val info = QueueInfo(key = "k", title = "t", subtitle = "s", art = null, artist = "Grateful Dead")
        val item = recordingMediaItem(flacTrack, info)

        assertEquals("https://archive.org/scarlet.flac", item.localConfiguration?.uri.toString())
        assertEquals(androidx.media3.common.MimeTypes.AUDIO_FLAC, item.localConfiguration?.mimeType)
        assertEquals("https://archive.org/scarlet.flac", item.mediaMetadata.extras?.getString(Keys.FLAC_URL))
        assertEquals("https://archive.org/scarlet.mp3", item.mediaMetadata.extras?.getString(Keys.MP3_URL))
    }

    // ------------------------------------------------------- Show / Artist metadata

    @Test
    fun `media items carry show date, venue name, and artist details in extras`() {
        val phishTrack = Track(id = 1, title = "Ghost", showDate = "1997-11-17", venueName = "McNichols Arena")
        val phishInfo = QueueInfo(key = showQueueKey("1997-11-17"), title = "1997-11-17", subtitle = "McNichols Arena · Denver, CO", art = null)
        val phishItem = mediaItem(phishTrack, phishInfo)
        val pExtras = phishItem.mediaMetadata.extras

        assertEquals("1997-11-17", pExtras?.getString(Keys.SHOW_DATE))
        assertEquals("McNichols Arena", pExtras?.getString(Keys.VENUE_NAME))
        assertEquals("Phish", pExtras?.getString(Keys.ARTIST_NAME))
        assertEquals("phish", pExtras?.getString(Keys.ARTIST_ID))

        val relistenItems = recordingTrackItems(detail())
        val rExtras = relistenItems.first().mediaMetadata.extras

        assertEquals("1977-05-08", rExtras?.getString(Keys.SHOW_DATE))
        assertEquals("Barton Hall, Cornell University", rExtras?.getString(Keys.VENUE_NAME))
        assertEquals("Grateful Dead", rExtras?.getString(Keys.ARTIST_NAME))
        assertEquals("grateful-dead", rExtras?.getString(Keys.ARTIST_ID))
    }
}
