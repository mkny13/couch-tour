package dev.mike.couchtour

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Continue Listening long-press menu (uat-044; the macOS equivalents are uat-007/uat-008):
 * every action on a real [InProgressLedgerRow], checked against the progress table it writes
 * to — the table that is the whole reason the app exists (CLAUDE.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = UI_TEST_QUALIFIERS)
class InProgressMenuUiTest {

    private val ui = ComposeUiRule()

    @get:Rule
    val rules = ui.chain

    private val servers = CatalogServers(
        phishIn = mapOf(
            "/shows/1997-11-22" to """{
                "date":"1997-11-22","venue_name":"Hampton Coliseum","audio_status":"complete",
                "tracks":[
                    {"id":1,"title":"Tweezer","position":1,"duration":600000,"audio_status":"complete",
                     "mp3_url":"https://example.invalid/1.mp3"},
                    {"id":2,"title":"Black-Eyed Katy","position":2,"duration":600000,"audio_status":"complete",
                     "mp3_url":"https://example.invalid/2.mp3"}
                ]
            }""",
        ),
    )

    private lateinit var vm: PlayerViewModel

    private val progress = Progress(
        queueKey = showQueueKey("1997-11-22"),
        title = "1997-11-22",
        subtitle = "Hampton Coliseum",
        artUrl = null,
        trackIndex = 1,
        positionMs = 42_000,
        trackTitle = "Black-Eyed Katy",
        updatedAt = 1_000,
        artist = "Phish",
    )

    @Before
    fun setUp() {
        servers.start()
        vm = playerViewModelForTest()
        runBlocking { vm.progressDao.put(progress) }
    }

    @After
    fun tearDown() {
        // PhishInDb is a process-wide singleton, so a row left behind would leak into the next
        // test's database.
        runBlocking { vm.progressDao.clear(progress.queueKey, System.currentTimeMillis()) }
        servers.shutdown()
    }

    private fun openMenu(): () -> NavHostController {
        val nav = ui.setScreen { InProgressLedgerRow(progress, vm, it) }
        ui.compose.onNodeWithText("Black-Eyed Katy").performTouchInput { longClick() }
        return nav
    }

    private fun stored(): Progress? = runBlocking { vm.progressDao.get(progress.queueKey) }

    @Test
    fun `long press offers every action`() {
        openMenu()
        listOf(
            "Resume playback", "Open show", "Mark completed",
            "Remove from In Progress", "Delete from history",
        ).forEach { ui.compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `playlist rows offer Open playlist instead`() {
        val playlist = progress.copy(queueKey = playlistQueueKey("summer-jams"))
        ui.setScreen { InProgressLedgerRow(playlist, vm, it) }
        ui.compose.onNodeWithText("Black-Eyed Katy").performTouchInput { longClick() }
        ui.compose.onNodeWithText("Open playlist").assertIsDisplayed()
        assertFalse(ui.compose.onNodeWithText("Open show").exists())
    }

    @Test
    fun `mark completed finishes the row and closes the menu`() {
        openMenu()
        ui.compose.onNodeWithText("Mark completed").performClick()
        ui.compose.waitUntil(5_000) { stored()?.finished == true }
        assertFalse(ui.compose.onNodeWithText("Mark completed").exists())
        assertFalse(stored()!!.dismissed)
    }

    @Test
    fun `remove hides it from In Progress but keeps history`() {
        openMenu()
        ui.compose.onNodeWithText("Remove from In Progress").performClick()
        ui.compose.waitUntil(5_000) { stored()?.dismissed == true }
        assertFalse(stored()!!.finished)
        val inProgress = runBlocking { vm.progressDao.inProgress().first() }
        val history = runBlocking { vm.progressDao.history().first() }
        assertFalse(inProgress.any { it.queueKey == progress.queueKey })
        assertTrue(history.any { it.queueKey == progress.queueKey })
    }

    @Test
    fun `delete erases it from history`() {
        openMenu()
        ui.compose.onNodeWithText("Delete from history").performClick()
        ui.compose.waitUntil(5_000) { stored() == null }
    }

    @Test
    fun `open show navigates to the show`() {
        val nav = openMenu()
        ui.compose.onNodeWithText("Open show").performClick()
        ui.compose.waitForIdle()
        assertEquals("show/{date}", nav().currentRoute)
        assertEquals("1997-11-22", nav().arg("date"))
    }

    @Test
    fun `resume playback reloads the show at the saved track and position`() {
        val player = FakePlayer()
        ui.compose.runOnUiThread { vm.attach(player) }
        openMenu()
        ui.compose.onNodeWithText("Resume playback").performClick()
        ui.waitFor { player.items.isNotEmpty() }

        assertEquals(listOf("Tweezer", "Black-Eyed Katy"), player.items.map { it.mediaMetadata.title.toString() })
        assertEquals(1, player.index)
        assertEquals(42_000L, player.requestedPositionMs)
        assertTrue(player.playWhenReady)
    }
}
