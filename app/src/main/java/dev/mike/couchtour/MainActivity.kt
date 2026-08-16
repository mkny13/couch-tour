package dev.mike.couchtour

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
        enableEdgeToEdge()
        openNowPlaying.value = intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true

        // Without this the media notification (and therefore the lockscreen controls)
        // is silently suppressed on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            CouchTourTheme {
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

    // Arriving from the media notification. Waits for the queue to be known — not just the
    // key, since shuffle queues have none — then opens the player once and clears the flag.
    LaunchedEffect(openNowPlaying.value, state.hasQueue) {
        if (openNowPlaying.value && state.hasQueue) {
            nav.navigate("player")
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

    val currentRoute by nav.currentBackStackEntryAsState()
    Scaffold(
        // The full player already shows everything the bar does — stacking both is redundant.
        bottomBar = {
            if (state.hasQueue && currentRoute?.destination?.route != "player") {
                MiniPlayer(state, vm, nav)
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(vm, nav) }
            composable("player") { NowPlayingScreen(vm, nav) }
            composable("history") { HistoryScreen(vm, nav) }
            composable("login") { LoginScreen(nav) }
            composable("artist/{backend}/{id}") { entry ->
                ArtistScreen(
                    backendId = entry.arguments?.getString("backend").orEmpty(),
                    artistId = entry.arguments?.getString("id").orEmpty(),
                    nav = nav,
                )
            }
            composable(
                "artist/{backend}/{id}/{period}?label={label}",
                arguments = listOf(navArgument("label") { type = NavType.StringType; nullable = true }),
            ) { entry ->
                ArtistShowsScreen(
                    backendId = entry.arguments?.getString("backend").orEmpty(),
                    artistId = entry.arguments?.getString("id").orEmpty(),
                    periodId = entry.arguments?.getString("period").orEmpty(),
                    periodLabel = entry.arguments?.getString("label"),
                    nav = nav,
                )
            }
            composable(
                "recording/{backend}/{artistId}/{date}?src={src}",
                arguments = listOf(navArgument("src") { type = NavType.StringType; nullable = true }),
            ) { entry ->
                RecordingScreen(
                    backendId = entry.arguments?.getString("backend").orEmpty(),
                    artistId = entry.arguments?.getString("artistId").orEmpty(),
                    date = entry.arguments?.getString("date").orEmpty(),
                    recordingId = entry.arguments?.getString("src"),
                    vm = vm,
                    nav = nav,
                )
            }
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
            composable("sync") { SyncScreen(nav) }
        }
    }
}

// ---------------------------------------------------------------- screens

@Composable
fun HomeScreen(vm: PlayerViewModel, nav: NavHostController) {
    val artists = loadOnce { loadAllArtists() }
    val recent by vm.progressDao.inProgress().collectAsState(initial = emptyList())
    val historyCount by vm.progressDao.historyCount().collectAsState(initial = 0)
    val username by Session.username.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val term = query.trim()
    val results = searchFor(term)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Couch Tour",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            CastButton()
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Artists, songs, venues, dates…") },
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

            item { SectionHeader("Artists", divided = true) }
            when (val r = artists.value) {
                null -> item { Loading() }
                else -> r.fold(
                    onSuccess = { list ->
                        items(list, key = { "${it.backend.id}-${it.id}" }) { artist ->
                            RowItem(
                                title = artist.name,
                                subtitle = "${artist.showCount} ${plural(artist.showCount, "show")}",
                                artUrl = null,
                                onClick = { nav.navigate("artist/${artist.backend.id}/${artist.id}") }
                            )
                        }
                    },
                    onFailure = { item { ErrorText(it) } }
                )
            }

            item { SectionHeader("Your phish.in account", divided = true) }
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

            item { SectionHeader("Sync", divided = true) }
            item {
                val paired by SyncSession.paired.collectAsState()
                RowItem(
                    title = "Sync across devices",
                    subtitle = if (paired) "Paired — manage devices" else "Not paired",
                    artUrl = null,
                    onClick = { nav.navigate("sync") }
                )
            }
        }
    }
}

/**
 * Merges Phish (a phish.in constant with its show count summed from `years()`) with every
 * Relisten artist. A Relisten outage still leaves Phish browsable; only surfaces an error if
 * *both* backends fail.
 */
private suspend fun loadAllArtists(): List<ArtistRef> {
    val phishShows = runCatching { PhishInApi.years().sumOf { it.showsWithAudioCount } }
    val relistenArtists = runCatching { RelistenCatalogSource.artists() }
    if (phishShows.isFailure && relistenArtists.isFailure) {
        throw relistenArtists.exceptionOrNull() ?: phishShows.exceptionOrNull()!!
    }
    val phish = PHISH.copy(showCount = phishShows.getOrDefault(0))
    return mergeArtists(
        mapOf(
            Backend.PHISHIN to listOf(phish),
            Backend.RELISTEN to relistenArtists.getOrDefault(emptyList()),
        )
    )
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

/** Years (or ranged periods) for one artist of either backend. */
@Composable
fun ArtistScreen(backendId: String, artistId: String, nav: NavHostController) {
    val backend = Backend.from(backendId)
    val loaded = loadOnce(artistId) {
        val source = sourceFor(backend ?: error("Unknown backend $backendId"))
        val artist = source.artists().firstOrNull { it.id == artistId } ?: error("Unknown artist $artistId")
        artist to source.periods(artist)
    }

    Column(Modifier.fillMaxSize()) {
        Header(loaded.value?.getOrNull()?.first?.name ?: artistId, nav)
        when (val r = loaded.value) {
            null -> Loading()
            else -> r.fold(
                onSuccess = { (_, periods) ->
                    LazyColumn {
                        // Newest first, matching the phish.in years screen.
                        items(periods.sortedByDescending { it.label }, key = { it.id }) { period ->
                            RowItem(
                                title = period.label,
                                subtitle = "${period.showCount} ${plural(period.showCount, "show")}",
                                artUrl = period.artUrl,
                                onClick = {
                                    // Phish keeps its own show/track screens so likes and the
                                    // "partial" audio badge — features Relisten has no
                                    // analogue for — still work.
                                    if (backend == Backend.PHISHIN) {
                                        nav.navigate("shows/${period.id}")
                                    } else {
                                        nav.navigate("artist/$backendId/$artistId/${period.id}")
                                    }
                                }
                            )
                        }
                    }
                },
                onFailure = { ErrorText(it) }
            )
        }
    }
}

/** Shows within one period of one Relisten artist. */
@Composable
fun ArtistShowsScreen(
    backendId: String,
    artistId: String,
    periodId: String,
    /** Set when arriving from a search hit (a song or venue): the period id isn't in
     *  [MusicSource.periods]' ordinary list, so its label travels with the route instead of
     *  being looked up — and skips the wasted years fetch that lookup would cost. */
    periodLabel: String? = null,
    nav: NavHostController,
) {
    val backend = Backend.from(backendId)
    val loaded = loadOnce(periodId) {
        val source = sourceFor(backend ?: error("Unknown backend $backendId"))
        val artist = source.artists().firstOrNull { it.id == artistId } ?: error("Unknown artist $artistId")
        val period = periodLabel?.let { PeriodRef(periodId, it) }
            ?: source.periods(artist).firstOrNull { it.id == periodId } ?: error("Unknown period $periodId")
        Triple(artist, period, source.shows(artist, period))
    }

    Column(Modifier.fillMaxSize()) {
        Header(loaded.value?.getOrNull()?.second?.label ?: periodId, nav)
        when (val r = loaded.value) {
            null -> Loading()
            else -> r.fold(
                onSuccess = { (_, _, shows) ->
                    LazyColumn {
                        items(shows, key = { it.date }) { show ->
                            RowItem(
                                title = show.date,
                                subtitle = show.where,
                                artUrl = show.artUrl,
                                trailing = if (show.recordingCount > 1) "${show.recordingCount} tapes" else null,
                                onClick = { nav.navigate("recording/$backendId/$artistId/${show.date}") }
                            )
                        }
                    }
                },
                onFailure = { ErrorText(it) }
            )
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
    vm: PlayerViewModel,
    nav: NavHostController,
) {
    val backend = Backend.from(backendId)
    val loaded = loadOnce(Triple(artistId, date, recordingId)) {
        val source = sourceFor(backend ?: error("Unknown backend $backendId"))
        val artist = source.artists().firstOrNull { it.id == artistId } ?: error("Unknown artist $artistId")
        val detail = source.show(artist, date, recordingId)
        // A finished show's stored position is the end of the encore, same reasoning
        // ShowScreen's resume banner uses (D22).
        detail to detail.queueKey?.let { vm.progressFor(it) }?.takeIf { !it.finished }
    }

    Column(Modifier.fillMaxSize()) {
        Header(date, nav)
        when (val r = loaded.value) {
            null -> Loading()
            else -> r.fold(
                onSuccess = { (detail, progress) ->
                    LazyColumn {
                        item { RecordingHeader(detail, backendId, artistId, date, nav) }
                        if (progress != null) {
                            item {
                                ResumeBanner(progress) {
                                    vm.playRecording(detail, progress.trackIndex, progress.positionMs)
                                }
                            }
                        }
                        groupedBySet(detail.tracks, { it.setName }, { it.id }) { index, track ->
                            RecordingTrackRow(track, index + 1) { vm.playRecording(detail, index, 0) }
                        }
                    }
                },
                onFailure = { ErrorText(it) }
            )
        }
    }
}

@Composable
private fun RecordingHeader(detail: ShowDetail, backendId: String, artistId: String, date: String, nav: NavHostController) {
    val summary = detail.summary
    Column {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = summary.artUrl,
                contentDescription = null,
                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(summary.venue.orEmpty(), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(summary.location.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Text("${detail.tracks.size} tracks", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                detail.recording?.let { rec ->
                    Text(recordingLabel(rec), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
        // No tape to switch on a single-source artist (Phish) or a show with only one.
        if (summary.artist.hasMultipleSources && detail.alternates.isNotEmpty()) {
            TapeSwitcher(detail, backendId, artistId, date, nav)
        }
    }
}

private fun recordingLabel(rec: RecordingRef): String {
    val rating = if (rec.rating > 0) " · %.1f".format(rec.rating) else ""
    return rec.label + rating
}

@Composable
private fun TapeSwitcher(detail: ShowDetail, backendId: String, artistId: String, date: String, nav: NavHostController) {
    var open by remember { mutableStateOf(false) }
    val tapes = listOfNotNull(detail.recording) + detail.alternates

    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        RowItem(
            title = "Switch tape",
            subtitle = "${tapes.size} ${plural(tapes.size, "recording")} of this show",
            artUrl = null,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tapes.forEach { tape ->
                val current = tape.id == detail.recording?.id
                DropdownMenuItem(
                    text = { Text((if (current) "✓ " else "") + recordingLabel(tape)) },
                    onClick = {
                        open = false
                        if (!current) nav.navigate("recording/$backendId/$artistId/$date?src=${tape.id}")
                    }
                )
            }
        }
    }
}

@Composable
private fun RecordingTrackRow(track: PlayableTrack, number: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$number", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        Text(track.title, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(fmt(track.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun SearchResultsList(
    results: SearchHits?,
    vm: PlayerViewModel,
    nav: NavHostController,
) {
    if (results == null) {
        Loading()
        return
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val artistsPresent = results.artistsPresent
    // Selection survives to a different query only by accident of key reuse — clear it once
    // the artist it named is no longer among the hits.
    val selectedArtist = artistsPresent.firstOrNull { "${it.backend.id}/${it.id}" == selected }
    val r = results.filteredTo(selectedArtist)

    Column(Modifier.fillMaxSize()) {
        if (artistsPresent.size > 1) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = selected == null,
                        onClick = { selected = null },
                        label = { Text("All") },
                    )
                }
                items(artistsPresent, key = { "${it.backend.id}/${it.id}" }) { artist ->
                    val key = "${artist.backend.id}/${artist.id}"
                    FilterChip(
                        selected = selected == key,
                        onClick = { selected = if (selected == key) null else key },
                        label = { Text(artist.name) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (r.isEmpty) {
            val message = if (results.failed.isNotEmpty()) {
                "Couldn't search " + results.failed.joinToString(" or ") {
                    if (it == Backend.PHISHIN) "Phish" else "Relisten"
                } + "."
            } else {
                "Nothing matched."
            }
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            return
        }

        LazyColumn {
            if (r.artists.isNotEmpty()) {
                item { SectionHeader("Artists") }
                items(r.artists, key = { "artist-${it.backend.id}-${it.id}" }) { artist ->
                    RowItem(
                        title = artist.name,
                        subtitle = "${artist.showCount} ${plural(artist.showCount, "show")}",
                        artUrl = null,
                        onClick = { nav.navigate("artist/${artist.backend.id}/${artist.id}") }
                    )
                }
            }
            if (r.shows.isNotEmpty()) {
                item { SectionHeader("Shows") }
                items(r.shows, key = { "show-${it.artist.backend.id}-${it.artist.id}-${it.date}" }) { show ->
                    RowItem(
                        title = show.date,
                        subtitle = listOfNotNull(
                            if (show.artist.backend != Backend.PHISHIN) show.artist.name else null,
                            show.where.ifBlank { null },
                        ).joinToString(" · "),
                        artUrl = show.artUrl,
                        onClick = {
                            when (show.artist.backend) {
                                Backend.PHISHIN -> nav.navigate("show/${show.date}")
                                Backend.RELISTEN -> nav.navigate("recording/relisten/${show.artist.id}/${show.date}")
                            }
                        }
                    )
                }
            }
            SliceKind.entries.forEach { kind ->
                val slices = r.slices.filter { it.kind == kind }
                if (slices.isNotEmpty()) {
                    item { SectionHeader(kind.heading) }
                    items(slices, key = { "${kind.name}-${it.artist.backend.id}-${it.artist.id}-${it.period.id}" }) { slice ->
                        RowItem(
                            title = slice.period.label,
                            subtitle = "${slice.artist.name} · ${slice.period.showCount} ${plural(slice.period.showCount, "show")}",
                            artUrl = null,
                            onClick = {
                                val encodedPeriod = android.net.Uri.encode(slice.period.id)
                                val encodedLabel = android.net.Uri.encode(slice.period.label)
                                nav.navigate("artist/${slice.artist.backend.id}/${slice.artist.id}/$encodedPeriod?label=$encodedLabel")
                            }
                        )
                    }
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

@Composable
fun LoginScreen(nav: NavHostController) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Header("Log in to phish.in", nav)
        Text(
            "phish.in is a separate website hosting the Phish archive. Logging in shows your " +
                "liked shows, tracks, and playlists — for Phish only, not the other artists in " +
                "this app. The password is sent once to get a token and is never stored; only " +
                "the token is kept, encrypted on this device.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * Pairing and device management for progress sync (D119-D127). No QR yet — the code is
 * short enough to type, and adding camera scanning is its own follow-up (ROADMAP.md).
 */
@Composable
fun SyncScreen(nav: NavHostController) {
    val paired by SyncSession.paired.collectAsState()
    var pairingResult by remember { mutableStateOf<PairStartResponse?>(null) }
    var claimCode by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Header("Sync", nav)
        Text(
            "Sync keeps listening history and resume position in step across your paired " +
                "devices. Pairing is one-time; after that, devices sync on their own.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (paired) {
            RowItem("This device is paired", "Tap to unlink", null) {
                SyncSession.unlink()
                pairingResult = null
            }
        }

        pairingResult?.let { result ->
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Enter this code on the other device:",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    result.code,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Text(
                    "Expires in 10 minutes",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            enabled = !busy,
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { SyncSession.startPairing() }
                        .onSuccess { pairingResult = it }
                        .onFailure { error = "Couldn't start pairing: ${it.message}" }
                    busy = false
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) { Text(if (paired) "Add another device" else "Pair this device") }

        if (!paired) {
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.10f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            )
            Text(
                "Have a code from another device?",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            OutlinedTextField(
                value = claimCode,
                onValueChange = { claimCode = it.uppercase(); error = null },
                label = { Text("Code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Button(
                enabled = !busy && claimCode.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { SyncSession.claimPairing(claimCode.trim()) }
                            .onSuccess { claimCode = "" }
                            .onFailure { error = "Couldn't join: ${it.message}" }
                        busy = false
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text(if (busy) "Joining…" else "Join") }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }

        if (paired) {
            val devices = loadOnce(paired to refreshKey) { SyncSession.devices() }
            SectionHeader("Devices", divided = true)
            when (val r = devices.value) {
                null -> Loading()
                else -> r.fold(
                    onSuccess = { list ->
                        list.forEach { device ->
                            RowItem(
                                title = device.name + if (device.isSelf) " (this device)" else "",
                                subtitle = device.platform,
                                artUrl = null,
                                trailing = "Revoke",
                                onClick = {
                                    scope.launch {
                                        runCatching { SyncSession.revoke(device.deviceId) }
                                        refreshKey++
                                    }
                                }
                            )
                        }
                    },
                    onFailure = { ErrorText(it) }
                )
            }
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
                        Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
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
            Text(show.location.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Text("$trackCount tracks · ${fmt(show.duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
        Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(10.dp))
        Text(
            "Resume “${progress.trackTitle}” at ${fmt(progress.positionMs)}",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Navigates to whatever a queue key points at. */
internal fun openQueueKey(key: String, nav: NavHostController) {
    val ref = parseQueueKey(key) ?: return
    when (ref.kind) {
        QueueKind.PLAYLIST -> nav.navigate("playlist/${ref.id}")
        QueueKind.SHOW -> nav.navigate("show/${ref.id}")
        QueueKind.RECORDING -> {
            val rec = parseRecordingId(ref.id) ?: return
            nav.navigate("recording/${Backend.RELISTEN.id}/${rec.artistSlug}/${rec.date}?src=${rec.sourceId}")
        }
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
        Text(progress.trackTitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        RowItem(
                            title = p.title,
                            subtitle = p.subtitle,
                            artUrl = p.artUrl,
                            onClick = { openQueue(p, nav) },
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Text("$number", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        // The set is already the section header above; repeating it per row is noise.
        Text(track.title, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(fmt(track.duration), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
            tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            Text("$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MiniPlayer(state: PlayerState, vm: PlayerViewModel, nav: NavHostController) {
    Column(
        Modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            )
            .navigationBarsPadding()
    ) {
        if (state.durationMs > 0) {
            val fraction = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { nav.navigate("player") }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkBox(
                artUrl = state.artUrl,
                contentDescription = "Open ${state.queueTitle}",
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.trackTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.showTitle.isNotEmpty()) {
                    Text(
                        state.showTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.isPlaying) "Pause" else "Play",
                )
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
        CastButton()
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
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (trailing != null) Text(trailing, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
 * Debounced search across every backend. produceState cancels the previous coroutine
 * whenever [term] changes, so the delay collapses a burst of keystrokes into one request.
 * [searchAll] already degrades per backend on failure, so this doesn't need a `runCatching`
 * of its own — [SearchHits.failed] carries what didn't answer.
 */
@Composable
private fun searchFor(term: String): State<SearchHits?> =
    produceState<SearchHits?>(initialValue = null, key1 = term) {
        if (term.length < 3) {
            value = null
            return@produceState
        }
        value = null
        delay(300)
        value = searchAll(term)
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
) = groupedBySet(tracks, { it.setName }, { it.id }, content)

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
    content: @Composable (Int, T) -> Unit,
) {
    items.forEachIndexed { index, item ->
        if (setName(item).isNotEmpty() && (index == 0 || setName(items[index - 1]) != setName(item))) {
            item(key = "set-${setName(item)}-$index") { SectionHeader(setName(item)) }
        }
        item(key = "track-${key(item)}") { content(index, item) }
    }
}

