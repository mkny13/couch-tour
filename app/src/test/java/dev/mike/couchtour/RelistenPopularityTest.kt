package dev.mike.couchtour

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelistenPopularityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().readText()

    private val deadArtist = ArtistRef(
        backend = Backend.RELISTEN,
        id = "grateful-dead",
        name = "Grateful Dead",
        hasSets = false,
        hasMultipleSources = true,
    )

    // ----------------------------------------------------------- JSON parsing

    @Test
    fun `parses popularity momentum_score and trend_ratio from year endpoint`() {
        val detail = json.decodeFromString<RelistenYearWithShows>(fixture("relisten_year.json"))
        val cornell = detail.shows.first { it.displayDate == "1977-05-08" }

        assertNotNull(cornell.popularity)
        assertEquals(0.7806, cornell.popularity!!.momentumScore, 0.0001)
        assertEquals(0.8673, cornell.popularity!!.trendRatio, 0.0001)
    }

    @Test
    fun `parses popularity 48h 7d 30d windows with plays hours hot_score`() {
        val detail = json.decodeFromString<RelistenYearWithShows>(fixture("relisten_year.json"))
        val cornell = detail.shows.first { it.displayDate == "1977-05-08" }
        val windows = cornell.popularity!!.windows
        assertNotNull(windows)

        val w48 = windows!!.window48h
        assertNotNull(w48)
        assertEquals(612, w48!!.plays)
        assertEquals(95.3917, w48.hours, 0.0001)
        assertEquals(24.7386, w48.hotScore, 0.0001)
        assertEquals(w48, windows.w48h)

        val w7d = windows.window7d
        assertNotNull(w7d)
        assertEquals(1640, w7d!!.plays)
        assertEquals(251.5275, w7d.hours, 0.0001)
        assertEquals(40.4969, w7d.hotScore, 0.0001)
        assertEquals(w7d, windows.w7d)

        val w30d = windows.window30d
        assertNotNull(w30d)
        assertEquals(8457, w30d!!.plays)
        assertEquals(1311.8942, w30d.hours, 0.0001)
        assertEquals(91.9619, w30d.hotScore, 0.0001)
        assertEquals(w30d, windows.w30d)
    }

    @Test
    fun `parses popularity from show detail endpoint with full sources`() {
        val show = json.decodeFromString<RelistenShowWithSources>(fixture("relisten_show.json"))
        assertNotNull(show.popularity)
        assertEquals(0.7806, show.popularity!!.momentumScore, 0.0001)
        assertEquals(0.8673, show.popularity!!.trendRatio, 0.0001)

        val detail = show.toShowDetail(deadArtist)
        assertNotNull(detail.summary.popularity)
        assertEquals(0.7806, detail.summary.momentumScore, 0.0001)
        assertEquals(24.7386, detail.summary.hotScore48h, 0.0001)
        assertEquals(40.4969, detail.summary.hotScore7d, 0.0001)
        assertEquals(91.9619, detail.summary.hotScore30d, 0.0001)
    }

    @Test
    fun `handles null popularity gracefully and defaults convenience getters to zero`() {
        val bareSummary = RelistenShowSummary(displayDate = "1977-01-01", popularity = null)
        val domain = bareSummary.toShowSummary(deadArtist)
        assertNull(domain.popularity)
        assertEquals(0.0, domain.momentumScore, 0.0)
        assertEquals(0.0, domain.hotScore48h, 0.0)
        assertEquals(0.0, domain.hotScore7d, 0.0)
        assertEquals(0.0, domain.hotScore30d, 0.0)
    }

    @Test
    fun `maps RelistenPopularity to domain Popularity model`() {
        val dto = RelistenPopularity(
            momentumScore = 0.65,
            trendRatio = 1.25,
            windows = RelistenPopularityWindows(
                window48h = RelistenPopularityWindow(plays = 100, hours = 10.0, hotScore = 15.5),
                window7d = RelistenPopularityWindow(plays = 500, hours = 50.0, hotScore = 35.0),
                window30d = RelistenPopularityWindow(plays = 2000, hours = 200.0, hotScore = 75.0),
            )
        )
        val domain = dto.toPopularity()
        assertEquals(0.65, domain.momentumScore, 0.0001)
        assertEquals(1.25, domain.trendRatio, 0.0001)
        assertEquals(15.5, domain.hotScore48h, 0.0001)
        assertEquals(35.0, domain.hotScore7d, 0.0001)
        assertEquals(75.0, domain.hotScore30d, 0.0001)
    }

    // ------------------------------------------------------------- ShowSortMode

    private fun testShow(
        date: String,
        rating: Double = 0.0,
        momentumScore: Double = 0.0,
        hot48h: Double = 0.0,
        hot7d: Double = 0.0,
        hot30d: Double = 0.0,
    ) = ShowSummary(
        artist = deadArtist,
        date = date,
        rating = rating,
        popularity = Popularity(
            momentumScore = momentumScore,
            hotScore48h = hot48h,
            hotScore7d = hot7d,
            hotScore30d = hot30d,
        ),
    )

    @Test
    fun `sorts shows chronologically ascending and descending`() {
        val s1 = testShow("1977-05-07")
        val s2 = testShow("1977-05-08")
        val s3 = testShow("1977-05-09")
        val list = listOf(s2, s3, s1)

        val asc = list.sortedByMode(ShowSortMode.DATE_ASC)
        assertEquals(listOf("1977-05-07", "1977-05-08", "1977-05-09"), asc.map { it.date })

        val desc = list.sortedByMode(ShowSortMode.DATE_DESC)
        assertEquals(listOf("1977-05-09", "1977-05-08", "1977-05-07"), desc.map { it.date })
    }

    @Test
    fun `sorts shows by top rated descending with date tie breaker`() {
        val low = testShow("1977-05-07", rating = 7.5)
        val highOld = testShow("1977-05-08", rating = 9.5)
        val highNew = testShow("1977-05-09", rating = 9.5)
        val list = listOf(low, highOld, highNew)

        val sorted = list.sortedByMode(ShowSortMode.TOP_RATED)
        assertEquals(listOf("1977-05-09", "1977-05-08", "1977-05-07"), sorted.map { it.date })
    }

    @Test
    fun `sorts shows by trending 48h with momentum score tie break`() {
        val s1 = testShow("1977-05-07", hot48h = 10.0, momentumScore = 0.9)
        val s2 = testShow("1977-05-08", hot48h = 25.0, momentumScore = 0.5)
        val s3 = testShow("1977-05-09", hot48h = 25.0, momentumScore = 0.8)
        val list = listOf(s1, s2, s3)

        val sorted = list.sortedByMode(ShowSortMode.TRENDING_48H)
        assertEquals(listOf("1977-05-09", "1977-05-08", "1977-05-07"), sorted.map { it.date })
    }

    @Test
    fun `sorts shows by hot 7d descending`() {
        val s1 = testShow("1977-05-07", hot7d = 50.0)
        val s2 = testShow("1977-05-08", hot7d = 20.0)
        val s3 = testShow("1977-05-09", hot7d = 80.0)
        val list = listOf(s1, s2, s3)

        val sorted = list.sortedByMode(ShowSortMode.HOT_7D)
        assertEquals(listOf("1977-05-09", "1977-05-07", "1977-05-08"), sorted.map { it.date })
    }

    @Test
    fun `sorts shows by popular 30d descending`() {
        val s1 = testShow("1977-05-07", hot30d = 100.0)
        val s2 = testShow("1977-05-08", hot30d = 300.0)
        val s3 = testShow("1977-05-09", hot30d = 200.0)
        val list = listOf(s1, s2, s3)

        val sorted = list.sortedByMode(ShowSortMode.POPULAR_30D)
        assertEquals(listOf("1977-05-08", "1977-05-09", "1977-05-07"), sorted.map { it.date })
    }

    @Test
    fun `sorts shows by momentum score descending`() {
        val s1 = testShow("1977-05-07", momentumScore = 0.2)
        val s2 = testShow("1977-05-08", momentumScore = 0.95)
        val s3 = testShow("1977-05-09", momentumScore = 0.75)
        val list = listOf(s1, s2, s3)

        val sorted = list.sortedByMode(ShowSortMode.MOMENTUM)
        assertEquals(listOf("1977-05-08", "1977-05-09", "1977-05-07"), sorted.map { it.date })
    }

    @Test
    fun `sorts real fixture year shows by trending 48h`() {
        val detail = json.decodeFromString<RelistenYearWithShows>(fixture("relisten_year.json"))
        val summaries = detail.shows.map { it.toShowSummary(deadArtist) }

        val trending = summaries.sortedBy(ShowSortMode.TRENDING_48H)
        assertEquals("1977-05-08", trending.first().date)
        assertEquals(24.7386, trending.first().hotScore48h, 0.0001)
    }
}
