package dev.mike.couchtour

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Search (uat-004, uat-011): the typed query fanning out to both backends through the real
 * [SearchScreen], then the result list's artist chips, tag filter and sort menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = UI_TEST_QUALIFIERS)
class SearchUiTest {

    private val ui = ComposeUiRule()

    @get:Rule
    val rules = ui.chain

    private val servers = CatalogServers(
        phishIn = mapOf(
            // Relevance order is the API's order; likes and dates are chosen so that every
            // sort produces a distinct order.
            "/search/tweezer" to """{
                "exact_show": null,
                "other_shows": [
                    {"date":"1997-11-22","venue_name":"Hampton Coliseum","likes_count":50,
                     "tags":[{"name":"Jamcharts","priority":5}]},
                    {"date":"1995-12-31","venue_name":"Madison Square Garden","likes_count":200},
                    {"date":"2003-02-28","venue_name":"Nassau Coliseum","likes_count":10,
                     "tags":[{"name":"Jamcharts","priority":5}]}
                ],
                "tracks": [
                    {"id":7,"title":"Tweezer","show_date":"1997-11-22","venue_name":"Hampton Coliseum",
                     "duration":1200000,"likes_count":30,"tags":[{"name":"Jamcharts","priority":5}]}
                ],
                "playlists": []
            }""",
        ),
        relisten = mapOf(
            // Relisten has no like counts: under "Most liked" this show has to settle last.
            "/v3/search" to """{
                "Artists": [],
                "Shows": [{"slim_artist":{"slug":"grateful-dead","name":"Grateful Dead"},
                           "display_date":"1972-08-27","source_count":4}],
                "Songs": [], "Venues": []
            }""",
        ),
    )

    private val dates = listOf("1997-11-22", "1995-12-31", "2003-02-28", "1972-08-27")

    private lateinit var vm: PlayerViewModel

    @Before
    fun setUp() {
        servers.start()
        vm = playerViewModelForTest()
    }

    @After
    fun tearDown() = servers.shutdown()

    /** Renders the real search screen and types [query] into it. */
    private fun search(query: String): () -> NavHostController {
        val nav = ui.setScreen { SearchScreen(vm, it) }
        ui.compose.onNode(hasSetTextAction()).performTextInput(query)
        return nav
    }

    private fun awaitResults() =
        ui.compose.waitUntil(5_000) { ui.compose.onNodeWithText("1972-08-27").exists() }

    @Test
    fun `short queries prompt for more instead of searching`() {
        search("tw")
        ui.compose.onNodeWithText(
            "Type 3 or more characters to search across Phish and Relisten artists, shows, and tracks."
        ).assertIsDisplayed()
    }

    @Test
    fun `a query shows hits from both backends in relevance order`() {
        search("tweezer")
        awaitResults()
        assertEquals(dates, ui.compose.onScreenOrder(dates))
        ui.compose.onNodeWithText("Tweezer").assertIsDisplayed()
        ui.compose.onNodeWithText("Sort: Relevance").assertIsDisplayed()
    }

    @Test
    fun `sort menu reorders shows, and Most liked puts Relisten hits last`() {
        search("tweezer")
        awaitResults()

        pickSort("Newest")
        assertEquals(listOf("2003-02-28", "1997-11-22", "1995-12-31", "1972-08-27"), ui.compose.onScreenOrder(dates))

        pickSort("Oldest")
        assertEquals(listOf("1972-08-27", "1995-12-31", "1997-11-22", "2003-02-28"), ui.compose.onScreenOrder(dates))

        pickSort("Most liked")
        assertEquals(listOf("1995-12-31", "1997-11-22", "2003-02-28", "1972-08-27"), ui.compose.onScreenOrder(dates))
    }

    private fun pickSort(label: String) {
        ui.compose.onNodeWithText("Sort: ", substring = true).performClick()
        ui.compose.onNodeWithText(label).performClick()
        ui.compose.onNodeWithText("Sort: $label").assertIsDisplayed()
    }

    @Test
    fun `artist chips narrow results to one artist`() {
        search("tweezer")
        awaitResults()

        ui.compose.onChip("Grateful Dead").performClick()
        assertEquals(listOf("1972-08-27"), ui.compose.onScreenOrder(dates))
        // phish.in's tracks belong to Phish alone, so they drop out too.
        assertFalse(ui.compose.onNodeWithText("Tweezer").exists())

        ui.compose.onChip("Phish").performClick()
        assertEquals(listOf("1997-11-22", "1995-12-31", "2003-02-28"), ui.compose.onScreenOrder(dates))
        ui.compose.onNodeWithText("Tweezer").assertIsDisplayed()

        // The artist row's "All", not the tag row's.
        ui.compose.onAllNodes(chip("All")).onFirst().performClick()
        assertEquals(dates, ui.compose.onScreenOrder(dates))
    }

    @Test
    fun `tag filter narrows shows and tracks`() {
        search("tweezer")
        awaitResults()

        ui.compose.onChip("Jamcharts").performClick()
        assertEquals(listOf("1997-11-22", "2003-02-28"), ui.compose.onScreenOrder(dates))
        ui.compose.onNodeWithText("Tweezer").assertIsDisplayed()
    }

    @Test
    fun `a selected tag missing from new results falls back to All`() {
        var hits by mutableStateOf(tweezerHits())
        ui.setScreen { SearchResultsList(hits, vm, it) }
        ui.compose.onChip("Jamcharts").performClick()
        assertEquals(listOf("1997-11-22"), ui.compose.onScreenOrder(dates))

        // A different query whose results carry no Jamcharts tag at all.
        hits = SearchHits(shows = listOf(ShowSummary(PHISH, "1995-12-31", venue = "Madison Square Garden")))
        ui.compose.waitForIdle()
        ui.compose.onNodeWithText("1995-12-31").assertIsDisplayed()
        assertFalse(ui.compose.onNodeWithText("Nothing matched.").exists())
    }

    @Test
    fun `nothing matched says so, and a failed backend is named`() {
        var hits by mutableStateOf(SearchHits())
        ui.setScreen { SearchResultsList(hits, vm, it) }
        ui.compose.onNodeWithText("Nothing matched.").assertIsDisplayed()

        hits = SearchHits(failed = setOf(Backend.RELISTEN))
        ui.compose.onNodeWithText("Couldn't search Relisten.").assertIsDisplayed()
    }

    @Test
    fun `tapping a Relisten show opens its recording`() {
        val nav = search("tweezer")
        awaitResults()
        ui.compose.onChip("Grateful Dead").assertExists()
        ui.compose.onNodeWithText("1972-08-27").performClick()
        ui.compose.waitForIdle()
        assertEquals("recording/{backend}/{artistId}/{date}?src={src}", nav().currentRoute)
        assertEquals("grateful-dead", nav().arg("artistId"))
    }

    private fun tweezerHits() = SearchHits(
        shows = listOf(
            ShowSummary(PHISH, "1997-11-22", venue = "Hampton Coliseum", tags = listOf(TagRef("Jamcharts"))),
            ShowSummary(PHISH, "1995-12-31", venue = "Madison Square Garden"),
        ),
    )
}
