package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseTest {

    @Test
    fun `round-trips every kind of node`() {
        assertEquals(BrowseNode.Root, BrowseNode.parse(BrowseNode.Root.id))
        assertEquals(BrowseNode.Continue, BrowseNode.parse(BrowseNode.Continue.id))
        assertEquals(BrowseNode.Years, BrowseNode.parse(BrowseNode.Years.id))
        assertEquals(BrowseNode.Year("1997"), BrowseNode.parse(BrowseNode.Year("1997").id))
        assertEquals(
            BrowseNode.Tour("1997", "Fall Tour"),
            BrowseNode.parse(BrowseNode.Tour("1997", "Fall Tour").id)
        )
        assertEquals(BrowseNode.ShowNode("1997-11-17"), BrowseNode.parse(BrowseNode.ShowNode("1997-11-17").id))
        assertEquals(
            BrowseNode.Resume("show:1997-11-17"),
            BrowseNode.parse(BrowseNode.Resume("show:1997-11-17").id)
        )
    }

    @Test
    fun `keeps colons inside a tour name`() {
        val node = BrowseNode.Tour("1997", "Fall: Leg 2")
        assertEquals(node, BrowseNode.parse(node.id))
    }

    @Test
    fun `a resume node wraps a playlist queue key untouched`() {
        val node = BrowseNode.Resume(playlistQueueKey("odd:slug"))
        assertEquals(node, BrowseNode.parse(node.id))
        assertEquals("playlist:odd:slug", (BrowseNode.parse(node.id) as BrowseNode.Resume).queueKey)
    }

    @Test
    fun `rejects malformed ids instead of guessing`() {
        assertNull(BrowseNode.parse(""))
        assertNull(BrowseNode.parse("year:"))
        assertNull(BrowseNode.parse("show:"))
        assertNull(BrowseNode.parse("resume:"))
        assertNull(BrowseNode.parse("tour:1997"))
        assertNull(BrowseNode.parse("tour:1997:"))
        assertNull(BrowseNode.parse("unknown:thing"))
    }
}
