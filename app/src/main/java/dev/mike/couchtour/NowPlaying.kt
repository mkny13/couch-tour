package dev.mike.couchtour

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.navigation.NavHostController

/**
 * Full-screen Now Playing screen recreated from the high-fidelity Ledger handoff.
 * Dark appearance: full-bleed cover-art gradient hero behind title text with bottom fade.
 * Light appearance: clean elevated background without full-bleed hero art.
 */
@Composable
fun NowPlayingScreen(vm: PlayerViewModel, nav: NavHostController) {
    val state by vm.state.collectAsState()
    val ledger = LocalLedgerColors.current
    var menuOpen by remember { mutableStateOf(false) }
    var showJamChartNote by remember { mutableStateOf(true) }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ledger.elevatedBackground)
    ) {
        // Dark variant artwork hero backdrop
        if (ledger.isDark) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFFD97706), Color(0xFF991B1B), Color(0xFF1E1B4B))
                        )
                    )
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp)
            ) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x8C5B8CFF), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.08f),
                        radius = size.width * 0.60f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x80F06BB0), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.04f),
                        radius = size.width * 0.55f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x59F2A93B), Color.Transparent),
                        center = Offset(size.width * 0.50f, size.height * 0.00f),
                        radius = size.width * 0.70f
                    )
                )
            }
            // Fade to background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(top = 230.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xFF12141F))
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { nav.popBackStack() },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse",
                        tint = ledger.textHeadline,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                FeedbackButton(nav, modifier = Modifier.size(44.dp), iconSize = 22.dp, tint = ledger.textHeadline)
                CastButton(modifier = Modifier.size(44.dp), iconSize = 22.dp, tint = ledger.textHeadline)
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = ledger.textHeadline,
                            modifier = Modifier.size(22.dp)
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
                        if (state.artistName.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Go to artist") },
                                onClick = {
                                    menuOpen = false
                                    onGoToArtist()
                                }
                            )
                        }
                    }
                }
            }

            val postShowPrompt: ShowSummary? by vm.postShowPrompt.collectAsState()
            postShowPrompt?.let { prompt ->
                PostShowTourPromptBanner(
                    prompt = prompt,
                    onPlay = { vm.playNextTourStop(prompt) },
                    onDismiss = { vm.dismissPostShowPrompt() },
                )
            }

            // Show & Tape Info Header
            val topHeaderPadding = if (ledger.isDark) 150.dp else 24.dp
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = topHeaderPadding)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.clickable(onClick = onGoToShow)
                ) {
                    Text(
                        text = state.artistName.ifEmpty { "Phish" },
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.02).sp,
                        color = ledger.textHeadline,
                        lineHeight = 33.sp
                    )
                    if (state.showDate.isNotEmpty()) {
                        Text(
                            text = state.showDate,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.02).sp,
                            color = ledger.textHeadline,
                            lineHeight = 33.sp
                        )
                    }
                }
                if (state.venueName.isNotEmpty()) {
                    Text(
                        text = state.venueName,
                        fontSize = 15.sp,
                        color = ledger.textSecondary,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }

                // TAPE and RATING Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TAPE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.4.sp,
                            color = ledger.textMuted
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 3.dp)
                        ) {
                            Text(
                                text = state.tapeLineage ?: (if (state.isFlac) "SBD · Soundboard" else "Audience recording"),
                                fontSize = 14.sp,
                                color = ledger.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = ledger.textSubtle,
                                modifier = Modifier.size(14.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (ledger.isDark) Color(0x80F2A93B) else Color(0x66A06615),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "FLAC",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.sp,
                                    color = ledger.ratingAmber
                                )
                            }
                        }
                    }

                    if (state.showRating > 0.0) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "SHOW RATING",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.4.sp,
                                color = ledger.textMuted
                            )
                            Text(
                                text = "★ " + "%.1f".format(java.util.Locale.US, state.showRating),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = ledger.ratingAmber,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Track & Set Info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    text = "TRACK ${state.trackIndex + 1}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                    color = ledger.textSubtle
                )
                Text(
                    text = state.trackTitle.ifEmpty { "Track" },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.015).sp,
                    color = ledger.textHeadline,
                    lineHeight = 28.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )

                // Tags row: JAM CHART badge (if phish track) + Duration chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    if (state.backend == Backend.PHISHIN.id || state.backend == null) {
                        Box(
                            modifier = Modifier
                                .border(1.dp, Color(0x73B5ABFC), RoundedCornerShape(4.dp))
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { showJamChartNote = !showJamChartNote }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "JAM CHART",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.sp,
                                    color = ledger.accentTintText
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = ledger.accentTintText,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                        }
                    }
                    if (state.durationMs > 0) {
                        Box(
                            modifier = Modifier
                                .border(1.dp, ledger.controlOutline, RoundedCornerShape(4.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = fmt(state.durationMs),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                                color = ledger.textSecondary
                            )
                        }
                    }
                }

                // Expandable Jam Chart Note Card
                if (showJamChartNote && (state.backend == Backend.PHISHIN.id || state.backend == null)) {
                    JamChartNoteCard(
                        noteText = "Notable version from phish.in archive records.",
                        onDismiss = { showJamChartNote = false },
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }

            // Waveform Scrubber
            val progressFraction = if (state.durationMs > 0) {
                (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0.41f

            WaveformScrubber(
                progress = progressFraction,
                onSeek = { fraction ->
                    val seekMs = (fraction * state.durationMs).toLong()
                    vm.seekTo(seekMs)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 24.dp)
            )

            // Timestamps
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = fmt(state.positionMs),
                    fontSize = 12.sp,
                    color = ledger.textMuted
                )
                Text(
                    text = formatRemainingTime(state.positionMs, state.durationMs),
                    fontSize = 12.sp,
                    color = ledger.textMuted
                )
            }

            // Transport Controls Row: Add to playlist -> Prev -> Play/Pause (76dp) -> Next -> Like with count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Add to Playlist button
                Box(
                    modifier = Modifier.size(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val trackRef = LocalPlaylistTrackEntity(
                        playlistId = "",
                        position = 0,
                        backend = state.backend ?: Backend.PHISHIN.id,
                        trackId = state.trackId.orEmpty(),
                        showDate = state.showDate,
                        artistSlug = state.artistId.ifEmpty { "phish" },
                        recordingId = null,
                        title = state.trackTitle,
                        durationMs = state.durationMs,
                        venueName = state.venueName,
                        artUrl = state.artUrl
                    )
                    AddToPlaylistButton(
                        vm = vm,
                        modifier = Modifier.size(64.dp),
                        iconSize = 24.dp,
                        tint = ledger.accentIcon,
                        buildRef = { trackRef }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Previous button
                IconButton(
                    onClick = { vm.previous() },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = ledger.textPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Play / Pause central button (76dp)
                val playButtonBg = if (ledger.isDark) Color(0xFFF3F5FE) else Color(0xFF20222C)
                val playButtonFg = if (ledger.isDark) Color(0xFF161826) else Color(0xFFFFFFFF)
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(playButtonBg)
                        .clickable { vm.togglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(34.dp),
                            strokeWidth = 3.dp,
                            color = playButtonFg
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = playButtonFg,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Next button
                IconButton(
                    onClick = { vm.next() },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = ledger.textPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Like / Heart button with count
                Box(
                    modifier = Modifier.size(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (state.backend == Backend.RELISTEN.id && state.trackId != null) {
                            LikeTrackButton(
                                trackId = state.trackId!!,
                                modifier = Modifier.size(38.dp),
                                iconSize = 30.dp
                            )
                        } else if (state.trackId != null && state.trackId?.toLongOrNull() != null) {
                            LikeButton(
                                type = Likable.Track,
                                id = state.trackId!!.toLong(),
                                initiallyLiked = state.likedByUser,
                                initialCount = state.likesCount,
                                modifier = Modifier.size(38.dp),
                                iconSize = 30.dp
                            )
                        } else {
                            IconButton(onClick = {}, modifier = Modifier.size(38.dp)) {
                                Icon(
                                    Icons.Default.FavoriteBorder,
                                    contentDescription = "Like",
                                    tint = ledger.accentIcon,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        if (state.likesCount > 0) {
                            Text(
                                text = "${state.likesCount}",
                                fontSize = 11.sp,
                                color = ledger.textMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Artwork with a procedural artwork fallback. */
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
    val ledger = LocalLedgerColors.current
    Surface(
        color = if (isFlac) (if (ledger.isDark) Color(0x33F2A93B) else Color(0x1AA06615)) else ledger.cardSurface,
        contentColor = if (isFlac) ledger.ratingAmber else ledger.textMuted,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Text(
            text = format,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
