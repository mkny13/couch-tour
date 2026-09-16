package dev.mike.couchtour

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * A period's show list — the sort chips (uat-001/uat-052) and the tag filter (uat-003, which
 * UAT marked "needs work" once) — driven through the real [ArtistShowsScreen].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = UI_TEST_QUALIFIERS)
class ShowListUiTest {

    private val ui = ComposeUiRule()

    @get:Rule
    val rules = ui.chain

    private val servers = CatalogServers(
        relisten = mapOf(
            "/v3/artists" to """[{"uuid":"gd","slug":"grateful-dead","name":"Grateful Dead","show_count":3}]""",
            "/v3/artists/grateful-dead/years" to """[
                {"uuid":"y1977","year":"1977","show_count":3},
                {"uuid":"y1978","year":"1978","show_count":2}
            ]""",
            // Three shows whose date, rating and 48h-trending orders all differ, so each sort
            // produces an order no other sort does.
            "/v3/artists/grateful-dead/years/y1977" to """{"year":"1977","shows":[
                {"display_date":"1977-05-07","avg_rating":8.0,"has_streamable_flac_source":true,
                 "popularity":{"windows":{"48h":{"hot_score":5.0}}}},
                {"display_date":"1977-05-08","avg_rating":9.5,"has_soundboard_source":true,
                 "popularity":{"windows":{"48h":{"hot_score":1.0}}}},
                {"display_date":"1977-05-09","avg_rating":7.0,"has_soundboard_source":true,
                 "popularity":{"windows":{"48h":{"hot_score":3.0}}}}
            ]}""",
            // No tags on any show: the tag row must not appear at all.
            "/v3/artists/grateful-dead/years/y1978" to """{"year":"1978","shows":[
                {"display_date":"1978-01-01","avg_rating":6.0},
                {"display_date":"1978-01-02","avg_rating":6.5}
            ]}""",
        ),
    )

    private val dates1977 = listOf("1977-05-07", "1977-05-08", "1977-05-09")

    @Before
    fun setUp() = servers.start()

    @After
    fun tearDown() = servers.shutdown()

    private fun render(periodId: String = "y1977", firstDate: String = "1977-05-08"): () -> NavHostController {
        val nav = ui.setScreen { ArtistShowsScreen("relisten", "grateful-dead", periodId, nav = it) }
        ui.compose.waitUntil(5_000) { ui.compose.onNodeWithText(firstDate).exists() }
        return nav
    }

    @Test
    fun `each sort chip reorders the list`() {
        render()
        assertEquals(listOf("1977-05-09", "1977-05-08", "1977-05-07"), ui.compose.onScreenOrder(dates1977))

        ui.compose.onChip("Top rated").performClick()
        ui.compose.onChip("Top rated").assertIsSelected()
        ui.compose.onChip("Date").assertIsNotSelected()
        assertEquals(listOf("1977-05-08", "1977-05-07", "1977-05-09"), ui.compose.onScreenOrder(dates1977))

        ui.compose.onChip("Trending 48h").performClick()
        assertEquals(listOf("1977-05-07", "1977-05-09", "1977-05-08"), ui.compose.onScreenOrder(dates1977))
        // The trailing badge follows the sort: the trending score, not the star rating.
        ui.compose.onNodeWithText("🔥 5.0").assertIsDisplayed()

        ui.compose.onChip("Date").performClick()
        assertEquals(listOf("1977-05-09", "1977-05-08", "1977-05-07"), ui.compose.onScreenOrder(dates1977))
    }

    @Test
    fun `picking a tag narrows the list and All restores it`() {
        render()
        ui.compose.onChip("All").assertIsSelected()

        ui.compose.onChip("SBD").performClick()
        ui.compose.onChip("SBD").assertIsSelected()
        ui.compose.onChip("All").assertIsNotSelected()
        assertEquals(listOf("1977-05-09", "1977-05-08"), ui.compose.onScreenOrder(dates1977))

        ui.compose.onChip("FLAC").performClick()
        assertEquals(listOf("1977-05-07"), ui.compose.onScreenOrder(dates1977))

        ui.compose.onChip("All").performClick()
        ui.compose.onChip("All").assertIsSelected()
        assertEquals(dates1977.reversed(), ui.compose.onScreenOrder(dates1977))
    }

    @Test
    fun `tag filter and sort compose`() {
        render()
        ui.compose.onChip("SBD").performClick()
        ui.compose.onChip("Top rated").performClick()
        assertEquals(listOf("1977-05-08", "1977-05-09"), ui.compose.onScreenOrder(dates1977))
    }

    @Test
    fun `tapping a tag badge on a row applies that filter`() {
        render()
        // The badge is the clickable "FLAC" that isn't the chip.
        ui.compose.onAllNodesWithText("FLAC")
            .filter(hasClickAction() and !chip("FLAC"))
            .onFirst()
            .performClick()
        ui.compose.onChip("FLAC").assertIsSelected()
        assertEquals(listOf("1977-05-07"), ui.compose.onScreenOrder(dates1977))
    }

    @Test
    fun `no tag row when no show carries a tag`() {
        render(periodId = "y1978", firstDate = "1978-01-01")
        ui.compose.onChip("Date").assertIsDisplayed()
        assertFalse(ui.compose.onNode(chip("All")).exists())
    }

    @Test
    fun `tapping a show opens its recording`() {
        val nav = render()
        ui.compose.onNode(hasText("1977-05-08") and hasClickAction(), useUnmergedTree = false).performClick()
        ui.compose.waitForIdle()
        assertEquals("recording/{backend}/{artistId}/{date}?src={src}", nav().currentRoute)
        assertEquals("1977-05-08", nav().arg("date"))
    }
}
