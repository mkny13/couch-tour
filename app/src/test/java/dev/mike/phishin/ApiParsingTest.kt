package dev.mike.phishin

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes real phish.in responses, trimmed. The fixtures keep every field the API sends,
 * including ones the app ignores, so a decoder that isn't tolerant of unknown keys fails
 * here rather than in the user's hands.
 */
class ApiParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().readText()

    // ------------------------------------------------------------------ shows

    @Test
    fun `parses a show with its venue and tracks`() {
        val show = json.decodeFromString<Show>(fixture("show.json"))
        assertEquals("1997-11-17", show.date)
        assertEquals("McNichols Arena", show.venueName)
        assertEquals("Denver, CO", show.location)
        assertEquals("complete", show.audioStatus)
        assertEquals(3, show.tracks.size)
        assertEquals("Tweezer", show.tracks[0].title)
    }

    @Test
    fun `treats a track with no audio as unplayable`() {
        val show = json.decodeFromString<Show>(fixture("show.json"))
        val playable = show.tracks.filter { it.playable }

        // Partial shows have gaps; queueing a null mp3_url would stall playback.
        assertEquals(2, playable.size)
        assertTrue(show.tracks[0].playable)
        assertFalse(show.tracks[2].playable)
        assertNull(show.tracks[2].mp3Url)
    }

    @Test
    fun `reads durations in milliseconds`() {
        val show = json.decodeFromString<Show>(fixture("show.json"))
        assertEquals(1_072_274L, show.tracks[0].duration)
        assertEquals("17:52", fmt(show.tracks[0].duration))
    }

    @Test
    fun `exposes waveform and set name per track`() {
        val track = json.decodeFromString<Show>(fixture("show.json")).tracks[0]
        assertEquals("Set 1", track.setName)
        assertTrue(track.waveformImageUrl!!.endsWith(".png"))
    }

    // ------------------------------------------------------------------ years

    @Test
    fun `parses periods including multi-year ranges`() {
        val periods = json.decodeFromString<List<Period>>(fixture("years.json"))
        assertTrue(periods.isNotEmpty())

        // The early entry is a range, not a year. This is the trap that makes
        // showsForPeriod pick between year= and year_range=.
        val first = periods.first()
        assertEquals("1983-1987", first.period)
        assertTrue(first.period.contains("-"))

        val single = periods.first { it.period == "1997" }
        assertFalse(single.period.contains("-"))
    }

    @Test
    fun `periods report how many shows actually have audio`() {
        val periods = json.decodeFromString<List<Period>>(fixture("years.json"))
        val early = periods.first { it.period == "1983-1987" }

        // Far fewer shows have audio than exist; the browse list must show the audio count.
        assertTrue(early.showsWithAudioCount < early.showsCount)
        assertTrue(early.showsWithAudioCount > 0)
    }

    // ----------------------------------------------------------------- search

    @Test
    fun `parses search results across all three sections`() {
        val results = json.decodeFromString<SearchResults>(fixture("search.json"))
        assertFalse(results.isEmpty)
        assertEquals(2, results.otherShows.size)
        assertEquals(2, results.tracks.size)
        assertEquals(2, results.playlists.size)
    }

    @Test
    fun `search tracks carry the show that they came from`() {
        val track = json.decodeFromString<SearchResults>(fixture("search.json")).tracks.first()

        // Without show_date a search hit can't be played inside its show.
        assertNotNull(track.showDate)
        assertNotNull(track.venueName)
        assertTrue(track.playable)
    }

    @Test
    fun `folds exact_show into the shows list when present`() {
        val withExact = json.decodeFromString<SearchResults>(
            """{"exact_show":{"date":"1997-02-13"},"other_shows":[{"date":"1998-08-15"}]}"""
        )
        assertEquals(listOf("1997-02-13", "1998-08-15"), withExact.shows.map { it.date })
    }

    @Test
    fun `handles a null exact_show`() {
        val results = json.decodeFromString<SearchResults>(fixture("search.json"))
        assertNull(results.exactShow)
        assertEquals(results.otherShows.size, results.shows.size)
    }

    @Test
    fun `reports an entirely empty result set`() {
        val empty = json.decodeFromString<SearchResults>(
            """{"exact_show":null,"other_shows":[],"tracks":[],"playlists":[]}"""
        )
        assertTrue(empty.isEmpty)
    }

    // -------------------------------------------------------------- playlists

    @Test
    fun `parses a playlist and its entries`() {
        val playlist = json.decodeFromString<Playlist>(fixture("playlist.json"))
        assertEquals("phishnet-key-jams-pt-1", playlist.slug)
        assertEquals("mfhgreyboy", playlist.username)
        assertEquals(2, playlist.entries.size)
        assertEquals("The Curtain With", playlist.entries[0].track.title)
    }

    @Test
    fun `reads excerpt bounds on a playlist entry`() {
        val entries = json.decodeFromString<Playlist>(fixture("playlist.json")).entries

        // Ignoring these plays the whole track instead of the excerpt the playlist chose.
        assertNull(entries[0].startsAtSecond)
        assertEquals(30, entries[1].startsAtSecond)
        assertEquals(90, entries[1].endsAtSecond)
    }

    @Test
    fun `entry duration is the clipped length not the whole track`() {
        val clipped = json.decodeFromString<Playlist>(fixture("playlist.json")).entries[1]
        assertEquals(60_000L, clipped.duration)
        assertTrue(clipped.duration < clipped.track.duration)
    }

    @Test
    fun `list endpoints return playlists with no entries`() {
        // Only the single-playlist endpoint populates entries; the UI must not expect them.
        val fromList = json.decodeFromString<Playlist>(
            """{"id":1,"slug":"s","name":"n","tracks_count":26}"""
        )
        assertTrue(fromList.entries.isEmpty())
        assertEquals(26, fromList.tracksCount)
    }

    // ------------------------------------------------------------------- auth

    @Test
    fun `parses a login response`() {
        val login = json.decodeFromString<LoginResponse>(fixture("login.json"))
        assertEquals("header.payload.signature", login.jwt)
        assertEquals("mike", login.username)
    }

    // --------------------------------------------------------------- defaults

    @Test
    fun `tolerates missing optional fields`() {
        val bare = json.decodeFromString<Show>("""{"date":"1997-02-13"}""")
        assertNull(bare.venueName)
        assertNull(bare.location)
        assertEquals("missing", bare.audioStatus)
        assertTrue(bare.tracks.isEmpty())
    }

    @Test
    fun `treats a blank mp3 url as unplayable`() {
        val blank = json.decodeFromString<Track>(
            """{"id":1,"title":"x","mp3_url":"","audio_status":"complete"}"""
        )
        assertFalse(blank.playable)
    }

    @Test
    fun `treats a present url with missing status as unplayable`() {
        val mismatch = json.decodeFromString<Track>(
            """{"id":1,"title":"x","mp3_url":"https://phish.in/blob/a.mp3","audio_status":"missing"}"""
        )
        assertFalse(mismatch.playable)
    }
}
