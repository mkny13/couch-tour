package dev.mike.couchtour

import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.tv.material3.MaterialTheme

/**
 * The Google TV / Android TV entry point (#182, Part 1 of #9) — Leanback-launched, so it's
 * a separate Activity from [MainActivity] rather than that one reused with a different
 * theme. The phone Activity's navigation assumes touch and a back stack UI a D-pad has no
 * equivalent for; keeping them apart means the browse rows (#225, Part 2.1) could be built
 * TV-native from the start instead of retrofitted around phone assumptions.
 *
 * It shares [PlaybackService] and the [Catalog] model with the phone app completely
 * unchanged: [PlayerViewModel] already talks to the service over a plain media3
 * MediaController, which doesn't care which Activity created it, and the browse data flows
 * through the same [groupArtistsForBrowse]/[MusicSource.periods] seams the phone app and
 * Android Auto consume. Part 2.1 adds the artist and year levels ([TvBrowseScreen]); show
 * and track drill-down is Part 2.2, and Part 3 (#184) adds playback, Now Playing, queue,
 * and remote transport/volume wiring.
 */
class TvMainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by lazy {
        ViewModelProvider(this)[PlayerViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                TvBrowseScreen(vm = playerViewModel)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                playerViewModel.togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (!playerViewModel.state.value.isPlaying) playerViewModel.togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (playerViewModel.state.value.isPlaying) playerViewModel.togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playerViewModel.next()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playerViewModel.previous()
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                playerViewModel.seekTo(playerViewModel.state.value.positionMs + 15_000)
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                playerViewModel.seekTo((playerViewModel.state.value.positionMs - 15_000).coerceAtLeast(0))
                true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (playerViewModel.state.value.isPlaying) playerViewModel.togglePlayPause()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
