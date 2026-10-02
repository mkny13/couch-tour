package dev.mike.couchtour

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Decodes the `contract_*.json` fixtures, which `scripts/contracts/record.sh` records from the
 * live phish.in and Relisten APIs (D-entry in DECISIONS.md). Unlike the hand-shaped fixtures
 * elsewhere, upstream drift shows up here when they're re-recorded. A decode failure on a
 * recorded file is a real DTO bug, not a test problem.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContractFixturesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    private var server: MockWebServer? = null

    @After
    fun tearDown() {
        server?.shutdown()
        PhishInApi.baseUrl = "https://phish.in/api/v2".toHttpUrl()
        RelistenApi.baseUrl = "https://api.relisten.net/api".toHttpUrl()
    }

    private fun serve(body: String): MockWebServer =
        MockWebServer().also {
            it.enqueue(MockResponse().setBody(body))
            it.start()
            server = it
        }

    // --------------------------------------------------------------- phish.in

    @Test
    fun `phishin years decode`() {
        val periods = json.decodeFromString<List<Period>>(fixture("contract_phishin_years.json"))
        assertTrue(periods.isNotEmpty())
        assertTrue(periods.all { it.period.isNotBlank() })
    }

    @Test
    fun `phishin shows for a year decode`() {
        val page = json.decodeFromString<ShowsPage>(fixture("contract_phishin_shows_year.json"))
        assertTrue(page.shows.isNotEmpty())
        assertTrue(page.shows.all { it.date.startsWith("1997") })
    }

    @Test
    fun `phishin show decodes with tracks`() {
        val show = json.decodeFromString<Show>(fixture("contract_phishin_show.json"))
        assertEquals("1997-11-22", show.date)
        assertTrue(show.tracks.isNotEmpty())
        assertTrue(show.tracks.any { it.playable })
    }

    @Test
    fun `phishin search decodes`() {
        val results = json.decodeFromString<SearchResults>(fixture("contract_phishin_search.json"))
        assertTrue(results.shows.isNotEmpty() || results.tracks.isNotEmpty())
    }

    @Test
    fun `phishin playlists decode`() {
        val page = json.decodeFromString<PlaylistsPage>(fixture("contract_phishin_playlists.json"))
        assertTrue(page.playlists.isNotEmpty())
    }

    @Test
    fun `phishin show decodes through the real client`() = runBlocking {
        PhishInApi.baseUrl = serve(fixture("contract_phishin_show.json")).url("/api/v2")

        val show = PhishInApi.show("1997-11-22")

        assertEquals("/api/v2/shows/1997-11-22", server!!.takeRequest().path)
        assertTrue(show.tracks.isNotEmpty())
    }

    // --------------------------------------------------------------- Relisten

    @Test
    fun `relisten artists decode`() {
        val artists = json.decodeFromString<List<RelistenArtist>>(fixture("contract_relisten_artists.json"))
        assertTrue(artists.isNotEmpty())
        assertTrue(artists.all { it.uuid.isNotBlank() && it.slug.isNotBlank() })
    }

    @Test
    fun `relisten years decode`() {
        val years = json.decodeFromString<List<RelistenYear>>(fixture("contract_relisten_years.json"))
        assertTrue(years.isNotEmpty())
    }

    @Test
    fun `relisten year decodes with shows`() {
        val year = json.decodeFromString<RelistenYearWithShows>(fixture("contract_relisten_year.json"))
        assertEquals("1997", year.year)
        assertTrue(year.shows.isNotEmpty())
    }

    @Test
    fun `relisten show decodes with sources and tracks`() {
        val show = json.decodeFromString<RelistenShowWithSources>(fixture("contract_relisten_show.json"))
        assertEquals("1997-11-22", show.displayDate)
        assertTrue(show.sources.isNotEmpty())
    }

    @Test
    fun `relisten on-date decodes`() {
        val shows = json.decodeFromString<List<RelistenShowSummary>>(fixture("contract_relisten_on_date.json"))
        assertTrue(shows.isNotEmpty())
    }

    @Test
    fun `relisten search decodes`() {
        val results = json.decodeFromString<RelistenSearchResults>(fixture("contract_relisten_search.json"))
        assertTrue(results.shows.isNotEmpty() || results.songs.isNotEmpty())
    }

    @Test
    fun `relisten show decodes through the real client`() = runBlocking {
        RelistenApi.baseUrl = serve(fixture("contract_relisten_show.json")).url("/api")

        val show = RelistenApi.show("phish", "1997-11-22")

        assertEquals("/api/v2/artists/phish/shows/1997-11-22", server!!.takeRequest().path)
        assertTrue(show.sources.isNotEmpty())
    }

    // ------------------------------------------------------- bundled assets

    @Test
    fun `curated releases asset loads with entries`() {
        CuratedMatches.init(ApplicationProvider.getApplicationContext())
        assertNotNull(CuratedMatches.match(Backend.PHISHIN, "phish", "1995-11-14"))
    }

    @Test
    fun `heuristic matches asset loads with entries`() {
        HeuristicMatches.init(ApplicationProvider.getApplicationContext())
        assertNotNull(HeuristicMatches.match(Backend.PHISHIN, "phish", "1994-06-22"))
    }
}
