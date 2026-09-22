package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure mapping behind the TV browse levels (#225): section grouping, year sorting, and
 * subtitle text. The composables themselves need a TV device or emulator to check (UAT);
 * these tests pin the model so the D-pad UI is at least fed the right rows in the right
 * order.
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
}
