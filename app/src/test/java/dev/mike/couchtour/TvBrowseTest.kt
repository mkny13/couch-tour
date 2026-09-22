package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure mapping behind the TV browse levels (#225, #226): section grouping, year/show
 * sorting, subtitle text, and track-to-set grouping. The composables themselves need a TV
 * device or emulator to check (UAT); these tests pin the model so the D-pad UI is at least
 * fed the right rows in the right order.
 */
class TvBrowseTest {

    private fun artist(
        backend: Backend = Backend.RELISTEN,
        id: String = "artist",
        name: String = "Artist",
        showCount: Int = 10,
    ) = ArtistRef(backend, id, name, showCount)

    // ------------------------------------------------------ tvArtistSections

    @Test
    fun `sections order phish, favorites, then everyone else`() {
        val phish = PHISH.copy(showCount = 2000)
        val groups = ArtistGroups(
            phish = phish,
            favorited = listOf(artist(id = "fav", name = "Favored", showCount = 40)),
            others = listOf(artist(id = "o", name = "Other", showCount = 5)),
        )
        val sections = tvArtistSections(groups)
        assertEquals(
            listOf(TvArtistSection.PHISH, TvArtistSection.FAVORITES, TvArtistSection.ALL),
            sections.map { it.section },
        )
        assertEquals(listOf(phish), sections[0].artists)
    }

    @Test
    fun `empty sections are dropped, so no heading has a dead focus lane under it`() {
        val groups = ArtistGroups(
            phish = PHISH,
            favorited = emptyList(),
            others = emptyList(),
        )
        val sections = tvArtistSections(groups)
        assertEquals(listOf(TvArtistSection.PHISH), sections.map { it.section })
    }

    @Test
    fun `favorites and others each sort by show count descending within their section`() {
        val groups = ArtistGroups(
            phish = null,
            favorited = listOf(
                artist(id = "f1", name = "Small", showCount = 3),
                artist(id = "f2", name = "Big", showCount = 99),
            ),
            others = listOf(
                artist(id = "o1", name = "Mid", showCount = 10),
                artist(id = "o2", name = "Bigger", showCount = 50),
                artist(id = "o3", name = "Small", showCount = 1),
            ),
        )
        val sections = tvArtistSections(groups)
        assertEquals(listOf("f2", "f1"), sections[0].artists.map { it.id })
        assertEquals(listOf("o2", "o1", "o3"), sections[1].artists.map { it.id })
    }

    // ------------------------------------------------------ tvYearItems

    @Test
    fun `years sort newest-first by label, matching Auto's artistPeriodsChildren`() {
        val periods = listOf(
            PeriodRef(id = "1983-1987", label = "1983-1987", showCount = 2),
            PeriodRef(id = "1999", label = "1999", showCount = 70),
            PeriodRef(id = "2017", label = "2017", showCount = 15),
            PeriodRef(id = "popular", label = "Popular", showCount = 0),
        )
        val items = tvYearItems(periods)
        // 'P' > '9' in string order, so Popular rides ahead of every numeric year with no
        // special pinning — the same accident-of-encoding Auto's tree relies on.
        assertEquals(listOf("Popular", "2017", "1999", "1983-1987"), items.map { it.period.label })
    }

    @Test
    fun `popular period's subtitle reads like a descriptor, not a zero show count`() {
        val items = tvYearItems(listOf(PeriodRef(id = POPULAR_PERIOD_ID, label = POPULAR_PERIOD_LABEL)))
        assertEquals(POPULAR_PERIOD_SUBTITLE, items.single().subtitle)
    }

    @Test
    fun `year subtitles pluralize and singularize the show count`() {
        val items = tvYearItems(
            listOf(
                PeriodRef(id = "1997", label = "1997", showCount = 1),
                PeriodRef(id = "1998", label = "1998", showCount = 42),
            ),
        )
        // Newest-first sort puts 1998 ahead of 1997.
        assertEquals("42 shows", items[0].subtitle)
        assertEquals("1 show", items[1].subtitle)
    }

    @Test
    fun `year items keep their period ids verbatim, so grid keys stay stable`() {
        val periods = listOf(
            PeriodRef(id = "1983-1987", label = "1983-1987", showCount = 2),
            PeriodRef(id = "2017", label = "2017", showCount = 15),
        )
        val items = tvYearItems(periods)
        assertEquals(listOf("2017", "1983-1987"), items.map { it.period.id })
    }

    // ------------------------------------------------------ tvShowItems

    private fun show(
        date: String,
        venue: String? = "Venue",
        location: String? = "City, ST",
    ) = ShowSummary(artist = artist(), date = date, venue = venue, location = location)

    @Test
    fun `show items sort newest-first, matching the phone's default show sort`() {
        val shows = listOf(show("1997-11-17"), show("1999-07-23"), show("1994-06-18"))
        val items = tvShowItems(shows)
        assertEquals(listOf("1999-07-23", "1997-11-17", "1994-06-18"), items.map { it.show.date })
    }

    @Test
    fun `show item subtitle is venue and location, matching ShowSummary#where`() {
        val items = tvShowItems(listOf(show("1997-11-17", venue = "Hampton Coliseum", location = "Hampton, VA")))
        assertEquals("Hampton Coliseum · Hampton, VA", items.single().subtitle)
    }

    @Test
    fun `show item subtitle is blank when venue and location are both missing`() {
        val items = tvShowItems(listOf(show("1997-11-17", venue = null, location = null)))
        assertEquals("", items.single().subtitle)
    }

    // ------------------------------------------------------ tvTrackSections

    private fun track(id: String, setName: String, title: String = "Track $id") =
        PlayableTrack(id = id, title = title, setName = setName, url = "https://example.com/$id.mp3")

    @Test
    fun `tracks group into contiguous set sections, in order`() {
        val tracks = listOf(
            track("1", "Set 1"),
            track("2", "Set 1"),
            track("3", "Set 2"),
            track("4", "Encore"),
        )
        val sections = tvTrackSections(tracks)
        assertEquals(listOf("Set 1", "Set 2", "Encore"), sections.map { it.setName })
        assertEquals(listOf("1", "2"), sections[0].rows.map { it.track.id })
        assertEquals(listOf("3"), sections[1].rows.map { it.track.id })
        assertEquals(listOf("4"), sections[2].rows.map { it.track.id })
    }

    @Test
    fun `tracks with no set name collapse into one unnamed section`() {
        val tracks = listOf(track("1", ""), track("2", ""), track("3", ""))
        val sections = tvTrackSections(tracks)
        assertEquals(1, sections.size)
        assertEquals("", sections.single().setName)
        assertEquals(listOf("1", "2", "3"), sections.single().rows.map { it.track.id })
    }

    @Test
    fun `track rows carry a 1-based position, so the UI never needs to recompute it`() {
        val tracks = listOf(track("1", "Set 1"), track("2", "Set 1"), track("3", "Set 2"))
        val sections = tvTrackSections(tracks)
        assertEquals(listOf(1, 2), sections[0].rows.map { it.position })
        assertEquals(listOf(3), sections[1].rows.map { it.position })
    }

    @Test
    fun `a repeated set name after a different set starts a new section, not a merge`() {
        val tracks = listOf(track("1", "Set 1"), track("2", "Set 2"), track("3", "Set 1"))
        val sections = tvTrackSections(tracks)
        assertEquals(listOf("Set 1", "Set 2", "Set 1"), sections.map { it.setName })
        assertTrue(sections.all { it.rows.size == 1 })
    }
}
