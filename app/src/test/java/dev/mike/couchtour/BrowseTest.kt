package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // -------------------------------------------------------------- artists

    @Test
    fun `round-trips the artists node and its children`() {
        assertEquals(BrowseNode.Artists, BrowseNode.parse(BrowseNode.Artists.id))
        assertEquals(
            BrowseNode.Artist("phishin", "phish"),
            BrowseNode.parse(BrowseNode.Artist("phishin", "phish").id)
        )
        assertEquals(
            BrowseNode.ArtistPeriod("phishin", "phish", "1997"),
            BrowseNode.parse(BrowseNode.ArtistPeriod("phishin", "phish", "1997").id)
        )
        assertEquals(
            BrowseNode.Recording("phishin", "phish", "1997-11-17"),
            BrowseNode.parse(BrowseNode.Recording("phishin", "phish", "1997-11-17").id)
        )
        assertEquals(
            BrowseNode.Artist("relisten", "grateful-dead"),
            BrowseNode.parse(BrowseNode.Artist("relisten", "grateful-dead").id)
        )
        assertEquals(
            BrowseNode.ArtistPeriod("relisten", "grateful-dead", "year-uuid"),
            BrowseNode.parse(BrowseNode.ArtistPeriod("relisten", "grateful-dead", "year-uuid").id)
        )
        assertEquals(
            BrowseNode.Recording("relisten", "grateful-dead", "1977-05-08"),
            BrowseNode.parse(BrowseNode.Recording("relisten", "grateful-dead", "1977-05-08").id)
        )
    }

    @Test
    fun `rejects malformed artist ids instead of guessing`() {
        assertNull(BrowseNode.parse("artist:"))
        assertNull(BrowseNode.parse("artist:relisten"))
        assertNull(BrowseNode.parse("artist:relisten:"))
        assertNull(BrowseNode.parse("artistperiod:relisten:grateful-dead"))
        assertNull(BrowseNode.parse("recording:relisten:grateful-dead"))
    }

    @Test
    fun `an artistperiod id is not mistaken for an artist id`() {
        // "artistperiod:" starts with "artist" but not "artist:" — the two prefixes must not
        // collide even though one is a textual prefix of the other's name.
        val node = BrowseNode.ArtistPeriod("relisten", "wsp", "period-uuid")
        assertEquals(node, BrowseNode.parse(node.id))
        assertTrue(node.id.startsWith("artistperiod:"))
    }
}
