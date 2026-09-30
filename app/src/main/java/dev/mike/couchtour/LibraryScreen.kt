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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    var sortMode by rememberSaveable { mutableStateOf(LibrarySortMode.RECENTLY_ADDED) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }

    val rawPlaylists by vm.localPlaylistDao.playlists().collectAsState(initial = emptyList())
    val rawShows by SavedShows.keys.collectAsState()
    val rawTracks by vm.localPlaylistDao.allTracks().collectAsState(initial = emptyList())
    val historyCount by vm.progressDao.historyCount().collectAsState(initial = 0)

    val queryTrimmed = searchQuery.trim()

    val playlistItems = remember(rawPlaylists) { playlistItems(rawPlaylists) }
    val showItems = remember(rawShows) { savedShowItems(rawShows) }
    val trackItems = remember(rawTracks) { trackItems(rawTracks) }

    val filteredPlaylists = remember(playlistItems, queryTrimmed, sortMode) {
        sortLibraryItems(filterLibraryItems(playlistItems, queryTrimmed), sortMode)
    }

    val filteredShows = remember(showItems, queryTrimmed, sortMode) {
        sortLibraryItems(filterLibraryItems(showItems, queryTrimmed), sortMode)
    }

    val filteredTracks = remember(trackItems, queryTrimmed, sortMode) {
        sortLibraryItems(filterLibraryItems(trackItems, queryTrimmed), sortMode)
    }

    val playlistCount = filteredPlaylists.size
    val showCount = filteredShows.size
    val trackCount = filteredTracks.size
    val totalCount = playlistCount + showCount + trackCount

    LaunchedEffect(Unit) {
        recordLibraryCounts(
            vm.localPlaylistDao.playlists(),
            SavedShows.keys,
            vm.localPlaylistDao.allTracks()
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ledger.appBackground)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
            FeedbackButton(
                nav = nav,
                modifier = Modifier.size(36.dp),
                iconSize = 20.dp,
                tint = ledger.textMuted
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

        // When Shows tab is empty and history is not, provide navigation to History
        if (selectedFilter == LibraryFilter.SHOWS && showCount == 0 && historyCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { nav.navigate("history") }
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Shows you have played are in History",
                    fontSize = 13.sp,
                    color = ledger.accentIcon,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Action row: Sort and "New playlist"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
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
                        .clickable { sortMenuOpen = true }
                        .padding(horizontal = 11.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = sortMode.label,
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

                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false }
                ) {
                    LibrarySortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                sortMode = mode
                                sortMenuOpen = false
                            }
                        )
                    }
                }
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
                items(filteredPlaylists, key = { it.key }) { item ->
                    LibraryRowItem(
                        badgeType = item.badge,
                        title = item.title,
                        subtitle = item.subtitle,
                        trailingAction = {
                            CircularPlayButton(
                                isPlaying = false,
                                onClick = {
                                    if (item.target is LibraryTarget.LocalPlaylist) {
                                        nav.navigate("local-playlist/${item.target.id}")
                                    }
                                },
                                size = 30.dp,
                                iconSize = 14.dp
                            )
                        },
                        onClick = {
                            if (item.target is LibraryTarget.LocalPlaylist) {
                                nav.navigate("local-playlist/${item.target.id}")
                            }
                        }
                    )
                }
            }

            // Shows
            if (selectedFilter == LibraryFilter.ALL || selectedFilter == LibraryFilter.SHOWS) {
                items(filteredShows, key = { it.key }) { item ->
                    var menuOpen by remember { mutableStateOf(false) }
                    val openShow = {
                        when (val target = item.target) {
                            is LibraryTarget.Show -> nav.navigate("show/${target.date}")
                            is LibraryTarget.Recording -> {
                                val key = item.rawKey ?: "relisten:${target.id.id}"
                                openQueueKey(key, nav)
                            }
                            is LibraryTarget.LocalPlaylist -> nav.navigate("local-playlist/${target.id}")
                        }
                    }
                    Box {
                        LibraryRowItem(
                            badgeType = item.badge,
                            title = item.title,
                            subtitle = item.subtitle,
                            trailingText = null,
                            onClick = openShow,
                            onLongClick = { menuOpen = true }
                        )
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open show") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    openShow()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove from Library") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    item.rawKey?.let { SavedShows.toggle(it) }
                                }
                            )
                        }
                    }
                }
            }

            // Tracks
            if (selectedFilter == LibraryFilter.ALL || selectedFilter == LibraryFilter.TRACKS) {
                items(filteredTracks, key = { it.key }) { item ->
                    LibraryRowItem(
                        badgeType = item.badge,
                        title = item.title,
                        subtitle = item.subtitle,
                        trailingText = item.trailingText,
                        onClick = {
                            if (item.target is LibraryTarget.LocalPlaylist) {
                                nav.navigate("local-playlist/${item.target.id}")
                            }
                        }
                    )
                }
            }

            // Empty state per tab
            val isEmpty = when (selectedFilter) {
                LibraryFilter.ALL -> totalCount == 0
                LibraryFilter.PLAYLISTS -> playlistCount == 0
                LibraryFilter.SHOWS -> showCount == 0
                LibraryFilter.TRACKS -> trackCount == 0
            }
            if (isEmpty) {
                item {
                    val emptyMessage = when {
                        queryTrimmed.isNotEmpty() -> "No results matching \"$queryTrimmed\"."
                        selectedFilter == LibraryFilter.PLAYLISTS -> "Create a playlist to see it here."
                        selectedFilter == LibraryFilter.SHOWS -> "Save a show from its page to see it here."
                        selectedFilter == LibraryFilter.TRACKS -> "Add tracks to playlists to see them here."
                        else -> "Your library is empty. Save a show from its page, add tracks to playlists, or create playlists to see them here."
                    }
                    Text(
                        text = emptyMessage,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRowItem(
    badgeType: String,
    title: String,
    subtitle: String,
    trailingText: String? = null,
    trailingTextColor: Color = LocalLedgerColors.current.textSecondary,
    trailingAction: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val ledger = LocalLedgerColors.current
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
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

internal suspend fun recordLibraryCounts(
    playlistsFlow: Flow<Collection<*>>,
    showsFlow: Flow<Collection<*>>,
    tracksFlow: Flow<Collection<*>>
) {
    combine(playlistsFlow, showsFlow, tracksFlow) { playlists, shows, tracks ->
        Triple(playlists.size, shows.size, tracks.size)
    }.first().let { (playlistCount, showCount, trackCount) ->
        emitLibraryCounts(playlistCount, showCount, trackCount)
    }
}

internal fun emitLibraryCounts(playlistCount: Int, showCount: Int, trackCount: Int) {
    val totalCount = playlistCount + showCount + trackCount
    DiagnosticsLog.log(
        "library.counts",
        "playlists" to playlistCount,
        "shows" to showCount,
        "tracks" to trackCount,
        "total" to totalCount
    )
    DiagnosticsLog.mark(
        "Library counts",
        "playlists=$playlistCount shows=$showCount tracks=$trackCount total=$totalCount"
    )
}
