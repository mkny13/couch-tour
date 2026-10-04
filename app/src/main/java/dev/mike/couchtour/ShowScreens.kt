package dev.mike.couchtour

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController

@Composable
fun ShowScreen(date: String, vm: PlayerViewModel, nav: NavHostController) {
    val show = loadOnce(date) { PhishInApi.show(date) }
    val saved = loadOnce(date) { vm.progressFor(showQueueKey(date)) }
    val localRelease = loadOnce(date) { vm.externalReleaseDao.get(PHISH.key, date) }
    var addingToPlaylist by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Header(date, nav)
        Loaded(show.value) { sRaw ->
            val release = localRelease.value?.getOrNull()
            val s = if (release != null && sRaw.externalReleasePlatform == null) {
                sRaw.copy(externalReleasePlatform = release.platform, externalReleaseUrl = release.url)
            } else sRaw
            val playable = s.tracks.filter { it.playable }
            // A finished show's stored position is the end of the encore, so
            // offering to resume it would just stop again immediately.
            val progress = saved.value?.getOrNull()?.takeIf { !it.finished }
            val artUrl = s.albumCoverUrl ?: s.coverArtUrls?.medium

            if (addingToPlaylist) {
                val trackEntities = playable.mapIndexed { idx, track ->
                    LocalPlaylistTrackEntity(
                        playlistId = "", position = idx, backend = Backend.PHISHIN.id,
                        trackId = track.id.toString(), showDate = date, title = track.title,
                        durationMs = track.duration, artUrl = artUrl,
                    )
                }
                AddTracksToPlaylistDialog(
                    vm = vm,
                    tracks = trackEntities,
                    onDismiss = { addingToPlaylist = false }
                )
            }

            val savedKeys by SavedShows.keys.collectAsState()
            val isSaved = s.date in savedKeys

            LazyColumn {
                item {
                    ShowHeader(
                        show = s,
                        trackCount = playable.size,
                        hasProgress = progress != null,
                        isSaved = isSaved,
                        onResume = {
                            if (progress != null) {
                                vm.playShow(s, progress.trackIndex, progress.positionMs)
                            } else {
                                vm.playShow(s, 0, 0)
                            }
                        },
                        onSave = { SavedShows.toggle(s.date) },
                        onAdd = { addingToPlaylist = true }
                    )
                }
                if (progress != null) {
                    item {
                        ResumeBanner(progress) {
                            vm.playShow(s, progress.trackIndex, progress.positionMs)
                        }
                    }
                }
                tracksGroupedBySet(playable) { index, track ->
                    TrackRow(track, index + 1, date, artUrl, vm) { vm.playShow(s, index, 0) }
                }
            }
        }
    }
}

/**
 * One tape of a Relisten show. [recordingId] null takes the source's own default (P3);
 * switching tapes re-navigates here with a different `src`.
 */
@Composable
fun RecordingScreen(
    backendId: String,
    artistId: String,
    date: String,
    recordingId: String?,
    /** Set by the source picker when it caught this show mid-playback on the source being
     *  switched away from (#17) — where in [ShowDetail.tracks] to pick up once this source
     *  loads. Null on every other navigation here (first visit, resume banner, track tap). */
    resumeIndex: Int? = null,
    resumeMs: Long? = null,
    vm: PlayerViewModel,
    nav: NavHostController,
) {
    val backend = Backend.from(backendId)
    val loaded = loadOnce(Triple(artistId, date, recordingId)) {
        val source = sourceFor(backend ?: error("Unknown backend $backendId"))
        val artist = source.artists().firstOrNull { it.id == artistId } ?: error("Unknown artist $artistId")
        var detail = source.show(artist, date, recordingId)
        
        val release = vm.externalReleaseDao.get(artist.key, date)
        if (release != null && detail.externalRelease == null) {
            runCatching {
                val rPlatform = ExternalReleasePlatform.valueOf(release.platform.uppercase())
                detail = detail.copy(externalRelease = ExternalRelease(rPlatform, release.url))
            }
        }
        
        // A finished show's stored position is the end of the encore, same reasoning
        // ShowScreen's resume banner uses (D22).
        detail to detail.queueKey?.let { vm.progressFor(it) }?.takeIf { !it.finished }
    }

    Column(Modifier.fillMaxSize()) {
        Header(date, nav)
        Loaded(loaded.value) { (detail, progress) ->
            // Keyed on detail, which is a fresh instance exactly once per navigation
            // here (produceState only re-runs when the Triple key above changes) — so
            // this fires once per switch rather than on every recomposition.
            LaunchedEffect(detail) {
                if (resumeIndex != null && detail.tracks.isNotEmpty()) {
                    vm.playRecording(detail, resumeIndex.coerceIn(0, detail.tracks.lastIndex), resumeMs ?: 0)
                }
            }
            var addingToPlaylist by remember { mutableStateOf(false) }

            if (addingToPlaylist) {
                val trackEntities = detail.tracks.mapIndexed { idx, track ->
                    LocalPlaylistTrackEntity(
                        playlistId = "", position = idx, backend = backendId,
                        trackId = track.id, showDate = date, title = track.title,
                        durationMs = track.durationMs,
                        artistSlug = artistId,
                        recordingId = detail.recording?.id,
                        artUrl = detail.summary.artUrl,
                    )
                }
                AddTracksToPlaylistDialog(
                    vm = vm,
                    tracks = trackEntities,
                    onDismiss = { addingToPlaylist = false }
                )
            }

            val savedKeys by SavedShows.keys.collectAsState()
            val isSaved = date in savedKeys

            LazyColumn {
                item {
                    RecordingHeader(
                        detail = detail,
                        backendId = backendId,
                        artistId = artistId,
                        date = date,
                        vm = vm,
                        nav = nav,
                        hasProgress = progress != null,
                        isSaved = isSaved,
                        onResume = {
                            if (progress != null) {
                                vm.playRecording(detail, progress.trackIndex, progress.positionMs)
                            } else {
                                vm.playRecording(detail, 0, 0)
                            }
                        },
                        onSave = { SavedShows.toggle(date) },
                        onAdd = { addingToPlaylist = true }
                    )
                }
                if (progress != null) {
                    item {
                        ResumeBanner(progress) {
                            vm.playRecording(detail, progress.trackIndex, progress.positionMs)
                        }
                    }
                }
                groupedBySet(detail.tracks, { it.setName }, { it.id }, { it.durationMs }) { index, track ->
                    RecordingTrackRow(track, index + 1, detail.summary.artist, date, detail.recording?.id, vm) { vm.playRecording(detail, index, 0) }
                }
            }
        }
    }
}

@Composable
private fun RecordingHeader(
    detail: ShowDetail,
    backendId: String,
    artistId: String,
    date: String,
    vm: PlayerViewModel,
    nav: NavHostController,
    hasProgress: Boolean = false,
    isSaved: Boolean = false,
    onResume: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null
) {
    val summary = detail.summary
    val ledger = LocalLedgerColors.current
    val totalDurationMs = detail.tracks.sumOf { it.durationMs }
    val compactDuration = formatCompactDuration(totalDurationMs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = summary.artist.name,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.02).sp,
                        color = ledger.textHeadline
                    )
                    Text(
                        text = date,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.02).sp,
                        color = ledger.textHeadline
                    )
                }
                summary.venue?.takeIf { it.isNotEmpty() }?.let { venue ->
                    Text(
                        text = listOfNotNull(venue, summary.location).joinToString(", "),
                        fontSize = 14.sp,
                        color = ledger.textSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                val ratingVal = detail.recording?.rating?.takeIf { it > 0.0 } ?: summary.rating
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    if (ratingVal > 0.0) {
                        Text(
                            text = "★ ${"%.1f".format(java.util.Locale.US, ratingVal)}",
                            fontSize = 13.sp,
                            color = ledger.ratingAmber,
                            fontWeight = FontWeight.Medium
                        )
                        Text(text = "·", fontSize = 13.sp, color = ledger.textSubtle)
                    }
                    Text(
                        text = "${detail.tracks.size} tracks · $compactDuration",
                        fontSize = 13.sp,
                        color = ledger.textSecondary
                    )
                }
                detail.recording?.let { rec ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Text(
                            text = recordingLabel(rec),
                            fontSize = 13.sp,
                            color = ledger.textSecondary
                        )
                    }
                }
            }

            // 96dp artwork tile on the RIGHT
            ShowArtwork(
                show = summary,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }

        // Action pills row (36dp height, 18dp radius)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Resume / Play pill
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, ledger.accentIcon, RoundedCornerShape(18.dp))
                    .background(Color(0x299184D9))
                    .clickable(enabled = onResume != null) { onResume?.invoke() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = ledger.accentTintText,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (hasProgress) "Resume" else "Play",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = ledger.accentTintText
                )
            }

            // Saved pill
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, if (isSaved) ledger.accentIcon else ledger.controlOutline, RoundedCornerShape(18.dp))
                    .background(if (isSaved) Color(0x299184D9) else Color.Transparent)
                    .clickable(enabled = onSave != null) { onSave?.invoke() }
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = null,
                    tint = if (isSaved) ledger.accentTintText else ledger.textSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (isSaved) "Saved" else "Save",
                    fontSize = 13.sp,
                    fontWeight = if (isSaved) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSaved) ledger.accentTintText else ledger.textSecondary
                )
            }

            // Add pill
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, ledger.controlOutline, RoundedCornerShape(18.dp))
                    .clickable(enabled = onAdd != null) { onAdd?.invoke() }
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null,
                    tint = ledger.textSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Add",
                    fontSize = 13.sp,
                    color = ledger.textSecondary
                )
            }

            detail.externalRelease?.let { release ->
                ExternalReleasePill(release)
            }

            Spacer(Modifier.weight(1f))
            ShareButton(showShareText(summary.artist, date))
        }

        val headerTags = detail.recording?.tags?.takeIf { it.isNotEmpty() } ?: summary.tags
        if (headerTags.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(headerTags.sortedByDescending { it.priority }) { tag ->
                    TagBadge(tag)
                }
            }
        }
        // No source to switch on a single-source artist (Phish) or a show with only one.
        if (summary.artist.hasMultipleSources && detail.alternates.isNotEmpty()) {
            SourcePicker(detail, backendId, artistId, date, vm, nav)
        }
    }
}

private fun recordingLabel(rec: RecordingRef): String {
    val quality = if (rec.hasFlac) "FLAC · " else ""
    val rating = if (rec.rating > 0) " · %.1f".format(rec.rating) else ""
    return "$quality${rec.label}$rating"
}

/**
 * etree-style "Source" picker (#17): every tape of this show, with the taper/lineage detail
 * that used to be on [RecordingRef] but never rendered anywhere. A bottom sheet rather than
 * [DropdownMenu] because rows here run 2-4 lines once taper, lineage, and badges are in —
 * cramped in a menu built for single-line items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePicker(detail: ShowDetail, backendId: String, artistId: String, date: String, vm: PlayerViewModel, nav: NavHostController) {
    var open by remember { mutableStateOf(false) }
    var compareOpen by remember { mutableStateOf(false) }
    val taperPreferences by vm.taperPreferencesFlow().collectAsState(initial = emptyMap())
    // Stable sort: preferred tapers float to the top, avoided sink to the bottom, and
    // within each band the upstream (rating/quality) order is preserved.
    val sources = remember(detail, taperPreferences) {
        fun prefScore(source: RecordingRef): Int = when (taperPreferences[source.taper ?: source.label]) {
            TaperPref.PREFERRED -> -1
            TaperPref.AVOIDED -> 1
            else -> 0
        }
        (listOfNotNull(detail.recording) + detail.alternates)
            .sortedWith(compareBy { prefScore(it) })
    }

    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        RowItem(
            title = "Source",
            subtitle = "${sources.size} ${plural(sources.size, "source")} for this show",
            artUrl = null,
            onClick = { open = true },
        )
        if (open) {
            ModalBottomSheet(onDismissRequest = { open = false }) {
                LazyColumn {
                    item {
                        RowItem(
                            title = "Compare sources",
                            subtitle = "Listen to snippets of this track side by side",
                            artUrl = null,
                            onClick = {
                                open = false
                                compareOpen = true
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                    items(sources, key = { it.id }) { source ->
                        val current = source.id == detail.recording?.id
                        val taperName = source.taper ?: source.label
                        val pref = taperPreferences[taperName]
                        SourceRow(
                            source = source,
                            current = current,
                            preference = pref,
                            onCyclePreference = {
                                // Owner picked a visible icon (issue #173 comment): tapping
                                // cycles neutral -> preferred -> avoided -> neutral.
                                vm.cycleTaperPreference(taperName, pref)
                            },
                        ) {
                            open = false
                            if (!current) {
                                // If this show is playing (or paused) right now, carry the
                                // position into the same track index on the new source — an
                                // approximation, since tapers split tracks differently (#17).
                                // Waveform-matched resume is future work, not attempted here.
                                val playing = vm.state.value
                                val resume = "&resumeIndex=${playing.trackIndex}&resumeMs=${playing.positionMs}"
                                    .takeIf { detail.queueKey != null && playing.queueKey == detail.queueKey }
                                    .orEmpty()
                                nav.navigate("recording/$backendId/$artistId/$date?src=${source.id}$resume")
                            }
                        }
                    }
                }
            }
        }
        if (compareOpen) {
            CompareSourcesSheet(
                detail = detail,
                backendId = backendId,
                artistId = artistId,
                date = date,
                vm = vm,
                nav = nav,
                onDismiss = { compareOpen = false }
            )
        }
    }
}

@Composable
internal fun SourceRow(
    source: RecordingRef,
    current: Boolean,
    preference: String? = null,
    onCyclePreference: () -> Unit = {},
    onClick: () -> Unit,
) {
    val isPreferred = preference == TaperPref.PREFERRED
    val isAvoided = preference == TaperPref.AVOIDED
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (isPreferred) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            // Avoided tapers stay visible (so they can be un-avoided) but read as demoted.
            .alpha(if (isAvoided) 0.5f else 1f)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (current) "✓ " else "") + source.label,
                fontSize = 16.sp,
                fontWeight = if (isPreferred) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isPreferred) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCyclePreference, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = when (preference) {
                        TaperPref.PREFERRED -> Icons.Rounded.ThumbUp
                        TaperPref.AVOIDED -> Icons.Rounded.ThumbDown
                        else -> Icons.Outlined.ThumbUp
                    },
                    contentDescription = when (preference) {
                        TaperPref.PREFERRED -> "Marked preferred — tap to mark avoided"
                        TaperPref.AVOIDED -> "Marked avoided — tap to clear"
                        else -> "Tap to mark this taper preferred"
                    },
                    modifier = Modifier.size(20.dp),
                    tint = when (preference) {
                        TaperPref.PREFERRED -> MaterialTheme.colorScheme.primary
                        TaperPref.AVOIDED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                )
            }
            if (source.hasFlac) {
                SourceBadge("FLAC", MaterialTheme.colorScheme.secondary)
            } else {
                SourceBadge("MP3", MaterialTheme.colorScheme.outline)
            }
            if (source.isSoundboard) SourceBadge("SBD", MaterialTheme.colorScheme.primary)
            // Heuristic, not a real field — see RecordingRef.looksLikeMatrix. Labelled with
            // a "?" so it reads as a guess rather than a confirmed fact.
            if (source.looksLikeMatrix) SourceBadge("Matrix?", MaterialTheme.colorScheme.tertiary)
        }
        if (source.rating > 0) {
            val reviews = if (source.reviewCount > 0) " · ${source.reviewCount} ${plural(source.reviewCount, "review")}" else ""
            Text(
                "★ %.1f".format(source.rating) + reviews,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        // Redundant when the label already is the taper's name (RelistenSource.toRecordingRef
        // defaults label to taper) — only shown when it adds information the title didn't.
        if (source.taper != null && source.taper != source.label) {
            Text("Taper: ${source.taper}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        source.lineage?.let {
            Text("Lineage: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun SourceBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        contentColor = color,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(start = 6.dp),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun RecordingTrackRow(
    track: PlayableTrack,
    number: Int,
    artist: ArtistRef,
    date: String,
    recordingId: String?,
    vm: PlayerViewModel,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$number", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, fontSize = 15.sp, maxLines = 1)
            if (track.tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    track.tags.sortedByDescending { it.priority }.take(3).forEach { tag ->
                        TagBadge(tag)
                    }
                }
            }
        }
        Text(fmt(track.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        // Relisten has no per-track page (trackShareUrl always null for it) — the share
        // falls back to the show link, so no trackSlug to pass here.
        ShareButton(trackShareText(artist, date, track.title, trackSlug = null))
        LikeTrackButton(
            LikedTrackRef(
                id = track.id,
                title = track.title,
                showDate = date,
                venueName = track.venueName,
                durationMs = track.durationMs,
                artistName = artist.name,
                artistSlug = artist.id,
                recordingId = recordingId,
                artUrl = track.artUrl,
            )
        )
        AddToPlaylistButton(vm) {
            LocalPlaylistTrackEntity(
                playlistId = "", position = 0, backend = Backend.RELISTEN.id,
                trackId = track.id, showDate = date, artistSlug = artist.id, recordingId = recordingId,
                title = track.title, durationMs = track.durationMs, venueName = track.venueName,
                artUrl = track.artUrl,
            )
        }
    }
}

@Composable
private fun ShowHeader(
    show: Show,
    trackCount: Int,
    hasProgress: Boolean = false,
    isSaved: Boolean = false,
    onResume: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null
) {
    val ledger = LocalLedgerColors.current
    val totalDurationMs = show.duration
    val compactDuration = formatCompactDuration(totalDurationMs)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Phish",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.02).sp,
                        color = ledger.textHeadline
                    )
                    Text(
                        text = show.date,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.02).sp,
                        color = ledger.textHeadline
                    )
                }
                show.venueName?.takeIf { it.isNotEmpty() }?.let { venue ->
                    Text(
                        text = listOfNotNull(venue, show.location).joinToString(", "),
                        fontSize = 14.sp,
                        color = ledger.textSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        text = "$trackCount tracks · $compactDuration",
                        fontSize = 13.sp,
                        color = ledger.textSecondary
                    )
                }
                val hasSbd = show.tags.any { it.name.contains("sbd", ignoreCase = true) }
                val hasFlac = show.tracks.any { it.mp3Url?.contains("flac", ignoreCase = true) == true }
                val tapeStr = listOf(
                    if (hasSbd) "SBD" else "AUD",
                    if (hasFlac) "FLAC" else null
                ).filterNotNull().joinToString(" · ")
                if (tapeStr.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Text(
                            text = tapeStr,
                            fontSize = 13.sp,
                            color = ledger.textSecondary
                        )
                    }
                }
            }

            // 96dp artwork tile on the RIGHT
            val artUrl = show.albumCoverUrl ?: show.coverArtUrls?.medium
            if (!artUrl.isNullOrEmpty()) {
                ShowArtwork(
                    artUrl = artUrl,
                    artistName = PHISH.name,
                    date = show.date,
                    venue = show.venueName,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                CoverArtPlaceholder(
                    modifier = Modifier.size(96.dp),
                    cornerRadius = 12.dp
                )
            }
        }

        // Action pills row (36dp height, 18dp radius)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Resume / Play pill
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, ledger.accentIcon, RoundedCornerShape(18.dp))
                    .background(Color(0x299184D9))
                    .clickable(enabled = onResume != null) { onResume?.invoke() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = ledger.accentTintText,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (hasProgress) "Resume" else "Play",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = ledger.accentTintText
                )
            }

            // Saved pill
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, if (isSaved) ledger.accentIcon else ledger.controlOutline, RoundedCornerShape(18.dp))
                    .background(if (isSaved) Color(0x299184D9) else Color.Transparent)
                    .clickable(enabled = onSave != null) { onSave?.invoke() }
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = null,
                    tint = if (isSaved) ledger.accentTintText else ledger.textSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (isSaved) "Saved" else "Save",
                    fontSize = 13.sp,
                    fontWeight = if (isSaved) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSaved) ledger.accentTintText else ledger.textSecondary
                )
            }

            // Add pill
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, ledger.controlOutline, RoundedCornerShape(18.dp))
                    .clickable(enabled = onAdd != null) { onAdd?.invoke() }
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null,
                    tint = ledger.textSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Add",
                    fontSize = 13.sp,
                    color = ledger.textSecondary
                )
            }

            if (show.externalReleasePlatform != null && show.externalReleaseUrl != null) {
                runCatching {
                    val platform = ExternalReleasePlatform.valueOf(show.externalReleasePlatform.uppercase())
                    ExternalRelease(platform, show.externalReleaseUrl)
                }.getOrNull()?.let { release ->
                    ExternalReleasePill(release)
                }
            }

            // Show Like pill (#431) — phish.in's server-side show like, matching macOS's
            // ShowLikeButton. A DTO with no id can't be liked, the same `id == 0` gate macOS uses.
            if (show.id != 0L) {
                LikeButton(
                    type = Likable.Show,
                    id = show.id,
                    initiallyLiked = show.likedByUser,
                    initialCount = show.likesCount,
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, ledger.controlOutline, RoundedCornerShape(18.dp))
                )
            }

            Spacer(Modifier.weight(1f))
            ShareButton(showShareText(PHISH, show.date))
        }

        if (show.tags.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(show.tags.sortedByDescending { it.priority }) { tag ->
                    TagBadge(tag.toTagRef())
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, number: Int, date: String, artUrl: String?, vm: PlayerViewModel, onClick: () -> Unit) {
    val ledger = LocalLedgerColors.current
    val playerState by vm.state.collectAsState()
    val isPlaying = playerState.isPlaying && playerState.showDate == date && playerState.trackIndex == number - 1
    val isCurrent = playerState.showDate == date && playerState.trackIndex == number - 1

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (isCurrent) Modifier.background(Color(0x249184D9))
                    else Modifier
                )
                .clickable(onClick = onClick)
                .padding(horizontal = if (isCurrent) 12.dp else 0.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$number",
                color = if (isCurrent) ledger.accentIcon else ledger.textSubtle,
                fontSize = 13.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    track.title,
                    fontSize = 15.sp,
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    color = if (isCurrent) ledger.textHeadline else ledger.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isPlaying) {
                    Text(
                        "PLAYING",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        color = ledger.accentIcon
                    )
                }
                if (track.tags.any { it.name.contains("jam", ignoreCase = true) }) {
                    Box(
                        modifier = Modifier
                            .border(1.dp, Color(0x73B5ABFC), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "JAM CHART",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                            color = ledger.accentTintText
                        )
                    }
                }
            }
            Text(
                fmt(track.duration),
                color = if (isCurrent) ledger.textSecondary else ledger.textSubtle,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.width(4.dp))
            LikeButton(Likable.Track, track.id, track.likedByUser, track.likesCount)
            AddToPlaylistButton(vm) {
                LocalPlaylistTrackEntity(
                    playlistId = "", position = 0, backend = Backend.PHISHIN.id,
                    trackId = track.id.toString(), showDate = date, title = track.title,
                    durationMs = track.duration, artUrl = artUrl,
                )
            }
        }
    }
}

/** Emits tracks in order, inserting a header row whenever the set changes. */
private fun androidx.compose.foundation.lazy.LazyListScope.tracksGroupedBySet(
    tracks: List<Track>,
    content: @Composable (Int, Track) -> Unit,
) {
    val setDurations = tracks.groupBy { it.setName }.mapValues { (_, setTracks) ->
        setTracks.sumOf { it.duration }
    }
    tracks.forEachIndexed { index, track ->
        val currentSetName = track.setName
        if (currentSetName.isNotEmpty() && (index == 0 || tracks[index - 1].setName != currentSetName)) {
            val durationMs = setDurations[currentSetName] ?: 0L
            item(key = "set-$currentSetName-$index") {
                SetHeader(currentSetName, durationMs)
            }
        }
        item(key = "track-${track.id}") { content(index, track) }
    }
}

@Composable
private fun SetHeader(setName: String, durationMs: Long) {
    val ledger = LocalLedgerColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp, bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = setName.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                color = ledger.textMuted
            )
            if (durationMs > 0) {
                Text(
                    text = formatCompactDuration(durationMs),
                    fontSize = 12.sp,
                    color = ledger.textSubtle
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        GradientHairline(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The backend-neutral form of [tracksGroupedBySet], reused for [PlayableTrack] by
 * [RecordingScreen] — a Relisten tape without real sets (D-verified: `features.sets`) maps
 * every track to an empty [PlayableTrack.setName] (P3), which collapses to one group with no
 * header rather than one meaningless "Set" divider.
 */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.groupedBySet(
    items: List<T>,
    setName: (T) -> String,
    key: (T) -> Any,
    durationMs: ((T) -> Long)? = null,
    content: @Composable (Int, T) -> Unit,
) {
    val setDurations = if (durationMs != null) {
        items.groupBy { setName(it) }.mapValues { (_, groupItems) ->
            groupItems.sumOf { durationMs(it) }
        }
    } else emptyMap()

    items.forEachIndexed { index, item ->
        val currentSetName = setName(item)
        if (currentSetName.isNotEmpty() && (index == 0 || setName(items[index - 1]) != currentSetName)) {
            val dMs = setDurations[currentSetName] ?: 0L
            item(key = "set-$currentSetName-$index") { SetHeader(currentSetName, dMs) }
        }
        item(key = "track-${key(item)}") { content(index, item) }
    }
}

@Composable
private fun ExternalReleasePill(release: ExternalRelease) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ledger = LocalLedgerColors.current
    
    val platformName = when (release.platform) {
        ExternalReleasePlatform.SPOTIFY -> "Spotify"
        ExternalReleasePlatform.TIDAL -> "Tidal"
    }
    // Heuristic matches show a suffix so users know this is an automated match, not curated.
    val label = if (release.isHeuristic) "$platformName · Auto-matched" else platformName

    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, ledger.controlOutline, RoundedCornerShape(18.dp))
            .clickable {
                val intent = ExternalReleaseHelper.buildIntent(release)
                try {
                    context.startActivity(intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ExternalReleaseHelper.getWebUrl(release))))
                }
            }
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            if (release.isHeuristic) Icons.Default.Star else Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = ledger.textSecondary,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            fontSize = 13.sp,
            color = ledger.textSecondary
        )
    }
}
