package dev.mike.phishin

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
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

    /** Set when launched from the media notification; consumed once the UI has navigated. */
    private val openNowPlaying = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode is singleTask, so a second tap re-enters through here, not onCreate.
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)) openNowPlaying.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openNowPlaying.value = intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true

        // Without this the media notification (and therefore the lockscreen controls)
        // is silently suppressed on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { App(openNowPlaying = openNowPlaying) }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "open_now_playing"
    }
}

@Composable
fun App(
    vm: PlayerViewModel = viewModel(),
    openNowPlaying: MutableState<Boolean> = remember { mutableStateOf(false) },
) {
    val nav = rememberNavController()
    val state by vm.state.collectAsState()

    // Arriving from the media notification. Waits for the queue key, which isn't known
    // until the MediaController has connected, then navigates once and clears the flag.
    LaunchedEffect(openNowPlaying.value, state.queueKey) {
        val key = state.queueKey
        if (openNowPlaying.value && key != null) {
            openQueueKey(key, nav)
            openNowPlaying.value = false
        }
    }

    // Player events don't fire while a track simply advances, so tick the scrubber.
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            delay(500)
            vm.refresh()
        }
    }

    Scaffold(
        bottomBar = { if (state.hasQueue) MiniPlayer(state, vm, nav) }
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(vm, nav) }
            composable("history") { HistoryScreen(vm, nav) }
            composable("login") { LoginScreen(nav) }
            composable("lastfm") { LastFmScreen(nav) }
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
    val historyCount by vm.progressDao.historyCount().collectAsState(initial = 0)
    val username by Session.username.collectAsState()
    val lastFmUser by LastFmSession.username.collectAsState()
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
                        items(recent, key = { it.queueKey }) { p -> ResumeCard(p, vm, nav) }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            if (historyCount > 0) {
                item {
                    RowItem(
                        title = "History",
                        subtitle = "$historyCount ${plural(historyCount, "show")} and playlists played",
                        artUrl = null,
                        onClick = { nav.navigate("history") }
                    )
                }
            }

            item { SectionHeader("Your phish.in", divided = true) }
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

            item { SectionHeader("Scrobbling", divided = true) }
            item {
                RowItem(
                    title = "Last.fm",
                    subtitle = when {
                        lastFmUser != null -> "Built-in scrobbler on, as $lastFmUser"
                        !LastFmApi.configured -> "Use the Last.fm app — tap to read how"
                        else -> "Not connected"
                    },
                    artUrl = null,
                    onClick = { nav.navigate("lastfm") }
                )
            }

            item { SectionHeader("Browse", divided = true) }
            item {
                RowItem("Browse playlists", "Public playlists on phish.in", null) {
                    nav.navigate("playlists")
                }
            }

            item { SectionHeader("Browse by year", divided = true) }

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
                            artUrl = track.showAlbumCoverUrl,
                            trailing = fmt(track.duration),
                            trailingContent = {
                                LikeButton(
                                    Likable.Track, track.id,
                                    track.likedByUser, track.likesCount,
                                )
                            },
                            onClick = { vm.playTrack(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LastFmScreen(nav: NavHostController) {
    val context = LocalContext.current
    val username by LastFmSession.username.collectAsState()
    var token by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Header("Last.fm", nav)

        Text(
            "You probably don't need this. The official Last.fm app can scrobble this app " +
                "directly — open it, go to Account, and switch on Phish.in under " +
                "\"Scrobble from…\". No API key, nothing to set up here.\n\n" +
                "Only use the built-in scrobbler below if you'd rather not run the Last.fm " +
                "app. Do not enable both, or every track is scrobbled twice.",
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.padding(16.dp)
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.10f))

        if (!LastFmApi.configured) {
            Text(
                "The built-in scrobbler is not configured in this build. It needs an API " +
                    "account from last.fm/api/account/create, with lastfm.apiKey and " +
                    "lastfm.apiSecret added to local.properties before rebuilding.",
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.padding(16.dp)
            )
            return
        }

        if (username != null) {
            Text(
                "Scrobbling as $username.",
                fontSize = 15.sp,
                modifier = Modifier.padding(16.dp)
            )
            Button(
                onClick = { LastFmSession.disconnect() },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) { Text("Disconnect") }
            return
        }

        Text(
            "Connecting opens Last.fm in your browser to approve this app. Your Last.fm " +
                "password is never typed into this app.",
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.padding(16.dp)
        )

        Button(
            onClick = {
                status = null
                scope.launch {
                    runCatching { LastFmApi.requestToken() }
                        .onSuccess {
                            token = it
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(LastFmApi.authorizeUrl(it)))
                            )
                        }
                        .onFailure { status = "Couldn't reach Last.fm: ${it.message}" }
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        ) { Text("1. Approve in browser") }

        Button(
            enabled = token != null,
            onClick = {
                val current = token ?: return@Button
                status = null
                scope.launch {
                    runCatching { LastFmApi.session(current) }
                        .onSuccess { (key, user) ->
                            LastFmSession.connect(key, user)
                            ScrobbleQueue.flush(context)
                        }
                        .onFailure { status = "Not approved yet: ${it.message}" }
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        ) { Text("2. Finish connecting") }

        status?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
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
                                        color = Color.Gray, fontSize = 13.sp
                                    )
                                    pl.description?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, color = Color.Gray, fontSize = 13.sp,
                                            modifier = Modifier.padding(top = 6.dp))
                                    }
                                }
                                LikeButton(Likable.Playlist, pl.id, pl.likedByUser, pl.likesCount)
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
        Column(Modifier.weight(1f)) {
            Text(show.venueName.orEmpty(), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(show.location.orEmpty(), color = Color.Gray, fontSize = 14.sp)
            Text("$trackCount tracks · ${fmt(show.duration)}", color = Color.Gray, fontSize = 13.sp)
        }
        LikeButton(Likable.Show, show.id, show.likedByUser, show.likesCount)
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

/** Navigates to whatever a queue key points at. */
private fun openQueueKey(key: String, nav: NavHostController) {
    val ref = parseQueueKey(key) ?: return
    when (ref.kind) {
        QueueKind.PLAYLIST -> nav.navigate("playlist/${ref.id}")
        QueueKind.SHOW -> nav.navigate("show/${ref.id}")
    }
}

private fun openQueue(progress: Progress, nav: NavHostController) =
    openQueueKey(progress.queueKey, nav)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResumeCard(progress: Progress, vm: PlayerViewModel, nav: NavHostController) {
    var menuOpen by remember { mutableStateOf(false) }
    val isPlaylist = parseQueueKey(progress.queueKey)?.kind == QueueKind.PLAYLIST

    Column(Modifier.width(132.dp)) {
        Box {
            AsyncImage(
                model = progress.artUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(8.dp))
                    // Tapping the art opens it; playing is the explicit button below.
                    .combinedClickable(
                        onClick = { openQueue(progress, nav) },
                        onLongClick = { menuOpen = true },
                    )
            )
            // A plain clickable Box rather than IconButton — IconButton enforces a 48dp
            // minimum touch target that spills outside the 132dp artwork.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { vm.resume(progress) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    "Resume ${progress.title}",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (isPlaylist) "Open playlist" else "Open show") },
                    onClick = { menuOpen = false; openQueue(progress, nav) }
                )
                DropdownMenuItem(
                    text = { Text("Mark completed") },
                    onClick = { menuOpen = false; vm.markCompleted(progress) }
                )
                DropdownMenuItem(
                    text = { Text("Remove from list") },
                    onClick = { menuOpen = false; vm.dismiss(progress) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(progress.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(progress.trackTitle, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
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
                color = Color.Gray,
                modifier = Modifier.padding(16.dp)
            )
            return
        }
        LazyColumn {
            items(history, key = { it.queueKey }) { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        RowItem(
                            title = p.title,
                            subtitle = p.subtitle,
                            artUrl = p.artUrl,
                            onClick = {
                                val ref = parseQueueKey(p.queueKey)
                                when (ref?.kind) {
                                    QueueKind.PLAYLIST -> nav.navigate("playlist/${ref.id}")
                                    QueueKind.SHOW -> nav.navigate("show/${ref.id}")
                                    null -> Unit
                                }
                            },
                            trailing = when {
                                p.finished -> "✓ completed"
                                p.dismissed -> "removed · ${fmt(p.positionMs)}"
                                else -> "at ${fmt(p.positionMs)}"
                            },
                        )
                    }
                    IconButton(onClick = { vm.forget(p) }) {
                        Icon(
                            Icons.Default.Close,
                            "Delete ${p.title} from history",
                            tint = Color.Gray,
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun TrackRow(track: Track, number: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$number", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        // The set is already the section header above; repeating it per row is noise.
        Text(track.title, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(fmt(track.duration), color = Color.Gray, fontSize = 13.sp)
        LikeButton(Likable.Track, track.id, track.likedByUser, track.likesCount)
    }
}

/**
 * Heart plus count. Owns its own state so a row updates immediately, and rolls back if the
 * request fails rather than showing a like that didn't happen. Signed out it still shows
 * the count, since that's public, but tapping is inert.
 */
@Composable
private fun LikeButton(type: Likable, id: Long, initiallyLiked: Boolean, initialCount: Int) {
    val signedIn by Session.username.collectAsState()
    var liked by remember(id) { mutableStateOf(initiallyLiked) }
    var count by remember(id) { mutableIntStateOf(initialCount) }
    var busy by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (signedIn == null) Modifier else Modifier.clickable(enabled = !busy) {
                    val wasLiked = liked
                    liked = !wasLiked
                    count += if (wasLiked) -1 else 1
                    busy = true
                    scope.launch {
                        val result = runCatching {
                            if (wasLiked) PhishInApi.unlike(type, id) else PhishInApi.like(type, id)
                        }
                        if (result.isFailure) {
                            liked = wasLiked
                            count += if (wasLiked) 1 else -1
                        }
                        busy = false
                    }
                }
            )
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Icon(
            if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (liked) "Unlike" else "Like",
            tint = if (liked) MaterialTheme.colorScheme.primary else Color.Gray,
            modifier = Modifier.size(18.dp)
        )
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            Text("$count", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun MiniPlayer(state: PlayerState, vm: PlayerViewModel, nav: NavHostController) {
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = state.artUrl,
                contentDescription = "Open ${state.queueTitle}",
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    // Shuffle has no queue key and so nowhere to go.
                    .then(
                        state.queueKey?.let { key ->
                            Modifier.clickable { openQueueKey(key, nav) }
                        } ?: Modifier
                    )
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
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        // Back only unwinds one step; from a playlist four levels deep that's tedious.
        IconButton(onClick = { nav.popBackStack("home", inclusive = false) }) {
            Icon(Icons.Default.Home, "Home")
        }
    }
}

/**
 * Section heading. [divided] draws a rule above it so the home screen's sections read as
 * distinct blocks rather than one continuous list; the first section on a screen omits it.
 */
@Composable
private fun SectionHeader(text: String, divided: Boolean = false) {
    Column {
        if (divided) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
        }
        Text(
            text.uppercase(),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
        )
    }
}

@Composable
private fun RowItem(
    title: String,
    subtitle: String,
    artUrl: String?,
    trailing: String? = null,
    /** Slot for a control that isn't part of the row's own click target, e.g. a heart. */
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = if (trailingContent != null) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
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
        trailingContent?.invoke()
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

