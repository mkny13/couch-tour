package dev.mike.couchtour

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest

/**
 * The full-screen player, reached by tapping the compact bar or the media notification
 * (`EXTRA_OPEN_NOW_PLAYING`). The bar keeps only art, title, and play/pause; everything else
 * — the waveform, timestamps, and the full transport — lives here.
 */
@Composable
fun NowPlayingScreen(vm: PlayerViewModel, nav: NavHostController) {
    val state by vm.state.collectAsState()
    val castDevice by Casting.deviceName.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val tint = rememberArtGradientColor(state.artUrl) ?: MaterialTheme.colorScheme.primaryContainer

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(tint, MaterialTheme.colorScheme.surface))
            )
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Default.KeyboardArrowDown, "Close")
                }
                Text(
                    if (castDevice != null) "Casting to $castDevice" else state.showTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                CastButton()
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val key = state.queueKey
                        DropdownMenuItem(
                            text = { Text(if (key == null) "Nothing to open" else "Go to show") },
                            enabled = key != null,
                            onClick = {
                                menuOpen = false
                                key?.let { openQueueKey(it, nav) }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            ArtworkBox(
                artUrl = state.artUrl,
                contentDescription = state.trackTitle,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(0.78f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp)),
            )

            Spacer(Modifier.height(28.dp))

            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    state.trackTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.showTitle.isNotEmpty()) {
                    Text(
                        state.showTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            if (state.durationMs > 0) {
                WaveformScrubber(
                    waveformUrl = state.waveformUrl,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    playedColor = MaterialTheme.colorScheme.primary,
                    unplayedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    onSeek = { vm.seekTo(it) },
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        fmt(state.positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        fmt(state.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth().padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.previous() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(24.dp))
                }
                Spacer(Modifier.width(28.dp))
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { vm.togglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(28.dp))
                IconButton(onClick = { vm.next() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, "Next", Modifier.size(24.dp))
                }
            }
        }
    }
}

/** Artwork with a fallback icon behind it, so a null or failed [artUrl] isn't a blank hole. */
@Composable
fun ArtworkBox(artUrl: String?, contentDescription: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Icon(
            Icons.Default.Album,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.Center).fillMaxSize(0.4f),
        )
        if (artUrl != null) {
            AsyncImage(
                model = artUrl,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The dark-vibrant swatch from the current artwork, for the player's background gradient.
 * Null while loading, for a null [artUrl], or if extraction fails — the caller falls back to
 * `primaryContainer`, which is also what every Relisten show gets (no `artUrl` today).
 */
@Composable
private fun rememberArtGradientColor(artUrl: String?): Color? {
    val context = LocalContext.current
    var color by remember(artUrl) { mutableStateOf<Color?>(null) }
    LaunchedEffect(artUrl) {
        color = null
        color = artUrl?.let { url ->
            runCatching {
                // allowHardware(false): hardware bitmaps can't be read back by Palette, the
                // same constraint Waveform.kt documents for its own Canvas readback.
                val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
                val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
                bitmap?.let { bmp ->
                    val palette = Palette.from(bmp).generate()
                    val swatch = palette.darkVibrantSwatch ?: palette.darkMutedSwatch ?: palette.dominantSwatch
                    swatch?.let { Color(it.rgb) }
                }
            }.getOrNull()
        }
    }
    return color
}
