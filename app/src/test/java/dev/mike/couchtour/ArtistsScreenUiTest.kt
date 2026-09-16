package dev.mike.couchtour

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * The Artists screen's sort toggle and filter field (UAT uat-009, uat-010), driven through the
 * real [ArtistsScreen] against mocked phish.in and Relisten catalogs.
 */
@RunWith(RobolectricTestRunner::class)
// A tall screen, so every row of the lazy list is composed and its order can be read back.
@Config(sdk = [34], qualifiers = UI_TEST_QUALIFIERS)
class ArtistsScreenUiTest {

    private val ui = ComposeUiRule()

    @get:Rule
    val rules = ui.chain

    private val servers = CatalogServers(
        phishIn = mapOf(
            "/years" to """[{"period":"1997","shows_count":40,"shows_with_audio_count":40}]""",
        ),
        relisten = mapOf(
            // Relisten's own Phish copy is in here on purpose: the screen must drop it rather
            // than list a second "Phish" under the pinned phish.in one.
            "/v3/artists" to """[
                {"uuid":"a1","slug":"aqueous","name":"Aqueous","show_count":50},
                {"uuid":"a2","slug":"grateful-dead","name":"Grateful Dead","show_count":2000},
                {"uuid":"a3","slug":"billy-strings","name":"Billy Strings","show_count":300},
                {"uuid":"a4","slug":"phish","name":"Phish","show_count":1700}
            ]""",
        ),
    )

    private val artists = listOf("Phish", "Aqueous", "Billy Strings", "Grateful Dead")

    @Before
    fun setUp() = servers.start()

    @After
    fun tearDown() {
        servers.shutdown()
        // Favorites is process-wide state; leave it as the next test expects to find it.
        Favorites.keys.value.forEach { Favorites.toggle(it) }
    }

    private fun render(): () -> NavHostController {
        val nav = ui.setScreen { ArtistsScreen(it) }
        ui.compose.waitUntil(5_000) { ui.compose.onNodeWithText("Grateful Dead").exists() }
        return nav
    }

    @Test
    fun `sort toggle reorders the list and keeps Phish pinned first`() {
        render()
        assertEquals(
            listOf("Phish", "Grateful Dead", "Billy Strings", "Aqueous"),
            ui.compose.onScreenOrder(artists),
        )

        ui.compose.onNodeWithText("A–Z").performClick()
        assertEquals(
            listOf("Phish", "Aqueous", "Billy Strings", "Grateful Dead"),
            ui.compose.onScreenOrder(artists),
        )

        ui.compose.onNodeWithText("Most shows").performClick()
        assertEquals(
            listOf("Phish", "Grateful Dead", "Billy Strings", "Aqueous"),
            ui.compose.onScreenOrder(artists),
        )
    }

    @Test
    fun `a favorited artist gets its own section under both sorts`() {
        render()
        ui.compose.onNodeWithContentDescription("Favorite Aqueous").performClick()
        ui.compose.onNodeWithText("FAVORITES").assertIsDisplayed()

        // Aqueous has the fewest shows, yet sits in the pinned Favorites section right under
        // Phish rather than at the bottom where "Most shows" would otherwise put it.
        val sections = listOf("FAVORITES", "ARTISTS")
        assertEquals(
            listOf("Phish", "FAVORITES", "Aqueous", "ARTISTS", "Grateful Dead", "Billy Strings"),
            ui.compose.onScreenOrder(artists + sections),
        )

        ui.compose.onNodeWithText("A–Z").performClick()
        assertEquals(
            listOf("Phish", "FAVORITES", "Aqueous", "ARTISTS", "Billy Strings", "Grateful Dead"),
            ui.compose.onScreenOrder(artists + sections),
        )
    }

    @Test
    fun `filter narrows the list, including favorites, and clearing restores it`() {
        render()
        ui.compose.onNodeWithContentDescription("Favorite Billy Strings").performClick()

        ui.compose.onNode(hasSetTextAction()).performTextInput("str")
        ui.compose.waitForIdle()
        ui.compose.onNodeWithText("Billy Strings").assertIsDisplayed()
        assertFalse(ui.compose.onNodeWithText("Grateful Dead").exists())
        assertFalse(ui.compose.onNodeWithText("Aqueous").exists())

        ui.compose.onNodeWithContentDescription("Clear").performClick()
        assertEquals(artists.toSet(), ui.compose.onScreenOrder(artists).toSet())
    }

    @Test
    fun `a filter with no matches says so instead of going blank`() {
        render()
        ui.compose.onNode(hasSetTextAction()).performTextInput("zzz")
        ui.compose.onNodeWithText("No artists match \"zzz\".").assertIsDisplayed()
    }

    @Test
    fun `tapping an artist opens that artist`() {
        val nav = render()
        ui.compose.onNodeWithText("Grateful Dead").performClick()
        ui.compose.waitForIdle()
        assertEquals("artist/{backend}/{id}", nav().currentRoute)
        assertEquals("grateful-dead", nav().arg("id"))
    }
}
