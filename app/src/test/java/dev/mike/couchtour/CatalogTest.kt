package dev.mike.couchtour

import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phish.in half of the [MusicSource] seam. The mapping is kept in pure functions so it
 * can be checked without a network call, the same reasoning D36 used to move `fmt` and the
 * queue-key parser out of the Compose files.
 */
class CatalogTest {

    private fun track(
        id: Long = 1,
        title: String = "Tweezer",
        setName: String = "Set 2",
        duration: Long = 1_200_000,
        mp3Url: String? = "https://phish.in/a.mp3",
        audioStatus: String = "complete",
        waveformImageUrl: String? = "https://phish.in/w.png",
        showDate: String? = "1997-11-17",
        venueName: String? = "McNichols Arena",
        showAlbumCoverUrl: String? = null,
    ) = Track(
        id = id, title = title, setName = setName, duration = duration, mp3Url = mp3Url,
        audioStatus = audioStatus, waveformImageUrl = waveformImageUrl, showDate = showDate,
        venueName = venueName, showAlbumCoverUrl = showAlbumCoverUrl,
    )

    private fun show(
        date: String = "1997-11-17",
        audioStatus: String = "complete",
        tracks: List<Track> = listOf(track()),
        albumCoverUrl: String? = "https://phish.in/cover.jpg",
    ) = Show(
        date = date, venueName = "McNichols Arena", tourName = "1997 Fall Tour",
        audioStatus = audioStatus, albumCoverUrl = albumCoverUrl,
        venue = Venue(name = "McNichols Arena", location = "Denver, CO"), tracks = tracks,
    )

    // ---------------------------------------------------------------- backend

    @Test
    fun `backend ids round-trip, because they travel in nav routes`() {
        Backend.entries.forEach { assertEquals(it, Backend.from(it.id)) }
        assertNull(Backend.from("archive"))
        assertNull(Backend.from(""))
    }

    // ---------------------------------------------------------------- periods

    @Test
    fun `a phish-in period keeps its own string as its id`() {
        // "1983-1987" is not a year, and showsForPeriod needs it back verbatim to pick
        // year_range= over year= (D11).
        val ref = Period(period = "1983-1987", showsWithAudioCount = 12).toPeriodRef()
        assertEquals("1983-1987", ref.id)
        assertEquals("1983-1987", ref.label)
        assertEquals(12, ref.showCount)
    }

    @Test
    fun `a period takes its art from the cover art urls`() {
        val ref = Period(period = "1997", coverArtUrls = CoverArt(medium = "m.jpg")).toPeriodRef()
        assertEquals("m.jpg", ref.artUrl)
    }

    // ------------------------------------------------------------------ shows

    @Test
    fun `a show maps to a summary with its venue and location split`() {
        val s = show().toShowSummary()
        assertEquals(PHISH, s.artist)
        assertEquals("1997-11-17", s.date)
        assertEquals("McNichols Arena", s.venue)
        assertEquals("Denver, CO", s.location)
        assertEquals("1997 Fall Tour", s.tourName)
        assertEquals("McNichols Arena · Denver, CO", s.where)
    }

    @Test
    fun `only a partial show is flagged partial`() {
        assertTrue(show(audioStatus = "partial").toShowSummary().partial)
        assertFalse(show(audioStatus = "complete").toShowSummary().partial)
    }

    @Test
    fun `phish-in has exactly one recording per show`() {
        // The concept that makes Relisten different. phish.in has one audio per date, so
        // there is no tape to choose and nothing to switch between.
        val detail = show().toShowDetail()
        assertEquals(1, detail.summary.recordingCount)
        assertNull(detail.recording)
        assertTrue(detail.alternates.isEmpty())
    }

    // ----------------------------------------------------------------- tracks

    @Test
    fun `phish-in durations are already milliseconds and are not converted`() {
        // Relisten's are seconds; this is the pair that makes the conversion easy to get
        // backwards, so both halves are pinned.
        assertEquals(1_200_000L, track(duration = 1_200_000).toPlayableTrack(null).durationMs)
    }

    @Test
    fun `unplayable tracks are dropped, so an index means one thing`() {
        // D12: the queue index refers to the filtered list, so the UI and the queue builder
        // have to filter identically. Mapping here is what makes that automatic.
        val detail = show(
            tracks = listOf(
                track(id = 1, title = "Tweezer"),
                track(id = 2, title = "Missing", mp3Url = null),
                track(id = 3, title = "Also missing", audioStatus = "missing"),
                track(id = 4, title = "Reprise"),
            )
        ).toShowDetail()
        assertEquals(listOf("Tweezer", "Reprise"), detail.tracks.map { it.title })
    }

    @Test
    fun `a track falls back to the show art when it has none of its own`() {
        assertEquals("show.jpg", track(showAlbumCoverUrl = null).toPlayableTrack("show.jpg").artUrl)
        assertEquals("own.jpg", track(showAlbumCoverUrl = "own.jpg").toPlayableTrack("show.jpg").artUrl)
    }

    @Test
    fun `a track keeps the show it was played at`() {
        // albumFor builds the scrobbled album out of these two (D50).
        val t = track().toPlayableTrack(null)
        assertEquals("1997-11-17", t.showDate)
        assertEquals("McNichols Arena", t.venueName)
        assertEquals("https://phish.in/w.png", t.waveformUrl)
    }

    // ----------------------------------------------------------- recordings

    @Test
    fun `looksLikeMatrix flags a source whose lineage mentions matrix`() {
        val rec = RecordingRef(id = "1", label = "SBD/AUD Matrix", lineage = "SBD/AUD Matrix > CDR")
        assertTrue(rec.looksLikeMatrix)
    }

    @Test
    fun `looksLikeMatrix checks taper too, case-insensitively`() {
        val rec = RecordingRef(id = "1", label = "Unknown MATRIX mix", taper = "Unknown MATRIX mix")
        assertTrue(rec.looksLikeMatrix)
    }

    @Test
    fun `looksLikeMatrix is false with no matrix mention`() {
        val rec = RecordingRef(id = "1", label = "Jimmy Page", taper = "Jimmy Page", lineage = "DAT > CDR")
        assertFalse(rec.looksLikeMatrix)
    }

    @Test
    fun `looksLikeMatrix is false when taper and lineage are both null`() {
        assertFalse(RecordingRef(id = "1", label = "Soundboard").looksLikeMatrix)
    }

    // -------------------------------------------------------------- queue key

    @Test
    fun `a phish-in show detail keys itself exactly as before`() {
        // The existing key, unchanged — this is what makes the second backend free of a
        // migration. A row written before this change still matches.
        assertEquals("show:1997-11-17", show().toShowDetail().queueKey)
    }

    @Test
    fun `a relisten show detail keys itself by artist, date, and tape`() {
        val artist = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead")
        val detail = ShowDetail(
            summary = ShowSummary(artist = artist, date = "1977-05-08"),
            recording = RecordingRef(id = "src-uuid", label = "SBD"),
        )
        assertEquals("relisten:grateful-dead/1977-05-08/src-uuid", detail.queueKey)
    }

    @Test
    fun `a relisten show with no tape has no key rather than a broken one`() {
        // Better to record nothing than to write a key that parses back to nothing —
        // the same call shuffle makes (D42).
        val artist = ArtistRef(Backend.RELISTEN, "wsp", "Widespread Panic")
        val detail = ShowDetail(summary = ShowSummary(artist = artist, date = "2001-04-22"))
        assertNull(detail.queueKey)
    }

    // ------------------------------------------------------------ mergeArtists

    @Test
    fun `phish is pinned first regardless of show count`() {
        val dead = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead", showCount = 2189)
        val merged = mergeArtists(
            mapOf(Backend.PHISHIN to listOf(PHISH), Backend.RELISTEN to listOf(dead))
        )
        assertEquals(listOf(PHISH, dead), merged)
    }

    @Test
    fun `everything after phish sorts by show count descending`() {
        val small = ArtistRef(Backend.RELISTEN, "goose", "Goose", showCount = 412)
        val big = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead", showCount = 2189)
        val merged = mergeArtists(
            mapOf(Backend.PHISHIN to listOf(PHISH), Backend.RELISTEN to listOf(small, big))
        )
        assertEquals(listOf(PHISH, big, small), merged)
    }

    @Test
    fun `a relisten outage still leaves phish in the merged list`() {
        val merged = mergeArtists(
            mapOf(Backend.PHISHIN to listOf(PHISH), Backend.RELISTEN to emptyList())
        )
        assertEquals(listOf(PHISH), merged)
    }

    @Test
    fun `relisten's own separate phish archive is dropped, not shown twice`() {
        // Relisten has its own taper-community Phish collection (slug "phish", a different
        // show count than phish.in's) — without this filter, "Phish" would appear twice.
        val relistenPhish = ArtistRef(Backend.RELISTEN, "phish", "Phish", showCount = 1884)
        val dead = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead", showCount = 2189)
        val merged = mergeArtists(
            mapOf(Backend.PHISHIN to listOf(PHISH), Backend.RELISTEN to listOf(relistenPhish, dead))
        )
        assertEquals(listOf(PHISH, dead), merged)
    }

    @Test
    fun `a favorited artist is pinned right after phish, ahead of bigger unfavorited artists`() {
        val small = ArtistRef(Backend.RELISTEN, "goose", "Goose", showCount = 412)
        val big = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead", showCount = 2189)
        val merged = mergeArtists(
            mapOf(Backend.PHISHIN to listOf(PHISH), Backend.RELISTEN to listOf(small, big)),
            favorites = setOf(small.key),
        )
        assertEquals(listOf(PHISH, small, big), merged)
    }

    @Test
    fun `favoriting an artist never displaces phish from position 1`() {
        // Phish's pinned slot is earned by its account/likes/playlists features, not by
        // being liked — favoriting is orthogonal to that and never moves it.
        val dead = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead", showCount = 2189)
        val merged = mergeArtists(
            mapOf(Backend.PHISHIN to listOf(PHISH), Backend.RELISTEN to listOf(dead)),
            favorites = setOf(dead.key),
        )
        assertEquals(listOf(PHISH, dead), merged)
    }

    @Test
    fun `multiple favorited artists still sort by show count among themselves`() {
        val smallFav = ArtistRef(Backend.RELISTEN, "goose", "Goose", showCount = 412)
        val bigFav = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead", showCount = 2189)
        val unfavoritedBiggest = ArtistRef(Backend.RELISTEN, "wsp", "Widespread Panic", showCount = 3000)
        val merged = mergeArtists(
            mapOf(Backend.PHISHIN to listOf(PHISH), Backend.RELISTEN to listOf(smallFav, bigFav, unfavoritedBiggest)),
            favorites = setOf(smallFav.key, bigFav.key),
        )
        assertEquals(listOf(PHISH, bigFav, smallFav, unfavoritedBiggest), merged)
    }

    // ----------------------------------------------------------------- search

    @Test
    fun `phish-in search results carry their tracks and playlists straight through`() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("fixtures/search.json")!!
            .bufferedReader().readText()
        val results = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<SearchResults>(fixture)
        val hits = results.toSearchHits()
        assertEquals(results.shows.size, hits.shows.size)
        assertEquals(results.tracks, hits.tracks)
        assertEquals(results.playlists, hits.playlists)
    }

    private val dead = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead")
    private val wsp = ArtistRef(Backend.RELISTEN, "wsp", "Widespread Panic")

    @Test
    fun `plus merges every field and unions the failed set`() {
        val a = SearchHits(artists = listOf(dead), failed = setOf(Backend.RELISTEN))
        val b = SearchHits(artists = listOf(wsp))
        val merged = a + b
        assertEquals(listOf(dead, wsp), merged.artists)
        assertEquals(setOf(Backend.RELISTEN), merged.failed)
    }

    @Test
    fun `artistsPresent is deduped across every hit type`() {
        val hits = SearchHits(
            artists = listOf(dead),
            shows = listOf(ShowSummary(artist = dead, date = "1977-05-08")),
            slices = listOf(SliceHit(SliceKind.SONG, wsp, PeriodRef("song:x", "Junior"))),
            tracks = listOf(Track(id = 1, title = "Tweezer")),
        )
        assertEquals(listOf(dead, wsp, PHISH), hits.artistsPresent)
    }

    @Test
    fun `filteredTo narrows every field to one artist, dropping phish-only fields for others`() {
        val hits = SearchHits(
            artists = listOf(dead, wsp),
            shows = listOf(
                ShowSummary(artist = dead, date = "1977-05-08"),
                ShowSummary(artist = wsp, date = "2001-04-22"),
            ),
            tracks = listOf(Track(id = 1, title = "Tweezer")),
        )
        val filtered = hits.filteredTo(dead)
        assertEquals(listOf(dead), filtered.artists)
        assertEquals(listOf("1977-05-08"), filtered.shows.map { it.date })
        assertTrue(filtered.tracks.isEmpty())
    }

    @Test
    fun `filteredTo a null key returns everything unchanged`() {
        val hits = SearchHits(artists = listOf(dead, wsp))
        assertEquals(hits, hits.filteredTo(null))
    }

    // ------------------------------------------------------------ pickRandomShow

    /** Only [periods] and [shows] are exercised by [pickRandomShow]; the rest just satisfy
     *  the interface. Both fakes are keyed by artist id, so one instance covers a merged set
     *  of artists spanning both backends. */
    private class FakeSource(
        private val periodsByArtist: Map<String, List<PeriodRef>>,
        private val showsByPeriod: Map<String, List<ShowSummary>>,
    ) : MusicSource {
        override val backend = Backend.RELISTEN
        override suspend fun artists() = emptyList<ArtistRef>()
        override suspend fun periods(artist: ArtistRef) = periodsByArtist.getValue(artist.id)
        override suspend fun shows(artist: ArtistRef, period: PeriodRef) = showsByPeriod.getValue(period.id)
        override suspend fun show(artist: ArtistRef, date: String, recordingId: String?) =
            error("not used by pickRandomShow")
        override suspend fun search(term: String) = SearchHits()
    }

    @Test
    fun `pickRandomShow can land on every artist in the merged set`() = runBlocking {
        val a = ArtistRef(Backend.RELISTEN, "artist-a", "Artist A")
        val b = ArtistRef(Backend.RELISTEN, "artist-b", "Artist B")
        val periodA = PeriodRef("pa", "Period A")
        val periodB = PeriodRef("pb", "Period B")
        val showA = ShowSummary(artist = a, date = "2001-01-01")
        val showB = ShowSummary(artist = b, date = "2002-02-02")
        val source = FakeSource(
            periodsByArtist = mapOf("artist-a" to listOf(periodA), "artist-b" to listOf(periodB)),
            showsByPeriod = mapOf("pa" to listOf(showA), "pb" to listOf(showB)),
        )
        val picked = (0 until 50).map { seed ->
            pickRandomShow(listOf(a, b), random = Random(seed.toLong())) { source }.artist.id
        }.toSet()
        assertEquals(setOf("artist-a", "artist-b"), picked)
    }

    @Test
    fun `pickRandomShow skips a partial show when a complete one is available in the same period`() = runBlocking {
        val artist = ArtistRef(Backend.RELISTEN, "artist-a", "Artist A")
        val period = PeriodRef("p", "Period")
        val complete = ShowSummary(artist = artist, date = "2001-01-01", partial = false)
        val partial = ShowSummary(artist = artist, date = "2001-01-02", partial = true)
        val source = FakeSource(
            periodsByArtist = mapOf("artist-a" to listOf(period)),
            showsByPeriod = mapOf("p" to listOf(complete, partial)),
        )
        repeat(20) { seed ->
            val picked = pickRandomShow(listOf(artist), random = Random(seed.toLong())) { source }
            assertFalse(picked.partial)
        }
    }

    @Test
    fun `pickRandomShow falls back to a partial show when the period has nothing else`() = runBlocking {
        val artist = ArtistRef(Backend.RELISTEN, "artist-a", "Artist A")
        val period = PeriodRef("p", "Period")
        val onlyPartial = ShowSummary(artist = artist, date = "2001-01-02", partial = true)
        val source = FakeSource(
            periodsByArtist = mapOf("artist-a" to listOf(period)),
            showsByPeriod = mapOf("p" to listOf(onlyPartial)),
        )
        val picked = pickRandomShow(listOf(artist), random = Random(0)) { source }
        assertEquals(onlyPartial, picked)
    }
}
