package dev.mike.couchtour

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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

    // The audio-quality preference is a process-wide singleton read at queue-build time.
    // Pin it at the default entering every test and restore it leaving, so quality tests
    // below never leak a COMPRESSED setting into another test class in the same JVM.
    @Before
    fun resetAudioQuality() {
        PlaybackSettings.setAudioQuality(AudioQuality.LOSSLESS)
    }

    @After
    fun restoreAudioQuality() {
        PlaybackSettings.setAudioQuality(AudioQuality.LOSSLESS)
    }

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
        val info = QueueInfo(key = "k", title = "t", subtitle = "s", art = "https://art.jpg", artist = "Grateful Dead")
        val track = PlayableTrack(id = "t1", title = "Song", durationMs = 1000, url = "https://a/1.mp3")

        val viaRecording = recordingMediaItem(track, info)
        assertEquals("Grateful Dead", viaRecording.mediaMetadata.artist)
        assertEquals("https://art.jpg", viaRecording.mediaMetadata.artworkUri.toString())
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

    @Test
    fun `the MP3 quality preference plays the mp3 stream and drops the flac extra`() {
        PlaybackSettings.setAudioQuality(AudioQuality.COMPRESSED)
        val info = QueueInfo(key = "k", title = "t", subtitle = "s", art = null, artist = "Grateful Dead")
        val item = recordingMediaItem(
            PlayableTrack(
                id = "t-flac",
                title = "Scarlet Begonias",
                durationMs = 400_000,
                url = "https://archive.org/scarlet.mp3",
                flacUrl = "https://archive.org/scarlet.flac",
                showDate = "1977-05-08",
                venueName = "Barton Hall",
            ),
            info,
        )

        assertEquals("https://archive.org/scarlet.mp3", item.localConfiguration?.uri.toString())
        assertEquals(androidx.media3.common.MimeTypes.AUDIO_MPEG, item.localConfiguration?.mimeType)
        assertNull(item.mediaMetadata.extras?.getString(Keys.FLAC_URL))
        assertEquals("https://archive.org/scarlet.mp3", item.mediaMetadata.extras?.getString(Keys.MP3_URL))
    }

    @Test
    fun `the MP3 preference still falls back to FLAC when no mp3 url exists`() {
        PlaybackSettings.setAudioQuality(AudioQuality.COMPRESSED)
        val info = QueueInfo(key = "k", title = "t", subtitle = "s", art = null, artist = "Grateful Dead")
        val item = recordingMediaItem(
            PlayableTrack(
                id = "t-flac-only",
                title = "Scarlet Begonias",
                durationMs = 400_000,
                url = "",
                flacUrl = "https://archive.org/scarlet.flac",
                showDate = "1977-05-08",
                venueName = "Barton Hall",
            ),
            info,
        )

        // A preference must never make a tape unplayable.
        assertEquals("https://archive.org/scarlet.flac", item.localConfiguration?.uri.toString())
        assertEquals(androidx.media3.common.MimeTypes.AUDIO_FLAC, item.localConfiguration?.mimeType)
        assertEquals("https://archive.org/scarlet.flac", item.mediaMetadata.extras?.getString(Keys.FLAC_URL))
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

    @Test
    fun `media items carry set name and track position in extras`() {
        val phishTrack = Track(
            id = 8435,
            title = "Bathtub Gin",
            setName = "Set 2",
            position = 4,
            showDate = "1997-11-17",
            venueName = "McNichols Arena"
        )
        val phishInfo = QueueInfo(key = showQueueKey("1997-11-17"), title = "1997-11-17", subtitle = "McNichols Arena", art = null)
        val phishItem = mediaItem(phishTrack, phishInfo)
        val pExtras = phishItem.mediaMetadata.extras

        assertEquals("Set 2", pExtras?.getString(Keys.SET_NAME))
        assertEquals(4, pExtras?.getInt(Keys.TRACK_POSITION))

        val relistenTrack = PlayableTrack(
            id = "t1",
            title = "Jack Straw",
            setName = "Set 1",
            position = 3,
            url = "https://archive.org/jack.mp3"
        )
        val relistenItem = recordingMediaItem(relistenTrack, phishInfo)
        val rExtras = relistenItem.mediaMetadata.extras

        assertEquals("Set 1", rExtras?.getString(Keys.SET_NAME))
        assertEquals(3, rExtras?.getInt(Keys.TRACK_POSITION))
    }

    // ------------------------------------------------------- YouTube (#234)

    /** A resolved, playable YouTube video as #232's resolver produces it. */
    private fun ytVideo(
        id: String = "dQw4w9WgXcQ",
        title: String = "Tweezer · Hampton 1989",
    ) = YouTubeVideo(
        id = id,
        title = title,
        channelId = "UCDEPOd0RCvw8iSTqFpSBZLA",
        thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        audioStreamUrl = "https://audio.example/$id.m4a",
        videoStreamUrl = "https://video.example/$id.mp4",
        durationMs = 3_875_000,
    )

    @Test
    fun `a youtube media item plays the audio-only stream by default`() {
        val item = youtubeMediaItem(ytVideo(), YouTubePlaybackMode.AUDIO)

        assertEquals("https://audio.example/dQw4w9WgXcQ.m4a", item.localConfiguration?.uri.toString())
    }

    @Test
    fun `a youtube media item in video mode plays the muxed stream`() {
        val item = youtubeMediaItem(ytVideo(), YouTubePlaybackMode.VIDEO)

        assertEquals("https://video.example/dQw4w9WgXcQ.mp4", item.localConfiguration?.uri.toString())
    }

    @Test
    fun `a youtube media item carries both stream urls and its mode in extras`() {
        val extras = youtubeMediaItem(ytVideo(), YouTubePlaybackMode.AUDIO).mediaMetadata.extras

        assertEquals("https://audio.example/dQw4w9WgXcQ.m4a", extras?.getString(Keys.YOUTUBE_AUDIO_URL))
        assertEquals("https://video.example/dQw4w9WgXcQ.mp4", extras?.getString(Keys.YOUTUBE_VIDEO_URL))
        assertEquals("audio", extras?.getString(Keys.YOUTUBE_MODE))
    }

    @Test
    fun `a youtube item tags the youtube backend and keys its queue for resume`() {
        val item = youtubeMediaItem(ytVideo(), YouTubePlaybackMode.AUDIO)
        val extras = item.mediaMetadata.extras

        assertEquals(Backend.YOUTUBE.id, extras?.getString(Keys.BACKEND))
        assertEquals("youtube:dQw4w9WgXcQ", extras?.getString(Keys.QUEUE_KEY))
        // The video id is the media id, so the Cast converter and the resume path agree.
        assertEquals("dQw4w9WgXcQ", item.mediaId)
    }

    @Test
    fun `a youtube item scrobbles the channel as artist and the video as title`() {
        // D50: the Last.fm app reads the MediaSession directly, so the channel the video
        // came from is the artist — and the album stays the queue context, not a fake one.
        val item = youtubeMediaItem(ytVideo(title = "Bathtub Gin · 1994-06-26"), YouTubePlaybackMode.AUDIO, artistName = "Trey Anastasio")

        assertEquals("Bathtub Gin · 1994-06-26", item.mediaMetadata.title)
        assertEquals("Trey Anastasio", item.mediaMetadata.artist)
        // The album is the queue context (artist · source), same rule as every backend.
        assertEquals("Trey Anastasio · YouTube", item.mediaMetadata.albumTitle)
    }

    @Test
    fun `a youtube item publishes the thumbnail as artwork`() {
        val item = youtubeMediaItem(ytVideo(), YouTubePlaybackMode.AUDIO)

        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", item.mediaMetadata.artworkUri.toString())
    }

    @Test
    fun `switching modes swaps the stream url and keeps identity`() {
        // The toggle's whole contract: only the URI and mime change, so replaceMediaItem
        // keeps the position, the queue, and the lockscreen metadata untouched.
        val audio = youtubeMediaItem(ytVideo(), YouTubePlaybackMode.AUDIO)
        val video = youtubeItemForMode(audio, YouTubePlaybackMode.VIDEO)!!

        assertEquals("https://video.example/dQw4w9WgXcQ.mp4", video.localConfiguration?.uri.toString())
        assertEquals("video", video.mediaMetadata.extras?.getString(Keys.YOUTUBE_MODE))
        assertEquals(audio.mediaId, video.mediaId)
        assertEquals(
            audio.mediaMetadata.extras?.getString(Keys.QUEUE_KEY),
            video.mediaMetadata.extras?.getString(Keys.QUEUE_KEY),
        )
        assertEquals(audio.mediaMetadata.title, video.mediaMetadata.title)
        assertEquals(audio.mediaMetadata.artist, video.mediaMetadata.artist)

        // And back — the exact round-trip the audio/video toggle performs.
        val backToAudio = youtubeItemForMode(video, YouTubePlaybackMode.AUDIO)!!
        assertEquals("https://audio.example/dQw4w9WgXcQ.m4a", backToAudio.localConfiguration?.uri.toString())
        assertEquals("audio", backToAudio.mediaMetadata.extras?.getString(Keys.YOUTUBE_MODE))
    }

    @Test
    fun `a non-youtube item is not a toggle target`() {
        val info = QueueInfo(key = showQueueKey("1997-11-17"), title = "1997-11-17", subtitle = "McNichols Arena", art = null)
        val track = Track(id = 1, title = "Tweezer", mp3Url = "https://phish.in/a.mp3", audioStatus = "complete")

        assertNull(youtubeItemForMode(mediaItem(track, info), YouTubePlaybackMode.VIDEO))
    }

    @Test
    fun `a youtube item built with no stream url at all fails loudly`() {
        // A blank URI would surface as a confusing player error mid-playback; the item
        // builder refuses instead.
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            youtubeMediaItem(ytVideo().copy(audioStreamUrl = null, videoStreamUrl = null), YouTubePlaybackMode.AUDIO)
        }
    }

    // ----------------------------------------------------------- durationMs

    @Test
    fun `mediaItem stores track duration in extras DURATION_MS`() {
        val info = QueueInfo(key = showQueueKey("1997-11-17"), title = "1997-11-17", subtitle = "Denver", art = null)
        val track = Track(id = 1, title = "Ghost", mp3Url = "https://phish.in/ghost.mp3", duration = 654_000L, audioStatus = "complete")
        val item = mediaItem(track, info)
        assertEquals(654_000L, item.mediaMetadata.extras?.getLong(Keys.DURATION_MS))
    }

    @Test
    fun `recordingMediaItem stores track duration in extras DURATION_MS`() {
        val items = recordingTrackItems(detail())
        assertEquals(325_000L, items.first().mediaMetadata.extras?.getLong(Keys.DURATION_MS))
    }
}
