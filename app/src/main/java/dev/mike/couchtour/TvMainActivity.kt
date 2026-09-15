package dev.mike.couchtour

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * The Google TV / Android TV entry point (#182, Part 1 of #9) — Leanback-launched, so it's
 * a separate Activity from [MainActivity] rather than that one reused with a different
 * theme. The phone Activity's navigation assumes touch and a back stack UI a D-pad has no
 * equivalent for; keeping them apart means Part 2's browse rows can be built TV-native from
 * the start instead of retrofitted around phone assumptions.
 *
 * It shares [PlaybackService] and the [Catalog] model with the phone app completely
 * unchanged: [PlayerViewModel] already talks to the service over a plain media3
 * MediaController, which doesn't care which Activity created it, and [loadArtistsByBackend]
 * is a free function in the same module, not gated behind any phone-specific wiring. Part 1
 * is only the foundation — this screen exists to prove both are reachable from here, not to
 * render real browse rows (that's Part 2).
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

@Composable
private fun TvBrowseScreen(vm: PlayerViewModel = viewModel()) {
    // Loading the real catalog here — rather than a static placeholder — is what actually
    // proves Catalog.kt's browse model is reachable from the TV process, not just importable.
    // Part 2 replaces this count with the artist/show rows it stands in for.
    var artistCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        artistCount = runCatching { loadArtistsByBackend().values.sumOf { it.size } }.getOrNull()
    }
    val playerState by vm.state.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(48.dp)) {
            Text(
                text = buildString {
                    append("Couch Tour")
                    artistCount?.let { append(" — $it artists") }
                    if (playerState.connected) append(" — playback connected")
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
