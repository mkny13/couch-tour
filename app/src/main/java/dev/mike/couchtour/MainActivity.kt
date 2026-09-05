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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    val isPlayerRoute = currentRoute?.destination?.route == "player"
    Scaffold(
        bottomBar = {
            if (!isPlayerRoute) {
                Column {
                    if (state.hasQueue) {
                        MiniPlayer(state, vm, nav)
                    }
                    LedgerBottomBar(currentRoute?.destination?.route, nav)
                }
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(vm, nav) }
            composable("artists") { ArtistsScreen(nav) }
            composable("search") { SearchScreen(vm, nav) }
            composable("library") { LibraryScreen(vm, nav) }
            composable("settings") { SettingsScreen(vm, nav) }
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
                "recording/{backend}/{artistId}/{date}?src={src}&resumeIndex={resumeIndex}&resumeMs={resumeMs}",
                arguments = listOf(
                    navArgument("src") { type = NavType.StringType; nullable = true },
                    // Carried by the source picker when it catches this show mid-playback
                    // (#17) — a query param, not an Int/Long NavType, because those can't be
                    // nullable and most navigations to this route have neither.
                    navArgument("resumeIndex") { type = NavType.StringType; nullable = true },
                    navArgument("resumeMs") { type = NavType.StringType; nullable = true },
                ),
            ) { entry ->
                RecordingScreen(
                    backendId = entry.arguments?.getString("backend").orEmpty(),
                    artistId = entry.arguments?.getString("artistId").orEmpty(),
                    date = entry.arguments?.getString("date").orEmpty(),
                    recordingId = entry.arguments?.getString("src"),
                    resumeIndex = entry.arguments?.getString("resumeIndex")?.toIntOrNull(),
                    resumeMs = entry.arguments?.getString("resumeMs")?.toLongOrNull(),
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
            composable("local-playlists") { LocalPlaylistsScreen(vm, nav) }
            composable("local-playlist/{id}") { entry ->
                LocalPlaylistScreen(entry.arguments?.getString("id").orEmpty(), vm, nav)
            }
            composable("sync") { SyncScreen(vm, nav) }
            composable("scan") { ScanScreen(nav) }
        }
    }
}

// ---------------------------------------------------------------- screens

@Composable
fun HomeScreen(vm: PlayerViewModel, nav: NavHostController) {
    val rawArtists = loadOnce { loadArtistsByBackend() }
    val favoriteKeys by Favorites.keys.collectAsState()
    // A derived merge rather than part of loadOnce's cached fetch: it must re-run whenever
    // the user favorites/unfavorites an artist, not just once per screen load.
    val artists = remember(rawArtists.value, favoriteKeys) {
        rawArtists.value?.map { mergeArtists(it, favoriteKeys) }
    }
    val recent by vm.progressDao.inProgress().collectAsState(initial = emptyList())
    val historyCount by vm.progressDao.historyCount().collectAsState(initial = 0)
    val username by Session.username.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val term = query.trim()
    val results = searchFor(term)

    Column(Modifier.fillMaxSize()) {
        val favoritedArtists = artists?.getOrNull()?.filter { it.key in favoriteKeys }.orEmpty()
        val preferences by vm.artistTourPreferenceDao.getAllPreferences().collectAsState(initial = emptyList())
        val preferencesMap = remember(preferences) { preferences.associateBy { it.artistKey } }
        var tourPickerArtist by remember { mutableStateOf<ArtistRef?>(null) }

        // A second, independent load rather than part of loadArtistsByBackend's: it is a
        // multi-request walk of the favorited artists' catalogs (#13), far slower than the
        // artist list, and the screen's first paint must not wait on it. Keyed on the date and
        // the favorites, which is exactly what the answer depends on.
        val today = remember { java.time.LocalDate.now() }
        val dateHeader = remember(today) {
            today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", java.util.Locale.US)).uppercase()
        }
        val ledger = LocalLedgerColors.current
        val todayStr = remember(today) { today.toString() }
        val onThisDate = loadOnce(todayStr to favoritedArtists.map { it.key }) {
            OnThisDate.load(favoritedArtists, todayStr)
        }
        val nextStopShows = loadOnce(Triple(todayStr, favoritedArtists.map { it.key }, preferencesMap)) {
            NextStop.load(favoritedArtists, todayStr, preferencesMap)
        }
        val finishedKeys by vm.progressDao.finishedKeys().collectAsState(initial = emptyList())
        val nextStop = remember(nextStopShows.value, finishedKeys) {
            nextStopShows.value?.getOrNull()?.let { oldestUnplayed(it, playedShowIds(finishedKeys)) }
        }


        LazyColumn(Modifier.fillMaxSize()) {
            // Date header + "Surprise me" chip
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateHeader,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp,
                        color = ledger.textSubtle,
                        modifier = Modifier.weight(1f)
                    )
                    SurpriseMeChip(surpriseMeArtists(favoritedArtists, artists?.getOrNull().orEmpty()), nav)
                }
            }

            // IN PROGRESS section
            if (recent.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { nav.navigate("history") }
                        ) {
                            Text(
                                text = "IN PROGRESS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.2.sp,
                                color = ledger.textMuted
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "History",
                                tint = ledger.textMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = "${recent.size} of $historyCount",
                            fontSize = 12.sp,
                            color = ledger.accentIcon,
                            modifier = Modifier.clickable { nav.navigate("history") }
                        )
                    }
                }
                item {
                    GradientHairline(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    )
                }
                items(recent.take(4), key = { it.queueKey }) { p ->
                    InProgressLedgerRow(p, vm, nav)
                }
            }

            // NEXT TOUR STOP Card
            if (nextStop != null || favoritedArtists.isNotEmpty()) {
                val show = nextStop
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ledger.cardSurface)
                            .border(1.dp, ledger.panelBorder, RoundedCornerShape(10.dp))
                    ) {
                        Column {
                            // Top Amber/Pink hairline
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFFF2A93B), Color(0xFFF06BB0), Color.Transparent)
                                        )
                                    )
                            )
                            Text(
                                text = "NEXT TOUR STOP",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.2.sp,
                                color = ledger.textMuted,
                                modifier = Modifier.padding(start = 14.dp, top = 10.dp)
                            )
                            if (favoritedArtists.isNotEmpty()) {
                                LazyRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(favoritedArtists, key = { it.key }) { artist ->
                                        val selected = show?.artist?.key == artist.key
                                        Box(
                                            modifier = Modifier
                                                .height(26.dp)
                                                .clip(RoundedCornerShape(13.dp))
                                                .background(
                                                    if (selected) Color(0x299184D9) else Color.Transparent
                                                )
                                                .border(
                                                    1.dp,
                                                    if (selected) ledger.accentIcon else ledger.controlOutline,
                                                    RoundedCornerShape(13.dp)
                                                )
                                                .clickable { tourPickerArtist = artist }
                                                .padding(horizontal = 11.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = artist.name,
                                                fontSize = 12.sp,
                                                color = if (selected) ledger.accentTintText else ledger.textSecondary
                                            )
                                        }
                                    }
                                }
                            }
                            if (show != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = show.artist.name,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ledger.textPrimary
                                        )
                                        Text(
                                            text = show.date,
                                            fontSize = 14.sp,
                                            color = ledger.textSecondary,
                                            modifier = Modifier.padding(top = 1.dp)
                                        )
                                        val subtitle = listOfNotNull(
                                            show.where.ifBlank { null },
                                            show.tourName
                                        ).joinToString(" · ")
                                        Text(
                                            text = subtitle,
                                            fontSize = 12.sp,
                                            color = ledger.textSubtle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (show.rating > 0.0) {
                                        Text(
                                            text = "★ ${"%.1f".format(java.util.Locale.US, show.rating)}",
                                            fontSize = 12.sp,
                                            color = ledger.ratingAmber,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                    }
                                    CircularPlayButton(
                                        isPlaying = false,
                                        onClick = { vm.playNextTourStop(show) },
                                        size = 34.dp,
                                        iconSize = 16.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ON THIS DATE section
            loaded(onThisDate.value) { shows ->
                if (shows.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ON THIS DATE",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.2.sp,
                                color = ledger.textMuted
                            )
                            Text(
                                text = "${shows.size} ${plural(shows.size, "show")}",
                                fontSize = 12.sp,
                                color = ledger.textSubtle
                            )
                        }
                    }
                    item {
                        GradientHairline(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        )
                    }
                    items(shows, key = { "${it.artist.key}-${it.date}" }) { show ->
                        OnThisDateLedgerRow(show, nav)
                    }
                }
            }

            // Separate from the full "Artists" list below (Relisten parity, #14) — quick
            // access to the bands the user cares about, rather than scrolling a merged list
            // that can run to hundreds of entries. Favoriting doesn't remove an artist from
            // that full list, so it still shows up in both places.
            if (favoritedArtists.isNotEmpty()) {
                item { SectionHeader("Favorites", divided = true) }
                items(favoritedArtists, key = { "favorite-${it.backend.id}-${it.id}" }) { artist ->
                    RowItem(
                        title = artist.name,
                        subtitle = "${artist.showCount} ${plural(artist.showCount, "show")}",
                        artUrl = null,
                        onClick = { nav.navigate("artist/${artist.backend.id}/${artist.id}") }
                    )
                }
            }

            item { SectionHeader("Artists", divided = true) }
            item {
                RowItem(
                    title = "Browse artists",
                    subtitle = artists?.getOrNull()?.let { "${it.size} ${plural(it.size, "artist")} on phish.in and Relisten" }
                        ?: "Explore artists on phish.in and Relisten",
                    artUrl = null,
                    onClick = { nav.navigate("artists") }
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
            item {
                // Account-free (#12), so it sits outside the phish.in-account section above —
                // and isn't titled "My playlists" too, which that section's row already is.
                RowItem("Local playlists", "Mix tracks from any artist, saved on this device", null) {
                    nav.navigate("local-playlists")
                }
            }

            item { SectionHeader("Playback", divided = true) }
            item {
                val skipFiller by PlaybackSettings.skipFiller.collectAsState()
                RowItem(
                    title = "Skip filler tracks",
                    subtitle = "Skip intros, tuning, and banter during playback",
                    artUrl = null,
                    trailingContent = {
                        Switch(
                            checked = skipFiller,
                            onCheckedChange = { PlaybackSettings.setSkipFiller(it) },
                        )
                    },
                    onClick = { PlaybackSettings.toggle() },
                )
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

            // Diagnostic detail, not a feature — small, muted, and at the literal bottom of
            // the scrollable list so it never competes with the content above (#43).
            item {
                Text(
                    "Couch Tour ${BuildConfig.VERSION_NAME}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 16.dp)
                )
            }
        }

        tourPickerArtist?.let { artist ->
            TourPickerDialog(
                artist = artist,
                currentPreference = preferencesMap[artist.key] ?: preferencesMap[artist.id],
                onDismiss = { tourPickerArtist = null },
                onSave = { tour, yr ->
                    vm.setArtistTourPreference(artist.key, tour, yr)
                    tourPickerArtist = null
                },
                onClear = {
                    vm.clearArtistTourPreference(artist.key)
                    tourPickerArtist = null
                }
            )
        }
    }
}

@Composable
private fun SurpriseMeChip(artists: List<ArtistRef>, nav: NavHostController) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ledger = LocalLedgerColors.current

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color.Transparent)
            .border(1.dp, ledger.controlOutline, RoundedCornerShape(13.dp))
            .clickable(enabled = !busy && artists.isNotEmpty()) {
                busy = true
                scope.launch {
                    runCatching { pickRandomShow(artists) }
                        .onSuccess { show ->
                            when (show.artist.backend) {
                                Backend.PHISHIN -> nav.navigate("show/${show.date}")
                                Backend.RELISTEN -> nav.navigate("recording/relisten/${show.artist.id}/${show.date}")
                            }
                        }
                    busy = false
                }
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Shuffle,
                contentDescription = null,
                tint = ledger.accentIcon,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (busy) "Finding…" else "Surprise me",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = ledger.textSecondary
            )
        }
    }
}

@Composable
private fun InProgressLedgerRow(progress: Progress, vm: PlayerViewModel, nav: NavHostController) {
    val ledger = LocalLedgerColors.current
    val playerState by vm.state.collectAsState()
    val isCurrentlyPlaying = playerState.hasQueue && playerState.queueKey == progress.queueKey
    val fraction = if (isCurrentlyPlaying && playerState.durationMs > 0) {
        (playerState.positionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else if (progress.positionMs > 0) {
        // When duration is unknown from offline progress, estimate reasonable progress based on position
        (progress.positionMs.toFloat() / (progress.positionMs + 300_000L).toFloat()).coerceIn(0.1f, 0.95f)
    } else {
        0.05f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openQueue(progress, nav) }
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = progress.artist.ifBlank { "Phish" },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = ledger.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 136.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatShowDate(progress.title),
                fontSize = 15.sp,
                color = ledger.textSecondary,
                maxLines = 1
            )
            Text(
                text = progress.trackTitle,
                fontSize = 14.sp,
                color = ledger.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 12.dp)
            )
            CircularPlayButton(
                isPlaying = isCurrentlyPlaying && playerState.isPlaying,
                onClick = {
                    if (isCurrentlyPlaying) {
                        vm.togglePlayPause()
                    } else {
                        vm.resume(progress)
                    }
                },
                size = 30.dp,
                iconSize = 15.dp
            )
        }
        // Bottom 2px progress bar overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.BottomCenter)
                .background(ledger.textPrimary.copy(alpha = 0.10f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(2.dp)
                    .background(Color(0xFFF06BB0))
            )
        }
    }
}

@Composable
private fun OnThisDateLedgerRow(show: ShowSummary, nav: NavHostController) {
    val ledger = LocalLedgerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                when (show.artist.backend) {
                    Backend.PHISHIN -> nav.navigate("show/${show.date}")
                    Backend.RELISTEN -> nav.navigate("recording/relisten/${show.artist.id}/${show.date}")
                }
            }
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val year = show.date.take(4)
                Text(
                    text = year,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = ledger.textPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = show.artist.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = ledger.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val venueLocation = show.where.ifBlank { null }
            if (venueLocation != null) {
                Text(
                    text = venueLocation,
                    fontSize = 12.sp,
                    color = ledger.textSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        if (show.rating > 0.0) {
            Text(
                text = "★ ${"%.1f".format(java.util.Locale.US, show.rating)}",
                fontSize = 12.sp,
                color = ledger.ratingAmber,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * Home screen's "Surprise me" action (#20). [artists] is the same merged list the Artists
 * section below already loaded, not a second fetch — picking still costs its own network
 * calls (period, then shows) since neither backend exposes a random-show endpoint.
 */
@Composable
private fun SurpriseMeButton(artists: List<ArtistRef>, nav: NavHostController) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Button(
            enabled = !busy && artists.isNotEmpty(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { pickRandomShow(artists) }
                        .onSuccess { show ->
                            when (show.artist.backend) {
                                Backend.PHISHIN -> nav.navigate("show/${show.date}")
                                Backend.RELISTEN -> nav.navigate("recording/relisten/${show.artist.id}/${show.date}")
                            }
                        }
                        .onFailure { error = "Couldn't find a show: ${it.message}" }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "Finding a show…" else "Surprise me")
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun ShowsScreen(period: String, nav: NavHostController) {
    val isPopular = period == POPULAR_PERIOD_ID
    val shows = loadOnce(period) {
        if (isPopular) PhishInApi.popularShows() else PhishInApi.showsForPeriod(period)
    }
    var selectedTag by rememberSaveable(period) { mutableStateOf<String?>("All") }

    Column(Modifier.fillMaxSize()) {
        Header(if (isPopular) POPULAR_PERIOD_LABEL else period, nav)
        Loaded(shows.value) { list ->
            val availableTags = remember(list) {
                val tags = list.flatMap { it.tags }
                    .distinctBy { it.name.lowercase() }
                    .sortedWith(compareByDescending<Tag> { it.priority }.thenBy { it.name })
                    .map { it.name }
                if (tags.isNotEmpty()) listOf("All") + tags else emptyList()
            }
            val filtered = if (selectedTag.isNullOrBlank() || selectedTag.equals("All", ignoreCase = true)) {
                list
            } else {
                list.filter { show -> show.tags.any { it.name.equals(selectedTag, ignoreCase = true) } }
            }

            LazyColumn {
                if (availableTags.size > 1) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 8.dp),
                        ) {
                            items(availableTags, key = { it }) { tag ->
                                val isSelected = (selectedTag == null && tag == "All") ||
                                    selectedTag.equals(tag, ignoreCase = true)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedTag = if (tag == "All") null else tag },
                                    label = { Text(tag) },
                                )
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "No shows match tag \"$selectedTag\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(filtered, key = { it.date }) { show ->
                    val isPartial = show.audioStatus == "partial"
                    RowItem(
                        title = show.date,
                        subtitle = listOfNotNull(show.venueName, show.location)
                            .joinToString(" · "),
                        artUrl = show.coverArtUrls?.small,
                        tags = show.tags.map { it.toTagRef() },
                        trailing = when {
                            isPopular -> "♥ ${show.likesCount}"
                            isPartial -> "partial"
                            else -> null
                        },
                        trailingSecondary = if (isPopular && isPartial) "partial" else null,
                        onClick = { nav.navigate("show/${show.date}") }
                    )
                }
            }
        }
    }
}

@Composable
fun ShowScreen(date: String, vm: PlayerViewModel, nav: NavHostController) {
    val show = loadOnce(date) { PhishInApi.show(date) }
    val saved = loadOnce(date) { vm.progressFor(showQueueKey(date)) }

    Column(Modifier.fillMaxSize()) {
        Header(date, nav)
        Loaded(show.value) { s ->
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
                val artUrl = s.albumCoverUrl ?: s.coverArtUrls?.medium
                tracksGroupedBySet(playable) { index, track ->
                    TrackRow(track, index + 1, date, artUrl, vm) { vm.playShow(s, index, 0) }
                }
            }
        }
    }
}

/** Full list of artists across all backends. */
@Composable
fun ArtistsScreen(nav: NavHostController) {
    val rawArtists = loadOnce { loadArtistsByBackend() }
    val favoriteKeys by Favorites.keys.collectAsState()
    val groups = remember(rawArtists.value, favoriteKeys) {
        rawArtists.value?.map { groupArtistsForBrowse(it, favoriteKeys) }
    }
    var sortMode by rememberSaveable { mutableStateOf(ArtistSortMode.POPULAR) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Header("Artists", nav)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Filter artists…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ArtistSortMode.entries, key = { it.name }) { mode ->
                FilterChip(
                    selected = sortMode == mode,
                    onClick = { sortMode = mode },
                    label = { Text(mode.label) },
                )
            }
        }
        Loaded(groups) { g ->
            val phishMatches = g.phish?.takeIf { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            val favorited = g.favorited.filterByName(query).sortedByMode(sortMode)
            val others = g.others.filterByName(query).sortedByMode(sortMode)

            if (phishMatches == null && favorited.isEmpty() && others.isEmpty()) {
                Text(
                    "No artists match \"$query\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                return@Loaded
            }

            LazyColumn {
                phishMatches?.let { phish ->
                    item(key = "${phish.backend.id}-${phish.id}") {
                        RowItem(
                            title = phish.name,
                            subtitle = "${phish.showCount} ${plural(phish.showCount, "show")}",
                            artUrl = null,
                            trailingContent = { FavoriteButton(phish) },
                            onClick = { nav.navigate("artist/${phish.backend.id}/${phish.id}") }
                        )
                    }
                }
                if (favorited.isNotEmpty()) {
                    item { SectionHeader("Favorites") }
                    items(favorited, key = { "fav-${it.backend.id}-${it.id}" }) { artist ->
                        RowItem(
                            title = artist.name,
                            subtitle = "${artist.showCount} ${plural(artist.showCount, "show")}",
                            artUrl = null,
                            trailingContent = { FavoriteButton(artist) },
                            onClick = { nav.navigate("artist/${artist.backend.id}/${artist.id}") }
                        )
                    }
                    item { SectionHeader("Artists") }
                }
                items(others, key = { "${it.backend.id}-${it.id}" }) { artist ->
                    RowItem(
                        title = artist.name,
                        subtitle = "${artist.showCount} ${plural(artist.showCount, "show")}",
                        artUrl = null,
                        trailingContent = { FavoriteButton(artist) },
                        onClick = { nav.navigate("artist/${artist.backend.id}/${artist.id}") }
                    )
                }
            }
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
        Header(loaded.value?.getOrNull()?.first?.name ?: artistId, nav, trailing = {
            loaded.value?.getOrNull()?.first?.let { FavoriteButton(it) }
        })
        Loaded(loaded.value) { (_, periods) ->
            LazyColumn {
                // Newest first, matching the phish.in years screen.
                items(periods.sortedByDescending { it.label }, key = { it.id }) { period ->
                    RowItem(
                        title = period.label,
                        subtitle = if (period.id == POPULAR_PERIOD_ID) POPULAR_PERIOD_SUBTITLE
                            else "${period.showCount} ${plural(period.showCount, "show")}",
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
    var sortMode by rememberSaveable(periodId) { mutableStateOf(ShowSortMode.DATE_DESC) }
    var selectedTag by rememberSaveable(periodId) { mutableStateOf<String?>("All") }

    val sortOptions = listOf(
        ShowSortMode.DATE_DESC to "Date",
        ShowSortMode.TOP_RATED to "Top rated",
        ShowSortMode.TRENDING_48H to "Trending 48h",
        ShowSortMode.HOT_7D to "Hot 7d",
        ShowSortMode.POPULAR_30D to "Popular 30d",
        ShowSortMode.MOMENTUM to "Momentum",
    )

    Column(Modifier.fillMaxSize()) {
        Header(loaded.value?.getOrNull()?.second?.label ?: periodId, nav)
        Loaded(loaded.value) { (_, _, shows) ->
            val availableTags = remember(shows) {
                val tags = shows.flatMap { it.tags }
                    .distinctBy { it.name.lowercase() }
                    .sortedWith(compareByDescending<TagRef> { it.priority }.thenBy { it.name })
                    .map { it.name }
                if (tags.isNotEmpty()) listOf("All") + tags else emptyList()
            }
            val filteredShows = if (selectedTag.isNullOrBlank() || selectedTag.equals("All", ignoreCase = true)) {
                shows
            } else {
                shows.filterByTag(selectedTag!!)
            }
            val ordered = filteredShows.sortedByMode(sortMode)

            LazyColumn {
                item {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            items(sortOptions, key = { it.first.name }) { (mode, label) ->
                                FilterChip(
                                    selected = sortMode == mode,
                                    onClick = { sortMode = mode },
                                    label = { Text(label) },
                                )
                            }
                        }
                        if (availableTags.size > 1) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 4.dp),
                            ) {
                                items(availableTags, key = { it }) { tag ->
                                    val isSelected = (selectedTag == null && tag == "All") ||
                                        selectedTag.equals(tag, ignoreCase = true)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedTag = if (tag == "All") null else tag },
                                        label = { Text(tag) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (ordered.isEmpty()) {
                    item {
                        Text(
                            "No shows match tag \"$selectedTag\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(ordered, key = { it.date }) { show ->
                    val hasRating = show.rating > 0
                    val tapesLabel = if (show.recordingCount > 1) "${show.recordingCount} tapes" else null
                    val trailingText = when (sortMode) {
                        ShowSortMode.TRENDING_48H -> if (show.hotScore48h > 0) "🔥 ${"%.1f".format(show.hotScore48h)}" else if (hasRating) "★ ${"%.1f".format(show.rating)}" else null
                        ShowSortMode.HOT_7D -> if (show.hotScore7d > 0) "🔥 ${"%.1f".format(show.hotScore7d)}" else if (hasRating) "★ ${"%.1f".format(show.rating)}" else null
                        ShowSortMode.POPULAR_30D -> if (show.hotScore30d > 0) "🔥 ${"%.1f".format(show.hotScore30d)}" else if (hasRating) "★ ${"%.1f".format(show.rating)}" else null
                        ShowSortMode.MOMENTUM -> if (show.momentumScore > 0) "⚡ ${"%.2f".format(show.momentumScore)}" else if (hasRating) "★ ${"%.1f".format(show.rating)}" else null
                        ShowSortMode.TOP_RATED -> if (hasRating) "★ ${"%.1f".format(show.rating)}" else null
                        else -> if (hasRating) "★ ${"%.1f".format(show.rating)}" else tapesLabel
                    }
                    val trailingSecondary = if (trailingText != null && trailingText != tapesLabel) tapesLabel else null

                    RowItem(
                        title = show.date,
                        subtitle = show.where,
                        artUrl = show.artUrl,
                        tags = show.tags,
                        show = show,
                        trailing = trailingText,
                        trailingSecondary = trailingSecondary,
                        onClick = { nav.navigate("recording/$backendId/$artistId/${show.date}") }
                    )
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
        val detail = source.show(artist, date, recordingId)
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
            LazyColumn {
                item { RecordingHeader(detail, backendId, artistId, date, vm, nav) }
                if (progress != null) {
                    item {
                        ResumeBanner(progress) {
                            vm.playRecording(detail, progress.trackIndex, progress.positionMs)
                        }
                    }
                }
                groupedBySet(detail.tracks, { it.setName }, { it.id }) { index, track ->
                    RecordingTrackRow(track, index + 1, detail.summary.artist, date, detail.recording?.id, vm) { vm.playRecording(detail, index, 0) }
                }
            }
        }
    }
}

@Composable
private fun RecordingHeader(detail: ShowDetail, backendId: String, artistId: String, date: String, vm: PlayerViewModel, nav: NavHostController) {
    val summary = detail.summary
    Column {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ShowArtwork(
                show = summary,
                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(summary.artist.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Text(summary.venue.orEmpty(), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(summary.location.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Text("${detail.tracks.size} tracks", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                detail.recording?.let { rec ->
                    Text(recordingLabel(rec), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            ShareButton(showShareText(summary.artist, date))
        }
        val headerTags = detail.recording?.tags?.takeIf { it.isNotEmpty() } ?: summary.tags
        if (headerTags.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
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
    val sources = listOfNotNull(detail.recording) + detail.alternates

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
                    items(sources, key = { it.id }) { source ->
                        val current = source.id == detail.recording?.id
                        SourceRow(source, current) {
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
    }
}

@Composable
private fun SourceRow(source: RecordingRef, current: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (current) "✓ " else "") + source.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
private fun SourceBadge(text: String, color: Color) {
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
fun TagBadge(
    tag: TagRef,
    modifier: Modifier = Modifier,
) {
    val fallbackColor = when (tag.name.uppercase()) {
        "SBD", "SOUNDBOARD" -> MaterialTheme.colorScheme.primary
        "FLAC" -> MaterialTheme.colorScheme.secondary
        "MATRIX", "MATRIX?" -> MaterialTheme.colorScheme.tertiary
        "JAMCHARTS" -> Color(0xFFE91E63)
        "BUSTOUT", "BUSTOUT*" -> Color(0xFFFF9800)
        "GUEST" -> Color(0xFF9C27B0)
        "LORE" -> Color(0xFF009688)
        else -> MaterialTheme.colorScheme.outline
    }
    val badgeColor = parseTagColor(tag.color, fallbackColor)
    Surface(
        color = badgeColor.copy(alpha = 0.18f),
        contentColor = badgeColor,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.padding(end = 4.dp),
    ) {
        Text(
            text = tag.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun parseTagColor(colorStr: String?, defaultColor: Color): Color {
    if (colorStr.isNullOrBlank()) return defaultColor
    return try {
        val hex = colorStr.trim().removePrefix("#")
        val colorInt = when (hex.length) {
            6 -> (0xFF000000 or hex.toLong(16)).toInt()
            8 -> hex.toLong(16).toInt()
            else -> return defaultColor
        }
        Color(colorInt)
    } catch (_: Throwable) {
        defaultColor
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
        LikeTrackButton(track.id)
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

/**
 * Heart toggle for a Relisten [PlayableTrack] (#11), backed by [LikedTracks]. Deliberately
 * separate from phish.in's [LikeButton]: no account gate, no server round-trip, no public
 * count — just a local like.
 */
@Composable
internal fun LikeTrackButton(
    trackId: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
) {
    val likedIds by LikedTracks.ids.collectAsState()
    val liked = trackId in likedIds
    IconButton(onClick = { LikedTracks.toggle(trackId) }, modifier = modifier) {
        Icon(
            if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (liked) "Unlike" else "Like",
            tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Add-to-playlist button for either backend's track row (#12) — [buildRef] is called with
 * `playlistId`/`position` left as placeholders; [PlayerViewModel.addToLocalPlaylist] fills
 * both in. A bottom sheet, matching [SourcePicker]'s reasoning: playlists here can run long
 * enough (and "New playlist" needs its own row) that a [DropdownMenu] would cramp them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistButton(
    vm: PlayerViewModel,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    tint: Color = LocalLedgerColors.current.accentIcon,
    buildRef: () -> LocalPlaylistTrackEntity
) {
    var open by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist", modifier = Modifier.size(iconSize), tint = tint)
    }
    if (open) {
        val playlists by vm.localPlaylistDao.playlists().collectAsState(initial = emptyList())
        ModalBottomSheet(onDismissRequest = { open = false }) {
            LazyColumn {
                item {
                    RowItem("New playlist", "", null) {
                        open = false
                        creating = true
                    }
                }
                items(playlists, key = { it.id }) { playlist ->
                    RowItem(
                        title = playlist.name,
                        subtitle = "${playlist.trackCount} ${plural(playlist.trackCount, "track")}",
                        artUrl = null,
                    ) {
                        open = false
                        vm.addToLocalPlaylist(playlist.id, buildRef())
                    }
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
                    vm.addToLocalPlaylist(id, buildRef())
                }
            },
        )
    }
}

@Composable
internal fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
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
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

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
private fun TourPickerDialog(
    artist: ArtistRef,
    currentPreference: ArtistTourPreferenceEntity?,
    onDismiss: () -> Unit,
    onSave: (tourName: String?, year: String?) -> Unit,
    onClear: () -> Unit,
) {
    val periodsState = loadOnce(artist.key) {
        val src = sourceFor(artist.backend)
        src.periods(artist).filter { it.id != POPULAR_PERIOD_ID }
    }
    var selectedYear by rememberSaveable(currentPreference) { mutableStateOf(currentPreference?.year.orEmpty()) }
    var tourNameInput by rememberSaveable(currentPreference) { mutableStateOf(currentPreference?.tourName.orEmpty()) }
    var mode by rememberSaveable { mutableStateOf(if (currentPreference?.tourName != null && currentPreference.year == null) "tour" else "year") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track tour for ${artist.name}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Choose a historical tour or specific year to track on your Next Stop shelf.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = mode == "year",
                        onClick = { mode = "year" },
                        label = { Text("By Year") }
                    )
                    FilterChip(
                        selected = mode == "tour",
                        onClick = { mode = "tour" },
                        label = { Text("By Tour Name") }
                    )
                }

                if (mode == "year") {
                    Loaded(periodsState.value) { periods ->
                        val validYears = periods.map { it.label }.filter { it.isNotBlank() }
                        Text("Select year:", style = MaterialTheme.typography.labelMedium)
                        LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 240.dp)) {
                            items(validYears) { yr ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedYear = yr }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        yr,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (selectedYear == yr) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (selectedYear == yr) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = tourNameInput,
                        onValueChange = { tourNameInput = it },
                        label = { Text("Tour Name (e.g. Europe '72, Spring 1977)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (selectedYear.isNotBlank()) {
                        Text(
                            "Optional Year filter: $selectedYear",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (mode == "year" && selectedYear.isNotBlank()) || (mode == "tour" && tourNameInput.isNotBlank()),
                onClick = {
                    if (mode == "year") {
                        onSave(null, selectedYear.trim())
                    } else {
                        onSave(tourNameInput.trim(), selectedYear.takeIf { it.isNotBlank() })
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (currentPreference != null) {
                    TextButton(onClick = onClear) { Text("Reset") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
internal fun SearchResultsList(
    results: SearchHits?,
    vm: PlayerViewModel,
    nav: NavHostController,
) {
    if (results == null) {
        Loading()
        return
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>("All") }
    var sortMode by rememberSaveable { mutableStateOf(SearchSortMode.RELEVANCE) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val artistsPresent = results.artistsPresent
    // Selection survives to a different query only by accident of key reuse — clear it once
    // the artist it named is no longer among the hits.
    val selectedArtist = artistsPresent.firstOrNull { "${it.backend.id}/${it.id}" == selected }
    val rArtist = results.filteredTo(selectedArtist)

    val availableTags = remember(rArtist) {
        val showTags = rArtist.shows.flatMap { it.tags }.map { it.name }
        val trackTags = rArtist.tracks.flatMap { it.tags }.map { it.name }
        val all = (showTags + trackTags).distinctBy { it.lowercase() }
        if (all.isNotEmpty()) listOf("All") + all else emptyList()
    }

    val r = remember(rArtist, selectedTag) {
        if (selectedTag.isNullOrBlank() || selectedTag.equals("All", ignoreCase = true)) {
            rArtist
        } else {
            val tag = selectedTag!!
            rArtist.copy(
                shows = rArtist.shows.filterByTag(tag),
                tracks = rArtist.tracks.filter { it.tags.any { t -> t.name.equals(tag, ignoreCase = true) } },
                artists = emptyList(),
                slices = emptyList(),
                playlists = emptyList(),
            )
        }
    }

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
            Spacer(Modifier.height(4.dp))
        }

        if (availableTags.size > 1) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(availableTags, key = { it }) { tag ->
                    val isSelected = (selectedTag == null && tag == "All") ||
                        selectedTag.equals(tag, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedTag = if (tag == "All") null else tag },
                        label = { Text(tag) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // A third stacked chip row here would be too much chrome on top of the artist and
        // tag rows above, so this is one compact line rather than a LazyRow of its own (#91).
        if (r.shows.isNotEmpty() || r.tracks.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box {
                    TextButton(onClick = { sortMenuOpen = true }) {
                        Text("Sort: ${sortMode.label}")
                        Icon(Icons.Default.KeyboardArrowDown, null)
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        SearchSortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = { sortMode = mode; sortMenuOpen = false },
                            )
                        }
                    }
                }
            }
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
                items(r.shows.sortedByMode(sortMode), key = { "show-${it.artist.backend.id}-${it.artist.id}-${it.date}" }) { show ->
                    RowItem(
                        title = show.date,
                        subtitle = listOfNotNull(
                            if (show.artist.backend != Backend.PHISHIN) show.artist.name else null,
                            show.where.ifBlank { null },
                        ).joinToString(" · "),
                        artUrl = show.artUrl,
                        tags = show.tags,
                        show = show,
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
                items(r.tracks.sortedByMode(sortMode), key = { "track-${it.id}" }) { track ->
                    RowItem(
                        title = track.title,
                        subtitle = listOfNotNull(
                            track.showDate, track.venueName, track.venueLocation
                        ).joinToString(" · "),
                        artUrl = track.showAlbumCoverUrl,
                        tags = track.tags.map { it.toTagRef() },
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

/** Pairing and device management for progress sync (D119-D127, QR pairing D145). */
@Composable
fun SyncScreen(vm: PlayerViewModel, nav: NavHostController) {
    val paired by SyncSession.paired.collectAsState()
    var pairingResult by remember { mutableStateOf<PairStartResponse?>(null) }
    var claimCode by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Compose Navigation's standard way to get a result back from a pushed screen: the
    // scanner writes into *this* entry's SavedStateHandle before popping itself off, since
    // it can't hand a return value back through the composable call itself.
    val scannedCode = nav.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow<String?>("scannedCode", null)
        ?.collectAsState()
    LaunchedEffect(scannedCode?.value) {
        scannedCode?.value?.let {
            claimCode = it
            error = null
            nav.currentBackStackEntry?.savedStateHandle?.set("scannedCode", null)
        }
    }

    // Live refresh the device list while this screen is open (#64).
    LaunchedEffect(paired) {
        if (!paired) return@LaunchedEffect
        while (true) {
            delay(5_000)
            refreshKey++
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Sync", nav)
        Text(
            "Sync keeps listening history and resume position in step across your paired " +
                "devices. Pairing is one-time; after that, devices sync on their own.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        val lastError by SyncSession.lastError.collectAsState()
        // Unconditional, not inside `if (paired)`: an auto-unlink on a bad token flips paired
        // to false in the same beat this message is set, so keeping it scoped to the paired
        // block would erase the explanation at exactly the moment it's needed (D172).
        lastError?.let {
            Text(
                it,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (paired) {
            RowItem("This device is paired", "Tap to unlink", null) {
                SyncSession.unlink()
                SyncSession.clearError()
                pairingResult = null
            }

            val syncing by SyncSession.syncing.collectAsState()
            val lastSyncedAt by SyncSession.lastSyncedAt.collectAsState()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (lastSyncedAt == 0L) "Never synced" else "Last synced ${relativeTime(lastSyncedAt)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = !syncing,
                    onClick = { scope.launch { runCatching { SyncSession.sync(vm.progressDao) } } }
                ) { Text(if (syncing) "Syncing…" else "Sync now") }
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
                // White backing behind the QR itself — the surrounding theme is dark, and a
                // QR scanner needs real light/dark contrast, not whatever the app's palette is.
                Surface(color = Color.White, modifier = Modifier.padding(top = 16.dp)) {
                    Image(
                        bitmap = remember(result.code) { qrCodeBitmap(result.code) },
                        contentDescription = "QR code for pairing code ${result.code}",
                        modifier = Modifier.padding(12.dp).size(200.dp)
                    )
                }
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
            TextButton(
                onClick = { nav.navigate("scan") },
                modifier = Modifier.padding(horizontal = 8.dp)
            ) { Text("Scan QR code instead") }
            Button(
                enabled = !busy && claimCode.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { SyncSession.claimPairing(claimCode.trim()) }
                            .onSuccess {
                                claimCode = ""
                                // Sync straight away rather than leaving both devices looking
                                // empty until a later timer fires — see claimPairing's note.
                                runCatching { SyncSession.sync(vm.progressDao) }
                                    .onFailure { error = "Paired, but the first sync failed: ${it.message}" }
                            }
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Devices",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { refreshKey++ }) {
                    Icon(Icons.Default.Refresh, "Refresh devices")
                }
            }
            HorizontalDivider()
            Loaded(devices.value) { list ->
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
            }
        }

        Text(
            "Couch Tour ${BuildConfig.VERSION_NAME}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 16.dp)
        )
    }
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

// ---------------------------------------------------------------- pieces

@Composable
private fun ShowHeader(show: Show, trackCount: Int) {
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
                        text = "★ 4.6",
                        fontSize = 13.sp,
                        color = ledger.ratingAmber,
                        fontWeight = FontWeight.Medium
                    )
                    Text(text = "·", fontSize = 13.sp, color = ledger.textSubtle)
                    Text(
                        text = "$trackCount tracks · $compactDuration",
                        fontSize = 13.sp,
                        color = ledger.textSecondary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text(
                        text = "SBD · Paluska · FLAC",
                        fontSize = 13.sp,
                        color = ledger.textSecondary
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = ledger.textSubtle,
                        modifier = Modifier.size(13.dp)
                    )
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
                    text = "Resume",
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
                    .border(1.dp, ledger.controlOutline, RoundedCornerShape(18.dp))
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    Icons.Default.Bookmark,
                    contentDescription = null,
                    tint = ledger.textSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Saved",
                    fontSize = 13.sp,
                    color = ledger.textSecondary
                )
            }

            // Add pill
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, ledger.controlOutline, RoundedCornerShape(18.dp))
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

            Spacer(Modifier.weight(1f))
            LikeButton(Likable.Show, show.id, show.likedByUser, show.likesCount)
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
        QueueKind.LOCAL_PLAYLIST -> nav.navigate("local-playlist/${ref.id}")
    }
}

private fun openQueue(progress: Progress, nav: NavHostController) =
    openQueueKey(progress.queueKey, nav)

/**
 * One card in the "On this date" row (#13). Same 132dp geometry as [ResumeCard] so the two
 * rows read as siblings, but simpler — no resume button or long-press menu, since tapping is
 * the only action: it opens the show, the same [Backend] dispatch [SurpriseMeButton] uses.
 */
@Composable
private fun AnniversaryCard(show: ShowSummary, nav: NavHostController) {
    Column(
        Modifier
            .width(132.dp)
            .clickable {
                when (show.artist.backend) {
                    Backend.PHISHIN -> nav.navigate("show/${show.date}")
                    Backend.RELISTEN -> nav.navigate("recording/relisten/${show.artist.id}/${show.date}")
                }
            }
    ) {
        ShowArtwork(
            show = show,
            modifier = Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(show.date, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(show.artist.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        show.where.takeIf { it.isNotEmpty() }?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResumeCard(progress: Progress, vm: PlayerViewModel, nav: NavHostController) {
    var menuOpen by remember { mutableStateOf(false) }
    val isPlaylist = parseQueueKey(progress.queueKey)?.kind in setOf(QueueKind.PLAYLIST, QueueKind.LOCAL_PLAYLIST)

    Column(Modifier.width(132.dp)) {
        Box {
            ShowArtwork(
                artUrl = progress.artUrl,
                artistName = progress.artist,
                date = progress.title,
                venue = progress.subtitle,
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
        Text(
            relativeTime(progress.updatedAt),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1
        )
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
                            artistName = p.artist,
                            date = p.title,
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

/**
 * Heart plus count. Owns its own state so a row updates immediately, and rolls back if the
 * request fails rather than showing a like that didn't happen. Signed out it still shows
 * the count, since that's public, but tapping is inert.
 */
@Composable
internal fun LikeButton(
    type: Likable,
    id: Long,
    initiallyLiked: Boolean,
    initialCount: Int,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
) {
    val signedIn by Session.username.collectAsState()
    var liked by remember(id) { mutableStateOf(initiallyLiked) }
    var count by remember(id) { mutableIntStateOf(initialCount) }
    var busy by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
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
            modifier = Modifier.size(iconSize)
        )
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            Text("$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Star toggle for [ArtistRef]s (#14). A star rather than [LikeButton]'s heart, deliberately:
 * favoriting is local-only, works signed out, and applies to both backends, so it shouldn't
 * look like the phish.in-account "like" it has nothing to do with.
 */
@Composable
private fun FavoriteButton(artist: ArtistRef) {
    val favoriteKeys by Favorites.keys.collectAsState()
    val favorited = artist.key in favoriteKeys
    IconButton(onClick = { Favorites.toggle(artist.key) }) {
        Icon(
            if (favorited) Icons.Filled.Star else Icons.Filled.StarBorder,
            if (favorited) "Unfavorite ${artist.name}" else "Favorite ${artist.name}",
            tint = if (favorited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MiniPlayer(state: PlayerState, vm: PlayerViewModel, nav: NavHostController) {
    val ledger = LocalLedgerColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(ledger.cardSurface)
            .border(1.dp, ledger.panelBorder)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { nav.navigate("player") }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!state.artUrl.isNullOrEmpty()) {
                ShowArtwork(
                    artUrl = state.artUrl,
                    contentDescription = "Open ${state.queueTitle}",
                    artistName = state.artistName.ifEmpty { if (state.backend == Backend.PHISHIN.id) PHISH.name else null },
                    date = state.showDate,
                    venue = state.venueName,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                CoverArtPlaceholder(
                    modifier = Modifier.size(40.dp),
                    cornerRadius = 8.dp
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.trackTitle.ifEmpty { "Not Playing" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = ledger.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = when {
                    state.showDate.isNotEmpty() && state.artistName.isNotEmpty() -> "${state.showDate} · ${state.artistName} · ${state.audioFormat}"
                    state.showDate.isNotEmpty() -> "${state.showDate} · ${state.audioFormat}"
                    state.showTitle.isNotEmpty() -> "${state.showTitle} · ${state.audioFormat}"
                    else -> state.queueTitle
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = ledger.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            IconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(40.dp)) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = ledger.textPrimary,
                    )
                } else {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = ledger.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            IconButton(onClick = { vm.next() }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = ledger.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (state.durationMs > 0) {
            val fraction = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(ledger.textPrimary.copy(alpha = 0.12f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(2.dp)
                        .background(ledger.specGradient)
                )
            }
        }
    }
}

@Composable
fun LedgerBottomBar(currentRoute: String?, nav: NavHostController) {
    val ledger = LocalLedgerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ledger.cardSurface)
            .border(1.dp, ledger.panelBorder)
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isHome = currentRoute == "home"
        val isSearch = currentRoute in listOf("artists", "search")
        val isLibrary = currentRoute in listOf("library", "local-playlists", "playlists", "mine/shows", "mine/tracks", "mine/playlists")
        val isSettings = currentRoute in listOf("settings", "sync", "login", "scan")

        BottomNavItem(
            label = "Home",
            icon = Icons.Default.Home,
            selected = isHome,
            onClick = {
                if (!isHome) {
                    nav.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                }
            }
        )
        BottomNavItem(
            label = "Search",
            icon = Icons.Default.Search,
            selected = isSearch,
            onClick = {
                if (!isSearch) {
                    nav.navigate("search")
                }
            }
        )
        BottomNavItem(
            label = "Library",
            icon = Icons.Default.Layers,
            selected = isLibrary,
            onClick = {
                if (!isLibrary) {
                    nav.navigate("library")
                }
            }
        )
        BottomNavItem(
            label = "Settings",
            icon = Icons.Default.Tune,
            selected = isSettings,
            onClick = {
                if (!isSettings) {
                    nav.navigate("settings")
                }
            }
        )
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val ledger = LocalLedgerColors.current
    val color = if (selected) ledger.accentIcon else ledger.textSubtle
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = color
        )
    }
}

@Composable
fun Header(
    title: String,
    nav: NavHostController,
    /** Slot for a per-screen control, e.g. ArtistScreen's favorite star. */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.invoke()
        FeedbackButton(nav)
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
    artUrl: String? = null,
    tags: List<TagRef> = emptyList(),
    trailing: String? = null,
    /** A second, dimmer line under [trailing] — e.g. History's "last played" timestamp. */
    trailingSecondary: String? = null,
    /** Slot for a control that isn't part of the row's own click target, e.g. a heart. */
    trailingContent: (@Composable () -> Unit)? = null,
    show: ShowSummary? = null,
    artistName: String? = null,
    date: String? = null,
    venue: String? = null,
    showArtwork: Boolean = (artUrl != null || show != null || (date != null && artistName != null)),
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
        if (showArtwork) {
            ShowArtwork(
                artUrl = artUrl ?: show?.artUrl,
                show = show,
                artistName = artistName ?: show?.artist?.name,
                date = date ?: show?.date ?: title.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) },
                venue = venue ?: show?.venue ?: subtitle,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    tags.sortedByDescending { it.priority }.take(2).forEach { tag ->
                        TagBadge(tag)
                    }
                }
            }
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (trailing != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(trailing, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (trailingSecondary != null) {
                    Text(trailingSecondary, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        }
        trailingContent?.invoke()
    }
}

@Composable
internal fun Loading() {
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

/** Collapses the load/loading/error scaffold every detail screen repeats: nothing while
 *  [result] is in flight (null), [ErrorText] on failure, [content] once it resolves. */
@Composable
private fun <T> Loaded(result: Result<T>?, content: @Composable (T) -> Unit) {
    when (result) {
        null -> Loading()
        else -> result.fold(onSuccess = { content(it) }, onFailure = { ErrorText(it) })
    }
}

/** [Loaded]'s form for a section embedded in a longer [androidx.compose.foundation.lazy.LazyListScope.item]
 *  list, which can't wrap itself in its own [Loading]/[ErrorText] outside the list. */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.loaded(
    result: Result<T>?,
    content: androidx.compose.foundation.lazy.LazyListScope.(T) -> Unit,
) {
    when (result) {
        null -> item { Loading() }
        else -> result.fold(onSuccess = { content(it) }, onFailure = { item { ErrorText(it) } })
    }
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
    content: @Composable (Int, T) -> Unit,
) {
    items.forEachIndexed { index, item ->
        if (setName(item).isNotEmpty() && (index == 0 || setName(items[index - 1]) != setName(item))) {
            item(key = "set-${setName(item)}-$index") { SectionHeader(setName(item)) }
        }
        item(key = "track-${key(item)}") { content(index, item) }
    }
}

