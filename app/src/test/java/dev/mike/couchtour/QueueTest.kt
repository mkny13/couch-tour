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
}
