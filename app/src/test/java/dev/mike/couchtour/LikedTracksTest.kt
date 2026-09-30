package dev.mike.couchtour

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LikedTracksTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("liked_tracks", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        LikedTracks.init(context)
    }

    @Test
    fun `toggle likes and unlikes a track id`() {
        val id = "track-uuid-1"

        LikedTracks.toggle(id)
        assertEquals(setOf(id), LikedTracks.ids.value)

        LikedTracks.toggle(id)
        assertEquals(emptySet<String>(), LikedTracks.ids.value)
    }

    @Test
    fun `likes persist across a fresh init from the same context`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = "track-uuid-2"

        LikedTracks.init(context)
        LikedTracks.toggle(id)

        LikedTracks.init(context)
        assertEquals(setOf(id), LikedTracks.ids.value)
    }

    @Test
    fun `toggle round-trips every field`() {
        val ref = LikedTrackRef(
            id = "track-uuid-1",
            title = "Ghost",
            showDate = "1997-11-17",
            venueName = "The Centrum",
            durationMs = 540000L,
            artistName = "Phish",
            artistSlug = "phish",
            recordingId = "source-42",
            artUrl = "https://example.com/art.jpg",
            likedAt = 1700000000000L,
            backend = Backend.RELISTEN.id,
        )

        LikedTracks.toggle(ref)

        assertEquals(listOf(ref), LikedTracks.entries.value)
        assertEquals(setOf("track-uuid-1"), LikedTracks.ids.value)
        assertTrue(LikedTracks.contains("track-uuid-1"))
    }

    @Test
    fun `entries is newest-first by likedAt`() {
        val ref1 = LikedTrackRef(id = "track-1", title = "First", likedAt = 1000L)
        val ref2 = LikedTrackRef(id = "track-2", title = "Second", likedAt = 3000L)
        val ref3 = LikedTrackRef(id = "track-3", title = "Third", likedAt = 2000L)

        LikedTracks.toggle(ref1)
        LikedTracks.toggle(ref2)
        LikedTracks.toggle(ref3)

        val orderedIds = LikedTracks.entries.value.map { it.id }
        assertEquals(listOf("track-2", "track-3", "track-1"), orderedIds)
    }

    @Test
    fun `unliking removes both the record and the id`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ref = LikedTrackRef(
            id = "track-uuid-unliking",
            title = "Tweezer",
            showDate = "1997-11-17",
            likedAt = 1000L,
        )

        LikedTracks.toggle(ref)
        assertEquals(1, LikedTracks.entries.value.size)
        assertEquals(setOf(ref.id), LikedTracks.ids.value)
        assertTrue(LikedTracks.contains(ref.id))

        LikedTracks.toggle(ref)
        assertEquals(emptyList<LikedTrackRef>(), LikedTracks.entries.value)
        assertEquals(emptySet<String>(), LikedTracks.ids.value)
        assertFalse(LikedTracks.contains(ref.id))

        // Fresh init confirms persistence of unliked state
        LikedTracks.init(context)
        assertEquals(emptyList<LikedTrackRef>(), LikedTracks.entries.value)
        assertEquals(emptySet<String>(), LikedTracks.ids.value)
        assertFalse(LikedTracks.contains(ref.id))
    }

    @Test
    fun `legacy track_ids set migrates to record with blank metadata and can be unliked`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("liked_tracks", android.content.Context.MODE_PRIVATE)
        prefs.edit().putStringSet("track_ids", setOf("legacy-track-1")).commit()

        LikedTracks.init(context)

        assertEquals(setOf("legacy-track-1"), LikedTracks.ids.value)
        assertTrue(LikedTracks.contains("legacy-track-1"))
        val entry = LikedTracks.entries.value.first()
        assertEquals("legacy-track-1", entry.id)
        assertEquals("", entry.title)
        assertEquals("", entry.showDate)
        assertEquals(0L, entry.likedAt)

        // Can be unliked
        LikedTracks.toggle(entry)
        assertEquals(emptySet<String>(), LikedTracks.ids.value)
        assertEquals(emptyList<LikedTrackRef>(), LikedTracks.entries.value)
        assertFalse(LikedTracks.contains("legacy-track-1"))

        // Re-init confirms it is gone from both track_records and track_ids
        LikedTracks.init(context)
        assertEquals(emptySet<String>(), LikedTracks.ids.value)
        assertEquals(emptyList<LikedTrackRef>(), LikedTracks.entries.value)
    }

    @Test
    fun `malformed JSON in track_records does not crash init and yields an empty store`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("liked_tracks", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("track_records", "{ malformed json: not valid [[[[").commit()

        LikedTracks.init(context)

        assertEquals(emptySet<String>(), LikedTracks.ids.value)
        assertEquals(emptyList<LikedTrackRef>(), LikedTracks.entries.value)
        assertFalse(LikedTracks.contains("any-id"))
    }

    @Test
    fun `likes from setlist and Now Playing produce complete records and either unlikes the same entry`() {
        val fromSetlist = LikedTrackRef(
            id = "relisten-uuid-1",
            title = "Bird Song",
            showDate = "1977-05-08",
            venueName = "Barton Hall",
            durationMs = 600000L,
            artistName = "Grateful Dead",
            artistSlug = "grateful-dead",
            recordingId = "tape-1",
            artUrl = "https://example.com/art.jpg",
        )
        LikedTracks.toggle(fromSetlist)
        assertTrue(LikedTracks.contains("relisten-uuid-1"))
        assertEquals("Bird Song", LikedTracks.entries.value.first().title)
        assertEquals("tape-1", LikedTracks.entries.value.first().recordingId)

        // Simulate Now Playing building a ref for the exact same track
        val fromNowPlaying = LikedTrackRef(
            id = "relisten-uuid-1",
            title = "Bird Song",
            showDate = "1977-05-08",
            venueName = "Barton Hall",
            durationMs = 600000L,
            artistName = "Grateful Dead",
            artistSlug = "grateful-dead",
            recordingId = "tape-1",
            artUrl = "https://example.com/art.jpg",
        )
        LikedTracks.toggle(fromNowPlaying)
        assertFalse(LikedTracks.contains("relisten-uuid-1"))
        assertTrue(LikedTracks.entries.value.isEmpty())
    }

    @Test
    fun `deriveRecordingId extracts bare source id from tape queue and null from others`() {
        // Tape queue: relisten:artist/date/source
        assertEquals(
            "tape-source-123",
            deriveRecordingId("relisten:grateful-dead/1977-05-08/tape-source-123")
        )
        // Two-part relisten key (show queue)
        assertNull(deriveRecordingId("relisten:grateful-dead/1977-05-08"))
        // Local playlist queue
        assertNull(deriveRecordingId("local-playlist:my-playlist-id"))
        // Remote playlist queue
        assertNull(deriveRecordingId("playlist:best-of-97"))
        // phish.in show queue
        assertNull(deriveRecordingId("show:1997-11-17"))
        // YouTube queue
        assertNull(deriveRecordingId("youtube:dQw4w9WgXcQ"))
        // Null or blank queue keys
        assertNull(deriveRecordingId(null))
        assertNull(deriveRecordingId(""))
        assertNull(deriveRecordingId("random-garbage"))
    }

    @Test
    fun `toLocalPlaylistTrackRef maps backend slug recordingId and display fields`() {
        val ref = LikedTrackRef(
            id = "track-uuid-1",
            title = "Help on the Way",
            showDate = "1975-08-13",
            venueName = "Great American Music Hall",
            durationMs = 240000L,
            artistName = "Grateful Dead",
            artistSlug = "grateful-dead",
            recordingId = "source-75",
            artUrl = "https://example.com/dead.jpg",
            likedAt = 123456L,
            backend = Backend.RELISTEN.id,
        )

        val entity = ref.toLocalPlaylistTrackRef()
        assertEquals("", entity.playlistId)
        assertEquals(0, entity.position)
        assertEquals("relisten", entity.backend)
        assertEquals("track-uuid-1", entity.trackId)
        assertEquals("1975-08-13", entity.showDate)
        assertEquals("grateful-dead", entity.artistSlug)
        assertEquals("source-75", entity.recordingId)
        assertEquals("Help on the Way", entity.title)
        assertEquals(240000L, entity.durationMs)
        assertEquals("Great American Music Hall", entity.venueName)
        assertEquals("https://example.com/dead.jpg", entity.artUrl)
    }
}
