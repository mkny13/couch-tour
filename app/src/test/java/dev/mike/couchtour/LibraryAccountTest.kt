package dev.mike.couchtour

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryAccountTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        PhishInApi.baseUrl = server.url("/api/v2")
        PhishInApi.authToken = null
        PhishInApi.onUnauthorized = null
    }

    @After
    fun tearDown() {
        server.shutdown()
        PhishInApi.baseUrl = "https://phish.in/api/v2".toHttpUrl()
        PhishInApi.authToken = null
        PhishInApi.onUnauthorized = null
    }

    @Test
    fun `loadLibraryAccount with no username performs zero requests and returns empty lists`() = runBlocking {
        // D26 guard: unauthenticated calls to filter=mine return all 2,504 public playlists.
        // With no username, zero network requests must be performed.
        val result = loadLibraryAccount(api = PhishInApi, username = null)
        assertEquals(0, server.requestCount)
        assertTrue(result.loaded)
        assertFalse(result.error)
        assertTrue(result.playlists.isEmpty())
        assertTrue(result.shows.isEmpty())
        assertTrue(result.tracks.isEmpty())
    }

    @Test
    fun `loadLibraryAccount with empty username performs zero requests`() = runBlocking {
        val result = loadLibraryAccount(api = PhishInApi, username = "   ")
        assertEquals(0, server.requestCount)
        assertTrue(result.loaded)
        assertFalse(result.error)
        assertTrue(result.playlists.isEmpty())
    }

    @Test
    fun `loadLibraryAccount on network failure returns error without throwing`() = runBlocking {
        // loadLibraryAccount fans out four parallel requests; a single enqueued 500 leaves the
        // other three unanswered until the client read timeout (~30s), so answer all of them.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                MockResponse().setResponseCode(500)
        }
        val result = loadLibraryAccount(api = PhishInApi, username = "mike")
        assertTrue(result.loaded)
        assertTrue(result.error)
        assertTrue(result.playlists.isEmpty())
        assertTrue(result.shows.isEmpty())
        assertTrue(result.tracks.isEmpty())
    }

    @Test
    fun `playlist merge dedupes a slug present in both mine and liked`() {
        val minePlaylist = Playlist(
            id = 1,
            slug = "fall-97",
            name = "Fall 97 Highlights",
            tracksCount = 12
        )
        val likedPlaylist = Playlist(
            id = 2,
            slug = "fall-97",
            name = "Fall 97 Highlights (duplicate)",
            tracksCount = 12
        )
        val otherPlaylist = Playlist(
            id = 3,
            slug = "island-tour",
            name = "Island Tour",
            tracksCount = 8
        )

        val items = accountPlaylistItems(listOf(minePlaylist, likedPlaylist, otherPlaylist))
        assertEquals(2, items.size)
        assertEquals("Fall 97 Highlights", items[0].title)
        assertEquals(LibraryTarget.Playlist("fall-97"), items[0].target)
        assertEquals("Island Tour", items[1].title)
        assertEquals(LibraryTarget.Playlist("island-tour"), items[1].target)
        assertEquals("phish.in · 12 tracks", items[0].subtitle)
    }

    @Test
    fun `show saved locally and liked on phish in produces one row keeping dated addedAt`() {
        val localShow = LibraryItem(
            key = "show_1997-11-17",
            rawKey = "show:1997-11-17",
            badge = "SHOW",
            title = "1997-11-17",
            subtitle = "Saved show",
            addedAt = 123456789L,
            sortKey = "1997-11-17",
            target = LibraryTarget.Show("1997-11-17")
        )
        val accountShow = accountShowItems(
            listOf(
                Show(
                    date = "1997-11-17",
                    venueName = "McNichols Sports Arena",
                    venue = Venue(name = "McNichols Sports Arena", location = "Denver, CO")
                )
            )
        ).first()

        val merged = mergeLibraryShows(listOf(localShow), listOf(accountShow))
        assertEquals(1, merged.size)
        assertEquals(123456789L, merged[0].addedAt)
        assertEquals(LibraryTarget.Show("1997-11-17"), merged[0].target)
        assertEquals("show:1997-11-17", merged[0].rawKey)
    }

    @Test
    fun `show saved locally and liked on phish in when both undated preserves rawKey and uses detailed subtitle`() {
        val localShow = LibraryItem(
            key = "show_1997-11-17",
            rawKey = "show:1997-11-17",
            badge = "SHOW",
            title = "1997-11-17",
            subtitle = "Saved show",
            addedAt = null,
            sortKey = "1997-11-17",
            target = LibraryTarget.Show("1997-11-17")
        )
        val accountShow = accountShowItems(
            listOf(
                Show(
                    date = "1997-11-17",
                    venueName = "McNichols Sports Arena",
                    venue = Venue(name = "McNichols Sports Arena", location = "Denver, CO")
                )
            )
        ).first()

        val merged = mergeLibraryShows(listOf(localShow), listOf(accountShow))
        assertEquals(1, merged.size)
        assertEquals(null, merged[0].addedAt)
        assertEquals("McNichols Sports Arena · Denver, CO", merged[0].subtitle)
        assertEquals("show:1997-11-17", merged[0].rawKey)
    }

    @Test
    fun `track liked on both backends produces one row per backend and trackId`() {
        val phishTrack = Track(
            id = 42L,
            title = "Ghost",
            duration = 600000L,
            showDate = "1997-11-17",
            venueName = "McNichols Sports Arena"
        )
        val relistenRef = LikedTrackRef(
            id = "42",
            title = "Help on the Way",
            artistName = "Grateful Dead",
            showDate = "1975-08-13",
            venueName = "Great American Music Hall",
            backend = "relisten",
            likedAt = 1000L
        )

        val accountItem = accountTrackItems(listOf(phishTrack)).first()
        val relistenItem = relistenLikedTrackItems(listOf(relistenRef)).first()

        val merged = mergeLibraryTracks(
            local = emptyList(),
            account = listOf(accountItem),
            relisten = listOf(relistenItem)
        )
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.backend == "phishin" && it.trackId == "42" && it.title == "Ghost" })
        assertTrue(merged.any { it.backend == "relisten" && it.trackId == "42" && it.title == "Help on the Way" })
    }

    @Test
    fun `track liked on phish in and in local playlist preserves both rows and distinct navigation targets`() {
        val localTrack = LibraryItem(
            key = "trk_1",
            rawKey = "1",
            badge = "TRACK",
            title = "Ghost",
            subtitle = "1997-11-17 · McNichols Sports Arena",
            addedAt = 999999L,
            sortKey = "Ghost",
            target = LibraryTarget.LocalPlaylist("pl-1"),
            backend = "phishin",
            trackId = "42"
        )
        val accountTrack = accountTrackItems(
            listOf(
                Track(
                    id = 42L,
                    title = "Ghost",
                    duration = 600000L,
                    showDate = "1997-11-17",
                    venueName = "McNichols Sports Arena"
                )
            )
        ).first()

        val merged = mergeLibraryTracks(
            local = listOf(localTrack),
            account = listOf(accountTrack)
        )
        assertEquals(2, merged.size)
        val playlistItem = merged.first { it.target is LibraryTarget.LocalPlaylist }
        val accountItem = merged.first { it.target is LibraryTarget.AccountTrack }
        assertEquals("pl-1", (playlistItem.target as LibraryTarget.LocalPlaylist).id)
        assertEquals(42L, (accountItem.target as LibraryTarget.AccountTrack).track.id)
    }

    @Test
    fun `track in multiple local playlists preserves all playlist rows without deduplicating away navigation targets`() {
        val trackInPl1 = LibraryItem(
            key = "trk_1",
            rawKey = "1",
            badge = "TRACK",
            title = "Ghost",
            subtitle = "1997-11-17 · McNichols Sports Arena",
            addedAt = null,
            sortKey = "Ghost",
            target = LibraryTarget.LocalPlaylist("pl-1"),
            backend = "phishin",
            trackId = "42"
        )
        val trackInPl2 = LibraryItem(
            key = "trk_2",
            rawKey = "2",
            badge = "TRACK",
            title = "Ghost",
            subtitle = "1997-11-17 · McNichols Sports Arena",
            addedAt = null,
            sortKey = "Ghost",
            target = LibraryTarget.LocalPlaylist("pl-2"),
            backend = "phishin",
            trackId = "42"
        )

        val merged = mergeLibraryTracks(
            local = listOf(trackInPl1, trackInPl2),
            account = emptyList()
        )
        assertEquals(2, merged.size)
        assertEquals(
            setOf("pl-1", "pl-2"),
            merged.mapNotNull { (it.target as? LibraryTarget.LocalPlaylist)?.id }.toSet()
        )
    }

    @Test
    fun `null addedAt account row sorts after dated local row under RECENTLY_ADDED but is reached by TITLE_ASC`() {
        val datedLocal = LibraryItem(
            key = "local_zebra",
            badge = "LIST",
            title = "Zebra Playlist",
            subtitle = "Local",
            addedAt = 50000L,
            sortKey = "Zebra Playlist",
            target = LibraryTarget.LocalPlaylist("pl-1")
        )
        val undatedAccount = LibraryItem(
            key = "account_apple",
            badge = "LIST",
            title = "Apple Playlist",
            subtitle = "Account",
            addedAt = null,
            sortKey = "Apple Playlist",
            target = LibraryTarget.Playlist("apple-playlist")
        )

        val recentlyAdded = sortLibraryItems(listOf(undatedAccount, datedLocal), LibrarySortMode.RECENTLY_ADDED)
        assertEquals("Zebra Playlist", recentlyAdded[0].title)
        assertEquals("Apple Playlist", recentlyAdded[1].title)

        val titleAsc = sortLibraryItems(listOf(datedLocal, undatedAccount), LibrarySortMode.TITLE_ASC)
        assertEquals("Apple Playlist", titleAsc[0].title)
        assertEquals("Zebra Playlist", titleAsc[1].title)
    }

    @Test
    fun `every LibraryItem produced from Show Track Playlist carries non-blank title without queue prefix`() {
        val badPlaylist = Playlist(
            id = 1,
            slug = "bad",
            name = "playlist:my-playlist",
            tracksCount = 5
        )
        val blankPlaylist = Playlist(
            id = 2,
            slug = "blank",
            name = "   ",
            tracksCount = 0
        )
        val playlistItems = accountPlaylistItems(listOf(badPlaylist, blankPlaylist))
        for (item in playlistItems) {
            assertTrue(item.title.isNotBlank())
            assertFalse(item.title.startsWith("playlist:"))
        }

        val badShow = Show(date = "show:1997-11-17")
        val showItems = accountShowItems(listOf(badShow))
        for (item in showItems) {
            assertTrue(item.title.isNotBlank())
            assertFalse(item.title.startsWith("show:"))
            assertEquals("1997-11-17", item.title)
        }

        val badTrack = Track(id = 1, title = "local-playlist:test")
        val blankTrack = Track(id = 2, title = "")
        val trackItems = accountTrackItems(listOf(badTrack, blankTrack))
        for (item in trackItems) {
            assertTrue(item.title.isNotBlank())
            assertFalse(item.title.startsWith("local-playlist:"))
            assertFalse(item.title.isBlank())
        }

        val badRelistenRef = LikedTrackRef(
            id = "rec1",
            title = "relisten:grateful-dead/1977-05-08/123",
            artistSlug = "grateful-dead",
            showDate = "1977-05-08",
            recordingId = "123"
        )
        val youtubeRelistenRef = LikedTrackRef(
            id = "rec2",
            title = "youtube:dQw4w9WgXcQ",
            artistSlug = "",
            showDate = "1977-05-08",
            recordingId = null
        )
        val relistenItems = relistenLikedTrackItems(listOf(badRelistenRef, youtubeRelistenRef))
        for (item in relistenItems) {
            assertTrue(item.title.isNotBlank())
            assertFalse(item.title.startsWith("relisten:"))
            assertFalse(item.title.startsWith("youtube:"))
        }
    }

    @Test
    fun `relisten liked track with null recordingId falls back to Show target`() {
        val refWithoutRec = LikedTrackRef(
            id = "track-1",
            title = "Sugaree",
            artistSlug = "grateful-dead",
            showDate = "1977-05-08",
            recordingId = null
        )
        val refWithRec = LikedTrackRef(
            id = "track-2",
            title = "Scarlet Begonias",
            artistSlug = "grateful-dead",
            showDate = "1977-05-08",
            recordingId = "rec-456"
        )

        val items = relistenLikedTrackItems(listOf(refWithoutRec, refWithRec))
        assertEquals(2, items.size)
        val sugaree = items.first { it.title == "Sugaree" }
        val scarlet = items.first { it.title == "Scarlet Begonias" }

        assertEquals(LibraryTarget.Show("1977-05-08"), sugaree.target)
        assertEquals(
            LibraryTarget.Recording(RecordingId("grateful-dead", "1977-05-08", "rec-456")),
            scarlet.target
        )
    }
}
