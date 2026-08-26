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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage

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

    val backgroundGradient = Brush.verticalGradient(
        listOf(
            Color(0xFF14171E),
            Color(0xFF0D0E13),
            Color(0xFF07080B),
        )
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { nav.popBackStack() },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        "Close",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text(
                    if (castDevice != null) "Casting to $castDevice" else state.showTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                )
                FeedbackButton(nav, modifier = Modifier.size(52.dp), iconSize = 28.dp, tint = Color.White)
                CastButton(modifier = Modifier.size(52.dp), iconSize = 28.dp, tint = Color.White)
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            "More",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
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

            val postShowPrompt by vm.postShowPrompt.collectAsState()
            postShowPrompt?.let { prompt ->
                PostShowTourPromptBanner(
                    prompt = prompt,
                    onPlay = { vm.playNextTourStop(prompt) },
                    onDismiss = { vm.dismissPostShowPrompt() },
                )
            }

            Spacer(Modifier.height(8.dp))

            ArtworkBox(
                artUrl = state.artUrl,
                contentDescription = state.trackTitle,
                artistName = state.artistName.ifEmpty { if (state.backend == Backend.PHISHIN.id) PHISH.name else null },
                showDate = state.showDate,
                venueName = state.venueName,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(0.82f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp)),
            )

            Spacer(Modifier.weight(1f))

            val onGoToShow = {
                val key = state.queueKey
                if (key != null) {
                    openQueueKey(key, nav)
                } else if (state.showDate.isNotEmpty()) {
                    if (state.backend == Backend.RELISTEN.id && state.artistId.isNotEmpty()) {
                        nav.navigate("recording/relisten/${state.artistId}/${state.showDate}")
                    } else {
                        nav.navigate("show/${state.showDate}")
                    }
                }
            }

            val onGoToArtist = {
                if (state.backend == Backend.RELISTEN.id && state.artistId.isNotEmpty()) {
                    nav.navigate("artist/relisten/${state.artistId}")
                } else {
                    nav.navigate("artist/phishin/phish")
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.trackTitle.ifEmpty { "Not Playing" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onGoToShow)
                            .padding(vertical = 2.dp, horizontal = 2.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        if (state.showDate.isNotEmpty()) {
                            Text(
                                state.showDate,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onGoToShow)
                                    .padding(vertical = 2.dp, horizontal = 4.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        AudioQualityBadge(format = state.audioFormat, isFlac = state.isFlac)
                    }
                    if (state.artistName.isNotEmpty() || state.venueName.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            if (state.artistName.isNotEmpty()) {
                                Text(
                                    state.artistName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(onClick = onGoToArtist)
                                        .padding(vertical = 2.dp, horizontal = 4.dp),
                                )
                            }
                            if (state.venueName.isNotEmpty()) {
                                if (state.artistName.isNotEmpty()) {
                                    Text(
                                        " · ",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                                Text(
                                    state.venueName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
                if (state.backend == Backend.RELISTEN.id && state.trackId != null) {
                    LikeTrackButton(
                        trackId = state.trackId!!,
                        modifier = Modifier.size(52.dp),
                        iconSize = 28.dp
                    )
                } else if (state.trackId != null && state.trackId?.toLongOrNull() != null) {
                    LikeButton(
                        type = Likable.Track,
                        id = state.trackId!!.toLong(),
                        initiallyLiked = state.likedByUser,
                        initialCount = state.likesCount,
                        modifier = Modifier.padding(start = 4.dp),
                        iconSize = 28.dp
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            WaveformScrubber(
                waveformUrl = state.waveformUrl,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                playedColor = MaterialTheme.colorScheme.primary,
                unplayedColor = Color(0xFF282C37),
                onSeek = { vm.seekTo(it) },
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    fmt(state.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (state.durationMs > 0) fmt(state.durationMs) else "--:--",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { vm.previous() },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(Modifier.width(36.dp))
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { vm.togglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (state.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                Spacer(Modifier.width(36.dp))
                IconButton(
                    onClick = { vm.next() },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        "Next",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}

/** Artwork with a procedural artwork fallback, so a null or failed [artUrl] renders vintage cassette graphics instead of a blank hole. */
@Composable
fun ArtworkBox(
    artUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    show: ShowSummary? = null,
    artistName: String? = null,
    showDate: String? = null,
    venueName: String? = null,
) {
    ShowArtwork(
        artUrl = artUrl,
        show = show,
        artistName = artistName,
        date = showDate,
        venue = venueName,
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

@Composable
internal fun PostShowTourPromptBanner(
    prompt: ShowSummary,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "NEXT TOUR STOP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = listOfNotNull(prompt.date, prompt.where.ifBlank { null } ?: prompt.artist.name).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onPlay,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("Play")
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun AudioQualityBadge(format: String, isFlac: Boolean, modifier: Modifier = Modifier) {
    Surface(
        color = if (isFlac) MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isFlac) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Text(
            text = format,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
