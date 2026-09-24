package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueTest {

    @Test
    fun `builds namespaced keys`() {
        assertEquals("show:1997-02-13", showQueueKey("1997-02-13"))
        assertEquals("playlist:phishnet-key-jams-pt-1", playlistQueueKey("phishnet-key-jams-pt-1"))
    }

    @Test
    fun `round-trips a show key`() {
        val ref = parseQueueKey(showQueueKey("1997-02-13"))
        assertEquals(QueueRef(QueueKind.SHOW, "1997-02-13"), ref)
        assertEquals("show:1997-02-13", ref!!.key)
    }

    @Test
    fun `round-trips a playlist key`() {
        val ref = parseQueueKey(playlistQueueKey("some-slug"))
        assertEquals(QueueRef(QueueKind.PLAYLIST, "some-slug"), ref)
        assertEquals("playlist:some-slug", ref!!.key)
    }

    @Test
    fun `rejects unknown or malformed keys instead of guessing`() {
        // Playing an unrecognised key as the wrong kind would fetch the wrong thing.
        assertNull(parseQueueKey(""))
        assertNull(parseQueueKey("1997-02-13"))
        assertNull(parseQueueKey("album:1997-02-13"))
        assertNull(parseQueueKey("show:"))
        assertNull(parseQueueKey("playlist:"))
    }

    @Test
    fun `keeps colons inside a playlist slug`() {
        val ref = parseQueueKey("playlist:odd:slug")
        assertEquals(QueueRef(QueueKind.PLAYLIST, "odd:slug"), ref)
    }

    @Test
    fun `does not confuse a show slug that starts with the other prefix`() {
        // A playlist literally called "show:..." must still parse as a playlist.
        assertEquals(
            QueueRef(QueueKind.PLAYLIST, "show:1997-02-13"),
            parseQueueKey("playlist:show:1997-02-13")
        )
    }

    // ------------------------------------------------------------- recordings

    @Test
    fun `builds a recording key from its three parts`() {
        assertEquals(
            "relisten:grateful-dead/1977-05-08/2ab1c5f0-9b1e-4f7a-8c3d-1e2f3a4b5c6d",
            recordingQueueKey("grateful-dead", "1977-05-08", "2ab1c5f0-9b1e-4f7a-8c3d-1e2f3a4b5c6d")
        )
    }

    @Test
    fun `round-trips a recording key`() {
        val key = recordingQueueKey("wsp", "2001-04-22", "src-uuid")
        val ref = parseQueueKey(key)
        assertEquals(QueueRef(QueueKind.RECORDING, "wsp/2001-04-22/src-uuid"), ref)
        assertEquals(key, ref!!.key)
        assertEquals(RecordingId("wsp", "2001-04-22", "src-uuid"), parseRecordingId(ref.id))
    }

    @Test
    fun `rejects a recording key that is missing parts`() {
        // Two tapes of one show have different track boundaries, so a key without its source
        // would resume a stored index against the wrong track list.
        assertNull(parseQueueKey("relisten:"))
        assertNull(parseQueueKey("relisten:wsp"))
        assertNull(parseQueueKey("relisten:wsp/2001-04-22"))
        assertNull(parseQueueKey("relisten:wsp/2001-04-22/src/extra"))
        assertNull(parseQueueKey("relisten:wsp//src-uuid"))
        assertNull(parseQueueKey("relisten://2001-04-22/src-uuid"))
    }

    @Test
    fun `parses a recording id independently of its prefix`() {
        assertEquals(RecordingId("phish", "1997-11-17", "u"), parseRecordingId("phish/1997-11-17/u"))
        assertNull(parseRecordingId(""))
        assertNull(parseRecordingId("phish/1997-11-17"))
    }

    @Test
    fun `recordingShowKey builds two-part key and parseQueueKey rejects it`() {
        // recordingShowKey produces a two-part key ("relisten:artistSlug/date") for show-level matching.
        val showKey = recordingShowKey("grateful-dead", "1977-05-08")
        assertEquals("relisten:grateful-dead/1977-05-08", showKey)

        // parseQueueKey must reject two-part show keys: a recording queue cannot be resumed
        // without a specific source/tape id.
        assertNull(parseQueueKey(showKey))
    }

    @Test
    fun `rejects malformed recording ids with wrong part counts or empty segments`() {
        assertNull(parseRecordingId("/artist/1977-05-08/src"))
        assertNull(parseRecordingId("artist/1977-05-08/src/"))
        assertNull(parseRecordingId("artist//src"))
        assertNull(parseRecordingId("artist/1977-05-08/src/extra"))
        assertNull(parseRecordingId("/"))
        assertNull(parseRecordingId("//"))
    }

    @Test
    fun `a colon inside a recording part is not a delimiter`() {
        // Recording parts are split on "/" precisely so the first-colon-only rule that show
        // and playlist keys live under never applies here.
        assertEquals(
            QueueRef(QueueKind.RECORDING, "odd:slug/1977-05-08/src"),
            parseQueueKey("relisten:odd:slug/1977-05-08/src")
        )
    }

    @Test
    fun `recording keys do not collide with the phish-in prefixes`() {
        // The whole reason no migration is needed: an existing "show:" row is untouched.
        assertEquals(QueueKind.SHOW, parseQueueKey("show:1997-02-13")!!.kind)
        assertEquals(QueueKind.PLAYLIST, parseQueueKey("playlist:relisten:x")!!.kind)
        assertEquals(QueueKind.RECORDING, parseQueueKey("relisten:a/b/c")!!.kind)
    }

    // ------------------------------------------------------------ local playlists (#12)

    @Test
    fun `builds a local playlist key`() {
        assertEquals("local-playlist:abc-123", localPlaylistQueueKey("abc-123"))
    }

    @Test
    fun `round-trips a local playlist key`() {
        val ref = parseQueueKey(localPlaylistQueueKey("abc-123"))
        assertEquals(QueueRef(QueueKind.LOCAL_PLAYLIST, "abc-123"), ref)
        assertEquals("local-playlist:abc-123", ref!!.key)
    }

    @Test
    fun `rejects an empty local playlist key`() {
        assertNull(parseQueueKey("local-playlist:"))
    }

    @Test
    fun `a local playlist key does not get parsed as a phish-in playlist`() {
        // A bare "playlist:" id routes to PhishInApi.playlist; a local one has no such slug.
        assertEquals(QueueKind.LOCAL_PLAYLIST, parseQueueKey("local-playlist:abc-123")!!.kind)
    }

    // ---------------------------------------------------------------- YouTube (#234)

    @Test
    fun `builds a youtube key from a video id`() {
        assertEquals("youtube:dQw4w9WgXcQ", youtubeProgressKey("dQw4w9WgXcQ"))
    }

    @Test
    fun `round-trips a youtube key`() {
        val ref = parseQueueKey(youtubeProgressKey("dQw4w9WgXcQ"))
        assertEquals(QueueRef(QueueKind.YOUTUBE, "dQw4w9WgXcQ"), ref)
        assertEquals("youtube:dQw4w9WgXcQ", ref!!.key)
    }

    @Test
    fun `rejects an empty youtube key`() {
        assertNull(parseQueueKey("youtube:"))
    }

    @Test
    fun `youtube keys do not collide with the other prefixes`() {
        // The prefix decides the kind; a video id is opaque and may embed another prefix's
        // literal, so each existing key must keep parsing as it always has.
        assertEquals(QueueKind.SHOW, parseQueueKey("show:1997-02-13")!!.kind)
        assertEquals(QueueKind.PLAYLIST, parseQueueKey("playlist:youtube:x")!!.kind)
        assertEquals(QueueKind.RECORDING, parseQueueKey("relisten:youtube/1977-05-08/src")!!.kind)
        assertEquals(QueueKind.LOCAL_PLAYLIST, parseQueueKey("local-playlist:youtube:x")!!.kind)
        assertEquals(QueueKind.YOUTUBE, parseQueueKey("youtube:1997-02-13")!!.kind)
        assertEquals(
            QueueRef(QueueKind.YOUTUBE, "show:1997-02-13"),
            parseQueueKey("youtube:show:1997-02-13"),
        )
    }

    @Test
    fun `the next-stop youtube-show identity is not a queue key`() {
        // NextStop's "have I played this show?" key starts "youtube-", never "youtube:",
        // so a progress row can never be mistaken for a video — or vice versa.
        assertNull(parseQueueKey("youtube-show:UC123"))
    }
}