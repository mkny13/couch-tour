package dev.mike.couchtour

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
 * and track drill-down is Part 2.2 and playback is Part 3.
 */
class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                TvBrowseScreen()
            }
        }
    }
}
