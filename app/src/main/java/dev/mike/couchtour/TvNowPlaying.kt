package dev.mike.couchtour

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Pure helper calculating progress fraction [0f, 1f] safely.
 */
internal fun tvProgressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * Pure helper combining artist, show date, and venue into a readable TV subtitle.
 */
internal fun tvFormatTrackSubtitle(artistName: String, showDate: String, venueName: String): String {
    return listOfNotNull(
        artistName.takeIf { it.isNotBlank() },
        showDate.takeIf { it.isNotBlank() },
        venueName.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/**
 * One item in the TV "Continue listening" row.
 */
internal data class TvContinueListeningItem(
    val progress: Progress,
    val title: String,
    val subtitle: String,
)

/**
 * Pure mapping of in-progress listening history for TV home screen.
 */
internal fun tvContinueListeningItems(list: List<Progress>): List<TvContinueListeningItem> =
    list.filter { !it.finished && it.deletedAt == null }.map { p ->
        TvContinueListeningItem(
            progress = p,
            title = p.title,
            subtitle = if (p.trackTitle.isNotBlank()) "${p.trackTitle} · ${p.subtitle}" else p.subtitle,
        )
    }

/**
 * Google TV Now Playing Screen (#184, Part 3 of #9):
 * Split layout with artwork, metadata, progress scrubber, and transport controls on the left,
 * and a scrollable, D-pad navigable queue on the right.
 */
@Composable
fun TvNowPlayingScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()

    // Scrubber update ticker while playing
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            delay(500)
            vm.refresh()
        }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        // Top navigation bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onBack) {
                Text("← Browse")
            }
            if (state.audioFormat.isNotEmpty()) {
                Text(
                    text = state.audioFormat,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Split screen: Left = Player details & controls, Right = Queue
        Row(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            // Left pane: Artwork, Track Info, Progress, Controls
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Artwork and Track Info row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShowArtwork(
                        artUrl = state.artUrl,
                        artistName = state.artistName,
                        date = state.showDate,
                        venue = state.venueName,
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.trackTitle.ifEmpty { "No track playing" },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = tvFormatTrackSubtitle(state.artistName, state.showDate, state.venueName)
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Progress Bar and Timers
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    val progressFraction = tvProgressFraction(state.positionMs, state.durationMs)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = fmt(state.positionMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = fmt(state.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }

                // Transport Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { vm.previous() },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Track")
                    }

                    IconButton(
                        onClick = { vm.seekTo((state.positionMs - 15_000).coerceAtLeast(0)) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.FastRewind, contentDescription = "Rewind 15s")
                    }

                    Button(
                        onClick = { vm.togglePlayPause() },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (state.isPlaying) "Pause" else "Play")
                    }

                    IconButton(
                        onClick = { vm.seekTo((state.positionMs + 15_000).coerceAtMost(state.durationMs)) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.FastForward, contentDescription = "Forward 15s")
                    }

                    IconButton(
                        onClick = { vm.next() },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next Track")
                    }
                }
            }

            // Right pane: Queue list
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight(),
            ) {
                Text(
                    text = "Queue (${state.queue.size} ${plural(state.queue.size, "track")})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                if (state.queue.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No tracks in queue",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.queue, key = { "queue-${it.index}-${it.mediaId}" }) { item ->
                            val isCurrent = item.index == state.trackIndex
                            Card(
                                onClick = { vm.seekToTrack(item.index) },
                                modifier = Modifier.fillMaxWidth(),
                                scale = CardDefaults.scale(focusedScale = 1.02f),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (isCurrent) {
                                        Text(
                                            text = "▶",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.width(24.dp),
                                        )
                                    } else {
                                        Text(
                                            text = "${item.index + 1}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(24.dp),
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (item.setName.isNotEmpty()) {
                                            Text(
                                                text = item.setName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }

                                    if (item.durationMs > 0) {
                                        Text(
                                            text = fmt(item.durationMs),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
