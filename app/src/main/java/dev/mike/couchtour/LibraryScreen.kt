package dev.mike.couchtour

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

enum class LibraryFilter {
    ALL, PLAYLISTS, SHOWS, TRACKS
}

/**
 * Library screen from the Ledger design handoff:
 * Title, search field, filter chips (All, Playlists, Shows, Tracks), sort control, and
 * rows with fixed-width 44dp type badges (LIST, SHOW, TRACK).
 */
@Composable
fun LibraryScreen(vm: PlayerViewModel, nav: NavHostController) {
    val ledger = LocalLedgerColors.current
    val scope = rememberCoroutineScope()
    var selectedFilter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }

    val playlists by vm.localPlaylistDao.playlists().collectAsState(initial = emptyList())
    val inProgressList by vm.progressDao.inProgress().collectAsState(initial = emptyList())
    val finishedKeys by vm.progressDao.finishedKeys().collectAsState(initial = emptyList())

    val playlistCount = playlists.size
    val showCount = inProgressList.size + finishedKeys.size
    val trackCount = playlists.sumOf { it.trackCount }
    val totalCount = playlistCount + showCount + trackCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ledger.appBackground)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp)
        ) {
            Text(
                text = "YOUR LIBRARY",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
                color = ledger.textSubtle
            )
            Text(
                text = "Playlists, shows & tracks",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.01).sp,
                color = ledger.textPrimary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Search Input Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = {
                    Text("Search your library", fontSize = 14.sp, color = ledger.textSubtle)
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = ledger.textSubtle, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = ledger.textSubtle, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = ledger.cardSurface,
                    unfocusedContainerColor = ledger.cardSurface,
                    focusedTextColor = ledger.textPrimary,
                    unfocusedTextColor = ledger.textPrimary,
                    focusedIndicatorColor = ledger.accentBase,
                    unfocusedIndicatorColor = ledger.controlOutline,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            )
        }

        // Filter Chips Row: All, Playlists, Shows, Tracks
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LibraryChip(
                label = "All $totalCount",
                selected = selectedFilter == LibraryFilter.ALL,
                onClick = { selectedFilter = LibraryFilter.ALL }
            )
            LibraryChip(
                label = "Playlists $playlistCount",
                selected = selectedFilter == LibraryFilter.PLAYLISTS,
                onClick = { selectedFilter = LibraryFilter.PLAYLISTS }
            )
            LibraryChip(
                label = "Shows $showCount",
                selected = selectedFilter == LibraryFilter.SHOWS,
                onClick = { selectedFilter = LibraryFilter.SHOWS }
            )
            LibraryChip(
                label = "Tracks $trackCount",
                selected = selectedFilter == LibraryFilter.TRACKS,
                onClick = { selectedFilter = LibraryFilter.TRACKS }
            )
        }

        // Action row: Sort and "New playlist"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .border(
                        1.dp,
                        ledger.accentIcon,
                        RoundedCornerShape(14.dp)
                    )
                    .background(
                        if (ledger.isDark) Color(0x249184D9) else Color(0x1A6F62C7),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 11.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Recently added",
                    fontSize = 12.sp,
                    color = ledger.accentTintText
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = ledger.accentTintText,
                    modifier = Modifier.size(12.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { showNewPlaylistDialog = true }
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "New playlist",
                    fontSize = 12.sp,
                    color = ledger.accentIcon,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "New playlist",
                    tint = ledger.accentIcon,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        // Library Items List
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            // Playlists
            if (selectedFilter == LibraryFilter.ALL || selectedFilter == LibraryFilter.PLAYLISTS) {
                items(playlists, key = { "pl_${it.id}" }) { playlist ->
                    LibraryRowItem(
                        badgeType = "LIST",
                        title = playlist.name,
                        subtitle = "${playlist.trackCount} ${plural(playlist.trackCount, "track")}",
                        trailingAction = {
                            CircularPlayButton(
                                isPlaying = false,
                                onClick = { nav.navigate("local-playlist/${playlist.id}") },
                                size = 30.dp,
                                iconSize = 14.dp
                            )
                        },
                        onClick = { nav.navigate("local-playlist/${playlist.id}") }
                    )
                }
            }

            // Shows from in-progress & finished history
            if (selectedFilter == LibraryFilter.ALL || selectedFilter == LibraryFilter.SHOWS) {
                items(inProgressList, key = { "ip_${it.queueKey}" }) { item ->
                    val showDate = item.queueKey.removePrefix("show:").removePrefix("recording:relisten:")
                    LibraryRowItem(
                        badgeType = "SHOW",
                        title = showDate,
                        subtitle = item.title,
                        trailingText = "★ 4.5",
                        trailingTextColor = ledger.ratingAmber,
                        onClick = { openQueueKey(item.queueKey, nav) }
                    )
                }
            }

            // If empty
            if (playlists.isEmpty() && inProgressList.isEmpty()) {
                item {
                    Text(
                        text = "Your library is empty. Play shows, save tracks, or create playlists to see them here.",
                        fontSize = 14.sp,
                        color = ledger.textMuted,
                        modifier = Modifier.padding(vertical = 32.dp)
                    )
                }
            }
        }
    }

    if (showNewPlaylistDialog) {
        NewPlaylistDialog(
            onDismiss = { showNewPlaylistDialog = false },
            onCreate = { name ->
                showNewPlaylistDialog = false
                scope.launch { vm.createLocalPlaylist(name) }
            }
        )
    }
}

@Composable
private fun LibraryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val ledger = LocalLedgerColors.current
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                if (selected) {
                    if (ledger.isDark) Color(0x299184D9) else Color(0x1F6F62C7)
                } else Color.Transparent
            )
            .border(
                1.dp,
                if (selected) ledger.accentIcon else ledger.controlOutline,
                RoundedCornerShape(15.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) ledger.accentTintText else ledger.textSecondary
        )
    }
}

@Composable
private fun LibraryRowItem(
    badgeType: String,
    title: String,
    subtitle: String,
    trailingText: String? = null,
    trailingTextColor: Color = LocalLedgerColors.current.textSecondary,
    trailingAction: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val ledger = LocalLedgerColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TypeBadge(type = badgeType)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = ledger.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = ledger.textSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            if (trailingAction != null) {
                trailingAction()
            } else if (trailingText != null) {
                Text(
                    text = trailingText,
                    fontSize = 12.sp,
                    color = trailingTextColor
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ledger.listDivider)
        )
    }
}
