package dev.mike.couchtour

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Transport controls (uat-027, uat-028) on the mini-player and the full Now Playing screen,
 * against a [FakePlayer] loaded through the real [PlayerViewModel.playShow] queue builder — so
 * what the UI shows is what the app actually put in the queue, not a hand-built state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = UI_TEST_QUALIFIERS)
class PlaybackControlsUiTest {

    private val ui = ComposeUiRule()

    @get:Rule
    val rules = ui.chain

    private lateinit var vm: PlayerViewModel
    private val player = FakePlayer()

    private val show = Show(
        date = "1997-11-22",
        venueName = "Hampton Coliseum",
        audioStatus = "complete",
        tracks = listOf("Tweezer", "Black-Eyed Katy", "Piper").mapIndexed { i, title ->
            Track(
                id = 100L + i,
                title = title,
                position = i + 1,
                duration = 600_000,
                setName = "Set 2",
                audioStatus = "complete",
                mp3Url = "https://example.invalid/${i + 1}.mp3",
            )
        },
    )

    @Before
    fun setUp() {
        vm = playerViewModelForTest()
    }

    /** Starts [show] the way tapping it in the app does, and waits for the UI to notice. */
    private fun startShow() {
        ui.compose.runOnUiThread {
            vm.attach(player)
            vm.playShow(show)
        }
        ui.compose.waitUntil(5_000) { ui.compose.onNodeWithText("Tweezer").exists() }
    }

    @Test
    fun `mini player shows the current track and toggles play and pause`() {
        val nav = ui.setScreen {
            val state by vm.state.collectAsState()
            MiniPlayer(state, vm, it)
        }
        ui.compose.onNodeWithText("Not Playing").assertIsDisplayed()
        startShow()

        ui.compose.onNodeWithText("1997-11-22 · Phish · MP3").assertIsDisplayed()
        assertTrue("playShow should start playback", player.playWhenReady)

        ui.compose.onNodeWithContentDescription("Pause").performClick()
        ui.compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        assertFalse(player.playWhenReady)

        ui.compose.onNodeWithContentDescription("Play").performClick()
        ui.compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        assertTrue(player.playWhenReady)

        ui.compose.onNodeWithContentDescription("Next").performClick()
        ui.compose.onNodeWithText("Black-Eyed Katy").assertIsDisplayed()
        assertEquals(1, player.index)

        ui.compose.onNodeWithText("Black-Eyed Katy").performClick()
        ui.compose.waitForIdle()
        assertEquals("player", nav().currentRoute)
    }

    @Test
    fun `now playing transport row steps through the queue`() {
        ui.setScreen { NowPlayingScreen(vm, it) }
        startShow()

        ui.compose.onNodeWithContentDescription("Next").performClick()
        ui.compose.onNodeWithText("Black-Eyed Katy").assertIsDisplayed()
        ui.compose.onNodeWithContentDescription("Next").performClick()
        ui.compose.onNodeWithText("Piper").assertIsDisplayed()
        assertEquals("Piper", player.currentTitle)

        ui.compose.onNodeWithContentDescription("Previous").performClick()
        ui.compose.onNodeWithText("Black-Eyed Katy").assertIsDisplayed()
        assertEquals(1, player.index)

        ui.compose.onNodeWithContentDescription("Pause").performClick()
        ui.compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `controls are harmless before a player connects`() {
        ui.setScreen {
            val state by vm.state.collectAsState()
            MiniPlayer(state, vm, it)
        }
        // No player attached: the taps must be dropped, not crash the app.
        ui.compose.onNodeWithContentDescription("Play").performClick()
        ui.compose.onNodeWithContentDescription("Next").performClick()
        ui.compose.onNodeWithText("Not Playing").assertIsDisplayed()
    }
}
