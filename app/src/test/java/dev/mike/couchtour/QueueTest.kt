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
}
