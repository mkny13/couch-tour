package dev.mike.couchtour

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
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
 * Local playlist search and the reorder interlock (uat-013 — the one UAT calls out as worth
 * testing hardest, and marked "needs work" once): reordering a filtered list would write
 * positions computed against the wrong rows and silently scramble the real track order, so
 * the move buttons must be unavailable while a filter is active.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = UI_TEST_QUALIFIERS)
class LocalPlaylistUiTest {

    private val ui = ComposeUiRule()

    @get:Rule
    val rules = ui.chain

    private lateinit var vm: PlayerViewModel
    private lateinit var playlistId: String
    private val titles = listOf("Tweezer", "Harry Hood", "Tweezer Reprise")

    @Before
    fun setUp() {
        vm = playerViewModelForTest()
        playlistId = runBlocking { vm.createLocalPlaylist("Mixtape") }
        runBlocking {
            titles.forEachIndexed { i, title ->
                vm.localPlaylistDao.addTrack(
                    LocalPlaylistTrackEntity(
                        playlistId = playlistId,
                        position = i,
                        backend = Backend.PHISHIN.id,
                        trackId = "${i + 1}",
                        showDate = "1997-11-22",
                        title = title,
                        durationMs = 600_000,
                    ),
                    now = 1_000L,
                )
            }
        }
    }

    @After
    fun tearDown() {
        // PhishInDb is a process-wide singleton; don't leave this playlist for the next test.
        runBlocking { vm.localPlaylistDao.deletePlaylist(playlistId) }
    }

    private fun render() {
        ui.setScreen { LocalPlaylistScreen(playlistId, vm, it) }
        ui.waitFor { ui.compose.onNodeWithText("Harry Hood").exists() }
    }

    private fun storedOrder(): List<String> =
        runBlocking { vm.localPlaylistDao.tracksOnce(playlistId).map { it.title } }

    @Test
    fun `move buttons reorder the playlist on screen and on disk`() {
        render()
        assertEquals(titles, ui.compose.onScreenOrder(titles))

        // The first row's "Move down".
        ui.compose.onAllNodesWithContentDescription("Move down").onFirst().performClick()
        val expected = listOf("Harry Hood", "Tweezer", "Tweezer Reprise")
        ui.waitFor { storedOrder() == expected }
        ui.waitFor { ui.compose.onScreenOrder(titles) == expected }
    }

    @Test
    fun `search filters the playlist's tracks`() {
        render()
        ui.compose.onNode(hasSetTextAction()).performTextInput("tweezer")
        assertEquals(listOf("Tweezer", "Tweezer Reprise"), ui.compose.onScreenOrder(titles))
        assertFalse(ui.compose.onNodeWithText("Harry Hood").exists())

        ui.compose.onNodeWithContentDescription("Clear").performClick()
        assertEquals(titles, ui.compose.onScreenOrder(titles))
    }

    @Test
    fun `reordering is disabled while a filter is active and comes back when cleared`() {
        render()
        ui.compose.onNode(hasSetTextAction()).performTextInput("tweezer")

        val ups = ui.compose.onAllNodesWithContentDescription("Move up")
        val downs = ui.compose.onAllNodesWithContentDescription("Move down")
        repeat(2) { i ->
            ups[i].assertIsNotEnabled()
            downs[i].assertIsNotEnabled()
        }
        // Attempting it anyway must not touch the stored order.
        downs[0].performClick()
        ui.compose.waitForIdle()
        assertEquals(titles, storedOrder())

        ui.compose.onNodeWithContentDescription("Clear").performClick()
        // Cleared: the middle row can move both ways again.
        ui.compose.onAllNodesWithContentDescription("Move up")[1].assertIsEnabled()
        ui.compose.onAllNodesWithContentDescription("Move down")[1].assertIsEnabled()
    }

    @Test
    fun `a filter with no matches says so`() {
        render()
        ui.compose.onNode(hasSetTextAction()).performTextInput("fluffhead")
        ui.compose.onNodeWithText("No tracks match \"fluffhead\".").assertIsDisplayed()
    }
}
