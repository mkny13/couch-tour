package dev.mike.couchtour

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

@Composable
private fun RenamePlaylistDialog(currentName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && name.trim() != currentName, onClick = { onRename(name.trim()) }) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PlaylistsScreen(title: String, nav: NavHostController, load: suspend () -> List<Playlist>) {
    val data = loadOnce(title) { load() }
    Column(Modifier.fillMaxSize()) {
        Header(title, nav)
        Loaded(data.value) { lists ->
            if (lists.isEmpty()) {
                Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn { items(lists, key = { it.slug }) { PlaylistRow(it, nav) } }
            }
        }
    }
}

@Composable
internal fun PlaylistRow(playlist: Playlist, nav: NavHostController) {
    RowItem(
        title = playlist.name,
        subtitle = listOfNotNull(
            playlist.username?.let { "by $it" },
            "${playlist.tracksCount} ${plural(playlist.tracksCount, "track")}",
        ).joinToString(" · "),
        artUrl = null,
        trailing = fmt(playlist.duration),
        onClick = { nav.navigate("playlist/${playlist.slug}") }
    )
}

@Composable
fun PlaylistScreen(slug: String, vm: PlayerViewModel, nav: NavHostController) {
    val data = loadOnce(slug) { PhishInApi.playlist(slug) }
    val saved = loadOnce(slug) { vm.progressFor(playlistQueueKey(slug)) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Header("Playlist", nav)
        Loaded(data.value) { pl ->
            val entries = pl.entries.filter { it.track.playable }
            val filtered = entries.filterByTitleIndexed(query)
            val progress = saved.value?.getOrNull()?.takeIf { !it.finished }
            LazyColumn {
                item {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text(
                            listOfNotNull(
                                pl.username?.let { "by $it" },
                                "${entries.size} tracks",
                                fmt(pl.duration),
                            ).joinToString(" · "),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                            )
                            pl.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        LikeButton(Likable.Playlist, pl.id, pl.likedByUser, pl.likesCount)
                    }
                }
                if (entries.size > 1) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Search this playlist…") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                if (progress != null) {
                    item {
                        ResumeBanner(progress) {
                            vm.playPlaylist(pl, progress.trackIndex, progress.positionMs)
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "No tracks match \"$query\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(filtered, key = { (_, e) -> "e-${e.position}-${e.track.id}" }) { (i, e) ->
                    RowItem(
                        title = e.track.title,
                        subtitle = listOfNotNull(
                            e.track.showDate, e.track.venueName
                        ).joinToString(" · "),
                        artUrl = e.track.showAlbumCoverUrl,
                        trailing = fmt(e.duration),
                        trailingContent = {
                            LikeButton(
                                Likable.Track, e.track.id,
                                e.track.likedByUser, e.track.likesCount,
                            )
                        },
                        onClick = { vm.playPlaylist(pl, i, 0) }
                    )
                }
            }
        }
    }
}

/**
 * Local playlists spanning both backends (#12) — account-free, unlike phish.in's own
 * [PlaylistsScreen]/[PlaylistScreen], which is why this is a separate screen pair rather than
 * a third filter on those (D161).
 */
@Composable
fun LocalPlaylistsScreen(vm: PlayerViewModel, nav: NavHostController) {
    val playlists by vm.localPlaylistDao.playlists().collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Header("Local playlists", nav)
        Button(
            onClick = { creating = true },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New playlist")
        }
        if (playlists.isEmpty()) {
            Text(
                "No playlists yet. Add a track to one from its playlist button.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn {
                items(playlists, key = { it.id }) { playlist ->
                    RowItem(
                        title = playlist.name,
                        subtitle = "${playlist.trackCount} ${plural(playlist.trackCount, "track")}",
                        artUrl = null,
                        onClick = { nav.navigate("local-playlist/${playlist.id}") },
                    )
                }
            }
        }
    }
    if (creating) {
        NewPlaylistDialog(
            onDismiss = { creating = false },
            onCreate = { name ->
                creating = false
                scope.launch {
                    val id = vm.createLocalPlaylist(name)
                    nav.navigate("local-playlist/$id")
                }
            },
        )
    }
}

@Composable
fun LocalPlaylistScreen(id: String, vm: PlayerViewModel, nav: NavHostController) {
    val playlists by vm.localPlaylistDao.playlists().collectAsState(initial = emptyList())
    val playlist = playlists.firstOrNull { it.id == id }
    val tracks by vm.localPlaylistDao.tracks(id).collectAsState(initial = emptyList())
    val saved = loadOnce(id) { vm.progressFor(localPlaylistQueueKey(id)) }
    val progress = saved.value?.getOrNull()?.takeIf { !it.finished }
    var renaming by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // Reordering writes positions computed against `tracks`' full indices — doing that while
    // a filter narrows what's on screen would scramble the playlist (#90), so reordering is
    // simply unavailable until the filter is cleared, rather than silently acting on the
    // wrong rows or clearing the user's search out from under them.
    val reorderable = query.isBlank()

    Column(Modifier.fillMaxSize()) {
        Header(playlist?.name ?: "Playlist", nav)
        if (playlist == null) {
            Loading()
        } else {
            val filtered = tracks.filterByTitleIndexed(query)
            LazyColumn {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${tracks.size} ${plural(tracks.size, "track")}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Default.Edit, "Rename playlist")
                        }
                        IconButton(onClick = { vm.deleteLocalPlaylist(id); nav.popBackStack() }) {
                            Icon(Icons.Default.Delete, "Delete playlist")
                        }
                    }
                }
                if (tracks.size > 1) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Search this playlist…") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                if (progress != null) {
                    item {
                        ResumeBanner(progress) {
                            vm.playLocalPlaylist(id, progress.trackIndex, progress.positionMs)
                        }
                    }
                }
                if (tracks.isEmpty()) {
                    item {
                        Text(
                            "No tracks yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else if (filtered.isEmpty()) {
                    item {
                        Text(
                            "No tracks match \"$query\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    items(filtered, key = { (_, t) -> t.rowId }) { (i, t) ->
                        RowItem(
                            title = t.title,
                            subtitle = listOfNotNull(t.showDate, t.venueName).joinToString(" · "),
                            artUrl = t.artUrl,
                            trailing = fmt(t.durationMs),
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        enabled = reorderable && i > 0,
                                        onClick = { vm.moveLocalPlaylistTrack(id, tracks, i, i - 1) },
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowUp,
                                            "Move up",
                                            tint = if (reorderable && i > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                        )
                                    }
                                    IconButton(
                                        enabled = reorderable && i < tracks.lastIndex,
                                        onClick = { vm.moveLocalPlaylistTrack(id, tracks, i, i + 1) },
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            "Move down",
                                            tint = if (reorderable && i < tracks.lastIndex) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                        )
                                    }
                                    IconButton(onClick = { vm.removeFromLocalPlaylist(t.rowId, id) }) {
                                        Icon(Icons.Default.Close, "Remove from playlist")
                                    }
                                }
                            },
                            onClick = { vm.playLocalPlaylist(id, i, 0) },
                        )
                    }
                }
            }
        }
    }
    if (renaming && playlist != null) {
        RenamePlaylistDialog(
            currentName = playlist.name,
            onDismiss = { renaming = false },
            onRename = { newName ->
                renaming = false
                vm.renameLocalPlaylist(id, newName)
            },
        )
    }
}

@Composable
fun MyShowsScreen(nav: NavHostController) {
    val data = loadOnce("my-shows") { PhishInApi.likedShows() }
    Column(Modifier.fillMaxSize()) {
        Header("My shows", nav)
        Loaded(data.value) { shows ->
            if (shows.isEmpty()) {
                Text(
                    "No liked shows yet. Like them on phish.in and they'll appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn {
                    items(shows, key = { it.date }) { show ->
                        RowItem(
                            title = show.date,
                            subtitle = listOfNotNull(show.venueName, show.location)
                                .joinToString(" · "),
                            artUrl = show.coverArtUrls?.small,
                            onClick = { nav.navigate("show/${show.date}") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MyTracksScreen(vm: PlayerViewModel, nav: NavHostController) {
    val data = loadOnce("my-tracks") { PhishInApi.likedTracks() }
    Column(Modifier.fillMaxSize()) {
        Header("My tracks", nav)
        Loaded(data.value) { tracks ->
            if (tracks.isEmpty()) {
                Text(
                    "No liked tracks yet. Like them on phish.in and they'll appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)
                )
            } else {
                val playable = tracks.filter { it.playable }
                LazyColumn {
                    item {
                        Button(
                            onClick = { vm.shuffle(playable, "My tracks") },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Shuffle all ${playable.size}")
                        }
                    }
                    items(tracks, key = { it.id }) { track ->
                        RowItem(
                            title = track.title,
                            subtitle = listOfNotNull(
                                track.showDate, track.venueName, track.venueLocation
                            ).joinToString(" · "),
                            artUrl = track.showAlbumCoverUrl,
                            trailing = fmt(track.duration),
                            trailingContent = {
                                LikeButton(
                                    Likable.Track, track.id,
                                    track.likedByUser, track.likesCount,
                                )
                            },
                            // A single liked track plays inside its show; shuffle
                            // above plays the liked tracks themselves.
                            onClick = { vm.playTrack(track) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Everything ever played: still going, finished, or removed from "Continue listening" by
 * hand. Removing something from the home row hides it here rather than destroying it.
 */
@Composable
fun HistoryScreen(vm: PlayerViewModel, nav: NavHostController) {
    val history by vm.progressDao.history().collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        Header("History", nav)
        if (history.isEmpty()) {
            Text(
                "Shows and playlists you've played will appear here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            return
        }
        LazyColumn {
            items(history, key = { it.queueKey }) { p ->
                val displayTitle = historyDisplayTitle(p.title, p.queueKey)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        RowItem(
                            title = displayTitle,
                            subtitle = p.subtitle,
                            artUrl = p.artUrl,
                            artistName = p.artist,
                            date = displayTitle,
                            venue = p.subtitle,
                            onClick = { openQueue(p, nav) },
                            trailing = when {
                                p.finished -> "✓ completed"
                                p.dismissed -> "removed · ${fmt(p.positionMs)}"
                                else -> "at ${fmt(p.positionMs)}"
                            },
                            trailingSecondary = relativeTime(p.updatedAt),
                        )
                    }
                    IconButton(onClick = { vm.forget(p) }) {
                        Icon(
                            Icons.Default.Close,
                            "Delete $displayTitle from history",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

