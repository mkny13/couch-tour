package dev.mike.phishin

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Without this the media notification (and therefore the lockscreen controls)
        // is silently suppressed on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App(vm: PlayerViewModel = viewModel()) {
    val nav = rememberNavController()
    val state by vm.state.collectAsState()

    // Player events don't fire while a track simply advances, so tick the scrubber.
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            delay(500)
            vm.refresh()
        }
    }

    Scaffold(
        bottomBar = { if (state.hasQueue) MiniPlayer(state, vm) }
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(vm, nav) }
            composable("archive") { ArchiveScreen(vm, nav) }
            composable("login") { LoginScreen(nav) }
            composable("shows/{period}") { entry ->
                ShowsScreen(entry.arguments?.getString("period").orEmpty(), nav)
            }
            composable("show/{date}") { entry ->
                ShowScreen(entry.arguments?.getString("date").orEmpty(), vm, nav)
            }
            composable("playlists") {
                PlaylistsScreen("Playlists", nav) { PhishInApi.playlists() }
            }
            composable("playlist/{slug}") { entry ->
                PlaylistScreen(entry.arguments?.getString("slug").orEmpty(), vm, nav)
            }
            composable("mine/playlists") {
                PlaylistsScreen("My playlists", nav) {
                    // "mine" and "liked" are separate filters; the page shows both together.
                    (PhishInApi.playlists(filter = "mine") + PhishInApi.playlists(filter = "liked"))
                        .distinctBy { it.slug }
                }
            }
            composable("mine/shows") { MyShowsScreen(nav) }
            composable("mine/tracks") { MyTracksScreen(vm, nav) }
        }
    }
}

// ---------------------------------------------------------------- screens

@Composable
fun HomeScreen(vm: PlayerViewModel, nav: NavHostController) {
    val years = loadOnce { PhishInApi.years() }
    val recent by vm.progressDao.inProgress().collectAsState(initial = emptyList())
    val archived by vm.progressDao.archived().collectAsState(initial = emptyList())
    val username by Session.username.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val term = query.trim()
    val results = searchFor(term)

    Column(Modifier.fillMaxSize()) {
        Text(
            "Phish.in",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Songs, venues, dates…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )

        if (term.length >= 3) {
            SearchResultsList(results.value, vm, nav)
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (recent.isNotEmpty()) {
                item { SectionHeader("Continue listening") }
                item {
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(recent, key = { it.queueKey }) { p -> ResumeCard(p, vm) }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            if (archived.isNotEmpty()) {
                item {
                    RowItem(
                        title = "Archive",
                        subtitle = "${archived.size} finished ${plural(archived.size, "show")}",
                        artUrl = archived.first().artUrl,
                        onClick = { nav.navigate("archive") }
                    )
                }
            }

            item { SectionHeader("Your phish.in") }
            if (username == null) {
                item {
                    RowItem(
                        title = "Log in",
                        subtitle = "See your saved shows, tracks, and playlists",
                        artUrl = null,
                        onClick = { nav.navigate("login") }
                    )
                }
            } else {
                item {
                    RowItem("My shows", "Shows you've liked", null) { nav.navigate("mine/shows") }
                }
                item {
                    RowItem("My tracks", "Tracks you've liked", null) { nav.navigate("mine/tracks") }
                }
                item {
                    RowItem("My playlists", "Created by you and liked", null) {
                        nav.navigate("mine/playlists")
                    }
                }
                item {
                    RowItem("Signed in as $username", "Tap to log out", null) { Session.logout() }
                }
            }

            item {
                RowItem("Browse playlists", "Public playlists on phish.in", null) {
                    nav.navigate("playlists")
                }
            }

            item { SectionHeader("Browse by year") }

            when (val r = years.value) {
                null -> item { Loading() }
                else -> r.fold(
                    onSuccess = { periods ->
                        // Newest first.
                        items(periods.reversed(), key = { it.period }) { p ->
                            RowItem(
                                title = p.period,
                                subtitle = "${p.showsWithAudioCount} shows with audio",
                                artUrl = p.coverArtUrls?.small,
                                onClick = { nav.navigate("shows/${p.period}") }
                            )
                        }
                    },
                    onFailure = { item { ErrorText(it) } }
                )
            }
        }
    }
}

@Composable
fun ShowsScreen(period: String, nav: NavHostController) {
    val shows = loadOnce(period) { PhishInApi.showsForPeriod(period) }

    Column(Modifier.fillMaxSize()) {
        Header(period, nav)
        when (val r = shows.value) {
            null -> Loading()
            else -> r.fold(
                onSuccess = { list ->
                    LazyColumn {
                        items(list, key = { it.date }) { show ->
                            RowItem(
                                title = show.date,
                                subtitle = listOfNotNull(show.venueName, show.location)
                                    .joinToString(" · "),
                                artUrl = show.coverArtUrls?.small,
                                trailing = if (show.audioStatus == "partial") "partial" else null,
                                onClick = { nav.navigate("show/${show.date}") }
                            )
                        }
                    }
                },
                onFailure = { ErrorText(it) }
            )
        }
    }
}

@Composable
fun ShowScreen(date: String, vm: PlayerViewModel, nav: NavHostController) {
    val show = loadOnce(date) { PhishInApi.show(date) }
    val saved = loadOnce(date) { vm.progressFor(showQueueKey(date)) }

    Column(Modifier.fillMaxSize()) {
        Header(date, nav)
        when (val r = show.value) {
            null -> Loading()
            else -> r.fold(
                onSuccess = { s ->
                    val playable = s.tracks.filter { it.playable }
                    // A finished show's stored position is the end of the encore, so
                    // offering to resume it would just stop again immediately.
                    val progress = saved.value?.getOrNull()?.takeIf { !it.finished }
                    LazyColumn {
                        item { ShowHeader(s, playable.size) }
                        if (progress != null) {
                            item {
                                ResumeBanner(progress) {
                                    vm.playShow(s, progress.trackIndex, progress.positionMs)
                                }
                            }
                        }
                        tracksGroupedBySet(playable) { index, track ->
                            TrackRow(track, index + 1) { vm.playShow(s, index, 0) }
                        }
                    }
                },
                onFailure = { ErrorText(it) }
            )
        }
    }
}

@Composable
private fun SearchResultsList(
    results: Result<SearchResults>?,
    vm: PlayerViewModel,
    nav: NavHostController,
) {
    when {
        results == null -> Loading()
        results.isFailure -> ErrorText(results.exceptionOrNull()!!)
        else -> {
            val r = results.getOrThrow()
            if (r.isEmpty) {
                Text("No shows or tracks matched.", color = Color.Gray, modifier = Modifier.padding(16.dp))
                return
            }
            LazyColumn {
                if (r.shows.isNotEmpty()) {
                    item { SectionHeader("Shows") }
                    items(r.shows, key = { "show-${it.date}" }) { show ->
                        RowItem(
                            title = show.date,
                            subtitle = listOfNotNull(show.venueName, show.location)
                                .joinToString(" · "),
                            artUrl = show.coverArtUrls?.small,
                            onClick = { nav.navigate("show/${show.date}") }
                        )
                    }
                }
                if (r.playlists.isNotEmpty()) {
                    item { SectionHeader("Playlists") }
                    items(r.playlists, key = { "pl-${it.slug}" }) { PlaylistRow(it, nav) }
                }
                if (r.tracks.isNotEmpty()) {
                    item { SectionHeader("Tracks") }
                    items(r.tracks, key = { "track-${it.id}" }) { track ->
                        RowItem(
                            title = track.title,
                            subtitle = listOfNotNull(
                                track.showDate, track.venueName, track.venueLocation
                            ).joinToString(" · "),
                            artUrl = null,
                            trailing = fmt(track.duration),
                            onClick = { vm.playTrack(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoginScreen(nav: NavHostController) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Header("Log in", nav)
        Text(
            "Your phish.in account. The password is sent once to get a token and is never " +
                "stored; only the token is kept, encrypted on this device.",
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; error = null },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Button(
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { Session.login(email.trim(), password) }
                        .onSuccess { nav.popBackStack() }
                        .onFailure {
                            error = if (it is ApiException && it.unauthorized) {
                                "Email or password not recognised."
                            } else {
                                "Couldn't log in: ${it.message}"
                            }
                            busy = false
                        }
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) { Text(if (busy) "Logging in…" else "Log in") }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
fun PlaylistsScreen(title: String, nav: NavHostController, load: suspend () -> List<Playlist>) {
    val data = loadOnce(title) { load() }
    Column(Modifier.fillMaxSize()) {
        Header(title, nav)
        when (val r = data.value) {
            null -> Loading()
            else -> r.fold(
                onSuccess = { lists ->
                    if (lists.isEmpty()) {
                        Text("Nothing here yet.", color = Color.Gray, modifier = Modifier.padding(16.dp))
                    } else {
                        LazyColumn { items(lists, key = { it.slug }) { PlaylistRow(it, nav) } }
                    }
                },
                onFailure = { ErrorText(it) }
            )
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, nav: NavHostController) {
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

    Column(Modifier.fillMaxSize()) {
        Header("Playlist", nav)
        when (val r = data.value) {
            null -> Loading()
            else -> r.fold(
                onSuccess = { pl ->
                    val entries = pl.entries.filter { it.track.playable }
                    val progress = saved.value?.getOrNull()?.takeIf { !it.finished }
                    LazyColumn {
                        item {
                            Column(Modifier.padding(16.dp)) {
                                Text(pl.name, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                                Text(
                                    listOfNotNull(
                                        pl.username?.let { "by $it" },
                                        "${entries.size} tracks",
                                        fmt(pl.duration),
                                    ).joinToString(" · "),
                                    color = Color.Gray, fontSize = 13.sp
                                )
                                pl.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = Color.Gray, fontSize = 13.sp,
                                        modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                        if (progress != null) {
                            item {
                                ResumeBanner(progress) {
                                    vm.playPlaylist(pl, progress.trackIndex, progress.positionMs)
                                }
                            }
                        }
                        itemsIndexed(entries, key = { _, e -> "e-${e.position}-${e.track.id}" }) { i, e ->
                            RowItem(
                                title = e.track.title,
                                subtitle = listOfNotNull(
                                    e.track.showDate, e.track.venueName
                                ).joinToString(" · "),
                                artUrl = e.track.showAlbumCoverUrl,
                                trailing = fmt(e.duration),
                                onClick = { vm.playPlaylist(pl, i, 0) }
                            )
                        }
                    }
                },
                onFailure = { ErrorText(it) }
            )
        }
    }
}

@Composable
fun MyShowsScreen(nav: NavHostController) {
    val data = loadOnce("my-shows") { PhishInApi.likedShows() }
    Column(Modifier.fillMaxSize()) {
        Header("My shows", nav)
        when (val r = data.value) {
            null -> Loading()
            else -> r.fold(
                onSuccess = { shows ->
                    if (shows.isEmpty()) {
                        Text(
                            "No liked shows yet. Like them on phish.in and they'll appear here.",
                            color = Color.Gray, modifier = Modifier.padding(16.dp)
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
                },
                onFailure = { ErrorText(it) }
            )
        }
    }
}

@Composable
fun MyTracksScreen(vm: PlayerViewModel, nav: NavHostController) {
    val data = loadOnce("my-tracks") { PhishInApi.likedTracks() }
    Column(Modifier.fillMaxSize()) {
        Header("My tracks", nav)
        when (val r = data.value) {
            null -> Loading()
            else -> r.fold(
                onSuccess = { tracks ->
                    if (tracks.isEmpty()) {
                        Text(
                            "No liked tracks yet. Like them on phish.in and they'll appear here.",
                            color = Color.Gray, modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        LazyColumn {
                            items(tracks, key = { it.id }) { track ->
                                RowItem(
                                    title = track.title,
                                    subtitle = listOfNotNull(
                                        track.showDate, track.venueName, track.venueLocation
                                    ).joinToString(" · "),
                                    artUrl = track.showAlbumCoverUrl,
                                    trailing = fmt(track.duration),
                                    onClick = { vm.playTrack(track) }
                                )
                            }
                        }
                    }
                },
                onFailure = { ErrorText(it) }
            )
        }
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun ShowHeader(show: Show, trackCount: Int) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = show.albumCoverUrl ?: show.coverArtUrls?.medium,
            contentDescription = null,
            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(show.venueName.orEmpty(), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(show.location.orEmpty(), color = Color.Gray, fontSize = 14.sp)
            Text("$trackCount tracks · ${fmt(show.duration)}", color = Color.Gray, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ResumeBanner(progress: Progress, onResume: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onResume)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.PlayArrow, null)
        Spacer(Modifier.width(10.dp))
        Text("Resume “${progress.trackTitle}” at ${fmt(progress.positionMs)}", fontSize = 14.sp)
    }
}

@Composable
private fun ResumeCard(progress: Progress, vm: PlayerViewModel) {
    Column(
        Modifier.width(132.dp).clickable { vm.resume(progress) }
    ) {
        Box {
            AsyncImage(
                model = progress.artUrl,
                contentDescription = null,
                modifier = Modifier.size(132.dp).clip(RoundedCornerShape(8.dp))
            )
            // Manual removal: drops the show from the list without playing it.
            // A plain clickable Box rather than IconButton — IconButton enforces a 48dp
            // minimum touch target that spills outside the 132dp artwork and over the
            // neighbouring card.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { vm.forget(progress) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    "Remove ${progress.title} from Continue listening",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(progress.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(progress.trackTitle, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
    }
}

@Composable
fun ArchiveScreen(vm: PlayerViewModel, nav: NavHostController) {
    val archived by vm.progressDao.archived().collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        Header("Archive", nav)
        if (archived.isEmpty()) {
            Text(
                "Shows you play all the way through land here.",
                color = Color.Gray,
                modifier = Modifier.padding(16.dp)
            )
            return
        }
        LazyColumn {
            items(archived, key = { it.queueKey }) { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        RowItem(
                            title = p.title,
                            subtitle = p.subtitle,
                            artUrl = p.artUrl,
                            trailing = "finished",
                            onClick = { nav.navigate("show/${p.queueKey.removePrefix("show:")}") }
                        )
                    }
                    IconButton(onClick = { vm.forget(p) }) {
                        Icon(Icons.Default.Close, "Remove ${p.title} from archive", tint = Color.Gray)
                    }
                }
            }
        }
    }
}

private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"

@Composable
private fun TrackRow(track: Track, number: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$number", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        // The set is already the section header above; repeating it per row is noise.
        Text(track.title, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(fmt(track.duration), color = Color.Gray, fontSize = 13.sp)
    }
}

@Composable
private fun MiniPlayer(state: PlayerState, vm: PlayerViewModel) {
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = state.artUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(state.trackTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(state.queueTitle, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
            }
            IconButton(onClick = { vm.previous() }) { Icon(Icons.Default.SkipPrevious, "Previous") }
            IconButton(onClick = { vm.togglePlayPause() }) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.isPlaying) "Pause" else "Play"
                )
            }
            IconButton(onClick = { vm.next() }) { Icon(Icons.Default.SkipNext, "Next") }
        }
        if (state.durationMs > 0) {
            WaveformScrubber(
                waveformUrl = state.waveformUrl,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                playedColor = MaterialTheme.colorScheme.primary,
                unplayedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                onSeek = { vm.seekTo(it) },
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(fmt(state.positionMs), fontSize = 11.sp, color = Color.Gray)
                Text(fmt(state.durationMs), fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun Header(title: String, nav: NavHostController) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = Color.Gray,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun RowItem(
    title: String,
    subtitle: String,
    artUrl: String?,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rows without artwork (playlists, account actions) shouldn't reserve the slot.
        if (artUrl != null) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 13.sp, color = Color.Gray, maxLines = 1)
        }
        if (trailing != null) Text(trailing, fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorText(t: Throwable) {
    Text(
        "Couldn't load: ${t.message}",
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(16.dp)
    )
}

// ---------------------------------------------------------------- helpers

/**
 * Debounced search. produceState cancels the previous coroutine whenever [term] changes,
 * so the delay collapses a burst of keystrokes into one request.
 */
@Composable
private fun searchFor(term: String): State<Result<SearchResults>?> =
    produceState<Result<SearchResults>?>(initialValue = null, key1 = term) {
        if (term.length < 3) {
            value = null
            return@produceState
        }
        value = null
        delay(300)
        value = runCatching { PhishInApi.search(term) }
    }

/** Runs [block] once per [key], exposing null while in flight. */
@Composable
private fun <T> loadOnce(key: Any = Unit, block: suspend () -> T): State<Result<T>?> =
    produceState<Result<T>?>(initialValue = null, key1 = key) {
        value = runCatching { block() }
    }

/** Emits tracks in order, inserting a header row whenever the set changes. */
private fun androidx.compose.foundation.lazy.LazyListScope.tracksGroupedBySet(
    tracks: List<Track>,
    content: @Composable (Int, Track) -> Unit,
) {
    tracks.forEachIndexed { index, track ->
        if (index == 0 || tracks[index - 1].setName != track.setName) {
            item(key = "set-${track.setName}-$index") { SectionHeader(track.setName) }
        }
        item(key = "track-${track.id}") { content(index, track) }
    }
}

fun fmt(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
