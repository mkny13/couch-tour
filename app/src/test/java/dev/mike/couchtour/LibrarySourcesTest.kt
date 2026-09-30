package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySourcesTest {

    @Test
    fun `savedShowItems on show key yields YYYY-MM-DD title`() {
        val items = savedShowItems(setOf("show:1997-11-17"))
        assertEquals(1, items.size)
        assertEquals("1997-11-17", items[0].title)
        assertEquals("Saved show", items[0].subtitle)
        assertEquals("SHOW", items[0].badge)
        assertEquals(LibraryTarget.Show("1997-11-17"), items[0].target)
        assertEquals(null, items[0].addedAt)
        assertFalse(items[0].title.contains("show:"))
    }

    @Test
    fun `savedShowItems on relisten key yields date and not raw key`() {
        val raw = "relisten:grateful-dead/1977-05-08/abc-123"
        val items = savedShowItems(setOf(raw))
        assertEquals(1, items.size)
        assertEquals("1977-05-08", items[0].title)
        assertEquals("Saved show", items[0].subtitle)
        assertEquals("SHOW", items[0].badge)
        assertFalse("Title must not contain relisten: prefix", items[0].title.contains("relisten:"))
        assertEquals(
            LibraryTarget.Recording(RecordingId("grateful-dead", "1977-05-08", "abc-123")),
            items[0].target
        )
    }

    @Test
    fun `savedShowItems on bare date yields date`() {
        val items = savedShowItems(setOf("1997-11-17"))
        assertEquals(1, items.size)
        assertEquals("1997-11-17", items[0].title)
        assertEquals(LibraryTarget.Show("1997-11-17"), items[0].target)
    }

    @Test
    fun `savedShowItems drops non-show queue keys and garbage without producing relisten titles`() {
        val badKeys = setOf(
            "playlist:foo",
            "local-playlist:abc",
            "youtube:vid123",
            "garbage",
            "",
            "relisten:invalid/recording",
            "show:invalid-date"
        )
        val items = savedShowItems(badKeys)
        assertEquals(0, items.size)
        for (item in items) {
            assertFalse(item.title.contains("relisten:"))
            assertFalse(item.title.contains("show:"))
            assertFalse(item.title.contains("playlist:"))
        }
    }

    @Test
    fun `playlistItems sorts newest updatedAt first`() {
        val older = LocalPlaylistEntity(
            id = "pl-1",
            name = "Alpha",
            trackCount = 2,
            createdAt = 1000L,
            updatedAt = 2000L
        )
        val newer = LocalPlaylistEntity(
            id = "pl-2",
            name = "Beta",
            trackCount = 5,
            createdAt = 1000L,
            updatedAt = 3000L
        )
        val items = playlistItems(listOf(older, newer))
        assertEquals(2, items.size)
        assertEquals("Beta", items[0].title)
        assertEquals(3000L, items[0].addedAt)
        assertEquals("5 tracks", items[0].subtitle)
        assertEquals("Alpha", items[1].title)
        assertEquals(2000L, items[1].addedAt)
        assertEquals("2 tracks", items[1].subtitle)
    }

    @Test
    fun `trackItems preserves rowId order`() {
        val t1 = LocalPlaylistTrackEntity(
            rowId = 10L,
            playlistId = "pl-1",
            position = 0,
            backend = "phishin",
            trackId = "100",
            showDate = "1997-11-17",
            title = "Ghost",
            durationMs = 60000L,
            venueName = "McNichols Sports Arena"
        )
        val t2 = LocalPlaylistTrackEntity(
            rowId = 20L,
            playlistId = "pl-1",
            position = 1,
            backend = "phishin",
            trackId = "101",
            showDate = "1997-11-17",
            title = "Down with Disease",
            durationMs = 120000L,
            venueName = "McNichols Sports Arena"
        )
        val items = trackItems(listOf(t1, t2))
        assertEquals(2, items.size)
        assertEquals("Down with Disease", items[0].title)
        assertEquals("trk_20", items[0].key)
        assertEquals("Ghost", items[1].title)
        assertEquals("trk_10", items[1].key)
        assertEquals(null, items[0].addedAt)
        assertEquals(null, items[1].addedAt)
    }

    @Test
    fun `sortLibraryItems puts null addedAt item after dated one under RECENTLY_ADDED`() {
        val dated = LibraryItem(
            key = "dated",
            badge = "LIST",
            title = "Dated Playlist",
            subtitle = "",
            addedAt = 5000L,
            sortKey = "Dated Playlist",
            target = LibraryTarget.LocalPlaylist("pl-1")
        )
        val undated = LibraryItem(
            key = "undated",
            badge = "SHOW",
            title = "1997-11-17",
            subtitle = "",
            addedAt = null,
            sortKey = "1997-11-17",
            target = LibraryTarget.Show("1997-11-17")
        )

        val sorted = sortLibraryItems(listOf(undated, dated), LibrarySortMode.RECENTLY_ADDED)
        assertEquals(listOf(dated, undated), sorted)
    }

    @Test
    fun `sortLibraryItems TITLE_ASC and TITLE_DESC ignore timestamps entirely`() {
        val itemA = LibraryItem(
            key = "a",
            badge = "SHOW",
            title = "Apple",
            subtitle = "",
            addedAt = 1000L,
            sortKey = "Apple",
            target = LibraryTarget.Show("1997-11-17")
        )
        val itemZ = LibraryItem(
            key = "z",
            badge = "SHOW",
            title = "Zebra",
            subtitle = "",
            addedAt = 9000L,
            sortKey = "Zebra",
            target = LibraryTarget.Show("1998-11-17")
        )

        val asc = sortLibraryItems(listOf(itemZ, itemA), LibrarySortMode.TITLE_ASC)
        assertEquals(listOf(itemA, itemZ), asc)

        val desc = sortLibraryItems(listOf(itemA, itemZ), LibrarySortMode.TITLE_DESC)
        assertEquals(listOf(itemZ, itemA), desc)
    }

    @Test
    fun `filterLibraryItems filters by title subtitle and search terms`() {
        val item1 = LibraryItem(
            key = "1",
            badge = "TRACK",
            title = "Tweezer",
            subtitle = "1995-12-02 · New Haven Coliseum",
            addedAt = null,
            sortKey = "Tweezer",
            target = LibraryTarget.LocalPlaylist("pl-1"),
            searchTerms = listOf("1995-12-02", "New Haven Coliseum", "phishin")
        )
        val item2 = LibraryItem(
            key = "2",
            badge = "SHOW",
            title = "1997-11-17",
            subtitle = "Saved show",
            addedAt = null,
            sortKey = "1997-11-17",
            target = LibraryTarget.Show("1997-11-17")
        )

        val list = listOf(item1, item2)
        assertEquals(list, filterLibraryItems(list, ""))
        assertEquals(listOf(item1), filterLibraryItems(list, "Tweezer"))
        assertEquals(listOf(item1), filterLibraryItems(list, "New Haven"))
        assertEquals(listOf(item2), filterLibraryItems(list, "1997"))
        assertEquals(emptyList<LibraryItem>(), filterLibraryItems(list, "Nonexistent"))
    }

    @Test
    fun `historyDisplayTitle sanitizes queue prefixes and falls back safely`() {
        assertEquals("1997-11-17", historyDisplayTitle("1997-11-17", "show:1997-11-17"))
        assertEquals("1997-11-17", historyDisplayTitle("show:1997-11-17", "show:1997-11-17"))
        assertEquals("1977-05-08", historyDisplayTitle("relisten:grateful-dead/1977-05-08/abc-123", "relisten:grateful-dead/1977-05-08/abc-123"))
        assertEquals("1977-05-08", historyDisplayTitle("", "relisten:grateful-dead/1977-05-08/abc-123"))
        assertEquals("1997-11-17", historyDisplayTitle("", "show:1997-11-17"))
        assertEquals("Removed show", historyDisplayTitle("playlist:summer-97", "playlist:summer-97"))
        assertEquals("Removed show", historyDisplayTitle("local-playlist:abc", "local-playlist:abc"))
        assertEquals("Removed show", historyDisplayTitle("youtube:123", "youtube:123"))
        assertEquals("Removed show", historyDisplayTitle("", "garbage-key"))
        assertEquals("My Show Name", historyDisplayTitle("My Show Name", "show:1997-11-17"))
    }
}
