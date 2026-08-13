package dev.mike.couchtour

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes real Relisten responses, trimmed, plus the pure mapping into the backend-neutral
 * model. See MULTI-ARTIST-PLAN.md "Verified against the live API" for where the facts pinned
 * here came from — several of them contradicted what seemed obvious before checking.
 */
class RelistenParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().readText()

    private val deadArtist = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead", hasSets = false, hasMultipleSources = true)

    // ---------------------------------------------------------------- artists

    @Test
    fun `parses artists and their feature flags`() {
        val artists = json.decodeFromString<List<RelistenArtist>>(fixture("relisten_artists.json"))
        assertEquals(listOf("phish", "grateful-dead", "wsp"), artists.map { it.slug })
    }

    @Test
    fun `phish has sets but no tape to choose, opposite of the Dead`() {
        val artists = json.decodeFromString<List<RelistenArtist>>(fixture("relisten_artists.json"))
        val phish = artists.first { it.slug == "phish" }.toArtistRef()
        val dead = artists.first { it.slug == "grateful-dead" }.toArtistRef()

        assertTrue(phish.hasSets)
        assertFalse(phish.hasMultipleSources)
        assertFalse(dead.hasSets)
        assertTrue(dead.hasMultipleSources)
    }

    // ------------------------------------------------------------------ years

    @Test
    fun `parses years into period refs keyed by uuid`() {
        val years = json.decodeFromString<List<RelistenYear>>(fixture("relisten_years.json"))
        val first = years.first().toPeriodRef()
        assertEquals("1965", first.label)
        assertEquals(1, first.showCount)
        // The uuid, not the year string, is what a Relisten period is fetched by.
        assertEquals(years.first().uuid, first.id)
    }

    @Test
    fun `parses a year's shows including venue and tour`() {
        val detail = json.decodeFromString<RelistenYearWithShows>(fixture("relisten_year.json"))
        assertEquals("1977", detail.year)

        val cornell = detail.shows.first { it.displayDate == "1977-05-08" }.toShowSummary(deadArtist)
        assertEquals(deadArtist, cornell.artist)
        assertEquals("Barton Hall, Cornell University", cornell.venue)
        assertEquals("Ithaca, NY, USA", cornell.location)
        assertEquals("Spring 1977", cornell.tourName)
        assertEquals(10, cornell.recordingCount)
    }

    // ------------------------------------------------------------- show + tapes

    @Test
    fun `defaults to the first source, because Relisten already sorts by rating`() {
        // Do NOT tie-break on is_soundboard: the soundboard here ranks 4th (8.212 against
        // 8.260), so picking it would override Relisten's own ranking with a worse tape.
        val show = json.decodeFromString<RelistenShowWithSources>(fixture("relisten_show.json"))
        val detail = show.toShowDetail(deadArtist)

        assertEquals("848d7cec-2b6d-faee-7661-ce4abd18cb01", detail.recording?.id)
        assertFalse(detail.recording!!.isSoundboard)
        assertEquals(3, detail.alternates.size)
    }

    @Test
    fun `an explicit recording id is honoured over the default`() {
        val show = json.decodeFromString<RelistenShowWithSources>(fixture("relisten_show.json"))
        val soundboardId = "0a1e8672-06ef-5fe6-5717-02061dcaf53e"

        val detail = show.toShowDetail(deadArtist, recordingId = soundboardId)

        assertEquals(soundboardId, detail.recording?.id)
        assertTrue(detail.recording!!.isSoundboard)
    }

    @Test
    fun `an unknown recording id falls back to the default rather than an empty show`() {
        val show = json.decodeFromString<RelistenShowWithSources>(fixture("relisten_show.json"))
        val detail = show.toShowDetail(deadArtist, recordingId = "not-a-real-uuid")
        assertEquals("848d7cec-2b6d-faee-7661-ce4abd18cb01", detail.recording?.id)
    }

    @Test
    fun `converts track duration from seconds to milliseconds`() {
        // 325 seconds = 5:25. Everything else in the app is milliseconds; a track this far
        // off is the kind of bug that looks fine until someone opens the scrubber.
        val show = json.decodeFromString<RelistenShowWithSources>(fixture("relisten_show.json"))
        val detail = show.toShowDetail(deadArtist)
        assertEquals("Minglewood Blues", detail.tracks.first().title)
        assertEquals(325_000L, detail.tracks.first().durationMs)
    }

    @Test
    fun `keeps every track of the chosen source, not the default's neighbours`() {
        // The empirical case for keying progress on the source: Cornell's tapes really do
        // carry different track counts (20 on the default tape, 25 on the soundboard).
        val show = json.decodeFromString<RelistenShowWithSources>(fixture("relisten_show.json"))
        assertEquals(20, show.toShowDetail(deadArtist).tracks.size)
        assertEquals(25, show.toShowDetail(deadArtist, "0a1e8672-06ef-5fe6-5717-02061dcaf53e").tracks.size)
    }

    @Test
    fun `a show with no sources has no recording and no tracks`() {
        val empty = RelistenShowWithSources(displayDate = "2001-04-22")
        val detail = empty.toShowDetail(ArtistRef(Backend.RELISTEN, "wsp", "Widespread Panic"))
        assertNull(detail.recording)
        assertTrue(detail.tracks.isEmpty())
        assertTrue(detail.alternates.isEmpty())
    }

    // --------------------------------------------------------- set flattening

    @Test
    fun `flattens sets in index order and drops tracks with no mp3 url`() {
        val raw = """
            {"display_date":"2001-04-22","sources":[{"uuid":"src-1","sets":[
                {"index":1,"name":"Set 2","tracks":[
                    {"uuid":"t3","title":"Third","duration":10,"mp3_url":"https://a/3.mp3"}
                ]},
                {"index":0,"name":"Set 1","tracks":[
                    {"uuid":"t1","title":"First","duration":10,"mp3_url":"https://a/1.mp3"},
                    {"uuid":"t2","title":"Missing","duration":10,"mp3_url":null}
                ]}
            ]}]}
        """.trimIndent()
        val artist = ArtistRef(Backend.RELISTEN, "wsp", "Widespread Panic", hasSets = true)
        val tracks = json.decodeFromString<RelistenShowWithSources>(raw).toShowDetail(artist).tracks

        assertEquals(listOf("First", "Third"), tracks.map { it.title })
        assertEquals(listOf("Set 1", "Set 2"), tracks.map { it.setName })
    }

    @Test
    fun `set names are suppressed for an artist without real sets`() {
        // Dead sources carry a single wrapper set literally named "Set" — showing it would
        // render one meaningless divider on every show.
        val raw = """
            {"display_date":"1977-05-08","sources":[{"uuid":"src-1","sets":[
                {"index":0,"name":"Set","tracks":[
                    {"uuid":"t1","title":"Minglewood Blues","duration":10,"mp3_url":"https://a/1.mp3"}
                ]}
            ]}]}
        """.trimIndent()
        val tracks = json.decodeFromString<RelistenShowWithSources>(raw).toShowDetail(deadArtist).tracks
        assertEquals("", tracks.first().setName)
    }

    @Test
    fun `a blank mp3 url is dropped the same as a null one`() {
        val raw = """
            {"display_date":"2001-04-22","sources":[{"uuid":"src-1","sets":[
                {"index":0,"name":"Set 1","tracks":[
                    {"uuid":"t1","title":"Kept","duration":1,"mp3_url":"https://a/1.mp3"},
                    {"uuid":"t2","title":"Blank","duration":1,"mp3_url":""}
                ]}
            ]}]}
        """.trimIndent()
        val artist = ArtistRef(Backend.RELISTEN, "wsp", "Widespread Panic", hasSets = true)
        val tracks = json.decodeFromString<RelistenShowWithSources>(raw).toShowDetail(artist).tracks
        assertEquals(listOf("Kept"), tracks.map { it.title })
    }
}
